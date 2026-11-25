#!/usr/bin/env bash
# visual-client-1.20.7.jar -> visual-client-1.20.8.jar ("No Equipment Animation", category Extra).
# The three sources live in tools/texture-painter-patch/src; they are compiled against each nested
# jar's own classes (they use no Minecraft names). Needs JDK 21+.
#   tools/vc-1.20.8-patch/build.sh visual-client-1.20.7.jar visual-client-1.20.8.jar
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
src="$here/../texture-painter-patch"
in="$(realpath "$1")"; out="$2"
build="$(mktemp -d)"
trap 'rm -rf "$build"' EXIT
asm="$src/lib/asm-9.7.jar:$src/lib/asm-tree-9.7.jar"
javac --release 21 -nowarn -d "$build/stubs" -cp "$asm" $(find "$src/stubs" -name '*.java')
for inner in $(unzip -Z1 "$in" | grep '^META-INF/jars/.*\.jar$'); do
  n="$(basename "$inner" .jar)"
  mkdir -p "$build/cp/$n"
  unzip -p "$in" "$inner" > "$build/$n.jar"
  (cd "$build/cp/$n" && unzip -q "$build/$n.jar" 'net/visualclient/*')
  javac --release 21 -nowarn -cp "$build/cp/$n:$build/stubs:$asm" -d "$build/classes/$n" \
    "$src/src/net/visualclient/modules/extra/NoEquipAnimationModule.java" \
    "$src/src/net/visualclient/mixin/VisualMixinPlugin.java" \
    "$src/src/net/visualclient/modules/misc/ConfigsModule.java"
done
java --add-exports java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED \
     --add-exports java.base/jdk.internal.org.objectweb.asm.tree=ALL-UNNAMED \
     "$here/Swap.java" "$in" "$out" "$build/classes"
