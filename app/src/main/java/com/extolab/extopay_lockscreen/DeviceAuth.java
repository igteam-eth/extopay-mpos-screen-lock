package com.extolab.extopay_lockscreen;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.CancellationSignal;
import android.util.Log;

/**
 * Asks the user to confirm their screen lock (PIN, password or pattern) with the
 * system prompt — the same check a payment app does before a payment.
 *
 * Android 11+ (API 30): BiometricPrompt with DEVICE_CREDENTIAL.
 * Android 7–10:          KeyguardManager.createConfirmDeviceCredentialIntent().
 *
 * The calling Activity must forward onActivityResult() to {@link #onActivityResult}.
 */
public class DeviceAuth {

    public interface Callback {
        void onAuthResult(boolean success, String message);
    }

    private static final String TAG = "DeviceAuth";
    private static final int REQ_CONFIRM_CREDENTIAL = 0x4155;

    private final Activity activity;
    private final KeyguardManager keyguard;
    private Callback pending;

    public DeviceAuth(Activity activity) {
        this.activity = activity;
        this.keyguard = (KeyguardManager) activity.getSystemService(Context.KEYGUARD_SERVICE);
    }

    public boolean isAvailable() {
        return keyguard != null && keyguard.isDeviceSecure();
    }

    public void authenticate(String title, String description, final Callback callback) {
        if (!isAvailable()) {
            callback.onAuthResult(false, "No screen lock is set on this terminal");
            return;
        }

        if (Build.VERSION.SDK_INT >= 30) {
            try {
                BiometricPrompt prompt = new BiometricPrompt.Builder(activity)
                        .setTitle(title)
                        .setDescription(description)
                        .setAllowedAuthenticators(BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                        .build();
                prompt.authenticate(new CancellationSignal(), activity.getMainExecutor(),
                        new BiometricPrompt.AuthenticationCallback() {
                            @Override
                            public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                                callback.onAuthResult(true, "Verified");
                            }

                            @Override
                            public void onAuthenticationError(int errorCode, CharSequence errString) {
                                callback.onAuthResult(false, errString == null ? "Cancelled" : errString.toString());
                            }
                        });
                return;
            } catch (Throwable t) {
                // e.g. SecurityException (missing USE_BIOMETRIC) or a POS ROM without
                // the biometric service: fall back to the Keyguard prompt below.
                Log.w(TAG, "BiometricPrompt failed, falling back to Keyguard", t);
            }
        }
        confirmWithKeyguard(title, description, callback);
    }

    @SuppressWarnings("deprecation")
    private void confirmWithKeyguard(String title, String description, Callback callback) {
        try {
            Intent intent = keyguard.createConfirmDeviceCredentialIntent(title, description);
            if (intent == null) {
                callback.onAuthResult(false, "No screen lock is set on this terminal");
                return;
            }
            pending = callback;
            activity.startActivityForResult(intent, REQ_CONFIRM_CREDENTIAL);
        } catch (Throwable t) {
            Log.e(TAG, "Keyguard confirm failed", t);
            pending = null;
            callback.onAuthResult(false, "This terminal can't show the screen-lock prompt ("
                    + t.getClass().getSimpleName() + ")");
        }
    }

    /** @return true if the result belonged to this helper. */
    public boolean onActivityResult(int requestCode, int resultCode) {
        if (requestCode != REQ_CONFIRM_CREDENTIAL) return false;
        Callback cb = pending;
        pending = null;
        if (cb != null) {
            cb.onAuthResult(resultCode == Activity.RESULT_OK,
                    resultCode == Activity.RESULT_OK ? "Verified" : "Cancelled");
        }
        return true;
    }
}
