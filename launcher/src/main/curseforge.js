// CurseForge library. The CurseForge API needs an API key
// (free at https://console.curseforge.com). The key is stored locally and encrypted.
const fs = require('fs');
const os = require('os');
const path = require('path');
const AdmZip = require('adm-zip');
const { getJson, download, pool, safeJoin } = require('./net');
const instances = require('./instances');
const versions = require('./versions');
const store = require('./store');

const API = 'https://api.curseforge.com/v1';
const GAME = 432; // Minecraft
const CLASS = { mod: 6, resourcepack: 12, shader: 6552, modpack: 4471, datapack: 6945 };
const CLASS_TO_TYPE = Object.fromEntries(Object.entries(CLASS).map(([k, v]) => [v, k]));
const FABRIC = 4;
const SORT = { relevance: 1, downloads: 6, updated: 3, newest: 11, follows: 2 };

function key() {
  const k = store.getSettings().curseforge.apiKey;
  if (!k) {
    const err = new Error('Für CurseForge wird ein API-Schlüssel benötigt. Trage ihn unter Einstellungen → CurseForge ein.');
    err.code = 'NO_KEY';
    throw err;
  }
  return k;
}

function cf(url, opts = {}) {
  return getJson(API + url, { ...opts, headers: { 'x-api-key': key(), 'Content-Type': 'application/json', ...(opts.headers || {}) } });
}

function hasKey() {
  return !!store.getSettings().curseforge.apiKey;
}

function mapMod(m) {
  return {
    id: m.id,
    slug: m.slug,
    title: m.name,
    description: m.summary,
    author: (m.authors && m.authors[0] && m.authors[0].name) || '',
    icon: (m.logo && (m.logo.thumbnailUrl || m.logo.url)) || null,
    downloads: m.downloadCount,
    categories: (m.categories || []).map((c) => c.name).slice(0, 3),
    type: CLASS_TO_TYPE[m.classId] || 'mod',
    gallery: (m.screenshots && m.screenshots[0] && m.screenshots[0].url) || null,
    url: (m.links && m.links.websiteUrl) || `https://www.curseforge.com/minecraft/${m.slug}`
  };
}

async function search({ query = '', type = 'mod', gameVersion = '', sort = 'relevance', offset = 0, limit = 24 }) {
  const params = new URLSearchParams({
    gameId: String(GAME),
    classId: String(CLASS[type] || CLASS.mod),
    searchFilter: query,
    sortField: String(SORT[sort] || 1),
    sortOrder: 'desc',
    index: String(offset),
    pageSize: String(limit)
  });
  if (gameVersion) params.set('gameVersion', gameVersion);
  if (type === 'mod' || type === 'modpack') params.set('modLoaderType', String(FABRIC));
  const res = await cf(`/mods/search?${params}`);
  return { total: Math.min(res.pagination.totalCount, 10000), hits: res.data.map(mapMod) };
}

async function files(modId, type, mcVersion) {
  const params = new URLSearchParams({ pageSize: '50' });
  if (mcVersion) params.set('gameVersion', mcVersion);
  if (type === 'mod' || type === 'modpack') params.set('modLoaderType', String(FABRIC));
  const res = await cf(`/mods/${modId}/files?${params}`);
  return res.data.filter((f) => f.isAvailable !== false).sort((a, b) => new Date(b.fileDate) - new Date(a.fileDate));
}

function sha1Of(file) {
  const h = (file.hashes || []).find((x) => x.algo === 1);
  return h ? h.value : undefined;
}

function noDownload(modTitle) {
  return new Error(`"${modTitle}" erlaubt keine Downloads über andere Launcher. Bitte lade es direkt auf curseforge.com herunter.`);
}

async function installFile(inst, file, type, meta, done) {
  if (done.has(file.modId)) return;
  done.add(file.modId);
  if (!file.downloadUrl) throw noDownload(meta.title);
  const dir = instances.CONTENT_DIRS[type] || 'mods';
  const target = safeJoin(path.join(instances.instanceDir(inst.id), dir), file.fileName);
  const prev = inst.installed[`curseforge:${file.modId}`];
  if (prev && prev.file !== `${dir}/${file.fileName}`) {
    fs.rmSync(path.join(instances.instanceDir(inst.id), prev.file), { force: true });
  }
  await download(file.downloadUrl, target, { sha1: sha1Of(file) });
  const info = { source: 'curseforge', title: meta.title, icon: meta.icon, file: `${dir}/${file.fileName}`, version: file.displayName };
  instances.recordInstall(inst.id, `curseforge:${file.modId}`, info);
  inst.installed[`curseforge:${file.modId}`] = info;
  if (type !== 'mod') return;
  for (const dep of file.dependencies || []) {
    if (dep.relationType !== 3 || done.has(dep.modId) || inst.installed[`curseforge:${dep.modId}`]) continue;
    const depFiles = await files(dep.modId, 'mod', inst.mcVersion);
    if (!depFiles.length) continue;
    const depMod = (await cf(`/mods/${dep.modId}`)).data;
    await installFile(inst, depFiles[0], 'mod', { title: depMod.name, icon: depMod.logo && depMod.logo.thumbnailUrl }, done);
  }
}

async function install({ instanceId, projectId, type, title, icon }) {
  const inst = instances.read(instanceId);
  const list = await files(projectId, type, inst.mcVersion);
  if (!list.length) throw new Error(`Keine passende Datei für Minecraft ${inst.mcVersion}${type === 'mod' ? ' (Fabric)' : ''}.`);
  await installFile(inst, list[0], type, { title, icon }, new Set());
  return { version: list[0].displayName };
}

/** Installs a CurseForge modpack (manifest.json + overrides) as a new instance. */
async function installModpack({ projectId, title, icon, gameVersion }, onProgress = () => {}) {
  const list = await files(projectId, 'modpack', gameVersion);
  if (!list.length) throw new Error('Keine Fabric-Version dieses Modpacks gefunden.');
  const packFile = list[0];
  if (!packFile.downloadUrl) throw noDownload(title);
  const tmp = path.join(os.tmpdir(), `vl-${Date.now()}.zip`);
  onProgress({ stage: 'Modpack wird geladen', current: 0, total: 1 });
  await download(packFile.downloadUrl, tmp, { sha1: sha1Of(packFile) });
  try {
    const zip = new AdmZip(tmp);
    const manifest = JSON.parse(zip.readAsText('manifest.json'));
    const loaderId = ((manifest.minecraft.modLoaders || []).find((l) => l.primary) || manifest.minecraft.modLoaders[0] || {}).id || '';
    if (!loaderId.startsWith('fabric-')) throw new Error('Nur Fabric-Modpacks werden unterstützt.');
    const mc = manifest.minecraft.version;
    const inst = instances.create({
      name: title || manifest.name,
      mcVersion: mc,
      loaderVersion: loaderId.slice('fabric-'.length),
      visualClient: versions.visualClientSupports(mc),
      icon,
      source: { type: 'curseforge-modpack', projectId, version: packFile.displayName }
    });
    const dir = instances.instanceDir(inst.id);
    const wanted = (manifest.files || []).filter((f) => f.required !== false);
    const fileIds = wanted.map((f) => f.fileID);
    const modIds = [...new Set(wanted.map((f) => f.projectID))];
    const [fileInfo, modInfo] = await Promise.all([
      fileIds.length ? cf('/mods/files', { method: 'POST', body: JSON.stringify({ fileIds }) }) : { data: [] },
      modIds.length ? cf('/mods', { method: 'POST', body: JSON.stringify({ modIds }) }) : { data: [] }
    ]);
    const mods = new Map(modInfo.data.map((m) => [m.id, m]));
    const blocked = [];
    let done = 0;
    await pool(fileInfo.data, 8, async (f) => {
      const mod = mods.get(f.modId);
      const type = (mod && CLASS_TO_TYPE[mod.classId]) || 'mod';
      if (!f.downloadUrl) { blocked.push(mod ? mod.name : f.fileName); return; }
      await download(f.downloadUrl, safeJoin(path.join(dir, instances.CONTENT_DIRS[type] || 'mods'), f.fileName), { sha1: sha1Of(f) });
      done++;
      onProgress({ stage: 'Modpack-Dateien', current: done, total: fileInfo.data.length });
    });
    const overrides = (manifest.overrides || 'overrides') + '/';
    for (const entry of zip.getEntries()) {
      if (!entry.entryName.startsWith(overrides) || entry.isDirectory) continue;
      const target = safeJoin(dir, entry.entryName.slice(overrides.length));
      fs.mkdirSync(path.dirname(target), { recursive: true });
      fs.writeFileSync(target, entry.getData());
    }
    const result = instances.read(inst.id);
    result.blocked = blocked;
    return result;
  } finally {
    fs.rmSync(tmp, { force: true });
  }
}

module.exports = { search, install, installModpack, hasKey };
