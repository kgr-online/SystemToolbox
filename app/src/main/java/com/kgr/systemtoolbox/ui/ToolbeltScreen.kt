package com.kgr.systemtoolbox.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.Slider
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kgr.systemtoolbox.modules.ToolbeltController
import com.kgr.systemtoolbox.modules.ToolbeltController.Slot
import com.kgr.systemtoolbox.modules.ToolbeltController.ToolbeltAction
import com.kgr.systemtoolbox.modules.ToolbeltController.ToolbeltIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** "HANGUP_OR_HOME" -> "Hangup or home". Power-user module; not localised. */
private fun prettify(name: String): String =
    name.split('_').joinToString(" ") { it.lowercase() }
        .replaceFirstChar { it.uppercase() }

private val HAPTIC_LEVELS = listOf("Off", "Light", "Medium", "Strong")
private val COLOR_MODES = listOf("Fixed black", "Material You", "Transparent")

@Composable
fun ToolbeltScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember {
        context.getSharedPreferences(ToolbeltController.PREFS, android.content.Context.MODE_PRIVATE)
    }

    var enabled by remember { mutableStateOf(ToolbeltController.isEnabled(context)) }
    var autoHide by remember {
        mutableStateOf(prefs.getBoolean(ToolbeltController.KEY_AUTOHIDE_FULLSCREEN, true))
    }
    var height by remember { mutableStateOf(ToolbeltController.heightDp(prefs)) }
    var iconScale by remember {
        mutableStateOf((ToolbeltController.iconScale(prefs) * 100).toInt())
    }
    var haptic by remember { mutableStateOf(ToolbeltController.hapticLevel(prefs)) }
    var collapsible by remember { mutableStateOf(ToolbeltController.isCollapsible(prefs)) }
    var colorMode by remember { mutableStateOf(ToolbeltController.colorMode(prefs)) }
    var privacyIndicatorOff by remember {
        mutableStateOf(ToolbeltController.isPrivacyIndicatorOff(prefs))
    }
    var xposedActive by remember { mutableStateOf(ToolbeltController.isXposedActive()) }
    var navMode by remember { mutableStateOf(-1) }
    val slots: SnapshotStateList<Slot> =
        remember { ToolbeltController.getSlots(context).toMutableStateList() }
    var apps by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val m = ToolbeltController.syncNavMode(context)
            val a = loadLaunchableApps(context)
            val liveIndicator = ToolbeltController.readLocationIndicatorEnabled()
            withContext(Dispatchers.Main) {
                navMode = m
                xposedActive = ToolbeltController.isXposedActive()
                apps = a
                // Ground-truth the switch against the live flag when we can read it.
                if (liveIndicator != null) privacyIndicatorOff = !liveIndicator
            }
        }
    }

    fun persistSlots() {
        val snapshot = slots.toList()
        scope.launch(Dispatchers.IO) { ToolbeltController.setSlots(context, snapshot) }
    }

    ScreenScaffold(title = Screen.Toolbelt.title, onBack = onBack) {
        Text(
            "A BlackBerry Q20-style row of five icons pinned to the bottom of the " +
                "screen, replacing the system nav bar / gesture pill.",
            style = MaterialTheme.typography.bodySmall
        )

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Enabled")
            Switch(
                checked = enabled,
                onCheckedChange = { on ->
                    enabled = on
                    scope.launch(Dispatchers.IO) { ToolbeltController.setEnabled(context, on) }
                }
            )
        }

        // Nav-bar-hiding hook status
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (xposedActive) "Nav-bar hook active"
                    else "Nav-bar hook not installed - the belt draws on top of the real nav bar",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (xposedActive) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
                if (!xposedActive) {
                    Text(
                        "The belt still works, it just doesn't reserve space from the system " +
                            "nav bar/gesture pill yet - that needs an LSPosed hook against Pixel " +
                            "Launcher that hasn't been written.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val modeLabel = when (navMode) {
                    2 -> "Gesture"
                    1 -> "2-button"
                    0 -> "3-button"
                    else -> "?"
                }
                Text(
                    "Current nav mode: $modeLabel",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Auto-hide in fullscreen apps", modifier = Modifier.weight(1f))
            Switch(
                checked = autoHide,
                onCheckedChange = { on ->
                    autoHide = on
                    prefs.edit().putBoolean(ToolbeltController.KEY_AUTOHIDE_FULLSCREEN, on).apply()
                }
            )
        }

        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Collapsible", modifier = Modifier.weight(1f))
                Switch(
                    checked = collapsible,
                    onCheckedChange = { on ->
                        collapsible = on
                        prefs.edit().putBoolean(ToolbeltController.KEY_COLLAPSIBLE, on).apply()
                    }
                )
            }
            Text(
                "Adds a grab strip above the icons. Swipe down / tap it to hide the " +
                    "belt and reclaim its space; tap the strip to bring it back.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Suppress location privacy icon", modifier = Modifier.weight(1f))
                Switch(
                    checked = privacyIndicatorOff,
                    onCheckedChange = { on ->
                        privacyIndicatorOff = on
                        scope.launch(Dispatchers.IO) {
                            val readBack = ToolbeltController.applyLocationIndicator(context, on)
                            if (readBack != null) {
                                withContext(Dispatchers.Main) { privacyIndicatorOff = !readBack }
                            }
                        }
                    }
                )
            }
            Text(
                "Every time an app reads location, Android briefly forces the nav bar " +
                    "back over the current fullscreen app to show this icon - popping the " +
                    "belt back on top. This turns the icon off system-wide. Trade-off: no " +
                    "GPS-in-use cue; mic/camera indicators are unaffected.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        DescriptionDivider()
        Text(
            "Appearance",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )

        IntSliderRow(
            label = "Belt height",
            value = height, valueText = "${height}dp", range = 36f..88f, steps = 25,
            onChange = { height = it }, onCommit = {
                prefs.edit().putInt(ToolbeltController.KEY_HEIGHT_DP, height).apply()
            }
        )
        IntSliderRow(
            label = "Icon size",
            value = iconScale, valueText = "$iconScale%", range = 40f..100f, steps = 11,
            onChange = { iconScale = it }, onCommit = {
                prefs.edit().putInt(ToolbeltController.KEY_ICON_SCALE, iconScale).apply()
            }
        )
        IntSliderRow(
            label = "Haptics",
            value = haptic,
            valueText = HAPTIC_LEVELS.getOrElse(haptic) { "$haptic" },
            range = 0f..3f, steps = 2,
            onChange = { haptic = it }, onCommit = {
                prefs.edit().putInt(ToolbeltController.KEY_HAPTIC, haptic).apply()
            }
        )

        PickerRow(
            label = "Color",
            current = COLOR_MODES.getOrElse(colorMode) { "$colorMode" },
            options = listOf(0, 1, 2).map { it to COLOR_MODES.getOrElse(it) { "$it" } },
            onPick = {
                colorMode = it
                prefs.edit().putInt(ToolbeltController.KEY_COLOR_MODE, it).apply()
            }
        )

        DescriptionDivider()
        Text(
            "Slots",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )

        slots.forEachIndexed { index, slot ->
            SlotCard(
                index = index,
                slot = slot,
                apps = apps,
                onChange = { updated ->
                    slots[index] = updated
                    persistSlots()
                }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    val defs = ToolbeltController.DEFAULT_SLOTS
                    slots.clear(); slots.addAll(defs)
                    scope.launch(Dispatchers.IO) { ToolbeltController.resetSlots(context) }
                },
                modifier = Modifier.weight(1f)
            ) { Text("Reset slots") }
            OutlinedButton(
                onClick = { scope.launch(Dispatchers.IO) { ToolbeltController.restartLauncher() } },
                modifier = Modifier.weight(1f)
            ) { Text("Restart launcher") }
        }

        DescriptionDivider()
        Text(
            "Debug: restarting the launcher is only needed after a reserved-inset " +
                "change once a nav-bar hook exists to read it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SlotCard(index: Int, slot: Slot, apps: List<Pair<String, String>>, onChange: (Slot) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(slot.icon.res),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Slot ${index + 1}",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            PickerRow(
                label = "Icon",
                current = prettify(slot.icon.name),
                options = ToolbeltIcon.entries.map { it to prettify(it.name) },
                onPick = { onChange(slot.copy(icon = it)) }
            )
            GestureRow(
                "Tap", slot.tap, slot.tapArg, apps,
            ) { act, a -> onChange(slot.copy(tap = act, tapArg = a)) }
            GestureRow(
                "Double", slot.doubleTap, slot.doubleArg, apps,
            ) { act, a -> onChange(slot.copy(doubleTap = act, doubleArg = a)) }
            GestureRow(
                "Long", slot.longTap, slot.longArg, apps,
            ) { act, a -> onChange(slot.copy(longTap = act, longArg = a)) }
        }
    }
}

@Composable
private fun GestureRow(
    label: String,
    action: ToolbeltAction,
    arg: String?,
    apps: List<Pair<String, String>>,
    onChange: (ToolbeltAction, String?) -> Unit,
) {
    PickerRow(
        label = label,
        current = prettify(action.name),
        options = ToolbeltAction.entries.map { it to prettify(it.name) },
        onPick = { picked ->
            onChange(picked, if (picked == ToolbeltAction.LAUNCH_APP) arg else null)
        }
    )
    if (action == ToolbeltAction.LAUNCH_APP) {
        PickerRow(
            label = "App",
            current = apps.firstOrNull { it.first == arg }?.second
                ?: arg ?: "Pick an app",
            options = apps.map { it.first to it.second },
            onPick = { onChange(action, it) }
        )
    }
}

/** All apps with a launcher entry: package -> label, sorted, self excluded. */
private fun loadLaunchableApps(context: android.content.Context): List<Pair<String, String>> {
    val pm = context.packageManager
    val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
        .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0).asSequence()
        .map { it.activityInfo.packageName }
        .filter { it != context.packageName }
        .distinct()
        .map { pkg ->
            val label = try {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (_: Exception) {
                pkg
            }
            pkg to label
        }
        .sortedBy { it.second.lowercase() }
        .toList()
}

@Composable
private fun IntSliderRow(
    label: String,
    value: Int,
    valueText: String,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Int) -> Unit,
    onCommit: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                valueText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            onValueChangeFinished = onCommit,
            valueRange = range,
            steps = steps,
        )
    }
}

@Composable
private fun <T> PickerRow(
    label: String,
    current: String,
    options: List<Pair<T, String>>,
    onPick: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().clickable { open = true }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(76.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            current,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f)
        )
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = { open = false }) { Text("\u2190 Back") }
            },
            title = { Text(label) },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(options) { (value, text) ->
                        Text(
                            text,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (text == current) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { open = false; onPick(value) }
                                .padding(vertical = 12.dp)
                        )
                    }
                }
            }
        )
    }
}
