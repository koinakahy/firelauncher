# Startup / loading-screen fix

Symptoms: app sits on the splash (grey screen + icon) or on "Launching..." and never opens the game.

Causes found in the BlackBox core (Bcore-release.aar) and how the launcher now avoids them:

1. Frozen UI after a swallowed crash - the core installs a default exception handler that swallows some
   crashes. On the main thread that leaves the process alive with a dead Looper (frozen splash, nothing
   logged). `CrashRecorder` is now installed last: it records the crash, and kills the process if the main
   thread's crash was swallowed. Crash is shown by WelcomeActivity on next start.
2. Main thread blocked in Application.onCreate - `BlackBoxCore.doCreate()` makes IPC calls to the engine
   process (":black"). If that is slow/dead it can block for a minute. In the UI process it now runs on a
   worker thread; the UI waits at most 4 s.
3. Wrong "installed" answer - `BPackageManager.isInstalled()/getLaunchIntentForPackage()` answer from the
   PHONE's package manager until the engine binder has been created once. The old launcher therefore skipped
   installing the game into the virtual space and then hung on the loading screen. `Engine.ensureReady()`
   creates the binders first, and installs are verified (`InstallResult.success` is true even on failure).
4. Engine calls on the UI thread (Google-services check in LauncherActivity.onCreate) - moved to a worker.
5. WelcomeActivity scanned every installed app at start-up for no reason - removed.

New: Settings > "Engine status" + "Engine check" (copyable report). Full engine log is written by the core to
Downloads/logs/<package>_logcat.txt.
