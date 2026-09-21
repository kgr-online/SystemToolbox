package com.kgr.systemtoolbox.modules

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kgr.systemtoolbox.core.RootShell
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class FilterMode(val label: String) {
    NON_PLAY("Non-Play"),
    ALL("All"),
    PLAY_STORE("Play Store"),
    FDROID("F-Droid"),
    AURORA("Aurora Store"),
    PACKAGE_INSTALLER("Package Installer")
}

// "Manual install" covers both known Package Installer UI package names (varies
// by AOSP/OEM flavor) and a null/empty installer, since `pm install` from ADB or
// a root shell leaves the installer package unset - that's still effectively a
// manual/sideloaded install, just without the Package Installer UI in the loop.
private val PACKAGE_INSTALLER_PACKAGES = setOf(
    "com.android.packageinstaller",
    "com.google.android.packageinstaller"
)
enum class TagMode { TAG, UNTAG }

sealed class TaggerUiState {
    object Loading : TaggerUiState()
    object NoRoot : TaggerUiState()
    data class Ready(
        val apps: List<AppInfo>,
        val filter: FilterMode,
        val query: String,
        val showSystem: Boolean,
        val tagMode: TagMode
    ) : TaggerUiState()
    data class Tagging(
        val progress: Int,
        val total: Int,
        val currentApp: String,
        val log: String,
        val tagMode: TagMode
    ) : TaggerUiState()
    data class Done(
        val results: Map<String, String?>,
        val apps: List<AppInfo>,
        val filter: FilterMode,
        val query: String,
        val showSystem: Boolean,
        val log: String,
        val tagMode: TagMode
    ) : TaggerUiState()
    data class Error(val message: String) : TaggerUiState()
}

class PlayStoreTaggerViewModel : ViewModel() {

    private val _uiState = MutableStateFlow<TaggerUiState>(TaggerUiState.Loading)
    val uiState: StateFlow<TaggerUiState> = _uiState.asStateFlow()

    private var allApps: List<AppInfo> = emptyList()
    private var currentFilter = FilterMode.NON_PLAY
    private var currentQuery = ""
    private var showSystem = false
    private var currentTagMode = TagMode.TAG
    private val logBuilder = StringBuilder()

    fun load(context: Context) {
        viewModelScope.launch {
            _uiState.value = TaggerUiState.Loading
            withContext(Dispatchers.IO) {
                if (!RootShell.isRootAvailable()) {
                    _uiState.value = TaggerUiState.NoRoot
                    return@withContext
                }
                try {
                    allApps = loadApps(context)
                    _uiState.value = TaggerUiState.Ready(filteredApps(), currentFilter, currentQuery, showSystem, currentTagMode)
                } catch (e: Exception) {
                    _uiState.value = TaggerUiState.Error(e.message ?: "Failed to load apps")
                }
            }
        }
    }

    fun setFilter(mode: FilterMode) { currentFilter = mode; updateReady() }
    fun setQuery(q: String) { currentQuery = q.trim(); updateReady() }
    fun setShowSystem(value: Boolean) { showSystem = value; updateReady() }
    fun getShowSystem() = showSystem
    fun setTagMode(mode: TagMode) {
        currentTagMode = mode
        // When switching to untag, default to ALL so Play-tagged apps are visible.
        // When switching to tag, default back to NON_PLAY.
        currentFilter = if (mode == TagMode.UNTAG) FilterMode.ALL else FilterMode.NON_PLAY
        clearSelection()
    }

    fun toggleSelection(packageName: String) {
        allApps = allApps.map {
            if (it.packageName == packageName) it.copy(isSelected = !it.isSelected) else it
        }
        updateReady()
    }

    fun selectAll() {
        val visible = filteredApps().map { it.packageName }.toSet()
        allApps = allApps.map {
            if (it.packageName in visible) it.copy(isSelected = true) else it
        }
        updateReady()
    }

    fun clearSelection() {
        allApps = allApps.map { it.copy(isSelected = false) }
        updateReady()
    }

    fun selectedCount(): Int = allApps.count { it.isSelected }

    fun tagSelected() {
        val selected = allApps.filter { it.isSelected }
        if (selected.isEmpty()) return
        logBuilder.clear()
        val mode = currentTagMode

        viewModelScope.launch {
            val results = mutableMapOf<String, String?>()
            withContext(Dispatchers.IO) {
                selected.forEachIndexed { index, app ->
                    appendLog("─── ${app.label} (${index + 1}/${selected.size})")
                    appendLog("    pkg: ${app.packageName}")

                    withContext(Dispatchers.Main) {
                        _uiState.value = TaggerUiState.Tagging(
                            progress = index + 1,
                            total = selected.size,
                            currentApp = app.label,
                            log = logBuilder.toString(),
                            tagMode = mode
                        )
                    }

                    val error = when (mode) {
                        TagMode.TAG -> PlayStoreTaggerManager.setPlayInstaller(app.packageName) { line ->
                            appendLog("    $line")
                            viewModelScope.launch(Dispatchers.Main) {
                                _uiState.value = TaggerUiState.Tagging(
                                    progress = index + 1,
                                    total = selected.size,
                                    currentApp = app.label,
                                    log = logBuilder.toString(),
                                    tagMode = mode
                                )
                            }
                        }
                        TagMode.UNTAG -> PlayStoreTaggerManager.clearInstaller(app.packageName) { line ->
                            appendLog("    $line")
                            viewModelScope.launch(Dispatchers.Main) {
                                _uiState.value = TaggerUiState.Tagging(
                                    progress = index + 1,
                                    total = selected.size,
                                    currentApp = app.label,
                                    log = logBuilder.toString(),
                                    tagMode = mode
                                )
                            }
                        }
                    }

                    results[app.packageName] = error
                    if (error == null) appendLog("    ✓ Success")
                    else appendLog("    ✗ $error")
                    appendLog("")
                }

                allApps = allApps.map { app ->
                    if (app.packageName in results) {
                        val newInstaller = PlayStoreTaggerManager.getInstaller(app.packageName)
                        app.copy(installerPackage = newInstaller, isSelected = false)
                    } else app
                }
            }

            _uiState.value = TaggerUiState.Done(
                results = results,
                apps = filteredApps(),
                filter = currentFilter,
                query = currentQuery,
                showSystem = showSystem,
                log = logBuilder.toString(),
                tagMode = mode
            )
        }
    }

    fun dismissResults() { updateReady() }

    private fun appendLog(line: String) { logBuilder.appendLine(line) }

    private fun updateReady() {
        val current = _uiState.value
        if (current is TaggerUiState.Ready || current is TaggerUiState.Done) {
            _uiState.value = TaggerUiState.Ready(filteredApps(), currentFilter, currentQuery, showSystem, currentTagMode)
        }
    }

    private fun filteredApps(): List<AppInfo> {
        var list = allApps.filter { if (!showSystem) !it.isSystem else true }
        list = when (currentFilter) {
            FilterMode.ALL -> list
            FilterMode.NON_PLAY -> list.filter { !it.isPlayInstalled }
            FilterMode.PLAY_STORE -> list.filter { it.installerPackage == "com.android.vending" }
            FilterMode.FDROID -> list.filter { it.installerPackage == "org.fdroid.fdroid" }
            FilterMode.AURORA -> list.filter { it.installerPackage == "com.aurora.store" }
            FilterMode.PACKAGE_INSTALLER -> list.filter {
                it.installerPackage.isNullOrEmpty() ||
                    it.installerPackage in PACKAGE_INSTALLER_PACKAGES
            }
        }
        if (currentQuery.isNotEmpty()) {
            val q = currentQuery.lowercase()
            list = list.filter {
                it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
            }
        }
        return list.sortedBy { it.label.lowercase() }
    }

    private fun loadApps(context: Context): List<AppInfo> {
        val pm = context.packageManager

        // Enumerate via a root `pm list packages` rather than
        // PackageManager.getInstalledApplications(): on API 30+ (this app
        // targets 34) that call is subject to package-visibility filtering,
        // which can silently under-report installed packages even with
        // QUERY_ALL_PACKAGES declared. A root `pm list packages` always
        // matches exactly what `adb shell pm list packages` would show, so
        // it can't miss anything that's actually installed.
        val allResult = Shell.cmd("pm list packages").exec()
        val systemResult = Shell.cmd("pm list packages -s").exec()

        fun parseNames(lines: List<String>) = lines
            .mapNotNull { it.removePrefix("package:").trim().takeIf(String::isNotEmpty) }

        val systemPackages = parseNames(systemResult.out).toSet()
        val packageNames = parseNames(allResult.out).filter { it != context.packageName }

        // Read every installer in one root-shell pass rather than one
        // PackageManager/root call per app. See PlayStoreTaggerManager.getAllInstallers
        // for why the in-process PackageManager read (getInstallSourceInfo /
        // getInstallerPackageName) isn't used here - it was confirmed to
        // return incorrect/empty data on this ROM even when the equivalent
        // root-shell read (`dumpsys package | grep installerPackageName=`)
        // is correct. This was also the root cause of every app showing as
        // "sideloaded" regardless of actual installer, and of the list not
        // reflecting a tag after it completed.
        val installers = PlayStoreTaggerManager.getAllInstallers()

        return packageNames.mapNotNull { pkgName ->
            val installer = installers[pkgName]

            try {
                val appInfo = pm.getApplicationInfo(pkgName, PackageManager.GET_META_DATA)
                val label = pm.getApplicationLabel(appInfo).toString()
                val icon = try { pm.getApplicationIcon(appInfo) } catch (_: Exception) { pm.defaultActivityIcon }
                val isSystem = pkgName in systemPackages || (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                AppInfo(
                    packageName = pkgName,
                    label = label,
                    icon = icon,
                    installerPackage = installer,
                    isSystem = isSystem
                )
            } catch (e: PackageManager.NameNotFoundException) {
                // PackageManager still won't resolve this package (rare - e.g. a
                // package installed for a different user profile, or one mid-
                // uninstall). Show it by package name rather than silently
                // dropping it, since `pm list packages` did report it as present.
                AppInfo(
                    packageName = pkgName,
                    label = pkgName,
                    icon = pm.defaultActivityIcon,
                    installerPackage = installer,
                    isSystem = pkgName in systemPackages
                )
            }
        }
    }
}
