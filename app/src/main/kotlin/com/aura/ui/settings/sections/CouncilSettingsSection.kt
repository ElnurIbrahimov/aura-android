package com.aura.ui.settings.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aura.ui.settings.SettingsSection
import com.aura.ui.theme.AuraSpacing
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.Icons

/**
 * Settings surface for the overnight council.
 *
 * Two controls, both with real readers: `councilEnabled` gates the council
 * block in `DaemonWorker.doWork`, and `councilActivityLevel` is passed to
 * `CouncilOrchestrator.runFromFindings` as the session cap.
 *
 * There is deliberately no auto-apply toggle. `councilAutoApply` persists and
 * round-trips through backup, but nothing in the app can *apply* an
 * `Intervention` — the council's output is a proposal, and the only consumer is
 * the insight event this section's daemon path emits. A switch that stores a
 * value and changes no behaviour is worse than no switch: it reads as a working
 * control. The preference and its `AuraBackup` field stay so already-saved
 * values keep round-tripping and the backup schema needs no version bump —
 * exactly the treatment `ModelRole.VERIFIER` records for the same situation.
 * Building the apply pipeline is a feature; removing a control that does
 * nothing is a fix, and this is the fix.
 *
 * Takes plain values rather than `State<T>`, matching every other section
 * (`EmotionDaemonSection`, `DreamConsolidationSection`), so `SettingsScreen`
 * can pass fields off its already-collected state.
 */
@Composable
fun CouncilSettingsSection(
    councilEnabled: Boolean,
    councilActivityLevel: Int,
    onToggleEnabled: (Boolean) -> Unit,
    onActivityLevelChange: (Int) -> Unit,
) {
    SettingsSection(
        icon = Icons.Filled.Groups,
        title = "Council",
        subtitle = "Agent society debates and interventions",
        initialExpanded = false,
    ) {
        Column {
            // Enable/disable toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = AuraSpacing.xs),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Enable overnight council", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Agents debate findings and propose interventions while idle",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = councilEnabled, onCheckedChange = onToggleEnabled)
            }

            if (councilEnabled) {
                // Activity level slider — the number of findings the council
                // debates per cycle, one full session each.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = AuraSpacing.xs),
                ) {
                    Text("Findings debated per night: $councilActivityLevel", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Each one costs a full debate — up to 4 agents, 2 rounds, a vote",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Slider(
                        value = councilActivityLevel.toFloat(),
                        onValueChange = { onActivityLevelChange(it.toInt()) },
                        valueRange = 1f..5f,
                        steps = 3,
                    )
                }
            }
        }
    }
}