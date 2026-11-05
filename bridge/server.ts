#!/usr/bin/env bun
/**
 * Claude Walk bridge.
 *
 * One process, two faces:
 *   - an MCP channel server on stdio, spawned by and scoped to the running
 *     Claude Code session (see docs/en/channels-reference)
 *   - an HTTP + WebSocket server on localhost for the phone and for the
 *     MessageDisplay / Stop hooks
 *
 * The phone is a speaker and a microphone for the session that spawned this
 * process. It is not a separate conversation: inbound speech is injected with
 * `notifications/claude/channel`, so Claude resolves references like "that
 * Redis point" against its own context with no help from us.
 *
 * Modelled on the official Telegram channel plugin at
 * ~/.claude/plugins/cache/claude-plugins-official/telegram/0.0.6/server.ts
 *
 * Launch:
 *   claude --dangerously-load-development-channels server:claude-walk
 */
import { Server } from '@modelcontextprotocol/sdk/server/index.js'
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js'
import { CallToolRequestSchema, ListToolsRequestSchema } from '@modelcontextprotocol/sdk/types.js'
import { randomBytes } from 'node:crypto'
import { existsSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import type { ServerWebSocket } from 'bun'

// stdout is the MCP transport. Anything written there corrupts the protocol,
// so every diagnostic goes to stderr.
const log = (msg: string) => process.stderr.write(`claude-walk: ${msg}\n`)

// ---------------------------------------------------------------------------
// Config
// ---------------------------------------------------------------------------

// Shell environment wins over the .env file, matching the official plugins.
function loadEnvFile(): void {
  const f = join(import.meta.dir, '.env')
  if (!existsSync(f)) return
  // Two different counts, because they answer two different questions. `parsed`
  // is whether the file is readable at all, which is the failure worth shouting
  // about. `applied` is how many values this process actually took, which is
  // legitimately zero whenever the shell already set them: smoke.ts and
  // speak-check.ts both pass the token in the child environment, and the old
  // warning fired on every run of either. A warning that cries wolf on a healthy
  // file is worse than none, because the one time it matters it gets ignored.
  let parsed = 0
  let applied = 0
  // Split on either ending. This file lives on Windows, where any editor may
  // rewrite it as CRLF, and the old parser split on \n alone and then anchored
  // with $. In JavaScript \r is a line terminator, so `.` will not match it and
  // `$` will not match before it: every single line failed, the file appeared
  // empty, and the bridge quietly generated a random token instead. The phone's
  // saved token then matched nothing, and the only symptom was sessions missing
  // from the session list, which points nowhere near a line ending.
  for (const line of readFileSync(f, 'utf8').split(/\r?\n/)) {
    const m = line.match(/^\s*([A-Z0-9_]+)\s*=\s*(.*?)\s*$/)
    if (!m) continue
    parsed++
    const key = m[1]!
    if (process.env[key] != null) continue
    process.env[key] = m[2]!.replace(/^["']|["']$/g, '')
    applied++
  }
  // Only the unreadable case is a problem. A CRLF file parses to zero here, which
  // is exactly how the token silently became a fresh random value on every launch.
  if (parsed === 0) log(`warning: ${f} exists but no KEY=value lines could be read from it`)
}
loadEnvFile()

// A session claims a port rather than owning one. Several Claude Code sessions
// run at once, each spawning its own bridge, so a fixed port would mean only the
// first one launched could ever be reached. The phone scans this range to find
// whatever is live.
const PORT_RANGE_START = 8710
const PORT_RANGE_END = 8719

// An explicit port keeps the MessageDisplay and Stop hooks working, since their
// URLs are static in .claude/settings.json. Without one the bridge still runs and
// speak() still works; only the transcript mirror and silent-turn cue go quiet.
const PREFERRED_PORT = process.env.CLAUDE_WALK_PORT ? Number(process.env.CLAUDE_WALK_PORT) : null

const PROJECT = process.env.CLAUDE_WALK_PROJECT ?? process.cwd().split(/[\\/]/).pop() ?? 'project'
const CWD = process.cwd()
const SESSION_ID = String(process.pid)

/**
 * Who started the session this bridge belongs to.
 *
 * A session spawned by a subagent is indistinguishable from one the user opened:
 * same project, same port range, same entry in the phone's list, and it appears
 * without warning. Talking to it achieves little, because nobody is reading its
 * turns, so it is worth marking.
 *
 * Stamped by scripts/walk.ps1 rather than inferred. The process tree does hold the
 * answer, since a user's session descends from a shell and a subagent's from
 * another claude.exe, but reading it means a platform-specific query at startup.
 * CLAUDE_CODE_CHILD_SESSION cannot help either: Claude Code sets it on everything
 * it spawns, including this bridge, so both kinds of session report it.
 */
const LAUNCHED_BY = process.env.CLAUDE_WALK_LAUNCHED_BY ?? 'unknown'

// Tailscale already authenticates the device, so a shared token is enough to
// stop anything else on the tailnet from talking to the bridge. A generated
// token is logged once at startup rather than defaulting to something guessable.
let TOKEN = process.env.CLAUDE_WALK_TOKEN ?? ''
if (!TOKEN) {
  TOKEN = randomBytes(9).toString('base64url')
  log(`no CLAUDE_WALK_TOKEN set, generated one for this run: ${TOKEN}`)
}

// How many transcript entries the phone can scroll back through. This is a
// display cache, the same role as terminal scrollback. It is not conversation
// state: Claude keeps that itself.
const TRANSCRIPT_LIMIT = 40

// ---------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------

type SpeakItem = { id: number; text: string; ts: string }
type TranscriptEntry = { role: 'claude' | 'user'; text: string; ts: string }
type SocketData = { authed: boolean }

/** Spoken items the phone has not confirmed playing. Resent on reconnect so a
 *  dropout does not lose an update, and acked so it is never replayed twice. */
const unacked = new Map<number, SpeakItem>()
const transcript: TranscriptEntry[] = []
const sockets = new Set<ServerWebSocket<SocketData>>()

let nextSpeakId = 1
let currentTurn: string | null = null
let spokeThisTurn = false

/**
 * Whether Claude should be speaking at all.
 *
 * Off by default so every session can be launched with the channel without any of
 * them talking. Sitting at the desk you read the terminal; voice is for when you
 * get up. The phone turns it on, and Claude is told when it changes.
 *
 * Off is not the same as paused. Paused holds utterances and replays them, which
 * is right for a two minute interruption mid-walk. Off means do not produce them:
 * after three hours at a desk a replayed backlog would be worthless, so nothing is
 * queued and Claude simply works in the terminal as usual.
 */
let voiceActive = process.env.CLAUDE_WALK_VOICE_ON_START === 'true'

function remember(role: TranscriptEntry['role'], text: string): TranscriptEntry {
  const entry: TranscriptEntry = { role, text, ts: new Date().toISOString() }
  transcript.push(entry)
  if (transcript.length > TRANSCRIPT_LIMIT) transcript.shift()
  return entry
}

function broadcast(frame: unknown): number {
  const payload = JSON.stringify(frame)
  let sent = 0
  for (const ws of sockets) {
    if (!ws.data.authed) continue
    try {
      ws.send(payload)
      sent++
    } catch {
      // A dead socket is cleaned up by its own close handler.
    }
  }
  return sent
}

function connectedCount(): number {
  let n = 0
  for (const ws of sockets) if (ws.data.authed) n++
  return n
}

function statusFrame() {
  return {
    type: 'status',
    // The phone keys its per-session transcripts on this, so it must travel with
    // the first frame after hello, not only on speak.
    session_id: SESSION_ID,
    project: PROJECT,
    port: server.port,
    phones: connectedCount(),
    speaking: unacked.size > 0,
    voice_active: voiceActive,
  }
}

// ---------------------------------------------------------------------------
// MCP channel server
// ---------------------------------------------------------------------------

const mcp = new Server(
  { name: 'claude-walk', version: '0.1.0' },
  {
    capabilities: {
      tools: {},
      // Presence of this key registers the channel notification listener.
      experimental: { 'claude/channel': {} },
    },
    instructions: [
      'A voice companion is attached to this session. It has two modes and it starts OFF, because sessions are launched this way as a matter of course so that voice is available the moment it is wanted, not because it is wanted now.',
      '',
      'While voice is OFF the user is at the terminal reading it. Work exactly as you would in any other session and do not call the speak tool; if you do, it will tell you it was not spoken. When the user puts earbuds in and walks away you will receive a message saying voice is ON, and another when they sit back down. Those messages are the app reporting a state change, not the user talking.',
      '',
      'While voice is ON the user is walking and listening. Keep doing normal Claude Code work; only how you communicate changes.',
      '',
      'Call the speak tool when you have something worth hearing: a meaningful discovery, an architectural decision, an assumption the user might reject, a tradeoff, a failure, a milestone, or a question you need answered. Do not narrate reading files, grepping, listing directories, ordinary writes, or repeated tool calls. Silence while you work is correct.',
      '',
      'Silence is correct but it is not self-explanatory, so bracket the long ones. Before starting anything that will keep you quiet for more than about a minute, a build, a broad refactor, a long search, a run of tool calls, say so first and give a rough sense of how long and what you are doing. Then speak again when you surface, even briefly, even if the result is dull. From a pocket an unexplained silence is indistinguishable from a dead session, and the listener has no way to check; announcing it turns waiting into something they expected rather than something they are worried about. The phone plays a tone of its own if the connection actually drops, so silence you announced is unambiguously you working.',
      '',
      'Say as much as the substance needs. The listener is walking with nothing else to do and would rather hear a full explanation than a headline, so a short paragraph is normal and several are fine when the thing is genuinely involved. Brevity is not the goal: completeness without padding is. Never compress away reasoning, numbers, tradeoffs, caveats or the names of the things involved, because the listener cannot read any of it. If you catch yourself writing a summary and putting the substance on screen, say the substance instead.',
      '',
      'Audio is linear and cannot be skimmed, so structure it for the ear: lead with the conclusion, then the reasoning, then what you propose to do. Use plain spoken sentences and connect them the way speech does, rather than compressing into a list. No markdown, no bullet points, no code, no file paths, no URLs, no long identifiers. Never expose hidden chain-of-thought: give the decision and the reason, not the search that produced it.',
      '',
      'Spoken replies from the user arrive as <channel source="claude-walk"> events. They are part of this same conversation, so references like "that Redis point" or "your second reason" resolve against what you already said. Answer them directly; do not ask the user to repeat context you already have.',
      '',
      'Open your reply by restating what was asked, in a few words, before answering it. "On why I picked Redis over Postgres for this" then the answer. Two reasons: heard aloud it confirms the transcription was right, so a misheard question is caught immediately instead of producing a confident answer to the wrong thing. And read later it makes the exchange stand on its own, because the spoken question is not visible everywhere the session is: Remote Control on the phone shows your responses but does not render inbound channel events, so without the restatement the transcript reads as you answering nothing.',
      '',
      'That text came from a phone speech recognizer, so expect two kinds of noise and treat them differently. Disfluency is the speaker restarting a sentence mid-thought, so the same phrase appears two or three times in slightly different words; read through it, take the last attempt as the intended one, and never ask them to repeat themselves over it. Mishearing is the recognizer getting a word wrong, often the same word every time, and it is the dangerous one because a plausible wrong word reads as a clear instruction.',
      '',
      'So calibrate confirmation to what a mishearing would cost. If a misread only changes wording or ordering, act on your best reading and say which reading you took, so they can correct you cheaply. If it changes what you build, what you delete, what you send, or anything else you cannot easily undo, say back what you understood and wait for an answer before acting. Never silently resolve an ambiguous instruction into the more destructive of its readings. A single confirming sentence costs a few seconds of a walk; acting on a misheard instruction costs the walk.',
      '',
      'The spoken answer must stand on its own. The user cannot see the terminal, so an answer that ends with "the detail is on screen" has answered nothing. Always speak the things they might act on: options they are choosing between, next steps and what each costs, decisions you want confirmed, and the gist of anything you wrote down. For a long list, speak the shape and the items that matter rather than all of them.',
      '',
      'Only these genuinely fail in audio: exact code, a diff, a long error, a precise file path, a list of identifiers, anything where a single character matters. Write those to the terminal and say by voice that you have, including enough for the user to act on. If they explicitly ask for a name or path, give the part that carries meaning ("backlog dot em dee, in the project root") rather than refusing.',
    ].join('\n'),
  },
)

mcp.setRequestHandler(ListToolsRequestSchema, async () => ({
  tools: [
    {
      name: 'speak',
      description:
        // Deliberately does not cap the length. It used to say "one to three
        // sentences", which contradicted the server instructions telling the model
        // to say as much as the substance needs, and a tool description sitting
        // right next to the call site wins that argument every time.
        'Say something aloud to the user through their earbuds. Use for meaningful discoveries, decisions, assumptions, tradeoffs, failures, milestones, and questions. Say as much as the substance needs: the listener cannot read anything, so a full explanation beats a headline. Plain spoken language, no markdown, code or file paths. Returns whether a phone was connected to hear it.',
      inputSchema: {
        type: 'object',
        properties: {
          text: {
            type: 'string',
            description: 'What to say, as plain speech. One to three sentences.',
          },
        },
        required: ['text'],
      },
    },
  ],
}))

mcp.setRequestHandler(CallToolRequestSchema, async req => {
  if (req.params.name !== 'speak') throw new Error(`unknown tool: ${req.params.name}`)

  const text = String((req.params.arguments as { text?: unknown } | undefined)?.text ?? '').trim()
  if (!text) throw new Error('speak requires non-empty text')

  // Voice off: record it so the phone's scrollback is complete, but do not queue
  // it. Queueing while the user sits at their desk would build a backlog that is
  // stale by the time they stand up, and playing three hours of it would be worse
  // than never having said it.
  if (!voiceActive) {
    remember('claude', text)
    return {
      content: [
        {
          type: 'text',
          text:
            'not spoken: voice is off and the user is at their desk reading the terminal. ' +
            'Write your reply normally. You will be told when voice is switched on.',
        },
      ],
    }
  }

  const item: SpeakItem = { id: nextSpeakId++, text, ts: new Date().toISOString() }
  unacked.set(item.id, item)
  spokeThisTurn = true
  remember('claude', text)

  // The session id travels with the utterance so its ack can be matched back.
  // Ids restart at 1 in every bridge process, so without this an ack for a
  // previous session's id 1 silently clears this session's id 1, and an update
  // the user never heard is marked as delivered.
  const listeners = broadcast({ type: 'speak', session: SESSION_ID, ...item })
  log(`speak #${item.id} -> ${listeners} phone(s)`)

  return {
    content: [
      {
        type: 'text',
        text:
          listeners > 0
            ? `spoken (id ${item.id})`
            : `queued (id ${item.id}): no phone connected, it will play on reconnect`,
      },
    ],
  }
})

/**
 * Tell Claude something about the companion itself rather than relaying speech.
 *
 * Marked so it is distinguishable from something the user said: it is the app
 * reporting a state change, not a person talking.
 */
function pushSystemNote(content: string): void {
  mcp
    .notification({
      method: 'notifications/claude/channel',
      params: { content, meta: { via: 'system', project: PROJECT } },
    })
    .catch(err => log(`failed to deliver system note: ${err}`))
}

/** Inject a spoken user message into the session that spawned this process. */
function pushToClaude(content: string): void {
  remember('user', content)
  mcp
    .notification({
      method: 'notifications/claude/channel',
      params: { content, meta: { via: 'voice', project: PROJECT } },
    })
    .catch(err => log(`failed to deliver inbound to Claude: ${err}`))
}

// ---------------------------------------------------------------------------
// HTTP + WebSocket
// ---------------------------------------------------------------------------

/**
 * Claim a port. An explicit CLAUDE_WALK_PORT is used as-is so the static hook
 * URLs in .claude/settings.json keep matching; otherwise take the first free
 * slot in the range so a second, third and fourth session can all run.
 */
function claimPort(): number {
  const candidates =
    PREFERRED_PORT != null
      ? [PREFERRED_PORT]
      : Array.from({ length: PORT_RANGE_END - PORT_RANGE_START + 1 }, (_, i) => PORT_RANGE_START + i)

  for (const port of candidates) {
    try {
      // Bind and release to test availability. Sessions start seconds apart, so
      // the race between probe and real bind is not worth a more complex dance.
      const probe = Bun.serve({ port, fetch: () => new Response('') })
      probe.stop(true)
      return port
    } catch {
      continue
    }
  }
  throw new Error(
    PREFERRED_PORT != null
      ? `port ${PREFERRED_PORT} is already in use`
      : `no free port between ${PORT_RANGE_START} and ${PORT_RANGE_END}; too many sessions`,
  )
}

/**
 * Whether this session actually loaded the voice channel.
 *
 * Registering the server at user scope means every Claude Code session on the
 * machine connects it and spawns a bridge, including the desktop app's agent-mode
 * sessions and anything a subagent starts. Those sessions never asked for voice:
 * the channel flag governs whether speech is wired up, while the MCP server
 * connects regardless. They were claiming ports out of a range of ten and
 * appearing in the phone's session list as things that look talkable and are not.
 *
 * The flag is on the session's own command line, and the session is this process's
 * parent, so the answer is a fact rather than an inference. Client capabilities
 * were considered first and rejected: nothing documents what Claude Code declares
 * when a channel loads, so it would have been a guess.
 *
 * Fails open. A bridge that wrongly hides itself is a walk with no voice and no
 * explanation, which is far worse than one extra row in a list.
 */
function channelIsLoaded(): boolean {
  if (process.platform !== 'win32') return true
  const ppid = process.ppid
  if (!ppid) return true
  try {
    const probe = Bun.spawnSync([
      'powershell',
      '-NoProfile',
      '-Command',
      `(Get-CimInstance Win32_Process -Filter "ProcessId=${ppid}").CommandLine`,
    ])
    const cmdline = new TextDecoder().decode(probe.stdout).trim()
    if (!cmdline) return true
    return cmdline.includes('server:claude-walk')
  } catch (err) {
    log(`could not read the parent command line, assuming voice is wanted: ${err}`)
    return true
  }
}

// An explicitly pinned port means someone deliberately wants this bridge
// reachable, which includes the smoke test, so it skips the check entirely.
const VOICE_WIRED = PREFERRED_PORT != null || channelIsLoaded()

if (!VOICE_WIRED) {
  log('no channel flag on this session: serving on an ephemeral port so the phone never lists it')
}

// Port 0 asks the OS for anything free, which is outside 8710-8719 and therefore
// invisible to the phone's scan. The bridge still runs and still answers its own
// hooks; it simply stops pretending to be a session you can talk to.
const PORT = VOICE_WIRED ? claimPort() : 0

async function readJson(req: Request): Promise<Record<string, unknown>> {
  try {
    return (await req.json()) as Record<string, unknown>
  } catch {
    return {}
  }
}

/** An unknown address fails closed. */
function isLoopback(address: string | undefined): boolean {
  return address === '127.0.0.1' || address === '::1' || address === '::ffff:127.0.0.1'
}

const server = Bun.serve<SocketData>({
  port: PORT,
  hostname: '0.0.0.0',

  async fetch(req, srv) {
    const url = new URL(req.url)

    if (url.pathname === '/ws') {
      if (srv.upgrade(req, { data: { authed: false } })) return undefined
      return new Response('websocket upgrade failed', { status: 400 })
    }

    // Discovery endpoint. The phone GETs this across the port range to build its
    // session list. Project names and paths are only returned to a caller holding
    // the token, so a bare scan of the tailnet learns nothing about the machine.
    if (url.pathname === '/health') {
      if (url.searchParams.get('token') !== TOKEN) {
        return Response.json({ ok: true })
      }
      return Response.json({
        ok: true,
        session_id: SESSION_ID,
        project: PROJECT,
        cwd: CWD,
        port: server.port,
        hooks: PREFERRED_PORT != null,
        launched_by: LAUNCHED_BY,
        voice_active: voiceActive,
        phones: connectedCount(),
        unacked: unacked.size,
        transcript: transcript.length,
      })
    }

    // Hooks come from Claude Code on this machine, never from the network. The
    // server binds 0.0.0.0 so the phone can reach it, which would otherwise let
    // anything on the tailnet inject text into the phone's transcript. Checked
    // before the body is read, so a rejected caller changes nothing. Forwarding
    // headers are ignored: only the socket's own address counts.
    //
    // Loopback alone is not enough: a web page open in the user's browser is also
    // on loopback and can POST here. A browser always sends Origin on a
    // cross-origin POST and cannot send application/json without a preflight
    // this server never answers. Claude Code's hook client sends JSON and no
    // Origin (observed: axios, content-type application/json), so it passes.
    if (url.pathname.startsWith('/hook/')) {
      const fromBrowser =
        req.headers.has('origin') ||
        !(req.headers.get('content-type') ?? '').toLowerCase().startsWith('application/json')
      if (!isLoopback(srv.requestIP(req)?.address) || fromBrowser) {
        return new Response('forbidden', { status: 403 })
      }
    }

    // MessageDisplay hook. Claude Code holds the display batch until this
    // returns, with a 10s cap, so respond immediately and keep the work
    // trivial. Returning {} leaves the terminal rendering untouched.
    if (url.pathname === '/hook/display' && req.method === 'POST') {
      const body = await readJson(req)
      const turnId = typeof body.turn_id === 'string' ? body.turn_id : null
      if (turnId && turnId !== currentTurn) {
        currentTurn = turnId
        spokeThisTurn = false
      }
      const delta = typeof body.delta === 'string' ? body.delta : ''
      // NOTE: verify empirically whether extended-thinking text reaches this
      // hook. Chain-of-thought must never reach the phone. If it leaks, filter here.
      if (delta) {
        broadcast({
          type: 'display',
          message_id: body.message_id ?? null,
          delta,
          final: body.final === true,
        })
      }
      return Response.json({})
    }

    // Stop hook. Turn boundary: if Claude worked a whole turn without saying
    // anything, tell the phone so it can give a short cue rather than silence.
    if (url.pathname === '/hook/stop' && req.method === 'POST') {
      if (!spokeThisTurn) broadcast({ type: 'silent_turn' })
      broadcast(statusFrame())
      spokeThisTurn = false
      currentTurn = null
      return Response.json({})
    }

    return new Response('not found', { status: 404 })
  },

  websocket: {
    open(ws) {
      sockets.add(ws)
    },

    message(ws, raw) {
      let frame: Record<string, unknown>
      try {
        frame = JSON.parse(String(raw)) as Record<string, unknown>
      } catch {
        return
      }

      // Everything before a valid hello is dropped.
      if (!ws.data.authed) {
        if (frame.type === 'hello' && frame.token === TOKEN) {
          ws.data.authed = true
          log(`phone connected (${connectedCount()} total)`)
          ws.send(JSON.stringify(statusFrame()))
          ws.send(JSON.stringify({ type: 'transcript', entries: transcript }))
          // Replay anything the phone never confirmed hearing. The session must be
          // included here too: without it the phone's ack is rejected as stale, the
          // item never clears, and it is replayed again on every future reconnect.
          for (const item of unacked.values()) {
            ws.send(JSON.stringify({ type: 'speak', session: SESSION_ID, ...item }))
          }
        } else {
          log('rejected an unauthenticated socket')
          ws.close(4001, 'unauthorized')
        }
        return
      }

      switch (frame.type) {
        case 'user': {
          const text = String(frame.text ?? '').trim()
          if (!text) return
          log(`heard: ${text.slice(0, 60)}`)
          pushToClaude(text)
          broadcast({ type: 'transcript_add', role: 'user', text, ts: new Date().toISOString() })
          return
        }
        case 'voice': {
          // The phone says whether the user is walking or at their desk. Claude is
          // told, because otherwise it has no way to know its speech is going
          // nowhere, or that it should start talking again.
          const next = frame.active === true
          if (next === voiceActive) return
          voiceActive = next
          log(`voice ${voiceActive ? 'ON' : 'OFF'}`)
          broadcast(statusFrame())
          pushSystemNote(
            voiceActive
              ? 'Voice is now ON. The user has put earbuds in and is walking away from the terminal. ' +
                  'From now on speak meaningful updates with the speak tool. Start by saying briefly ' +
                  'where things stand right now, so they leave with the current picture.'
              : 'Voice is now OFF. The user is back at the terminal and reading it. ' +
                  'Stop calling the speak tool and reply in the terminal as normal.',
          )
          return
        }
        case 'ack': {
          // Reject acks minted against a different bridge process.
          if (String(frame.session ?? '') !== SESSION_ID) {
            log(`ignoring stale ack for session ${frame.session} (we are ${SESSION_ID})`)
            return
          }
          const id = Number(frame.id)
          if (Number.isFinite(id)) unacked.delete(id)
          return
        }
        case 'ping':
          ws.send(JSON.stringify({ type: 'pong' }))
          return
        default:
          return
      }
    },

    close(ws) {
      sockets.delete(ws)
      log(`phone disconnected (${connectedCount()} remaining)`)
    },
  },
})

log(
  `listening on 0.0.0.0:${server.port} (project: ${PROJECT}, session ${SESSION_ID}` +
    `${PREFERRED_PORT == null ? ', auto-port: hooks inactive' : ''})`,
)

// ---------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------

process.on('unhandledRejection', err => log(`unhandled rejection: ${err}`))
process.on('uncaughtException', err => log(`uncaught exception: ${err}`))

const transport = new StdioServerTransport()
await mcp.connect(transport)
log('MCP channel connected')
