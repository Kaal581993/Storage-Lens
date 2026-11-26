#define _POSIX_C_SOURCE 200809L
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/nvme_ioctl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <unistd.h>

/* Read-only hardware interface: sysfs identity + NVMe Get Log Page 02h only.
 * No arbitrary paths, write commands, firmware operations or shell execution. */
static int valid_controller(const char *name) {
    if (strncmp(name, "nvme", 4) != 0) return 0;
    size_t digits = strlen(name + 4);
    if (digits == 0 || digits > 6) return 0;
    for (size_t i = 4; name[i] != '\0'; i++) if (name[i] < '0' || name[i] > '9') return 0;
    return 1;
}
static void json_string(const char *value) {
    putchar('"');
    for (const unsigned char *p = (const unsigned char *)value; *p; p++) {
        if (*p == '"' || *p == '\\') { putchar('\\'); putchar(*p); }
        else if (*p >= 32 && *p < 127) putchar(*p);
        else putchar(' ');
    }
    putchar('"');
}
static void model_for(const char *controller, char model[129]) {
    char path[256]; model[0] = '\0';
    int count = snprintf(path, sizeof(path), "/sys/class/nvme/%s/model", controller);
    if (count < 0 || (size_t)count >= sizeof(path)) return;
    FILE *file = fopen(path, "r");
    if (file == NULL) return;
    if (fgets(model, 129, file) == NULL) model[0] = '\0';
    fclose(file);
    size_t length = strlen(model);
    while (length && (model[length - 1] == '\n' || model[length - 1] == '\r' || model[length - 1] == ' ')) model[--length] = '\0';
}
static void identity(const char *controller) {
    char model[129]; model_for(controller, model);
    fputs("\"controller\":", stdout); json_string(controller);
    fputs(",\"model\":", stdout); json_string(model);
}
static void decimal128(const unsigned char *bytes, char output[40]) {
    __uint128_t value = 0;
    for (int i = 15; i >= 0; i--) value = (value << 8) | bytes[i];
    char reverse[40]; size_t count = 0;
    do { reverse[count++] = (char)('0' + value % 10); value /= 10; } while (value != 0);
    for (size_t i = 0; i < count; i++) output[i] = reverse[count - i - 1];
    output[count] = '\0';
}
static void print_smart(const unsigned char data[512]) {
    char written[40], commands[40];
    /* NVM Express SMART log: 48..63 Data Units Written, 80..95 Host Write Commands. */
    decimal128(data + 48, written); decimal128(data + 80, commands);
    printf(",\"readable\":true,\"percentage_used\":%u,\"available_spare\":%u,\"critical_warning\":%u,\"temperature_kelvin\":%u,\"data_units_written\":",
        (unsigned)data[5], (unsigned)data[3], (unsigned)data[0], (unsigned)data[1] | ((unsigned)data[2] << 8));
    json_string(written); fputs(",\"host_write_commands\":", stdout); json_string(commands);
}
static int read_smart(const char *controller) {
    char path[64]; snprintf(path, sizeof(path), "/dev/%s", controller);
    int device = open(path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    int error = device < 0 ? errno : 0; struct stat attrs;
    unsigned char *data = NULL;
    if (!error && (fstat(device, &attrs) < 0 || !S_ISCHR(attrs.st_mode))) error = ENODEV;
    if (!error && posix_memalign((void **)&data, 4096, 4096) != 0) error = ENOMEM;
    if (!error) {
        memset(data, 0, 4096);
        struct nvme_admin_cmd command; memset(&command, 0, sizeof(command));
        command.opcode = 0x02; /* Get Log Page, never a write/admin mutation opcode. */
        command.nsid = 0xffffffffU; command.addr = (uint64_t)(uintptr_t)data;
        command.data_len = 512; command.cdw10 = (127U << 16) | 2U; command.timeout_ms = 5000;
        int status = ioctl(device, NVME_IOCTL_ADMIN_CMD, &command);
        if (status != 0) error = status < 0 ? errno : EIO;
    }
    if (device >= 0) close(device);
    putchar('{'); identity(controller);
    if (error) { printf(",\"readable\":false,\"errno\":%d,\"error\":", error); json_string(strerror(error)); }
    else print_smart(data);
    puts("}"); free(data); return error ? 2 : 0;
}
int main(int argc, char **argv) {
#ifdef STORAGE_LENS_NVME_TEST
    if (argc == 2 && strcmp(argv[1], "--self-test") == 0) {
        unsigned char fixture[512] = {0}; char value[40];
        fixture[5] = 12; fixture[3] = 99; fixture[1] = 0x2c; fixture[2] = 1;
        fixture[48] = 0x15; fixture[49] = 0xcd; fixture[50] = 0x5b; fixture[51] = 7;
        fixture[80] = 0xb1; fixture[81] = 0x68; fixture[82] = 0xde; fixture[83] = 0x3a;
        decimal128(fixture + 48, value); if (strcmp(value, "123456789") != 0) return 1;
        decimal128(fixture + 80, value); if (strcmp(value, "987654321") != 0) return 1;
        memset(fixture + 48, 0xff, 16); decimal128(fixture + 48, value);
        if (strcmp(value, "340282366920938463463374607431768211455") != 0) return 1;
        fputs("{\"fixture\":true", stdout); print_smart(fixture); puts("}"); return 0;
    }
#endif
    if (argc == 2 && strcmp(argv[1], "--list") == 0) {
        DIR *directory = opendir("/sys/class/nvme");
        if (directory == NULL) { puts("[]"); return 0; }
        struct dirent *entry; int first = 1; putchar('[');
        while ((entry = readdir(directory)) != NULL) if (valid_controller(entry->d_name)) {
            if (!first) putchar(',');
            first = 0; putchar('{'); identity(entry->d_name); putchar('}');
        }
        closedir(directory); puts("]"); return 0;
    }
    if (argc == 3 && strcmp(argv[1], "--read") == 0 && valid_controller(argv[2])) return read_smart(argv[2]);
    fputs("Storage Lens NVMe reader: --list | --read nvmeN\nRead-only SMART health; no arbitrary device paths accepted.\n", stderr); return 64;
}
