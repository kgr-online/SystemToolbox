package com.kgr.systemtoolbox.xposed

import android.content.Context
import android.graphics.Insets
import android.provider.Settings
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * LSPosed-only hook that hides the on-screen navigation bar and disables the
 * **bottom** swipe-up gesture (home / recents / quickswitch), so SystemToolbox's
 * "toolbelt" ([com.kgr.systemtoolbox.service.ToolbeltOverlayController]) can
 * stand in for it. The **edge** back-gesture is deliberately left alone.
 *
 * Gated live on the world-readable `Settings.Global` key
 * [com.kgr.systemtoolbox.modules.ToolbeltController.GLOBAL_ACTIVE] (written
 * with root by the app). Flag 0 -> every hook falls through to stock
 * behaviour.
 *
 * Ported from Key2Toolbox's NavBarHookInit, which targeted AOSP Launcher3
 * packaged as `org.lineageos.trebuchet`/`com.android.launcher3` on the Key2's
 * LineageOS 22.2. The P9P runs Kvesitso as the home-screen surface, but
 * Overview/Taskbar/gesture-nav is still owned by Pixel Launcher
 * (`com.google.android.apps.nexuslauncher`) - Google's own fork of the same
 * QuickStep/Launcher3 codebase, decompiled and cross-checked against this
 * device's actual build (Android 17 Beta 3) before writing this:
 *
 *  1. `com.android.launcher3.taskbar.TaskbarStashController
 *     .getContentHeightToReportToApps()` and
 *     `com.android.launcher3.taskbar.TaskbarInsetsController
 *     .getInsetsForGravity[WithCutout]` are UNCHANGED from Key2Toolbox's
 *     Trebuchet target - same class names, same method signatures. These
 *     report the belt height (read live from `Settings.Global`
 *     [com.kgr.systemtoolbox.modules.ToolbeltController.GLOBAL_INSET_PX]) as
 *     both the taskbar's own content height and the system inset, so app
 *     content ends above the belt instead of being hidden behind it. The
 *     taskbar only re-reads this on recreation, so the app restarts Pixel
 *     Launcher when the height / enabled state changes.
 *  2. The bottom swipe-up's actual input handling MOVED on this build: K2TB's
 *     `TouchInteractionService.onInputEvent` doesn't exist here (confirmed -
 *     grepping the decompiled source turned up nothing under that class).
 *     The method - same name, same signature, `onInputEvent(InputEvent):void`
 *     - is now on `com.android.quickstep.TouchInteractionHandler` instead.
 *     Skip it entirely while the belt is active; the edge back-gesture is a
 *     separate SystemUI input monitor and is not touched.
 *
 * Class / method names drift between builds - every hook is wrapped and logs
 * whether it attached. `adb logcat | grep SystemToolbox-Xposed`
 */
class NavBarHookInit : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "SystemToolbox-Xposed"
        private const val SELF_PKG = "com.kgr.systemtoolbox"

        private const val LAUNCHER_PKG = "com.google.android.apps.nexuslauncher"

        private const val TASKBAR_STASH = "com.android.launcher3.taskbar.TaskbarStashController"
        private const val TASKBAR_INSETS = "com.android.launcher3.taskbar.TaskbarInsetsController"

        /** Moved here from TouchInteractionService on this build - see class doc. */
        private const val TOUCH_INTERACTION_HANDLER = "com.android.quickstep.TouchInteractionHandler"
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        when (lpparam.packageName) {
            SELF_PKG -> selfProbe(lpparam.classLoader)
            LAUNCHER_PKG -> {
                XposedBridge.log("[$TAG] loaded in Pixel Launcher, installing toolbelt nav hooks")
                hookTaskbarInsets(lpparam.classLoader)
                hookBottomSwipeGesture(lpparam.classLoader)
            }
        }
    }

    // --- config --------------------------------------------------------

    private fun active(): Boolean {
        val ctx = currentApplication() ?: return false
        return try {
            Settings.Global.getInt(
                ctx.contentResolver, "systoolbox_toolbelt_active", 0
            ) == 1
        } catch (t: Throwable) {
            false
        }
    }

    /** Bottom inset (px) to reserve for the belt, or -1 when the module is off. */
    private fun beltInsetPx(): Int {
        val ctx = currentApplication() ?: return -1
        return try {
            if (Settings.Global.getInt(ctx.contentResolver, "systoolbox_toolbelt_active", 0) != 1) return -1
            val fallback = (54 * ctx.resources.displayMetrics.density).toInt()
            Settings.Global.getInt(ctx.contentResolver, "systoolbox_toolbelt_inset_px", fallback)
        } catch (t: Throwable) {
            -1
        }
    }

    // --- taskbar (= nav bar + its inset on this device) --------------

    private fun hookTaskbarInsets(cl: ClassLoader) {
        var stashHooked = false
        runCatching {
            val stash = XposedHelpers.findClass(TASKBAR_STASH, cl)
            val beltHeight = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val px = beltInsetPx()
                    if (px >= 0) param.result = px
                }
            }
            XposedHelpers.findAndHookMethod(stash, "getContentHeightToReportToApps", beltHeight)
            stashHooked = true
        }
        var insetsHooked = 0
        runCatching {
            val insets = XposedHelpers.findClass(TASKBAR_INSETS, cl)
            val beltInset = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val px = beltInsetPx()
                    if (px >= 0 && param.result is Insets) {
                        param.result = Insets.of(0, 0, 0, px)
                    }
                }
            }
            for (m in listOf("getInsetsForGravity", "getInsetsForGravityWithCutout")) {
                runCatching { XposedBridge.hookAllMethods(insets, m, beltInset); insetsHooked++ }
            }
        }
        XposedBridge.log("[$TAG] taskbar hooks: stash=$stashHooked insets=$insetsHooked")
    }

    // --- bottom swipe-up gesture ------------------------------------

    /**
     * `TouchInteractionHandler.onInputEvent(InputEvent)` is this build's entry
     * point for the launcher's "swipe-up" input monitor (home / recents /
     * quickswitch / assistant corner) - moved here from
     * `TouchInteractionService` on Trebuchet, same signature. Skip it entirely
     * while the belt is active. The edge back-gesture is handled elsewhere
     * and is unaffected.
     */
    private fun hookBottomSwipeGesture(cl: ClassLoader) {
        var hooked = false
        runCatching {
            val tih = XposedHelpers.findClass(TOUCH_INTERACTION_HANDLER, cl)
            XposedBridge.hookAllMethods(tih, "onInputEvent", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (active()) param.result = null
                }
            })
            hooked = true
        }
        XposedBridge.log("[$TAG] TouchInteractionHandler.onInputEvent hook attached=$hooked")
    }

    // --- helpers -----------------------------------------------------

    private fun selfProbe(cl: ClassLoader) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                "com.kgr.systemtoolbox.modules.ToolbeltController", cl, "isXposedActive",
                XC_MethodReplacement.returnConstant(true)
            )
        }
    }

    private fun currentApplication(): Context? = try {
        val at = XposedHelpers.callStaticMethod(
            XposedHelpers.findClass("android.app.ActivityThread", null),
            "currentActivityThread"
        )
        XposedHelpers.callMethod(at, "getApplication") as? Context
    } catch (t: Throwable) {
        null
    }
}
