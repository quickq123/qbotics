// The `_host` module: the two primitives from design doc §2.3.
// Everything else (blocking and async APIs) is built on these in Python.
#include "py/runtime.h"
#include "py/mphal.h"

#define RESULT_BUF_SIZE (4096)
#define RESULT_MAX_SIZE (48 * 1024)
static char result_buf[RESULT_BUF_SIZE];

// _host.submit(call: str) -> ticket: int
static mp_obj_t modhost_submit(mp_obj_t call_in) {
    size_t len;
    const char *call = mp_obj_str_get_data(call_in, &len);
    return mp_obj_new_int(host_submit(call, (uint32_t)len));
}
static MP_DEFINE_CONST_FUN_OBJ_1(modhost_submit_obj, modhost_submit);

// _host.wait(ticket: int, timeout_ms: int = -1) -> str | None (None = timed out).
// Ctrl+C while waiting raises KeyboardInterrupt.
static mp_obj_t modhost_wait(size_t n_args, const mp_obj_t *args) {
    int32_t ticket = mp_obj_get_int(args[0]);
    int32_t timeout_ms = n_args > 1 ? mp_obj_get_int(args[1]) : -1;
    int32_t n = host_wait(ticket, timeout_ms, result_buf, RESULT_BUF_SIZE);
    if (n == -3) {
        mp_raise_type(&mp_type_KeyboardInterrupt);
    }
    if (n < 0) {
        return mp_const_none;
    }
    if (n <= RESULT_BUF_SIZE) {
        return mp_obj_new_str(result_buf, (size_t)n);
    }
    // Too big for the fixed buffer: the host has kept the answer, so ask again
    // with a buffer from the Python heap (MemoryError if the heap can't hold it).
    if (n > RESULT_MAX_SIZE) { // the host never sends more than this
        mp_raise_ValueError(MP_ERROR_TEXT("host result too large"));
    }
    vstr_t vstr;
    vstr_init_len(&vstr, (size_t)n);
    int32_t n2 = host_wait(ticket, 0, vstr.buf, (uint32_t)n);
    if (n2 != n) {
        vstr_clear(&vstr);
        mp_raise_ValueError(MP_ERROR_TEXT("host result changed"));
    }
    return mp_obj_new_str_from_vstr(&vstr);
}
static MP_DEFINE_CONST_FUN_OBJ_VAR_BETWEEN(modhost_wait_obj, 1, 2, modhost_wait);

static const mp_rom_map_elem_t modhost_globals_table[] = {
    { MP_ROM_QSTR(MP_QSTR___name__), MP_ROM_QSTR(MP_QSTR__host) },
    { MP_ROM_QSTR(MP_QSTR_submit), MP_ROM_PTR(&modhost_submit_obj) },
    { MP_ROM_QSTR(MP_QSTR_wait), MP_ROM_PTR(&modhost_wait_obj) },
};
static MP_DEFINE_CONST_DICT(modhost_globals, modhost_globals_table);

const mp_obj_module_t modhost_module = {
    .base = { &mp_type_module },
    .globals = (mp_obj_dict_t *)&modhost_globals,
};
MP_REGISTER_MODULE(MP_QSTR__host, modhost_module);

// input([prompt]) -> str: Python's built-in for asking the person at the terminal.
// Blocks until a line is entered. Ctrl+C raises KeyboardInterrupt; where there is
// no terminal to ask (a scratchpad run) it raises EOFError, as Python does.
static mp_obj_t modhost_input(size_t n_args, const mp_obj_t *args) {
    if (n_args == 1) {
        mp_obj_print_helper(&mp_plat_print, args[0], PRINT_STR);
    }
    int32_t n = host_read_line(result_buf, RESULT_BUF_SIZE);
    if (n == -1) {
        mp_raise_type(&mp_type_KeyboardInterrupt);
    }
    if (n < 0) {
        mp_raise_msg(&mp_type_EOFError, MP_ERROR_TEXT("input() needs the terminal"));
    }
    return mp_obj_new_str(result_buf, (size_t)n);
}
MP_DEFINE_CONST_FUN_OBJ_VAR_BETWEEN(modhost_input_obj, 0, 1, modhost_input);
