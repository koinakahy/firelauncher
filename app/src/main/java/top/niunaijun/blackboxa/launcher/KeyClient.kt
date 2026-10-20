package top.niunaijun.blackboxa.launcher

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** License check against Firebase Realtime Database (REST, no SDK). */
class KeyClient {

    sealed class Result {
        data class Ok(val expiryMs: Long) : Result()
        /** invalid = the key itself is bad/expired, so it is safe to forget it */
        data class Fail(val message: String, val invalid: Boolean) : Result()
    }

    private class Resp(val status: Int, val body: String, val serverTime: Long)

    private val day = 86_400_000L

    fun validateAndActivate(rawKey: String, fp: String, deviceName: String): Result {
        return try {
            val k = rawKey.trim().uppercase()
            if (!k.matches(Regex("^[A-Z0-9-]{8,64}$"))) return Result.Fail("Invalid key", true)

            val g = call("GET", "keys/$k.json", null)
            if (g.status !in 200..299) return Result.Fail("Server error (${g.status})", false)
            val raw = g.body.trim()
            if (raw.isEmpty() || raw == "null") return Result.Fail("Invalid key", true)

            val o = JSONObject(raw)
            if (o.optBoolean("banned")) return Result.Fail("This key has been disabled", true)

            val days = o.optLong("days", 0)
            if (days <= 0) return Result.Fail("Invalid key", true)
            val maxDevices = o.optInt("maxDevices", 1).coerceIn(1, 5)

            // First use: let the SERVER stamp the activation time (cannot be faked by the phone).
            var activatedAt = o.optLong("activatedAt", 0)
            if (activatedAt == 0L) {
                val p = call("PUT", "keys/$k/activatedAt.json", "{\".sv\":\"timestamp\"}")
                activatedAt = p.body.trim().toLongOrNull()
                    ?: return Result.Fail("Activation failed, try again", false)
            }

            val serverNow = if (g.serverTime > 0) g.serverTime else System.currentTimeMillis()
            val expiry = activatedAt + days * day
            if (serverNow >= expiry) return Result.Fail("This key has expired", true)

            // Device slots s1..sN
            val devices = o.optJSONObject("devices")
            var known = false
            var freeSlot: String? = null
            for (i in 1..maxDevices) {
                val slot = "s$i"
                val v = devices?.optString(slot, "") ?: ""
                if (v == fp) known = true
                if (v.isEmpty() && freeSlot == null) freeSlot = slot
            }
            if (!known) {
                val slot = freeSlot ?: return Result.Fail("Device limit reached for this key", false)
                val p = call("PUT", "keys/$k/devices/$slot.json", "\"$fp\"")
                if (p.status !in 200..299) return Result.Fail("Device limit reached for this key", false)
            }

            // Convert server expiry to the phone's clock so the countdown is right.
            Result.Ok(expiry - serverNow + System.currentTimeMillis())
        } catch (e: Exception) {
            Result.Fail("Network error. Check your internet and try again.", false)
        }
    }

    private fun call(method: String, path: String, body: String?): Resp {
        val c = URL("${LauncherConfig.FIREBASE_DB_URL.trimEnd('/')}/$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 15000; c.readTimeout = 15000
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val status = c.responseCode
        val stream = if (status in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        return Resp(status, text, c.date)
    }
}
