package `is`.xyz.mpv

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * PIN lock for the private vault. The PIN is stored as a SHA-256 hash with a
 * random per-PIN salt in SharedPreferences — never in plaintext. After 5
 * consecutive wrong attempts the keypad locks for 5 minutes.
 */
object PinStore {
    private const val PREFS_NAME = "pure_vault_pin"
    private const val KEY_HASH = "pin_hash"
    private const val KEY_SALT = "pin_salt"
    private const val KEY_FAILED = "failed_attempts"
    private const val KEY_LOCK_UNTIL = "lock_until"
    private const val MAX_FAILED = 5
    private const val LOCK_MS = 5 * 60 * 1000L

    fun hasPin(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.contains(KEY_HASH) && prefs.contains(KEY_SALT)
    }

    fun setPin(context: Context, pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val saltHex = salt.joinToString("") { "%02x".format(it) }
        val hash = hashPin(saltHex, pin)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_SALT, saltHex)
            .putString(KEY_HASH, hash)
            .putInt(KEY_FAILED, 0)
            .putLong(KEY_LOCK_UNTIL, 0)
            .apply()
    }

    fun verifyPin(context: Context, pin: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val salt = prefs.getString(KEY_SALT, null) ?: return false
        val stored = prefs.getString(KEY_HASH, null) ?: return false
        val computed = hashPin(salt, pin)
        return MessageDigest.isEqual(
            computed.toByteArray(Charsets.UTF_8),
            stored.toByteArray(Charsets.UTF_8)
        )
    }

    fun clearPin(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /** Record one wrong attempt; returns the consecutive failure count. Locks at 5. */
    fun recordFailure(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val n = prefs.getInt(KEY_FAILED, 0) + 1
        val edit = prefs.edit().putInt(KEY_FAILED, n)
        if (n >= MAX_FAILED) {
            edit.putLong(KEY_LOCK_UNTIL, System.currentTimeMillis() + LOCK_MS)
            edit.putInt(KEY_FAILED, 0)
        }
        edit.apply()
        return n
    }

    fun resetFailures(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt(KEY_FAILED, 0).putLong(KEY_LOCK_UNTIL, 0).apply()
    }

    fun lockRemainingMs(context: Context): Long {
        val until = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_LOCK_UNTIL, 0)
        return (until - System.currentTimeMillis()).coerceAtLeast(0)
    }

    fun isLocked(context: Context): Boolean = lockRemainingMs(context) > 0

    private fun hashPin(saltHex: String, pin: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest("$saltHex:$pin".toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
