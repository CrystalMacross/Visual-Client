// Launcher designs on top of the New UI (Settings > Launcher design): Glass, Neon and Nova.
// main.js passes the choice as ?skin=...; this loads the matching stylesheet before the page paints.
(function () {
  var SKINS = ['glass', 'neon', 'nova'];
  var skin = new URLSearchParams(location.search).get('skin');
  if (SKINS.indexOf(skin) < 0) return;
  document.documentElement.dataset.skin = skin;
  var link = document.createElement('link');
  link.rel = 'stylesheet';
  link.href = 'skin-' + skin + '.css';
  document.head.appendChild(link);
})();
