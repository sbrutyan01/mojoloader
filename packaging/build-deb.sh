#!/bin/sh
# Builds dist/mojoloader_VERSION_ARCH.deb from an already built mojoloader and mojoloader-ui.jar.
set -eu
VERSION="${1:?usage: build-deb.sh VERSION}"
ARCH="$(dpkg --print-architecture)"
ROOT="build/deb/mojoloader_${VERSION}_${ARCH}"

rm -rf "$ROOT"
install -D -m 0755 mojoloader                   "$ROOT/usr/bin/mojoloader"
install -D -m 0755 packaging/mojoloader-ui      "$ROOT/usr/bin/mojoloader-ui"
install -D -m 0644 mojoloader-ui.jar            "$ROOT/usr/share/mojoloader/mojoloader-ui.jar"
install -D -m 0644 packaging/mojoloader.desktop "$ROOT/usr/share/applications/mojoloader.desktop"
install -D -m 0644 packaging/mojoloader.svg     "$ROOT/usr/share/icons/hicolor/scalable/apps/mojoloader.svg"
install -D -m 0644 README.md                    "$ROOT/usr/share/doc/mojoloader/README.md"

SIZE="$(du -sk "$ROOT/usr" | cut -f1)"
mkdir -p "$ROOT/DEBIAN"
cat > "$ROOT/DEBIAN/control" <<CONTROL
Package: mojoloader
Version: ${VERSION}
Section: electronics
Priority: optional
Architecture: ${ARCH}
Depends: libc6 (>= 2.34)
Recommends: default-jre | java17-runtime
Installed-Size: ${SIZE}
Maintainer: Sergey Brutyan <sbrutyan01@users.noreply.github.com>
Homepage: https://github.com/sbrutyan01/mojoloader
Description: Loader for the Mojo v3 FPGA board
 Command-line loader (mojoloader) and Swing front-end (mojoloader-ui) for
 the Embedded Micro Mojo v3 Spartan-6 board. The command-line tool has no
 dependencies; the front-end needs a Java 17 or later runtime.
CONTROL

mkdir -p dist
dpkg-deb --root-owner-group --build "$ROOT" "dist/mojoloader_${VERSION}_${ARCH}.deb"
