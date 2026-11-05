package com.tugartech.claudewalk.ui

import android.provider.Settings
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tugartech.claudewalk.Activity
import com.tugartech.claudewalk.ConnectionState
import com.tugartech.claudewalk.SessionState
import com.tugartech.claudewalk.TranscriptLine
import com.tugartech.claudewalk.WalkUiState
import com.tugartech.claudewalk.ui.theme.ClaudeWalkTheme
import com.tugartech.claudewalk.ui.theme.MonoSmall
import com.tugartech.claudewalk.ui.theme.WalkColors

/**
 * Status, transcript and controls, and nothing more. This screen exists for the moments the phone comes
 * out of a pocket; the product is the audio.
 */
@Composable
fun WalkScreen(
    state: WalkUiState,
    totalUnheardElsewhere: Int,
    onTapMic: () -> Unit,
    onStopAudio: () -> Unit,
    onDiscard: () -> Unit,
    onUndoLast: () -> Unit,
    onTogglePause: () -> Unit,
    onSetVoiceActive: (Boolean) -> Unit,
    onOpenTailscale: () -> Unit,
    onToggleMute: () -> Unit,
    onSetContinuous: (Boolean) -> Unit,
    onSendText: (String) -> Unit,
    onReconnect: () -> Unit,
    onEditSettings: () -> Unit,
    onOpenSessions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(20.dp),
    ) {
        Header(state, totalUnheardElsewhere, onReconnect, onEditSettings, onOpenSessions)

        Spacer(Modifier.size(16.dp))

        if (state.currentText.isNotBlank()) {
            TerminalMirror(state.currentText)
            Spacer(Modifier.size(16.dp))
        }

        Transcript(state.transcript, Modifier.weight(1f))

        if (state.tailnetDown) {
            Spacer(Modifier.size(8.dp))
            TailnetBanner(onOpenTailscale)
        } else {
            state.error?.let {
                Spacer(Modifier.size(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }

        if (state.partial.isNotBlank()) {
            Spacer(Modifier.size(8.dp))
            LiveTranscript(state.partial, sending = state.activity == Activity.SENDING)
        }

        Spacer(Modifier.size(12.dp))
        ActivityLine(state)
        Spacer(Modifier.size(12.dp))

        // The mode switch sits above the controls rather than among them: it is a
        // different kind of decision. The three below act on this moment; this one
        // decides whether Claude is talking to you at all.
        ModeSwitch(state.voiceActive, onSetVoiceActive)

        // Only while the phone is part of the session. At the desk how you would
        // talk is a question about nothing, and the row below is inert anyway.
        if (state.voiceActive) {
            Spacer(Modifier.size(10.dp))
            ListeningModeToggle(
                continuous = state.continuous,
                onChange = onSetContinuous,
            )
        }

        Spacer(Modifier.size(10.dp))

        Controls(state, onTapMic, onStopAudio, onDiscard, onUndoLast, onTogglePause, onToggleMute, onSendText)
    }
}

/**
 * Walking, or at the desk.
 *
 * Sessions get launched with the channel as a matter of course so voice is there
 * the moment it is wanted. This is what makes it wanted, and while it is off Claude
 * produces no speech at all, so no backlog builds up during a morning at the desk.
 */
@Composable
private fun ModeSwitch(active: Boolean, onChange: (Boolean) -> Unit) {
    if (active) {
        OutlinedButton(
            onClick = { onChange(false) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text("Back at the desk")
        }
        return
    }

    Column {
        Button(
            onClick = { onChange(true) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text("Start walking")
        }
        Spacer(Modifier.size(8.dp))
        Text(
            "Claude is working quietly and writing to the terminal. Nothing is spoken and " +
                "nothing is saved up, so there is no backlog waiting when you set off. The " +
                "controls below are off too: talking to a phone that answers in a terminal " +
                "you are not holding only looks broken.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Press-to-talk or hands-free, on the front screen rather than inside Settings.
 *
 * This is the control most likely to be wanted mid-walk and it was the hardest to
 * reach: a noisy street, someone talking beside you, a quiet room where you would
 * rather not press anything. Two taps into Settings is the wrong cost for a
 * decision that changes with the street you are on.
 *
 * Shown as a pair rather than a single toggle because the current mode has to be
 * legible at a glance. A lone button reading "Hands-free" cannot say whether that
 * is the state or the offer.
 */
@Composable
private fun ListeningModeToggle(
    continuous: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ModeChip(
            label = "Press to talk",
            selected = !continuous,
            onClick = { onChange(false) },
            modifier = Modifier.weight(1f),
        )
        ModeChip(
            label = "Hands-free",
            selected = continuous,
            onClick = { onChange(true) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ModeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (selected) {
        Button(
            onClick = onClick,
            modifier = modifier,
            shape = RoundedCornerShape(12.dp),
            contentPadding = ControlPadding,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
        ) {
            ControlLabel(label)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            shape = RoundedCornerShape(12.dp),
            contentPadding = ControlPadding,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) {
            ControlLabel(label)
        }
    }
}

/**
 * The only connection failure the user can fix from where they are standing.
 *
 * Given its own banner rather than a line of red text, with the fix one tap away,
 * because reading an error and then hunting for the Tailscale app is exactly the
 * fiddling a walk cannot accommodate.
 */
@Composable
private fun TailnetBanner(onOpenTailscale: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(14.dp),
    ) {
        Text(
            "Tailnet is down",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        Spacer(Modifier.size(4.dp))
        Text(
            "The laptop cannot be reached until Tailscale is back on. This is not the " +
                "session ending: it is fixable from here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        Spacer(Modifier.size(10.dp))
        Button(
            onClick = onOpenTailscale,
            shape = RoundedCornerShape(12.dp),
            contentPadding = ControlPadding,
        ) {
            ControlLabel("Open Tailscale")
        }
    }
}

@Composable
private fun Header(
    state: WalkUiState,
    unheardElsewhere: Int,
    onReconnect: () -> Unit,
    onEditSettings: () -> Unit,
    onOpenSessions: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(
                    when {
                        state.paused -> WalkColors.Warning
                        state.connection == ConnectionState.CONNECTED -> WalkColors.Success
                        state.connection == ConnectionState.CONNECTING -> WalkColors.Warning
                        else -> WalkColors.Critical
                    },
                ),
        )
        Spacer(Modifier.size(10.dp))

        // The project name is the way into the tray: tapping it is how the earbuds
        // move to another session without leaving this conversation.
        Row(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onOpenSessions)
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = when (state.connection) {
                    ConnectionState.CONNECTED -> state.project.ifBlank { "Connected" }
                    ConnectionState.CONNECTING -> "Connecting…"
                    ConnectionState.DISCONNECTED -> "Disconnected"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            if (unheardElsewhere > 0) {
                Spacer(Modifier.size(8.dp))
                Box(
                    Modifier
                        .clip(CircleShape)
                        .background(WalkColors.Accent)
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                ) {
                    Text(
                        "$unheardElsewhere",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))
        if (state.connection == ConnectionState.DISCONNECTED) {
            TextButton(onClick = onReconnect) { Text("Reconnect") }
        }
        TextButton(onClick = onEditSettings) { Text("Settings") }
    }
}

@Composable
private fun TerminalMirror(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(14.dp),
    ) {
        Text(
            text = text,
            style = MonoSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 6,
        )
    }
}

@Composable
private fun Transcript(lines: List<TranscriptLine>, modifier: Modifier = Modifier) {
    if (lines.isEmpty()) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                "Nothing said yet. Claude speaks when there is something worth hearing.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
    }

    LazyColumn(modifier, state = listState, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(lines) { line -> TranscriptRow(line) }
    }
}

@Composable
private fun TranscriptRow(line: TranscriptLine) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (line.role == "user") MaterialTheme.colorScheme.background
                else MaterialTheme.colorScheme.surface,
            )
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (line.role == "user") "You" else "Claude",
                style = MaterialTheme.typography.labelSmall,
                color = if (line.role == "user") MaterialTheme.colorScheme.onSurfaceVariant
                else WalkColors.Accent,
            )
            // Queued but not played, e.g. it arrived while paused.
            if (!line.heard) {
                Spacer(Modifier.size(6.dp))
                Box(Modifier.size(6.dp).clip(CircleShape).background(WalkColors.Accent))
            }
        }
        Spacer(Modifier.size(4.dp))
        Text(
            text = line.text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (line.heard) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * What the recogniser has heard so far, while it is still changing.
 *
 * Given its own block rather than a share of the one-line activity label, which
 * several other states outranked, so live text appeared only when the activity
 * happened to be LISTENING at that instant. Seeing it as it forms is the only
 * chance to notice a mishearing before it is sent.
 *
 * It stays on screen through sending, dimmed, because blanking it at the moment
 * of despatch removed the text exactly when it was worth reading and left nothing
 * until the bridge echoed the line back.
 */
@Composable
private fun LiveTranscript(text: String, sending: Boolean) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (sending) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ActivityLine(state: WalkUiState) {
    val working = state.working &&
        !state.paused &&
        state.activity != Activity.LISTENING &&
        state.activity != Activity.CLAUDE_SPEAKING

    val label = when {
        state.paused && state.pendingWhilePaused > 0 -> "Paused · ${state.pendingWhilePaused} waiting"
        state.paused -> "Paused"
        state.pendingSend.isNotBlank() -> "Sending in a moment. Discard to stop it"
        state.activity == Activity.CLAUDE_SPEAKING -> "Claude is speaking"
        state.continuous && state.micMuted -> "Muted"
        state.activity == Activity.LISTENING ->
            // Deliberately avoids the word "pause". There is a button called Pause
            // on the same screen doing something entirely different, and telling
            // someone to "pause to send" next to it reads as an instruction to press
            // it. Nothing needs pressing: stopping talking is the whole gesture.
            if (state.continuous) "Listening, sends when you stop talking" else "Listening…"
        state.activity == Activity.SENDING -> "Sending…"
        working -> "Claude is working"
        else -> "Waiting"
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        if (working) {
            WorkingDots()
            Spacer(Modifier.size(8.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Three dots breathing in sequence while Claude is mid-turn.
 *
 * Static text cannot distinguish "thinking hard" from "frozen", which is the exact
 * doubt that makes you take the phone out of your pocket. Motion answers it at a
 * glance, with no reading.
 *
 * Honours the system animation setting: with animations turned off the dots are
 * drawn at rest rather than removed, so the state is still legible without moving.
 */
@Composable
private fun WorkingDots() {
    val context = LocalContext.current
    val animate = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }

    val alphas: List<Float> = if (!animate) {
        listOf(0.9f, 0.6f, 0.35f)
    } else {
        val transition = rememberInfiniteTransition(label = "working")
        // Staggered so the eye reads a travelling pulse rather than three blinks.
        List(DOT_COUNT) { i ->
            transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = DOT_CYCLE_MS,
                        delayMillis = i * (DOT_CYCLE_MS / DOT_COUNT),
                    ),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot$i",
            ).value
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        alphas.forEach { alpha ->
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(WalkColors.Accent.copy(alpha = alpha)),
            )
        }
    }
}

private const val DOT_COUNT = 3
private const val DOT_CYCLE_MS = 600

/**
 * Material's default 24dp of horizontal padding per side leaves too little room
 * for "Discard" once three buttons share the width of a phone.
 */
private val ControlPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp)

/**
 * One line, never wrapped, shrunk rather than clipped.
 *
 * Button labels here are set by state, so a longer word ("Discard", "Resume")
 * arrives in a box sized by a shorter one. Without this the text wrapped and the
 * row grew, which is exactly what went wrong when Undo was first added.
 */
@Composable
private fun ControlLabel(text: String) {
    Text(
        text = text,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Visible,
        style = MaterialTheme.typography.labelLarge,
    )
}

@Composable
private fun Controls(
    state: WalkUiState,
    onTapMic: () -> Unit,
    onStopAudio: () -> Unit,
    onDiscard: () -> Unit,
    onUndoLast: () -> Unit,
    onTogglePause: () -> Unit,
    onToggleMute: () -> Unit,
    onSendText: (String) -> Unit,
) {
    var draft by remember { mutableStateOf("") }

    val talking = state.activity == Activity.LISTENING
    val staged = state.pendingSend.isNotBlank()
    val speaking = state.activity == Activity.CLAUDE_SPEAKING

    /**
     * Whether there is anything of yours to throw away right now.
     *
     * This is what decides between Discard and Pause for the last slot, because
     * they answer different questions: one bins what you said, the other holds what
     * Claude is about to say. Offering Discard with nothing to discard wasted the
     * slot, and in hands-free it wasted it permanently, since the microphone is
     * open almost continuously and the row therefore never left the talking shape.
     */
    val hasSomethingToDiscard = staged || (talking && state.partial.isNotBlank())

    // At the desk the phone is not part of the session in either direction, so the
    // controls are inert rather than merely quiet. Leaving Talk live let you speak
    // into a phone whose reply would arrive silently in the terminal you are not
    // holding: the words land, nothing comes back, and the app looks broken. Off
    // means off both ways, and turning it back on is one tap above.
    val live = state.voiceActive

    // Always exactly three, because a fourth does not fit: adding one squeezed every
    // label until the text outgrew its box. So the row changes meaning with the
    // moment instead of growing.
    //
    // Hands-free is what forced this to be thought through properly. The microphone
    // is open almost all the time there, so the old rule, which keyed everything off
    // "are you talking", left the row frozen in one shape and Pause unreachable on
    // screen while the notification still offered it. A control existing in the
    // shade and not in the app is worse than it being absent from both.
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        // In hands-free there is nothing to press to start talking, so the primary
        // button becomes the one control that mode actually needs: a way to shut the
        // microphone without leaving the mode.
        if (state.continuous) {
            Button(
                onClick = onToggleMute,
                enabled = live,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                contentPadding = ControlPadding,
                colors = if (state.micMuted) {
                    ButtonDefaults.buttonColors(containerColor = WalkColors.Critical)
                } else {
                    ButtonDefaults.buttonColors()
                },
            ) {
                ControlLabel(if (state.micMuted) "Unmute" else "Mute")
            }
        } else {
            Button(
                onClick = onTapMic,
                enabled = live,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                contentPadding = ControlPadding,
            ) {
                ControlLabel(if (talking) "Send" else "Talk")
            }
        }

        // Middle slot: take back your last sentence while you are mid-turn,
        // otherwise silence Claude. Stop stays here even when nothing is playing,
        // greyed, rather than yielding to Pause: letting both slots fall through to
        // the same control produced a row reading "Mute, Pause, Pause", which is
        // what happens when two independent conditions share one fallback.
        if (talking && !staged && state.partial.isNotBlank()) {
            // The visible twin of a double tap on the earbud, so the gesture is
            // discoverable and testable at a desk.
            OutlinedButton(
                onClick = onUndoLast,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                contentPadding = ControlPadding,
            ) {
                ControlLabel("Undo")
            }
        } else {
            OutlinedButton(
                onClick = onStopAudio,
                // Never gated on the mode. Silencing audio that is actually playing
                // is always safe and always wanted, and gating it here built a trap:
                // switching to desk mode mid-sentence greyed out the one control
                // that could stop the talking.
                enabled = speaking,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                contentPadding = ControlPadding,
            ) {
                ControlLabel("Stop")
            }
        }

        // Third slot: bin what you said if there is anything to bin, otherwise hold
        // what Claude is about to say. Pause therefore has a home in every state,
        // which is what the notification has always implied.
        if (hasSomethingToDiscard) {
            OutlinedButton(
                onClick = onDiscard,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                contentPadding = ControlPadding,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = WalkColors.Critical),
            ) {
                ControlLabel("Discard")
            }
        } else {
            OutlinedButton(
                onClick = onTogglePause,
                enabled = live,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                contentPadding = ControlPadding,
                colors = if (state.paused) {
                    ButtonDefaults.outlinedButtonColors(contentColor = WalkColors.Accent)
                } else {
                    ButtonDefaults.outlinedButtonColors()
                },
            ) {
                ControlLabel(if (state.paused) "Resume" else "Pause")
            }
        }
    }

    // Text entry must remain available when speech fails. It is still
    // gated on the mode, because "speech failed" and "the phone is not part of
    // this session" are different situations and only the first needs a fallback.
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            enabled = live,
            modifier = Modifier.weight(1f),
            placeholder = { Text(if (live) "Type instead" else "At the desk") },
            shape = RoundedCornerShape(12.dp),
            singleLine = true,
        )
        Spacer(Modifier.size(8.dp))
        Button(
            onClick = { onSendText(draft); draft = "" },
            enabled = live && draft.isNotBlank(),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text("Send")
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun WalkScreenPreview() {
    ClaudeWalkTheme {
        WalkScreen(
            state = WalkUiState(
                connection = ConnectionState.CONNECTED,
                activity = Activity.CLAUDE_SPEAKING,
                activeKey = "a",
                sessions = mapOf(
                    "a" to SessionState(
                        key = "a",
                        project = "my-app",
                        transcript = listOf(
                            TranscriptLine("claude", "I found an issue with the authentication architecture."),
                            TranscriptLine("user", "Why is that a problem?"),
                            TranscriptLine("claude", "Because every request assumes a user object already exists.", heard = false),
                        ),
                    ),
                ),
            ),
            totalUnheardElsewhere = 4,
            onTapMic = {},
            onStopAudio = {},
            onDiscard = {},
            onUndoLast = {},
            onTogglePause = {},
            onSetVoiceActive = {},
            onOpenTailscale = {},
            onToggleMute = {},
            onSetContinuous = {},
            onSendText = {},
            onReconnect = {},
            onEditSettings = {},
            onOpenSessions = {},
        )
    }
}
