#include <stddef.h>
#include <stdint.h>
#include "py/mpconfig.h"

// Functions the Java host provides (Wasm imports, module "host").
#define HOST_IMPORT(name) __attribute__((import_module("host"), import_name(name)))

HOST_IMPORT("write") void host_write(const char *ptr, uint32_t len);
HOST_IMPORT("ticks_us") int64_t host_ticks_us(void);
// Returns 1 if Ctrl+C cut the sleep short, else 0.
HOST_IMPORT("sleep_ms") int32_t host_sleep_ms(int32_t ms);
// Returns a fresh budget (> 0) to continue, or <= 0 to stop the program.
HOST_IMPORT("slice") int32_t host_slice(void);
// Queue a call; returns a ticket immediately.
HOST_IMPORT("submit") int32_t host_submit(const char *ptr, uint32_t len);
// Block until the ticket completes or timeout_ms passes (-1 = forever).
// Writes up to cap bytes of result into buf; returns the full result length,
// -1 on timeout, or -3 if Ctrl+C stopped the wait.
HOST_IMPORT("wait") int32_t host_wait(int32_t ticket, int32_t timeout_ms, char *buf, uint32_t cap);
// Block until a line is entered at the terminal; writes it (without the line
// break) into buf. Returns its length, -1 if interrupted, -2 if there is no terminal.
HOST_IMPORT("read_line") int32_t host_read_line(char *buf, uint32_t cap);

// File system. Runs operation `op` on `path`; `arg` is extra data (bytes to
// write, or the new path for a rename). Returns the length of the result, or a
// negative errno. The result bytes are then fetched with host_fs_result.
HOST_IMPORT("fs_call") int32_t host_fs_call(int32_t op, const char *path, uint32_t path_len, const char *arg, uint32_t arg_len);
HOST_IMPORT("fs_result") void host_fs_result(char *buf, uint32_t cap);

static inline void mp_hal_set_interrupt_char(char c) {
    (void)c;
}

mp_uint_t mp_hal_ticks_ms(void);
mp_uint_t mp_hal_ticks_us(void);
mp_uint_t mp_hal_ticks_cpu(void);
void mp_hal_delay_ms(mp_uint_t ms);
void mp_hal_delay_us(mp_uint_t us);
