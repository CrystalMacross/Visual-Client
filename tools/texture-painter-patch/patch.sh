#!/usr/bin/env bash
# Adds "Export ZIP" and the placed End Crystal texture to the Texture Painter of a
# Visual Client bundle jar.  Needs JDK 21+.
#   tools/texture-painter-patch/patch.sh visual-client-1.12.0.jar visual-client-1.12.1.jar 1.12.1
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
in="$1"; out="$2"; version="${3:-1.12.1}"
build="$(mktemp -d)"
trap 'rm -rf "$build"' EXIT

javac --release 21 -d "$build/classes" "$here/src/net/visualclient/gui/TexturePainterExtras.java"
java --add-exports java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED \
     --add-exports java.base/jdk.internal.org.objectweb.asm.tree=ALL-UNNAMED \
     "$here/Patcher.java" "$in" "$out" "$build/classes" "$version"
