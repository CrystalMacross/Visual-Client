// Starts Minecraft with Fabric (+ Visual Client) through minecraft-launcher-core.
const fs = require('fs');
const path = require('path');
const { Client } = require('minecraft-launcher-core');
const store = require('./store');
const auth = require('./auth');
const instances = require('./instances');
const versions = require('./versions');
const { ensureJava } = require('./java');

let running = null;

function isRunning() {
  return !!running;
}

async function launch(instanceId, send) {
  if (running) throw new Error('Minecraft läuft bereits.');
  const settings = store.getSettings();
  const inst = instances.read(instanceId);
  const root = instances.sharedRoot();
  const gameDir = instances.instanceDir(inst.id);
  const progress = (p) => send('launch:progress', p);

  progress({ stage: 'Anmeldung wird geprüft', current: 0, total: 1 });
  const authorization = await auth.launchAuth(settings.selectedAccount);

  progress({ stage: 'Fabric wird installiert', current: 0, total: 1 });
  const fabric = await versions.installFabric(root, inst.mcVersion, inst.loaderVersion);
  if (!inst.loaderVersion) instances.update(inst.id, { loaderVersion: fabric.loader });

  if (inst.visualClient && versions.visualClientSupports(inst.mcVersion)) {
    progress({ stage: 'Visual Client wird eingebunden', current: 0, total: 1 });
    versions.installVisualClient(gameDir);
    await versions.ensureFabricApi(gameDir, inst.mcVersion);
  }

  let javaPath = settings.java.customPath && fs.existsSync(settings.java.customPath) ? settings.java.customPath : null;
  if (!javaPath) {
    const vjson = await versions.versionJson(inst.mcVersion);
    const component = (vjson.javaVersion && vjson.javaVersion.component) || 'java-runtime-delta';
    javaPath = await ensureJava(root, component, progress);
  }

  const launcher = new Client();
  const opts = {
    root,
    authorization,
    version: { number: inst.mcVersion, type: 'release', custom: fabric.id },
    memory: { max: `${settings.java.maxMemory}M`, min: `${settings.java.minMemory}M` },
    javaPath,
    customArgs: settings.java.jvmArgs ? settings.java.jvmArgs.split(/\s+/).filter(Boolean) : [],
    window: settings.game.fullscreen ? { fullscreen: true } : { width: settings.game.width, height: settings.game.height },
    overrides: { gameDirectory: gameDir, detached: false, maxSockets: 8 }
  };

  launcher.on('debug', (line) => send('launch:log', String(line)));
  launcher.on('data', (line) => send('launch:log', String(line)));
  launcher.on('progress', (e) => progress({ stage: stageName(e.type), current: e.task, total: e.total }));
  launcher.on('download-status', () => {});

  progress({ stage: 'Spieldateien werden geprüft', current: 0, total: 1 });
  const child = await launcher.launch(opts);
  if (!child) throw new Error('Minecraft konnte nicht gestartet werden. Details stehen im Protokoll.');
  running = child;
  instances.update(inst.id, { lastPlayed: Date.now() });
  send('launch:started', { instanceId: inst.id });
  child.on('close', (code) => {
    running = null;
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
