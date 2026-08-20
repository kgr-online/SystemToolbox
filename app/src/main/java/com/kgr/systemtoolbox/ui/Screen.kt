package com.kgr.systemtoolbox.ui

enum class AccessType(val label: String) {
    ROOT("root"),
    ACCESSIBILITY("accessibility"),
    NOTIFICATION("notification")
}

sealed class Screen(
    val title: String,
    val subtitle: String = "",
    val access: List<AccessType> = emptyList()
) {
    data object Home : Screen("System Toolbox")

    // System tab
    data object AdBlock : Screen(
        "AdBlock", "Systemless hosts-based ad & tracker blocking",
        listOf(AccessType.ROOT)
    )
    data object DenylistManager : Screen(
        "Denylist Manager", "Manage Magisk DenyList and Zygisk-Hide together",
        listOf(AccessType.ROOT)
    )
    data object Zram : Screen(
        "ZRAM", "Compression algorithm and size",
        listOf(AccessType.ROOT)
    )
    data object WirelessAdb : Screen(
        "Persistent Wireless ADB", "Static port, survives reboot",
        listOf(AccessType.ROOT)
    )
    data object PlayStoreTagger : Screen(
        "Play Store Tagger", "Retag apps as Play Store installs",
        listOf(AccessType.ROOT)
    )
}
