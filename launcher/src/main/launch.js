// Starts Minecraft with Fabric (+ Visual Client) through minecraft-launcher-core.
const fs = require('fs');
const path = require('path');
const childProcess = require('child_process');
const { Client } = require('minecraft-launcher-core');
const store = require('./store');
const auth = require('./auth');
const instances = require('./instances');
const versions = require('./versions');
const { ensureJava } = require('./java');
const { presence } = require('./discord');
const fastlaunch = require('./fastlaunch');

fastlaunch.install();

let running = null;
let starting = false;

// The game runs independently of the launcher: it is started detached with its output going
// to a log file (no pipes), so closing the launcher does not stop Minecraft. The running game
// is remembered here so a reopened launcher can keep counting its play time.
const RUNNING_FILE = 'running-game.json';

/** Follows a growing text file and reports complete lines. */
function tailFile(file, onLine) {
  let pos = 0;
  let rest = '';
  const read = () => {
    let fd;
    try {
      fd = fs.openSync(file, 'r');
      const size = fs.fstatSync(fd).size;
      if (size < pos) pos = 0;
      while (pos < size) {
        const buf = Buffer.alloc(Math.min(size - pos, 1 << 16));
        const n = fs.readSync(fd, buf, 0, buf.length, pos);
        if (n <= 0) break;
        pos += n;
        rest += buf.toString('utf8', 0, n);
      }
    } catch { /* not there yet */ } finally {
      if (fd !== undefined) fs.closeSync(fd);
    }
    const lines = rest.split(/\r?\n/);
    rest = lines.pop();
    for (const l of lines) if (l) onLine(l);
  };
  const timer = setInterval(read, 250);
  return { stop() { clearInterval(timer); read(); if (rest) onLine(rest); } };
}

function alive(pid) {
  try { process.kill(pid, 0); return true; } catch (e) { return e.code === 'EPERM'; }
}

/** Play time is flushed regularly (and remembered on disk) so it survives a closed launcher. */
function trackPlayTime(instanceId, pid, logFile, lastFlush, send) {
  let last = lastFlush;
  const save = () => { try { store.writeJson(RUNNING_FILE, { instanceId, pid, logFile, lastFlush: last }); } catch { /* ignore */ } };
  const flush = (until = Date.now()) => {
    try { instances.addPlayTime(instanceId, until - last); } catch { /* instance deleted */ }
    last = Math.max(last, until);
    save();
  };
  save();
  return {
    flush,
    finish(until) {
      flush(until);
      try { fs.rmSync(store.file(RUNNING_FILE), { force: true }); } catch { /* ignore */ }
      send('instances:changed', { instanceId });
    }
  };
}

function isRunning() {
  return !!running;
}

async function launch(instanceId, send) {
  if (running || starting) throw new Error('Minecraft läuft bereits.');
  starting = true;
  try {
    return await start(instanceId, send);
  } finally {
    starting = false;
  }
}

async function start(instanceId, send) {
  const settings = store.getSettings();
  const inst = instances.read(instanceId);
  const root = instances.sharedRoot();
  const gameDir = instances.instanceDir(inst.id);
  const progress = (p) => send('launch:progress', p);

  progress({ stage: 'Start wird vorbereitet', current: 0, total: 1 });
  // the login (usually cached) and the game files are prepared at the same time
  const authorization = auth.launchAuth(settings.selectedAccount);
  const files = (async () => {
    const fabric = await versions.installFabric(root, inst.mcVersion, inst.loaderVersion);
    if (!inst.loaderVersion) instances.update(inst.id, { loaderVersion: fabric.loader });
    if (inst.visualClient && versions.visualClientSupports(inst.mcVersion)) {
      versions.installVisualClient(gameDir);
      await versions.ensureFabricApi(gameDir, inst.mcVersion);
    }
    let javaPath = settings.java.customPath && fs.existsSync(settings.java.customPath) ? settings.java.customPath : null;
    let component = null;
    if (!javaPath) {
      component = await versions.javaComponent(root, inst.mcVersion, fabric.id);
      javaPath = await ensureJava(root, component, progress);
    }
    return { fabric, javaPath, component };
  })();
  authorization.catch(() => {}); // reported by Promise.all below
  files.catch(() => {});
  const [session, { fabric, javaPath, component }] = await Promise.all([authorization, files]);

  const userArgs = settings.java.jvmArgs ? settings.java.jvmArgs.split(/\s+/).filter(Boolean) : [];
  const fastArgs = settings.java.fastStart === false ? [] : fastlaunch.jvmFlags(root, fabric.id, component);

  const launcher = new Client();
  const opts = {
    root,
    authorization: session,
    version: { number: inst.mcVersion, type: 'release', custom: fabric.id },
    memory: { max: `${settings.java.maxMemory}M`, min: `${settings.java.minMemory}M` },
    javaPath,
    customArgs: fastArgs.concat(userArgs),
    window: settings.game.fullscreen ? { fullscreen: true } : { width: settings.game.width, height: settings.game.height },
    overrides: { gameDirectory: gameDir, detached: true, maxSockets: 8 }
  };

  const logFile = path.join(gameDir, 'logs', 'launcher-output.log');
  let tail = null;
  // replaces MCLC's spawn (which pipes the output and dies with the launcher)
  launcher.startMinecraft = function startDetached(args) {
    fs.mkdirSync(path.dirname(logFile), { recursive: true });
    const out = fs.openSync(logFile, 'w');
    let mc;
    try {
      mc = childProcess.spawn(this.options.javaPath || 'java', args, {
        cwd: this.options.overrides.cwd || this.options.root,
        detached: true,
        stdio: ['ignore', out, out],
        windowsHide: false
      });
    } finally {
      fs.closeSync(out);
    }
    mc.unref();
    tail = tailFile(logFile, (line) => this.emit('data', line + '\n'));
    mc.on('error', (e) => this.emit('debug', '[Visual Launcher]: ' + e.message));
    return mc;
  };

  launcher.on('debug', (line) => send('launch:log', String(line)));
  launcher.on('data', (line) => { send('launch:log', String(line)); presence.feed(line); });
  launcher.on('progress', (e) => progress({ stage: stageName(e.type), current: e.task, total: e.total }));
  launcher.on('download-status', () => {});

  progress({ stage: 'Spieldateien werden geprüft', current: 0, total: 1 });
  const child = await launcher.launch(opts);
  if (!child) throw new Error('Minecraft konnte nicht gestartet werden. Details stehen im Protokoll.');
  running = child;
  instances.update(inst.id, { lastPlayed: Date.now() });
  send('launch:started', { instanceId: inst.id });
  presence.gameStarted(inst);

  const play = trackPlayTime(inst.id, child.pid, logFile, Date.now(), send);
  const timer = setInterval(() => { play.flush(); send('instances:changed', { instanceId: inst.id }); }, 60000);
  child.on('exit', (code) => {
    clearInterval(timer);
    if (tail) tail.stop();
    play.finish(Date.now());
    running = null;
    presence.gameClosed();
    send('launch:closed', { code });
  });
  return true;
}

/**
 * Launcher start: a game that was still running when the launcher was closed. If it still
 * runs, play time keeps being counted; otherwise the time until its log was last written is added.
 */
function resumeDetached(send) {
  const r = store.readJson(RUNNING_FILE, null);
  if (!r || !r.instanceId || !r.pid) return;
  const lastWrite = () => { try { return fs.statSync(r.logFile).mtimeMs; } catch { return r.lastFlush; } };
  const play = trackPlayTime(r.instanceId, r.pid, r.logFile, r.lastFlush, send);
  if (!alive(r.pid)) {
    play.finish(Math.min(Date.now(), Math.max(r.lastFlush, lastWrite())));
    return;
  }
  running = { pid: r.pid, kill: () => { try { process.kill(r.pid); } catch { /* gone */ } } };
  const timer = setInterval(() => {
    if (alive(r.pid)) { play.flush(); send('instances:changed', { instanceId: r.instanceId }); return; }
    clearInterval(timer);
    play.finish(Math.min(Date.now(), Math.max(r.lastFlush, lastWrite())));
    running = null;
    send('launch:closed', { code: 0 });
  }, 15000);
}

function stageName(type) {
  const names = {
    assets: 'Assets', 'assets-copy': 'Assets', natives: 'Native Bibliotheken',
    classes: 'Bibliotheken', 'classes-custom': 'Fabric-Bibliotheken', 'classes-maven-custom': 'Fabric-Bibliotheken',
    'version-jar': 'Minecraft'
  };
  return (names[type] || 'Dateien') + ' werden geladen';
}

function kill() {
  if (running) running.kill();
}

module.exports = { launch, isRunning, kill, resumeDetached };
