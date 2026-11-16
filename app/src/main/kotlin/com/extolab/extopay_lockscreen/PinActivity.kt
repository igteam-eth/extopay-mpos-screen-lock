package com.extolab.extopay_lockscreen

import android.animation.ObjectAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.cloudpos.DeviceException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Full-screen PIN pad used for:
 *  - SETUP:  Enter new PIN → Re-enter PIN → setPinLock(true, pin)
 *  - CHANGE: Enter current PIN → Enter new PIN → Re-enter PIN → setPinLock(true, newPin)
 *  - REMOVE: Enter current PIN → confirm dialog → setPinLock(false, currentPin)
 */
class PinActivity : Activity() {

    enum class Mode { SETUP, CHANGE, REMOVE }

    private enum class Step { CURRENT, NEW, CONFIRM }

    private lateinit var mode: Mode
    private val steps = mutableListOf<Step>()
    private var step: Step? = null

    private val input = StringBuilder()
    private var currentPin: String? = null
    private var newPin: String? = null

    private lateinit var store: PinStore
    private lateinit var lockManager: LockScreenManager
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var tvToolbarTitle: TextView
    private lateinit var tvStep: TextView
    private lateinit var tvTitle: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var tvError: TextView
    private lateinit var tvBusy: TextView
    private lateinit var ivIcon: ImageView
    private lateinit var dots: LinearLayout
    private lateinit var stepBar: LinearLayout
    private lateinit var keypad: LinearLayout
    private lateinit var busy: View
    private lateinit var kOk: View

    private var keypadEnabled = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No screenshots / screen recording of the PIN screen.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_pin)

        store = PinStore(this)
        lockManager = LockScreenManager(this)

        mode = intent.getStringExtra(EXTRA_MODE)?.let { Mode.valueOf(it) } ?: Mode.SETUP
        steps += when (mode) {
            Mode.SETUP -> listOf(Step.NEW, Step.CONFIRM)
            Mode.CHANGE -> listOf(Step.CURRENT, Step.NEW, Step.CONFIRM)
            Mode.REMOVE -> listOf(Step.CURRENT)
        }

        tvToolbarTitle = findViewById(R.id.tvToolbarTitle)
        tvStep = findViewById(R.id.tvStep)
        tvTitle = findViewById(R.id.tvTitle)
        tvSubtitle = findViewById(R.id.tvSubtitle)
        tvError = findViewById(R.id.tvError)
        tvBusy = findViewById(R.id.tvBusy)
        ivIcon = findViewById(R.id.ivIcon)
        dots = findViewById(R.id.dots)
        stepBar = findViewById(R.id.stepBar)
        keypad = findViewById(R.id.keypad)
        busy = findViewById(R.id.busy)
        kOk = findViewById(R.id.kOk)

        tvToolbarTitle.text = when (mode) {
            Mode.SETUP -> "Set up PIN"
            Mode.CHANGE -> "Change PIN"
            Mode.REMOVE -> "Remove screen lock"
        }

        val digitIds = intArrayOf(
            R.id.k0, R.id.k1, R.id.k2, R.id.k3, R.id.k4,
            R.id.k5, R.id.k6, R.id.k7, R.id.k8, R.id.k9
        )
        digitIds.forEachIndexed { i, id ->
            val digit = '0' + i
            findViewById<View>(id).setOnClickListener { v ->
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onDigit(digit)
            }
        }
        findViewById<View>(R.id.kDel).apply {
            setOnClickListener { v ->
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onDelete()
            }
            setOnLongClickListener {
                input.setLength(0)
                renderDots(false)
                true
            }
        }
        kOk.setOnClickListener { submit() }
        findViewById<View>(R.id.btnBack).setOnClickListener {
            @Suppress("DEPRECATION")
            onBackPressed()
        }

        goTo(steps.first(), null)
    }

    override fun onResume() {
        super.onResume()
        if (step == Step.CURRENT) startLockoutCountdownIfNeeded()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
        wipe()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (busy.visibility == View.VISIBLE) return
        val i = steps.indexOf(step)
        if (i > 0) {
            goTo(steps[i - 1], null)
        } else {
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    /** Physical keypad support (many POS terminals have one). */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (busy.visibility != View.VISIBLE) {
            when (keyCode) {
                in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                    onDigit('0' + (keyCode - KeyEvent.KEYCODE_0)); return true
                }
                in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> {
                    onDigit('0' + (keyCode - KeyEvent.KEYCODE_NUMPAD_0)); return true
                }
                KeyEvent.KEYCODE_DEL -> {
                    onDelete(); return true
                }
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    submit(); return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ------------------------------------------------------------------
    // Steps
    // ------------------------------------------------------------------

    private fun goTo(next: Step, error: String?) {
        step = next
        input.setLength(0)
        if (next == Step.NEW) newPin = null
        if (next == Step.CURRENT) currentPin = null

        when (next) {
            Step.CURRENT -> {
                ivIcon.setImageResource(R.drawable.ic_lock)
                tvTitle.text = "Enter current PIN"
                tvSubtitle.text = if (store.hasPin())
                    "Enter the PIN you use to unlock this terminal"
                else
                    "This PIN was set outside the app, so it can't be checked here.\nEnter the PIN set on the terminal."
            }
            Step.NEW -> {
                ivIcon.setImageResource(R.drawable.ic_pin)
                tvTitle.text = if (mode == Mode.SETUP) "Choose a PIN" else "Enter new PIN"
                tvSubtitle.text = "Use ${PinStore.MIN_LENGTH}–${PinStore.MAX_LENGTH} digits"
            }
            Step.CONFIRM -> {
                ivIcon.setImageResource(R.drawable.ic_shield)
                tvTitle.text = "Re-enter PIN"
                tvSubtitle.text = "Enter the same PIN again to confirm"
            }
        }

        val index = steps.indexOf(next)
        tvStep.text = if (steps.size > 1) "Step ${index + 1} of ${steps.size}" else ""
        renderStepBar(index)
        showError(error)
        renderDots(false)
        if (next == Step.CURRENT) startLockoutCountdownIfNeeded()
    }

    private fun onDigit(d: Char) {
        if (!keypadEnabled()) return
        if (input.length >= PinStore.MAX_LENGTH) return
        input.append(d)
        if (tvError.text.isNotEmpty()) showError(null)
        renderDots(false)
        // Re-enter step: submit as soon as the length matches.
        val target = newPin
        if (step == Step.CONFIRM && target != null && input.length == target.length) {
            handler.postDelayed({ if (step == Step.CONFIRM) submit() }, 150)
        }
    }

    private fun onDelete() {
        if (!keypadEnabled() || input.isEmpty()) return
        input.setLength(input.length - 1)
        renderDots(false)
    }

    private fun submit() {
        if (!keypadEnabled()) return
        val value = input.toString()
        if (value.length < PinStore.MIN_LENGTH) {
            fail("PIN must be at least ${PinStore.MIN_LENGTH} digits")
            return
        }

        when (step) {
            Step.CURRENT -> {
                if (store.verify(value)) {
                    currentPin = value
                    if (mode == Mode.REMOVE) confirmRemove() else goTo(Step.NEW, null)
                } else if (store.lockoutRemainingMs() > 0) {
                    fail(null)
                    startLockoutCountdownIfNeeded()
                } else {
                    val left = store.attemptsLeft()
                    fail("Wrong PIN. $left ${if (left == 1) "attempt" else "attempts"} left")
                }
            }
            Step.NEW -> {
                if (mode == Mode.CHANGE && value == currentPin) {
                    fail("New PIN must be different from the current PIN")
                    return
                }
                newPin = value
                goTo(Step.CONFIRM, null)
            }
            Step.CONFIRM -> {
                if (value != newPin) {
                    shake()
                    goTo(Step.NEW, "PINs didn't match. Try again.")
                    return
                }
                apply()
            }
            null -> Unit
        }
    }

    private fun confirmRemove() {
        AlertDialog.Builder(this)
            .setTitle("Remove screen lock?")
            .setMessage("Anyone who picks up this terminal will be able to unlock it.")
            .setPositiveButton("Remove") { _, _ -> apply() }
            .setNegativeButton("Cancel") { _, _ ->
                setResult(RESULT_CANCELED)
                finish()
            }
            .setCancelable(false)
            .show()
    }

    // ------------------------------------------------------------------
    // SDK call
    // ------------------------------------------------------------------

    private fun apply() {
        showBusy(
            when (mode) {
                Mode.SETUP -> "Setting PIN…"
                Mode.CHANGE -> "Changing PIN…"
                Mode.REMOVE -> "Removing screen lock…"
            }
        )
        val oldPin = currentPin
        val pin = newPin

        executor.execute {
            val error: String? = try {
                when (mode) {
                    Mode.SETUP -> lockManager.enableLock(LockScreenManager.LockType.PIN, pin!!)
                    Mode.CHANGE -> try {
                        // Try to replace the PIN directly first.
                        lockManager.enableLock(LockScreenManager.LockType.PIN, pin!!)
                    } catch (first: DeviceException) {
                        // Some firmware may need the old lock removed first.
                        lockManager.disableLock(LockScreenManager.LockType.PIN, oldPin)
                        lockManager.enableLock(LockScreenManager.LockType.PIN, pin!!)
                    }
                    Mode.REMOVE -> lockManager.disableLock(LockScreenManager.LockType.PIN, oldPin)
                }
                null
            } catch (e: DeviceException) {
                "The terminal returned an error (code ${e.code})." +
                        (e.message?.let { "\n$it" } ?: "")
            } catch (e: SecurityException) {
                "Permission denied. The app needs android.permission.CREATE_USERS " +
                        "and must be signed for this terminal."
            } catch (t: Throwable) {
                "${t.javaClass.simpleName}: ${t.message}"
            }

            handler.post {
                if (isFinishing) return@post
                if (error == null) onApplied() else onApplyFailed(error)
            }
        }
    }

    private fun onApplied() {
        val message = if (mode == Mode.REMOVE) {
            store.clearPin()
            "Screen lock removed"
        } else {
            store.savePin(newPin!!)
            if (mode == Mode.SETUP) "PIN set" else "PIN changed"
        }
        wipe()
        setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT_MESSAGE, message))
        finish()
    }

    private fun onApplyFailed(error: String) {
        hideBusy()
        AlertDialog.Builder(this)
            .setTitle(
                when (mode) {
                    Mode.REMOVE -> "Couldn't remove screen lock"
                    Mode.CHANGE -> "Couldn't change PIN"
                    Mode.SETUP -> "Couldn't set PIN"
                }
            )
            .setMessage(error)
            .setPositiveButton("Try again") { _, _ -> apply() }
            .setNegativeButton("Cancel") { _, _ ->
                setResult(RESULT_CANCELED)
                finish()
            }
            .setCancelable(false)
            .show()
    }

    // ------------------------------------------------------------------
    // Lockout after too many wrong PINs
    // ------------------------------------------------------------------

    private val lockoutTick: Runnable = object : Runnable {
        override fun run() {
            val ms = store.lockoutRemainingMs()
            if (ms > 0 && step == Step.CURRENT) {
                setKeypadEnabled(false)
                showError("Too many attempts. Try again in ${(ms + 999) / 1000}s")
                handler.postDelayed(this, 500)
            } else {
                setKeypadEnabled(true)
                if (step == Step.CURRENT) showError(null)
            }
        }
    }

    private fun startLockoutCountdownIfNeeded() {
        handler.removeCallbacks(lockoutTick)
        if (store.lockoutRemainingMs() > 0) {
            handler.post(lockoutTick)
        } else {
            setKeypadEnabled(true)
        }
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private fun renderDots(error: Boolean) {
        dots.removeAllViews()
        val len = input.length
        val target = newPin
        val slots = if (step == Step.CONFIRM && target != null) target.length
        else max(PinStore.MIN_LENGTH, len)
        val size = dp(14f)
        val gap = dp(if (slots > 8) 6f else 9f)
        for (i in 0 until slots) {
            val dot = View(this)
            dot.layoutParams = LinearLayout.LayoutParams(size, size).apply {
                setMargins(gap, 0, gap, 0)
            }
            dot.setBackgroundResource(
                when {
                    error -> R.drawable.dot_error
                    i < len -> R.drawable.dot_filled
                    else -> R.drawable.dot_empty
                }
            )
            dots.addView(dot)
        }
        kOk.isEnabled = keypadEnabled() && len >= PinStore.MIN_LENGTH
        dots.contentDescription = "$len digits entered"
    }

    private fun renderStepBar(current: Int) {
        stepBar.removeAllViews()
        if (steps.size < 2) return
        for (i in steps.indices) {
            val seg = View(this)
            seg.layoutParams = LinearLayout.LayoutParams(0, dp(4f), 1f).apply {
                setMargins(if (i == 0) 0 else dp(3f), 0, if (i == steps.size - 1) 0 else dp(3f), 0)
            }
            seg.background = GradientDrawable().apply {
                cornerRadius = dp(2f).toFloat()
                @Suppress("DEPRECATION")
                setColor(resources.getColor(if (i <= current) R.color.primary else R.color.divider))
            }
            stepBar.addView(seg)
        }
    }

    private fun fail(message: String?) {
        shake()
        input.setLength(0)
        renderDots(true)
        handler.postDelayed({ renderDots(false) }, 450)
        if (message != null) showError(message)
    }

    private fun shake() {
        dots.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val d = dp(10f).toFloat()
        ObjectAnimator.ofFloat(dots, "translationX", 0f, d, -d, d * 0.8f, -d * 0.8f, d * 0.4f, -d * 0.4f, 0f)
            .setDuration(400)
            .start()
    }

    private fun showError(message: String?) {
        tvError.text = message ?: ""
    }

    private fun showBusy(text: String) {
        tvBusy.text = text
        busy.visibility = View.VISIBLE
    }

    private fun hideBusy() {
        busy.visibility = View.GONE
    }

    private fun keypadEnabled(): Boolean = keypadEnabled && busy.visibility != View.VISIBLE

    private fun setKeypadEnabled(enabled: Boolean) {
        keypadEnabled = enabled
        keypad.alpha = if (enabled) 1f else 0.35f
        kOk.isEnabled = enabled && input.length >= PinStore.MIN_LENGTH
    }

    private fun wipe() {
        input.setLength(0)
        currentPin = null
        newPin = null
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).roundToInt()

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_RESULT_MESSAGE = "result_message"

        @JvmStatic
        fun intent(context: Context, mode: Mode): Intent =
            Intent(context, PinActivity::class.java).putExtra(EXTRA_MODE, mode.name)
    }
}
