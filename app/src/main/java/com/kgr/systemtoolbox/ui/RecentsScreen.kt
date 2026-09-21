package com.kgr.systemtoolbox.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kgr.systemtoolbox.modules.RecentsController
import com.kgr.systemtoolbox.modules.RecentsController.LayoutMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Recents mode picker for SystemToolbox. Simplified from Key2Toolbox's
 * RecentsScreen: no Grid (needs an LSPosed hook against Pixel Launcher that
 * hasn't been written), no Masonry (dropped from this port entirely - see
 * [RecentsController]), no scrim-alpha slider (that was a Grid/Stock launcher
 * property with no launcher hook here to apply it), no Xposed status card
 * (irrelevant with no launcher-touching mode left) and no restart-launcher
 * button (Slim List never touches the launcher process, so there's nothing to
 * restart on a mode change).
 */
@Composable
fun RecentsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var mode by remember { mutableStateOf(LayoutMode.STOCK) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val m = RecentsController.getLayoutMode(context)
            withContext(Dispatchers.Main) { mode = m }
        }
    }

    fun setMode(newMode: LayoutMode) {
        mode = newMode
        scope.launch(Dispatchers.IO) { RecentsController.setLayoutMode(context, newMode) }
    }

    ScreenScaffold(title = Screen.Recents.title, onBack = onBack) {
        Text(
            "Choose what opens when you trigger Recents from the Toolbelt or the " +
                "app-switch key.",
            style = MaterialTheme.typography.bodySmall
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                val options = listOf(
                    LayoutMode.STOCK to "Stock Overview",
                    LayoutMode.SLIM_LIST to "Slim List",
                )
                options.forEach { (value, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { setMode(value) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = mode == value, onClick = { setMode(value) })
                        Spacer(Modifier.width(8.dp))
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (mode == LayoutMode.SLIM_LIST) {
                    Text(
                        "A thumbnail-free vertical list of running tasks, drawn as its " +
                            "own overlay - no launcher involvement, nothing to hook. Tap a " +
                            "row to resume that task, swipe it away to dismiss.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }

        DescriptionDivider()

        Text(
            "Grid (two-row tablet Overview) isn't available yet - it needs an LSPosed " +
                "hook reverse-engineered against Pixel Launcher's Taskbar internals, " +
                "which hasn't been written.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
