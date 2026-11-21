// Microsoft / Xbox / Minecraft login through msmc. The login window talks to
// login.live.com directly; tokens are only stored locally (encrypted).
const { Auth } = require('msmc');
const store = require('./store');

// A Minecraft session is valid for about 24 hours. It is reused while it stays valid for at
// least this long, so most game starts need no Microsoft/Xbox round trips at all.
const SESSION_MARGIN = 6 * 60 * 60 * 1000;
const refreshing = new Map(); // uuid -> running refresh

function publicAccount(a) {
  return { uuid: a.uuid, name: a.name, skin: a.skin || null };
}

function skinUrl(profile) {
  const skins = (profile && profile.skins) || [];
  const active = skins.find((s) => s.state === 'ACTIVE') || skins[0];
  return active && active.url ? active.url.replace(/^http:/, 'https:') : null;
}

async function login() {
  const auth = new Auth('select_account');
  const xbox = await auth.launch('electron', {
    width: 520,
    height: 680,
    resizable: false,
    title: 'Mit Microsoft anmelden',
    autoHideMenuBar: true,
    backgroundColor: '#ffffff'
  });
  const mc = await xbox.getMinecraft();
  if (!mc.profile || mc.isDemo()) {
    throw new Error('Dieses Microsoft-Konto besitzt kein Minecraft: Java Edition.');
  }
  const account = {
    uuid: mc.profile.id,
    name: mc.profile.name,
    skin: skinUrl(mc.profile),
    refresh: store.encrypt(xbox.save()),
    session: store.encrypt(JSON.stringify(mc.mclc()))
  };
  const accounts = store.getAccounts().filter((a) => a.uuid !== account.uuid);
  accounts.push(account);
  store.saveAccounts(accounts);
  store.saveSettings({ selectedAccount: account.uuid });
  return publicAccount(account);
}

function list() {
  return store.getAccounts().map(publicAccount);
}

function logout(uuid) {
  store.saveAccounts(store.getAccounts().filter((a) => a.uuid !== uuid));
  const s = store.getSettings();
  if (s.selectedAccount === uuid) {
    const rest = store.getAccounts();
    store.saveSettings({ selectedAccount: rest[0] ? rest[0].uuid : null });
  }
  return list();
}

/** The stored Minecraft session of an account if it is still valid long enough. */
function cachedSession(acc) {
  if (!acc || !acc.session) return null;
  try {
    const s = JSON.parse(store.decrypt(acc.session));
    const exp = s && s.meta && Number(s.meta.exp);
    if (s && s.access_token && s.uuid === acc.uuid && exp && exp - Date.now() > SESSION_MARGIN) return s;
  } catch { /* unreadable: refresh */ }
  return null;
}

/** Gets a new Minecraft session with the stored Microsoft refresh token (one at a time per account). */
function refreshSession(uuid) {
  if (refreshing.has(uuid)) return refreshing.get(uuid);
  const job = (async () => {
    const acc = store.getAccounts().find((a) => a.uuid === uuid);
    if (!acc) throw new Error('Bitte melde dich zuerst mit Microsoft an.');
    let xbox;
    try {
      xbox = await new Auth('select_account').refresh(store.decrypt(acc.refresh));
    } catch (e) {
      throw new Error('Deine Anmeldung ist abgelaufen. Bitte melde dich erneut an. (' + (e && e.message ? e.message : e) + ')');
    }
    const mc = await xbox.getMinecraft();
    if (!mc.profile || mc.isDemo()) throw new Error('Dieses Konto besitzt kein Minecraft: Java Edition.');
    const session = mc.mclc();
    // re-read: the account list may have changed while the refresh was running
    const accounts = store.getAccounts();
    const current = accounts.find((a) => a.uuid === uuid);
    if (current) {
      current.name = mc.profile.name;
      current.skin = skinUrl(mc.profile) || current.skin;
      current.refresh = store.encrypt(xbox.save());
      current.session = store.encrypt(JSON.stringify(session));
      store.saveAccounts(accounts);
    }
    return session;
  })();
  refreshing.set(uuid, job);
  job.then(() => refreshing.delete(uuid), () => refreshing.delete(uuid));
  return job;
}

/** Returns the MCLC authorization object: the cached session, or a freshly refreshed one. */
async function launchAuth(uuid) {
  const acc = store.getAccounts().find((a) => a.uuid === uuid);
  if (!acc) throw new Error('Bitte melde dich zuerst mit Microsoft an.');
  return cachedSession(acc) || refreshSession(uuid);
}

/**
 * Called when the launcher starts or the account changes: refreshes an expiring session in
 * the background, so pressing "Play" does not have to wait for Microsoft.
 */
function prepare(uuid) {
  const acc = uuid && store.getAccounts().find((a) => a.uuid === uuid);
  if (!acc || cachedSession(acc)) return Promise.resolve(false);
  return refreshSession(uuid).then(() => true, () => false);
}

module.exports = { login, list, logout, launchAuth, prepare };
