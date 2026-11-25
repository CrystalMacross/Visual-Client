#!/usr/bin/env bash
# Downloads the Minecraft client jars of all eight builds and prepares them for compiling:
#   <work>/mc/<v>.jar        runtime names (intermediary for 1.21.x, official names for 26.x)
#   <work>/mc/<v>-named.jar  1.21.x with Mojang names, to compile against
#   <work>/mc/full-<v>.tiny  official / intermediary / named mappings (merge.py)
# plus tiny-remapper, the 26.x libraries and a few compile-only libraries. Needs python3, curl, JDK 21.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
work="${1:?work dir}"; mkdir -p "$work/mc" "$work/libs26"; cd "$work"
curl -sfo vm.json https://piston-meta.mojang.com/mc/game/version_manifest_v2.json
python3 - <<'PY'
import json, urllib.request, os
d = json.load(open('vm.json'))
for v in ['1.21.4', '1.21.5', '1.21.6', '1.21.9', '1.21.11', '26.1', '26.2', '26.3']:
    m = json.load(urllib.request.urlopen([x for x in d['versions'] if x['id'] == v][0]['url']))
    out = f'mc/{v}-obf.jar' if v.startswith('1.') else f'mc/{v}.jar'
    if not os.path.exists(out): urllib.request.urlretrieve(m['downloads']['client']['url'], out)
    if v.startswith('1.') and not os.path.exists(f'mc/moj-{v}.txt'):
        urllib.request.urlretrieve(m['downloads']['client_mappings']['url'], f'mc/moj-{v}.txt')
    if v == '26.3':
        for lib in m['libraries']:
            a = lib.get('downloads', {}).get('artifact')
            if a and 'natives' not in a['path'] and not os.path.exists('libs26/' + os.path.basename(a['path'])):
                urllib.request.urlretrieve(a['url'], 'libs26/' + os.path.basename(a['path']))
PY
[ -f tiny-remapper.jar ] || curl -sfo tiny-remapper.jar https://maven.fabricmc.net/net/fabricmc/tiny-remapper/0.11.1/tiny-remapper-0.11.1-fat.jar
[ -f asm-9.9.jar ] || curl -sfo asm-9.9.jar https://repo1.maven.org/maven2/org/ow2/asm/asm/9.9/asm-9.9.jar
for v in 1.21.4 1.21.5 1.21.6 1.21.9 1.21.11; do
  [ -f mc/int-$v.tiny ] || { curl -sfo int.jar https://maven.fabricmc.net/net/fabricmc/intermediary/$v/intermediary-$v-v2.jar; unzip -p int.jar mappings/mappings.tiny > mc/int-$v.tiny; rm int.jar; }
  python3 "$here/merge.py" mc/moj-$v.txt mc/int-$v.tiny mc/full-$v.tiny
  java -jar tiny-remapper.jar mc/$v-obf.jar mc/$v.jar mc/full-$v.tiny official intermediary >/dev/null 2>&1
  java -jar tiny-remapper.jar mc/$v-obf.jar mc/$v-named.jar mc/full-$v.tiny official named >/dev/null 2>&1
done
echo "prepared $work"
