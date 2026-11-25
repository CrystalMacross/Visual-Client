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

const ICON_FILE = 'profile.png';

/** Adds the instance's own profile picture (if any) as a data URL for the UI. */
function withIconUrl(meta) {
  const file = path.join(instanceDir(meta.id), ICON_FILE);
  if (meta.customIcon && fs.existsSync(file)) {
    meta.iconUrl = 'data:image/png;base64,' + fs.readFileSync(file).toString('base64');
  }
  return meta;
}

/** Stores a square PNG (already cropped/resized) as the instance's profile picture. */
function setProfilePicture(id, png) {
  fs.writeFileSync(path.join(instanceDir(id), ICON_FILE), png);
  const meta = read(id);
  meta.customIcon = true;
  write(meta);
  return withIconUrl(read(id));
}

function clearProfilePicture(id) {
  fs.rmSync(path.join(instanceDir(id), ICON_FILE), { force: true });
  const meta = read(id);
  delete meta.customIcon;
  write(meta);
  return read(id);
}

function write(meta) {
  delete meta.iconUrl;
  fs.mkdirSync(instanceDir(meta.id), { recursive: true });
  fs.writeFileSync(metaFile(meta.id), JSON.stringify(meta, null, 2));
  return meta;
}

function list() {
  fs.mkdirSync(instancesRoot(), { recursive: true });
  return fs.readdirSync(instancesRoot())
    .filter((id) => fs.existsSync(path.join(instancesRoot(), id, 'visual-launcher.json')))
    .map((id) => { try { return withIconUrl(read(id)); } catch { return null; } })
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
  const allowed = ['name', 'visualClient', 'icon', 'lastPlayed', 'loaderVersion', 'installed', 'performanceMods'];
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
        name: info ? info.title : /^visual-client-/i.test(base) ? 'Visual Client ' + base.replace(/^visual-client-|\.jar$/gi, '') : /^visual-styles-/i.test(base) ? 'Visual Styles ' + base.replace(/^visual-styles-|\.jar$/gi, '') : base,
        icon: info ? info.icon : null,
        source: info ? info.source : null,
        enabled,
        size: stat.isFile() ? stat.size : 0,
        builtIn: /^visual-(client|styles)-/i.test(base)
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

// ---------------------------------------------------------------- uploaded files ("Upload Files")

const MAX_UPLOAD = 512 * 1024 * 1024;

/** Reads a zip/jar's entries (AdmZip is only loaded when files are uploaded). */
function openArchive(file) {
  const AdmZip = require('adm-zip');
  try {
    return new AdmZip(file);
  } catch {
    throw new Error('Die Datei ist kein gültiges Archiv (beschädigt oder keine .jar/.zip).');
  }
}

function entryText(zip, name) {
  const e = zip.getEntry(name);
  return e ? zip.readAsText(e) : null;
}

/** fabric.mod.json "icon" is a path or a { size: path } map; returns a small PNG as data URL. */
function modIcon(zip, icon) {
  let p = icon;
  if (p && typeof p === 'object') {
    const sizes = Object.keys(p).map(Number).filter((n) => n > 0).sort((a, b) => a - b);
    const best = sizes.find((n) => n >= 64) || sizes[sizes.length - 1];
    p = best !== undefined ? p[String(best)] : null;
  }
  if (typeof p !== 'string' || !/\.png$/i.test(p)) return null;
  const e = zip.getEntry(p.replace(/^\//, ''));
  if (!e || e.header.size > 256 * 1024) return null;
  return 'data:image/png;base64,' + e.getData().toString('base64');
}

/**
 * Works out what an uploaded file is:
 *   .jar  -> mod (Fabric: name/version/icon from fabric.mod.json; Quilt/Forge: added with a warning)
 *   .zip  -> resource pack (pack.mcmeta) or shader pack (shaders/ folder)
 */
function inspectUpload(file) {
  const ext = path.extname(file).toLowerCase();
  const base = path.basename(file);
  const zip = openArchive(file);
  const names = zip.getEntries().map((e) => e.entryName);
  if (ext === '.jar') {
    const info = { type: 'mod', title: base.replace(/\.jar$/i, ''), version: null, icon: null, warning: null };
    const fabric = entryText(zip, 'fabric.mod.json');
    if (fabric) {
      try {
        const json = JSON.parse(fabric);
        if (json.name) info.title = String(json.name).slice(0, 80);
        if (json.version) info.version = String(json.version).slice(0, 40);
        info.icon = modIcon(zip, json.icon);
      } catch { /* keep the file name */ }
    } else if (names.includes('quilt.mod.json')) {
      info.warning = 'Quilt-Mod – läuft nur, wenn sie auch Fabric unterstützt.';
    } else if (names.includes('META-INF/mods.toml') || names.includes('META-INF/neoforge.mods.toml')) {
      info.warning = 'Forge/NeoForge-Mod – Fabric lädt sie nicht.';
    } else {
      info.warning = 'Keine Fabric-Mod erkannt (fabric.mod.json fehlt).';
    }
    return info;
  }
  if (ext === '.zip') {
    if (names.includes('pack.mcmeta')) {
      return { type: 'resourcepack', title: base.replace(/\.zip$/i, ''), version: null, icon: packIcon(zip), warning: null };
    }
    if (names.some((n) => n.startsWith('shaders/'))) {
      return { type: 'shader', title: base.replace(/\.zip$/i, ''), version: null, icon: null, warning: null };
    }
    if (names.some((n) => /^[^/]+\/pack\.mcmeta$/.test(n))) {
      throw new Error('Das Ressourcenpaket ist in einem Unterordner verpackt – pack.mcmeta muss direkt im Zip liegen.');
    }
    throw new Error('Unbekanntes Zip: weder Ressourcenpaket (pack.mcmeta) noch Shaderpaket (shaders/).');
  }
  throw new Error('Nur .jar (Mods) und .zip (Ressourcen- und Shaderpakete) werden unterstützt.');
}

function packIcon(zip) {
  const e = zip.getEntry('pack.png');
  if (!e || e.header.size > 256 * 1024) return null;
  return 'data:image/png;base64,' + e.getData().toString('base64');
}

/**
 * Copies files the user picked (mods not on Modrinth, own resource/shader packs) into the
 * instance and records them so the content list shows name, version and icon.
 * @returns {{ added: object[], errors: object[] }}
 */
function addFiles(id, files) {
  const meta = read(id);
  const added = [];
  const errors = [];
  for (const raw of files) {
    const file = String(raw);
    const base = path.basename(file);
    try {
      const stat = fs.statSync(file);
      if (!stat.isFile()) throw new Error('Keine Datei.');
      if (stat.size > MAX_UPLOAD) throw new Error('Größer als 512 MB.');
      if (/^visual-client-/i.test(base)) throw new Error('Visual Client wird vom Launcher selbst verwaltet.');
      if (/^visual-styles-/i.test(base)) throw new Error('Visual Styles wird vom Launcher selbst verwaltet.');
      const info = inspectUpload(file);
      const dir = CONTENT_DIRS[info.type];
      const name = base.replace(/[^\w.+\- ()[\]]/g, '_');
      const target = path.join(instanceDir(id), dir, name);
      fs.mkdirSync(path.dirname(target), { recursive: true });
      fs.rmSync(target + '.disabled', { force: true });
      fs.copyFileSync(file, target);
      for (const [key, rec] of Object.entries(meta.installed)) {
        if (rec.file === `${dir}/${name}`) delete meta.installed[key];
      }
      meta.installed[`upload:${dir}/${name}`] = {
        title: info.version ? `${info.title} ${info.version}` : info.title,
        file: `${dir}/${name}`,
        icon: info.icon,
        source: 'Upload',
        type: info.type,
        uploaded: Date.now()
      };
      added.push({ file: name, type: info.type, title: info.title, warning: info.warning });
    } catch (e) {
      errors.push({ file: base, message: e.message });
    }
  }
  write(meta);
  return { added, errors };
}

/** Records a library install so it can be shown with title/icon and isn't installed twice. */
function recordInstall(id, key, info) {
  const meta = read(id);
  meta.installed[key] = info;
  write(meta);
}

module.exports = {
  CONTENT_DIRS, sharedRoot, instancesRoot, instanceDir, list, read, create, update, addPlayTime, remove,
  setProfilePicture, clearProfilePicture, openFolder,
  content, toggleContent, removeContent, recordInstall, addFiles
};
