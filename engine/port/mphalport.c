#include "py/mphal.h"
#include "py/runtime.h"

mp_uint_t mp_hal_stdout_tx_strn(const char *str, size_t len) {
    host_write(str, (uint32_t)len);
    return len;
}

void mp_hal_stdout_tx_strn_cooked(const char *str, size_t len) {
    host_write(str, (uint32_t)len);
}

void mp_hal_stdout_tx_str(const char *str) {
    mp_hal_stdout_tx_strn(str, strlen(str));
}

int mp_hal_stdin_rx_chr(void) {
    return -1;
}

mp_uint_t mp_hal_ticks_ms(void) {
    return (mp_uint_t)(host_ticks_us() / 1000);
}

mp_uint_t mp_hal_ticks_us(void) {
    return (mp_uint_t)host_ticks_us();
}

mp_uint_t mp_hal_ticks_cpu(void) {
    return (mp_uint_t)host_ticks_us();
}

void mp_hal_delay_ms(mp_uint_t ms) {
    host_sleep_ms((int32_t)ms);
}

void mp_hal_delay_us(mp_uint_t us) {
    host_sleep_ms((int32_t)((us + 999) / 1000));
}
