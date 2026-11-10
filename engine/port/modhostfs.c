// The `_hostfs` module: raw access to the computer's drive through the host.
// The ROM (rom/_boot.py) wraps it in a MicroPython VFS, so programs use the
// ordinary open(), os and import. All path checking and quotas are enforced
// by the host, never here.
#include "py/runtime.h"
#include "py/mperrno.h"
#include "py/mphal.h"

// _hostfs.call(op: int, path: str, arg: bytes | str = b"") -> bytes
// Raises OSError(errno) if the host refuses or fails the operation.
static mp_obj_t modhostfs_call(size_t n_args, const mp_obj_t *args) {
    int32_t op = mp_obj_get_int(args[0]);
    size_t path_len;
    const char *path = mp_obj_str_get_data(args[1], &path_len);
    const char *arg = "";
    size_t arg_len = 0;
    if (n_args > 2) {
        mp_buffer_info_t bufinfo;
        mp_get_buffer_raise(args[2], &bufinfo, MP_BUFFER_READ);
        arg = bufinfo.buf;
        arg_len = bufinfo.len;
    }
    int32_t n = host_fs_call(op, path, (uint32_t)path_len, arg, (uint32_t)arg_len);
    if (n < 0) {
        mp_raise_OSError(-n);
    }
    vstr_t vstr;
    vstr_init_len(&vstr, (size_t)n);
    host_fs_result(vstr.buf, (uint32_t)n);
    return mp_obj_new_bytes_from_vstr(&vstr);
}
static MP_DEFINE_CONST_FUN_OBJ_VAR_BETWEEN(modhostfs_call_obj, 2, 3, modhostfs_call);

static const mp_rom_map_elem_t modhostfs_globals_table[] = {
    { MP_ROM_QSTR(MP_QSTR___name__), MP_ROM_QSTR(MP_QSTR__hostfs) },
    { MP_ROM_QSTR(MP_QSTR_call), MP_ROM_PTR(&modhostfs_call_obj) },
};
static MP_DEFINE_CONST_DICT(modhostfs_globals, modhostfs_globals_table);

const mp_obj_module_t modhostfs_module = {
    .base = { &mp_type_module },
    .globals = (mp_obj_dict_t *)&modhostfs_globals,
};
MP_REGISTER_MODULE(MP_QSTR__hostfs, modhostfs_module);
