/*
 * mojoloader - command-line loader for the Embedded Micro Mojo v3
 *              (Spartan-6 XC6SLX9 + ATmega32U4) FPGA board.
 *
 * Clean rewrite of notandy/cmojoloader. Speaks the same serial protocol as
 * the stock Mojo loader firmware and keeps the original options (-d -f -c
 * -r -v). -p prints progress as plain lines for front-ends (MojoLoaderUI). Uses only POSIX/glibc interfaces, so it builds unchanged on
 * Ubuntu 22.04 (gcc 11, glibc 2.35) and Ubuntu 24.04 (gcc 13, glibc 2.39).
 *
 * Build:  cc -std=c11 -O2 -Wall -Wextra -o mojoloader mojoloader.c
 *
 * Protocol (115200 8N1 on the board's CDC-ACM port, e.g. /dev/ttyACM0):
 *   pulse DTR 5 times            -> AVR enters loader mode
 *   'E'                          -> 'D'   erase flash
 *   'F' | 'V' | 'R'              -> 'R'   write flash / flash+verify / RAM
 *   4-byte little-endian size    -> 'O'
 *   bitstream (.bin)             -> 'D'
 *   'S' (after 'V' only)         -> 0xAA, 4-byte LE (size + 5), bitstream
 *   'L' (flash modes only)       -> 'D'   configure FPGA from flash
 */

#define _DEFAULT_SOURCE
#define _POSIX_C_SOURCE 200809L

#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <termios.h>
#include <time.h>
#include <unistd.h>

#define DEFAULT_DEVICE   "/dev/ttyACM0"
#define DEFAULT_TIMEOUT  30          /* seconds, per protocol step */
#define CHUNK_SIZE       1024        /* bytes per write()/read() */
#define MAX_BIN_SIZE     (16u * 1024u * 1024u)
#define FLASH_HEADER_LEN 5           /* 0xAA + 4-byte length */

enum mode { MODE_FLASH, MODE_FLASH_VERIFY, MODE_RAM, MODE_ERASE };

static const char *progname = "mojoloader";
static int timeout_ms = DEFAULT_TIMEOUT * 1000;
static int show_progress;
static int progress_lines;      /* -p: one "Writing: NN%" line per step */

/* ------------------------------------------------------------------ */
/* helpers                                                             */
/* ------------------------------------------------------------------ */

static void fail(const char *fmt, ...) __attribute__((noreturn, format(printf, 1, 2)));

static void fail(const char *fmt, ...)
{
    va_list ap;

    if (show_progress && !progress_lines)
        fputc('\n', stderr);
    fprintf(stderr, "%s: ", progname);
    va_start(ap, fmt);
    vfprintf(stderr, fmt, ap);
    va_end(ap);
    fputc('\n', stderr);
    exit(EXIT_FAILURE);
}

static int64_t now_ms(void)
{
    struct timespec ts;

    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static void sleep_ms(long ms)
{
    struct timespec ts = { ms / 1000, (ms % 1000) * 1000000L };

    while (nanosleep(&ts, &ts) == -1 && errno == EINTR)
        ;
}

static void progress(const char *what, size_t done, size_t total)
{
    static int last = -1;
    int pct;

    if (!show_progress)
        return;
    pct = total ? (int)((uint64_t)done * 100 / total) : 100;
    if (pct != last) {
        fprintf(stderr, progress_lines ? "%s: %d%%\n" : "\r%s: %3d%%", what, pct);
        fflush(stderr);
        last = pct;
    }
    if (done == total) {
        if (!progress_lines)
            fputc('\n', stderr);
        last = -1;
    }
}

/* ------------------------------------------------------------------ */
/* serial port                                                         */
/* ------------------------------------------------------------------ */

static int serial_open(const char *dev)
{
    struct termios tio;
    int fd;

    fd = open(dev, O_RDWR | O_NOCTTY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) {
        int e = errno;

        if (e == EACCES)
            fail("cannot open %s: %s\n"
                 "  hint: sudo usermod -aG dialout $USER, then log out and in",
                 dev, strerror(e));
        if (e == EBUSY)
            fail("cannot open %s: %s\n"
                 "  hint: another program (ModemManager, a terminal) holds the port",
                 dev, strerror(e));
        fail("cannot open %s: %s", dev, strerror(e));
    }
    if (!isatty(fd))
        fail("%s is not a serial device", dev);

    /* Keep other programs from opening the port mid-transfer. */
    if (ioctl(fd, TIOCEXCL) < 0)
        fprintf(stderr, "%s: warning: cannot lock %s: %s\n",
                progname, dev, strerror(errno));

    if (tcgetattr(fd, &tio) < 0)
        fail("tcgetattr %s: %s", dev, strerror(errno));

    cfmakeraw(&tio);                       /* 8 data bits, no parity, raw */
    tio.c_cflag |= CLOCAL | CREAD;         /* ignore modem status lines */
    tio.c_cflag &= ~(tcflag_t)(CSTOPB | CRTSCTS);
    tio.c_iflag &= ~(tcflag_t)(IXON | IXOFF | IXANY);
    tio.c_cc[VMIN] = 1;
    tio.c_cc[VTIME] = 0;
    if (cfsetispeed(&tio, B115200) < 0 || cfsetospeed(&tio, B115200) < 0)
        fail("cannot select 115200 baud: %s", strerror(errno));
    if (tcsetattr(fd, TCSANOW, &tio) < 0)
        fail("tcsetattr %s: %s", dev, strerror(errno));

    tcflush(fd, TCIOFLUSH);
    return fd;
}

static void set_dtr(int fd, int on)
{
    static int warned;
    int bit = TIOCM_DTR;

    if (ioctl(fd, on ? TIOCMBIS : TIOCMBIC, &bit) < 0 && !warned) {
        fprintf(stderr, "%s: warning: cannot drive DTR: %s\n",
                progname, strerror(errno));
        warned = 1;
    }
}

/* Five DTR pulses put the ATmega32U4 into loader mode. */
static void mojo_reset(int fd)
{
    int i;

    set_dtr(fd, 0);
    sleep_ms(5);
    for (i = 0; i < 5; i++) {
        set_dtr(fd, 0);
        sleep_ms(5);
        set_dtr(fd, 1);
        sleep_ms(5);
    }
    /* Drop anything the FPGA design was sending before the reset. */
    sleep_ms(20);
    tcflush(fd, TCIFLUSH);
}

static void wait_fd(int fd, short events, int64_t deadline, const char *what)
{
    struct pollfd pfd = { .fd = fd, .events = events };

    for (;;) {
        int64_t left = deadline - now_ms();
        int r;

        if (left <= 0)
            fail("timeout while %s", what);
        r = poll(&pfd, 1, (int)left);
        if (r > 0)
            return;         /* ready, or error/hangup for read/write to report */
        if (r < 0 && errno != EINTR)
            fail("poll: %s", strerror(errno));
    }
}

static void serial_write(int fd, const uint8_t *buf, size_t len, const char *what)
{
    int64_t deadline = now_ms() + timeout_ms;

    while (len > 0) {
        ssize_t n = write(fd, buf, len);

        if (n > 0) {
            buf += n;
            len -= (size_t)n;
            deadline = now_ms() + timeout_ms;   /* progress resets the clock */
        } else if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            wait_fd(fd, POLLOUT, deadline, what);
        } else if (n < 0 && errno == EINTR) {
            continue;
        } else {
            fail("write error while %s: %s", what,
                 n < 0 ? strerror(errno) : "device closed");
        }
    }
}

static void serial_read(int fd, uint8_t *buf, size_t len, const char *what)
{
    int64_t deadline = now_ms() + timeout_ms;

    while (len > 0) {
        ssize_t n = read(fd, buf, len);

        if (n > 0) {
            buf += n;
            len -= (size_t)n;
            deadline = now_ms() + timeout_ms;
        } else if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            wait_fd(fd, POLLIN, deadline, what);
        } else if (n < 0 && errno == EINTR) {
            continue;
        } else {
            fail("read error while %s: %s", what,
                 n < 0 ? strerror(errno) : "device disconnected");
        }
    }
}

static void send_byte(int fd, uint8_t c, const char *what)
{
    serial_write(fd, &c, 1, what);
}

static void expect_byte(int fd, uint8_t want, const char *what)
{
    uint8_t got;

    serial_read(fd, &got, 1, what);
    if (got != want)
        fail("unexpected reply while %s: got 0x%02X, wanted 0x%02X ('%c')",
             what, got, want, want);
}

/* ------------------------------------------------------------------ */
/* bitstream file                                                      */
/* ------------------------------------------------------------------ */

static uint8_t *load_file(const char *path, size_t *len_out)
{
    struct stat st;
    uint8_t *data;
    size_t len, off = 0;
    int fd;

    fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0)
        fail("cannot open %s: %s", path, strerror(errno));
    if (fstat(fd, &st) < 0)
        fail("cannot stat %s: %s", path, strerror(errno));
    if (!S_ISREG(st.st_mode))
        fail("%s is not a regular file", path);
    if (st.st_size <= 0)
        fail("%s is empty", path);
    if ((uint64_t)st.st_size > MAX_BIN_SIZE)
        fail("%s is too large (%lld bytes)", path, (long long)st.st_size);

    len = (size_t)st.st_size;
    data = malloc(len);
    if (!data)
        fail("out of memory");
    while (off < len) {
        ssize_t n = read(fd, data + off, len - off);

        if (n < 0 && errno == EINTR)
            continue;
        if (n <= 0)
            fail("cannot read %s: %s", path,
                 n < 0 ? strerror(errno) : "file truncated");
        off += (size_t)n;
    }
    close(fd);
    *len_out = len;
    return data;
}

/* ------------------------------------------------------------------ */
/* operations                                                          */
/* ------------------------------------------------------------------ */

static void do_erase(int fd)
{
    printf("Erasing flash...\n");
    send_byte(fd, 'E', "sending erase command");
    expect_byte(fd, 'D', "waiting for erase to finish");
}

static void do_verify(int fd, const uint8_t *data, size_t len)
{
    uint8_t hdr[FLASH_HEADER_LEN];
    uint8_t buf[CHUNK_SIZE];
    uint32_t flash_size;
    size_t off = 0;

    send_byte(fd, 'S', "requesting flash contents");
    serial_read(fd, hdr, sizeof hdr, "reading flash header");
    if (hdr[0] != 0xAA)
        fail("verify failed: flash start byte is 0x%02X, expected 0xAA", hdr[0]);

    flash_size = (uint32_t)hdr[1] | (uint32_t)hdr[2] << 8 |
                 (uint32_t)hdr[3] << 16 | (uint32_t)hdr[4] << 24;
    if (flash_size != len + FLASH_HEADER_LEN)
        fail("verify failed: flash holds %lu bytes, expected %lu",
             (unsigned long)flash_size, (unsigned long)(len + FLASH_HEADER_LEN));

    while (off < len) {
        size_t n = len - off < sizeof buf ? len - off : sizeof buf;
        size_t i;

        serial_read(fd, buf, n, "reading back flash");
        for (i = 0; i < n; i++)
            if (buf[i] != data[off + i])
                fail("verify failed at byte %lu: flash 0x%02X, file 0x%02X",
                     (unsigned long)(off + i), buf[i], data[off + i]);
        off += n;
        progress("Verifying", off, len);
    }
}

static void do_load(int fd, enum mode mode, const uint8_t *data, size_t len)
{
    uint8_t size_le[4];
    size_t off = 0;
    char cmd = mode == MODE_RAM ? 'R' : mode == MODE_FLASH_VERIFY ? 'V' : 'F';

    printf("Loading %lu bytes to %s...\n", (unsigned long)len,
           mode == MODE_RAM ? "FPGA (RAM only)" : "flash");

    send_byte(fd, (uint8_t)cmd, "sending load command");
    expect_byte(fd, 'R', "waiting for the Mojo to respond "
                "(right port? loader firmware up to date?)");

    size_le[0] = (uint8_t)(len);
    size_le[1] = (uint8_t)(len >> 8);
    size_le[2] = (uint8_t)(len >> 16);
    size_le[3] = (uint8_t)(len >> 24);
    serial_write(fd, size_le, sizeof size_le, "sending bitstream size");
    expect_byte(fd, 'O', "waiting for size acknowledge");

    while (off < len) {
        size_t n = len - off < CHUNK_SIZE ? len - off : CHUNK_SIZE;

        serial_write(fd, data + off, n, "sending bitstream");
        off += n;
        progress("Writing", off, len);
    }
    expect_byte(fd, 'D', "waiting for transfer acknowledge");

    if (mode == MODE_FLASH_VERIFY)
        do_verify(fd, data, len);

    if (mode != MODE_RAM) {
        send_byte(fd, 'L', "sending load-from-flash command");
        expect_byte(fd, 'D', "waiting for FPGA to configure from flash");
    }
}

/* ------------------------------------------------------------------ */
/* main                                                                */
/* ------------------------------------------------------------------ */

static void usage(FILE *out)
{
    fprintf(out,
        "Usage: %s [-d device] [-r | -v] -f design.bin\n"
        "       %s [-d device] -c\n"
        "\n"
        "  -f FILE   bitstream (.bin from ISE, bitgen -g Binary:Yes)\n"
        "  -d DEV    serial port of the Mojo (default " DEFAULT_DEVICE ")\n"
        "  -r        load into the FPGA only (RAM); flash is left untouched\n"
        "  -v        write to flash, then read back and verify\n"
        "  -c        erase the flash\n"
        "  -t SEC    timeout per protocol step (default %d)\n"
        "  -p        print progress as plain lines (for GUIs and scripts)\n"
        "  -h        show this help\n"
        "\n"
        "Without -r or -v the bitstream is written to flash and loaded.\n",
        progname, progname, DEFAULT_TIMEOUT);
}

int main(int argc, char **argv)
{
    const char *dev = DEFAULT_DEVICE;
    const char *file = NULL;
    int opt_erase = 0, opt_ram = 0, opt_verify = 0;
    enum mode mode;
    uint8_t *data = NULL;
    size_t len = 0;
    int fd, c;

    if (argc > 0 && argv[0] && *argv[0])
        progname = argv[0];
    setvbuf(stdout, NULL, _IOLBF, 0);      /* keep output ordered when piped */

    while ((c = getopt(argc, argv, "crvpd:f:t:h")) != -1) {
        switch (c) {
        case 'c': opt_erase = 1; break;
        case 'r': opt_ram = 1; break;
        case 'v': opt_verify = 1; break;
        case 'p': progress_lines = 1; break;
        case 'd': dev = optarg; break;
        case 'f': file = optarg; break;
        case 't': {
            char *end;
            long sec = strtol(optarg, &end, 10);

            if (*optarg == '\0' || *end != '\0' || sec < 1 || sec > 3600) {
                fprintf(stderr, "%s: bad timeout '%s'\n", progname, optarg);
                return 2;
            }
            timeout_ms = (int)sec * 1000;
            break;
        }
        case 'h':
            usage(stdout);
            return EXIT_SUCCESS;
        default:
            usage(stderr);
            return 2;
        }
    }

    if (optind < argc) {
        fprintf(stderr, "%s: unexpected argument '%s'\n", progname, argv[optind]);
        usage(stderr);
        return 2;
    }
    if (opt_erase && (file || opt_ram || opt_verify)) {
        fprintf(stderr, "%s: -c cannot be combined with -f, -r or -v\n", progname);
        return 2;
    }
    if (opt_ram && opt_verify) {
        fprintf(stderr, "%s: -v verifies flash; it cannot be used with -r\n", progname);
        return 2;
    }
    if (!opt_erase && !file) {
        fprintf(stderr, "%s: no bitstream given (-f), nothing to do\n", progname);
        usage(stderr);
        return 2;
    }

    mode = opt_erase ? MODE_ERASE :
           opt_ram ? MODE_RAM :
           opt_verify ? MODE_FLASH_VERIFY : MODE_FLASH;

    if (mode != MODE_ERASE)
        data = load_file(file, &len);      /* fail early, before touching the board */

    show_progress = progress_lines || isatty(STDERR_FILENO);

    fd = serial_open(dev);
    mojo_reset(fd);

    if (mode == MODE_ERASE)
        do_erase(fd);
    else
        do_load(fd, mode, data, len);

    free(data);
    close(fd);
    printf("Done.\n");
    return EXIT_SUCCESS;
}
