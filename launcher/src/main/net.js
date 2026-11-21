// Small HTTP helpers. Every request goes straight to the respective service
// (Mojang, Microsoft, Fabric, Modrinth, CurseForge) - never to the launcher author.
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { Readable } = require('stream');
const { pipeline } = require('stream/promises');

const USER_AGENT = 'VisualLauncher/1.0.0';

// Electron's net.fetch uses Chromium's network stack (system proxy + certificate store).
let doFetch = globalThis.fetch;
try {
  const { net } = require('electron');
  if (net && typeof net.fetch === 'function') doFetch = (url, opts) => net.fetch(url, opts);
} catch { /* plain Node (scripts/tests) */ }

async function request(url, opts = {}) {
  const res = await doFetch(url, {
    ...opts,
    headers: { 'User-Agent': USER_AGENT, Accept: 'application/json', ...(opts.headers || {}) }
  });
  if (!res.ok) {
    let detail = '';
    try { detail = (await res.text()).slice(0, 200); } catch { /* ignore */ }
    const err = new Error(`HTTP ${res.status} bei ${new URL(url).host}${detail ? ': ' + detail : ''}`);
    err.status = res.status;
    throw err;
  }
  return res;
}

async function getJson(url, opts) {
  return (await request(url, opts)).json();
}

function sha1File(file) {
  return new Promise((resolve, reject) => {
    const hash = crypto.createHash('sha1');
    fs.createReadStream(file).on('error', reject).on('data', (d) => hash.update(d)).on('end', () => resolve(hash.digest('hex')));
  });
}

/** Downloads url to dest (atomically). Skips the download when the file exists with the right sha1. */
async function download(url, dest, { sha1, headers } = {}) {
  if (sha1 && fs.existsSync(dest) && (await sha1File(dest)) === sha1.toLowerCase()) return dest;
  fs.mkdirSync(path.dirname(dest), { recursive: true });
  const tmp = dest + '.part';
  const res = await request(url, { headers: { Accept: '*/*', ...(headers || {}) } });
  await pipeline(Readable.fromWeb(res.body), fs.createWriteStream(tmp));
  if (sha1) {
    const actual = await sha1File(tmp);
    if (actual !== sha1.toLowerCase()) {
      fs.rmSync(tmp, { force: true });
      throw new Error(`Prüfsumme stimmt nicht: ${path.basename(dest)}`);
    }
  }
  fs.renameSync(tmp, dest);
  return dest;
}

/** Runs async jobs with limited parallelism. */
async function pool(items, limit, fn) {
  let i = 0;
  const workers = Array.from({ length: Math.min(limit, items.length) }, async () => {
    while (i < items.length) {
      const idx = i++;
      await fn(items[idx], idx);
    }
  });
  await Promise.all(workers);
}

/** Resolves rel inside base and refuses paths that escape it (zip-slip protection). */
function safeJoin(base, rel) {
  const resolved = path.resolve(base, rel);
  const root = path.resolve(base) + path.sep;
  if (!resolved.startsWith(root)) throw new Error('Unsicherer Dateipfad im Paket: ' + rel);
  return resolved;
}

module.exports = { request, getJson, download, pool, sha1File, safeJoin, USER_AGENT };
