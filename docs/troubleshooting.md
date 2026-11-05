# Troubleshooting

Start with the bridge's own view. With a session running:

```powershell
curl.exe "http://127.0.0.1:8710/health?token=<token>"   # macOS/Linux: curl
```

It reports the project, how many phones are connected, and how many spoken messages are
waiting to be heard.

| Symptom | Likely cause | Fix |
|---|---|---|
| Phone says **Disconnected** | No voice-enabled session is running. The bridge only exists while the Claude Code session that started it is open. | Start one with the launch command in [setup step 9](setup.md#9-start-a-voice-enabled-session). |
| `curl` to `/health` is refused | The session was started without the channel, or from a folder whose `.mcp.json` has no `claude-walk` entry. | Launch from the repo root (or a project set up in [step 10](setup.md#10-voice-enable-your-other-projects)) with both flags. In Claude Code, `/mcp` shows whether `claude-walk` is connected. |
| `/health` answers but the phone never connects | The firewall rule is missing, or Tailscale is off on the phone. | Check `tailscale status` shows the phone online. Re-run the firewall command from [step 5](setup.md#5-open-the-ports-on-the-tailscale-interface-only). From the phone's browser, open `http://<host>:8710/health`: a `{"ok":true}` page means the network path is fine. |
| Phone connects, then drops at once | Token mismatch. The bridge closes the socket with code 4001. | Compare the token in the app's **Settings** with `bridge/.env`. If `bridge/.env` is missing, the bridge makes a new token every launch and logs it to Claude Code's MCP log. |
| `/health` returns only `{"ok":true}` | The token in your `curl` does not match. | Same as above. Detail is only returned to a caller holding the token. |
| The app shows **Tailnet is down** | The phone's Tailscale VPN is off. | Tap **Open Tailscale** and connect. |
| Claude does not talk after **Start walking** | It is in the middle of a turn. Messages are delivered when the current turn ends. | Wait. Claude announces long silences when voice is on. |
| A walk goes quiet but the phone shows connected | Claude is waiting on a permission prompt in the terminal. | Launch with `--dangerously-skip-permissions` (read [the note](setup.md#about-the-two-flags) first). Some prompts still appear even then. |
| Saying "stop" does not stop a running command | Spoken messages queue until the current turn ends. They do not interrupt it. | Expected. Use Remote Control or the terminal to interrupt. |
| Earbud button does nothing, or controls music instead | Android gives the media button to the app that last played audio. | Let Claude Walk speak once, or tap **Talk** on screen. During a phone call the button belongs to the call. |
| Session list shows a session that never speaks | Any session in a project with a `claude-walk` entry in `.mcp.json` starts a bridge, even without the channel flag. On Windows the bridge detects the missing flag and hides itself, unless the port is pinned with `CLAUDE_WALK_PORT`. On macOS and Linux it cannot tell, so it is listed. | Relaunch that session with both flags, or remove the entry from that project's `.mcp.json`. |
| A session is missing from the list | All ten ports (8710-8719) are taken, or a stray bridge started by hand is holding one. | Close sessions you are not using. Find what holds a port with `netstat -ano \| findstr :8710`. |
| The transcript on the phone is empty, but speech works | The session's port is not pinned, so the hooks in `.claude/settings.json` point at the wrong port. | Pin the port with `CLAUDE_WALK_PORT` in that project's `.mcp.json` and match the hook URLs. See [step 10](setup.md#10-voice-enable-your-other-projects). |
| Remote Control shows Claude answering nothing | Remote Control does not display channel messages, only Claude's replies. | Expected. Claude restates each question before answering so the transcript still reads. |
| Android build fails with files locked, missing or "unable to delete" | The repo is in a cloud-synced folder. | Set `claudewalk.buildDir` in `android/local.properties`. See [setup step 6](setup.md#6-build-the-android-app). |
| Build fails with a Java version error | Gradle is not using JDK 17. | Point `JAVA_HOME` at a JDK 17, or build from Android Studio. |
| Misheard words in your messages | Phone speech recognition, especially outdoors. | Claude is instructed to confirm before acting on anything destructive it may have misheard. Use **Undo** (or double-tap the earbud) to drop a bad sentence before sending. |
