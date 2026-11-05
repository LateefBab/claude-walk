package com.tugartech.claudewalk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tugartech.claudewalk.SessionsUiState
import com.tugartech.claudewalk.net.DiscoveredSession
import com.tugartech.claudewalk.ui.theme.ClaudeWalkTheme
import com.tugartech.claudewalk.ui.theme.MonoSmall
import com.tugartech.claudewalk.ui.theme.WalkColors

/**
 * The session tray.
 *
 * Lives in a navigation drawer rather than its own page: switching should not mean
 * leaving the conversation. A permanent side rail was considered and rejected, since
 * the phone is held one-handed and a rail would steal width from the transcript for
 * something consulted a few times a walk.
 */
@Composable
fun SessionDrawer(
    state: SessionsUiState,
    onRefresh: () -> Unit,
    onSelect: (DiscoveredSession) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Sessions", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            if (state.scanning) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                TextButton(onClick = onRefresh) { Text("Refresh") }
            }
        }

        Spacer(Modifier.size(12.dp))

        when {
            state.error != null -> Hint(state.error)

            state.sessions.isEmpty() && !state.scanning -> Hint(
                "Nothing live. A session appears here only if it was started with the channel " +
                    "flag: claude --dangerously-load-development-channels server:claude-walk " +
                    "--dangerously-skip-permissions",
            )

            else -> LazyColumn(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.sessions) { session ->
                    SessionRow(
                        session = session,
                        active = session.port == state.activePort,
                        onClick = { onSelect(session) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 16.dp),
    )
}

@Composable
private fun SessionRow(session: DiscoveredSession, active: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (active) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.background,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (active) WalkColors.Accent else WalkColors.BorderDefault),
        )
        Spacer(Modifier.size(12.dp))

        Column(Modifier.weight(1f)) {
            Text(
                session.project,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
            )
            Text(
                // Auto-assigned ports mean that project's hook URLs do not match, so
                // its terminal mirror is quiet. Say so rather than look broken.
                buildString {
                    append("port ${session.port}")
                    if (!session.hooks) append(" · mirror off")
                },
                style = MonoSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }

        // Unheard count, straight from the bridge's own outstanding queue. Nothing
        // is lost while you are elsewhere; this is how much is waiting.
        if (session.unheard > 0) {
            Spacer(Modifier.size(8.dp))
            Box(
                Modifier
                    .clip(CircleShape)
                    .background(WalkColors.Accent)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(
                    session.unheard.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 320)
@Composable
private fun SessionDrawerPreview() {
    ClaudeWalkTheme {
        SessionDrawer(
            state = SessionsUiState(
                sessions = listOf(
                    // Named, not positional: adding launchedBy mid-way silently
                    // shifted every argument here into the wrong parameter.
                    DiscoveredSession(
                        port = 8710, sessionId = "72900", launchedBy = "user",
                        project = "my-app", cwd = "C:/…",
                        hooks = true, phones = 1, unheard = 0,
                    ),
                    DiscoveredSession(
                        port = 8711, sessionId = "12664", launchedBy = "user",
                        project = "api-server", cwd = "C:/…",
                        hooks = false, phones = 0, unheard = 3,
                    ),
                    DiscoveredSession(
                        port = 8712, sessionId = "14240", launchedBy = "unknown",
                        project = "docs-site", cwd = "C:/…",
                        hooks = false, phones = 0, unheard = 1,
                    ),
                ),
                activePort = 8710,
            ),
            onRefresh = {},
            onSelect = {},
        )
    }
}
