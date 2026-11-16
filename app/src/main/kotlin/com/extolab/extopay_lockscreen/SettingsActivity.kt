package com.extolab.extopay_lockscreen

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.cloudpos.DeviceException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Exto "Security" settings page:
 *  - Status card (None / PIN is on / Set outside this app)
 *  - PIN: Set up PIN, Change PIN
 *  - Authenticate: verify the screen lock with the system prompt (as a payment would)
 *  - Remove screen lock: confirm the current lock, then turn it off through the SDK
 *  - Lock screen: message and notification policy
 */
class SettingsActivity : Activity() {

    private lateinit var store: PinStore
    private lateinit var lockManager: LockScreenManager
    private lateinit var deviceAuth: DeviceAuth
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var ivStatus: ImageView
    private lateinit var tvStatus: TextView
    private lateinit var tvStatusDetail: TextView
    private lateinit var tvMessage: TextView
    private lateinit var tvNotifications: TextView
    private lateinit var tvAuthSummary: TextView
    private lateinit var tvAuthResult: TextView
    private lateinit var rowSetup: View
    private lateinit var rowChange: View
    private lateinit var rowAuth: View
    private lateinit var ivAuthChevron: View
    private lateinit var headerRemove: View
    private lateinit var cardRemove: View
    private lateinit var tvRemoveNote: View

    private var lockScreenMessage = ""
    private var notificationFlag = LockScreenManager.NOTIFICATION_SENSITIVE_ONLY

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        store = PinStore(this)
        lockManager = LockScreenManager(this)
        deviceAuth = DeviceAuth(this)

        ivStatus = findViewById(R.id.ivStatus)
        tvStatus = findViewById(R.id.tvStatus)
        tvStatusDetail = findViewById(R.id.tvStatusDetail)
        tvMessage = findViewById(R.id.tvMessage)
        tvNotifications = findViewById(R.id.tvNotifications)
        rowSetup = findViewById(R.id.rowSetup)
        rowChange = findViewById(R.id.rowChange)
        rowAuth = findViewById(R.id.rowAuth)
        ivAuthChevron = findViewById(R.id.ivAuthChevron)
        tvAuthSummary = findViewById(R.id.tvAuthSummary)
        tvAuthResult = findViewById(R.id.tvAuthResult)
        headerRemove = findViewById(R.id.headerRemove)
        cardRemove = findViewById(R.id.cardRemove)
        tvRemoveNote = findViewById(R.id.tvRemoveNote)

        rowSetup.setOnClickListener { openPin(PinActivity.Mode.SETUP) }
        rowChange.setOnClickListener { openPin(PinActivity.Mode.CHANGE) }
        rowAuth.setOnClickListener { testAuthentication() }
        findViewById<View>(R.id.rowRemove).setOnClickListener { removeScreenLock() }
        findViewById<View>(R.id.rowMessage).setOnClickListener { editMessage() }
        findViewById<View>(R.id.rowNotifications).setOnClickListener { chooseNotifications() }
    }

    override fun onResume() {
        super.onResume()
        renderStatus()
        loadLockScreenInfo()
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (deviceAuth.onActivityResult(requestCode, resultCode)) return
        if (requestCode == REQ_PIN && resultCode == RESULT_OK && data != null) {
            data.getStringExtra(PinActivity.EXTRA_RESULT_MESSAGE)?.let { toast(it) }
        }
    }

    @Suppress("DEPRECATION")
    private fun openPin(mode: PinActivity.Mode) {
        startActivityForResult(PinActivity.intent(this, mode), REQ_PIN)
    }

    // ------------------------------------------------------------------
    // Status card + PIN rows
    // ------------------------------------------------------------------

    private fun renderStatus() {
        var appPin = store.hasPin()
        val secure = store.isDeviceSecure()

        // PIN was removed in Android Settings (or never really applied): forget it.
        if (appPin && !secure) {
            store.clearPin()
            appPin = false
        }

        when {
            appPin -> {
                setPill("PIN is on", R.color.success, R.color.success_soft)
                tvStatusDetail.text = "The terminal asks for the PIN when it wakes up."
                ivStatus.setImageResource(R.drawable.ic_lock)
            }
            secure -> {
                setPill("Set outside this app", R.color.warning, R.color.warning_soft)
                tvStatusDetail.text = "A screen lock was set in Android Settings or by another app. " +
                        "You can change or remove it here, but the app can't check the current PIN."
                ivStatus.setImageResource(R.drawable.ic_lock)
            }
            else -> {
                setPill("None", R.color.text_secondary, R.color.key_bg)
                tvStatusDetail.text = "Anyone can unlock this terminal."
                ivStatus.setImageResource(R.drawable.ic_lock_open)
            }
        }

        val locked = appPin || secure
        rowSetup.visibility = if (locked) View.GONE else View.VISIBLE
        rowChange.visibility = if (locked) View.VISIBLE else View.GONE

        // Authenticate needs a screen lock to check against.
        rowAuth.isEnabled = secure
        rowAuth.alpha = if (secure) 1f else 0.5f
        ivAuthChevron.visibility = if (secure) View.VISIBLE else View.GONE
        tvAuthSummary.text = if (secure)
            "Confirm it's you with the terminal's screen lock, the same check used before a payment"
        else
            "Set up a PIN first. Payments that need a screen-lock check won't work without one."
        if (!secure) tvAuthResult.visibility = View.GONE

        // Remove section only when there is something to remove.
        val removeVis = if (secure) View.VISIBLE else View.GONE
        headerRemove.visibility = removeVis
        cardRemove.visibility = removeVis
        tvRemoveNote.visibility = removeVis
    }

    // ------------------------------------------------------------------
    // Authenticate
    // ------------------------------------------------------------------

    private fun testAuthentication() {
        deviceAuth.authenticate("Verify it's you", "Enter the terminal's screen lock") { success, message ->
            val time = SimpleDateFormat("HH:mm", Locale.US).format(Date())
            showAuthResult(
                success,
                if (success) "✓ Verified at $time" else "✗ Not verified at $time · $message"
            )
        }
    }

    private fun showAuthResult(success: Boolean, text: String) {
        tvAuthResult.visibility = View.VISIBLE
        tvAuthResult.text = text
        tvAuthResult.setTextColor(color(if (success) R.color.success else R.color.danger))
        tvAuthResult.background = pill(if (success) R.color.success_soft else R.color.danger_soft)
    }

    // ------------------------------------------------------------------
    // Remove screen lock
    // ------------------------------------------------------------------

    private fun removeScreenLock() {
        if (!store.isDeviceSecure()) {
            toast("No screen lock to remove")
            renderStatus()
            return
        }
        if (store.hasPin()) {
            // PIN set by this app: enter it on our PIN pad (checked against the saved
            // hash), confirm, then setPinLock(false, pin).
            openPin(PinActivity.Mode.REMOVE)
            return
        }
        // Lock set outside this app (could be PIN, password or pattern):
        // confirm with the system prompt first.
        deviceAuth.authenticate("Remove screen lock", "Confirm your current screen lock to remove it") { success, message ->
            if (success) confirmRemoveAnyLock() else toast("Not removed: $message")
        }
    }

    private fun confirmRemoveAnyLock() {
        AlertDialog.Builder(this)
            .setTitle("Remove screen lock?")
            .setMessage("Anyone who picks up this terminal will be able to unlock it.")
            .setPositiveButton("Remove") { _, _ -> removeAnyLock() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * The lock type isn't known (and the app doesn't have the credential), so try each
     * SDK disable call until KeyguardManager reports the device is no longer secure.
     */
    private fun removeAnyLock() {
        executor.execute {
            var lastError: String? = null
            for (type in LockScreenManager.LockType.values()) {
                try {
                    lockManager.disableLock(type, "")
                } catch (e: DeviceException) {
                    lastError = "code ${e.code}"
                } catch (t: Throwable) {
                    lastError = t.message
                }
                if (!store.isDeviceSecure()) break
            }
            val removed = !store.isDeviceSecure()
            val err = lastError
            handler.post {
                if (isFinishing) return@post
                if (removed) {
                    store.clearPin()
                    toast("Screen lock removed")
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("Couldn't remove screen lock")
                        .setMessage(
                            "The terminal kept its screen lock" +
                                    (if (err != null) " ($err)" else "") + ". " +
                                    "Remove it in Android Settings → Security, or ask WizarPOS " +
                                    "which value the SDK needs to turn it off."
                        )
                        .setPositiveButton("OK", null)
                        .show()
                }
                renderStatus()
            }
        }
    }

    private fun setPill(text: String, textColor: Int, bgColor: Int) {
        tvStatus.text = text
        tvStatus.setTextColor(color(textColor))
        tvStatus.background = pill(bgColor)
    }

    private fun pill(bgColor: Int) = GradientDrawable().apply {
        cornerRadius = 100 * resources.displayMetrics.density
        setColor(color(bgColor))
    }

    @Suppress("DEPRECATION")
    private fun color(id: Int): Int = resources.getColor(id)

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------------
    // Lock-screen message + notifications
    // ------------------------------------------------------------------

    private fun loadLockScreenInfo() {
        executor.execute {
            val text = runCatching { lockManager.getLockScreenText() }.getOrNull()
            val flag = runCatching { lockManager.getLockScreenNotification() }.getOrNull()
            handler.post {
                if (isFinishing) return@post
                if (text != null) lockScreenMessage = text
                tvMessage.text = when {
                    text == null -> "Not available on this terminal"
                    text.isEmpty() -> "None"
                    else -> text
                }
                if (flag != null) notificationFlag = flag
                tvNotifications.text = if (flag == null) "Not available on this terminal" else labelFor(flag)
            }
        }
    }

    private fun editMessage() {
        val et = EditText(this).apply {
            setText(lockScreenMessage)
            setSelection(text.length)
            hint = "e.g. ExtoPay · Terminal 001"
            filters = arrayOf(InputFilter.LengthFilter(80))
            setSingleLine(true)
        }
        val pad = (20 * resources.displayMetrics.density).roundToInt()
        val box = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(et)
        }

        AlertDialog.Builder(this)
            .setTitle("Lock screen message")
            .setMessage("Shown on the lock screen, for example the shop name or a support number.")
            .setView(box)
            .setPositiveButton("Save") { _, _ -> saveMessage(et.text.toString().trim()) }
            .setNeutralButton("Clear") { _, _ -> saveMessage("") }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveMessage(text: String) {
        runSdk(if (text.isEmpty()) "Message cleared" else "Message saved") {
            lockManager.setLockScreenText(text)
        }
    }

    private fun chooseNotifications() {
        AlertDialog.Builder(this)
            .setTitle("Notifications on lock screen")
            .setSingleChoiceItems(NOTIFICATION_OPTIONS, notificationFlag - 1) { d, which ->
                d.dismiss()
                val flag = which + 1
                runSdk("Saved") { lockManager.setLockScreenNotification(flag) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun runSdk(okMessage: String, call: () -> Unit) {
        executor.execute {
            val err: String? = try {
                call()
                null
            } catch (e: DeviceException) {
                "Failed (code ${e.code})"
            } catch (t: Throwable) {
                "Failed: ${t.message}"
            }
            handler.post {
                if (isFinishing) return@post
                toast(err ?: okMessage)
                loadLockScreenInfo()
            }
        }
    }

    private companion object {
        const val REQ_PIN = 1

        val NOTIFICATION_OPTIONS = arrayOf(
            "Show all notifications",
            "Hide all notifications",
            "Hide sensitive content"
        )

        fun labelFor(flag: Int): String =
            if (flag in 1..3) NOTIFICATION_OPTIONS[flag - 1] else "Unknown ($flag)"
    }
}
