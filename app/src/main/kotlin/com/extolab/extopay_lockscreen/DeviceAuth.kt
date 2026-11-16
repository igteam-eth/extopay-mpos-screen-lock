package com.extolab.extopay_lockscreen

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.util.Log

/**
 * Asks the user to confirm their screen lock (PIN, password or pattern) with the
 * system prompt — the same check a payment app does before a payment.
 *
 * Android 11+ (API 30): BiometricPrompt with DEVICE_CREDENTIAL.
 * Android 7–10:          KeyguardManager.createConfirmDeviceCredentialIntent().
 *
 * The calling Activity must forward onActivityResult() to [onActivityResult].
 */
class DeviceAuth(private val activity: Activity) {

    fun interface Callback {
        fun onAuthResult(success: Boolean, message: String)
    }

    private val keyguard = activity.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager?
    private var pending: Callback? = null

    fun isAvailable(): Boolean = keyguard?.isDeviceSecure == true

    fun authenticate(title: String, description: String, callback: Callback) {
        if (!isAvailable()) {
            callback.onAuthResult(false, "No screen lock is set on this terminal")
            return
        }

        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val prompt = BiometricPrompt.Builder(activity)
                    .setTitle(title)
                    .setDescription(description)
                    .setAllowedAuthenticators(BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                    .build()
                prompt.authenticate(
                    CancellationSignal(), activity.mainExecutor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            callback.onAuthResult(true, "Verified")
                        }

                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                            callback.onAuthResult(false, errString?.toString() ?: "Cancelled")
                        }
                    })
                return
            } catch (t: Throwable) {
                // e.g. SecurityException (missing USE_BIOMETRIC) or a POS ROM without
                // the biometric service: fall back to the Keyguard prompt below.
                Log.w(TAG, "BiometricPrompt failed, falling back to Keyguard", t)
            }
        }
        confirmWithKeyguard(title, description, callback)
    }

    @Suppress("DEPRECATION")
    private fun confirmWithKeyguard(title: String, description: String, callback: Callback) {
        try {
            val intent = keyguard?.createConfirmDeviceCredentialIntent(title, description)
            if (intent == null) {
                callback.onAuthResult(false, "No screen lock is set on this terminal")
                return
            }
            pending = callback
            activity.startActivityForResult(intent, REQ_CONFIRM_CREDENTIAL)
        } catch (t: Throwable) {
            Log.e(TAG, "Keyguard confirm failed", t)
            pending = null
            callback.onAuthResult(
                false,
                "This terminal can't show the screen-lock prompt (${t.javaClass.simpleName})"
            )
        }
    }

    /** @return true if the result belonged to this helper. */
    fun onActivityResult(requestCode: Int, resultCode: Int): Boolean {
        if (requestCode != REQ_CONFIRM_CREDENTIAL) return false
        val cb = pending
        pending = null
        val ok = resultCode == Activity.RESULT_OK
        cb?.onAuthResult(ok, if (ok) "Verified" else "Cancelled")
        return true
    }

    private companion object {
        const val TAG = "DeviceAuth"
        const val REQ_CONFIRM_CREDENTIAL = 0x4155
    }
}
