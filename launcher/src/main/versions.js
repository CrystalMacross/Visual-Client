// Minecraft + Fabric version handling and the bundled Visual Client mod.
const fs = require('fs');
const path = require('path');
const { app } = require('electron');
const { getJson, download, request } = require('./net');

const MOJANG_MANIFEST = 'https://piston-meta.mojang.com/mc/game/version_manifest_v2.json';
const FABRIC_META = 'https://meta.fabricmc.net/v2';
const FABRIC_API_PROJECT = 'P7dR8mSH'; // Modrinth id of Fabric API

let manifestCache = null;

async function mojangManifest() {
  if (!manifestCache) manifestCache = await getJson(MOJANG_MANIFEST);
  return manifestCache;
}

function parseVersion(v) {
  const m = /^(\d+)\.(\d+)(?:\.(\d+))?$/.exec(v);
  return m ? [Number(m[1]), Number(m[2]), Number(m[3] || 0)] : null;
}

/** Visual Client 1.12 supports Minecraft 1.21.4 - 1.21.11 and 26.1 - 26.3.x */
function visualClientSupports(v) {
  const p = parseVersion(v);
  if (!p) return false;
  if (p[0] === 1 && p[1] === 21) return p[2] >= 4;
  if (p[0] === 26) return p[1] >= 1 && p[1] <= 3;
  return false;
}

/** Releases that Fabric supports, newest first, flagged with Visual Client support. */
async function listVersions() {
  const [mojang, fabricGames] = await Promise.all([
    mojangManifest(),
    getJson(`${FABRIC_META}/versions/game`).catch(() => [])
  ]);
  const fabric = new Set(fabricGames.map((g) => g.version));
  return mojang.versions
    .filter((v) => v.type === 'release' && (fabric.size === 0 || fabric.has(v.id)))
    .map((v) => ({ id: v.id, visualClient: visualClientSupports(v.id) }));
}

async function versionJson(mcVersion) {
  const entry = (await mojangManifest()).versions.find((v) => v.id === mcVersion);
  if (!entry) throw new Error('Unbekannte Minecraft-Version ' + mcVersion);
  return getJson(entry.url);
}

/** Installs the Fabric profile json and returns its version id. */
async function installFabric(root, mcVersion, loaderVersion) {
  let loader = loaderVersion;
  if (!loader) {
    const loaders = await getJson(`${FABRIC_META}/versions/loader/${encodeURIComponent(mcVersion)}`);
    if (!loaders.length) throw new Error('Fabric unterstützt Minecraft ' + mcVersion + ' nicht.');
    loader = (loaders.find((l) => l.loader.stable) || loaders[0]).loader.version;
  }
  const profile = await getJson(`${FABRIC_META}/versions/loader/${encodeURIComponent(mcVersion)}/${encodeURIComponent(loader)}/profile/json`);
  const dir = path.join(root, 'versions', profile.id);
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(path.join(dir, profile.id + '.json'), JSON.stringify(profile, null, 2));
  return { id: profile.id, loader };
}

// ---------------------------------------------------------------- Visual Client

function bundledModDirs() {
  return [
    path.join(process.resourcesPath || '', 'mods'),
    path.join(app.getAppPath(), 'resources', 'mods'),
    path.resolve(app.getAppPath(), '..') // development: repository root
  ];
}

function bundledVisualClient() {
  let best = null;
  for (const dir of bundledModDirs()) {
    let names = [];
    try { names = fs.readdirSync(dir); } catch { continue; }
    for (const name of names) {
      const m = /^visual-client-(\d+)[._](\d+)(?:[._](\d+))?\.jar$/i.exec(name);
      if (!m) continue;
      const v = [Number(m[1]), Number(m[2]), Number(m[3] || 0)];
      const cmp = best ? (v[0] - best.v[0] || v[1] - best.v[1] || v[2] - best.v[2]) : 1;
      if (cmp > 0) best = { v, file: path.join(dir, name) };
    }
  }
  return best ? { version: best.v.join('.'), file: best.file } : null;
}

/** Puts the bundled Visual Client into the instance (replacing older copies). */
function installVisualClient(instanceDir) {
  const vc = bundledVisualClient();
  if (!vc) throw new Error('Visual Client ist nicht im Launcher enthalten.');
  const mods = path.join(instanceDir, 'mods');
  fs.mkdirSync(mods, { recursive: true });
  const targetName = `visual-client-${vc.version}.jar`;
  for (const f of fs.readdirSync(mods)) {
    if (/^visual-client-.*\.jar(\.disabled)?$/i.test(f) && f !== targetName) fs.rmSync(path.join(mods, f));
  }
  const target = path.join(mods, targetName);
  if (!fs.existsSync(target) || fs.statSync(target).size !== fs.statSync(vc.file).size) {
    fs.copyFileSync(vc.file, target);
  }
  return vc.version;
}

/** Fabric API is required by Visual Client; fetch the newest build for the version from Modrinth. */
async function ensureFabricApi(instanceDir, mcVersion) {
  const mods = path.join(instanceDir, 'mods');
  fs.mkdirSync(mods, { recursive: true });
  if (fs.readdirSync(mods).some((f) => /^fabric-api-.*\.jar$/i.test(f))) return;
  const params = new URLSearchParams({ loaders: JSON.stringify(['fabric']), game_versions: JSON.stringify([mcVersion]) });
  const versions = await getJson(`https://api.modrinth.com/v2/project/${FABRIC_API_PROJECT}/version?${params}`);
  if (!versions.length) throw new Error('Keine Fabric API für Minecraft ' + mcVersion + ' gefunden.');
  const file = versions[0].files.find((f) => f.primary) || versions[0].files[0];
  await download(file.url, path.join(mods, file.filename), { sha1: file.hashes.sha1 });
}

module.exports = {
  listVersions, versionJson, installFabric, visualClientSupports,
  bundledVisualClient, installVisualClient, ensureFabricApi, request
};
