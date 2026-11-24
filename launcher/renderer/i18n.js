// Launcher language (English / Deutsch).
// The UI is written in German; in English mode every German text that appears in the window
// (static HTML, texts set by app.js, toasts, confirm dialogs and error messages from the main
// process) is translated as it is shown. The language comes from the URL (?lang=en|de), which
// main.js sets from the "language" setting; switching reloads the window like the design switch.
(() => {
  'use strict';
  const lang = new URLSearchParams(location.search).get('lang') === 'de' ? 'de' : 'en';
  document.documentElement.lang = lang;
  window.VL_LANG = lang;
  if (lang !== 'en') return;

  const EXACT = {
    // window, navigation, home
    'Minimieren': 'Minimize', 'Maximieren': 'Maximize', 'Schließen': 'Close',
    'Start': 'Home', 'Instanzen': 'Instances', 'Einstellungen': 'Settings', 'Rechtliches': 'Legal',
    'Willkommen': 'Welcome', 'Willkommen bei Visual Launcher': 'Welcome to Visual Launcher',
    'Spielzeit': 'Play time', 'Inhalte': 'Content', 'Gesamt': 'Total',
    'Bereit zum Spielen': 'Ready to play', 'Lege eine Instanz an, um loszulegen.': 'Create an instance to get started.',
    'Spielen': 'Play', 'SPIELEN': 'PLAY', 'BEENDEN': 'STOP', 'STARTET…': 'STARTING…',
    'Protokoll anzeigen': 'Show log', 'Protokoll ausblenden': 'Hide log', 'Zuletzt gespielt': 'Last played',
    'Ohne Visual Client': 'Without Visual Client', 'Noch nicht gespielt': 'Not played yet',
    'Gute Nacht': 'Good night', 'Guten Morgen': 'Good morning', 'Guten Tag': 'Good afternoon', 'Guten Abend': 'Good evening',
    'Gespielte Zeit': 'Play time', 'Konto': 'Account', 'Nicht angemeldet': 'Not signed in',
    // instances
    'Jede Instanz hat eigene Mods, Ressourcenpakete und Welten.': 'Every instance has its own mods, resource packs and worlds.',
    'Neue Instanz': 'New instance', '+ Neue Instanz': '+ New instance',
    'Noch keine Instanzen. Erstelle eine unter „Instanzen“ oder installiere ein Modpack.': 'No instances yet. Create one under “Instances” or install a modpack.',
    'Noch keine Instanzen.': 'No instances yet.', 'Keine Instanz': 'No instance',
    'Profilbild ändern (klicken oder Bild hierher ziehen)': 'Change profile picture (click or drop an image here)',
    'Eigene .jar-Mods oder .zip-Ressourcen-/Shaderpakete hinzufügen (auch Dateien, die es nicht auf Modrinth gibt)': 'Add your own .jar mods or .zip resource/shader packs (also files that are not on Modrinth)',
    'Ordner öffnen': 'Open folder', 'Umbenennen': 'Rename', 'Profilbild ändern': 'Change profile picture',
    'Profilbild entfernen': 'Remove profile picture', 'Löschen': 'Delete', 'Entfernen': 'Remove',
    'Visual Client unterstützt diese Minecraft-Version nicht (1.21.4 – 26.3).': 'Visual Client does not support this Minecraft version (1.21.4 – 26.3).',
    'Tipp: .jar-Mods und .zip-Pakete einfach hierher ziehen': 'Tip: just drag .jar mods and .zip packs here',
    'Loslassen zum Hinzufügen': 'Drop to add', '.jar = Mod · .zip = Ressourcen- oder Shaderpaket': '.jar = mod · .zip = resource or shader pack',
    'Nur .jar-Mods und .zip-Pakete können hinzugefügt werden.': 'Only .jar mods and .zip packs can be added.',
    'Profilbild gespeichert.': 'Profile picture saved.', 'Instanz umbenennen': 'Rename instance',
    'Instanz gelöscht.': 'Instance deleted.', 'Eingebaut': 'Built in',
    'Mod': 'Mod', 'Ressourcenpaket': 'Resource pack', 'Shaderpaket': 'Shader pack', 'Datenpaket': 'Data pack', 'Datei': 'File',
    'Noch leer. Füge Inhalte über Modrinth oder „Upload Files“ hinzu.': 'Still empty. Add content from Modrinth or with “Upload Files”.',
    'Versionen konnten nicht geladen werden:': 'Could not load the versions:',
    'Profilbild (optional)': 'Profile picture (optional)', 'Klicken, um ein Bild zu wählen': 'Click to choose an image',
    'Bild gewählt – klicken zum Ändern': 'Image chosen – click to change',
    'Minecraft-Version (Fabric)': 'Minecraft version (Fabric)', 'Visual Client installieren': 'Install Visual Client',
    'Meine Welt': 'My world', 'Erstellen': 'Create', 'Speichern': 'Save', 'Abbrechen': 'Cancel',
    'Lege zuerst eine Instanz an.': 'Create an instance first.',
    // design
    'Hintergrund und Akzentfarbe – gilt für beide Launcher-Designs.': 'Background and accent colour – applies to both launcher designs.',
    'Passe den Hintergrund und die Akzentfarbe an.': 'Customize the background and the accent colour.',
    'Zurücksetzen': 'Reset', 'Vorschau': 'Preview', 'Vorlagen': 'Presets', 'Hintergrund': 'Background',
    'Verlauf': 'Gradient', 'Farbe': 'Colour', 'Bild': 'Image', 'Farbe 1 (Hex)': 'Colour 1 (hex)', 'Farbe 2 (Hex)': 'Colour 2 (hex)',
    'Winkel': 'Angle', 'Screenshot hochladen': 'Upload screenshot', 'Klicken oder Bild hierher ziehen': 'Click or drop an image here',
    'Abdunkeln': 'Dim', 'Unschärfe': 'Blur', 'Akzentfarbe': 'Accent colour', 'Akzent (Hex)': 'Accent (hex)',
    'Hintergrundbild gespeichert.': 'Background image saved.',
    // settings
    'Alles wird nur lokal auf diesem Gerät gespeichert.': 'Everything is stored only on this device.',
    'Launcher-Design': 'Launcher design', 'Neu': 'New', 'Klassisch': 'Classic',
    'Biom und Dimension anzeigen (braucht Visual Client)': 'Show biome and dimension (needs Visual Client)',
    'Weltnamen im Einzelspieler anzeigen': 'Show the world name in singleplayer',
    '„Neu“ mit Effekten, „Klassisch“ das bisherige, dazu Glass (Milchglas), Neon (Cyberpunk) und Nova (Seitenleiste). Der Launcher lädt sich zum Wechseln kurz neu, alle Einstellungen bleiben erhalten.': '“New” with effects, “Classic” the previous one, plus Glass (frosted glass), Neon (cyberpunk) and Nova (sidebar). The launcher reloads briefly to switch; all settings are kept.',
    '„Neu“ mit Effekten, „Klassisch“ dieses Design, dazu Glass, Neon und Nova. Der Launcher lädt sich zum Wechseln kurz neu, alle Einstellungen bleiben erhalten.': '“New” with effects, “Classic” this design, plus Glass, Neon and Nova. The launcher reloads briefly to switch; all settings are kept.',
    '„Neu“ ist dieses Design mit Effekten, „Klassisch“ das bisherige. Der Launcher lädt sich zum Wechseln kurz neu, alle Einstellungen bleiben erhalten.': '“New” is this design with effects, “Classic” the previous one. The launcher reloads briefly to switch; all settings are kept.',
    '„Neu“ ist das überarbeitete Design mit Effekten, „Klassisch“ dieses Design. Der Launcher lädt sich zum Wechseln kurz neu, alle Einstellungen bleiben erhalten.': '“New” is the redesigned look with effects, “Classic” this design. The launcher reloads briefly to switch; all settings are kept.',
    'Effekte (Partikel, Lichter, Animationen) – beim Spielen automatisch pausiert': 'Effects (particles, lights, animations) – paused automatically while you play',
    'Sprache': 'Language', 'Die Sprache des Launchers. Er lädt sich zum Wechseln kurz neu.': 'The launcher language. It reloads briefly to switch.',
    'Konten': 'Accounts', 'Microsoft-Konten mit Minecraft: Java Edition. Klicke ein Konto an, um es zu verwenden.': 'Microsoft accounts with Minecraft: Java Edition. Click an account to use it.',
    'Weiteres Microsoft-Konto': 'Another Microsoft account', 'Aktiv': 'Active', 'Abmelden': 'Sign out',
    'Java & Speicher': 'Java & memory', 'Java wird automatisch passend zur Minecraft-Version geladen.': 'Java is downloaded automatically to match the Minecraft version.',
    'Maximaler RAM': 'Maximum RAM', 'Eigener Java-Pfad (optional)': 'Custom Java path (optional)',
    'JVM-Argumente (optional)': 'JVM arguments (optional)', 'Leer = automatisch (empfohlen)': 'Empty = automatic (recommended)',
    'Schnellstart (Java-Klassen-Cache, ab Minecraft 1.20.5)': 'Fast start (Java class cache, Minecraft 1.20.5 and newer)',
    'Spiel': 'Game', 'Fenstergröße und Verhalten beim Start.': 'Window size and behaviour on start.',
    'Breite': 'Width', 'Höhe': 'Height', 'Vollbild': 'Fullscreen', 'Launcher beim Spielstart minimieren': 'Minimize the launcher when the game starts',
    'Discord-Status': 'Discord status', 'Discord Rich Presence anzeigen': 'Show Discord Rich Presence',
    'Zeigt in Discord z. B. „Playing: mc.hypixel.net“. Der Launcher spricht dafür nur mit der Discord-App auf diesem PC.': 'Shows e.g. “Playing: mc.hypixel.net” in Discord. For this the launcher only talks to the Discord app on this PC.',
    'Zeigt in Discord z. B. „Playing: mc.hypixel.net“. Der Launcher spricht dafür nur mit der Discord-App auf diesem PC. Die Application ID erstellst du unter': 'Shows e.g. “Playing: mc.hypixel.net” in Discord. For this the launcher only talks to the Discord app on this PC. Create the Application ID at',
    'Server-IP anzeigen (für deine Discord-Freunde sichtbar)': 'Show the server IP (visible to your Discord friends)',
    'Die Application ID erstellst du unter': 'Create the Application ID at',
    '(New Application → ID kopieren). Optional unter „Rich Presence → Art Assets“ ein Bild mit dem Namen': '(New Application → copy the ID). Optionally, under “Rich Presence → Art Assets”, upload an image named',
    'hochladen.': '.', 'ID fehlt': 'ID missing', 'Discord nicht gefunden': 'Discord not found',
    'Die Application ID besteht nur aus Ziffern (15–25 Stellen).': 'The Application ID consists of digits only (15–25 digits).',
    'Eigene Application ID (optional)': 'Own Application ID (optional)', 'leer = Visual Launcher': 'empty = Visual Launcher',
    'Leer lassen: Discord zeigt „Visual Launcher“. Für einen eigenen Namen legst du unter': 'Leave it empty and Discord shows “Visual Launcher”. For a name of your own, create an app at',
    'Zeigt in Discord z. B. „Playing: mc.hypixel.net“. Der Launcher spricht dafür nur mit der Discord-App auf diesem PC. Leer lassen: Discord zeigt „Visual Launcher“. Für einen eigenen Namen legst du unter': 'Shows e.g. “Playing: mc.hypixel.net” in Discord. For this the launcher only talks to the Discord app on this PC. Leave it empty and Discord shows “Visual Launcher”. For a name of your own, create an app at',
    'eine App an und trägst ihre ID hier ein.': 'and enter its ID here.',
    'Datenschutz': 'Privacy',
    'Visual Launcher sammelt keine Daten. Es gibt keine Telemetrie, keine Analyse und keinen Server des Entwicklers. Der Launcher verbindet sich nur direkt mit Microsoft/Xbox/Mojang (Anmeldung & Spieldateien), Fabric und Modrinth.': 'Visual Launcher collects no data. There is no telemetry, no analytics and no server of the developer. The launcher only connects directly to Microsoft/Xbox/Mojang (sign-in & game files), Fabric and Modrinth.',
    'Bedingungen und Datenschutz.': 'Terms and privacy.',
    // first start, sign-in
    'Minecraft mit Fabric und Visual Client – modern, schnell, ohne Datensammlung.': 'Minecraft with Fabric and Visual Client – modern, fast, no data collection.',
    'Erster Start': 'First start', 'Bitte lies und akzeptiere die Bedingungen, bevor du fortfährst.': 'Please read and accept the terms before you continue.',
    'Ich habe die Terms of Service, die Terms of Use und den Datenschutzhinweis gelesen und akzeptiere sie.': 'I have read and accept the Terms of Service, the Terms of Use and the Privacy Notice.',
    'Akzeptieren & weiter': 'Accept & continue',
    'Melde dich einmal an – danach startet Minecraft mit einem Klick.': 'Sign in once – after that Minecraft starts with one click.',
    'Anmelden': 'Sign in', 'Mit Microsoft anmelden': 'Sign in with Microsoft',
    'Melde dich mit dem Microsoft-Konto an, das Minecraft: Java Edition besitzt.': 'Sign in with the Microsoft account that owns Minecraft: Java Edition.',
    'Die Anmeldung läuft direkt über Microsoft. Dein Passwort sieht der Launcher nie. Deine Sitzung wird nur verschlüsselt auf diesem Gerät gespeichert.': 'You sign in directly with Microsoft. The launcher never sees your password. Your session is stored only on this device, encrypted.',
    'Warte auf Microsoft…': 'Waiting for Microsoft…', 'Anmeldung abgebrochen.': 'Sign-in cancelled.',
    'Bitte warte, bis Minecraft gestartet ist.': 'Please wait until Minecraft has started.',
    // launching
    'Minecraft startet …': 'Minecraft is starting …', 'Start wird vorbereitet': 'Preparing the start',
    'Spieldateien werden geprüft': 'Checking game files', 'Java wird vorbereitet': 'Preparing Java', 'Java wird heruntergeladen': 'Downloading Java',
    'Assets werden geladen': 'Downloading assets', 'Native Bibliotheken werden geladen': 'Downloading native libraries',
    'Bibliotheken werden geladen': 'Downloading libraries', 'Fabric-Bibliotheken werden geladen': 'Downloading Fabric libraries',
    'Minecraft werden geladen': 'Downloading Minecraft', 'Dateien werden geladen': 'Downloading files',
    'Modpack wird geladen': 'Loading the modpack', 'Modpack-Dateien': 'Modpack files',
    'Minecraft läuft bereits.': 'Minecraft is already running.',
    'Minecraft konnte nicht gestartet werden. Details stehen im Protokoll.': 'Minecraft could not be started. Details are in the log.',
    // library
    'Mods': 'Mods', 'Ressourcenpakete': 'Resource packs', 'Shader': 'Shaders', 'Modpacks': 'Modpacks',
    'Relevanz': 'Relevance', 'Beliebt': 'Popular', 'Aktualisiert': 'Updated', 'Downloads': 'Downloads',
    'Suchen …': 'Search …', 'Nur passend zur Instanz': 'Only matching the instance', 'Ziel:': 'Target:',
    'Nichts gefunden.': 'Nothing found.', '← Zurück': '← Back', 'Weiter →': 'Next →', 'Seite': 'Page',
    'Installieren': 'Install', 'Als Instanz installieren': 'Install as instance', 'Installiere …': 'Installing …', 'Installiert ✓': 'Installed ✓',
    // dialogs and errors from the main process
    'Dateien zur Instanz hinzufügen': 'Add files to the instance', 'Hinzufügen': 'Add',
    'Mods und Pakete (.jar, .zip)': 'Mods and packs (.jar, .zip)', 'Mods (.jar)': 'Mods (.jar)',
    'Ressourcen- und Shaderpakete (.zip)': 'Resource and shader packs (.zip)',
    'Bitte wähle ein Bild (PNG, JPG, WEBP, GIF oder BMP).': 'Please choose an image (PNG, JPG, WEBP, GIF or BMP).',
    'Das Bild ist größer als 25 MB.': 'The image is larger than 25 MB.', 'Das Bild konnte nicht gelesen werden.': 'The image could not be read.',
    'Profilbild für die Instanz wählen': 'Choose a profile picture for the instance', 'Hintergrundbild wählen': 'Choose a background image', 'Profilbild wählen': 'Choose a profile picture',
    'Dieses Microsoft-Konto besitzt kein Minecraft: Java Edition.': 'This Microsoft account does not own Minecraft: Java Edition.',
    'Bitte melde dich zuerst mit Microsoft an.': 'Please sign in with Microsoft first.',
    'Dieses Konto besitzt kein Minecraft: Java Edition.': 'This account does not own Minecraft: Java Edition.',
    'Ungültige Instanz': 'Invalid instance', 'Name und Version werden benötigt.': 'Name and version are required.', 'Ungültige Datei': 'Invalid file',
    'Die Datei ist kein gültiges Archiv (beschädigt oder keine .jar/.zip).': 'The file is not a valid archive (damaged or not a .jar/.zip).',
    'Quilt-Mod – läuft nur, wenn sie auch Fabric unterstützt.': 'Quilt mod – only runs if it also supports Fabric.',
    'Forge/NeoForge-Mod – Fabric lädt sie nicht.': 'Forge/NeoForge mod – Fabric does not load it.',
    'Keine Fabric-Mod erkannt (fabric.mod.json fehlt).': 'No Fabric mod detected (fabric.mod.json is missing).',
    'Das Ressourcenpaket ist in einem Unterordner verpackt – pack.mcmeta muss direkt im Zip liegen.': 'The resource pack is packed in a subfolder – pack.mcmeta must be directly in the zip.',
    'Unbekanntes Zip: weder Ressourcenpaket (pack.mcmeta) noch Shaderpaket (shaders/).': 'Unknown zip: neither a resource pack (pack.mcmeta) nor a shader pack (shaders/).',
    'Nur .jar (Mods) und .zip (Ressourcen- und Shaderpakete) werden unterstützt.': 'Only .jar (mods) and .zip (resource and shader packs) are supported.',
    'Keine Datei.': 'Not a file.', 'Größer als 512 MB.': 'Larger than 512 MB.',
    'Visual Client wird vom Launcher selbst verwaltet.': 'Visual Client is managed by the launcher itself.',
    'Visual Styles wird vom Launcher selbst verwaltet.': 'Visual Styles is managed by the launcher itself.',
    'Visual Client ist nicht im Launcher enthalten.': 'Visual Client is not included in the launcher.',
    'Nur Fabric-Modpacks werden unterstützt.': 'Only Fabric modpacks are supported.'
  };

  const PATTERNS = [
    [/^Angemeldet als (.+)$/, 'Signed in as $1'],
    [/^(.+) abmelden\?$/, 'Sign out $1?'],
    [/^(Gute Nacht|Guten Morgen|Guten Tag|Guten Abend), (.+)$/, (m, word, name) => EXACT[word] + ', ' + name],
    [/^Visual Launcher (\S+) · Visual Client (\S+) eingebaut$/, 'Visual Launcher $1 · Visual Client $2 built in'],
    [/^Fabric \(?neueste\)? · (\d+) Inhalte$/, 'Fabric (latest) · $1 items'],
    [/^Fabric (.+) · (\d+) Inhalte$/, 'Fabric $1 · $2 items'],
    [/^Minecraft (.+) · Fabric neueste$/, 'Minecraft $1 · Fabric latest'],
    [/^(\d+) T\. (\d+) Std\.$/, '$1 d $2 h'], [/^(\d+) Std\. (\d+) Min\.$/, '$1 h $2 min'], [/^(\d+) Std\.$/, '$1 h'], [/^(\d+) Min\.$/, '$1 min'],
    [/^([\d.,]+) Mio\.$/, '$1M'], [/^([\d.,]+) Tsd\.$/, '$1K'],
    [/^„(.+)“ mit allen Welten und Mods endgültig löschen\?$/, 'Delete “$1” with all worlds and mods for good?'],
    [/^Mod „(.+)“ hinzugefügt\.$/, 'Mod “$1” added.'], [/^Ressourcenpaket „(.+)“ hinzugefügt\.$/, 'Resource pack “$1” added.'],
    [/^Shaderpaket „(.+)“ hinzugefügt\.$/, 'Shader pack “$1” added.'], [/^Datei „(.+)“ hinzugefügt\.$/, 'File “$1” added.'],
    [/^(\d+) Dateien zu „(.+)“ hinzugefügt\.$/, '$1 files added to “$2”.'],
    [/^Versionen konnten nicht geladen werden: (.*)$/, 'Could not load the versions: $1'],
    [/^★ = Visual Client (.*) wird automatisch installiert\.$/, '★ = Visual Client $1 is installed automatically.'],
    [/^Instanz „(.+)“ erstellt\.$/, 'Instance “$1” created.'],
    [/^Minecraft wurde mit Code (-?\d+) beendet\. Details im Protokoll\.$/, 'Minecraft exited with code $1. Details are in the log.'],
    [/^Durchsuche die komplette (.+)-Bibliothek und installiere mit einem Klick\.$/, 'Browse the complete $1 library and install with one click.'],
    [/^Fehler: (.*)$/, 'Error: $1'],
    [/^Seite (\d+) von (.+)$/, 'Page $1 of $2'],
    [/^von (.+)$/, 'by $1'],
    [/^Modpack „(.+)“ wurde als Instanz installiert\.$/, 'Modpack “$1” was installed as an instance.'],
    [/^Manuell herunterladen \(vom Autor gesperrt\): (.*)$/, 'Download manually (blocked by the author): $1'],
    [/^Start fehlgeschlagen: (.*)$/, 'Start failed: $1'],
    [/^Deine Anmeldung ist abgelaufen\. Bitte melde dich erneut an\. \((.*)\)$/, 'Your sign-in has expired. Please sign in again. ($1)'],
    [/^Für dein System gibt es keine Java-Laufzeit "(.+)"\. Trage in den Einstellungen einen eigenen Java-Pfad ein\.$/, 'There is no Java runtime "$1" for your system. Enter your own Java path in the settings.'],
    [/^Nicht unterstütztes Betriebssystem: (.*)$/, 'Unsupported operating system: $1'],
    [/^Keine passende Version für Minecraft (.+?)( \(Fabric\))?\.$/, 'No matching version for Minecraft $1$2.'],
    [/^Dieses Modpack hat keine Fabric-Version( für (.+))?\. Visual Launcher unterstützt Fabric-Modpacks\.$/, 'This modpack has no Fabric version$1. Visual Launcher supports Fabric modpacks.'],
    [/^HTTP (\d+) bei (.+)$/, 'HTTP $1 from $2'],
    [/^Unsicherer Dateipfad im Paket: (.*)$/, 'Unsafe file path in the pack: $1'],
    [/^Unbekannte Minecraft-Version(.*)$/, 'Unknown Minecraft version$1'],
    [/^Fabric unterstützt Minecraft (.+) nicht\.$/, 'Fabric does not support Minecraft $1.'],
    [/^Keine Fabric API für Minecraft (.+)$/, 'No Fabric API for Minecraft $1'],
    [/^(.+): Quilt-Mod – läuft nur, wenn sie auch Fabric unterstützt\.$/, '$1: Quilt mod – only runs if it also supports Fabric.'],
    [/^(.+): Forge\/NeoForge-Mod – Fabric lädt sie nicht\.$/, '$1: Forge/NeoForge mod – Fabric does not load it.']
  ];
  // " für 1.21.4" inside the modpack message: German preposition left in the captured group
  const fixups = [[/ für (\S+)/g, ' for $1']];

  function translate(text) {
    const key = text.replace(/\s+/g, ' ').trim();
    if (!key || !/[A-Za-zÄÖÜäöüß]/.test(key)) return text;
    let out = Object.prototype.hasOwnProperty.call(EXACT, key) ? EXACT[key] : undefined;
    if (out === undefined) {
      for (const [re, rep] of PATTERNS) {
        if (re.test(key)) {
          out = key.replace(re, rep);
          for (const [fre, frep] of fixups) out = out.replace(fre, frep);
          // the captured part may itself be a known German message (e.g. "Datei: <error>")
          out = out.replace(/: (.+)$/, (m, rest) => ': ' + (EXACT[rest] || rest));
          break;
        }
      }
    }
    if (out === undefined || out === key) return text;
    const lead = text.match(/^\s*/)[0];
    const trail = text.match(/\s*$/)[0];
    return lead + out + trail;
  }
  window.vlTranslate = translate;

  const ATTRS = ['title', 'placeholder', 'aria-label', 'alt'];
  const SKIP = (el) => el && el.closest && el.closest('#log, pre, code, script, style, .legal-doc, #legal-doc, #gate-doc');

  function translateNode(node) {
    if (node.nodeType === Node.TEXT_NODE) {
      if (SKIP(node.parentElement)) return;
      const next = translate(node.nodeValue);
      if (next !== node.nodeValue) node.nodeValue = next;
      return;
    }
    if (node.nodeType !== Node.ELEMENT_NODE || SKIP(node)) return;
    for (const a of ATTRS) {
      if (node.hasAttribute(a)) {
        const v = node.getAttribute(a);
        const next = translate(v);
        if (next !== v) node.setAttribute(a, next);
      }
    }
    for (const child of node.childNodes) translateNode(child);
  }

  translateNode(document.documentElement);
  document.title = translate(document.title);

  new MutationObserver((records) => {
    for (const r of records) {
      if (r.type === 'characterData') translateNode(r.target);
      else if (r.type === 'attributes') translateNode(r.target);
      else for (const n of r.addedNodes) translateNode(n);
    }
  }).observe(document.documentElement, { subtree: true, childList: true, characterData: true, attributes: true, attributeFilter: ATTRS });

  const nativeConfirm = window.confirm.bind(window);
  window.confirm = (msg) => nativeConfirm(translate(String(msg)));
})();
