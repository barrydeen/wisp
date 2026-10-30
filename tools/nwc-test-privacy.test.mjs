// Run with: node --experimental-vm-modules --test tools/nwc-test-privacy.test.mjs
// Execute the diagnostic itself with synthetic crypto adapters and a fake relay.
// This checks logging, not Noble compatibility or NWC interoperability.
import assert from 'node:assert/strict';
import { createHash, randomBytes, webcrypto } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import { createContext, SourceTextModule, SyntheticModule } from 'node:vm';

const source = await readFile(new URL('./nwc-test.mjs', import.meta.url), 'utf8');
const secret = '11'.repeat(32);
const walletPubkey = '22'.repeat(32);
const clientPubkey = Buffer.from('02' + '33'.repeat(32), 'hex');
const sharedPoint = Buffer.from('02' + '44'.repeat(32), 'hex');
const rawKey = sharedPoint.subarray(1);
const digest = (value) => new Uint8Array(createHash('sha256').update(value).digest());
const relayCredential = 'synthetic-relay-token';
const relay = `wss://relay.invalid/?token=${relayCredential}`;
const uri = `nostr+walletconnect://${walletPubkey}?relay=${encodeURIComponent(relay)}&secret=${secret}`;
const forbidden = [
  secret, uri, relay, relayCredential,
  sharedPoint.toString('hex'), rawKey.toString('hex'),
  Buffer.from(digest(sharedPoint)).toString('hex'),
];

async function encryptResponse(plaintext) {
  const iv = new Uint8Array(16);
  const key = await webcrypto.subtle.importKey('raw', rawKey, 'AES-CBC', false, ['encrypt']);
  const ciphertext = await webcrypto.subtle.encrypt(
    { name: 'AES-CBC', iv }, key, new TextEncoder().encode(plaintext)
  );
  return Buffer.from(ciphertext).toString('base64') + '?iv=' + Buffer.from(iv).toString('base64');
}

async function runScenario({ env = {}, uriValue = uri, startupFailure = false, response = 'valid', frames = [], socketError = false, timeout = false } = {}) {
  const output = [];
  const exits = [];
  let socket;
  let signalSocket;
  let signalExit;
  let timeoutCallback;
  const socketReady = new Promise((resolve) => { signalSocket = resolve; });
  const exited = new Promise((resolve) => { signalExit = resolve; });

  class FakeWebSocket {
    handlers = new Map();
    sent = [];
    constructor(url) {
      assert.equal(url, relay);
      socket = this;
      signalSocket();
    }
    on(event, handler) { this.handlers.set(event, handler); }
    send(value) { this.sent.push(JSON.parse(value)); }
    close() { this.handlers.get('close')?.(); }
    async emit(event, value) { await this.handlers.get(event)?.(value); }
  }

  const context = createContext({
    Buffer, URL, TextEncoder, TextDecoder, crypto: webcrypto,
    console: Object.fromEntries(['log', 'error', 'warn'].map((name) => [
      name, (...args) => { output.push(args.map(String).join(' ')); },
    ])),
    process: {
      env: { ...env, NWC_URI: uriValue },
      exit: (code) => { exits.push(code); signalExit(); },
    },
    setTimeout: (callback) => { timeoutCallback = callback; },
  });
  const dependencies = {
    '@noble/secp256k1': {
      getPublicKey: () => {
        if (startupFailure) throw new Error(uri + sharedPoint.toString('hex'));
        return clientPubkey;
      },
      getSharedSecret: () => sharedPoint,
      schnorr: { sign: async () => new Uint8Array(64) },
    },
    '@noble/hashes/sha256': { sha256: digest },
    '@noble/hashes/utils': {
      bytesToHex: (value) => Buffer.from(value).toString('hex'),
      hexToBytes: (value) => new Uint8Array(Buffer.from(value, 'hex')),
    },
    'node:crypto': { randomBytes },
    ws: { default: FakeWebSocket },
  };
  const module = new SourceTextModule(source, { context, identifier: 'nwc-test.mjs' });
  await module.link((specifier) => {
    const exports = dependencies[specifier];
    assert.ok(exports, `Unexpected dependency: ${specifier}`);
    return new SyntheticModule(Object.keys(exports), function () {
      for (const [name, value] of Object.entries(exports)) this.setExport(name, value);
    }, { context });
  });
  await module.evaluate();
  await Promise.race([socketReady, exited]);

  if (socket) {
    await socket.emit('open');
    const subscription = socket.sent.find((message) => message[0] === 'REQ')[1];
    const message = (value) => socket.emit('message', Buffer.from(JSON.stringify(value)));
    await message(['EOSE', subscription]);
    for (const frame of frames) {
      if (typeof frame === 'string') await socket.emit('message', Buffer.from(frame));
      else await message(frame);
    }
    if (socketError) await socket.emit('error', new Error(uri + rawKey.toString('hex')));
    if (timeout) {
      timeoutCallback();
    } else {
      const content = await encryptResponse(response === 'valid'
        ? JSON.stringify({ result_type: 'get_balance', result: { balance: 123, private_data: secret } })
        : `malformed wallet response ${secret}`);
      await message(['EVENT', subscription, {
        kind: 23195, pubkey: walletPubkey,
        tags: [['diagnostic', secret]], content,
      }]);
    }
  }
  await exited;
  return { output: output.join('\n'), exits };
}

function assertRedacted(output) {
  for (const value of forbidden) assert.equal(output.includes(value), false, 'Sensitive fixture appeared in diagnostic output');
}

test('successful diagnostics retain public IDs and redact secrets and wallet response', async () => {
  const result = await runScenario();
  assertRedacted(result.output);
  assert.ok(result.output.includes(walletPubkey));
  assert.ok(result.output.includes(clientPubkey.toString('hex')));
  assert.match(result.output, /Response decrypted and parsed/);
  assert.doesNotMatch(result.output, /123|private_data/);
  assert.deepEqual(result.exits, [0]);
});

test('debug environment variables cannot enable sensitive output', async () => {
  const result = await runScenario({ env: { DEBUG: '*', NWC_DEBUG: 'true', NODE_DEBUG: '*' } });
  assertRedacted(result.output);
});

test('invalid connection URI is reported without echoing the URI', async () => {
  const result = await runScenario({ uriValue: `invalid URI ${uri}` });
  assertRedacted(result.output);
  assert.match(result.output, /failed to start/);
  assert.deepEqual(result.exits, [1]);
});

test('crypto startup errors do not echo credentials or shared material', async () => {
  const result = await runScenario({ startupFailure: true });
  assertRedacted(result.output);
  assert.deepEqual(result.exits, [1]);
});

test('relay notices, rejection reasons and malformed frames are redacted', async () => {
  const result = await runScenario({ frames: [
    ['NOTICE', secret], ['OK', 'request-id', false, uri],
    `invalid frame ${uri}`, { input: secret },
  ] });
  assertRedacted(result.output);
  assert.match(result.output, /Relay accepted event: no/);
  assert.match(result.output, /Ignoring malformed relay message/);
});

test('decrypted JSON parse errors do not echo wallet data', async () => {
  const result = await runScenario({ response: 'invalid' });
  assertRedacted(result.output);
  assert.match(result.output, /Unable to decrypt or parse/);
});

test('WebSocket errors do not echo credential-bearing URLs', async () => {
  const result = await runScenario({ socketError: true });
  assertRedacted(result.output);
  assert.match(result.output, /WebSocket error\./);
});

test('timeout diagnostics do not expose credentials', async () => {
  const result = await runScenario({ timeout: true });
  assertRedacted(result.output);
  assert.match(result.output, /Timeout/);
});
