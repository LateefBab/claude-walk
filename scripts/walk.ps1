<#
.SYNOPSIS
  Start a Claude Code session that is voice-enabled for Claude Walk.

.DESCRIPTION
  Wraps the two flags that must be used together and, if needed, registers the
  claude-walk channel in the target project so `server:claude-walk` resolves.

  The two flags are unrelated despite the shared prefix:
    --dangerously-load-development-channels   lets a non-allowlisted channel register
    --dangerously-skip-permissions            stops tool permission prompts

  With only the first, the session stalls at the first permission prompt and there
  is no way to answer it from a pocket.

.EXAMPLE
  # Voice-enable the project you are standing in
  ./scripts/walk.ps1

.EXAMPLE
  # Voice-enable another project
  ./scripts/walk.ps1 -Project "C:\path\to\other-project"

.EXAMPLE
  # See what it would do, without launching or writing anything
  ./scripts/walk.ps1 -Project "C:\path\to\other-project" -WhatIf
#>
[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [string]$Project = (Get-Location).Path,

    # Pin this project's bridge to a fixed port. Required for that project's
    # MessageDisplay and Stop hooks to match, since their URLs are static.
    [int]$Port,

    # Register the channel in the project and stop, without starting a session.
    [switch]$RegisterOnly,

    # Carry on the most recent conversation in this project instead of starting a
    # new one. A channel can only be opted into at startup, so voice-enabling a
    # session already in flight means relaunching the process; -Continue keeps the
    # conversation so nothing is lost in the process.
    [switch]$Continue,

    # Resume a specific conversation by id rather than the most recent.
    [string]$SessionId
)

$ErrorActionPreference = 'Stop'

$bridge = Join-Path (Split-Path $PSScriptRoot -Parent) 'bridge\server.ts'
if (-not (Test-Path -LiteralPath $bridge)) {
    Write-Error "Bridge not found at $bridge"
    exit 1
}

if (-not (Test-Path -LiteralPath $Project)) {
    Write-Error "Project not found: $Project"
    exit 1
}
$Project = (Resolve-Path -LiteralPath $Project).Path

if (-not (Get-Command bun -ErrorAction SilentlyContinue)) {
    Write-Error 'bun is not on PATH. The bridge runs on Bun.'
    exit 1
}

# --- Register the channel in the target project -----------------------------
# Claude Code resolves `server:claude-walk` from the project's .mcp.json, so a
# project without this entry silently starts with no channel and never appears in
# the phone's session list.
$mcpPath = Join-Path $Project '.mcp.json'
$entry = [ordered]@{ command = 'bun'; args = @($bridge) }

if (Test-Path -LiteralPath $mcpPath) {
    $mcp = Get-Content -LiteralPath $mcpPath -Raw | ConvertFrom-Json
    if (-not $mcp.PSObject.Properties.Name.Contains('mcpServers')) {
        $mcp | Add-Member -NotePropertyName mcpServers -NotePropertyValue ([pscustomobject]@{})
    }
    if ($mcp.mcpServers.PSObject.Properties.Name -contains 'claude-walk') {
        Write-Host "channel already registered in $mcpPath" -ForegroundColor DarkGray
    }
    elseif ($PSCmdlet.ShouldProcess($mcpPath, 'add claude-walk channel')) {
        $mcp.mcpServers | Add-Member -NotePropertyName 'claude-walk' -NotePropertyValue ([pscustomobject]$entry)
        # Existing servers are preserved; only the claude-walk key is added.
        $mcp | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $mcpPath -Encoding UTF8
        Write-Host "registered claude-walk in $mcpPath" -ForegroundColor Green
    }
}
elseif ($PSCmdlet.ShouldProcess($mcpPath, 'create with claude-walk channel')) {
    ([pscustomobject]@{ mcpServers = [pscustomobject]@{ 'claude-walk' = [pscustomobject]$entry } }) |
        ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $mcpPath -Encoding UTF8
    Write-Host "created $mcpPath" -ForegroundColor Green
}

# --- Warn about a port already in use ---------------------------------------
$busy = @()
foreach ($p in 8710..8719) {
    if (netstat -ano | Select-String ":$p\s.*LISTENING") { $busy += $p }
}
if ($busy.Count -ge 10) {
    Write-Warning 'All ports 8710-8719 are in use. This session will fail to claim one.'
}
elseif ($busy.Count -gt 0) {
    Write-Host "in use: $($busy -join ', ')  ->  this session takes the next free port" -ForegroundColor DarkGray
}

if ($Port) { $env:CLAUDE_WALK_PORT = "$Port" }
$env:CLAUDE_WALK_PROJECT = Split-Path $Project -Leaf
# Provenance, so the phone can tell a session you opened from one a subagent
# spawned. Anything that reaches the bridge without this stamp was started some
# other way, which is exactly the case worth flagging.
$env:CLAUDE_WALK_LAUNCHED_BY = 'user'

Write-Host ''
Write-Host "Claude Walk: $($env:CLAUDE_WALK_PROJECT)" -ForegroundColor Cyan
Write-Host 'Approve the channel confirmation prompt before you leave.' -ForegroundColor Yellow
# Nothing to paste: the channel server's instructions string is injected into the
# session automatically when the channel loads.
Write-Host ''

if ($RegisterOnly) {
    Write-Host 'Registered only. Launch it later with:' -ForegroundColor DarkGray
    Write-Host '  claude --dangerously-load-development-channels server:claude-walk --dangerously-skip-permissions'
    return
}

$claudeArgs = @()
if ($SessionId) { $claudeArgs += @('--resume', $SessionId) }
elseif ($Continue) { $claudeArgs += '--continue' }
$claudeArgs += @(
    '--dangerously-load-development-channels', 'server:claude-walk',
    '--dangerously-skip-permissions'
)

if ($Continue -or $SessionId) {
    Write-Host 'Carrying on the existing conversation. Exit the old session first, or two' -ForegroundColor DarkGray
    Write-Host 'processes will be attached to the same project.' -ForegroundColor DarkGray
    Write-Host ''
}

if ($PSCmdlet.ShouldProcess($Project, 'launch a voice-enabled Claude Code session')) {
    Set-Location -LiteralPath $Project
    & claude @claudeArgs
}
