// Faster game starts.
//
// minecraft-launcher-core (MCLC) does a lot of work on every launch that only matters the
// first time: it re-hashes every asset file (several thousand files, hundreds of MB), hashes
// every library jar in the background and runs `java -version`. These patches keep the
// behaviour for missing or broken files but skip the repeated work:
//   - assets: a file that exists with the size from the asset index is not hashed again
//   - library hashes are only computed when MCLC actually waits for the result
//   - a Java runtime that worked once is not probed again until the file changes
// The JVM flags give Java 21+ a class-data cache (AppCDS) so classes load faster.
const fs = require('fs');
const path = require('path');
const Handler = require('minecraft-launcher-core/components/handler');
const store = require('./store');
const { pool } = require('./net');

const JAVA_CACHE = 'java-checked.json';

let installed = false;

function install() {
  if (installed) return;
  installed = true;
  const originalAssets = Handler.prototype.getAssets;
  const originalCheckSum = Handler.prototype.checkSum;
  const originalCheckJava = Handler.prototype.checkJava;

  Handler.prototype.getAssets = async function fastAssets() {
    if (typeof this.isLegacy === 'function' && this.isLegacy()) return originalAssets.call(this);
    const assetDir = path.resolve(this.options.overrides.assetRoot || path.join(this.options.root, 'assets'));
    const assetId = this.options.version.custom || this.options.version.number;
    const indexDir = path.join(assetDir, 'indexes');
    const indexFile = path.join(indexDir, `${assetId}.json`);
    let index = readIndex(indexFile);
    if (!index) {
      await this.downloadAsync(this.version.assetIndex.url, indexDir, `${assetId}.json`, true, 'asset-json');
      index = readIndex(indexFile);
      if (!index) return originalAssets.call(this);
    }
    const objects = Object.values(index.objects || {});
    const missing = [];
    await pool(objects, 64, async (o) => {
      const file = path.join(assetDir, 'objects', o.hash.substring(0, 2), o.hash);
      try {
        const st = await fs.promises.stat(file);
        if (st.isFile() && (!o.size || st.size === o.size)) return;
      } catch { /* missing */ }
      missing.push(o);
    });
    this.client.emit('debug', `[Visual Launcher]: assets ${objects.length - missing.length}/${objects.length} vorhanden, ${missing.length} werden geladen`);
    if (!missing.length) return;
    let done = 0;
    this.client.emit('progress', { type: 'assets', task: 0, total: missing.length });
    await pool(missing, 16, async (o) => {
      const sub = o.hash.substring(0, 2);
      await this.downloadAsync(`${this.options.overrides.url.resource}/${sub}/${o.hash}`,
        path.join(assetDir, 'objects', sub), o.hash, true, 'assets');
      done++;
      this.client.emit('progress', { type: 'assets', task: done, total: missing.length });
    });
  };

  // MCLC calls checkSum for every library without awaiting it (the result is never used),
  // which hashes ~100 MB of jars while the game starts. The hash is now only computed when
  // somebody waits for it (natives of old versions).
  Handler.prototype.checkSum = function lazyCheckSum(hash, file) {
    const self = this;
    let run = null;
    return {
      then(resolve, reject) {
        if (!run) run = originalCheckSum.call(self, hash, file);
        return run.then(resolve, reject);
      }
    };
  };

  Handler.prototype.checkJava = async function cachedCheckJava(java) {
    const stamp = fileStamp(java);
    const cache = store.readJson(JAVA_CACHE, {});
    if (stamp && cache[java] === stamp) {
      this.client.emit('debug', `[Visual Launcher]: Java bereits geprüft (${java})`);
      return { run: true };
    }
    const result = await originalCheckJava.call(this, java);
    if (result && result.run && stamp) {
      cache[java] = stamp;
      try { store.writeJson(JAVA_CACHE, cache); } catch { /* only a cache */ }
    }
    return result;
  };
}

function readIndex(file) {
  try {
    const json = JSON.parse(fs.readFileSync(file, 'utf8'));
    return json && json.objects ? json : null;
  } catch {
    return null;
  }
}

function fileStamp(file) {
  try {
    const st = fs.statSync(file);
    return st.isFile() ? `${st.size}:${Math.round(st.mtimeMs)}` : null;
  } catch {
    return null;
  }
}

// Java runtimes Mojang ships before Java 19 do not know -XX:+AutoCreateSharedArchive.
const OLD_RUNTIMES = new Set(['jre-legacy', 'java-runtime-alpha', 'java-runtime-beta', 'java-runtime-gamma', 'java-runtime-gamma-snapshot']);

/**
 * JVM flags for a faster start. `component` is the Mojang runtime name (null for a custom Java,
 * whose version is unknown - then nothing is added).
 */
function jvmFlags(root, versionId, component) {
  if (!component || OLD_RUNTIMES.has(component) || !component.startsWith('java-runtime-')) return [];
  const dir = path.join(root, 'cache', 'cds');
  try { fs.mkdirSync(dir, { recursive: true }); } catch { return []; }
  const name = `${versionId}-${component}`.replace(/[^\w.-]/g, '_');
  return [
    '-XX:+IgnoreUnrecognizedVMOptions',
    '-Xlog:cds*=off',
    '-XX:+AutoCreateSharedArchive',
    `-XX:SharedArchiveFile=${path.join(dir, name + '.jsa')}`
  ];
}

module.exports = { install, jvmFlags };
