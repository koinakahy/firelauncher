package top.niunaijun.blackboxa.launcher

import android.app.ProgressDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.databinding.ActivityLauncherBinding
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class LauncherActivity : AppCompatActivity() {

    private lateinit var b: ActivityLauncherBinding
    private lateinit var store: LicenseStore
    private val client = KeyClient()
    private val io = Executors.newSingleThreadExecutor()     // network (key validation)
    private val eng = Executors.newSingleThreadExecutor()    // engine / IPC work, never on the UI thread
    private val ui = Handler(Looper.getMainLooper())
    private val cards = mutableMapOf<String, View>()

    private val ticker = object : Runnable {
        override fun run() { renderMembership(); ui.postDelayed(this, 1000) }
    }

    /**
     * Stable per-install id, hashed. Settings.Secure.ANDROID_ID is NOT read directly any more: the
     * BlackBox core spoofs the calling uid inside this process, so the system rejects that call with
     * "Calling uid ... doesn't match source uid" and the activity crashed on start.
     * The id is created once (ANDROID_ID if it works, otherwise random) and then saved.
     */
    private val deviceId: String by lazy {
        val sp = getSharedPreferences("fire_device", Context.MODE_PRIVATE)
        sp.getString("id", null) ?: run {
            val raw = try { Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) } catch (_: Throwable) { null }
                ?: java.util.UUID.randomUUID().toString()
            val id = MessageDigest.getInstance("SHA-256").digest(("fire:$raw").toByteArray())
                .joinToString("") { "%02x".format(it) }.take(32)
            sp.edit().putString("id", id).apply()
            id
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityLauncherBinding.inflate(layoutInflater)
        setContentView(b.root)
        store = LicenseStore(this)
        b.tvBrand.text = LauncherConfig.BRAND

        buildGameCards()
        b.bottomNav.setOnItemSelectedListener {
            b.tabGames.visibility = if (it.itemId == R.id.nav_games) View.VISIBLE else View.GONE
            b.tabMembership.visibility = if (it.itemId == R.id.nav_membership) View.VISIBLE else View.GONE
            b.tabSeller.visibility = if (it.itemId == R.id.nav_seller) View.VISIBLE else View.GONE
            b.tabSettings.visibility = if (it.itemId == R.id.nav_settings) View.VISIBLE else View.GONE
            true
        }
        setupMembership()
        setupSeller()
        setupSettings()
        refreshLocks()
        watchEngine()
    }

    override fun onResume() { super.onResume(); ui.post(ticker); refreshCards() }
    override fun onPause() { super.onPause(); ui.removeCallbacks(ticker) }
    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
        io.shutdown(); eng.shutdown()
    }

    // ---------------------------------------------------------------- Games
    private fun buildGameCards() {
        LauncherConfig.GAMES.forEach { g ->
            val v = LayoutInflater.from(this).inflate(R.layout.item_game_card, b.gamesList, false)
            v.findViewById<TextView>(R.id.tvGameTitle).text = g.title
            v.findViewById<View>(R.id.btnLaunch).setOnClickListener { onLaunch(g) }
            b.gamesList.addView(v)
            cards[g.pkg] = v
        }
    }

    private fun refreshCards() {
        LauncherConfig.GAMES.forEach { g ->
            val v = cards[g.pkg] ?: return@forEach
            val icon = v.findViewById<ImageView>(R.id.ivGameIcon)
            val status = v.findViewById<TextView>(R.id.tvGameStatus)
            if (isInstalledOnPhone(g.pkg)) {
                runCatching { icon.setImageDrawable(packageManager.getApplicationIcon(g.pkg)) }
                status.text = if (store.isActive()) "Ready to launch" else "Locked - key required"
            } else {
                icon.setImageResource(R.drawable.ic_nav_games)
                status.text = "Not installed - get it on Play Store"
            }
        }
        refreshLocks()
    }

    private fun refreshLocks() {
        val active = store.isActive()
        cards.values.forEach {
            it.findViewById<View>(R.id.ivLock).visibility = if (active) View.GONE else View.VISIBLE
            it.alpha = if (active) 1f else 0.85f
        }
    }

    private fun isInstalledOnPhone(pkg: String) =
        runCatching { packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

    private fun onLaunch(g: LauncherConfig.Game) {
        if (!isInstalledOnPhone(g.pkg)) { showInstallDialog(g); return }
        val saved = store.key
        if (saved == null) askKey(g) else validate(saved, g)
    }

    private fun showInstallDialog(g: LauncherConfig.Game) {
        dialog().setTitle("${g.title} not found")
            .setMessage("Please download ${g.title} from the Play Store first.")
            .setPositiveButton("Open Play Store") { _, _ ->
                val market = Uri.parse("market://details?id=${g.pkg}")
                val web = Uri.parse("https://play.google.com/store/apps/details?id=${g.pkg}")
                try { startActivity(Intent(Intent.ACTION_VIEW, market)) }
                catch (e: Exception) { startActivity(Intent(Intent.ACTION_VIEW, web)) }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun askKey(g: LauncherConfig.Game?) {
        val input = EditText(this).apply {
            hint = "Enter your key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setTextColor(0xFFFFFFFF.toInt()); setHintTextColor(0xFF8A6A6A.toInt())
            setSingleLine()
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val wrap = android.widget.FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        dialog().setTitle("Enter License Key").setView(wrap)
            .setPositiveButton("Activate") { _, _ ->
                val k = input.text.toString().trim()
                if (k.isEmpty()) toast("Key is empty") else validate(k, g)
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun validate(key: String, g: LauncherConfig.Game?) {
        @Suppress("DEPRECATION")
        val pd = ProgressDialog(this).apply { setMessage("Validating key..."); setCancelable(false); show() }
        io.execute {
            val r = client.validateAndActivate(key, deviceId, Build.MODEL ?: "Android")
            ui.post {
                runCatching { pd.dismiss() }
                when (r) {
                    is KeyClient.Result.Ok -> {
                        store.save(key, r.expiryMs)
                        refreshCards(); renderMembership()
                        if (g != null) startGame(g) else toast("Key activated")
                    }
                    is KeyClient.Result.Fail -> {
                        if (r.invalid) { store.clear(); refreshCards(); renderMembership() }
                        dialog().setTitle("Validation failed").setMessage(r.message)
                            .setPositiveButton("OK", null).show()
                    }
                }
            }
        }
    }

    private fun startGame(g: LauncherConfig.Game) {
        val pd = ProgressDialog(this).apply { setMessage("Launching ${g.title}..."); setCancelable(false); show() }
        eng.execute {
            var err: String? = Engine.abiProblem(this, g.pkg)
            var ok = false
            if (err == null) err = Engine.ensureReady()
            if (err == null) {
                try {
                    val u = LauncherConfig.VIRTUAL_USER
                    err = Engine.installIntoVirtual(g.pkg, u)
                    if (err == null) {
                        ok = BlackBoxCore.get().launchApk(g.pkg, u)
                        if (!ok) err = "The game was added but could not be started (no launch activity found)."
                    }
                } catch (e: Throwable) {
                    err = "Launch error: ${e.javaClass.simpleName}: ${e.message}"
                }
            }
            ui.post {
                runCatching { pd.dismiss() }
                if (isFinishing) return@post
                if (ok) onGameLaunched()
                else dialog().setTitle("Could not launch ${g.title}")
                    .setMessage(err ?: "Unknown error")
                    .setPositiveButton("OK", null).show()
            }
        }
    }

    private fun onGameLaunched() {
        if (Settings.canDrawOverlays(this)) {
            startService(Intent(this, OverlayService::class.java))
        } else {
            Toast.makeText(this, "Validation successful", Toast.LENGTH_LONG).show()
        }
    }

    // ----------------------------------------------------------- Membership
    private fun setupMembership() {
        b.btnRedeem.setOnClickListener { askKey(null) }
        b.btnLogout.setOnClickListener {
            store.clear(); refreshCards(); renderMembership(); toast("Key removed from this device")
        }
    }

    private fun renderMembership() {
        val k = store.key
        val left = store.expiryMs - System.currentTimeMillis()
        if (k == null || left <= 0) {
            b.tvPlanStatus.text = if (k == null) "NO ACTIVE KEY" else "KEY EXPIRED"
            b.tvTimeLeft.text = "--"
            b.tvExpiry.text = "Enter a key to unlock the games"
            b.tvKeyMasked.text = k?.let { mask(it) } ?: "-"
            if (k != null && left <= 0) refreshLocks()
            return
        }
        b.tvPlanStatus.text = "ACTIVE"
        b.tvKeyMasked.text = mask(k)
        if (store.expiryMs == Long.MAX_VALUE) {
            b.tvTimeLeft.text = "LIFETIME"; b.tvExpiry.text = "Never expires"
        } else {
            val s = left / 1000
            b.tvTimeLeft.text = "%dd %02dh %02dm %02ds".format(s / 86400, s % 86400 / 3600, s % 3600 / 60, s % 60)
            b.tvExpiry.text = "Expires " + SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(store.expiryMs))
        }
    }

    private fun mask(k: String) = if (k.length <= 8) k else k.take(4) + "••••••••" + k.takeLast(4)

    // --------------------------------------------------------------- Seller
    private fun setupSeller() {
        b.btnBuyKey.setOnClickListener { open(LauncherConfig.BUY_KEY_URL) }
        b.btnGiveaway.setOnClickListener { open(LauncherConfig.GIVEAWAY_URL) }
    }

    // ------------------------------------------------------------- Settings
    private fun setupSettings() {
        b.tvDeviceId.text = "Device ID: $deviceId"
        b.tvDeviceId.setOnClickListener {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("device", deviceId))
            toast("Device ID copied")
        }
        b.btnGoogle.setOnClickListener { toggleGoogle() }
        b.btnEngineCheck.setOnClickListener { runEngineCheck() }
        b.btnOverlay.setOnClickListener {
            if (Settings.canDrawOverlays(this)) toast("Overlay permission already granted")
            else startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        updateGoogleButton()
    }

    private fun updateGoogleButton() {
        b.btnGoogle.isEnabled = false
        b.tvGoogleState.text = "Checking..."
        eng.execute {
            val ready = Engine.ensureReady(15_000) == null
            val on = ready && runCatching { BlackBoxCore.get().isInstallGms(LauncherConfig.VIRTUAL_USER) }.getOrDefault(false)
            ui.post {
                if (isFinishing) return@post
                b.btnGoogle.isEnabled = ready
                b.btnGoogle.text = if (on) "Remove Google login" else "Add Google login"
                b.tvGoogleState.text = when {
                    !ready -> "Engine is not ready yet - see Engine status below"
                    on -> "Google services installed in the virtual space"
                    else -> "Needed to sign in to games with Google"
                }
            }
        }
    }

    private fun toggleGoogle() {
        val u = LauncherConfig.VIRTUAL_USER
        val pd = ProgressDialog(this).apply { setMessage("Please wait..."); setCancelable(false); show() }
        eng.execute {
            val msg = try {
                val notReady = Engine.ensureReady()
                if (notReady != null) notReady else {
                    val core = BlackBoxCore.get()
                    if (core.isInstallGms(u)) { if (core.uninstallGms(u)) "Google services removed" else "Remove failed" }
                    else { val r = core.installGms(u); if (r != null && r.success) "Google services installed" else "Failed: ${r?.msg}" }
                }
            } catch (e: Throwable) { "Error: ${e.message}" }
            ui.post { runCatching { pd.dismiss() }; if (!isFinishing) { toast(msg); updateGoogleButton() } }
        }
    }

    // --------------------------------------------------------------- Engine
    /** Shows live engine state at the bottom of Settings; also warms the engine in the background. */
    private fun watchEngine() {
        b.tvEngineState.text = "Starting..."
        eng.execute {
            val err = Engine.ensureReady(40_000)
            ui.post {
                if (isFinishing) return@post
                b.tvEngineState.text = err ?: "Ready"
                b.tvEngineState.setTextColor(if (err == null) 0xFF6BD66B.toInt() else 0xFFFF6B5E.toInt())
            }
        }
    }

    private fun runEngineCheck() {
        val pd = ProgressDialog(this).apply { setMessage("Checking engine..."); setCancelable(false); show() }
        eng.execute {
            val text = try { Engine.report(this) } catch (e: Throwable) { "Report failed: ${Log.getStackTraceString(e)}" }
            ui.post {
                runCatching { pd.dismiss() }
                if (isFinishing) return@post
                val tv = TextView(this).apply {
                    this.text = text; setTextIsSelectable(true); textSize = 11f
                    setTextColor(0xFFFFFFFF.toInt()); typeface = android.graphics.Typeface.MONOSPACE
                    val pad = (16 * resources.displayMetrics.density).toInt(); setPadding(pad, pad / 2, pad, 0)
                }
                dialog().setTitle("Engine check")
                    .setView(android.widget.ScrollView(this).apply { addView(tv) })
                    .setPositiveButton("Copy") { _, _ ->
                        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText("engine", text))
                        toast("Copied")
                    }
                    .setNegativeButton("Close", null).show()
            }
        }
    }

    // -------------------------------------------------------------- helpers
    private fun dialog() = AlertDialog.Builder(this, R.style.FireDialog)
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
    private fun open(url: String) = runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}
