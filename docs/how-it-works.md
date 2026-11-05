# How it works

## The one idea

The phone is a speaker and a microphone for the Claude Code session already running on your
computer. It is not a second Claude, not a second context, and not a proxy conversation.
Claude decides what is worth saying. The bridge carries words in both directions and keeps
no conversation state of its own.

Everything below follows from that.

## Pieces

```
bridge/server.ts     one Bun process: MCP channel (stdio) + HTTP + WebSocket
android/             native Kotlin / Jetpack Compose app
scripts/walk.ps1     registers the channel in a project and launches Claude Code with it
.mcp.json            registers the bridge for this repo, port pinned to 8710
.claude/settings.json  MessageDisplay and Stop hooks pointing at that port
```

### The bridge

Claude Code spawns `bridge/server.ts` as an MCP server over stdio. Because the server
declares the `claude/channel` capability and the session is launched with
`--dangerously-load-development-channels server:claude-walk`, it becomes a
[channel](https://code.claude.com/docs/en/channels): it can push messages into the session.

It exposes one tool, `speak(text)`, and an `instructions` string that Claude Code adds to the
session. The instructions tell Claude when speaking is worth it, how to shape speech for
listening rather than reading, and how to handle transcription errors.

The same process serves:

| Endpoint | Caller | Purpose |
|---|---|---|
| `GET /ws` | the phone | WebSocket. The first frame must be `hello` with the token, or the socket is closed with 4001. |
| `GET /health` | the phone's session scan, you | Liveness. Project, session and phone counts only with `?token=`. |
| `POST /hook/display` | Claude Code (`MessageDisplay` hook) | Mirrors Claude's written output to the phone screen. Returns `{}` immediately, because Claude Code holds the terminal display until it answers. |
| `POST /hook/stop` | Claude Code (`Stop` hook) | Marks the turn boundary, so the phone stops showing Claude as working. |

Hook endpoints accept only loopback callers.

**stdout is the MCP transport.** Anything else written there corrupts the protocol, so every
diagnostic goes to stderr.

### Voice on and off

Voice starts off, so any number of sessions can carry the channel without talking. The phone
sends a `voice` frame with `active: true` when you tap **Start walking**. The bridge tells Claude through a channel
message, and Claude starts using `speak`. **Back at the desk** reverses it. While voice is
off, `speak` returns "not spoken" and nothing is queued, because a backlog built up while you
sat at the desk would be stale by the time you stood up.

### Delivery

Each spoken item gets an id and stays in an unacked queue until the phone confirms it
finished playing. On reconnect, everything unacked is replayed, so a signal dropout does not
lose an update. Delivery is best effort: if an ack itself is lost, the item can play twice. Ids are scoped to the bridge process, so an ack
from an earlier session cannot clear a message in this one.

The bridge also keeps the last 40 transcript lines so a reconnecting phone can fill its
screen. That is a display cache, the same role as terminal scrollback, not conversation
state.

### Ports and sessions

Every session spawns its own bridge. A bridge with `CLAUDE_WALK_PORT` set binds exactly that
port, which keeps the static hook URLs matching. Otherwise it takes the first free port in
8710-8719. The phone scans the range with the token to list live sessions, and you switch
between them in the app.

### The app

A foreground service owns the WebSocket, text-to-speech, the speech recognizer and a media
session, so everything keeps working with the screen off and the phone in a pocket.

- **Speech out:** Android `TextToSpeech`, with a voice you pick in the app (offline voices run
  on the phone). Long messages are split to fit the engine's limit.
- **Speech in:** Android `SpeechRecognizer`. Press to talk, or hands-free (which starts muted).
  A turn can span pauses; each sentence is kept separately so one can be undone.
- **Earbud button:** Android only routes media buttons to an app that is actually playing
  audio, and speech output does not count. The app therefore plays a silent looping track to
  hold the button.
- **Tailnet monitor:** watches whether the phone has a VPN up, to tell "Tailscale is off"
  apart from "no session is running". It checks for any VPN, so a different VPN app can
  hide a disconnected tailnet.

## Known constraints

- Channel messages do not interrupt a running turn. They queue until it ends.
- Remote Control does not render inbound channel messages, so Claude restates each spoken
  question before answering it.
- A custom channel is not on Anthropic's allowlist, hence the development-channels flag.
