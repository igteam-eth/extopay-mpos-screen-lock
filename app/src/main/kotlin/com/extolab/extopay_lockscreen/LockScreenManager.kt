package com.extolab.extopay_lockscreen

import android.content.Context
import com.cloudpos.DeviceException
import com.cloudpos.advance.ext.POSTerminalAdvance
import com.cloudpos.advance.ext.system.ISystemDevice

/**
 * Wrapper around the CloudPOS SDK (v1.8.3.13+) screen-lock APIs in [ISystemDevice]:
 *  - setPinLock(enable, pin)
 *  - setPasswordLock(enable, password)
 *  - setPatternLock(enable, pattern)
 *  - setTextOnLockScreen / getTextOnLockScreen
 *  - setLockScreenShowNotification / getLockScreenShowNotification
 *
 * Every method opens the system device, does its work and closes it again.
 * `ISystemDevice.open()` is synchronous, so never call these methods on the
 * main (UI) thread.
 */
class LockScreenManager(context: Context) {

    enum class LockType { PIN, PASSWORD, PATTERN }

    private val context: Context = context.applicationContext

    // ------------------------------------------------------------------
    // Screen lock
    // ------------------------------------------------------------------

    /**
     * Turns on a screen lock of the given type.
     *
     * @param credential PIN ("1234"), password ("abcd1234") or pattern ("14789").
     */
    @Throws(DeviceException::class)
    fun enableLock(type: LockType, credential: String) {
        validate(type, credential)
        setLock(type, true, credential)
    }

    /**
     * Turns the screen lock off (the device goes back to "None"/"Swipe").
     *
     * @param currentCredential the credential that is set now. The SDK docs do not say
     * whether this is checked when disabling, so pass the current value when you know
     * it; an empty string is sent otherwise.
     */
    @Throws(DeviceException::class)
    fun disableLock(type: LockType, currentCredential: String?) {
        setLock(type, false, currentCredential ?: "")
    }

    private fun setLock(type: LockType, enable: Boolean, value: String) = withDevice { device ->
        when (type) {
            LockType.PIN -> device.setPinLock(enable, value)
            LockType.PASSWORD -> device.setPasswordLock(enable, value)
            LockType.PATTERN -> device.setPatternLock(enable, value)
        }
    }

    // ------------------------------------------------------------------
    // Lock-screen text and notification policy
    // ------------------------------------------------------------------

    /** Shows [text] on the lock screen; an empty string clears it. */
    @Throws(DeviceException::class)
    fun setLockScreenText(text: String?) = withDevice { it.setTextOnLockScreen(text ?: "") }

    @Throws(DeviceException::class)
    fun getLockScreenText(): String? = withDevice { it.textOnLockScreen }

    /** @param flag [NOTIFICATION_ALL], [NOTIFICATION_NONE] or [NOTIFICATION_SENSITIVE_ONLY]. */
    @Throws(DeviceException::class)
    fun setLockScreenNotification(flag: Int) = withDevice { it.setLockScreenShowNotification(flag) }

    @Throws(DeviceException::class)
    fun getLockScreenNotification(): Int = withDevice { it.lockScreenShowNotification }

    // ------------------------------------------------------------------
    // Open / close helper
    // ------------------------------------------------------------------

    private fun <T> withDevice(action: (ISystemDevice) -> T): T {
        val device = POSTerminalAdvance.getInstance().systemDevice
            ?: throw IllegalStateException("ISystemDevice not available on this terminal.")
        var openedHere = false
        try {
            if (!device.isOpened) {
                device.open(context)
                openedHere = true
            }
            return action(device)
        } finally {
            if (openedHere) {
                try {
                    device.close()
                } catch (ignored: DeviceException) {
                    // already closed
                }
            }
        }
    }

    companion object {
        /** Lock-screen notification visibility values from the SDK docs. */
        const val NOTIFICATION_ALL = 1
        const val NOTIFICATION_NONE = 2
        const val NOTIFICATION_SENSITIVE_ONLY = 3

        /** Throws IllegalArgumentException with a readable message when the credential is invalid. */
        @JvmStatic
        fun validate(type: LockType, value: String?) {
            val v = value ?: ""
            when (type) {
                // "must be at least 4 digits (numeric only)"
                LockType.PIN -> require(v.matches(Regex("\\d{4,}"))) {
                    "PIN must be at least 4 digits, numbers only."
                }
                // "must be at least 4 characters and may contain letters and numbers"
                LockType.PASSWORD -> require(v.matches(Regex("[A-Za-z0-9]{4,}"))) {
                    "Password must be at least 4 characters, letters and numbers only."
                }
                // "4 to 16 connected points in a 3x3 grid", digits 1..9, no repeats
                //   1 2 3
                //   4 5 6
                //   7 8 9
                LockType.PATTERN -> {
                    require(v.matches(Regex("[1-9]{4,16}"))) {
                        "Pattern must be 4 or more points, using digits 1-9 (e.g. 14789 = L shape)."
                    }
                    val seen = HashSet<Char>()
                    for (c in v) {
                        require(seen.add(c)) { "Pattern points cannot repeat ($c is used twice)." }
                    }
                }
            }
        }
    }
}
