package top.niunaijun.blackboxa.launcher

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.core.system.ServiceManager
import top.niunaijun.blackboxa.app.AppManager
import top.niunaijun.blackboxa.app.CrashRecorder
import java.io.File

/**
 * Safe access to the BlackBox engine for the launcher UI.
 *
 * Rules this file exists to enforce:
 *  1. Nothing here may run on the UI thread (engine calls are IPC into another process).
 *  2. The core's BPackageManager answers isInstalled()/getLaunchIntentForPackage() from the
 *     PHONE's package manager until its service binder has been created once. That made the old
 *     launcher think the game was already inside the virtual space, skip the install, and then
 *     hang forever on the loading screen. ensureReady() creates the binders first.
 *  3. InstallResult.success is true even when the install threw, so installs are always verified.
 */
object Engine {
    private const val TAG = "FireEngine"

    private val SERVICES = listOf(
        ServiceManager.ACTIVITY_MANAGER, ServiceManager.PACKAGE_MANAGER, ServiceManager.USER_MANAGER,
        ServiceManager.JOB_MANAGER, ServiceManager.STORAGE_MANAGER, ServiceManager.XPOSED_MANAGER,
        ServiceManager.ACCOUNT_MANAGER, ServiceManager.LOCATION_MANAGER, ServiceManager.NOTIFICATION_MANAGER
    )

    /** @return null when the engine is ready, otherwise a message that can be shown to the user. */
    fun ensureReady(timeoutMs: Long = 25_000L): String? {
        if (Looper.myLooper() == Looper.getMainLooper()) return "Internal error: engine used on the UI thread"
        val deadline = SystemClock.uptimeMillis() + timeoutMs

        if (!AppManager.awaitCore(timeoutMs)) {
            return "The engine is still starting. Wait a few seconds and try again.\n(Settings > Engine check shows details.)"
        }
        AppManager.coreError?.let {
            return "The engine failed to start: ${it.javaClass.simpleName}: ${it.message}\n(Settings > Engine check shows details.)"
        }
        while (true) {
            if (warmServices()) {
                try { BlackBoxCore.getBPackageManager().resetTransactionThrottler() } catch (_: Throwable) { }
                return null
            }
            if (SystemClock.uptimeMillis() >= deadline) break
            SystemClock.sleep(500)
        }
        return "The BlackBox engine process is not responding.\nClose the app completely, open it again and retry. If it keeps happening open Settings > Engine check."
    }

    private fun warmServices(): Boolean = try {
        BlackBoxCore.getBPackageManager().getService() != null &&
            BlackBoxCore.getBActivityManager().getService() != null
    } catch (t: Throwable) {
        Log.w(TAG, "warmServices: ${t.message}")
        false
    }

    fun isInstalledInVirtual(pkg: String, userId: Int): Boolean = try {
        BlackBoxCore.get().getInstalledApplications(0, userId)?.any { it.packageName == pkg } == true
    } catch (t: Throwable) {
        Log.w(TAG, "isInstalledInVirtual: ${t.message}")
        false
    }

    /** Copies the phone's installed game into the virtual space. @return null on success, else an error message. */
    fun installIntoVirtual(pkg: String, userId: Int): String? {
        if (isInstalledInVirtual(pkg, userId)) return null
        val res = try {
            BlackBoxCore.get().installPackageAsUser(pkg, userId)
        } catch (t: Throwable) {
            return "Install into the virtual space failed: ${t.javaClass.simpleName}: ${t.message}"
        }
        if (res == null) return "Install into the virtual space failed (engine returned nothing)."
        if (!res.success) return res.msg ?: "Install into the virtual space failed."
        return if (isInstalledInVirtual(pkg, userId)) null
        else "The game could not be added to the virtual space.\nMake sure the game is fully installed and updated on the phone, then try again."
    }

    /** Explains a 32-bit / 64-bit mismatch between this app and the game, or null if there is none. */
    fun abiProblem(context: Context, pkg: String): String? = try {
        val ai = context.packageManager.getApplicationInfo(pkg, 0)
        val f = ApplicationInfo::class.java.getDeclaredField("primaryCpuAbi").apply { isAccessible = true }
        val abi = f.get(ai) as? String
        val host64 = Process.is64Bit()
        when {
            abi == null -> null
            abi.contains("64") && !host64 ->
                "This game is 64-bit but Fire Launcher is running as 32-bit. Install the app-arm64-v8a APK."
            !abi.contains("64") && host64 ->
                "This game is 32-bit but Fire Launcher is running as 64-bit. Install the app-armeabi-v7a APK."
            else -> null
        }
    } catch (_: Throwable) {
        null
    }

    /** Human-readable report for Settings > Engine check. Run off the UI thread. */
    fun report(context: Context): String {
        val sb = StringBuilder()
        fun line(s: String = "") { sb.append(s).append('\n') }
        fun clip(f: File) = runCatching { f.readText().take(2500) }.getOrDefault("(unreadable)")

        line("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        line("Supported ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
        line("Fire Launcher running as: ${if (Process.is64Bit()) "64-bit" else "32-bit"}")
        runCatching {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            line("App version: ${pi.versionName}")
        }
        line()

        val finished = AppManager.isCoreFinished()
        val err = AppManager.coreError
        line("Engine init: " + when {
            err != null -> "FAILED"
            finished -> "finished"
            else -> "still running (not finished)"
        })
        err?.let { line(Log.getStackTraceString(it).take(2500)) }

        val ready = ensureReady(8_000)
        line("Engine ready: ${if (ready == null) "YES" else "NO"}")
        ready?.let { line(it) }
        line()

        line("Engine services:")
        for (name in SERVICES) {
            val ok = try { BlackBoxCore.get().getService(name) != null } catch (_: Throwable) { false }
            line("  ${if (ok) "OK     " else "MISSING"} $name")
        }
        val users = try { BlackBoxCore.get().users?.size ?: -1 } catch (_: Throwable) { -1 }
        line("Virtual users: $users")
        line()

        line("Games:")
        for (g in LauncherConfig.GAMES) {
            val onPhone = runCatching { context.packageManager.getPackageInfo(g.pkg, 0); true }.getOrDefault(false)
            val inVirtual = isInstalledInVirtual(g.pkg, LauncherConfig.VIRTUAL_USER)
            line("  ${g.title}: on phone=$onPhone, in virtual space=$inVirtual")
            abiProblem(context, g.pkg)?.let { line("    ! $it") }
        }
        line()

        val dir = context.filesDir
        File(dir, CrashRecorder.FILE_HOST).takeIf { it.exists() }?.let {
            line("Last app/engine crash (crash.txt):"); line(clip(it)); line()
        }
        File(dir, CrashRecorder.FILE_GAME).takeIf { it.exists() }?.let {
            line("Last game crash (crash_game.txt):"); line(clip(it)); line()
        }
        line("Full engine log: Downloads/logs/${context.packageName}_logcat.txt")
        return sb.toString()
    }
}
