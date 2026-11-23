// Discord Rich Presence over Discord's local IPC socket.
// Talks only to the Discord desktop app on this computer - no network requests.
// Protocol: frames of [opcode int32 LE][length int32 LE][JSON], op 0 = handshake, op 1 = frame.
const net = require('net');
const path = require('path');
const crypto = require('crypto');

const OP_HANDSHAKE = 0;
const OP_FRAME = 1;
const OP_CLOSE = 2;

function socketPaths() {
  if (process.platform === 'win32') {
    return Array.from({ length: 10 }, (_, i) => `\\\\?\\pipe\\discord-ipc-${i}`);
  }
  const { XDG_RUNTIME_DIR, TMPDIR, TMP, TEMP } = process.env;
  const bases = [XDG_RUNTIME_DIR, TMPDIR, TMP, TEMP, '/tmp'].filter(Boolean);
  const subdirs = ['', 'app/com.discordapp.Discord', 'snap.discord', 'snap.discord-canary', '.flatpak/com.discordapp.Discord/xdg-run'];
  const out = [];
  for (const base of bases) for (const sub of subdirs) for (let i = 0; i < 10; i++) out.push(path.join(base, sub, `discord-ipc-${i}`));
  return [...new Set(out)];
}

function encode(op, data) {
  const json = Buffer.from(JSON.stringify(data), 'utf8');
  const header = Buffer.alloc(8);
  header.writeInt32LE(op, 0);
  header.writeInt32LE(json.length, 4);
  return Buffer.concat([header, json]);
}

class DiscordRpc {
  constructor() {
    this.socket = null;
    this.clientId = null;
    this.ready = false;
    this.pending = null; // last activity requested while connecting
    this.connecting = null;
  }

  async connect(clientId) {
    if (this.ready && this.clientId === clientId) return true;
    if (this.connecting) return this.connecting;
    this.disconnect();
    this.clientId = clientId;
    this.connecting = (async () => {
      for (const p of socketPaths()) {
        try {
          await this._open(p, clientId);
          return true;
        } catch { /* try next pipe */ }
      }
      return false;
    })();
    try { return await this.connecting; } finally { this.connecting = null; }
  }

  _open(p, clientId) {
    return new Promise((resolve, reject) => {
      const sock = net.createConnection(p);
      let buf = Buffer.alloc(0);
      const timer = setTimeout(() => { sock.destroy(); reject(new Error('timeout')); }, 3000);
      sock.once('error', (e) => { clearTimeout(timer); reject(e); });
      sock.on('connect', () => sock.write(encode(OP_HANDSHAKE, { v: 1, client_id: clientId })));
      sock.on('data', (chunk) => {
        buf = Buffer.concat([buf, chunk]);
        while (buf.length >= 8) {
          const op = buf.readInt32LE(0);
          const len = buf.readInt32LE(4);
          if (buf.length < 8 + len) break;
          let msg = {};
          try { msg = JSON.parse(buf.subarray(8, 8 + len).toString('utf8')); } catch { /* ignore */ }
          buf = buf.subarray(8 + len);
          if (op === OP_CLOSE) { sock.destroy(); continue; }
          if (!this.ready && msg.evt === 'READY') {
            clearTimeout(timer);
            this.socket = sock;
            this.ready = true;
            sock.removeAllListeners('error');
            sock.on('error', () => this._lost());
            sock.on('close', () => this._lost());
            resolve();
            if (this.pending !== null) { const a = this.pending; this.pending = null; this.setActivity(a); }
          } else if (!this.ready && msg.evt === 'ERROR') {
            clearTimeout(timer);
            sock.destroy();
            reject(new Error(msg.data && msg.data.message));
          }
        }
      });
    });
  }

  _lost() {
    this.ready = false;
    this.socket = null;
  }

  /** activity = null clears the presence. */
  setActivity(activity) {
    if (!this.ready || !this.socket) { this.pending = activity; return false; }
    this.socket.write(encode(OP_FRAME, {
      cmd: 'SET_ACTIVITY',
      args: { pid: process.pid, activity: activity || undefined },
      nonce: crypto.randomUUID()
    }));
    return true;
  }

  disconnect() {
    if (this.socket) {
      try { this.socket.write(encode(OP_CLOSE, {})); } catch { /* ignore */ }
      this.socket.destroy();
    }
    this._lost();
    this.pending = null;
  }
}

// ---------------------------------------------------------------- game state from the Minecraft log

/** Reads what the player is doing from Minecraft's log lines (no mod needed). */
class GameState {
  constructor() {
    this.reset();
  }

  reset() {
    this.mode = 'menu'; // menu | singleplayer | multiplayer
    this.server = null;
  }

  /** @returns true when the state changed */
  feed(line) {
    if (/\[CHAT\]|\[System\]|<[^>]+>/.test(line)) return false; // never react to chat text
    const before = this.mode + '|' + this.server;
    let m;
    if ((m = /Connecting to ([^\s,]+),\s*(\d+)/.exec(line))) {
      const port = Number(m[2]);
      this.mode = 'multiplayer';
      this.server = port && port !== 25565 ? `${m[1]}:${port}` : m[1];
    } else if (/Starting integrated minecraft server/i.test(line)) {
      this.mode = 'singleplayer';
      this.server = null;
    } else if (this.mode === 'singleplayer' && /\[Server thread\/INFO\].*Stopping server/i.test(line)) {
      this.reset();
    } else if (this.mode === 'multiplayer' && /Client disconnected|Disconnected from server|Lost connection|disconnect\.(lost|kicked|genericReason)/i.test(line)) {
      this.reset();
    }
    return before !== this.mode + '|' + this.server;
  }
}

// ---------------------------------------------------------------- presence controller

const rpc = new DiscordRpc();
const game = new GameState();
let session = null; // { instance, mcVersion, start }
let getSettings = () => ({ enabled: false });

function mainMenuText() {
  try { return require('./store').getSettings().language === 'de' ? 'Im Hauptmenü' : 'In the main menu'; } catch { return 'In the main menu'; }
}

function activity() {
  if (!session) return null;
  const s = getSettings();
  let details;
  if (game.mode === 'multiplayer') details = s.showServer && game.server ? `Playing: ${game.server}` : 'Playing: Multiplayer';
  else if (game.mode === 'singleplayer') details = 'Playing: Singleplayer';
  else details = mainMenuText();
  return {
    details: details.slice(0, 128),
    state: `Minecraft ${session.mcVersion} · ${session.instance}`.slice(0, 128),
    timestamps: { start: session.start },
    assets: { large_image: 'logo', large_text: 'Visual Launcher', small_image: 'minecraft', small_text: `Minecraft ${session.mcVersion}` },
    instance: false
  };
}

async function refresh() {
  const s = getSettings();
  if (!s.enabled || !s.clientId || !/^\d{15,25}$/.test(s.clientId)) { rpc.disconnect(); return { connected: false }; }
  if (!session) { if (rpc.ready) rpc.setActivity(null); return { connected: rpc.ready }; }
  const ok = await rpc.connect(s.clientId).catch(() => false);
  if (ok) rpc.setActivity(activity());
  return { connected: !!ok };
}

const presence = {
  configure(settingsGetter) { getSettings = settingsGetter; },
  gameStarted(inst) {
    game.reset();
    session = { instance: inst.name, mcVersion: inst.mcVersion, start: Date.now() };
    refresh();
  },
  feed(text) {
    if (!session) return;
    let changed = false;
    for (const line of String(text).split(/\r?\n/)) if (line && game.feed(line)) changed = true;
    if (changed) refresh();
  },
  gameClosed() {
    session = null;
    game.reset();
    if (rpc.ready) rpc.setActivity(null);
    rpc.disconnect();
  },
  /** Called when settings change: applies on/off immediately. */
  refresh,
  status() {
    return { connected: rpc.ready, playing: !!session, mode: game.mode, server: game.server };
  }
};

module.exports = { DiscordRpc, GameState, presence };
