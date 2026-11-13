#!/bin/sh
# Assembles build/AppDir: loader, UI jar and a bundled Java runtime (jlink, JDK 17+).
set -eu
APPDIR="build/AppDir"

rm -rf "$APPDIR"
install -D -m 0755 mojoloader                   "$APPDIR/usr/bin/mojoloader"
install -D -m 0644 mojoloader-ui.jar            "$APPDIR/usr/share/mojoloader/mojoloader-ui.jar"
install -D -m 0755 packaging/AppRun             "$APPDIR/AppRun"
install -D -m 0644 packaging/mojoloader.desktop "$APPDIR/mojoloader.desktop"
install -D -m 0644 packaging/mojoloader.svg     "$APPDIR/mojoloader.svg"
ln -s mojoloader.svg "$APPDIR/.DirIcon"

jlink --add-modules java.desktop,java.prefs \
      --strip-debug --no-header-files --no-man-pages \
      --output "$APPDIR/usr/lib/jre"
