/* Visual Launcher UI. Talks to the main process only through window.vl (see preload.js). */
'use strict';

const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];

const state = {
  info: null,
  settings: null,
  accounts: [],
  selectedAccount: null,
  instances: [],
  selectedInstance: null,
  versions: [],
  running: false,
  launching: false,
  legal: null
};

// ------------------------------------------------------------------ helpers

async function api(promise) {
  const res = await promise;
  if (!res.ok) {
    const err = new Error(res.error);
    err.code = res.code;
    throw err;
  }
  return res.data;
}

function esc(s) {
  return String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

function h(html) {
  const t = document.createElement('template');
  t.innerHTML = html.trim();
  return t.content.firstElementChild;
}

function toast(msg, type = '') {
  const el = h(`<div class="toast ${type}">${esc(msg)}</div>`);
  $('#toasts').append(el);
  setTimeout(() => { el.style.transition = 'opacity .3s'; el.style.opacity = '0'; setTimeout(() => el.remove(), 300); }, type === 'error' ? 7000 : 3500);
}

function fmtNum(n) {
  if (n >= 1e6) return (n / 1e6).toFixed(1).replace('.0', '') + ' Mio.';
  if (n >= 1e3) return (n / 1e3).toFixed(1).replace('.0', '') + ' Tsd.';
  return String(n || 0);
}

function fmtSize(b) {
  if (!b) return '';
  return b > 1048576 ? (b / 1048576).toFixed(1) + ' MB' : Math.max(1, Math.round(b / 1024)) + ' KB';
}

const HEX = /^#[0-9a-fA-F]{6}$/;
function normHex(v) {
  let s = String(v || '').trim();
  if (!s.startsWith('#')) s = '#' + s;
  if (/^#[0-9a-fA-F]{3}$/.test(s)) s = '#' + s.slice(1).split('').map((c) => c + c).join('');
  return HEX.test(s) ? s.toLowerCase() : null;
}
function hexToRgb(hex) {
  const n = parseInt(hex.slice(1), 16);
  return `${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255}`;
}

function initials(name) {
  return esc(String(name || '?').trim().charAt(0).toUpperCase() || '?');
}

/** Draws the face of a Minecraft skin (with hat layer) into a canvas. */
function skinHead(url, fallbackName) {
  const wrap = document.createElement('div');
  wrap.innerHTML = initials(fallbackName);
  if (!url) return wrap;
  const img = new Image();
  img.onload = () => {
    const c = document.createElement('canvas');
    c.width = 8; c.height = 8;
    const ctx = c.getContext('2d');
    ctx.imageSmoothingEnabled = false;
    ctx.drawImage(img, 8, 8, 8, 8, 0, 0, 8, 8);
    ctx.drawImage(img, 40, 8, 8, 8, 0, 0, 8, 8);
    wrap.replaceChildren(c);
  };
  img.src = url;
  return wrap;
}

function thumb(icon, name, cls = 'thumb') {
  return icon
    ? `<img class="${cls}" src="${esc(icon)}" alt="" loading="lazy" referrerpolicy="no-referrer">`
    : `<div class="${cls}">${initials(name)}</div>`;
}

/** Tiny, safe markdown renderer for the legal documents. */
function markdown(md) {
  const inline = (s) => esc(s)
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    .replace(/\[(.+?)\]\((https:\/\/[^)\s]+)\)/g, '<a href="#" data-ext="$2">$1</a>');
  const out = [];
  let list = false;
  for (const raw of md.split(/\r?\n/)) {
    const line = raw.trimEnd();
    const li = /^\s*[-*] (.*)$/.exec(line);
    if (li) {
      if (!list) { out.push('<ul>'); list = true; }
      out.push(`<li>${inline(li[1])}</li>`);
      continue;
    }
    if (list) { out.push('</ul>'); list = false; }
    if (!line.trim()) continue;
    const hm = /^(#{1,3}) (.*)$/.exec(line);
    if (hm) out.push(`<h${hm[1].length}>${inline(hm[2])}</h${hm[1].length}>`);
    else out.push(`<p>${inline(line)}</p>`);
  }
  if (list) out.push('</ul>');
  return out.join('');
}

document.addEventListener('click', (e) => {
  const a = e.target.closest('[data-ext]');
  if (a) { e.preventDefault(); window.vl.openExternal(a.dataset.ext); }
});

// ------------------------------------------------------------------ window + navigation

$$('[data-win]').forEach((b) => b.addEventListener('click', () => window.vl.win[b.dataset.win]()));

function showPage(name) {
  $$('.nav[data-page]').forEach((n) => n.classList.toggle('active', n.dataset.page === name));
  $$('.page').forEach((p) => p.classList.toggle('active', p.dataset.page === name));
  if (name === 'instances') renderInstances();
  if (name === 'settings') renderSettings();
  if (name === 'design') renderDesign();
  if (name === 'legal') renderLegal('#legal-doc', $('#legal-tabs .active').dataset.doc);
  if (name === 'modrinth') libraries.modrinth.open();
}
$$('.nav[data-page]').forEach((n) => n.addEventListener('click', () => showPage(n.dataset.page)));
$('#account-chip').addEventListener('click', () => showPage('settings'));

// ------------------------------------------------------------------ design / background

const PRESETS = [
  { color1: '#0f1020', color2: '#3b1d6e', accent: '#8b5cf6' },
  { color1: '#020617', color2: '#0e7490', accent: '#22d3ee' },
  { color1: '#1a0b12', color2: '#7f1d1d', accent: '#f43f5e' },
  { color1: '#03140c', color2: '#14532d', accent: '#22c55e' },
  { color1: '#1c1206', color2: '#9a3412', accent: '#f59e0b' },
  { color1: '#0b0b0f', color2: '#27272a', accent: '#e4e4e7' }
];

function applyDesign(d, imageUrl) {
  const root = document.documentElement.style;
  root.setProperty('--accent', d.accent);
  root.setProperty('--accent-rgb', hexToRgb(d.accent));
  root.setProperty('--bg1', d.color1);
  root.setProperty('--bg2', d.color2);
  const bg = $('#bg');
  const css = backgroundCss(d, imageUrl);
  bg.style.background = css;
  bg.style.backgroundSize = 'cover';
  bg.style.backgroundPosition = 'center';
  bg.style.filter = d.blur ? `blur(${d.blur}px)` : '';
  $('#bg-dim').style.opacity = String(d.dim / 100);
  const prev = $('#preview-bg');
  if (prev) {
    prev.style.background = css;
    prev.style.backgroundSize = 'cover';
    prev.style.backgroundPosition = 'center';
    prev.style.filter = d.blur ? `blur(${Math.max(0, d.blur / 2)}px) brightness(${1 - d.dim / 100})` : `brightness(${1 - d.dim / 100})`;
  }
}

function backgroundCss(d, imageUrl) {
  if (d.mode === 'image' && imageUrl) return `url("${imageUrl}")`;
  if (d.mode === 'solid') return d.color1;
  return `linear-gradient(${d.angle}deg, ${d.color1}, ${d.color2})`;
}

let saveTimer = null;
function saveDesign() {
  applyDesign(state.settings.design, state.settings.backgroundImage);
  clearTimeout(saveTimer);
  saveTimer = setTimeout(() => api(window.vl.settings.save({ design: state.settings.design })).catch((e) => toast(e.message, 'error')), 250);
}

function bindHex(textId, pickId, key) {
  const text = $('#' + textId);
  const pick = $('#' + pickId);
  text.addEventListener('input', () => {
    const v = normHex(text.value);
    text.classList.toggle('invalid', !v);
    if (v) { pick.value = v; state.settings.design[key] = v; saveDesign(); }
  });
  text.addEventListener('blur', () => { text.value = state.settings.design[key]; text.classList.remove('invalid'); });
  pick.addEventListener('input', () => { text.value = pick.value; text.classList.remove('invalid'); state.settings.design[key] = pick.value; saveDesign(); });
}
bindHex('c1', 'c1-pick', 'color1');
bindHex('c2', 'c2-pick', 'color2');
bindHex('accent', 'accent-pick', 'accent');

for (const [id, key, unit] of [['angle', 'angle', '°'], ['dim', 'dim', '%'], ['blur', 'blur', 'px']]) {
  $('#' + id).addEventListener('input', (e) => {
    state.settings.design[key] = Number(e.target.value);
    $('#' + id + '-val').textContent = e.target.value + unit;
    saveDesign();
  });
}

$$('#bg-mode button').forEach((b) => b.addEventListener('click', () => {
  state.settings.design.mode = b.dataset.mode;
  if (b.dataset.mode === 'image' && !state.settings.backgroundImage) {
    renderDesign();
    return;
  }
  saveDesign();
  renderDesign();
}));

async function chooseBackground(filePath) {
  try {
    const url = filePath ? await api(window.vl.background.set(filePath)) : await api(window.vl.background.choose());
    if (!url) return;
    state.settings.backgroundImage = url;
    state.settings.design.mode = 'image';
    applyDesign(state.settings.design, url);
    renderDesign();
    toast('Hintergrundbild gespeichert.', 'ok');
  } catch (e) { toast(e.message, 'error'); }
}
const drop = $('#drop');
drop.addEventListener('click', () => chooseBackground());
drop.addEventListener('dragover', (e) => { e.preventDefault(); drop.classList.add('over'); });
drop.addEventListener('dragleave', () => drop.classList.remove('over'));
drop.addEventListener('drop', (e) => {
  e.preventDefault();
  drop.classList.remove('over');
  const file = e.dataTransfer.files[0];
  if (file) chooseBackground(window.vl.pathForFile(file));
});
// dropping an image anywhere on the design page works too
document.addEventListener('dragover', (e) => e.preventDefault());
document.addEventListener('drop', (e) => {
  e.preventDefault();
  if (!$('.page[data-page=design]').classList.contains('active') || e.target.closest('#drop')) return;
  const file = e.dataTransfer.files[0];
  if (file && file.type.startsWith('image/')) chooseBackground(window.vl.pathForFile(file));
});

$('#design-reset').addEventListener('click', () => {
  Object.assign(state.settings.design, { mode: 'gradient', ...PRESETS[0], angle: 135, dim: 35, blur: 0 });
  saveDesign();
  renderDesign();
});

function renderDesign() {
  const d = state.settings.design;
  $$('#bg-mode button').forEach((b) => b.classList.toggle('active', b.dataset.mode === d.mode));
  $$('.page[data-page=design] [data-for]').forEach((f) => { f.hidden = !f.dataset.for.split(' ').includes(d.mode); });
  for (const [id, key] of [['c1', 'color1'], ['c2', 'color2'], ['accent', 'accent']]) {
    $('#' + id).value = d[key];
    $('#' + id + '-pick').value = d[key];
  }
  $('#angle').value = d.angle; $('#angle-val').textContent = d.angle + '°';
  $('#dim').value = d.dim; $('#dim-val').textContent = d.dim + '%';
  $('#blur').value = d.blur; $('#blur-val').textContent = d.blur + 'px';
  const presets = $('#presets');
  presets.replaceChildren(...PRESETS.map((p) => {
    const b = h(`<button class="preset" title="${p.accent}" style="background:linear-gradient(135deg, ${p.color1}, ${p.color2}); box-shadow: inset 0 -8px 0 ${p.accent}"></button>`);
    b.addEventListener('click', () => { Object.assign(d, p, { mode: d.mode === 'image' ? 'image' : 'gradient' }); saveDesign(); renderDesign(); });
    return b;
  }));
  applyDesign(d, state.settings.backgroundImage);
}

// ------------------------------------------------------------------ legal

async function loadLegal() {
  if (!state.legal) state.legal = await api(window.vl.legal.get());
  return state.legal;
}

async function renderLegal(target, doc) {
  const legal = await loadLegal();
  $(target).innerHTML = markdown(legal[doc]);
  $(target).scrollTop = 0;
}

function bindDocTabs(tabs, target) {
  $$(tabs + ' button').forEach((b) => b.addEventListener('click', () => {
    $$(tabs + ' button').forEach((x) => x.classList.toggle('active', x === b));
    renderLegal(target, b.dataset.doc);
  }));
}
bindDocTabs('#legal-tabs', '#legal-doc');
bindDocTabs('#gate-tabs', '#gate-doc');

$('#gate-check').addEventListener('change', (e) => { $('#gate-accept').disabled = !e.target.checked; });
$('#gate-accept').addEventListener('click', async () => {
  await api(window.vl.legal.accept());
  $('#gate-legal').hidden = true;
  checkLogin();
});

// ------------------------------------------------------------------ accounts

async function loadAccounts() {
  const res = await api(window.vl.auth.list());
  state.accounts = res.accounts;
  state.selectedAccount = res.selected && res.accounts.some((a) => a.uuid === res.selected) ? res.selected : (res.accounts[0] && res.accounts[0].uuid);
  renderAccountChip();
}

function currentAccount() {
  return state.accounts.find((a) => a.uuid === state.selectedAccount) || null;
}

function renderAccountChip() {
  const acc = currentAccount();
  const chip = $('#account-chip');
  chip.replaceChildren(acc ? skinHead(acc.skin, acc.name) : h('<svg viewBox="0 0 24 24"><circle cx="12" cy="8" r="4"/><path d="M4 21c0-4 4-6 8-6s8 2 8 6"/></svg>'));
  chip.title = acc ? acc.name : 'Nicht angemeldet';
}

function checkLogin() {
  $('#gate-login').hidden = state.accounts.length > 0;
}

async function doLogin(button) {
  const label = button.innerHTML;
  button.disabled = true;
  button.innerHTML = '<span class="ms-logo"></span>Warte auf Microsoft…';
  $('#login-error').hidden = true;
  try {
    const acc = await api(window.vl.auth.login());
    await loadAccounts();
    state.selectedAccount = acc.uuid;
    renderAccountChip();
    checkLogin();
    toast(`Angemeldet als ${acc.name}`, 'ok');
    renderSettings();
  } catch (e) {
    const msg = /closed|cancel|error\.gui\.closed/i.test(e.message) ? 'Anmeldung abgebrochen.' : e.message;
    if (!$('#gate-login').hidden) { $('#login-error').textContent = msg; $('#login-error').hidden = false; } else toast(msg, 'error');
  } finally {
    button.disabled = false;
    button.innerHTML = label;
  }
}
$('#login-btn').addEventListener('click', (e) => doLogin(e.currentTarget));
$('#add-account').addEventListener('click', (e) => doLogin(e.currentTarget));

// ------------------------------------------------------------------ settings page

function renderSettings() {
  const s = state.settings;
  const list = $('#accounts');
  list.replaceChildren(...state.accounts.map((a) => {
    const row = h(`<div class="account-row ${a.uuid === state.selectedAccount ? 'active' : ''}">
      <div class="avatar"></div><div class="name">${esc(a.name)}</div>
      ${a.uuid === state.selectedAccount ? '<span class="pill accent">Aktiv</span>' : ''}
      <button class="icon-btn" title="Abmelden"><svg viewBox="0 0 24 24"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4M16 17l5-5-5-5M21 12H9"/></svg></button></div>`);
    $('.avatar', row).append(skinHead(a.skin, a.name));
    row.addEventListener('click', async () => {
      await api(window.vl.auth.select(a.uuid));
      state.selectedAccount = a.uuid;
      renderAccountChip();
      renderSettings();
    });
    $('.icon-btn', row).addEventListener('click', async (e) => {
      e.stopPropagation();
      if (!confirm(`${a.name} abmelden?`)) return;
      await api(window.vl.auth.logout(a.uuid));
      await loadAccounts();
      renderSettings();
      checkLogin();
    });
    return row;
  }));
  $('#ram').value = s.java.maxMemory; $('#ram-val').textContent = (s.java.maxMemory / 1024).toFixed(1) + ' GB';
  $('#java-path').value = s.java.customPath || '';
  $('#jvm-args').value = s.java.jvmArgs || '';
  $('#fast-start').checked = s.java.fastStart !== false;
  $('#win-w').value = s.game.width;
  $('#win-h').value = s.game.height;
  $('#fullscreen').checked = s.game.fullscreen;
  $('#close-launcher').checked = s.game.closeLauncher;
  $('#discord-on').checked = s.discord.enabled;
  $('#discord-server').checked = s.discord.showServer;
  $('#discord-server').disabled = !s.discord.enabled;
  $('#discord-id').value = s.discord.clientId || '';
  updateDiscordState();
  $('#about').textContent = `Visual Launcher ${state.info.version} · Visual Client ${state.info.visualClient || '–'} eingebaut`;
  const current = document.body.dataset.ui || 'old';
  $$('#ui-mode button').forEach((b) => b.classList.toggle('active', b.dataset.ui === current));
  const fx = $('#fx-toggle');
  if (fx) fx.checked = !s.ui || s.ui.effects !== false;
}

// ------------------------------------------------------------------ launcher UI (new / classic) + effects

function applyUiPrefs() {
  const ui = state.settings.ui || {};
  const on = ui.effects !== false;
  document.body.classList.toggle('no-fx', !on);
  window.dispatchEvent(new CustomEvent('vl-fx', { detail: on }));
}

$$('#ui-mode button').forEach((b) => b.addEventListener('click', async () => {
  if (b.dataset.ui === (document.body.dataset.ui || 'old')) return;
  if (state.launching) { toast('Bitte warte, bis Minecraft gestartet ist.', 'error'); return; }
  document.body.classList.add('ui-switching');
  try {
    await api(window.vl.ui.set(b.dataset.ui));
  } catch (e) {
    document.body.classList.remove('ui-switching');
    toast(e.message, 'error');
  }
}));

const fxToggle = $('#fx-toggle');
if (fxToggle) fxToggle.addEventListener('change', (e) => {
  state.settings.ui = { ...(state.settings.ui || {}), effects: e.target.checked };
  applyUiPrefs();
  saveSetting({ ui: state.settings.ui });
});

function saveSetting(patch) {
  return api(window.vl.settings.save(patch)).catch((e) => toast(e.message, 'error'));
}
$('#ram').addEventListener('input', (e) => {
  const v = Number(e.target.value);
  state.settings.java.maxMemory = v;
  state.settings.java.minMemory = Math.min(1024, v);
  $('#ram-val').textContent = (v / 1024).toFixed(1) + ' GB';
});
$('#ram').addEventListener('change', () => saveSetting({ java: state.settings.java }));
$('#java-path').addEventListener('change', (e) => { state.settings.java.customPath = e.target.value.trim(); saveSetting({ java: state.settings.java }); });
$('#jvm-args').addEventListener('change', (e) => { state.settings.java.jvmArgs = e.target.value.trim(); saveSetting({ java: state.settings.java }); });
$('#fast-start').addEventListener('change', (e) => { state.settings.java.fastStart = e.target.checked; saveSetting({ java: state.settings.java }); });
$('#win-w').addEventListener('change', (e) => { state.settings.game.width = Math.max(640, Number(e.target.value) || 1280); saveSetting({ game: state.settings.game }); });
$('#win-h').addEventListener('change', (e) => { state.settings.game.height = Math.max(480, Number(e.target.value) || 720); saveSetting({ game: state.settings.game }); });
$('#fullscreen').addEventListener('change', (e) => { state.settings.game.fullscreen = e.target.checked; saveSetting({ game: state.settings.game }); });
async function updateDiscordState() {
  const s = state.settings.discord;
  const pill = $('#discord-state');
  if (!pill) return;
  let text = 'aus', cls = '';
  if (s.enabled && !/^\d{15,25}$/.test(s.clientId || '')) { text = 'ID fehlt'; cls = ''; }
  else if (s.enabled) {
    const st = await api(window.vl.discord.status()).catch(() => null);
    text = st && st.connected ? 'verbunden' : st && st.playing ? 'Discord nicht gefunden' : 'bereit';
    cls = st && st.connected ? 'ok' : 'accent';
  }
  pill.textContent = text;
  pill.className = 'pill ' + cls;
}
function saveDiscord() {
  saveSetting({ discord: state.settings.discord }).then(() => setTimeout(updateDiscordState, 800));
}
$('#discord-on').addEventListener('change', (e) => { state.settings.discord.enabled = e.target.checked; $('#discord-server').disabled = !e.target.checked; saveDiscord(); });
$('#discord-server').addEventListener('change', (e) => { state.settings.discord.showServer = e.target.checked; saveDiscord(); });
$('#discord-id').addEventListener('change', (e) => {
  const id = e.target.value.trim();
  e.target.classList.toggle('invalid', !!id && !/^\d{15,25}$/.test(id));
  if (id && !/^\d{15,25}$/.test(id)) { toast('Die Application ID besteht nur aus Ziffern (15–25 Stellen).', 'error'); return; }
  state.settings.discord.clientId = id;
  saveDiscord();
});
$('#close-launcher').addEventListener('change', (e) => { state.settings.game.closeLauncher = e.target.checked; saveSetting({ game: state.settings.game }); });

// ------------------------------------------------------------------ instances

async function loadInstances() {
  state.instances = await api(window.vl.instances.list());
  const saved = state.settings.selectedInstance;
  state.selectedInstance = state.instances.some((i) => i.id === saved) ? saved : (state.instances[0] && state.instances[0].id) || null;
  renderHome();
}

function currentInstance() {
  return state.instances.find((i) => i.id === state.selectedInstance) || null;
}

async function selectInstance(id) {
  state.selectedInstance = id;
  state.settings.selectedInstance = id;
  await api(window.vl.instances.select(id));
  renderHome();
}

async function loadVersions() {
  if (!state.versions.length) state.versions = await api(window.vl.versions.list());
  return state.versions;
}

function renderHome() {
  const inst = currentInstance();
  const sel = $('#home-instance');
  sel.replaceChildren(...state.instances.map((i) => h(`<option value="${esc(i.id)}">${esc(i.name)} · ${esc(i.mcVersion)}</option>`)));
  if (!state.instances.length) sel.append(h('<option value="">Keine Instanz</option>'));
  sel.value = state.selectedInstance || '';
  $('#home-title').textContent = inst ? inst.name : 'Visual Launcher';
  $('#home-sub').textContent = inst ? `Fabric ${inst.loaderVersion || '(neueste)'} · ${Object.keys(inst.installed || {}).length} Inhalte` : 'Lege eine Instanz an, um loszulegen.';
  $('#home-version').textContent = inst ? 'Minecraft ' + inst.mcVersion : '–';
  renderHomeExtras(inst);
  const vcOn = inst && inst.visualClient && supportsVc(inst.mcVersion);
  $('#home-vc').textContent = vcOn ? `Visual Client ${state.info.visualClient}` : 'Ohne Visual Client';
  $('#home-vc').className = 'pill ' + (vcOn ? 'accent' : '');
  updatePlayButton();
  const recent = $('#home-recent');
  const items = state.instances.slice(0, 6);
  recent.replaceChildren(...(items.length ? items.map(instanceCard) : [h('<div class="empty glass">Noch keine Instanzen. Erstelle eine unter „Instanzen“ oder installiere ein Modpack.</div>')]));
}
$('#home-instance').addEventListener('change', (e) => e.target.value && selectInstance(e.target.value));

/** New UI only: greeting, big instance picture and stat tiles. */
function renderHomeExtras(inst) {
  const greet = $('#greeting');
  if (greet) {
    const hr = new Date().getHours();
    const word = hr < 5 ? 'Gute Nacht' : hr < 11 ? 'Guten Morgen' : hr < 18 ? 'Guten Tag' : 'Guten Abend';
    const acc = currentAccount();
    greet.textContent = acc ? `${word}, ${acc.name}` : word;
  }
  const pic = $('#home-pic');
  if (pic) pic.innerHTML = inst ? thumb(inst.iconUrl || inst.icon, inst.name, 'hero-thumb') : '<div class="hero-thumb">?</div>';
  const set = (id, v) => { const el = $(id); if (el) el.textContent = v; };
  set('#stat-playtime', inst ? fmtPlaytime(inst.playTime) : '–');
  set('#stat-content', inst ? String(Object.keys(inst.installed || {}).length) : '–');
  set('#stat-total', fmtPlaytime(state.instances.reduce((a, i) => a + (i.playTime || 0), 0)));
  set('#stat-instances', String(state.instances.length));
}

async function refreshInstanceViews() {
  await loadInstances();
  if ($('.page[data-page=instances]').classList.contains('active')) renderInstances();
}

function supportsVc(v) {
  const m = /^(\d+)\.(\d+)(?:\.(\d+))?$/.exec(v || '');
  if (!m) return false;
  const [a, b, c] = [Number(m[1]), Number(m[2]), Number(m[3] || 0)];
  return (a === 1 && b === 21 && c >= 4) || (a === 26 && b >= 1 && b <= 3);
}

const CLOCK = '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/></svg>';

/** "12 Min.", "3 Std. 5 Min.", "2 T. 4 Std." */
function fmtPlaytime(ms) {
  const min = Math.floor((ms || 0) / 60000);
  if (min < 1) return 'Noch nicht gespielt';
  const d = Math.floor(min / 1440), hrs = Math.floor((min % 1440) / 60), m = min % 60;
  if (d) return `${d} T. ${hrs} Std.`;
  if (hrs) return m ? `${hrs} Std. ${m} Min.` : `${hrs} Std.`;
  return `${m} Min.`;
}

function playtimeBadge(i) {
  return `<span class="playtime" title="Gespielte Zeit">${CLOCK}${esc(fmtPlaytime(i.playTime))}</span>`;
}

function instanceCard(i) {
  const el = h(`<div class="card glass ${i.id === state.selectedInstance ? 'selected' : ''}">
    ${thumb(i.iconUrl || i.icon, i.name)}
    <div class="body"><div class="title-row"><div class="title">${esc(i.name)}</div>${playtimeBadge(i)}</div>
    <div class="meta">${esc(i.mcVersion)} · Fabric${i.visualClient && supportsVc(i.mcVersion) ? ' · Visual Client' : ''}</div></div></div>`);
  el.addEventListener('click', () => { selectInstance(i.id); if ($('.page[data-page=instances]').classList.contains('active')) renderInstances(); });
  el.addEventListener('dblclick', () => { selectInstance(i.id); showPage('home'); });
  return el;
}

function renderInstances() {
  const grid = $('#instance-grid');
  grid.replaceChildren(...(state.instances.length ? state.instances.map(instanceCard) : [h('<div class="empty glass">Noch keine Instanzen.</div>')]));
  renderInstanceDetail();
}

async function renderInstanceDetail() {
  const inst = currentInstance();
  const box = $('#instance-detail');
  if (!inst) { box.hidden = true; return; }
  box.hidden = false;
  box.innerHTML = `<div class="detail-head"><button class="profile-pic" data-act="icon" title="Profilbild ändern (klicken oder Bild hierher ziehen)">${thumb(inst.iconUrl || inst.icon, inst.name)}<span class="profile-edit"><svg viewBox="0 0 24 24"><path d="M4 7h3l2-3h6l2 3h3v12H4z"/><circle cx="12" cy="13" r="3.5"/></svg></span></button>
    <div><h2>${esc(inst.name)}${playtimeBadge(inst)}</h2><div class="muted">Minecraft ${esc(inst.mcVersion)} · Fabric ${esc(inst.loaderVersion || 'neueste')}</div></div>
    <div class="detail-actions">
      <button class="btn primary upload-btn" data-act="upload" title="Eigene .jar-Mods oder .zip-Ressourcen-/Shaderpakete hinzufügen (auch Dateien, die es nicht auf Modrinth gibt)"><svg viewBox="0 0 24 24"><path d="M12 16V4m0 0l-4 4m4-4l4 4"/><path d="M4 16v3a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-3"/></svg>Upload Files</button>
      <button class="btn" data-act="folder">Ordner öffnen</button>
      <button class="btn" data-act="rename">Umbenennen</button>
      <button class="btn" data-act="pick-icon">Profilbild ändern</button>
      ${inst.customIcon ? '<button class="btn" data-act="clear-icon">Profilbild entfernen</button>' : ''}
      <button class="btn danger" data-act="delete">Löschen</button>
    </div></div>
    ${supportsVc(inst.mcVersion) ? `<label class="toggle"><input type="checkbox" id="inst-vc" ${inst.visualClient ? 'checked' : ''}><span></span>Visual Client ${esc(state.info.visualClient || '')} verwenden (inkl. Fabric API)</label>` : '<p class="muted small">Visual Client unterstützt diese Minecraft-Version nicht (1.21.4 – 26.3).</p>'}
    <div class="content-head"><h3>Inhalte</h3><span class="muted small">Tipp: .jar-Mods und .zip-Pakete einfach hierher ziehen</span></div>
    <div class="content-list"><div class="spinner"></div></div>
    <div class="drop-overlay"><svg viewBox="0 0 24 24"><path d="M12 16V4m0 0l-4 4m4-4l4 4"/><path d="M4 16v3a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-3"/></svg><b>Loslassen zum Hinzufügen</b><span>.jar = Mod · .zip = Ressourcen- oder Shaderpaket</span></div>`;
  $('[data-act=folder]', box).onclick = () => api(window.vl.instances.open(inst.id));
  $('[data-act=upload]', box).onclick = () => uploadFiles(inst);
  // drag & drop files onto the instance
  const hasFiles = (e) => e.dataTransfer && [...e.dataTransfer.types].includes('Files');
  box.ondragover = (e) => { if (!hasFiles(e)) return; e.preventDefault(); box.classList.add('drag-over'); };
  box.ondragleave = (e) => { if (!box.contains(e.relatedTarget)) box.classList.remove('drag-over'); };
  box.ondrop = (e) => {
    box.classList.remove('drag-over');
    if (!hasFiles(e)) return;
    e.preventDefault();
    const files = [...e.dataTransfer.files].map((f) => window.vl.pathForFile(f)).filter((f) => /\.(jar|zip)$/i.test(f));
    if (!files.length) { toast('Nur .jar-Mods und .zip-Pakete können hinzugefügt werden.', 'error'); return; }
    uploadFiles(inst, files);
  };
  const setIcon = async (filePath) => {
    try {
      const res = await api(window.vl.instances.chooseIcon(inst.id, filePath));
      if (!res) return;
      await loadInstances();
      renderInstances();
      toast('Profilbild gespeichert.', 'ok');
    } catch (e) { toast(e.message, 'error'); }
  };
  const pic = $('[data-act=icon]', box);
  pic.onclick = () => setIcon();
  $('[data-act=pick-icon]', box).onclick = () => setIcon();
  pic.addEventListener('dragover', (e) => { e.preventDefault(); pic.classList.add('over'); });
  pic.addEventListener('dragleave', () => pic.classList.remove('over'));
  pic.addEventListener('drop', (e) => {
    e.preventDefault(); e.stopPropagation(); pic.classList.remove('over');
    const f = e.dataTransfer.files[0];
    if (f) setIcon(window.vl.pathForFile(f));
  });
  const clear = $('[data-act=clear-icon]', box);
  if (clear) clear.onclick = async () => { await api(window.vl.instances.clearIcon(inst.id)); await loadInstances(); renderInstances(); };
  $('[data-act=rename]', box).onclick = () => openModal('Instanz umbenennen', `<div class="field"><label>Name</label><input id="m-name" class="input" maxlength="48" value="${esc(inst.name)}"></div>`, async () => {
    const name = $('#m-name').value.trim();
    if (!name) return false;
    await api(window.vl.instances.update(inst.id, { name }));
    await loadInstances();
    renderInstances();
  });
  $('[data-act=delete]', box).onclick = async () => {
    if (!confirm(`„${inst.name}“ mit allen Welten und Mods endgültig löschen?`)) return;
    await api(window.vl.instances.remove(inst.id));
    await loadInstances();
    renderInstances();
    toast('Instanz gelöscht.');
  };
  const vc = $('#inst-vc', box);
  if (vc) vc.onchange = async () => { await api(window.vl.instances.update(inst.id, { visualClient: vc.checked })); await loadInstances(); };

  const items = await api(window.vl.instances.content(inst.id));
  const list = $('.content-list', box);
  const labels = { mod: 'Mod', resourcepack: 'Ressourcenpaket', shader: 'Shader', datapack: 'Datenpaket' };
  if (!items.length) { list.innerHTML = '<div class="muted small">Noch leer. Füge Inhalte über Modrinth oder „Upload Files“ hinzu.</div>'; return; }
  list.replaceChildren(...items.map((it) => {
    const row = h(`<div class="content-row ${it.enabled ? '' : 'disabled'}">
      ${thumb(it.icon, it.name)}
      <div class="name">${esc(it.name)}<small>${esc(it.file)} ${fmtSize(it.size)}</small></div>
      ${it.builtIn ? '<span class="tag">Eingebaut</span>' : ''}
      ${it.source ? `<span class="tag">${esc(it.source)}</span>` : ''}
      <span class="tag">${labels[it.type] || it.type}</span>
      ${it.builtIn ? '' : `<label class="toggle" title="Aktiviert"><input type="checkbox" ${it.enabled ? 'checked' : ''}><span></span></label>
      <button class="icon-btn" title="Entfernen"><svg viewBox="0 0 24 24"><path d="M3 6h18M8 6V4h8v2M6 6l1 14h10l1-14"/></svg></button>`}
    </div>`);
    const t = $('input', row);
    if (t) t.onchange = async () => { await api(window.vl.instances.toggle(inst.id, it.type, it.file)); renderInstanceDetail(); };
    const del = $('.icon-btn', row);
    if (del) del.onclick = async () => { await api(window.vl.instances.removeContent(inst.id, it.type, it.file)); renderInstanceDetail(); };
    return row;
  }));
}

/** "Upload Files": own .jar mods / .zip packs (also ones that are not on Modrinth). */
async function uploadFiles(inst, paths) {
  const btn = $('#instance-detail [data-act=upload]');
  if (btn) btn.disabled = true;
  try {
    const res = await api(window.vl.instances.addFiles(inst.id, paths || []));
    if (!res) return; // dialog cancelled
    for (const a of res.added) if (a.warning) toast(`${a.file}: ${a.warning}`, 'error');
    for (const e of res.errors) toast(`${e.file}: ${e.message}`, 'error');
    if (res.added.length) {
      const labels = { mod: 'Mod', resourcepack: 'Ressourcenpaket', shader: 'Shaderpaket' };
      toast(res.added.length === 1
        ? `${labels[res.added[0].type] || 'Datei'} „${res.added[0].title}“ hinzugefügt.`
        : `${res.added.length} Dateien zu „${inst.name}“ hinzugefügt.`, 'ok');
    }
    await loadInstances();
    renderInstanceDetail();
  } catch (e) {
    toast(e.message, 'error');
  } finally {
    const b = $('#instance-detail [data-act=upload]');
    if (b) b.disabled = false;
  }
}

$('#new-instance').addEventListener('click', async () => {
  let pickedIcon = null;
  openModal('Neue Instanz', '<div class="spinner"></div>', null);
  let list;
  try { list = await loadVersions(); } catch (e) { closeModal(); toast('Versionen konnten nicht geladen werden: ' + e.message, 'error'); return; }
  const vcVersions = list.filter((v) => v.visualClient);
  const options = list.map((v) => `<option value="${esc(v.id)}">${esc(v.id)}${v.visualClient ? '  ★ Visual Client' : ''}</option>`).join('');
  openModal('Neue Instanz', `
    <div class="field"><label>Name</label><input id="m-name" class="input" maxlength="48" placeholder="Meine Welt"></div>
    <div class="field"><label>Minecraft-Version (Fabric)</label><select id="m-version" class="select">${options}</select></div>
    <p class="vc-hint">★ = Visual Client ${esc(state.info.visualClient || '')} wird automatisch installiert.</p>
    <div class="field"><label>Profilbild (optional)</label>
      <div class="pick-row"><button type="button" class="profile-pic" id="m-pic"><div class="thumb" id="m-pic-thumb">+</div></button>
      <span class="muted small" id="m-pic-text">Klicken, um ein Bild zu wählen</span></div></div>
    <label class="toggle"><input type="checkbox" id="m-vc" checked><span></span>Visual Client installieren</label>`, async () => {
    const mcVersion = $('#m-version').value;
    const name = $('#m-name').value.trim() || `Minecraft ${mcVersion}`;
    const inst = await api(window.vl.instances.create({ name, mcVersion, visualClient: $('#m-vc').checked && supportsVc(mcVersion) }));
    if (pickedIcon) await api(window.vl.instances.chooseIcon(inst.id, pickedIcon)).catch((e) => toast(e.message, 'error'));
    await loadInstances();
    await selectInstance(inst.id);
    renderInstances();
    toast(`Instanz „${inst.name}“ erstellt.`, 'ok');
  }, 'Erstellen');
  if (vcVersions.length) $('#m-version').value = vcVersions[0].id;
  $('#m-pic').onclick = async () => {
    try {
      const res = await api(window.vl.pickImage());
      if (!res) return;
      pickedIcon = res.path;
      $('#m-pic-thumb').outerHTML = `<img class="thumb" id="m-pic-thumb" src="${res.preview}" alt="">`;
      $('#m-pic-text').textContent = 'Bild gewählt – klicken zum Ändern';
    } catch (e) { toast(e.message, 'error'); }
  };
  const sync = () => { const ok = supportsVc($('#m-version').value); $('#m-vc').disabled = !ok; $('#m-vc').checked = ok; };
  $('#m-version').addEventListener('change', sync);
  sync();
});

// ------------------------------------------------------------------ modal

let modalAction = null;
function openModal(title, body, onOk, okLabel = 'Speichern') {
  modalAction = onOk;
  $('#modal-card').innerHTML = `<h2 style="margin-bottom:18px">${esc(title)}</h2>${body}
    <div class="actions"><button class="btn ghost" id="m-cancel">Abbrechen</button>${onOk ? `<button class="btn primary" id="m-ok">${esc(okLabel)}</button>` : ''}</div>`;
  $('#modal').hidden = false;
  $('#m-cancel').onclick = closeModal;
  const ok = $('#m-ok');
  if (ok) ok.onclick = async () => {
    ok.disabled = true;
    try { if ((await modalAction()) !== false) closeModal(); } catch (e) { toast(e.message, 'error'); } finally { ok.disabled = false; }
  };
  const first = $('#modal-card input');
  if (first) setTimeout(() => first.focus(), 50);
}
function closeModal() { $('#modal').hidden = true; modalAction = null; }
$('#modal').addEventListener('mousedown', (e) => { if (e.target.id === 'modal') closeModal(); });
document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape' && !$('#modal').hidden) closeModal();
  if (e.key === 'Enter' && !$('#modal').hidden && $('#m-ok') && e.target.tagName === 'INPUT') $('#m-ok').click();
});

// ------------------------------------------------------------------ play

function updatePlayButton() {
  const btn = $('#play');
  const label = $('span', btn);
  btn.classList.toggle('running', state.running);
  document.body.classList.toggle('game-running', state.running);
  document.body.classList.toggle('launching', state.launching);
  btn.disabled = state.launching || (!state.running && !currentInstance());
  label.textContent = state.running ? 'BEENDEN' : state.launching ? 'STARTET…' : 'SPIELEN';
}

$('#play').addEventListener('click', async () => {
  if (state.running) { await api(window.vl.game.kill()); return; }
  const inst = currentInstance();
  if (!inst) { showPage('instances'); return; }
  if (!currentAccount()) { checkLogin(); $('#gate-login').hidden = false; return; }
  state.launching = true;
  updatePlayButton();
  $('#progress').hidden = false;
  $('#log').textContent = '';
  try {
    await api(window.vl.game.launch(inst.id));
  } catch (e) {
    state.launching = false;
    $('#progress').hidden = true;
    toast(e.message, 'error');
    updatePlayButton();
  }
});

$('#toggle-log').addEventListener('click', () => {
  const log = $('#log');
  log.hidden = !log.hidden;
  $('#toggle-log').textContent = log.hidden ? 'Protokoll anzeigen' : 'Protokoll ausblenden';
});

function setProgress(p, barSel = '#progress-bar', stageSel = '#progress-stage', countSel = '#progress-count') {
  $(stageSel).textContent = p.stage || '…';
  const total = Number(p.total) || 0;
  const bar = $(barSel);
  bar.parentElement.classList.toggle('indeterminate', total <= 1);
  bar.style.width = total > 1 ? `${Math.min(100, (p.current / total) * 100)}%` : '0';
  if (countSel) $(countSel).textContent = total > 1 ? `${p.current}/${total}` : '';
}

window.vl.events.launchProgress((p) => setProgress(p));
window.vl.events.launchLog((line) => {
  const log = $('#log');
  log.textContent += line.endsWith('\n') ? line : line + '\n';
  if (log.textContent.length > 200000) log.textContent = log.textContent.slice(-150000);
  log.scrollTop = log.scrollHeight;
});
window.vl.events.launchStarted(async () => {
  state.launching = false;
  state.running = true;
  $('#progress').hidden = true;
  updatePlayButton();
  toast('Minecraft startet …', 'ok');
  await loadInstances();
});
window.vl.events.instancesChanged(() => refreshInstanceViews());
window.vl.events.launchClosed(({ code }) => {
  refreshInstanceViews();
  state.running = false;
  updatePlayButton();
  if (code && code !== 0) {
    toast(`Minecraft wurde mit Code ${code} beendet. Details im Protokoll.`, 'error');
    $('#log').hidden = false;
  }
});

// ------------------------------------------------------------------ library (Modrinth)

const TYPE_LABELS = { mod: 'Mods', resourcepack: 'Ressourcenpakete', shader: 'Shader', modpack: 'Modpacks' };

class Library {
  constructor(source) {
    this.source = source;
    this.page = $(`.page[data-source=${source}]`);
    this.type = 'mod';
    this.query = '';
    this.sort = source === 'modrinth' ? 'relevance' : 'relevance';
    this.offset = 0;
    this.limit = 24;
    this.built = false;
    this.seq = 0;
    this.gameVersion = null;
  }

  title() { return 'Modrinth'; }

  build() {
    const sorts = this.source === 'modrinth'
      ? [['relevance', 'Relevanz'], ['downloads', 'Downloads'], ['follows', 'Beliebt'], ['newest', 'Neu'], ['updated', 'Aktualisiert']]
      : [['relevance', 'Relevanz'], ['downloads', 'Downloads'], ['follows', 'Beliebt'], ['updated', 'Aktualisiert']];
    this.page.innerHTML = `
      <div class="page-head"><div><h1>${this.title()}</h1><p class="muted">Durchsuche die komplette ${this.title()}-Bibliothek und installiere mit einem Klick.</p></div>
        <div class="lib-target">Ziel: <select class="select lib-inst"></select></div></div>
      <div class="segmented lib-types">${Object.entries(TYPE_LABELS).map(([k, v]) => `<button data-type="${k}" class="${k === this.type ? 'active' : ''}">${v}</button>`).join('')}</div>
      <div class="lib-bar">
        <div class="search"><svg viewBox="0 0 24 24"><circle cx="11" cy="11" r="7"/><path d="M21 21l-4.3-4.3"/></svg><input class="input lib-q" placeholder="Suchen …"></div>
        <select class="select lib-sort">${sorts.map(([k, v]) => `<option value="${k}">${v}</option>`).join('')}</select>
        <label class="toggle lib-match-wrap"><input type="checkbox" class="lib-match" checked><span></span>Nur passend zur Instanz</label>
      </div>
      <div class="results"></div>
      <div class="pager"></div>`;
    $$('.lib-types button', this.page).forEach((b) => b.addEventListener('click', () => {
      this.type = b.dataset.type;
      $$('.lib-types button', this.page).forEach((x) => x.classList.toggle('active', x === b));
      this.offset = 0;
      this.search();
    }));
    let timer = null;
    $('.lib-q', this.page).addEventListener('input', (e) => {
      clearTimeout(timer);
      timer = setTimeout(() => { this.query = e.target.value; this.offset = 0; this.search(); }, 300);
    });
    $('.lib-sort', this.page).addEventListener('change', (e) => { this.sort = e.target.value; this.offset = 0; this.search(); });
    $('.lib-match', this.page).addEventListener('change', () => { this.offset = 0; this.search(); });
    $('.lib-inst', this.page).addEventListener('change', (e) => { selectInstance(e.target.value); this.offset = 0; this.search(); });
    this.built = true;
  }

  reset() { this.built = false; }

  async open() {
    if (!this.built) this.build();
    const sel = $('.lib-inst', this.page);
    sel.replaceChildren(...state.instances.map((i) => h(`<option value="${esc(i.id)}">${esc(i.name)} · ${esc(i.mcVersion)}</option>`)));
    if (!state.instances.length) sel.append(h('<option value="">Keine Instanz</option>'));
    sel.value = state.selectedInstance || '';
    const inst = currentInstance();
    if (this.gameVersion !== (inst && inst.mcVersion) || !$('.results', this.page).children.length) {
      this.gameVersion = inst && inst.mcVersion;
      this.search();
    }
  }

  async search() {
    const seq = ++this.seq;
    const results = $('.results', this.page);
    results.innerHTML = '<div class="spinner"></div>';
    $('.pager', this.page).innerHTML = '';
    const inst = currentInstance();
    const match = $('.lib-match', this.page).checked && this.type !== 'modpack';
    $('.lib-match-wrap', this.page).hidden = this.type === 'modpack';
    try {
      const res = await api(window.vl[this.source].search({
        query: this.query, type: this.type, sort: this.sort, offset: this.offset, limit: this.limit,
        gameVersion: match && inst ? inst.mcVersion : ''
      }));
      if (seq !== this.seq) return;
      if (!res.hits.length) { results.innerHTML = '<div class="empty">Nichts gefunden.</div>'; return; }
      results.replaceChildren(...res.hits.map((hit) => this.card(hit)));
      this.pager(res.total);
    } catch (e) {
      if (seq !== this.seq) return;
      results.innerHTML = `<div class="empty">Fehler: ${esc(e.message)}</div>`;
    }
  }

  pager(total) {
    const pages = Math.ceil(total / this.limit);
    const page = Math.floor(this.offset / this.limit) + 1;
    const pager = $('.pager', this.page);
    pager.innerHTML = `<button class="btn" ${page <= 1 ? 'disabled' : ''} data-d="-1">← Zurück</button><span>Seite ${page} von ${fmtNum(pages)}</span><button class="btn" ${page >= pages ? 'disabled' : ''} data-d="1">Weiter →</button>`;
    $$('button', pager).forEach((b) => b.addEventListener('click', () => {
      this.offset = Math.max(0, this.offset + Number(b.dataset.d) * this.limit);
      this.search();
      $('#content').scrollTop = 0;
    }));
  }

  installedKey(hit) { return `${this.source}:${hit.id}`; }

  card(hit) {
    const inst = currentInstance();
    const installed = inst && inst.installed && inst.installed[this.installedKey(hit)];
    const isPack = this.type === 'modpack';
    const el = h(`<div class="result glass">
      ${thumb(hit.icon, hit.title)}
      <div class="body">
        <div><span class="title">${esc(hit.title)}</span><span class="by">von ${esc(hit.author)}</span></div>
        <div class="desc">${esc(hit.description)}</div>
        <div class="foot">
          <div class="stats"><span>⬇ ${fmtNum(hit.downloads)}</span>${(hit.categories || []).slice(0, 2).map((c) => `<span>${esc(c)}</span>`).join('')}</div>
          <button class="btn ghost" data-ext="${esc(hit.url)}">Seite</button>
          <button class="btn primary install ${installed ? 'done' : ''}">${isPack ? 'Als Instanz installieren' : installed ? 'Installiert ✓' : 'Installieren'}</button>
        </div>
      </div></div>`);
    $('.install', el).addEventListener('click', (e) => this.install(hit, e.currentTarget));
    return el;
  }

  async install(hit, btn) {
    const label = btn.textContent;
    btn.disabled = true;
    btn.textContent = 'Installiere …';
    try {
      if (this.type === 'modpack') {
        const inst = await api(window.vl[this.source].modpack({ projectId: hit.id, title: hit.title, icon: hit.icon }));
        await loadInstances();
        await selectInstance(inst.id);
        btn.textContent = 'Installiert ✓';
        btn.classList.add('done');
        toast(`Modpack „${hit.title}“ wurde als Instanz installiert.`, 'ok');
        if (inst.blocked && inst.blocked.length) toast(`Manuell herunterladen (vom Autor gesperrt): ${inst.blocked.join(', ')}`, 'error');
        return;
      }
      const inst = currentInstance();
      if (!inst) throw new Error('Lege zuerst eine Instanz an.');
      const res = await api(window.vl[this.source].install({ instanceId: inst.id, projectId: hit.id, type: this.type, title: hit.title, icon: hit.icon }));
      await loadInstances();
      btn.textContent = 'Installiert ✓';
      btn.classList.add('done');
      toast(`${hit.title} ${res.version || ''} → ${inst.name}`, 'ok');
    } catch (e) {
      btn.textContent = label;
      toast(e.message, 'error');
    } finally {
      btn.disabled = false;
    }
  }
}

const libraries = { modrinth: new Library('modrinth') };

window.vl.events.installProgress((p) => {
  const total = Number(p.total) || 0;
  if (total > 1 && (p.current === total || p.current % 10 === 0)) {
    const btn = $('.page.active .install[disabled]');
    if (btn) btn.textContent = `${p.current}/${total} …`;
  }
});

// ------------------------------------------------------------------ boot

(async function boot() {
  try {
    state.info = await api(window.vl.app.info());
    $('#app-version').textContent = 'v' + state.info.version;
    state.settings = await api(window.vl.settings.get());
    applyDesign(state.settings.design, state.settings.backgroundImage);
    applyUiPrefs();
    await loadAccounts();
    await loadInstances();
    state.running = await api(window.vl.game.running());
    updatePlayButton();
    if (state.settings.legalAccepted !== state.info.legalVersion) {
      $('#gate-legal').hidden = false;
      renderLegal('#gate-doc', 'tos');
    } else {
      checkLogin();
    }
  } catch (e) {
    toast('Start fehlgeschlagen: ' + e.message, 'error');
  }
})();
