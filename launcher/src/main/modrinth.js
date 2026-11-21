// Modrinth library: search, details and installing mods / resource packs / shaders / modpacks.
// API docs: https://docs.modrinth.com/api/
const fs = require('fs');
const os = require('os');
const path = require('path');
const AdmZip = require('adm-zip');
const { getJson, download, pool, safeJoin } = require('./net');
const instances = require('./instances');
const versions = require('./versions');

const API = 'https://api.modrinth.com/v2';
const TYPES = { mod: 'mod', resourcepack: 'resourcepack', shader: 'shader', modpack: 'modpack', datapack: 'datapack' };

async function search({ query = '', type = 'mod', gameVersion = '', sort = 'relevance', offset = 0, limit = 24 }) {
  const facets = [[`project_type:${TYPES[type] || 'mod'}`]];
  if (gameVersion) facets.push([`versions:${gameVersion}`]);
  if (type === 'mod' || type === 'modpack') facets.push(['categories:fabric']);
  const params = new URLSearchParams({ query, facets: JSON.stringify(facets), index: sort, offset: String(offset), limit: String(limit) });
  const res = await getJson(`${API}/search?${params}`);
  return {
    total: res.total_hits,
    hits: res.hits.map((h) => ({
      id: h.project_id,
      slug: h.slug,
      title: h.title,
      description: h.description,
      author: h.author,
      icon: h.icon_url || null,
      downloads: h.downloads,
      follows: h.follows,
      categories: h.display_categories || h.categories || [],
      type: h.project_type,
      gallery: h.featured_gallery || (h.gallery && h.gallery[0]) || null,
      url: `https://modrinth.com/${h.project_type}/${h.slug}`
    }))
  };
}

async function project(id) {
  const p = await getJson(`${API}/project/${encodeURIComponent(id)}`);
  return {
    id: p.id, slug: p.slug, title: p.title, description: p.description, body: p.body,
    icon: p.icon_url, downloads: p.downloads, type: p.project_type,
    gallery: (p.gallery || []).map((g) => g.url), url: `https://modrinth.com/${p.project_type}/${p.slug}`
  };
}

async function compatibleVersions(projectId, type, mcVersion) {
  const params = new URLSearchParams({ game_versions: JSON.stringify([mcVersion]) });
  if (type === 'mod' || type === 'modpack') params.set('loaders', JSON.stringify(['fabric']));
  return getJson(`${API}/project/${encodeURIComponent(projectId)}/version?${params}`);
}

async function installVersion(inst, version, type, meta, done) {
  if (done.has(version.project_id)) return;
  done.add(version.project_id);
  const file = version.files.find((f) => f.primary) || version.files[0];
  const dir = instances.CONTENT_DIRS[type === 'shader' ? 'shader' : type] || 'mods';
  const target = safeJoin(path.join(instances.instanceDir(inst.id), dir), file.filename);
  // replace an older file of the same project
  const prev = inst.installed[`modrinth:${version.project_id}`];
  if (prev && prev.file !== `${dir}/${file.filename}`) {
    fs.rmSync(path.join(instances.instanceDir(inst.id), prev.file), { force: true });
    fs.rmSync(path.join(instances.instanceDir(inst.id), prev.file + '.disabled'), { force: true });
  }
  await download(file.url, target, { sha1: file.hashes && file.hashes.sha1 });
  const info = { source: 'modrinth', title: meta.title, icon: meta.icon, file: `${dir}/${file.filename}`, version: version.version_number };
  instances.recordInstall(inst.id, `modrinth:${version.project_id}`, info);
  inst.installed[`modrinth:${version.project_id}`] = info;

  // required dependencies (mods only)
  if (type !== 'mod') return;
  for (const dep of version.dependencies || []) {
    if (dep.dependency_type !== 'required' || !dep.project_id && !dep.version_id) continue;
    let depVersion;
    if (dep.version_id) depVersion = await getJson(`${API}/version/${dep.version_id}`);
    else depVersion = (await compatibleVersions(dep.project_id, 'mod', inst.mcVersion))[0];
    if (!depVersion || done.has(depVersion.project_id) || inst.installed[`modrinth:${depVersion.project_id}`]) continue;
    const depProject = await getJson(`${API}/project/${depVersion.project_id}`);
    await installVersion(inst, depVersion, 'mod', { title: depProject.title, icon: depProject.icon_url }, done);
  }
}

/** Installs a project into an instance (mods include required dependencies). */
async function install({ instanceId, projectId, type, title, icon }) {
  const inst = instances.read(instanceId);
  const list = await compatibleVersions(projectId, type, inst.mcVersion);
  if (!list.length) throw new Error(`Keine passende Version für Minecraft ${inst.mcVersion}${type === 'mod' ? ' (Fabric)' : ''}.`);
  await installVersion(inst, list[0], type, { title, icon }, new Set());
  return { version: list[0].version_number };
}

/** Installs a Modrinth modpack (.mrpack) as a new instance. */
async function installModpack({ projectId, title, icon, gameVersion }, onProgress = () => {}) {
  const params = new URLSearchParams({ loaders: JSON.stringify(['fabric']) });
  if (gameVersion) params.set('game_versions', JSON.stringify([gameVersion]));
  const list = await getJson(`${API}/project/${encodeURIComponent(projectId)}/version?${params}`);
  if (!list.length) throw new Error('Dieses Modpack hat keine Fabric-Version' + (gameVersion ? ' für ' + gameVersion : '') + '. Visual Launcher unterstützt Fabric-Modpacks.');
  const version = list[0];
  const file = version.files.find((f) => f.primary) || version.files[0];
  const tmp = path.join(os.tmpdir(), `vl-${Date.now()}.mrpack`);
  onProgress({ stage: 'Modpack wird geladen', current: 0, total: 1 });
  await download(file.url, tmp, { sha1: file.hashes && file.hashes.sha1 });
  try {
    const zip = new AdmZip(tmp);
    const index = JSON.parse(zip.readAsText('modrinth.index.json'));
    const deps = index.dependencies || {};
    if (!deps['fabric-loader']) throw new Error('Nur Fabric-Modpacks werden unterstützt.');
    const inst = instances.create({
      name: title || index.name,
      mcVersion: deps.minecraft,
      loaderVersion: deps['fabric-loader'],
      visualClient: versions.visualClientSupports(deps.minecraft),
      icon,
      source: { type: 'modrinth-modpack', projectId, version: version.version_number }
    });
    const dir = instances.instanceDir(inst.id);
    const files = (index.files || []).filter((f) => !f.env || f.env.client !== 'unsupported');
    let done = 0;
    await pool(files, 8, async (f) => {
      await download(f.downloads[0], safeJoin(dir, f.path), { sha1: f.hashes && f.hashes.sha1 });
      done++;
      onProgress({ stage: 'Modpack-Dateien', current: done, total: files.length });
    });
    for (const entry of zip.getEntries()) {
      for (const prefix of ['overrides/', 'client-overrides/']) {
        if (!entry.entryName.startsWith(prefix) || entry.isDirectory) continue;
        const target = safeJoin(dir, entry.entryName.slice(prefix.length));
        fs.mkdirSync(path.dirname(target), { recursive: true });
        fs.writeFileSync(target, entry.getData());
      }
    }
    return instances.read(inst.id);
  } finally {
    fs.rmSync(tmp, { force: true });
  }
}

async function gameVersions() {
  const tags = await getJson(`${API}/tag/game_version`);
  return tags.filter((t) => t.version_type === 'release').map((t) => t.version);
}

module.exports = { search, project, install, installModpack, gameVersions };
