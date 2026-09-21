package com.kgr.systemtoolbox.modules

import android.content.Context
import android.content.SharedPreferences

/**
 * Recents mode switch for SystemToolbox: Stock (system default Overview) or
 * Slim List (a thumbnail-free vertical task list drawn as our own
 * accessibility overlay - see
 * [com.kgr.systemtoolbox.service.SlimRecentsOverlayController]).
 *
 * Trimmed from Key2Toolbox's RecentsController, which also had Grid (an
 * LSPosed hook forcing Launcher3's tablet two-row Overview) and Masonry (an
 * overlay snapshot quilt). Both are dropped here - Grid needs its own
 * decompile-and-hook pass against Pixel Launcher (not done), and Masonry's
 * snapshot/screenshot machinery was cut along with it to keep this port
 * scoped to Slim List only.
 *
 * Because Slim List never touches the launcher process - unlike Grid, which
 * needed a world-readable Settings.Global key for its Xposed hook to read
 * with no permission - this is plain in-app SharedPreferences instead of a
 * root-written Settings.Global mirror. Cheaper (no root shell spawn to read
 * or write) and there is nothing outside this process that needs to see it.
 * If a future hook ever needs this value, move it back to Settings.Global at
 * that point.
 */
object RecentsController {

    private const val PREFS = "systemtweaks"
    private const val KEY_LAYOUT_MODE = "recents_layout_mode"

    enum class LayoutMode(val value: Int) {
        STOCK(0),

        /** Standalone vertical task list, thumbnail-free - see
         *  [com.kgr.systemtoolbox.service.SlimRecentsOverlayController]. Never
         *  touches the launcher process; showing it is intercepted in
         *  [com.kgr.systemtoolbox.service.SystemAccessibilityService] before
         *  GLOBAL_ACTION_RECENTS would fire. */
        SLIM_LIST(1);

        companion object {
            fun fromValue(v: Int?): LayoutMode = entries.firstOrNull { it.value == v } ?: STOCK
        }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getLayoutMode(context: Context): LayoutMode =
        LayoutMode.fromValue(prefs(context).getInt(KEY_LAYOUT_MODE, LayoutMode.STOCK.value))

    fun setLayoutMode(context: Context, mode: LayoutMode) {
        prefs(context).edit().putInt(KEY_LAYOUT_MODE, mode.value).apply()
    }
}
