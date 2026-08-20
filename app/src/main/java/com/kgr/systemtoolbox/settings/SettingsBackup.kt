package com.kgr.systemtoolbox.settings

import android.content.Context
import android.net.Uri
import com.kgr.systemtoolbox.modules.AdBlockController
import com.kgr.systemtoolbox.modules.ZramController
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exports/imports System Toolbox's ZRAM and AdBlock state to/from a single
 * JSON document via Storage Access Framework Uris.
 *
 * Supports SELECTIVE backup/restore per [BackupModule] - the caller passes
 * which modules to include.
 *
 * {
 *   "app_version": "1.0",
 *   "exported_at": "2026-07-15T22:10:00Z",
 *   "zram": { "size_mb": 3072, "algorithm": "zstd", "swappiness": 60 },
 *   "adblock": {
 *     "enabled": true,
 *     "sources": ["https://..."],
 *     "user_added": ["127.0.0.1 ads.example.com"],
 *     "wildcard_added": ["*.doubleclick.net"],
 *     "user_removed": ["reddit.com"],
 *     "whitelist": ["*.reddit.com"]
 *   }
 * }
 *
 * Play Store Tagger and Denylist Manager are intentionally NOT supported
 * here (separate app sandbox / live root state rather than persisted prefs).
 */
object SettingsBackup {

    /** Modules the backup/restore UI lets the user select individually. */
    enum class BackupModule(val label: String) {
        ZRAM("ZRAM"),
        AD_BLOCK("AdBlock")
    }

    // AdBlockController.PERSISTED_DATA_FILES entries, mapped to their JSON key names.
    private val ADBLOCK_FILE_TO_JSON_KEY = mapOf(
        "sources.txt" to "sources",
        "user_added.txt" to "user_added",
        "wildcard_added.txt" to "wildcard_added",
        "user_removed.txt" to "user_removed",
        "whitelist.txt" to "whitelist"
    )

    /**
     * @param modules which modules to include. Defaults to all supported
     * modules (unchanged behavior from before selective backup existed).
     */
    fun exportToJson(
        context: Context,
        appVersion: String,
        modules: Set<BackupModule> = BackupModule.entries.toSet()
    ): JSONObject {
        val root = JSONObject()
        root.put("app_version", appVersion)
        root.put(
            "exported_at",
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date())
        )

        // ZRAM isn't a SharedPreferences module - only include it if selected AND
        // a script is actually persisted, and only if all three values parsed cleanly.
        if (BackupModule.ZRAM in modules && ZramController.isPersisted()) {
            val sizeMb = ZramController.persistedSize()?.mb
            val algorithm = ZramController.persistedAlgorithm()
            val swappiness = ZramController.persistedSwappiness()
            if (sizeMb != null && algorithm != null && swappiness != null) {
                val zramJson = JSONObject()
                zramJson.put("size_mb", sizeMb)
                zramJson.put("algorithm", algorithm)
                zramJson.put("swappiness", swappiness)
                root.put("zram", zramJson)
            }
        }

        // AdBlock: only include if selected AND the module has actually been installed
        // (nothing persisted to back up otherwise). Blob files are stored as line arrays
        // rather than raw strings so the JSON stays diffable/readable.
        if (BackupModule.AD_BLOCK in modules && AdBlockController.isInstalled()) {
            val adBlockJson = JSONObject()
            adBlockJson.put("enabled", AdBlockController.isEnabled())
            for ((fileName, jsonKey) in ADBLOCK_FILE_TO_JSON_KEY) {
                val lines = AdBlockController.readPersistedFile(fileName)
                    .split("\n")
                    .filter { it.isNotBlank() }
                adBlockJson.put(jsonKey, JSONArray(lines))
            }
            root.put("adblock", adBlockJson)
        }

        return root
    }

    /**
     * Applies a previously exported JSON document, restricted to [modules].
     * Unknown / missing sections (e.g. from a newer app version) are skipped
     * rather than crashing. This is a merge/restore, not a wipe-then-restore.
     */
    fun importFromJson(
        context: Context,
        root: JSONObject,
        modules: Set<BackupModule> = BackupModule.entries.toSet()
    ): ImportResult {
        // ZRAM: only touch it if selected AND the backup actually has a "zram"
        // section - absence means "leave whatever's currently configured alone".
        var zramRestored = false
        if (BackupModule.ZRAM in modules) {
            val zramJson = root.optJSONObject("zram")
            if (zramJson != null) {
                val sizeMb = zramJson.optInt("size_mb", -1)
                val algorithm = zramJson.optString("algorithm", "")
                val swappiness = zramJson.optInt("swappiness", -1)
                val size = ZramController.Size.entries.firstOrNull { it.mb == sizeMb }

                if (size != null && size != ZramController.Size.OFF && algorithm.isNotBlank() && swappiness >= 0) {
                    try {
                        // applyLive = false: this restores after a clean flash, so just
                        // persist the boot script - let it take effect on next reboot
                        // rather than swapping ZRAM off/on live mid-restore.
                        ZramController.setSize(context, size, algorithm, swappiness, applyLive = false)
                        zramRestored = true
                    } catch (e: Exception) {
                        // Nothing else to fail over - just report it wasn't restored.
                        zramRestored = false
                    }
                }
            }
        }

        // AdBlock: only touch it if selected AND the backup has an "adblock" section.
        // Installs the module first if it isn't present on this device yet (a fresh
        // flash won't have it) - in that case the caller should tell the user a
        // reboot is needed, same as a manual install from the AdBlock screen.
        var adBlockRestored = false
        var adBlockNeedsReboot = false
        if (BackupModule.AD_BLOCK in modules) {
            val adBlockJson = root.optJSONObject("adblock")
            if (adBlockJson != null) {
                try {
                    if (!AdBlockController.isInstalled()) {
                        val install = AdBlockController.install(context)
                        if (!install.success) throw RuntimeException(install.outString)
                        adBlockNeedsReboot = true
                    }
                    for ((fileName, jsonKey) in ADBLOCK_FILE_TO_JSON_KEY) {
                        val arr = adBlockJson.optJSONArray(jsonKey) ?: continue
                        val content = (0 until arr.length()).joinToString("\n") { arr.getString(it) }
                        val write = AdBlockController.writePersistedFileRaw(context, fileName, content)
                        if (!write.success) throw RuntimeException(write.outString)
                    }
                    val recompile = AdBlockController.recompile()
                    if (!recompile.success) throw RuntimeException(recompile.outString)
                    AdBlockController.setEnabled(adBlockJson.optBoolean("enabled", true))
                    adBlockRestored = true
                } catch (e: Exception) {
                    adBlockRestored = false
                }
            }
        }

        return ImportResult.Success(0, zramRestored, adBlockRestored, adBlockNeedsReboot)
    }

    fun writeToUri(context: Context, uri: Uri, json: JSONObject) {
        context.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(json.toString(2).toByteArray())
        } ?: error("Could not open output stream for $uri")
    }

    fun readFromUri(context: Context, uri: Uri): JSONObject {
        val text = context.contentResolver.openInputStream(uri)?.use { input ->
            BufferedReader(InputStreamReader(input)).readText()
        } ?: error("Could not open input stream for $uri")
        return JSONObject(text)
    }

    sealed class ImportResult {
        data class Success(
            val restoredKeys: Int,
            val zramRestored: Boolean = false,
            val adBlockRestored: Boolean = false,
            val adBlockNeedsReboot: Boolean = false
        ) : ImportResult()
        data class Failure(val message: String) : ImportResult()
    }
}
