package com.tugartech.claudewalk

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.MultiplePermissionsState
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.tugartech.claudewalk.ui.SessionDrawer
import com.tugartech.claudewalk.ui.SetupScreen
import com.tugartech.claudewalk.ui.WalkScreen
import com.tugartech.claudewalk.ui.theme.ClaudeWalkTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ClaudeWalkTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    App()
                }
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun App(viewModel: WalkViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settingsState.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()

    val required = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val permissions = rememberMultiplePermissionsState(required)
    var editingSettings by rememberSaveable { mutableStateOf(false) }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // Only scan while the tray is open. Background polling would cost battery for
    // a number that only matters when someone is looking at it.
    LaunchedEffect(drawerState.currentValue) {
        if (drawerState.currentValue == DrawerValue.Open) viewModel.refreshSessions()
    }

    when {
        !permissions.allPermissionsGranted -> PermissionGate(permissions)

        !settings.isConfigured || editingSettings -> {
            // System back leaves settings rather than the app. Disabled on a first
            // run, where there is nothing behind this screen to go back to.
            BackHandler(enabled = settings.isConfigured) { editingSettings = false }

            SetupScreen(
                settings = settings,
                onSave = { host, port, token ->
                    viewModel.saveSettings(host, port, token)
                    editingSettings = false
                },
                // Empty until the service's speech engine has bound, which is exactly
                // the case on a first run, where there is no engine and nothing to pick.
                voices = state.voices,
                onVoiceChange = viewModel::setVoice,
                onPreviewVoice = viewModel::previewVoice,
                onSetContinuous = viewModel::setContinuous,
                onDone = { editingSettings = false },
            )
        }

        else -> {
            val context = LocalContext.current
            // The microphone foreground service can only be started while the app
            // is in the foreground, so it starts here rather than at boot.
            LaunchedEffect(settings.isConfigured) { WalkService.start(context) }

            // Everything queued on sessions other than the one in your ears.
            val unheardElsewhere = sessions.sessions
                .filter { it.port != sessions.activePort }
                .sumOf { it.unheard }

            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    ModalDrawerSheet {
                        SessionDrawer(
                            state = sessions,
                            onRefresh = viewModel::refreshSessions,
                            onSelect = {
                                viewModel.connectToSession(it)
                                scope.launch { drawerState.close() }
                            },
                        )
                    }
                },
            ) {
                WalkScreen(
                    state = state,
                    totalUnheardElsewhere = unheardElsewhere,
                    onTapMic = viewModel::tap,
                    onStopAudio = viewModel::stopAudio,
                    onDiscard = viewModel::discardTurn,
                    onUndoLast = viewModel::undoLast,
                    onTogglePause = viewModel::togglePause,
                    onSetVoiceActive = viewModel::setVoiceActive,
                    onOpenTailscale = viewModel::openTailscale,
                    onToggleMute = viewModel::toggleMute,
                    onSetContinuous = viewModel::setContinuous,
                    onSendText = viewModel::sendText,
                    onReconnect = viewModel::reconnect,
                    onEditSettings = { editingSettings = true },
                    onOpenSessions = { scope.launch { drawerState.open() } },
                )
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun PermissionGate(permissions: MultiplePermissionsState) {
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text("Permissions needed", style = MaterialTheme.typography.titleLarge)
        Text(
            "The microphone is how you talk back to Claude. Notifications keep the " +
                "connection alive while the screen is off and put Talk, Stop and Pause on the " +
                "lock screen.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
        )
        Button(onClick = { permissions.launchMultiplePermissionRequest() }) {
            Text("Grant permissions")
        }
    }
}
