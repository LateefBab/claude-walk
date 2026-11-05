package com.tugartech.claudewalk.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tugartech.claudewalk.speech.VoiceOption
import com.tugartech.claudewalk.speech.VoicePrefs
import java.util.Locale

/**
 * Pick a voice and tune how fast it talks.
 *
 * Every change applies and persists immediately rather than on a save button,
 * because the only way to judge a voice is to hear it, and hearing it requires the
 * engine to already hold the setting.
 */
@Composable
fun VoiceSettings(
    voices: List<VoiceOption>,
    prefs: VoicePrefs,
    onChange: (VoicePrefs) -> Unit,
    onPreview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = voices.firstOrNull { it.id == prefs.voiceId }

    Column(modifier.fillMaxWidth()) {
        Text("Voice", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.size(8.dp))
        Text(
            if (voices.isEmpty()) {
                "The voice list appears once the speech engine has started. Save a host " +
                    "and token first, then come back here."
            } else {
                "Offline voices are listed first. A voice that needs the network goes " +
                    "silent in a dead zone, which is the one thing a walk cannot tolerate."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (voices.isEmpty()) return@Column

        Spacer(Modifier.size(16.dp))
        VoicePicker(
            voices = voices,
            selected = selected,
            onSelect = { onChange(prefs.copy(voiceId = it)) },
        )

        if (selected?.requiresNetwork == true) {
            Spacer(Modifier.size(8.dp))
            Text(
                "This voice needs a data connection to speak.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.size(20.dp))
        TunerSlider(
            label = "Speed",
            value = prefs.rate,
            range = VoicePrefs.MIN_RATE..VoicePrefs.MAX_RATE,
            // Tenths across the range, which is the smallest step that is audible.
            steps = 14,
            onCommit = { onChange(prefs.copy(rate = it)) },
        )

        Spacer(Modifier.size(12.dp))
        TunerSlider(
            label = "Pitch",
            value = prefs.pitch,
            range = VoicePrefs.MIN_PITCH..VoicePrefs.MAX_PITCH,
            steps = 9,
            onCommit = { onChange(prefs.copy(pitch = it)) },
        )

        Spacer(Modifier.size(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onPreview) { Text("Preview") }
            Spacer(Modifier.size(8.dp))
            TextButton(
                onClick = { onChange(VoicePrefs()) },
                enabled = prefs != VoicePrefs(),
            ) {
                Text("Reset")
            }
        }
    }
}

@Composable
private fun VoicePicker(
    voices: List<VoiceOption>,
    selected: VoiceOption?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(
                selected?.let { "${it.label} · ${it.detail}" } ?: SYSTEM_DEFAULT,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(SYSTEM_DEFAULT) },
                onClick = {
                    expanded = false
                    onSelect("")
                },
            )
            voices.forEach { voice ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(voice.label, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                voice.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelect(voice.id)
                    },
                )
            }
        }
    }
}

/**
 * Drags locally and commits on release, so the engine is reconfigured once per
 * gesture rather than on every frame of the drag.
 */
@Composable
private fun TunerSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onCommit: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var live by remember(value) { mutableStateOf(value) }

    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            Text(
                String.format(Locale.getDefault(), "%.1f×", live),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = live,
            onValueChange = { live = it },
            onValueChangeFinished = { onCommit(live) },
            valueRange = range,
            steps = steps,
        )
    }
}

private const val SYSTEM_DEFAULT = "System default"

@Preview(showBackground = true)
@Composable
private fun VoiceSettingsPreview() {
    VoiceSettings(
        voices = listOf(
            VoiceOption("en-gb-x-gbb-local", "English (United Kingdom)", "gbb · high · offline", false),
            VoiceOption("en-us-x-iom-network", "English (United States)", "iom · very high · needs network", true),
        ),
        prefs = VoicePrefs(voiceId = "en-gb-x-gbb-local", rate = 1.2f),
        onChange = {},
        onPreview = {},
    )
}
