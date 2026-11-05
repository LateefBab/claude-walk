# Claude Walk

Supervise a running Claude Code session by voice, from your earbuds, while you walk.

![License: MIT](https://img.shields.io/badge/license-MIT-blue)
![Android 12+](https://img.shields.io/badge/Android-12%2B-3ddc84)
![Bun](https://img.shields.io/badge/runtime-Bun-black)

Claude Walk turns your Android phone into a speaker and a microphone for the Claude Code
session that is already running on your computer. Claude speaks the things worth hearing
(a finding, a decision, a question it needs answered) into your earbuds. You answer out loud,
and your reply lands in that same session as if you had typed it.

It is not a second Claude, a separate chat, or a summary of your session. It is the same
session, with the terminal swapped for your ears.

> Built by Lateef Babatunde for personal use and shared as is. Independent project, not affiliated
> with or endorsed by Anthropic. "Claude" and "Claude Code" are Anthropic's names for their
> products.

## How it works

```
  Android phone                                   Your computer
 +---------------------+      Tailscale      +-----------------------------+
 |  Claude Walk app    |  <---- WebSocket --> |  bridge (Bun, port 8710+)   |
 |  text-to-speech     |                      |        ^  MCP channel       |
 |  speech recognizer  |                      |        v  (stdio)           |
 +---------------------+                      |  your Claude Code session   |
        earbuds                               +-----------------------------+
```

1. Claude Code starts the **bridge** as an MCP server and loads it as a
   [channel](https://code.claude.com/docs/en/channels). The bridge gives Claude one tool,
   `speak`, plus instructions on when to use it.
2. The **phone app** connects to the bridge over your [Tailscale](https://tailscale.com)
   network, from anywhere, including mobile data. Nothing is exposed to the public internet.
3. When Claude calls `speak`, the phone reads it aloud. When you talk, the phone transcribes
   you and the bridge pushes the text into the session as a channel event.
4. Two [hooks](https://code.claude.com/docs/en/hooks) mirror Claude's written replies to the
   phone screen, and tell the phone when a turn ended without anything being said.

Voice starts **off**. Every session can carry the channel without talking. Tap
**Start walking** in the app when you get up, and **Back at the desk** when you return.
Claude is told each time and adjusts how it communicates.

More detail: [docs/how-it-works.md](docs/how-it-works.md).

## Requirements

| What | Version / notes |
|---|---|
| [Claude Code](https://code.claude.com/docs) | Recent release, signed in with a claude.ai or Console account. Channels are a research preview: they are not available through Bedrock, Vertex or Foundry, and on Team or Enterprise plans an admin must enable them. Tested with 2.1.293. |
| [Bun](https://bun.sh) | 1.3 or newer (tested with 1.3.11). Runs the bridge. |
| [Tailscale](https://tailscale.com/download) | On the computer and on the phone, signed in to the same tailnet. |
| Android phone | Android 12 (API 31) or newer, with a microphone and earbuds. Tested on a Pixel 6 Pro. |
| JDK | 17. Android Studio bundles one. |
| Android SDK | Platform 34. Easiest through [Android Studio](https://developer.android.com/studio). |
| `adb` | Comes with the Android SDK platform-tools. Used to install the app. |
| Operating system | Built and tested on Windows 11. The bridge is plain Bun and should run on macOS and Linux, but that is untested. The helper script `scripts/walk.ps1` needs PowerShell 7 (`pwsh`) there. |

## Quick start

The full walkthrough, with a check after every step, is in **[docs/setup.md](docs/setup.md)**.
In short:

```bash
git clone https://github.com/LateefBab/claude-walk.git
cd claude-walk
cd bridge; bun install --frozen-lockfile; bun smoke.ts; cd ..   # expect: RESULT: ALL PASS
```

The rest runs from the repo root.

1. Generate a token and put it in `bridge/.env` as `CLAUDE_WALK_TOKEN=...`.
2. Allow ports 8710-8719 inbound on the Tailscale interface only (Windows firewall rule in
   the setup guide).
3. Build the app (in `android/`, run `.\gradlew.bat assembleDebug` on Windows or
   `./gradlew assembleDebug` elsewhere), then install it with
   `adb install -r android/app/build/outputs/apk/debug/app-debug.apk`.
4. In the app, enter your computer's Tailscale name or IP, port `8710` and the token.
5. Start Claude Code with the channel:

   ```bash
   claude --dangerously-load-development-channels server:claude-walk --dangerously-skip-permissions
   ```

6. Approve the channel prompt, put your earbuds in, tap **Start walking**, and go.

Read the [note on `--dangerously-skip-permissions`](docs/setup.md#about-the-two-flags) before
using it.

### Let your Claude set it up

If you already use Claude Code, open it in your clone of this repo and say:

> Read docs/setup.md and set up Claude Walk on this machine. Do every step marked [agent]
> yourself, stop at each step marked [you] and tell me exactly what to do, and verify each
> step before moving on.

The steps that need a human (signing in to Tailscale on the phone, enabling USB debugging,
an elevated firewall command, approving the channel prompt, typing settings into the app)
are marked so it knows to hand them to you.

## Using it

| You want to | Do this |
|---|---|
| Start a walk | Tap **Start walking**. Claude says where things stand. |
| Talk to Claude | In **Press to talk** mode, tap your earbud (or **Talk**), speak, then tap again (or **Send**). In **Hands-free** mode the mic stays open and sends when you stop talking. Hands-free starts muted, so tap **Unmute** when you want it listening. |
| Take back a sentence | Double-tap your earbud while talking (or **Undo**). Each double tap removes one more sentence. |
| Drop the whole message | **Discard**, before it sends. |
| Silence Claude | Double-tap your earbud while Claude is speaking (or **Stop**). **Pause** holds what Claude says next until you **Resume**. |
| Type instead | Use the text box at the bottom. |
| Switch between sessions | Open the session list from the header or swipe in from the left edge. Every voice-enabled session on the computer shows up, one per port in 8710-8719. |
| Finish | Tap **Back at the desk**. Claude goes back to writing in the terminal. |

Claude is told to speak only when something is worth hearing, to announce long silences,
to restate your question before answering it, and to confirm before acting on anything
destructive it may have misheard.

## Limitations

- **Spoken replies queue, they do not interrupt.** What you say arrives when Claude's
  current turn ends. "Stop" cannot halt a command that is already running.
- **Research preview.** Channels and the development-channel flag may change in future
  Claude Code releases.
- **English only.** The app asks the speech recognizer for US English (`en-US`). Other
  languages are not supported yet.
- **One phone, one person.** No accounts, no multi-user support.
- **Earbud button.** Android gives the media button to whichever app last played audio. If
  you start music, that app takes the button until Claude Walk speaks again. During a phone
  call, the button controls the call.
- **Remote Control** in the Claude mobile app shows Claude's replies but not your spoken
  messages, so use the Claude Walk app for the transcript.

## Privacy and security

- Your voice is transcribed by the phone's speech recognition service. On most phones that
  is Google's, which may process audio on its servers. Speech output is synthesized on the
  phone.
- The bridge talks only to your phone, over your tailnet. It makes no other network calls.
- The phone must present a shared token to connect. Hook endpoints accept only local
  callers. Traffic is plain WebSocket, carried inside Tailscale's encrypted WireGuard tunnel.
- The setup guide scopes the firewall rule to the Tailscale interface, so the ports stay
  closed on Wi-Fi and public networks.

See [SECURITY.md](SECURITY.md) for the threat model and how to report a problem.

## Troubleshooting

"Disconnected" on the phone almost always means no voice-enabled session is running. More
symptoms and fixes: [docs/troubleshooting.md](docs/troubleshooting.md).

## Contributing

This is a personal project shared in the hope it is useful. Issues are welcome; see
[CONTRIBUTING.md](CONTRIBUTING.md).

## License

[MIT](LICENSE) © 2026 Lateef Babatunde, TugarTech.
