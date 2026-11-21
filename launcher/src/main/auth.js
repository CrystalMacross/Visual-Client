// Microsoft / Xbox / Minecraft login through msmc. The login window talks to
// login.live.com directly; tokens are only stored locally (encrypted).
const { Auth } = require('msmc');
const store = require('./store');

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
    refresh: store.encrypt(xbox.save())
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

/** Refreshes the stored session and returns the MCLC authorization object. */
async function launchAuth(uuid) {
  const accounts = store.getAccounts();
  const acc = accounts.find((a) => a.uuid === uuid);
  if (!acc) throw new Error('Bitte melde dich zuerst mit Microsoft an.');
  let xbox;
  try {
    xbox = await new Auth('select_account').refresh(store.decrypt(acc.refresh));
  } catch (e) {
    throw new Error('Deine Anmeldung ist abgelaufen. Bitte melde dich erneut an. (' + (e && e.message ? e.message : e) + ')');
  }
  const mc = await xbox.getMinecraft();
  if (!mc.profile || mc.isDemo()) throw new Error('Dieses Konto besitzt kein Minecraft: Java Edition.');
  acc.name = mc.profile.name;
  acc.skin = skinUrl(mc.profile) || acc.skin;
  acc.refresh = store.encrypt(xbox.save());
  store.saveAccounts(accounts);
  return mc.mclc();
}

module.exports = { login, list, logout, launchAuth };
