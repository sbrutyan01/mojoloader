CC      ?= cc
CFLAGS  ?= -O2 -Wall -Wextra
PREFIX  ?= /usr/local
JAVAC   ?= javac
JAR     ?= jar
VERSION ?= 0.1.0
# appimagetool binary (https://github.com/AppImage/appimagetool); only needed for "make appimage"
APPIMAGETOOL ?= appimagetool

all: mojoloader

mojoloader: mojoloader.c
	$(CC) -std=c11 $(CFLAGS) -o $@ $<

# Swing front-end (needs a JDK 17 or later: sudo apt install default-jdk)
ui: mojoloader-ui.jar

mojoloader-ui.jar: MojoLoaderUI.java
	mkdir -p build/ui
	$(JAVAC) --release 17 -Xlint:all -d build/ui $<
	$(JAR) --create --file $@ --main-class MojoLoaderUI -C build/ui .

run-ui: mojoloader mojoloader-ui.jar
	env -u LD_LIBRARY_PATH java -jar mojoloader-ui.jar

# Release packages, written to dist/
deb: mojoloader mojoloader-ui.jar
	packaging/build-deb.sh $(VERSION)

appdir: mojoloader mojoloader-ui.jar
	packaging/build-appdir.sh

# --appimage-extract-and-run lets appimagetool work without FUSE (containers, CI)
appimage: appdir
	mkdir -p dist
	ARCH=$$(uname -m) $(APPIMAGETOOL) --appimage-extract-and-run build/AppDir \
		dist/MojoLoader-$(VERSION)-$$(uname -m).AppImage

dist: deb appimage
	cp mojoloader-ui.jar dist/mojoloader-ui-$(VERSION).jar
	cd dist && sha256sum * > SHA256SUMS

install: mojoloader
	install -D -m 0755 mojoloader $(DESTDIR)$(PREFIX)/bin/mojoloader

clean:
	$(RM) mojoloader mojoloader-ui.jar
	$(RM) -r build dist

.PHONY: all ui run-ui deb appdir appimage dist install clean
