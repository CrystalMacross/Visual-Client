// Instances: every instance has its own game folder (mods, resource packs, saves, ...).
// Versions, libraries, assets and Java runtimes are shared in <userData>/minecraft.
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { shell } = require('electron');
const store = require('./store');

const CONTENT_DIRS = { mod: 'mods', resourcepack: 'resourcepacks', shader: 'shaderpacks', datapack: 'datapacks' };

function sharedRoot() {
  return path.join(store.dataDir(), 'minecraft');
}

function instancesRoot() {
  return path.join(store.dataDir(), 'instances');
}

function instanceDir(id) {
  if (!/^[a-z0-9-]+$/.test(id)) throw new Error('Ungültige Instanz');
  return path.join(instancesRoot(), id);
}

function metaFile(id) {
  return path.join(instanceDir(id), 'visual-launcher.json');
}

function read(id) {
  const meta = JSON.parse(fs.readFileSync(metaFile(id), 'utf8'));
  meta.id = id;
  meta.installed = meta.installed || {};
  return meta;
}

function write(meta) {
  fs.mkdirSync(instanceDir(meta.id), { recursive: true });
  fs.writeFileSync(metaFile(meta.id), JSON.stringify(meta, null, 2));
  return meta;
}

function list() {
  fs.mkdirSync(instancesRoot(), { recursive: true });
  return fs.readdirSync(instancesRoot())
    .filter((id) => fs.existsSync(path.join(instancesRoot(), id, 'visual-launcher.json')))
    .map((id) => { try { return read(id); } catch { return null; } })
    .filter(Boolean)
    .sort((a, b) => (b.lastPlayed || b.created || 0) - (a.lastPlayed || a.created || 0));
}

function slug(name) {
  const base = String(name).toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '').slice(0, 32) || 'instanz';
  return base + '-' + crypto.randomBytes(3).toString('hex');
}

function create({ name, mcVersion, loaderVersion = null, visualClient = true, icon = null, source = null }) {
  if (!name || !mcVersion) throw new Error('Name und Version werden benötigt.');
  const meta = {
    id: slug(name),
    name: String(name).slice(0, 48),
    mcVersion,
    loader: 'fabric',
    loaderVersion,
    visualClient,
    icon,
    source,
    created: Date.now(),
    lastPlayed: null,
    playTime: 0,
    installed: {}
  };
  for (const dir of Object.values(CONTENT_DIRS)) fs.mkdirSync(path.join(instanceDir(meta.id), dir), { recursive: true });
  return write(meta);
}

function update(id, patch) {
  const meta = read(id);
  const allowed = ['name', 'visualClient', 'icon', 'lastPlayed', 'loaderVersion', 'installed'];
  for (const k of allowed) if (k in patch) meta[k] = patch[k];
  return write(meta);
}

/** Adds played milliseconds to an instance. */
function addPlayTime(id, ms) {
  if (!(ms > 0)) return;
  const meta = read(id);
  meta.playTime = (meta.playTime || 0) + Math.round(ms);
  write(meta);
}

function remove(id) {
  fs.rmSync(instanceDir(id), { recursive: true, force: true });
}

function openFolder(id, sub) {
  const dir = sub ? path.join(instanceDir(id), CONTENT_DIRS[sub] || '') : instanceDir(id);
  fs.mkdirSync(dir, { recursive: true });
  return shell.openPath(dir);
}

/** Files in mods / resourcepacks / shaderpacks with their enabled state. */
function content(id) {
  const meta = read(id);
  const byFile = {};
  for (const [key, info] of Object.entries(meta.installed)) byFile[info.file] = { ...info, key };
  const out = [];
  for (const [type, dir] of Object.entries(CONTENT_DIRS)) {
    const full = path.join(instanceDir(id), dir);
    let names = [];
    try { names = fs.readdirSync(full); } catch { continue; }
    for (const name of names) {
      const stat = fs.statSync(path.join(full, name));
      if (name.endsWith('.part')) continue;
      const enabled = !name.endsWith('.disabled');
      const base = enabled ? name : name.slice(0, -'.disabled'.length);
      const info = byFile[`${dir}/${base}`] || null;
      out.push({
        type,
        file: name,
        name: info ? info.title : /^visual-client-/i.test(base) ? 'Visual Client ' + base.replace(/^visual-client-|\.jar$/gi, '') : base,
        icon: info ? info.icon : null,
        source: info ? info.source : null,
        enabled,
        size: stat.isFile() ? stat.size : 0,
        builtIn: /^visual-client-/i.test(base)
      });
    }
  }
  return out;
}

function contentPath(id, type, file) {
  const dir = CONTENT_DIRS[type];
  if (!dir || file.includes('/') || file.includes('\\') || file.startsWith('.')) throw new Error('Ungültige Datei');
  return path.join(instanceDir(id), dir, file);
}

function toggleContent(id, type, file) {
  const p = contentPath(id, type, file);
  const next = file.endsWith('.disabled') ? p.slice(0, -'.disabled'.length) : p + '.disabled';
  fs.renameSync(p, next);
}

function removeContent(id, type, file) {
  fs.rmSync(contentPath(id, type, file), { recursive: true, force: true });
  const meta = read(id);
  const base = file.replace(/\.disabled$/, '');
  for (const [key, info] of Object.entries(meta.installed)) {
    if (info.file === `${CONTENT_DIRS[type]}/${base}`) delete meta.installed[key];
  }
  write(meta);
}

/** Records a library install so it can be shown with title/icon and isn't installed twice. */
function recordInstall(id, key, info) {
  const meta = read(id);
  meta.installed[key] = info;
  write(meta);
}

module.exports = {
  CONTENT_DIRS, sharedRoot, instancesRoot, instanceDir, list, read, create, update, addPlayTime, remove, openFolder,
  content, toggleContent, removeContent, recordInstall
};
