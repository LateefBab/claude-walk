#!/usr/bin/env bun
/**
 * End-to-end smoke test for the bridge.
 *
 * Spawns server.ts as a real MCP stdio subprocess, drives it with a real MCP
 * client, and connects a fake phone over WebSocket. Verifies the full speak
 * path, the reconnect-replay contract and the hook endpoints without needing a
 * live Claude Code session.
 *
 *   bun smoke.ts
 */
import { Client } from '@modelcontextprotocol/sdk/client/index.js'
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js'
import { join } from 'node:path'
import { networkInterfaces } from 'node:os'

const PORT = 8719
const TOKEN = 'smoke-token'
const BASE = `http://127.0.0.1:${PORT}`

const results: string[] = []
const check = (cond: boolean, msg: string) => results.push(`${cond ? 'PASS' : 'FAIL'}  ${msg}`)

/** A fake phone. Authenticates, then records every frame it is sent. */
async function phone(): Promise<{ frames: any[]; ws: WebSocket }> {
  const ws = new WebSocket(`ws://127.0.0.1:${PORT}/ws`)
  const frames: any[] = []
  await new Promise<void>((res, rej) => {
    ws.onopen = () => res()
    ws.onerror = e => rej(e)
  })
  ws.onmessage = e => frames.push(JSON.parse(String(e.data)))
  ws.send(JSON.stringify({ type: 'hello', token: TOKEN }))
  await Bun.sleep(300)
  return { frames, ws }
}

/**
 * The token is required. Without it /health returns only {"ok":true}, every field
 * reads as undefined, and comparisons like `undefined === 1` are quietly false,
 * so assertions fail for the wrong reason and wait loops exit immediately.
 */
const health = async () => {
  const body = (await (await fetch(`${BASE}/health?token=${TOKEN}`)).json()) as {
    phones?: number
    unacked?: number
  }
  if (typeof body.unacked !== 'number') {
    throw new Error('health returned no detail: token mismatch, nothing can be asserted')
  }
  return body as { phones: number; unacked: number }
}

const transport = new StdioClientTransport({
  command: 'bun',
  args: [join(import.meta.dir, 'server.ts')],
  env: {
    ...process.env,
    CLAUDE_WALK_PORT: String(PORT),
    CLAUDE_WALK_TOKEN: TOKEN,
    // These tests exercise delivery, acking and replay, not the walking/at-desk
    // mode. Voice is off by default so a session can be launched with the channel
    // without talking; start it on so speak() actually produces something to test.
    CLAUDE_WALK_VOICE_ON_START: 'true',
  },
})

const client = new Client({ name: 'smoke', version: '1.0.0' }, { capabilities: {} })
await client.connect(transport)
await Bun.sleep(500)

try {
  // 1. The channel advertises exactly one tool, and it is speak.
  const tools = await client.listTools()
  check(tools.tools.length === 1 && tools.tools[0]?.name === 'speak', 'exposes a single speak tool')

  // 2. speak with no phone connected must queue rather than vanish.
  const queued = await client.callTool({ name: 'speak', arguments: { text: 'first finding' } })
  const queuedText = (queued.content as any[])[0]?.text ?? ''
  check(queuedText.includes('queued'), `speak with no phone queues (got: ${queuedText})`)
  check((await health()).unacked === 1, 'queued item is tracked as unacked')

  // 3. A phone connecting must receive the missed utterance.
  const p1 = await phone()
  const replayed = p1.frames.find(f => f.type === 'speak' && f.text === 'first finding')
  check(!!replayed, 'missed utterance replayed on connect')

  // 4. Acking it clears the queue, so it is never played twice. The ack must echo
  // the session from the speak frame: ids restart at 1 in every bridge process, so
  // unscoped acks would let one session clear another session's outstanding item.
  p1.ws.send(JSON.stringify({ type: 'ack', id: replayed?.id, session: replayed?.session }))
  await Bun.sleep(300)
  check((await health()).unacked === 0, 'ack clears the unacked queue')

  // 5. speak with a phone connected reports it was heard, and arrives live.
  p1.frames.length = 0
  const live = await client.callTool({ name: 'speak', arguments: { text: 'all 23 tests pass' } })
  await Bun.sleep(300)
  const liveText = (live.content as any[])[0]?.text ?? ''
  check(liveText.includes('spoken'), `speak with a phone reports spoken (got: ${liveText})`)
  check(
    p1.frames.some(f => f.type === 'speak' && f.text === 'all 23 tests pass'),
    'live utterance reaches the phone',
  )

  // 6. Empty speak is rejected rather than sending silence to the earbuds.
  let rejected = false
  try {
    const r = await client.callTool({ name: 'speak', arguments: { text: '   ' } })
    rejected = r.isError === true
  } catch {
    rejected = true
  }
  check(rejected, 'empty speak is rejected')

  // 7. A reconnecting phone must not replay what was already acked.
  p1.ws.close()
  await Bun.sleep(200)
  const p2 = await phone()
  check(
    !p2.frames.some(f => f.type === 'speak' && f.text === 'first finding'),
    'acked utterance is not replayed to a reconnecting phone',
  )

  // 8. Hooks from loopback reach the phone. Assert the effect, not the {}
  // reply: a rejected request answers {} too in a naive guard.
  p2.frames.length = 0
  const hook = (base: string, path: string, body: object, headers: Record<string, string> = {}) =>
    fetch(`${base}${path}`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', ...headers },
      body: JSON.stringify(body),
    })
  const disp = await hook(BASE, '/hook/display', { delta: 'loopback text', final: true })
  await Bun.sleep(200)
  check(disp.status === 200, `loopback /hook/display accepted (got ${disp.status})`)
  check(
    p2.frames.some(f => f.type === 'display' && f.delta === 'loopback text'),
    'loopback display hook reaches the phone',
  )
  // The first stop closes the turn that already spoke (step 5), so it must not
  // cue; the second closes a turn with no speech, so it must.
  p2.frames.length = 0
  const stop1 = await hook(BASE, '/hook/stop', {})
  await Bun.sleep(200)
  check(
    stop1.status === 200 && !p2.frames.some(f => f.type === 'silent_turn'),
    'stop after a spoken turn sends no silent cue',
  )
  p2.frames.length = 0
  const stop2 = await hook(BASE, '/hook/stop', {})
  await Bun.sleep(200)
  check(
    stop2.status === 200 && p2.frames.some(f => f.type === 'silent_turn'),
    'loopback stop hook cues the phone after a silent turn',
  )

  // 9. A web page in the user's browser is also on loopback. It sends Origin,
  // and without a preflight it cannot send application/json. Both are refused.
  p2.frames.length = 0
  const b1 = await hook(BASE, '/hook/display', { delta: 'from a page' }, { origin: 'https://example.com' })
  const b2 = await fetch(`${BASE}/hook/display`, {
    method: 'POST',
    headers: { 'content-type': 'text/plain' },
    body: JSON.stringify({ delta: 'from a page' }),
  })
  await Bun.sleep(200)
  check(
    b1.status === 403 && b2.status === 403 && p2.frames.length === 0,
    `browser-shaped loopback hooks refused and send nothing (got ${b1.status}/${b2.status})`,
  )

  // 10. Hooks from any other address are refused and change nothing, even with
  // a forwarding header claiming loopback. Needs a reachable non-loopback interface.
  const lanIp = Object.values(networkInterfaces())
    .flat()
    .find(i => i && i.family === 'IPv4' && !i.internal)?.address
  const remote = `http://${lanIp}:${PORT}`
  const reachable = lanIp ? await fetch(`${remote}/health`).then(() => true, () => false) : false
  if (!reachable) {
    console.log('SKIP  non-loopback hook rejection: no reachable non-loopback IPv4 address')
  } else {
    p2.frames.length = 0
    const r1 = await hook(remote, '/hook/display', { delta: 'injected', final: true })
    const r2 = await hook(remote, '/hook/display', { delta: 'injected' }, { 'x-forwarded-for': '127.0.0.1' })
    const r3 = await hook(remote, '/hook/stop', {})
    await Bun.sleep(200)
    check(
      r1.status === 403 && r2.status === 403 && r3.status === 403,
      `non-loopback hooks refused, spoofed header included (got ${r1.status}/${r2.status}/${r3.status})`,
    )
    check(p2.frames.length === 0, 'refused hooks send nothing to the phone')
  }
  p2.ws.close()
} finally {
  await client.close()
}

console.log(results.join('\n'))
const failed = results.some(r => r.startsWith('FAIL'))
console.log(failed ? '\nRESULT: FAILURES' : '\nRESULT: ALL PASS')
process.exit(failed ? 1 : 0)
