package com.extolab.extopay_lockscreen;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Base64;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.KeySpec;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Keeps track of the PIN this app set through the SDK.
 *
 * The CloudPOS SDK can set a PIN but cannot read or verify one, so the app keeps a
 * salted PBKDF2 hash (never the PIN itself) to check "Enter current PIN" in the
 * Change / Remove flows. It also counts wrong attempts and enforces a lockout.
 */
public class PinStore {

    public static final int MIN_LENGTH = 4;
    public static final int MAX_LENGTH = 12;
    public static final int MAX_ATTEMPTS = 5;
    public static final long LOCKOUT_MS = 30_000L;

    private static final String PREFS = "pin_store";
    private static final String KEY_HASH = "hash";
    private static final String KEY_SALT = "salt";
    private static final String KEY_FAILED = "failed";
    private static final String KEY_LOCK_UNTIL = "lock_until_elapsed";
    private static final int ITERATIONS = 10_000;

    private final SharedPreferences prefs;
    private final KeyguardManager keyguard;

    public PinStore(Context context) {
        Context app = context.getApplicationContext();
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        keyguard = (KeyguardManager) app.getSystemService(Context.KEYGUARD_SERVICE);
    }

    /** True when this app set the PIN (and so can verify it). */
    public boolean hasPin() {
        return prefs.contains(KEY_HASH);
    }

    /** True when the device has any secure screen lock (PIN/password/pattern). */
    public boolean isDeviceSecure() {
        return keyguard != null && keyguard.isDeviceSecure();
    }

    public void savePin(String pin) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        prefs.edit()
                .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
                .putString(KEY_HASH, Base64.encodeToString(hash(pin, salt), Base64.NO_WRAP))
                .remove(KEY_FAILED)
                .remove(KEY_LOCK_UNTIL)
                .apply();
    }

    public void clearPin() {
        prefs.edit().clear().apply();
    }

    /**
     * Checks the PIN against the saved hash. Returns true when there is no saved hash
     * (PIN set outside this app), because then only the terminal can judge it.
     */
    public boolean verify(String pin) {
        if (!hasPin()) return true;
        byte[] salt = Base64.decode(prefs.getString(KEY_SALT, ""), Base64.NO_WRAP);
        byte[] expected = Base64.decode(prefs.getString(KEY_HASH, ""), Base64.NO_WRAP);
        boolean ok = MessageDigest.isEqual(expected, hash(pin, salt));
        if (ok) {
            prefs.edit().remove(KEY_FAILED).remove(KEY_LOCK_UNTIL).apply();
        } else {
            int failed = prefs.getInt(KEY_FAILED, 0) + 1;
            SharedPreferences.Editor e = prefs.edit().putInt(KEY_FAILED, failed);
            if (failed >= MAX_ATTEMPTS) {
                e.putLong(KEY_LOCK_UNTIL, SystemClock.elapsedRealtime() + LOCKOUT_MS);
                e.putInt(KEY_FAILED, 0);
            }
            e.apply();
        }
        return ok;
    }

    public int attemptsLeft() {
        return MAX_ATTEMPTS - prefs.getInt(KEY_FAILED, 0);
    }

    /** Milliseconds left in the lockout, or 0. */
    public long lockoutRemainingMs() {
        long until = prefs.getLong(KEY_LOCK_UNTIL, 0);
        long now = SystemClock.elapsedRealtime();
        if (until == 0 || now >= until || until - now > LOCKOUT_MS) {
            // elapsedRealtime resets on reboot; drop stale values
            if (until != 0) prefs.edit().remove(KEY_LOCK_UNTIL).apply();
            return 0;
        }
        return until - now;
    }

    private static byte[] hash(String pin, byte[] salt) {
        try {
            KeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("PIN hashing failed", e);
        }
    }
}
