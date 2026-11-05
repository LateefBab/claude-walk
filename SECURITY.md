# Security

## Reporting a problem

Please report security issues privately through GitHub: **Security > Report a
vulnerability** on this repository. Do not open a public issue for them.

This is a personal project maintained in spare time. I will read every report, but I cannot
promise a response time.

## Supported versions

Only the latest commit on `main`.

## Threat model

Claude Walk is designed for one person, one computer and one phone on a private Tailscale
network.

| Layer | Protection |
|---|---|
| Network reach | The bridge listens on ports 8710-8719 on all interfaces. On Windows and on Linux with ufw, the setup guide opens them in the firewall only on the Tailscale interface, so devices on your Wi-Fi or a public network cannot reach them. On a machine without such a rule (macOS by default), they are reachable from the local network and only the token protects them. |
| Transport | Plain WebSocket (`ws://`) inside Tailscale's WireGuard tunnel, which encrypts it end to end. The app allows cleartext for this reason, because the host is chosen by you at runtime. |
| Phone authentication | A shared token in `bridge/.env`. A socket that does not present it first is closed. `/health` returns detail only with the token. |
| Hooks | `/hook/display` and `/hook/stop` accept loopback callers only, and refuse browser-shaped requests (an `Origin` header or a non-JSON body), so neither the network nor a web page you have open can inject text into your transcript. |
| Secrets in the repo | `bridge/.env` and `android/local.properties` are git-ignored. No keys are needed: speech runs on the phone's built-in services. |

## Things to know

- **Anyone on your tailnet who has the token can talk to your session.** Treat the token
  like a password. If you share your tailnet, generate a new token after removing someone.
- **`--dangerously-skip-permissions`** lets Claude run tools without asking. Spoken
  instructions can be misheard. Claude is instructed to confirm anything destructive, but
  only use the flag in projects where unattended work is acceptable.
- **Speech recognition** uses the phone's recognition service, usually Google's, which may
  process audio off the device.
- If no token is configured, the bridge generates one per launch and writes it to stderr,
  which Claude Code keeps in its MCP logs.
