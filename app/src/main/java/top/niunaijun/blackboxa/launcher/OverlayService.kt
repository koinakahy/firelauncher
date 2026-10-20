package top.niunaijun.blackboxa.launcher

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import top.niunaijun.blackboxa.R

/** Shows the "Validation successful" popup, then leaves a round logo on the screen edge. */
class OverlayService : Service() {
    private var wm: WindowManager? = null
    private var banner: View? = null
    private var logo: View? = null
    private val h = Handler(Looper.getMainLooper())

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        removeAll()
        showBanner()
        showLogo()
        return START_NOT_STICKY
    }

    private fun params(gravity: Int, y: Int = 0) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).apply { this.gravity = gravity; this.y = y }

    private fun showBanner() {
        banner = LayoutInflater.from(this).inflate(R.layout.overlay_banner, null)
        wm?.addView(banner, params(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 120))
        h.postDelayed({ banner?.let { runCatching { wm?.removeView(it) }; banner = null } }, 3000)
    }

    private fun showLogo() {
        logo = LayoutInflater.from(this).inflate(R.layout.overlay_logo, null)
        logo?.setOnClickListener {
            startActivity(Intent(this, LauncherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        wm?.addView(logo, params(Gravity.END or Gravity.CENTER_VERTICAL))
    }

    private fun removeAll() {
        listOf(banner, logo).forEach { v -> v?.let { runCatching { wm?.removeView(it) } } }
        banner = null; logo = null
    }

    override fun onDestroy() { removeAll(); super.onDestroy() }
}
