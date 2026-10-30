#!/usr/bin/env node
/**
 * NWC (NIP-47) diagnostic script.
 *
 * Tests get_balance against a wallet service using well-known JS crypto
 * libraries. Logs public identities and operation status only. Wallet
 * credentials, ECDH material and response contents are never logged.
 *
 * Usage (Bash; keeps the URI out of shell history):
 *   read -rs -p "NWC URI: " NWC_URI
 *   export NWC_URI
 *   echo
 *   node tools/nwc-test.mjs
 *   unset NWC_URI
 *
 * Dependencies (install once):
 *   npm install --prefix tools @noble/secp256k1 @noble/hashes ws
 */

import { getPublicKey, getSharedSecret, schnorr } from '@noble/secp256k1';
import { sha256 } from '@noble/hashes/sha256';
import { randomBytes } from 'node:crypto';
import { bytesToHex } from '@noble/hashes/utils';
import WebSocket from 'ws';

async function runDiagnostic() {
  // ─── NWC connection string parsing ───────────────────────────────────────────

  const NWC_URI = process.env.NWC_URI;
  if (!NWC_URI) {
    console.error('Set NWC_URI env var to your nostr+walletconnect:// string');
    process.exit(1);
  }

  function parseNwcUri(uri) {
    const normalized = uri.trim().replace('nostr+walletconnect://', 'nwc://');
    const url = new URL(normalized);
    const walletPubkey = url.hostname;
    const relay = url.searchParams.get('relay');
    const secret = url.searchParams.get('secret');
    return { walletPubkey, relay, secret };
  }

  const { walletPubkey, relay, secret } = parseNwcUri(NWC_URI);
  const clientPubkey = bytesToHex(getPublicKey(secret));

  console.log('=== NWC Connection ===');
  console.log('Wallet service pubkey:', walletPubkey);
  console.log('Client pubkey (hex):  ', clientPubkey);

  // ─── ECDH shared secret (NIP-04: raw x-coordinate) ──────────────────────────

  // @noble/secp256k1 getSharedSecret returns the full compressed point (33 bytes)
  // by default, or uncompressed (65 bytes) with isCompressed=false.
  // The raw x-coordinate is bytes [1..33] of the compressed output.
  const sharedPointCompressed = getSharedSecret(secret, '02' + walletPubkey);
  const rawXCoord = sharedPointCompressed.slice(1, 33); // 32 bytes — NIP-04 key

  // ─── NIP-04 encrypt/decrypt helpers ──────────────────────────────────────────

  async function nip04Encrypt(plaintext, key) {
    const iv = randomBytes(16);
    const cryptoKey = await globalThis.crypto.subtle.importKey(
      'raw', key, { name: 'AES-CBC' }, false, ['encrypt']
    );
    const ct = new Uint8Array(
      await globalThis.crypto.subtle.encrypt({ name: 'AES-CBC', iv }, cryptoKey, new TextEncoder().encode(plaintext))
    );
    return Buffer.from(ct).toString('base64') + '?iv=' + Buffer.from(iv).toString('base64');
  }

  async function nip04Decrypt(content, key) {
    const [ctB64, ivB64] = content.split('?iv=');
    const ct = Buffer.from(ctB64, 'base64');
    const iv = Buffer.from(ivB64, 'base64');
    const cryptoKey = await globalThis.crypto.subtle.importKey(
      'raw', key, { name: 'AES-CBC' }, false, ['decrypt']
    );
    const pt = await globalThis.crypto.subtle.decrypt({ name: 'AES-CBC', iv }, cryptoKey, ct);
    return new TextDecoder().decode(pt);
  }

  // ─── Nostr event helpers ─────────────────────────────────────────────────────

  function serializeForId(event) {
    return JSON.stringify([0, event.pubkey, event.created_at, event.kind, event.tags, event.content]);
  }

  async function createEvent(privkey, kind, content, tags) {
    const pubkey = clientPubkey;
    const created_at = Math.floor(Date.now() / 1000);
    const unsigned = { pubkey, created_at, kind, tags, content };
    const serialized = serializeForId(unsigned);
    const idBytes = sha256(new TextEncoder().encode(serialized));
    const id = bytesToHex(idBytes);
    const sig = bytesToHex(await schnorr.sign(idBytes, privkey));
    return { ...unsigned, id, sig };
  }

  // ─── Main: send get_balance ──────────────────────────────────────────────────

  const requestPayload = JSON.stringify({ method: 'get_balance' });
  const encryptedContent = await nip04Encrypt(requestPayload, rawXCoord);

  console.log('\n=== Request ===');

  const requestEvent = await createEvent(secret, 23194, encryptedContent, [['p', walletPubkey]]);
  console.log('Event ID: ', requestEvent.id);

  // ─── WebSocket: subscribe, wait EOSE, publish, await response ────────────────

  console.log('\n=== Connecting to relay ===');

  const ws = new WebSocket(relay);
  const subId = 'nwc-diag-' + Date.now();

  let gotEose = false;

  ws.on('open', () => {
    console.log('Connected.');

    // Subscribe for response (kind 23195 referencing our event id)
    const filter = { kinds: [23195], '#e': [requestEvent.id] };
    const reqMsg = JSON.stringify(['REQ', subId, filter]);
    console.log('Response subscription sent.');
    ws.send(reqMsg);
  });

  ws.on('message', async (data) => {
    let msg;
    try {
      msg = JSON.parse(data.toString());
      if (!Array.isArray(msg)) throw new Error('Expected a relay message array');
    } catch {
      console.error('Ignoring malformed relay message.');
      return;
    }

    if (msg[0] === 'EOSE' && msg[1] === subId && !gotEose) {
      gotEose = true;
      console.log('\nGot EOSE — publishing request event...');
      const eventMsg = JSON.stringify(['EVENT', requestEvent]);
      ws.send(eventMsg);
      console.log('>> EVENT published');
    }

    if (msg[0] === 'OK') {
      console.log('Relay accepted event:', msg[2] === true ? 'yes' : 'no');
    }

    if (msg[0] === 'EVENT' && msg[1] === subId) {
      console.log('\n=== Got NWC Response ===');
      try {
        const decrypted = await nip04Decrypt(msg[2].content, rawXCoord);
        JSON.parse(decrypted);
        console.log('Response decrypted and parsed (details redacted).');
      } catch {
        console.error('Unable to decrypt or parse the NWC response.');
      }

      // Clean up
      ws.send(JSON.stringify(['CLOSE', subId]));
      ws.close();
    }
  });

  ws.on('error', () => console.error('WebSocket error.'));
  ws.on('close', () => {
    console.log('\nDisconnected.');
    process.exit(0);
  });

  // Timeout after 30s
  setTimeout(() => {
    console.error('\nTimeout — no response after 30s');
    ws.close();
    process.exit(1);
  }, 30000);
}

runDiagnostic().catch(() => {
  console.error('NWC diagnostic failed to start. Check the connection and dependency setup.');
  process.exit(1);
});
