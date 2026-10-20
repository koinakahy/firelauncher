package top.niunaijun.blackboxa.app

import android.content.Context
import java.io.File

/** Name of the current Android process (works on every API level). */
object ProcessNames {
    @Volatile private var cached: String? = null

    fun current(context: Context): String {
        cached?.let { return it }
        val name = try {
            File("/proc/self/cmdline").readBytes().takeWhile { it.toInt() != 0 }
                .toByteArray().toString(Charsets.UTF_8).trim()
        } catch (_: Throwable) { "" }
        val result = if (name.isNotEmpty()) name else context.packageName
        cached = result
        return result
    }

    fun isMain(context: Context): Boolean = current(context) == context.packageName
}
