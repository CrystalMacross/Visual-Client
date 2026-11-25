#!/usr/bin/env bash
# visual-client-1.20.8.jar -> visual-client-1.20.9.jar ("Aurora Sky", category Extra).
#   tools/vc-1.20.9-patch/prepare-mc.sh <work>        (once: Minecraft jars + mappings)
#   tools/vc-1.20.9-patch/build.sh visual-client-1.20.8.jar visual-client-1.20.9.jar <work>
# AuroraSkyModule is written with Mojang names. Per build a few names are filled in (GuiGraphics or
# GuiGraphicsExtractor, the camera getter, yaw/pitch and the day clock); 1.21.x is compiled against the
# Mojang-named jar and the mod's classes remapped to Mojang names, then remapped to intermediary with
# tiny-remapper; 26.x (official names) is compiled directly. LinkCheck then resolves every reference of the
# result against the real Minecraft jar and the mod. Needs JDK 21 and JDK 25 (26.x jars are Java 25 classes).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
in="$(realpath "$1")"; out="$2"; work="$(realpath "$3")"
jdk25="${JDK25:-/usr/lib/jvm/java-25-openjdk-amd64}"
b="$(mktemp -d)"; trap 'rm -rf "$b"' EXIT
src="$here/../texture-painter-patch"
asm="$src/lib/asm-9.7.jar:$src/lib/asm-tree-9.7.jar"
javac --release 21 -nowarn -d "$b/stubs" -cp "$asm" $(find "$src/stubs" -name '*.java')
javac -nowarn -d "$b/check" -cp "$work/asm-9.9.jar" "$here/LinkCheck.java"
libs26="$(ls "$work"/libs26/*.jar | tr '\n' ':')"
for v in 1.21.4 1.21.5 1.21.6 1.21.9 1.21.11 26.1 26.2 26.3; do
  case $v in
    1.21.11) GG=net.minecraft.client.gui.GuiGraphics; CAM="getMainCamera()"; YAW="yRot()"; PITCH="xRot()"; TIME="level.getDayTime()";;
    1.21.*) GG=net.minecraft.client.gui.GuiGraphics; CAM="getMainCamera()"; YAW="getYRot()"; PITCH="getXRot()"; TIME="level.getDayTime()";;
    26.1) GG=net.minecraft.client.gui.GuiGraphicsExtractor; CAM="getMainCamera()"; YAW="yRot()"; PITCH="xRot()"; TIME="level.getOverworldClockTime()";;
    26.*) GG=net.minecraft.client.gui.GuiGraphicsExtractor; CAM="mainCamera()"; YAW="yRot()"; PITCH="xRot()"; TIME="level.getOverworldClockTime()";;
  esac
  n="visualclient-mc$v"
  mkdir -p "$b/mod/$n" "$b/gen/$v/net/visualclient/modules/extra" "$b/classes/$n"
  unzip -p "$in" "META-INF/jars/$n.jar" > "$b/$n.jar"
  (cd "$b/mod/$n" && unzip -q "$b/$n.jar" 'net/visualclient/*')
  sed -e "s/\bGG\b/$GG/g" -e "s/gameRenderer\.CAMERA/gameRenderer.$CAM/" -e "s/cam\.YAW/cam.$YAW/" -e "s/cam\.PITCH/cam.$PITCH/" -e "s/night(TIME)/night($TIME)/" \
    "$here/src/net/visualclient/modules/extra/AuroraSkyModule.java" > "$b/gen/$v/net/visualclient/modules/extra/AuroraSkyModule.java"
  if [[ $v == 1.21.* ]]; then
    (cd "$b/mod/$n" && zip -qr "$b/mod-$v-int.jar" net)
    java -jar "$work/tiny-remapper.jar" "$b/mod-$v-int.jar" "$b/mod-$v-named.jar" "$work/mc/full-$v.tiny" intermediary named "$work/mc/$v.jar" >/dev/null 2>&1
    javac --release 21 -nowarn -proc:none -d "$b/named-$v" -cp "$work/mc/$v-named.jar:$b/mod-$v-named.jar:$libs26" "$b/gen/$v/net/visualclient/modules/extra/AuroraSkyModule.java"
    (cd "$b/named-$v" && zip -qr "$b/aur-$v-named.jar" net)
    java -jar "$work/tiny-remapper.jar" "$b/aur-$v-named.jar" "$b/aur-$v-int.jar" "$work/mc/full-$v.tiny" named intermediary "$work/mc/$v-named.jar" "$b/mod-$v-named.jar" >/dev/null 2>&1
    (cd "$b/classes/$n" && unzip -q "$b/aur-$v-int.jar")
  else
    "$jdk25/bin/javac" --release 21 -nowarn -proc:none -d "$b/classes/$n" -cp "$work/mc/$v.jar:$b/mod/$n:$libs26" "$b/gen/$v/net/visualclient/modules/extra/AuroraSkyModule.java" 2>&1 | grep -v "^warning" || true
  fi
  # ConfigsModule (registers the module) uses no Minecraft names
  javac --release 21 -nowarn -cp "$b/mod/$n:$b/classes/$n:$b/stubs:$asm" -d "$b/classes/$n" "$src/src/net/visualclient/modules/misc/ConfigsModule.java"
  echo -n "  $v: "
  java -cp "$b/check:$work/asm-9.9.jar" LinkCheck "$b/classes/$n/net/visualclient/modules/extra/AuroraSkyModule.class" "$b/classes/$n" "$work/mc/$v.jar" "$b/mod/$n" | tail -1
done
java --add-exports java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED \
     --add-exports java.base/jdk.internal.org.objectweb.asm.tree=ALL-UNNAMED \
     "$here/Swap.java" "$in" "$out" "$b/classes" \
     "$(basename "$in" .jar | sed 's/^visual-client-//')" "$(basename "$out" .jar | sed 's/^visual-client-//')"
