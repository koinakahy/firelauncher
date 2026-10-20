package top.niunaijun.blackboxa.view.main

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import top.niunaijun.blackboxa.app.CrashRecorder
import java.io.File

class WelcomeActivity : AppCompatActivity() {

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        jump()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val crash = File(filesDir, CrashRecorder.FILE_HOST)
        if (crash.exists()) {
            showCrash(crash)
            return
        }
        // NOTE: the old code scanned every installed app here (APK + ABI + Xposed checks) although
        // the launcher never uses that list. Removed: it only delayed start-up.
        jump()
    }

    private fun showCrash(file: File) {
        val text = runCatching { file.readText() }.getOrDefault("(unreadable)").take(6000)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val tv = android.widget.TextView(this).apply {
            this.text = "The app closed unexpectedly last time. Details:\n\n$text"
            setTextIsSelectable(true); textSize = 11f
            setTextColor(0xFFFFFFFF.toInt()); setPadding(pad, pad, pad, pad)
        }
        val copy = android.widget.Button(this).apply {
            this.text = "Copy error"
            setOnClickListener {
                (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .setPrimaryClip(android.content.ClipData.newPlainText("crash", text))
            }
        }
        val go = android.widget.Button(this).apply {
            this.text = "Clear and continue"
            setOnClickListener { file.delete(); jump() }
        }
        val col = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(0xFF0A0406.toInt())
            fitsSystemWindows = true
            addView(copy); addView(go)
            addView(android.widget.ScrollView(this@WelcomeActivity).apply { addView(tv) })
        }
        setContentView(col)
    }

    private fun jump() {
        startActivity(Intent(this, top.niunaijun.blackboxa.launcher.LauncherActivity::class.java))
        finish()
    }
}
