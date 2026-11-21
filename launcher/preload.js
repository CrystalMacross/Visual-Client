// Exposes a small, explicit API to the UI. The UI has no Node.js access.
const { contextBridge, ipcRenderer, webUtils } = require('electron');

const call = (channel) => (...args) => ipcRenderer.invoke(channel, ...args);
const on = (channel) => (cb) => {
  const fn = (_e, payload) => cb(payload);
  ipcRenderer.on(channel, fn);
  return () => ipcRenderer.removeListener(channel, fn);
};

contextBridge.exposeInMainWorld('vl', {
  win: {
    minimize: () => ipcRenderer.send('win:minimize'),
    maximize: () => ipcRenderer.send('win:maximize'),
    close: () => ipcRenderer.send('win:close')
  },
  openExternal: (url) => ipcRenderer.send('open:external', url),
  pathForFile: (file) => webUtils.getPathForFile(file),
  app: { info: call('app:info') },
  legal: { get: call('legal:get'), accept: call('legal:accept') },
  settings: { get: call('settings:get'), save: call('settings:save') },
  background: { choose: call('background:choose'), set: call('background:set') },
  auth: { list: call('auth:list'), login: call('auth:login'), logout: call('auth:logout'), select: call('auth:select') },
  versions: { list: call('versions:list') },
  instances: {
    list: call('instances:list'), create: call('instances:create'), update: call('instances:update'),
    remove: call('instances:remove'), open: call('instances:open'), content: call('instances:content'),
    toggle: call('instances:toggle'), removeContent: call('instances:removeContent'), select: call('instances:select')
  },
  modrinth: { search: call('modrinth:search'), install: call('modrinth:install'), modpack: call('modrinth:modpack') },
  game: { launch: call('game:launch'), kill: call('game:kill'), running: call('game:running') },
  events: {
    launchProgress: on('launch:progress'),
    launchLog: on('launch:log'),
    launchStarted: on('launch:started'),
    launchClosed: on('launch:closed'),
    installProgress: on('install:progress'),
    instancesChanged: on('instances:changed')
  }
});
