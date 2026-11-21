// Starts Minecraft with Fabric (+ Visual Client) through minecraft-launcher-core.
const fs = require('fs');
const path = require('path');
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
    overrides: { gameDirectory: gameDir, detached: false, maxSockets: 8 }
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

  // play time: flushed every minute so it survives a closed launcher or a crash
  let lastFlush = Date.now();
  const flush = () => {
    const now = Date.now();
    try { instances.addPlayTime(inst.id, now - lastFlush); } catch { /* instance deleted */ }
    lastFlush = now;
  };
  const timer = setInterval(() => { flush(); send('instances:changed', { instanceId: inst.id }); }, 60000);
  child.on('close', (code) => {
    clearInterval(timer);
    flush();
    running = null;
    presence.gameClosed();
    send('launch:closed', { code });
  });
  return true;
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

module.exports = { launch, isRunning, kill };
