package top.niunaijun.blackboxa.app

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.util.Log
import top.niunaijun.blackbox.BlackBoxCore

/**
 *
 * @Description:
 * @Author: wukaicheng
 * @CreateDate: 2021/4/29 21:21
 */
class App : Application() {

    companion object {
        private const val TAG = "App"

        @SuppressLint("StaticFieldLeak")
        @Volatile
        private lateinit var mContext: Context

        @JvmStatic
        fun getContext(): Context {
            return mContext
        }
    }

    private inline fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {   // Throwable on purpose: linkage errors must not kill start-up
            Log.e(TAG, "Error in $what", t)
        }
    }

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        if (base == null) return
        mContext = base

        CrashRecorder.install(base)

        guarded("closeCodeInit") { BlackBoxCore.get().closeCodeInit() }
        guarded("onBeforeMainApplicationAttach") { BlackBoxCore.get().onBeforeMainApplicationAttach(this, base) }
        AppManager.doAttachBaseContext(base)
        guarded("onAfterMainApplicationAttach") { BlackBoxCore.get().onAfterMainApplicationAttach(this, base) }

        // The core installed its own handler during attach; make ours the outermost again.
        CrashRecorder.install(base)
    }

    override fun onCreate() {
        super.onCreate()
        guarded("onCreate") { AppManager.doOnCreate(mContext) }
        CrashRecorder.install(mContext)
    }
}
