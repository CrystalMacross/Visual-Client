// Downloads the Java runtime Mojang publishes for each Minecraft version
// (the same runtimes the official launcher uses), so players need no Java install.
const fs = require('fs');
const path = require('path');
const { getJson, download, pool } = require('./net');

const RUNTIMES = 'https://launchermeta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json';

function platformKey() {
  const { platform, arch } = process;
  if (platform === 'win32') return arch === 'arm64' ? 'windows-arm64' : arch === 'ia32' ? 'windows-x86' : 'windows-x64';
  if (platform === 'darwin') return arch === 'arm64' ? 'mac-os-arm64' : 'mac-os';
  if (platform === 'linux') return arch === 'ia32' ? 'linux-i386' : 'linux';
  throw new Error('Nicht unterstütztes Betriebssystem: ' + platform);
}

function javaBinary(dir) {
  if (process.platform === 'win32') return path.join(dir, 'bin', 'javaw.exe');
  if (process.platform === 'darwin') return path.join(dir, 'jre.bundle', 'Contents', 'Home', 'bin', 'java');
  return path.join(dir, 'bin', 'java');
}

/**
 * Makes sure the runtime `component` (e.g. "java-runtime-delta") exists below root/runtime.
 * @returns absolute path of the java executable
 */
async function ensureJava(root, component, onProgress = () => {}) {
  const dir = path.join(root, 'runtime', component, platformKey());
  const marker = path.join(dir, '.visual-launcher-complete');
  const bin = javaBinary(dir);
  if (fs.existsSync(marker) && fs.existsSync(bin)) return bin;

  onProgress({ stage: 'Java wird vorbereitet', current: 0, total: 1 });
  const all = await getJson(RUNTIMES);
  const entries = (all[platformKey()] || {})[component];
  if (!entries || !entries.length) {
    throw new Error(`Für dein System gibt es keine Java-Laufzeit "${component}". Trage in den Einstellungen einen eigenen Java-Pfad ein.`);
  }
  const manifest = await getJson(entries[0].manifest.url);
  const files = Object.entries(manifest.files);

  for (const [rel, f] of files) {
    if (f.type === 'directory') fs.mkdirSync(path.join(dir, rel), { recursive: true });
  }
  const downloads = files.filter(([, f]) => f.type === 'file');
  let done = 0;
  await pool(downloads, 16, async ([rel, f]) => {
    const target = path.join(dir, rel);
    await download(f.downloads.raw.url, target, { sha1: f.downloads.raw.sha1 });
    if (f.executable && process.platform !== 'win32') fs.chmodSync(target, 0o755);
    done++;
    if (done % 20 === 0 || done === downloads.length) {
      onProgress({ stage: 'Java wird heruntergeladen', current: done, total: downloads.length });
    }
  });
  if (process.platform !== 'win32') {
    for (const [rel, f] of files) {
      if (f.type !== 'link') continue;
      const link = path.join(dir, rel);
      try { fs.rmSync(link, { force: true }); fs.symlinkSync(f.target, link); } catch { /* ignore */ }
    }
  }
  fs.writeFileSync(marker, new Date().toISOString());
  return bin;
}

module.exports = { ensureJava };
