package top.niunaijun.blackboxa.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackboxa.view.main.BlackBoxLoader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object AppManager {
    private const val TAG = "AppManager"

    /**
     * The BlackBox core talks to its own service process during doCreate(). If that process is
     * slow or dead, doCreate() can block for a very long time. Doing that on the main thread
     * freezes the app on the splash screen, so in the UI process we run it on a worker thread and
     * only wait this long. Everything that needs the engine waits on [awaitCore] off the UI thread.
     */
    private const val MAIN_THREAD_BUDGET_MS = 4000L

    private val coreLatch = CountDownLatch(1)

    @Volatile
    var coreError: Throwable? = null
        private set

    @JvmStatic
    val mBlackBoxLoader by lazy { BlackBoxLoader() }

    @JvmStatic
    val mBlackBoxCore by lazy { mBlackBoxLoader.getBlackBoxCore() }

    @JvmStatic
    val mRemarkSharedPreferences: SharedPreferences by lazy {
        App.getContext().getSharedPreferences("UserRemark", Context.MODE_PRIVATE)
    }

    fun doAttachBaseContext(context: Context) {
        try {
            mBlackBoxLoader.attachBaseContext(context)
            mBlackBoxLoader.addLifecycleCallback()
        } catch (t: Throwable) {
            Log.e(TAG, "Error in doAttachBaseContext", t)
        }
    }

    fun doOnCreate(context: Context) {
        val task = Runnable {
            try {
                mBlackBoxLoader.doOnCreate(context)
            } catch (t: Throwable) {
                coreError = t
                Log.e(TAG, "BlackBoxCore.doCreate failed", t)
            } finally {
                coreLatch.countDown()
            }
        }
        if (ProcessNames.isMain(context)) {
            val worker = Thread(task, "fire-core-init").apply { isDaemon = true }
            worker.start()
            try { worker.join(MAIN_THREAD_BUDGET_MS) } catch (_: InterruptedException) { }
        } else {
            // engine (:black) and virtual game processes must be fully initialised before they continue
            task.run()
        }
    }

    /** true once BlackBoxCore.doCreate() has finished (successfully or not). Call off the UI thread. */
    fun awaitCore(timeoutMs: Long): Boolean =
        try { coreLatch.await(timeoutMs, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { false }

    fun isCoreFinished(): Boolean = coreLatch.count == 0L
}
