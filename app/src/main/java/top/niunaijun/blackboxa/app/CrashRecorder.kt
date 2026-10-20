package top.niunaijun.blackboxa.app

import android.content.Context
import android.os.Looper
import android.os.Process
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes uncaught exceptions to files.
 *
 * Why this exists: the BlackBox core installs its own default exception handler (SimpleCrashFix)
 * that SWALLOWS some crashes. When that happens on the main thread the Looper dies but the
 * process stays alive, so the app sits forever on the splash screen with no error shown.
 * We therefore install our handler last (outermost), record the crash first, and if the main
 * thread's crash was swallowed we kill the process so the app never freezes silently.
 *
 *  crash.txt       - host app (UI) process and the engine/server process. Shown by WelcomeActivity.
 *  crash_game.txt  - crashes inside virtual game processes. Only shown in Settings > Engine check.
 */
object CrashRecorder {
    const val FILE_HOST = "crash.txt"
    const val FILE_GAME = "crash_game.txt"

    private class Handler(
        private val prev: Thread.UncaughtExceptionHandler?,
        private val dir: File,
        private val proc: String,
        private val kind: Kind
    ) : Thread.UncaughtExceptionHandler {

        override fun uncaughtException(t: Thread, e: Throwable) {
            try {
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                File(dir, if (kind == Kind.GAME) FILE_GAME else FILE_HOST).writeText(
                    "Time: $stamp\nProcess: $proc\nThread: ${t.name}\n\n" + Log.getStackTraceString(e)
                )
            } catch (_: Throwable) { }

            try { prev?.uncaughtException(t, e) } catch (_: Throwable) { }

            // If we are still alive here the previous handler swallowed the crash.
            // A dead main thread = frozen app, so end the process instead of hanging.
            if (kind == Kind.MAIN && t === Looper.getMainLooper().thread) {
                Process.killProcess(Process.myPid())
                System.exit(10)
            }
        }
    }

    private enum class Kind { MAIN, SERVER, GAME }

    fun install(context: Context) {
        try {
            val dir = context.filesDir ?: return
            val current = Thread.getDefaultUncaughtExceptionHandler()
            if (current is Handler) return          // already outermost
            val proc = ProcessNames.current(context)
            val kind = when {
                proc == context.packageName -> Kind.MAIN
                proc.endsWith(":black") -> Kind.SERVER
                else -> Kind.GAME
            }
            Thread.setDefaultUncaughtExceptionHandler(Handler(current, dir, proc, kind))
        } catch (_: Throwable) { }
    }
}
