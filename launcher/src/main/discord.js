// Discord Rich Presence over Discord's local IPC socket.
// Talks only to the Discord desktop app on this computer - no network requests.
// Protocol: frames of [opcode int32 LE][length int32 LE][JSON], op 0 = handshake, op 1 = frame.
const net = require('net');
const fs = require('fs');
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

// ---------------------------------------------------------------- game state from Visual Client (server, world, biome)

/**
 * Visual Client's "Discord Status" module writes <game dir>/visualclient/presence.json about once a second:
 * {"state":"multiplayer","server":"mc.hypixel.net","world":"","dimension":"minecraft:overworld","biome":"minecraft:cherry_grove","time":...}
 * It is a local file - nothing is sent anywhere. Without the mod the launcher falls back to the log lines above.
 */
class ModState {
  constructor() { this.reset(); }
  reset() { this.data = null; this.mtime = 0; }
  /** @returns true when the content changed */
  read(file) {
    let st;
    try { st = fs.statSync(file); } catch { const had = !!this.data; this.reset(); return had; }
    if (st.mtimeMs === this.mtime) return false;
    this.mtime = st.mtimeMs;
    try {
      const d = JSON.parse(fs.readFileSync(file, 'utf8'));
      if (!d || typeof d !== 'object') return false;
      const before = JSON.stringify(this.data);
      this.data = {
        state: ['menu', 'singleplayer', 'multiplayer'].includes(d.state) ? d.state : 'menu',
        server: String(d.server || '').slice(0, 100),
        world: String(d.world || '').slice(0, 60),
        dimension: String(d.dimension || ''),
        biome: String(d.biome || ''),
        time: Number(d.time) || 0
      };
      return before !== JSON.stringify(this.data);
    } catch { return false; }
  }
  /** Data older than a minute is stale (game frozen, mod switched off). */
  fresh() { return !!this.data && Date.now() - this.data.time < 60000; }
}

/** "minecraft:cherry_grove" -> "Cherry Grove" */
function prettyId(id) {
  const name = String(id || '').split(':').pop().replace(/^the_/, '');
  return name.split('_').filter(Boolean).map((w) => w[0].toUpperCase() + w.slice(1)).join(' ');
}

function dimensionName(dim, de) {
  if (/the_nether/.test(dim)) return 'Nether';
  if (/the_end/.test(dim)) return 'The End';
  if (/overworld/.test(dim)) return de ? 'Oberwelt' : 'Overworld';
  return prettyId(dim);
}

// biome -> picture on the website (https://visualmod.vercel.app/rpc/biomes/<name>.png), first match wins
const BIOME_ICONS = [
  [/cherry/, 'cherry'], [/pale_garden/, 'pale'], [/mushroom/, 'mushroom'], [/deep_dark/, 'deepdark'], [/lush_caves/, 'lush'],
  [/dripstone|caves?$/, 'cave'], [/badlands/, 'badlands'], [/desert/, 'desert'], [/savanna/, 'savanna'], [/jungle|bamboo/, 'jungle'],
  [/swamp|mangrove/, 'swamp'], [/frozen|snowy|ice|grove|jagged|frozen_peaks|snow/, 'snow'], [/taiga/, 'taiga'],
  [/ocean/, 'ocean'], [/beach|river|shore/, 'beach'], [/peaks|windswept|stony|hills|meadow/, 'mountain'],
  [/forest|birch|flower/, 'forest'], [/plains|sunflower/, 'plains']
];
const SITE = 'https://visualmod.vercel.app/rpc/';

function biomeIcon(biome, dim) {
  if (/the_nether/.test(dim)) return 'nether';
  if (/the_end/.test(dim)) return 'end';
  const id = String(biome || '').split(':').pop();
  for (const [re, name] of BIOME_ICONS) if (re.test(id)) return name;
  return 'plains';
}

// ---------------------------------------------------------------- presence controller

// Application ID of the "Visual Launcher" Discord app (Discord shows its name as "Playing ...").
// A personal ID from the launcher settings wins over this one.
const DEFAULT_CLIENT_ID = '';

const rpc = new DiscordRpc();
const game = new GameState();
const mod = new ModState();
let session = null; // { instance, mcVersion, start, file }
let timer = null;
let getSettings = () => ({ enabled: false });

function german() {
  try { return require('./store').getSettings().language === 'de'; } catch { return false; }
}

function clientId(s) {
  const id = (s.clientId || DEFAULT_CLIENT_ID || '').trim();
  return /^\d{15,25}$/.test(id) ? id : '';
}

function activity() {
  if (!session) return null;
  const s = getSettings();
  const de = german();
  const live = mod.fresh() ? mod.data : null;
  const mode = live ? live.state : game.mode;
  const server = (live && live.server) || game.server;
  let details;
  if (mode === 'multiplayer') {
    details = s.showServer && server ? (de ? `Spielt auf ${server}` : `Playing on ${server}`) : (de ? 'Spielt Mehrspieler' : 'Playing multiplayer');
  } else if (mode === 'singleplayer') {
    const world = live && live.world && s.showWorld !== false ? ` · ${live.world}` : '';
    details = (de ? 'Einzelspieler' : 'Singleplayer') + world;
  } else {
    details = de ? 'Im Hauptmenü' : 'In the main menu';
  }
  let state = `Minecraft ${session.mcVersion} · ${session.instance}`;
  const assets = { large_image: SITE + 'logo.png', large_text: `Visual Launcher · Minecraft ${session.mcVersion}` };
  if (live && mode !== 'menu' && live.dimension) {
    const dim = dimensionName(live.dimension, de);
    if (live.biome && s.showBiome !== false) {
      const biome = prettyId(live.biome);
      state = `${biome} · ${dim}`;
      assets.small_image = SITE + 'biomes/' + biomeIcon(live.biome, live.dimension) + '.png';
      assets.small_text = (de ? 'Biom: ' : 'Biome: ') + biome;
    } else {
      state = dim;
    }
  }
  return {
    details: details.slice(0, 128),
    state: state.slice(0, 128),
    timestamps: { start: session.start },
    assets,
    buttons: [{ label: 'Visual Launcher', url: 'https://visualmod.vercel.app' }],
    instance: false
  };
}

async function refresh() {
  const s = getSettings();
  const id = clientId(s);
  if (!s.enabled || !id) { rpc.disconnect(); return { connected: false, missingId: !id }; }
  if (!session) { if (rpc.ready) rpc.setActivity(null); return { connected: rpc.ready }; }
  const ok = await rpc.connect(id).catch(() => false);
  if (ok) rpc.setActivity(activity());
  return { connected: !!ok };
}

function poll() {
  if (!session || !session.file) return;
  const wasFresh = mod.fresh();
  if (mod.read(session.file) || wasFresh !== mod.fresh()) refresh();
}

const presence = {
  configure(settingsGetter) { getSettings = settingsGetter; },
  gameStarted(inst, gameDir) {
    game.reset();
    mod.reset();
    session = {
      instance: inst.name, mcVersion: inst.mcVersion, start: Date.now(),
      file: gameDir ? path.join(gameDir, 'visualclient', 'presence.json') : null
    };
    // a file from the last session must not count
    if (session.file) { try { fs.rmSync(session.file, { force: true }); } catch { /* ignore */ } }
    clearInterval(timer);
    timer = setInterval(poll, 2000);
    refresh();
  },
  feed(text) {
    if (!session) return;
    let changed = false;
    for (const line of String(text).split(/\r?\n/)) if (line && game.feed(line)) changed = true;
    if (changed && !mod.fresh()) refresh();
  },
  gameClosed() {
    clearInterval(timer);
    timer = null;
    session = null;
    game.reset();
    mod.reset();
    if (rpc.ready) rpc.setActivity(null);
    rpc.disconnect();
  },
  /** Called when settings change: applies on/off immediately. */
  refresh,
  status() {
    const live = mod.fresh() ? mod.data : null;
    return {
      connected: rpc.ready, playing: !!session, mode: live ? live.state : game.mode,
      server: (live && live.server) || game.server, biome: live ? live.biome : '', hasId: !!clientId(getSettings())
    };
  }
};

module.exports = { DiscordRpc, GameState, ModState, prettyId, biomeIcon, presence };
