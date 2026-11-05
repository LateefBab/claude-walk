# Claude Walk: notes for coding agents

Read this before changing anything. To **set the project up** rather than change it, follow
[docs/setup.md](docs/setup.md).

## What this is

A voice companion for a Claude Code session that is already running. The phone is a speaker
and a microphone for that session: not a second Claude, not a second context. Architecture:
[docs/how-it-works.md](docs/how-it-works.md).

- `bridge/server.ts`: Bun + TypeScript, `@modelcontextprotocol/sdk`, `Bun.serve` for HTTP and
  WebSocket. One file on purpose.
- `android/`: Kotlin 1.9, Jetpack Compose (Material 3), AGP 8.2, Gradle 8.9, JDK 17,
  minSdk 31, targetSdk 34. App id `com.tugartech.claudewalk`.
- `scripts/walk.ps1`: registers the channel in a project and launches a session with it.

## Rules that protect the user

1. **stdout in `bridge/server.ts` is the MCP transport.** Never `console.log` in the bridge.
   Use the `log()` helper, which writes to stderr. One stray line silently breaks the channel.
2. **`/hook/display` must return `{}` immediately.** Claude Code holds the terminal display
   until the `MessageDisplay` hook answers. Slow work here stalls the user's own terminal.
3. **No conversation state in the bridge.** The transcript buffer is a display cache. Do not
   add reference resolution, summarisation or message enrichment: Claude already has the
   context, and a second copy of it is a second Claude.
4. **`bridge/.env` is read by every session on the machine**, from any project. Only the
   token belongs there. Per-project values go in that project's `.mcp.json` `env` block.
5. **Hook endpoints accept loopback callers only.** Keep it that way: the bridge binds
   `0.0.0.0` so the phone can reach it.
6. **Never print or log the token** beyond the existing one-time stderr line when none is
   configured.
7. **Microphone defaults to closed.** Anything that opens the mic must be a deliberate user
   action, never a side effect of arriving somewhere or switching mode.

## Kotlin / Compose

- No `!!`. Composables are stateless and read a `StateFlow`; state lives in the service and
  `WalkRepository`. `Modifier` is the first optional parameter.
- `SpeechRecognizer` must be created and driven on the main thread.
- Release the TTS engine, recognizer, socket and media session in `onDestroy`. A leaked
  recognizer holds the microphone.

## Commands

| Purpose | Command |
|---|---|
| Bridge test (must pass) | `cd bridge && bun smoke.ts` |
| Build the app | `cd android && ./gradlew assembleDebug` (`gradlew.bat` on Windows) |
| Install | `adb install -r android/app/build/outputs/apk/debug/app-debug.apk` |
| Launch a voice session | `claude --dangerously-load-development-channels server:claude-walk --dangerously-skip-permissions` from the repo root |
| Bridge health | `curl "http://127.0.0.1:8710/health?token=<token>"` (`curl.exe` in Windows PowerShell) |
| Desk test without Tailscale | `adb reverse tcp:8710 tcp:8710`, then host `127.0.0.1` in the app |

Ports 8710-8719, one per concurrent session.
