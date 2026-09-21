package com.kgr.systemtoolbox.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Rect
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.telecom.TelecomManager
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.kgr.systemtoolbox.core.RootShell
import com.kgr.systemtoolbox.modules.RecentsController
import com.kgr.systemtoolbox.modules.SlimRecentsController
import com.kgr.systemtoolbox.modules.ToolbeltController
import com.kgr.systemtoolbox.modules.ToolbeltController.ToolbeltAction
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Accessibility service backing Toolbelt and Slim List Recents on SystemToolbox.
 *
 * Ported from Key2Toolbox's Key2AccessibilityService, but deliberately lean:
 * only the pieces those two features need came over. Everything else in the
 * original file - Nav Lock, PIN-on-keyboard, IME block, IME suggestion
 * shortcuts, chat composer, calculator/in-call shortcuts, Auto-Focus - was
 * Key2 physical-keyboard-specific and doesn't apply to the P9P, so none of it
 * was ported. If SystemToolbox grows more accessibility-based features later,
 * they belong in this same service (only one AccessibilityService per app can
 * be bound at a time in the normal case) rather than a second one.
 */
class SystemAccessibilityService : AccessibilityService() {

    companion object {
        /**
         * True while this service instance is actually connected and bound.
         * Ground truth for "is accessibility enabled" - unlike reading
         * ENABLED_ACCESSIBILITY_SERVICES back from Settings.Secure, this
         * can't be silently withheld by the system/ROM; it's set directly
         * by the lifecycle callbacks below.
         */
        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var instance: SystemAccessibilityService? = null
            private set

        /** Coalesce window-event bursts into one immersive-state `dumpsys` probe. */
        private const val FULLSCREEN_PROBE_DEBOUNCE_MS = 200L

        const val PREFS = "systemtweaks"
    }

    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var imeActive = false        // real soft keyboard currently showing
    @Volatile private var foregroundPkg: String? = null
    @Volatile private var fullscreenCached = false // last known immersive state of the foreground app
    @Volatile private var fullscreenProbeInFlight = false

    private var prefs: SharedPreferences? = null
    private var screenReceiver: BroadcastReceiver? = null

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
        if (key == null) return@OnSharedPreferenceChangeListener
        if (key.startsWith("toolbelt_")) {
            // Anything that changes the reserved bottom inset needs a launcher
            // restart - a future nav-bar hook would only re-read that on
            // recreation, matching Key2Toolbox's original taskbar behavior.
            if (key == ToolbeltController.KEY_ENABLED ||
                key == ToolbeltController.KEY_HEIGHT_DP ||
                key == ToolbeltController.KEY_COLLAPSIBLE ||
                key == ToolbeltController.KEY_COLLAPSED ||
                key == ToolbeltController.KEY_COLOR_MODE
            ) {
                val on = prefs?.getBoolean(ToolbeltController.KEY_ENABLED, false) ?: false
                worker.execute {
                    if (key == ToolbeltController.KEY_ENABLED) {
                        ToolbeltController.pushGlobalActive(on)
                        ToolbeltController.syncNavMode(this)
                    }
                    // Only bounce the launcher if the reserved inset actually moved.
                    if (ToolbeltController.pushInset(this)) ToolbeltController.restartLauncher()
                }
            }
            refreshToolbelt(rebuild = true)
        }
    }

    /** (Re)attach or detach the toolbelt overlay to match current settings. */
    private fun refreshToolbelt(rebuild: Boolean = false) {
        // Keep the belt off the lockscreen - its buttons would be dead there.
        if (isDeviceLocked()) {
            ToolbeltOverlayController.hide()
            return
        }
        ToolbeltOverlayController.refresh(this, ::handleToolbeltAction, rebuild)
    }

    /** True while a cellular or VoIP call occupies the audio path. No permission needed. */
    private fun isInCall(): Boolean {
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION
    }

    /**
     * Every "open Recents" trigger (Toolbelt slot, physical app-switch key)
     * routes through here. [SlimRecentsController.listTasks] runs a root
     * shell command, so this always dispatches off [worker] - never build the
     * overlay on the calling thread.
     */
    private fun openRecents() {
        val mode = RecentsController.getLayoutMode(this)
        if (mode != RecentsController.LayoutMode.SLIM_LIST) {
            performGlobalAction(GLOBAL_ACTION_RECENTS)
            return
        }
        worker.execute {
            try {
                val tasks = SlimRecentsController.listTasks(this)
                mainHandler.post {
                    SlimRecentsOverlayController.show(this, tasks)
                    // Slim List's window just attached above the Toolbelt's in
                    // z-order (both are TYPE_ACCESSIBILITY_OVERLAY from this
                    // app; whichever attaches most recently wins). That leaves
                    // the belt visible but untouchable. There's no direct
                    // "bring to front" API for a window added via
                    // WindowManager - removing and re-adding is the only way
                    // to change stacking, so re-add it now to reclaim the top
                    // spot for its own bounds.
                    ToolbeltOverlayController.hide()
                    ToolbeltOverlayController.refresh(this, ::handleToolbeltAction)
                }
            } catch (t: Throwable) {
                Log.e("SystemToolbox", "openRecents failed", t)
            }
        }
    }

    private fun handleToolbeltAction(action: ToolbeltAction, arg: String?) {
        // Any toolbelt press should close Slim List first - none of the
        // actions below know or care that it might be open. RECENTS is the
        // one exception: openRecents() -> show() already refreshes an
        // already-open Slim List in place rather than a full close+reopen.
        if (action != ToolbeltAction.RECENTS && SlimRecentsOverlayController.isShowing()) {
            SlimRecentsOverlayController.hide()
        }
        when (action) {
            ToolbeltAction.NONE, ToolbeltAction.TOGGLE_BELT -> {} // handled in the overlay
            ToolbeltAction.LAUNCH_APP -> launchApp(arg)
            ToolbeltAction.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            ToolbeltAction.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            ToolbeltAction.RECENTS -> openRecents()
            ToolbeltAction.NOTIFICATIONS -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            ToolbeltAction.QUICK_SETTINGS -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            ToolbeltAction.POWER_DIALOG -> performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
            ToolbeltAction.LOCK_SCREEN -> performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            ToolbeltAction.SPLIT_SCREEN -> performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
            ToolbeltAction.SCREENSHOT ->
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R)
                    performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
                else worker.execute { RootShell.run("input keyevent 120") }
            ToolbeltAction.VOICE_ASSIST -> launchVoiceAssist()
            ToolbeltAction.DIALER_KEYPAD -> {
                try {
                    startActivity(Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {
                }
            }
            ToolbeltAction.DIALER_HOME -> {
                try {
                    val telecom = getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                    val dialerPkg = telecom?.defaultDialerPackage
                    val launchIntent = dialerPkg?.let { packageManager.getLaunchIntentForPackage(it) }
                    if (launchIntent != null) {
                        startActivity(launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } else {
                        startActivity(Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                } catch (_: Exception) {
                }
            }
            ToolbeltAction.LAST_APP -> {
                // Open Overview, then trigger it again: AOSP returns to the
                // previously focused task, i.e. "switch to last app".
                performGlobalAction(GLOBAL_ACTION_RECENTS)
                mainHandler.postDelayed({ performGlobalAction(GLOBAL_ACTION_RECENTS) }, 350)
            }
            ToolbeltAction.HANGUP -> worker.execute { RootShell.run("input keyevent 6") }
            ToolbeltAction.HANGUP_OR_HOME ->
                if (isInCall()) worker.execute { RootShell.run("input keyevent 6") }
                else performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    private fun launchApp(pkg: String?) {
        val target = pkg?.takeIf { it.isNotBlank() } ?: return
        val intent = packageManager.getLaunchIntentForPackage(target)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } ?: return
        try {
            startActivity(intent)
        } catch (_: Exception) {
            // app gone / not launchable
        }
    }

    private fun launchVoiceAssist() {
        val intent = Intent(Intent.ACTION_VOICE_COMMAND).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_ASSIST).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
                // no assistant installed
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        instance = this
        val p = getSharedPreferences(PREFS, MODE_PRIVATE)
        prefs = p
        p.registerOnSharedPreferenceChangeListener(prefListener)

        serviceInfo?.let { info ->
            info.flags = info.flags or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            serviceInfo = info
        }

        worker.execute {
            // Toolbelt: mirror the enabled state + live nav mode for a future
            // launcher hook, then bring the belt up if it's on.
            val toolbeltOn = prefs?.getBoolean(ToolbeltController.KEY_ENABLED, false) ?: false
            ToolbeltController.pushGlobalActive(toolbeltOn)
            ToolbeltController.syncNavMode(this)
            ToolbeltController.pushInset(this)
            mainHandler.post { refreshToolbelt(rebuild = true) }
        }

        val rx = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                    // A TYPE_ACCESSIBILITY_OVERLAY window can render above the
                    // keyguard and doesn't tear itself down just because the
                    // screen locked - close it immediately so it can never be
                    // caught sitting on top of the lockscreen.
                    SlimRecentsOverlayController.hide()
                    mainHandler.post { refreshToolbelt() }
                } else if (intent?.action == Intent.ACTION_SCREEN_ON ||
                    intent?.action == Intent.ACTION_USER_PRESENT
                ) {
                    mainHandler.post { refreshToolbelt() }
                }
            }
        }
        registerReceiver(rx, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        })
        screenReceiver = rx
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        imeActive = isImeVisible()

        val pkg = foregroundAppPackage()
        val pkgChanged = pkg != null && pkg != foregroundPkg
        if (pkgChanged) {
            foregroundPkg = pkg
        }

        // Toolbelt: keep the belt attached; slide it away while the soft
        // keyboard is up or the foreground app is fullscreen/immersive. The
        // immersive check is async + cached (see [scheduleFullscreenProbe]);
        // every event just re-applies the last known value.
        ToolbeltOverlayController.setImeVisible(imeActive, anyImeWindow())
        ToolbeltOverlayController.setForegroundFullscreen(fullscreenCached)
        refreshToolbelt()
        scheduleFullscreenProbe(event, pkgChanged)
    }

    // --------------------------------------------------------------- helpers

    /**
     * The package of the focused/active TYPE_APPLICATION window - i.e. the app
     * behind any keyboard. Reading the application window (not the event source)
     * keeps this stable while the IME window comes and goes.
     */
    private fun foregroundAppPackage(): String? {
        val windowList: List<AccessibilityWindowInfo> = try {
            windows ?: return null
        } catch (_: Exception) {
            return null
        }
        for (w in windowList) {
            if (w.type == AccessibilityWindowInfo.TYPE_APPLICATION && (w.isActive || w.isFocused)) {
                val root = w.root ?: continue
                val pkg = root.packageName?.toString()
                root.recycle()
                if (pkg != null) return pkg
            }
        }
        return null
    }

    private val fullscreenProbeRunnable = Runnable { runFullscreenProbe() }

    /**
     * Ask for a fresh immersive-state probe, but only on window-shaped events
     * (app switch, windows changed, window state changed) and coalesced through
     * a short delay so a burst of events triggers one `dumpsys` at most.
     */
    private fun scheduleFullscreenProbe(event: AccessibilityEvent?, pkgChanged: Boolean) {
        val t = event?.eventType
        val windowish = pkgChanged ||
            t == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
            t == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        if (!windowish) return
        mainHandler.removeCallbacks(fullscreenProbeRunnable)
        mainHandler.postDelayed(fullscreenProbeRunnable, FULLSCREEN_PROBE_DEBOUNCE_MS)
    }

    private fun runFullscreenProbe() {
        if (fullscreenProbeInFlight) return
        fullscreenProbeInFlight = true
        val fallback = isForegroundFullscreenByStrip()
        worker.execute {
            val value = try { probeImmersiveViaDump() } catch (_: Exception) { null } ?: fallback
            fullscreenProbeInFlight = false
            if (value != fullscreenCached) {
                fullscreenCached = value
                mainHandler.post {
                    ToolbeltOverlayController.setForegroundFullscreen(value)
                    refreshToolbelt()
                }
            }
        }
    }

    /**
     * Whether the foreground app window is *requesting* an immersive
     * (status-bar-hidden) layout, read from `dumpsys window`. `null` = the
     * foreground app window couldn't be resolved, caller keeps the last value.
     *
     * Keys off the app's requested inset visibility, not whether a bar is on
     * screen right now, so a transient status-bar reveal (the privacy chip
     * flash on a location/mic/camera hit, or a deliberate swipe-to-peek) does
     * not read as "left fullscreen" and does not bounce the belt back in.
     *
     * Key2Toolbox's original version found the foreground window via a
     * `mCurrentFocus=Window{<hash>` line, then located that same hash's own
     * block further down the dump. That line does not exist at all in
     * `dumpsys window windows` on the P9P's Android 17 Beta 3 build (verified
     * against a real dump - grepped for it and every focus-related field,
     * nothing). Instead: of all windows with `ty=BASE_APPLICATION`, exactly
     * one reports `isVisible=true` at a time - every backgrounded app's
     * window (confirmed: Kvesitso, f-droid, Firefox, SystemToolbox, etc. all
     * still appear in the dump, all `isVisible=false`) - so that's the
     * foreground app in practice. The three FULLSCREEN/immersive markers
     * themselves are unchanged on this build and still appear in that block.
     */
    private fun probeImmersiveViaDump(): Boolean? {
        val out = RootShell.run("dumpsys window windows").outString
        if (out.isBlank()) return null
        val blocks = out.split(Regex("""(?m)^  Window #\d+ """)).drop(1)
        val appBlock = blocks.firstOrNull { b ->
            b.contains("ty=BASE_APPLICATION") && Regex("""\bisVisible=true\b""").containsMatchIn(b)
        } ?: return null
        return Regex("""Requested non-default-visibility types:[^\n]*\bstatusBars\b""").containsMatchIn(appBlock) ||
            Regex("""vsysui=[^\n]*(FULLSCREEN|IMMERSIVE)""").containsMatchIn(appBlock) ||
            Regex("""\bfl=[^\n]*\bFULLSCREEN\b""").containsMatchIn(appBlock)
    }

    /**
     * Fallback immersive check: the foreground app is fullscreen when the system
     * status-bar strip is absent from the accessibility window list. Cheap and
     * root-free, but fooled by a transient bar reveal - hence it only backs up
     * [probeImmersiveViaDump] when the `dumpsys` parse fails.
     */
    private fun isForegroundFullscreenByStrip(): Boolean {
        val list = try { windows ?: return false } catch (_: Exception) { return false }
        if (list.none { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }) return false
        val strip = (32 * resources.displayMetrics.density).toInt()
        val hasStatusBar = list.any { w ->
            if (w.type != AccessibilityWindowInfo.TYPE_SYSTEM) return@any false
            val b = Rect().also { w.getBoundsInScreen(it) }
            b.top <= 0 && b.height() in 1..strip
        }
        return !hasStatusBar
    }

    private fun anyImeWindow(): Boolean = try {
        windows?.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } ?: false
    } catch (_: Exception) {
        false
    }

    /** Only a real, tall soft keyboard counts as "the IME is up" for belt auto-hide. */
    private fun isImeVisible(): Boolean {
        val windowList: List<AccessibilityWindowInfo> = try {
            windows ?: return false
        } catch (_: Exception) {
            return false
        }
        val imeWindows = windowList.filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        if (imeWindows.isEmpty()) return false
        val minPx = (100 * resources.displayMetrics.density).toInt()
        val tallest = imeWindows.maxOf { w ->
            Rect().also { w.getBoundsInScreen(it) }.height()
        }
        return tallest >= minPx
    }

    private fun isDeviceLocked(): Boolean {
        val km = getSystemService(KEYGUARD_SERVICE) as? KeyguardManager
        return km?.isKeyguardLocked ?: false
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return false
        val kc = event.keyCode

        // Slim List escape hatch: while the overlay is showing, Back/Home/
        // app-switch always close it, unconditionally. This has to be first
        // and unconditional - the overlay is FLAG_NOT_FOCUSABLE so it can't
        // otherwise receive keys.
        if (SlimRecentsOverlayController.isShowing()) {
            when (kc) {
                KeyEvent.KEYCODE_BACK -> {
                    if (event.action == KeyEvent.ACTION_DOWN) SlimRecentsOverlayController.hide()
                    return true
                }
                KeyEvent.KEYCODE_HOME -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        SlimRecentsOverlayController.hide()
                        performGlobalAction(GLOBAL_ACTION_HOME)
                    }
                    return true
                }
                KeyEvent.KEYCODE_APP_SWITCH -> {
                    if (event.action == KeyEvent.ACTION_DOWN) openRecents() // re-show with a fresh task list
                    return true
                }
            }
        }
        return false
    }

    // ------------------------------------------------------------- Lifecycle

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        isRunning = false
        teardownToolbelt()
        return super.onUnbind(intent)
    }

    /**
     * Drop the belt and clear the SystemUI-hook mirror: with no accessibility
     * service there is no overlay to stand in for the nav bar.
     * onServiceConnected pushes the flag back if the module is still enabled.
     */
    private fun teardownToolbelt() {
        ToolbeltOverlayController.hide()
        SlimRecentsOverlayController.hide()
        try {
            RootShell.run("settings put global ${ToolbeltController.GLOBAL_ACTIVE} 0")
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        isRunning = false
        instance = null
        teardownToolbelt()
        prefs?.unregisterOnSharedPreferenceChangeListener(prefListener)
        screenReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {}
        }
        worker.shutdown()
        super.onDestroy()
    }
}
