package com.extolab.extopay_lockscreen

import android.app.KeyguardManager
import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Keeps track of the PIN this app set through the SDK.
 *
 * The CloudPOS SDK can set a PIN but cannot read or verify one, so the app keeps a
 * salted PBKDF2 hash (never the PIN itself) to check "Enter current PIN" in the
 * Change / Remove flows. It also counts wrong attempts and enforces a lockout.
 */
class PinStore(context: Context) {

    private val prefs: SharedPreferences
    private val keyguard: KeyguardManager?

    init {
        val app = context.applicationContext
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        keyguard = app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager?
    }

    /** True when this app set the PIN (and so can verify it). */
    fun hasPin(): Boolean = prefs.contains(KEY_HASH)

    /** True when the device has any secure screen lock (PIN/password/pattern). */
    fun isDeviceSecure(): Boolean = keyguard?.isDeviceSecure == true

    fun savePin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs.edit()
            .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(KEY_HASH, Base64.encodeToString(hash(pin, salt), Base64.NO_WRAP))
            .remove(KEY_FAILED)
            .remove(KEY_LOCK_UNTIL)
            .apply()
    }

    fun clearPin() {
        prefs.edit().clear().apply()
    }

    /**
     * Checks the PIN against the saved hash. Returns true when there is no saved hash
     * (PIN set outside this app), because then only the terminal can judge it.
     */
    fun verify(pin: String): Boolean {
        if (!hasPin()) return true
        val salt = Base64.decode(prefs.getString(KEY_SALT, ""), Base64.NO_WRAP)
        val expected = Base64.decode(prefs.getString(KEY_HASH, ""), Base64.NO_WRAP)
        val ok = MessageDigest.isEqual(expected, hash(pin, salt))
        if (ok) {
            prefs.edit().remove(KEY_FAILED).remove(KEY_LOCK_UNTIL).apply()
        } else {
            val failed = prefs.getInt(KEY_FAILED, 0) + 1
            val e = prefs.edit().putInt(KEY_FAILED, failed)
            if (failed >= MAX_ATTEMPTS) {
                e.putLong(KEY_LOCK_UNTIL, SystemClock.elapsedRealtime() + LOCKOUT_MS)
                e.putInt(KEY_FAILED, 0)
            }
            e.apply()
        }
        return ok
    }

    fun attemptsLeft(): Int = MAX_ATTEMPTS - prefs.getInt(KEY_FAILED, 0)

    /** Milliseconds left in the lockout, or 0. */
    fun lockoutRemainingMs(): Long {
        val until = prefs.getLong(KEY_LOCK_UNTIL, 0)
        val now = SystemClock.elapsedRealtime()
        if (until == 0L || now >= until || until - now > LOCKOUT_MS) {
            // elapsedRealtime resets on reboot; drop stale values
            if (until != 0L) prefs.edit().remove(KEY_LOCK_UNTIL).apply()
            return 0
        }
        return until - now
    }

    companion object {
        const val MIN_LENGTH = 4
        const val MAX_LENGTH = 12
        const val MAX_ATTEMPTS = 5
        const val LOCKOUT_MS = 30_000L

        private const val PREFS = "pin_store"
        private const val KEY_HASH = "hash"
        private const val KEY_SALT = "salt"
        private const val KEY_FAILED = "failed"
        private const val KEY_LOCK_UNTIL = "lock_until_elapsed"
        private const val ITERATIONS = 10_000

        private fun hash(pin: String, salt: ByteArray): ByteArray = try {
            val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256)
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
        } catch (e: Exception) {
            throw IllegalStateException("PIN hashing failed", e)
        }
    }
}
