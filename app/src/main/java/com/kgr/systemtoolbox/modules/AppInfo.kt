package com.kgr.systemtoolbox.modules

import android.graphics.drawable.Drawable

data class AppInfo(
    val packageName: String,
    val label: String,
    val icon: Drawable,
    val installerPackage: String?,
    val isSystem: Boolean = false,
    val isSelected: Boolean = false
) {
    val installerLabel: String
        get() = when (installerPackage) {
            "com.android.vending"          -> "Play Store"
            "org.fdroid.fdroid"            -> "F-Droid"
            "com.aurora.store"             -> "Aurora Store"
            null, ""                       -> "Unknown / Sideloaded"
            "com.android.packageinstaller",
            "com.google.android.packageinstaller" -> "Package Installer"
            "adb"                          -> "ADB"
            else                           -> installerPackage
        }

    val isPlayInstalled: Boolean
        get() = installerPackage == "com.android.vending"
}

sealed class TagResult {
    data class Success(val packageName: String) : TagResult()
    data class Failure(val packageName: String, val error: String) : TagResult()
}
