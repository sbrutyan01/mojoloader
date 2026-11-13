# mojoloader

Loader for the Embedded Micro **Mojo v3** FPGA board (Spartan-6 XC6SLX9 + ATmega32U4)
on Linux: a small C command-line tool plus an optional Java Swing front-end.

It is a from-scratch rewrite of [notandy/cmojoloader](https://github.com/notandy/cmojoloader)
that speaks the same serial protocol as the stock Mojo loader firmware.
Targets Ubuntu 22.04 and 24.04; it uses only POSIX/glibc calls and has no dependencies.

> Status: tested against a simulated board on a pseudo-terminal, not yet on real hardware.

## Install

Ready-made packages are on the [Releases](https://github.com/sbrutyan01/mojoloader/releases) page:

- **`mojoloader_VERSION_amd64.deb`** (Ubuntu 22.04 / 24.04, Debian 12+): installs `mojoloader`,
  `mojoloader-ui` and a menu entry. `sudo apt install ./mojoloader_*.deb`
- **`MojoLoader-VERSION-x86_64.AppImage`**: self-contained, bundles its own Java runtime.
  `chmod +x MojoLoader-*.AppImage && ./MojoLoader-*.AppImage`
  (`--cli ARGS` runs the command-line loader instead of the UI).
- **`mojoloader-ui-VERSION.jar`**: the UI only; needs Java 17+ and the `mojoloader` binary.

## Build

```
sudo apt install build-essential     # C loader
make

sudo apt install default-jdk         # optional: Swing UI, JDK 17 or later
make ui
```

Packages: `make deb` builds the .deb; `make appimage` builds the AppImage and needs
[appimagetool](https://github.com/AppImage/appimagetool) (`make appimage APPIMAGETOOL=/path/to/it`).
`.github/workflows/release.yml` builds everything on Ubuntu 22.04 and publishes a release:
push a `v*` tag, or use Actions -> release -> Run workflow and enter a version.

## Command line

```
./mojoloader -f design.bin        # write flash and load
./mojoloader -v -f design.bin     # write flash, verify, load
./mojoloader -r -f design.bin     # load to the FPGA only (RAM)
./mojoloader -c                   # erase flash
```

| Option | Meaning |
|---|---|
| `-f FILE` | bitstream (`.bin` from ISE, `bitgen -g Binary:Yes`) |
| `-d DEV` | serial port, default `/dev/ttyACM0` |
| `-r` | RAM only, flash untouched |
| `-v` | verify flash after writing |
| `-c` | erase flash |
| `-t SEC` | timeout per protocol step, default 30 |
| `-p` | progress as plain lines (used by the UI) |

## Swing UI

```
java -jar mojoloader-ui.jar
```

Keep `mojoloader` next to the jar, or set the "Loader program" path in the window.

## Troubleshooting

- **Permission denied on the port:** `sudo usermod -aG dialout $USER`, then log out and in.
- **Port busy / no response:** stop ModemManager (`sudo systemctl stop ModemManager`).
- **Java fails with `CXXABI_1.3.7 not found`:** the shell has Xilinx ISE's `settings64.sh`
  sourced. Use a clean terminal or `env -u LD_LIBRARY_PATH java -jar mojoloader-ui.jar`.

## Protocol

115200 8N1 on the board's CDC-ACM port. Five DTR pulses enter loader mode, then:

| Send | Reply |
|---|---|
| `E` | `D` (flash erased) |
| `F` / `V` / `R` | `R` (flash / flash + verify / RAM) |
| 4-byte little-endian size | `O` |
| bitstream | `D` |
| `S` (after `V`) | `0xAA`, 4-byte size + 5, bitstream |
| `L` (flash modes) | `D` (FPGA configured from flash) |
