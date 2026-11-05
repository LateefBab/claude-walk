#!/usr/bin/env bun
/**
 * The pocket-audio risk gate.
 *
 * Starts the bridge, waits for the phone to connect, sends an utterance, and
 * waits for the ack. The ack is the point: the app only sends it from
 * TextToSpeech's onDone callback, so receiving one is objective proof the audio
 * actually played through to the end rather than being throttled or dropped.
 *
 * Run it with the phone in a pocket and the screen off. That is the case the
 * whole native-app decision exists to handle.
 *
 *   bun speak-check.ts
 *   bun speak-check.ts "some other sentence"
 *
 * Over USB, no Tailscale needed:
 *   adb reverse tcp:8710 tcp:8710      then use host 127.0.0.1 in the app
 */
import { Client } from '@modelcontextprotocol/sdk/client/index.js'
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js'
import { existsSync, readFileSync } from 'node:fs'
import { join } from 'node:path'

/**
 * Read the token from the same place the bridge does, never from a literal here.
 *
 * This used to carry the real token as a default argument, which is fine right up
 * until the project is put under version control and the secret goes with it.
 * `.env` is gitignored; source files are not.
 */
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
const PORT = Number(process.env.CLAUDE_WALK_PORT ?? 8710)
const TEXT = process.argv[2] ??
  'Pocket audio check. If you can hear this with the screen off, the hard part works.'

/**
 * The token is required: without it /health returns only {"ok":true} and every
 * field below is undefined. Since `undefined > 0` and `undefined === 0` are both
 * false, an unauthenticated poll makes every wait loop exit immediately and every
 * guard pass, so the harness reports success without measuring anything.
 */
const health = async () => {
  const body = (await (
    await fetch(`http://127.0.0.1:${PORT}/health?token=${encodeURIComponent(TOKEN)}`)
  ).json()) as { phones?: number; unacked?: number }

  if (typeof body.phones !== 'number' || typeof body.unacked !== 'number') {
    throw new Error(
      'health returned no detail: the token does not match the bridge, so nothing can be measured',
    )
  }
  return { phones: body.phones, unacked: body.unacked }
}

const transport = new StdioClientTransport({
  command: 'bun',
  args: [join(import.meta.dir, 'server.ts')],
  env: {
    ...process.env,
    CLAUDE_WALK_PORT: String(PORT),
    CLAUDE_WALK_TOKEN: TOKEN,
    CLAUDE_WALK_PROJECT: 'speak-check',
    // This checks audio actually plays, so it needs voice on. Sessions default to
    // off so they can all be launched with the channel without talking.
    CLAUDE_WALK_VOICE_ON_START: 'true',
  },
})

const client = new Client({ name: 'speak-check', version: '1.0.0' }, { capabilities: {} })
await client.connect(transport)
await Bun.sleep(600)

console.log(`bridge up on :${PORT}`)
console.log(`token: ${TOKEN}`)
console.log('waiting for the phone to connect (Ctrl+C to give up)…')

try {
  // Wait for a phone.
  let waited = 0
  while ((await health()).phones === 0) {
    await Bun.sleep(1000)
    waited++
    if (waited % 10 === 0) console.log(`  still waiting (${waited}s)`)
    if (waited > 180) {
      console.log('\nFAIL: no phone connected after 3 minutes.')
      console.log('Check: app settings host/port/token, adb reverse, or the Windows Firewall rule for 8710.')
      process.exit(1)
    }
  }
  console.log('phone connected')

  // Require the connection to hold, not just to have existed once. A phone that
  // connects and immediately drops used to satisfy the wait above and then get
  // reported as a pass, which is worse than no test at all.
  console.log('confirming the connection is stable…')
  for (let i = 0; i < 6; i++) {
    await Bun.sleep(500)
    if ((await health()).phones === 0) {
      console.log('\nFAIL: the phone connected and then dropped. Nothing was spoken.')
      console.log('Check logcat: adb logcat | grep BridgeClient')
      process.exit(1)
    }
  }

  console.log(`\nspeaking: "${TEXT}"`)
  const res = await client.callTool({ name: 'speak', arguments: { text: TEXT } })
  const said = (res.content as Array<{ text?: string }>)[0]?.text ?? ''
  console.log(`bridge says: ${said}`)

  if (said.includes('queued')) {
    console.log('\nFAIL: the bridge had no listener at the moment of speaking.')
    process.exit(1)
  }

  // Prove the utterance was actually registered as outstanding before waiting for
  // it to clear. Without this, an ack counter that never incremented reads as an
  // instant success.
  if ((await health()).unacked === 0) {
    console.log('\nFAIL: the utterance never registered as outstanding, so a cleared')
    console.log('queue proves nothing. This is a bridge or harness fault, not playback.')
    process.exit(1)
  }

  console.log('waiting for playback ack…')
  const startedAt = Bun.nanoseconds()
  let elapsed = 0
  while ((await health()).unacked > 0) {
    await Bun.sleep(250)
    elapsed = (Bun.nanoseconds() - startedAt) / 1e6
    if (elapsed > 60_000) {
      console.log('\nFAIL: no ack after 60s. The frame arrived but playback did not complete.')
      console.log('Check logcat: adb logcat -s AndroidTtsProvider:* WalkService:*')
      process.exit(1)
    }
  }

  const seconds = elapsed / 1000
  console.log(`\nPASS: acked after ${seconds.toFixed(1)}s. The phone played it to completion.`)
  // Speech runs at roughly 12-15 characters per second, so an ack far faster than
  // the text could possibly take to say means something acked without playing.
  const plausible = TEXT.length / 25
  if (seconds < plausible) {
    console.log(
      `WARNING: ${TEXT.length} characters should take at least ~${plausible.toFixed(1)}s to say. ` +
        'Treat this pass as suspect and check that audio was really audible.',
    )
  }
} finally {
  await client.close()
}
