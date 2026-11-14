package dev.antisnorr.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.antisnorr.R
import dev.antisnorr.core.AlertMode
import dev.antisnorr.core.DetectionConfig
import dev.antisnorr.core.MonitorBus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun MonitorScreen(
    state: MonitorBus.State,
    config: DetectionConfig,
    onToggle: () -> Unit,
    onTest: () -> Unit,
    onModeChange: (AlertMode) -> Unit,
    onThresholdChange: (Float) -> Unit,
    onRepeatChange: (Long) -> Unit,
    onOpenGadgetbridge: () -> Unit,
    onBatterySettings: () -> Unit,
    onExportLog: () -> Unit,
    onClearLog: () -> Unit,
    gadgetbridgeInstalled: Boolean,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Header(state)
            Controls(state.running, onToggle, onTest)
            LiveCard(state, config.threshold)
            SettingsCard(
                config = config,
                onModeChange = onModeChange,
                onThresholdChange = onThresholdChange,
                onRepeatChange = onRepeatChange,
                gadgetbridgeInstalled = gadgetbridgeInstalled,
            )
            SetupCard(onOpenGadgetbridge, onBatterySettings, onExportLog, onClearLog)
            EventsCard(state)
            Text(
                text = stringResource(R.string.footer_privacy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Header(state: MonitorBus.State) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.app_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        StatusPill(state)
    }
}

@Composable
private fun StatusPill(state: MonitorBus.State) {
    val (textRes, container, content) = when {
        state.error != null -> Triple(
            R.string.status_error,
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
        )

        state.status == MonitorBus.Status.SNORE -> Triple(
            R.string.status_snore,
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
        )

        state.running -> Triple(
            R.string.status_listening,
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )

        else -> Triple(
            R.string.status_idle,
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Surface(
        shape = RoundedCornerShape(50),
        color = container,
        contentColor = content,
    ) {
        Text(
            text = stringResource(textRes),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun Controls(running: Boolean, onToggle: () -> Unit, onTest: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = onToggle,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
        ) {
            Text(
                text = stringResource(if (running) R.string.btn_stop else R.string.btn_start),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        OutlinedButton(
            onClick = onTest,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.btn_test_vibration))
        }
    }
}

@Composable
private fun LiveCard(state: MonitorBus.State, threshold: Float) {
    val probability = state.snoreProbability.coerceIn(0f, 1f)
    val accent = if (probability >= threshold) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }

    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.section_live),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.label_snore_probability),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "${(probability * 100).roundToInt()}%",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = accent,
                )
            }

            LinearProgressIndicator(
                progress = { probability },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(CircleShape),
                color = accent,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                LabeledValue(
                    label = stringResource(R.string.label_top_class),
                    value = state.topLabel.ifBlank { stringResource(R.string.label_no_signal) } +
                        if (state.topLabel.isNotBlank() && state.topProbability > 0f) {
                            " ${(state.topProbability * 100).roundToInt()}%"
                        } else {
                            ""
                        },
                )
                LabeledValue(
                    label = stringResource(R.string.label_rms),
                    value = if (state.running) "${state.rmsDb.roundToInt()} dB" else "—",
                )
            }

            state.error?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun LabeledValue(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsCard(
    config: DetectionConfig,
    onModeChange: (AlertMode) -> Unit,
    onThresholdChange: (Float) -> Unit,
    onRepeatChange: (Long) -> Unit,
    gadgetbridgeInstalled: Boolean,
) {
    ElevatedCard {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.section_settings),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.setting_threshold),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "${(config.threshold * 100).roundToInt()}%",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Slider(
                value = config.threshold,
                onValueChange = onThresholdChange,
                valueRange = 0.05f..0.95f,
            )
            Text(
                text = stringResource(R.string.setting_threshold_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.setting_repeat),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(
                        R.string.setting_repeat_value,
                        (config.repeatMs / 1000).toInt(),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Slider(
                value = (config.repeatMs / 1000f).coerceIn(0f, 120f),
                onValueChange = { seconds -> onRepeatChange(seconds.toLong() * 1000L) },
                valueRange = 0f..120f,
            )

            Spacer(Modifier.height(4.dp))

            Text(
                text = stringResource(R.string.setting_mode),
                style = MaterialTheme.typography.bodyMedium,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeChip(config.alertMode, AlertMode.NOTIFICATION, R.string.mode_notification, onModeChange)
                ModeChip(config.alertMode, AlertMode.GB_INTENT_NOTIFICATION, R.string.mode_gb_notification, onModeChange)
                ModeChip(config.alertMode, AlertMode.GB_INTENT_CALL, R.string.mode_gb_call, onModeChange)
            }
            Text(
                text = stringResource(
                    when (config.alertMode) {
                        AlertMode.NOTIFICATION -> R.string.mode_hint_notification
                        AlertMode.GB_INTENT_NOTIFICATION -> R.string.mode_hint_gb_notification
                        AlertMode.GB_INTENT_CALL -> R.string.mode_hint_gb_call
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.setting_pattern_summary, config.pattern.totalMs / 1000),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!gadgetbridgeInstalled && config.alertMode != AlertMode.NOTIFICATION) {
                Text(
                    text = stringResource(R.string.error_gb_missing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun ModeChip(
    selected: AlertMode,
    mode: AlertMode,
    labelRes: Int,
    onModeChange: (AlertMode) -> Unit,
) {
    FilterChip(
        selected = selected == mode,
        onClick = { onModeChange(mode) },
        label = { Text(stringResource(labelRes)) },
    )
}

@Composable
private fun SetupCard(onOpenGadgetbridge: () -> Unit, onBatterySettings: () -> Unit, onExportLog: () -> Unit, onClearLog: () -> Unit) {
    ElevatedCard {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.section_setup),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
            )
            listOf(
                R.string.setup_step_1,
                R.string.setup_step_2,
                R.string.setup_step_3,
                R.string.setup_step_4,
                R.string.setup_step_5,
            ).forEach { step ->
                Text(
                    text = stringResource(step),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = onOpenGadgetbridge, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.btn_open_gadgetbridge))
            }
            OutlinedButton(onClick = onExportLog, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.btn_export_log))
            }
            OutlinedButton(onClick = onClearLog, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.btn_clear_log))
            }
            OutlinedButton(onClick = onBatterySettings, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.btn_battery_settings))
            }
        }
    }
}

@Composable
private fun EventsCard(state: MonitorBus.State) {
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    ElevatedCard {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.section_events),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = stringResource(R.string.events_summary, state.events.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.events.isEmpty()) {
                Text(
                    text = stringResource(R.string.events_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                state.events.take(5).forEach { event ->
                    Text(
                        text = stringResource(
                            R.string.event_format,
                            timeFormat.format(Date(event.timeMs)),
                            (event.durationMs / 1000).coerceAtLeast(1).toInt(),
                            (event.peakProbability.coerceIn(0f, 1f) * 100).roundToInt(),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
