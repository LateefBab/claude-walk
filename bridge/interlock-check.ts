#!/usr/bin/env bun
/**
 * The microphone interlock gate, driven deliberately.
 *
 * The invariant under test: the microphone must never be open while Claude's
 * audio is audible. `speak-check.ts` proves audio plays; this proves nothing is
 * listening while it does.
 *
 * A normal exchange only exercises the easy half of that, because the reopen
 * attempt lands while TextToSpeech still reports SPEAKING and is refused on those
 * grounds alone. The hard half is the window *after* the engine says it has
 * finished, while sound is still leaving the speaker: `speech.status()` reads
 * IDLE there, so only the drain condition stands between the microphone and
 * Claude's own tail.
 *
 * Hitting that window by hand is luck. This hits it on purpose: POSTing to
 * /hook/stop makes the bridge broadcast a status frame, and the phone answers a
 * status frame by calling resumeContinuousListening() directly. That is the exact
 * call path that used to bypass the drain, so hammering it across the end of an
 * utterance guarantees several attempts land inside the window.
 *
 *   bun interlock-check.ts
 *
 * Then read the verdict from the phone, where the evidence actually lives:
 *   adb logcat -d | grep -E "MIC OPEN|MIC CLOSED|stays shut|INTERLOCK VIOLATION"
 */
import { Client } from '@modelcontextprotocol/sdk/client/index.js'
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js'
import { existsSync, readFileSync } from 'node:fs'
import { join } from 'node:path'

function tokenFromEnvFile(): string {
  const f = join(import.meta.dir, '.env')
  if (!existsSync(f)) return ''
  for (const line of readFileSync(f, 'utf8').split(/\r?\n/)) {
    const m = line.match(/^\s*CLAUDE_WALK_TOKEN\s*=\s*(.*?)\s*$/)
    if (m) return m[1]!.replace(/^["']|["']$/g, '')
  }
  return ''
}

const TOKEN = process.env.CLAUDE_WALK_TOKEN ?? tokenFromEnvFile()
if (!TOKEN) {
  console.error('No CLAUDE_WALK_TOKEN in the environment or in bridge/.env')
  process.exit(1)
}

const PORT = Number(process.env.CLAUDE_WALK_PORT ?? 8716)
const BASE = `http://127.0.0.1:${PORT}`

const health = async () => {
  const body = (await (await fetch(`${BASE}/health?token=${encodeURIComponent(TOKEN)}`)).json()) as {
    phones?: number
    unacked?: number
  }
  if (typeof body.phones !== 'number' || typeof body.unacked !== 'number') {
    throw new Error('health returned no detail: the token does not match the bridge')
  }
  return { phones: body.phones, unacked: body.unacked }
}

/** Provokes a direct resumeContinuousListening() on the phone. */
const provokeReopen = () =>
  fetch(`${BASE}/hook/stop`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: '{}',
  }).catch(() => {})

const transport = new StdioClientTransport({
  command: 'bun',
  args: [join(import.meta.dir, 'server.ts')],
  env: {
    ...process.env,
    CLAUDE_WALK_PORT: String(PORT),
    CLAUDE_WALK_TOKEN: TOKEN,
    CLAUDE_WALK_PROJECT: 'INTERLOCK TEST',
    CLAUDE_WALK_VOICE_ON_START: 'true',
  },
})

const client = new Client({ name: 'interlock-check', version: '1.0.0' }, { capabilities: {} })
await client.connect(transport)
await Bun.sleep(600)

console.log(`bridge up on :${PORT}`)
console.log('waiting for the phone (it should reconnect on its own if it was last on this port)…')

let waited = 0
while ((await health()).phones === 0) {
  await Bun.sleep(1000)
  if (++waited % 10 === 0) console.log(`  still waiting (${waited}s)`)
  if (waited > 180) {
    console.log('\nFAIL: no phone connected after 3 minutes.')
    process.exit(1)
  }
}
console.log('phone connected\n')

const TEXT =
  'Testing the drain window now. Stay quiet while this plays, because anything you say will muddy the result.'

console.log('TEST A — reopen attempts across the end of an utterance')
console.log(`speaking: "${TEXT}"`)

// Hammer the direct-reopen path continuously, from before playback until well
// after it. Attempts during speech should be refused for speaking; attempts in
// the ~700ms after should be refused for draining. Both refusals are the point.
let provocations = 0
const hammer = setInterval(() => {
  provocations++
  void provokeReopen()
}, 120)

const res = await client.callTool({ name: 'speak', arguments: { text: TEXT } })
const said = (res.content as Array<{ text?: string }>)[0]?.text ?? ''
console.log(`bridge says: ${said}`)
if (said.includes('queued')) {
  clearInterval(hammer)
  console.log('\nFAIL: no listener at the moment of speaking.')
  process.exit(1)
}

const startedAt = Bun.nanoseconds()
while ((await health()).unacked > 0) {
  await Bun.sleep(200)
  if ((Bun.nanoseconds() - startedAt) / 1e6 > 60_000) {
    clearInterval(hammer)
    console.log('\nFAIL: no ack after 60s.')
    process.exit(1)
  }
}
const playedFor = (Bun.nanoseconds() - startedAt) / 1e6

// Keep provoking well past the ack, so the drain window is covered from both
// sides rather than only up to the moment the engine reported done.
console.log(`acked after ${(playedFor / 1000).toFixed(1)}s — still provoking through the drain…`)
await Bun.sleep(3000)
clearInterval(hammer)

console.log(`\ndone: ${provocations} reopen attempts provoked across the utterance and its drain.`)
console.log('The phone holds the verdict. On the laptop:')
console.log('  adb logcat -d | grep -E "MIC OPEN|stays shut|INTERLOCK VIOLATION"')
console.log('')
console.log('Expected: many "stays shut: Claude is speaking", at least one')
console.log('"stays shut: audio still draining", no INTERLOCK VIOLATION, and exactly')
console.log('one MIC OPEN at the end.')
console.log('')
console.log('Leaving the bridge up for TEST B (pause and resume). Ctrl+C when finished.')

// Hold the bridge open so the pause/resume path can be driven by hand.
await new Promise(() => {})
