package top.niunaijun.blackboxa.view.main

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackboxa.util.InjectionUtil
import top.niunaijun.blackboxa.view.list.ListViewModel

class WelcomeActivity : AppCompatActivity() {

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        jump()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val crash = java.io.File(filesDir, "crash.txt")
        if (crash.exists()) {
            showCrash(crash)
            return
        }
        previewInstalledAppList()
        jump()
    }

    private fun showCrash(file: java.io.File) {
        val text = runCatching { file.readText() }.getOrDefault("(unreadable)").take(6000)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val tv = android.widget.TextView(this).apply {
            this.text = text; setTextIsSelectable(true); textSize = 11f
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
            setOnClickListener { file.delete(); previewInstalledAppList(); jump() }
        }
        val col = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(0xFF0A0406.toInt())
            addView(copy); addView(go)
            addView(android.widget.ScrollView(this@WelcomeActivity).apply { addView(tv) })
        }
        setContentView(col)
    }

    private fun jump() {
        startActivity(Intent(this, top.niunaijun.blackboxa.launcher.LauncherActivity::class.java))
        finish()
    }

    private fun previewInstalledAppList(){
        val viewModel = ViewModelProvider(this,InjectionUtil.getListFactory()).get(ListViewModel::class.java)
        viewModel.previewInstalledList()
    }
}