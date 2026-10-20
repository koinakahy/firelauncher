package top.niunaijun.blackboxa.launcher

/** Edit these values only. Never put an admin/product token in the app. */
object LauncherConfig {
    const val BRAND = "FIRE LAUNCHER"

    // Firebase console -> Realtime Database -> copy the URL shown at the top
    const val FIREBASE_DB_URL = "https://mazlauncher-default-rtdb.firebaseio.com"

    // Used by the Seller tab
    const val BUY_KEY_URL = "https://t.me/your_seller"
    const val GIVEAWAY_URL = "https://t.me/your_channel"

    data class Game(val title: String, val pkg: String)

    val GAMES = listOf(
        Game("Carrom Pool", "com.miniclip.carrom"),
        Game("8 Ball Pool", "com.miniclip.eightballpool")
    )

    const val VIRTUAL_USER = 0
}
