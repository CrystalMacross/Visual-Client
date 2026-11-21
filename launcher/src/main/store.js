// Local storage for settings and accounts. Everything stays on this computer.
// Secrets (Microsoft refresh tokens) are encrypted with the
// operating system's key store via Electron's safeStorage when available.
const fs = require('fs');
const path = require('path');
const { app, safeStorage } = require('electron');

const DEFAULT_SETTINGS = {
  legalAccepted: null, // version string of the accepted terms
  design: {
    mode: 'gradient', // gradient | solid | image
    color1: '#0f1020',
    color2: '#3b1d6e',
    angle: 135,
    accent: '#8b5cf6',
    image: null, // file name inside userData/backgrounds
    dim: 35,
    blur: 0
  },
  java: { minMemory: 1024, maxMemory: 4096, customPath: '', jvmArgs: '', fastStart: true },
  game: { width: 1280, height: 720, fullscreen: false, closeLauncher: false },
  discord: { enabled: true, showServer: true, clientId: '' },
  selectedInstance: null,
  selectedAccount: null
};

function dataDir() {
  return app.getPath('userData');
}

function file(name) {
  return path.join(dataDir(), name);
}

function readJson(name, fallback) {
  try {
    return JSON.parse(fs.readFileSync(file(name), 'utf8'));
  } catch {
    return fallback;
  }
}

function writeJson(name, value) {
  fs.mkdirSync(dataDir(), { recursive: true });
  const target = file(name);
  fs.writeFileSync(target + '.tmp', JSON.stringify(value, null, 2));
  fs.renameSync(target + '.tmp', target);
}

function merge(base, extra) {
  const out = { ...base };
  for (const [k, v] of Object.entries(extra || {})) {
    out[k] = v && typeof v === 'object' && !Array.isArray(v) && base[k] && typeof base[k] === 'object'
      ? merge(base[k], v)
      : v;
  }
  return out;
}

function encrypt(text) {
  if (!text) return '';
  if (safeStorage.isEncryptionAvailable()) return 'enc:' + safeStorage.encryptString(text).toString('base64');
  return 'b64:' + Buffer.from(text, 'utf8').toString('base64');
}

function decrypt(value) {
  if (!value) return '';
  if (value.startsWith('enc:')) return safeStorage.decryptString(Buffer.from(value.slice(4), 'base64'));
  if (value.startsWith('b64:')) return Buffer.from(value.slice(4), 'base64').toString('utf8');
  return value;
}

function getSettings() {
  const s = merge(DEFAULT_SETTINGS, readJson('settings.json', {}));
  delete s.curseforge; // removed feature; drop old stored key
  return s;
}

function saveSettings(patch) {
  const current = merge(DEFAULT_SETTINGS, readJson('settings.json', {}));
  const next = merge(current, patch);
  delete next.curseforge;
  writeJson('settings.json', next);
  return getSettings();
}

function getAccounts() {
  return readJson('accounts.json', []);
}

function saveAccounts(list) {
  writeJson('accounts.json', list);
}

module.exports = { dataDir, file, readJson, writeJson, getSettings, saveSettings, getAccounts, saveAccounts, encrypt, decrypt };
