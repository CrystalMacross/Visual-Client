// Visual Launcher - main process.
// Privacy: the launcher has no telemetry, analytics, crash reporting or own server.
// It only talks to Microsoft/Xbox/Mojang (login + game files), Fabric and Modrinth.
const fs = require('fs');
const path = require('path');
const { app, BrowserWindow, ipcMain, dialog, shell, nativeTheme, nativeImage } = require('electron');

const store = require('./src/main/store');
const auth = require('./src/main/auth');
const instances = require('./src/main/instances');
const versions = require('./src/main/versions');
const modrinth = require('./src/main/modrinth');
const launcher = require('./src/main/launch');
const { presence } = require('./src/main/discord');

const LEGAL_VERSION = '2026-11-21';
const ALLOWED_EXTERNAL = ['modrinth.com', 'discord.com', 'github.com', 'www.minecraft.net', 'minecraft.net', 'account.microsoft.com'];

let win = null;

app.setName('Visual Launcher');
app.commandLine.appendSwitch('disable-features', 'SpareRendererForSitePerProcess');

function createWindow() {
  nativeTheme.themeSource = 'dark';
  win = new BrowserWindow({
    width: 1240,
    height: 780,
    minWidth: 980,
    minHeight: 640,
    frame: false,
    titleBarStyle: process.platform === 'darwin' ? 'hiddenInset' : 'hidden',
    backgroundColor: '#0b0b14',
    show: false,
    icon: path.join(__dirname, 'build', 'icon.png'),
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      spellcheck: false
    }
  });
  win.removeMenu();
  win.loadFile(path.join(__dirname, 'renderer', 'index.html'));
  win.once('ready-to-show', () => win.show());

  // never navigate away from the app; open allowed links in the system browser
  win.webContents.setWindowOpenHandler(({ url }) => {
    openExternal(url);
    return { action: 'deny' };
  });
  win.webContents.on('will-navigate', (e) => e.preventDefault());
}

function openExternal(url) {
  try {
    const u = new URL(url);
    if (u.protocol === 'https:' && ALLOWED_EXTERNAL.some((h) => u.hostname === h || u.hostname.endsWith('.' + h))) {
      shell.openExternal(u.toString());
    }
  } catch { /* ignore invalid urls */ }
}

function send(channel, payload) {
  if (win && !win.isDestroyed()) win.webContents.send(channel, payload);
}

/** Wraps handlers so errors reach the UI as readable messages. */
function handle(channel, fn) {
  ipcMain.handle(channel, async (_e, ...args) => {
    try {
      return { ok: true, data: await fn(...args) };
    } catch (err) {
      return { ok: false, error: (err && err.message) || String(err), code: err && err.code };
    }
  });
}

// ---------------------------------------------------------------- background image

const BG_DIR = () => path.join(store.dataDir(), 'backgrounds');
const IMAGE_TYPES = { '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.webp': 'image/webp', '.gif': 'image/gif', '.bmp': 'image/bmp' };

function backgroundDataUrl(name) {
  if (!name) return null;
  const file = path.join(BG_DIR(), path.basename(name));
  if (!fs.existsSync(file)) return null;
  const type = IMAGE_TYPES[path.extname(file).toLowerCase()] || 'image/png';
  return `data:${type};base64,${fs.readFileSync(file).toString('base64')}`;
}

function storeBackground(source) {
  const ext = path.extname(source).toLowerCase();
  if (!IMAGE_TYPES[ext]) throw new Error('Bitte wähle ein Bild (PNG, JPG, WEBP, GIF oder BMP).');
  if (fs.statSync(source).size > 25 * 1024 * 1024) throw new Error('Das Bild ist größer als 25 MB.');
  fs.mkdirSync(BG_DIR(), { recursive: true });
  for (const f of fs.readdirSync(BG_DIR())) fs.rmSync(path.join(BG_DIR(), f), { force: true });
  const name = `background-${Date.now()}${ext}`;
  fs.copyFileSync(source, path.join(BG_DIR(), name));
  store.saveSettings({ design: { mode: 'image', image: name } });
  return backgroundDataUrl(name);
}

// ---------------------------------------------------------------- instance profile pictures

/** Loads an image, crops it to a centred square and scales it to 256x256 PNG. */
function squarePng(file) {
  const ext = path.extname(file).toLowerCase();
  if (!IMAGE_TYPES[ext]) throw new Error('Bitte wähle ein Bild (PNG, JPG, WEBP, GIF oder BMP).');
  if (fs.statSync(file).size > 25 * 1024 * 1024) throw new Error('Das Bild ist größer als 25 MB.');
  let img = nativeImage.createFromPath(file);
  if (img.isEmpty()) throw new Error('Das Bild konnte nicht gelesen werden.');
  const { width, height } = img.getSize();
  const side = Math.min(width, height);
  img = img.crop({ x: Math.floor((width - side) / 2), y: Math.floor((height - side) / 2), width: side, height: side });
  if (side > 256) img = img.resize({ width: 256, height: 256, quality: 'best' });
  return img.toPNG();
}

async function chooseProfilePicture(id, filePath) {
  let file = filePath;
  if (!file) {
    const res = await dialog.showOpenDialog(win, {
      title: 'Profilbild für die Instanz wählen',
      properties: ['openFile'],
      filters: [{ name: 'Bilder', extensions: ['png', 'jpg', 'jpeg', 'webp', 'gif', 'bmp'] }]
    });
    if (res.canceled || !res.filePaths[0]) return null;
    file = res.filePaths[0];
  }
  return instances.setProfilePicture(id, squarePng(String(file)));
}

// ---------------------------------------------------------------- IPC

function registerIpc() {
  // window controls
  ipcMain.on('win:minimize', () => win && win.minimize());
  ipcMain.on('win:maximize', () => win && (win.isMaximized() ? win.unmaximize() : win.maximize()));
  ipcMain.on('win:close', () => win && win.close());
  ipcMain.on('open:external', (_e, url) => openExternal(url));

  handle('app:info', () => ({
    version: app.getVersion(),
    platform: process.platform,
    legalVersion: LEGAL_VERSION,
    visualClient: versions.bundledVisualClient() ? versions.bundledVisualClient().version : null
  }));
  handle('legal:get', () => {
    const read = (f) => fs.readFileSync(path.join(__dirname, 'legal', f), 'utf8');
    return { tos: read('TERMS_OF_SERVICE.md'), tou: read('TERMS_OF_USE.md'), privacy: read('PRIVACY.md') };
  });
  handle('legal:accept', () => store.saveSettings({ legalAccepted: LEGAL_VERSION }));

  handle('settings:get', () => {
    const s = store.getSettings();
    return { ...s, backgroundImage: s.design.mode === 'image' ? backgroundDataUrl(s.design.image) : null };
  });
  handle('settings:save', (patch) => {
    const clean = { ...patch };
    delete clean.legalAccepted;
    store.saveSettings(clean);
    if (clean.discord) presence.refresh();
    return true;
  });
  handle('discord:status', () => presence.status());
  handle('background:choose', async () => {
    const res = await dialog.showOpenDialog(win, {
      title: 'Hintergrundbild wählen',
      properties: ['openFile'],
      filters: [{ name: 'Bilder', extensions: ['png', 'jpg', 'jpeg', 'webp', 'gif', 'bmp'] }]
    });
    if (res.canceled || !res.filePaths[0]) return null;
    return storeBackground(res.filePaths[0]);
  });
  handle('background:set', (filePath) => storeBackground(String(filePath)));

  handle('auth:list', () => ({ accounts: auth.list(), selected: store.getSettings().selectedAccount }));
  handle('auth:login', () => auth.login());
  handle('auth:logout', (uuid) => auth.logout(uuid));
  handle('auth:select', (uuid) => store.saveSettings({ selectedAccount: uuid }));

  handle('versions:list', () => versions.listVersions());

  handle('instances:list', () => instances.list());
  handle('instances:create', (opts) => {
    const inst = instances.create(opts);
    if (inst.visualClient && versions.visualClientSupports(inst.mcVersion)) versions.installVisualClient(instances.instanceDir(inst.id));
    return inst;
  });
  handle('instances:update', (id, patch) => instances.update(id, patch));
  handle('instances:remove', (id) => instances.remove(id));
  handle('instances:open', (id, sub) => instances.openFolder(id, sub));
  handle('instances:content', (id) => instances.content(id));
  handle('instances:toggle', (id, type, file) => instances.toggleContent(id, type, file));
  handle('instances:removeContent', (id, type, file) => instances.removeContent(id, type, file));
  handle('instances:select', (id) => store.saveSettings({ selectedInstance: id }));
  handle('instances:chooseIcon', (id, filePath) => chooseProfilePicture(id, filePath));
  handle('instances:clearIcon', (id) => instances.clearProfilePicture(id));

  handle('modrinth:search', (q) => modrinth.search(q));
  handle('modrinth:install', (o) => modrinth.install(o));
  handle('modrinth:modpack', (o) => modrinth.installModpack(o, (p) => send('install:progress', p)));


  handle('game:launch', async (id) => {
    await launcher.launch(id, send);
    if (store.getSettings().game.closeLauncher && win) win.minimize();
    return true;
  });
  handle('game:kill', () => launcher.kill());
  handle('game:running', () => launcher.isRunning());
}

const single = app.requestSingleInstanceLock();
if (!single) {
  app.quit();
} else {
  app.on('second-instance', () => {
    if (win) { if (win.isMinimized()) win.restore(); win.focus(); }
  });
  app.whenReady().then(() => {
    presence.configure(() => store.getSettings().discord);
    registerIpc();
    createWindow();
    app.on('activate', () => { if (BrowserWindow.getAllWindows().length === 0) createWindow(); });
  });
  app.on('window-all-closed', () => { if (process.platform !== 'darwin') app.quit(); });
}
