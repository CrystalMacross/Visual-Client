// Copies the newest visual-client-<version>.jar from the repository root into
// resources/mods so it gets bundled into the launcher.
const fs = require('fs');
const path = require('path');

const repoRoot = path.resolve(__dirname, '..', '..');
const target = path.resolve(__dirname, '..', 'resources', 'mods');

function parse(name) {
  const m = /^visual-client-(\d+)[._](\d+)(?:[._](\d+))?\.jar$/i.exec(name);
  return m ? [Number(m[1]), Number(m[2]), Number(m[3] || 0)] : null;
}

const jars = fs.readdirSync(repoRoot)
  .map((f) => ({ f, v: parse(f) }))
  .filter((x) => x.v)
  .sort((a, b) => b.v[0] - a.v[0] || b.v[1] - a.v[1] || b.v[2] - a.v[2]);

if (!jars.length) {
  console.error('No visual-client-*.jar found in ' + repoRoot);
  process.exit(1);
}
fs.mkdirSync(target, { recursive: true });
for (const old of fs.readdirSync(target)) {
  if (/^visual-client-.*\.jar$/i.test(old)) fs.rmSync(path.join(target, old));
}
const newest = jars[0];
const out = `visual-client-${newest.v.join('.')}.jar`;
fs.copyFileSync(path.join(repoRoot, newest.f), path.join(target, out));
console.log('Bundled Visual Client ' + newest.v.join('.') + ' -> resources/mods/' + out);

// Visual Styles: the newest visual-styles-<version>[+<mc>].jar from the repository root, if any.
const styles = fs.readdirSync(repoRoot)
  .map((f) => ({ f, m: /^visual-styles-(\d+)\.(\d+)\.(\d+)(?:\+[\w.]+)?\.jar$/i.exec(f) }))
  .filter((x) => x.m)
  .map((x) => ({ f: x.f, v: [Number(x.m[1]), Number(x.m[2]), Number(x.m[3])] }))
  .sort((a, b) => b.v[0] - a.v[0] || b.v[1] - a.v[1] || b.v[2] - a.v[2]);
for (const old of fs.readdirSync(target)) {
  if (/^visual-styles-.*\.jar$/i.test(old)) fs.rmSync(path.join(target, old));
}
if (styles.length) {
  fs.copyFileSync(path.join(repoRoot, styles[0].f), path.join(target, styles[0].f));
  console.log('Bundled Visual Styles ' + styles[0].v.join('.') + ' -> resources/mods/' + styles[0].f);
}