#!/usr/bin/env bash
# Patches a Visual Client bundle jar (every Minecraft build inside it):
#   - Texture Painter: "Export ZIP" + placed End Crystal texture
#   - "Configs" module: named configs + shareable config codes
#   - "No Explosion Particles" module (Extra), Totem Tweaks "No Pop Animation"
#   - Sakura forest title-screen panorama (resources/)
# Already applied steps are skipped. Needs JDK 21+.
#   tools/texture-painter-patch/patch.sh visual-client-1.17.0.jar visual-client-1.18.0.jar 1.18.0
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
in="$1"; out="$2"; version="${3:-1.18.0}"
build="$(mktemp -d)"
trap 'rm -rf "$build"' EXIT

# compile the helpers against the mod's own classes (no Minecraft classes are referenced)
inner="$(unzip -Z1 "$in" | grep '^META-INF/jars/.*\.jar$' | head -1)"
mkdir -p "$build/cp"
unzip -p "$in" "$inner" > "$build/inner.jar"
(cd "$build/cp" && unzip -q "$build/inner.jar" 'net/visualclient/*')
javac --release 21 -nowarn -cp "$build/cp" -d "$build/classes" $(find "$here/src" -name '*.java')
# resources that go into every Minecraft build (title-screen panorama, ...)
if [ -d "$here/resources" ]; then cp -r "$here/resources/." "$build/classes/"; fi
java --add-exports java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED \
     --add-exports java.base/jdk.internal.org.objectweb.asm.tree=ALL-UNNAMED \
     "$here/Patcher.java" "$in" "$out" "$build/classes" "$version"
