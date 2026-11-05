package com.tugartech.claudewalk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tugartech.claudewalk.SettingsUiState
import com.tugartech.claudewalk.WalkSettings
import com.tugartech.claudewalk.speech.VoiceOption
import com.tugartech.claudewalk.speech.VoicePrefs

/**
 * Two very different jobs behind one gear icon, so the screen changes shape.
 *
 * Before the bridge is configured this is a first-run form and nothing else matters.
 * Afterwards, host and token are settings you touch once a year, while the voice is
 * the thing you actually come here to change, so the voice goes first and the
 * connection collapses to a one-line summary. Three text fields and a live token on
 * screen every time you want to nudge the speaking rate is noise, and the token is
 * a secret that should not be sitting in the open for no reason.
 */
@Composable
fun SetupScreen(
    settings: SettingsUiState,
    onSave: (host: String, port: Int, token: String) -> Unit,
    modifier: Modifier = Modifier,
    voices: List<VoiceOption> = emptyList(),
    onVoiceChange: (VoicePrefs) -> Unit = {},
    onPreviewVoice: () -> Unit = {},
    onSetContinuous: (Boolean) -> Unit = {},
    onDone: () -> Unit = {},
) {
    val firstRun = !settings.isConfigured
    var showConnection by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        if (firstRun) {
            ConnectionSettings(settings = settings, onSave = onSave)
            return@Column
        }

        // The way out. Saving the connection used to be the only thing that closed
        // this screen, which meant coming here to change the voice and then having
        // to re-save a host you had not touched, or having no way back at all.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Settings",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDone) { Text("Done") }
        }

        Spacer(Modifier.size(16.dp))

        VoiceSettings(
            voices = voices,
            prefs = settings.voice,
            onChange = onVoiceChange,
            onPreview = onPreviewVoice,
        )

        Spacer(Modifier.size(32.dp))
        HorizontalDivider()
        Spacer(Modifier.size(24.dp))

        ListeningSettings(
            continuous = settings.continuousListening,
            onChange = onSetContinuous,
        )

        Spacer(Modifier.size(32.dp))
        HorizontalDivider()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showConnection = !showConnection }
                .padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Connection", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${settings.host}:${settings.port}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                if (showConnection) "Hide" else "Change",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        if (showConnection) {
            ConnectionSettings(
                settings = settings,
                onSave = onSave,
                showHeader = false,
            )
        }
    }
}

/**
 * How a turn starts: by pressing, or by talking.
 *
 * Hands-free is off by default and says why. An always-open microphone outdoors
 * will eventually send Claude a passing conversation, and the honest trade is
 * worth stating rather than discovering.
 */
@Composable
private fun ListeningSettings(
    continuous: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Text("Listening", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.size(12.dp))

        ListeningOption(
            title = "Press to talk",
            detail = "The microphone is shut until you press Talk or tap an earbud. " +
                "Nothing is heard by accident.",
            selected = !continuous,
            onSelect = { onChange(false) },
        )
        Spacer(Modifier.size(8.dp))
        ListeningOption(
            title = "Hands-free",
            detail = "The microphone stays open and a pause of about two seconds sends " +
                "what you said. It closes itself while Claude is speaking so it cannot " +
                "hear Claude through your earbuds, and Mute shuts it without leaving " +
                "the mode. Outdoors it will occasionally send a passing conversation: " +
                "double tap an earbud to take the last thing back.",
            selected = continuous,
            onSelect = { onChange(true) },
        )
    }
}

@Composable
private fun ListeningOption(
    title: String,
    detail: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
            )
            .clickable(onClick = onSelect)
            .padding(14.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(4.dp))
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ConnectionSettings(
    settings: SettingsUiState,
    onSave: (host: String, port: Int, token: String) -> Unit,
    modifier: Modifier = Modifier,
    showHeader: Boolean = true,
) {
    // Keyed on the saved values so reopening the section shows what is actually
    // stored, rather than an abandoned edit from the last time it was expanded.
    var host by remember(settings.host) { mutableStateOf(settings.host) }
    var port by remember(settings.port) { mutableStateOf(settings.port.toString()) }
    var token by remember(settings.token) { mutableStateOf(settings.token) }

    Column(modifier.fillMaxWidth()) {
        if (showHeader) {
            Text("Connect to the bridge", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.size(8.dp))
            Text(
                "Host is the Tailscale name of the machine running Claude Code. The token is " +
                    "CLAUDE_WALK_TOKEN from bridge/.env, or the one the bridge printed on startup.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(24.dp))
        }

        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text("Host") },
            placeholder = { Text("my-laptop") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.size(12.dp))

        OutlinedTextField(
            value = port,
            onValueChange = { port = it.filter(Char::isDigit) },
            label = { Text("Port") },
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = KeyboardType.Number,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.size(12.dp))

        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("Token") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.size(24.dp))

        Button(
            onClick = { onSave(host.trim(), port.toIntOrNull() ?: WalkSettings.DEFAULT_PORT, token.trim()) },
            enabled = host.isNotBlank() && token.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save and connect")
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SetupScreenFirstRunPreview() {
    SetupScreen(settings = SettingsUiState(), onSave = { _, _, _ -> })
}

@Preview(showBackground = true)
@Composable
private fun SetupScreenConfiguredPreview() {
    SetupScreen(
        settings = SettingsUiState(host = "my-laptop", token = "secret", isConfigured = true),
        onSave = { _, _, _ -> },
        voices = listOf(
            VoiceOption("en-gb-x-gbb-local", "English (United Kingdom)", "gbb · high · offline", false),
        ),
    )
}
