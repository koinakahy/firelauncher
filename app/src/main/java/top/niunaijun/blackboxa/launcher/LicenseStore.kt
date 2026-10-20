package top.niunaijun.blackboxa.launcher

import android.content.Context

/** Stores only the key and the expiry the server returned. The server stays the authority. */
class LicenseStore(context: Context) {
    private val sp = context.getSharedPreferences("fire_license", Context.MODE_PRIVATE)

    var key: String?
        get() = sp.getString("key", null)
        private set(v) { sp.edit().putString("key", v).apply() }

    /** Epoch millis, Long.MAX_VALUE = lifetime, 0 = none */
    var expiryMs: Long
        get() = sp.getLong("expiry", 0L)
        private set(v) { sp.edit().putLong("expiry", v).apply() }

    fun save(key: String, expiryMs: Long) { this.key = key; this.expiryMs = expiryMs }
    fun clear() { sp.edit().clear().apply() }
    fun isActive() = key != null && expiryMs > System.currentTimeMillis()
}
