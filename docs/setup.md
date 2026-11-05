# Setup

Every step ends with a **Check** that tells you what you should see before moving on. Steps
are tagged:

- **[agent]**: a coding agent (or you) can do it from a terminal on the computer.
- **[you]**: needs a human: a phone in hand, an admin prompt, or a sign-in.

If you are an agent following this guide: do the [agent] steps, verify each Check, and at
every [you] step stop and tell the user exactly what to do, then wait for them to confirm.
Never print the token anywhere except where a step says to.

Commands are shown for Windows PowerShell 7 first, with macOS/Linux notes where they differ.
**Every command runs from the root of the cloned repo** unless the block itself changes
directory and comes back.

---

## 1. Install the prerequisites

**[agent]** Confirm these are installed, and install what is missing:

| Tool | Check | Install |
|---|---|---|
| Claude Code | `claude --version` | https://code.claude.com/docs |
| Bun 1.3+ | `bun --version` | https://bun.sh |
| Git | `git --version` | https://git-scm.com |
| Tailscale | `tailscale version` | https://tailscale.com/download |
| JDK 17 | `java -version` shows 17 | Android Studio bundles one, or https://adoptium.net |
| Android SDK, platform 34 | `adb version` | [Android Studio](https://developer.android.com/studio) (then SDK Manager), or the [command-line tools only](https://developer.android.com/studio#command-line-tools-only) download. See step 6. |

**[you]** Claude Code must be signed in with a claude.ai or Console account. Channels are a
research preview and do not work through Bedrock, Vertex or Foundry. On a Team or Enterprise
plan, an admin has to enable channels first.

`adb` lives in the SDK's `platform-tools` folder, which installers do not add to `PATH`.
Add it for the current shell:

```powershell
$env:Path += ";$env:LOCALAPPDATA\Android\Sdk\platform-tools"   # macOS: export PATH="$PATH:$HOME/Library/Android/sdk/platform-tools"
```

**Check:** every command in the table prints a version.

## 2. Clone and test the bridge

**[agent]**

```powershell
git clone https://github.com/LateefBab/claude-walk.git
cd claude-walk
cd bridge
bun install --frozen-lockfile
bun smoke.ts
cd ..
```

Avoid cloning into a cloud-synced folder (OneDrive, Dropbox, iCloud Drive). Sync clients lock
build files mid-build. If you must, see step 6 for moving the Android build output.

**Check:** the last line is `RESULT: ALL PASS`. If the step that checks non-loopback hooks
prints `SKIP`, that is fine: the machine has no network interface to test it against.

## 3. Create the token

The phone proves it is yours with a shared token.

**[agent]** Create `bridge/.env` containing one line, `CLAUDE_WALK_TOKEN=<token>`. Windows,
PowerShell 7 (`pwsh`):

```powershell
$t = bun -e "console.log(require('crypto').randomBytes(16).toString('base64url'))"
"CLAUDE_WALK_TOKEN=$t" | Out-File -Encoding utf8NoBOM bridge/.env
```

macOS/Linux:

```bash
echo "CLAUDE_WALK_TOKEN=$(bun -e "console.log(require('crypto').randomBytes(16).toString('base64url'))")" > bridge/.env
```

`bridge/.env` is git-ignored. Only machine-wide values go in it, because every session that
loads the bridge reads it, whichever project it runs in. Per-project settings go in that
project's `.mcp.json` (step 10).

**[you]** You will type this token into the phone in step 8. Open `bridge/.env` and keep it
handy, or move it to your phone through a password manager.

**Check:** `bridge/.env` exists and holds one non-empty `CLAUDE_WALK_TOKEN=` line.

## 4. Put both devices on Tailscale

**[you]**

1. Install Tailscale on the computer and sign in: https://tailscale.com/download
2. Install the Tailscale app on the phone and sign in **to the same account / tailnet**.
3. Leave Tailscale connected on the phone whenever you walk.

**[agent]** Find the computer's address on the tailnet:

```powershell
tailscale status        # the phone should be listed, not "offline"
tailscale ip -4         # the computer's 100.x.y.z address
```

With MagicDNS on (the default), the phone can also reach the computer by its machine name,
as shown in the first column of `tailscale status`.

**Check:** `tailscale status` lists both devices, and neither is marked offline.

## 5. Open the ports, on the Tailscale interface only

Each voice-enabled session claims one port between 8710 and 8719. The phone connects to them
inbound. The bridge listens on all interfaces, so the firewall decides who can reach it. The
rule below opens the ports only on the Tailscale adapter, so they stay closed on Wi-Fi and
public networks.

**Check for a program rule for `bun`.** The first time any Bun program listens on a port,
Windows may ask whether to allow it ("Windows Defender Firewall has blocked some
features"). Whatever you answered became a rule for `bun.exe` that applies to every port:

- An **Allow** rule covering the Public profile makes the bridge reachable from any Wi-Fi
  you join, whatever the rule below says.
- A **Block** rule on the profile your Tailscale adapter uses stops the phone connecting.

List them, and the profile each network uses:

```powershell
Get-NetFirewallApplicationFilter | Where-Object Program -match '\\bun\.exe$' | Get-NetFirewallRule |
  Format-Table DisplayName, Action, Direction, Profile, Enabled
Get-NetConnectionProfile | Format-Table InterfaceAlias, NetworkCategory
```

Review the list. If you do not need Bun programs reachable from other machines, remove
them (elevated): `Get-NetFirewallApplicationFilter | Where-Object Program -match '\\bun\.exe$' | Get-NetFirewallRule | Remove-NetFirewallRule`.
If Windows asks again later, any answer creates a new program rule, so run the check again
afterwards. The Tailscale rule below is what should let the phone in.

**[you]** Windows, in an **elevated** PowerShell (Run as administrator):

```powershell
New-NetFirewallRule -DisplayName "Claude Walk bridge (Tailscale only)" `
  -Direction Inbound -Action Allow -Protocol TCP -LocalPort 8710-8719 `
  -InterfaceAlias "Tailscale" -Profile Any
```

To remove it later: `Remove-NetFirewallRule -DisplayName "Claude Walk bridge (Tailscale only)"`.

- **Linux with ufw:** `sudo ufw allow in on tailscale0 to any port 8710:8719 proto tcp`
- **macOS:** the built-in firewall is off by default, so the ports are reachable from your
  local network, protected only by the token. The application firewall cannot scope by
  interface; use it (or a tool like `pf`) if that matters to you, and allow `bun` when asked.

**Check:** `Get-NetFirewallRule -DisplayName "Claude Walk bridge (Tailscale only)"` shows the
rule as enabled. The end-to-end test happens in step 9.

## 6. Build the Android app

**[agent]**

Option A, Android Studio: open the `android/` folder, let it sync, then
**Build > Build App Bundle(s) / APK(s) > Build APK(s)**. Studio installs SDK 34 when prompted
and writes `android/local.properties` for you.

Option B, command line:

1. Install SDK platform 34 and accept the licenses. With Android Studio installed, use
   **Settings > Languages & Frameworks > Android SDK**. With only the command-line tools:
   `sdkmanager "platforms;android-34" "platform-tools"` then `sdkmanager --licenses`.
2. Point the build at the SDK. Create `android/local.properties`:

   ```properties
   sdk.dir=C:/Users/<you>/AppData/Local/Android/Sdk
   ```

   On macOS that is usually `/Users/<you>/Library/Android/sdk`; on Linux `/home/<you>/Android/Sdk`.
   Skip this if `ANDROID_HOME` is already set.
3. Make sure `JAVA_HOME` points at JDK 17 (Android Studio's bundled one lives in its `jbr`
   folder).
4. Build:

   ```powershell
   cd android
   .\gradlew.bat assembleDebug      # macOS/Linux: ./gradlew assembleDebug
   cd ..
   ```

   The first build downloads Gradle and dependencies and takes several minutes. Kotlin
   warnings (`w: ...`) along the way are normal; only `BUILD FAILED` is a failure.

The APK lands at `android/app/build/outputs/apk/debug/app-debug.apk`.

If the repo sits in a cloud-synced folder and the build fails with files locked or missing,
add `claudewalk.buildDir=C:/somewhere/outside/sync` to `android/local.properties`. The APK then
lands under that folder at `app/outputs/apk/debug/`.

**Check:** `app-debug.apk` exists at the path above.

## 7. Install it on the phone

**[you]** Enable USB debugging:

1. **Settings > About phone**, tap **Build number** seven times.
2. **Settings > System > Developer options**, turn on **USB debugging**.
3. Connect the phone by USB and accept the "Allow USB debugging?" prompt on it.

(Wireless debugging works too: **Developer options > Wireless debugging**, then
`adb pair` and `adb connect` as shown on the phone.)

**[agent]**

```powershell
adb devices                                    # exactly one device, state "device"
adb shell getprop ro.product.model             # confirm it is the right phone
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

If more than one device is attached, add `-s <serial>` to every `adb` command.

**Check:** `adb install` prints `Success`, and **Claude Walk** appears in the phone's apps.

## 8. Configure the app

**[you]** Open Claude Walk on the phone:

1. **Host:** the computer's Tailscale machine name or `100.x.y.z` address from step 4.
2. **Port:** `8710`.
3. **Token:** the value from `bridge/.env`.
4. Tap **Save and connect**.
5. Allow the **microphone** permission, and **notifications** (the playback controls live in
   the notification).

It will show **Disconnected** for now. That is expected: there is no session to connect to
until step 9.

## 9. Start a voice-enabled session

**[you]** In a **new terminal of your own** (not inside the Claude session that may be
helping you set up), from the root of the cloned repo:

```powershell
claude --dangerously-load-development-channels server:claude-walk --dangerously-skip-permissions
```

On first launch Claude Code asks you to approve the project's MCP server (`claude-walk` from
`.mcp.json`), and warns that a development channel is loading. Approve both.

**[agent]** Confirm the bridge is up (replace `<token>`):

```powershell
curl.exe "http://127.0.0.1:8710/health?token=<token>"   # macOS/Linux: curl
```

**Check:**

- The health call returns JSON that includes `"project"` and `"phones"`. A bare
  `{"ok":true}` means the token did not match.
- Within a few seconds the phone shows the project name instead of **Disconnected**, and
  `phones` becomes `1`.
- **[you]** Tap **Start walking**, put your earbuds in, and Claude says where things stand.
  Tap **Talk**, say "can you hear me", tap **Send**. The reply should come back as speech.

If the phone does not connect, see [troubleshooting](troubleshooting.md). To take Tailscale
and the firewall out of the picture while debugging at a desk, connect by USB, run
`adb reverse tcp:8710 tcp:8710`, and set the app's host to `127.0.0.1`.

### About the two flags

The launch command uses two flags that sound alike but do unrelated things:

- `--dangerously-load-development-channels server:claude-walk` lets a channel that is not on
  Anthropic's allowlist register. Without it, the bridge never becomes a channel. It does
  nothing to tool permissions.
- `--dangerously-skip-permissions` lets Claude run tools (edit files, run commands) **without
  asking you first**. It is there because you cannot answer a permission prompt from your
  pocket: without it, the session stalls on the first prompt until you are back at the desk.

Only use the second flag in a project you trust, with work you would be comfortable letting
Claude carry out unattended. Even with it, some actions still prompt (explicit `ask` rules,
tools that require interaction, deletions aimed at your home or root directory). If a walk
goes quiet while the phone shows connected, a waiting prompt is the first thing to check.
You can leave the flag off and accept that some prompts will wait for you.

## 10. Voice-enable your other projects

The steps above voice-enable this repo's own folder. To use Claude Walk while working on
another project, that project needs the channel registered in its `.mcp.json`. Each
concurrently running session also needs its own port.

**Windows (helper script):**

```powershell
# from the claude-walk repo; -ExecutionPolicy Bypass avoids a script-policy block
pwsh -ExecutionPolicy Bypass -File scripts/walk.ps1 -Project "C:\path\to\other-project" -Port 8711
```

This adds `claude-walk` to that project's `.mcp.json` (keeping any existing servers) and
launches Claude Code there with both flags. Use `-RegisterOnly` to set it up without
launching, or `-WhatIf` to see what it would do. If the project already has a `claude-walk`
entry, it is left alone.

**Any OS (by hand):** add this to the other project's `.mcp.json`, using the absolute path to
your clone:

```json
{
  "mcpServers": {
    "claude-walk": {
      "command": "bun",
      "args": ["/absolute/path/to/claude-walk/bridge/server.ts"],
      "env": { "CLAUDE_WALK_PORT": "8711" }
    }
  }
}
```

**Optional, the screen mirror:** speech works without this. To also mirror Claude's written
replies to the phone screen and have it show when Claude's turn ends, copy the `hooks` block from
this repo's `.claude/settings.json` into the other project's `.claude/settings.json`, changing
`8710` to that project's port in both URLs. The hooks only work when the port is pinned, as
above.

**Already in a session without voice?** A channel can only be loaded when Claude Code starts.
Exit, then relaunch with `--continue` to pick the same conversation back up:
`pwsh -File scripts/walk.ps1 -Continue`, or add `--continue` to the launch command.

**Check:** with two sessions running, the phone's session list shows both.

## After a reboot

Tailscale and the firewall rule survive a reboot. Nothing else starts by itself: the bridge
only lives as long as the Claude Code session that started it. To walk again, start a session
(step 9) and open the app.
