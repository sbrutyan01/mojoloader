CC      ?= cc
CFLAGS  ?= -O2 -Wall -Wextra
PREFIX  ?= /usr/local
JAVAC   ?= javac
JAR     ?= jar

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
	java -jar mojoloader-ui.jar

install: mojoloader
	install -D -m 0755 mojoloader $(DESTDIR)$(PREFIX)/bin/mojoloader

clean:
	$(RM) mojoloader mojoloader-ui.jar
	$(RM) -r build

.PHONY: all ui run-ui install clean
