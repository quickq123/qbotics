// Entry points the Java host calls (Wasm exports), plus the port glue
// MicroPython needs: GC root scanning, metering and stubs for things a
// sandboxed computer doesn't have.
#include <stdlib.h>
#include <string.h>

#include "py/builtin.h"
#include "py/compile.h"
#include "py/cstack.h"
#include "py/gc.h"
#include "py/lexer.h"
#include "py/mperrno.h"
#include "py/mphal.h"
#include "py/repl.h"
#include "py/runtime.h"

#define WASM_EXPORT(name) __attribute__((export_name(name)))

// Top of the C shadow stack (it grows down). Defined by wasm-ld.
extern char __stack_high;

static char *heap;

int32_t mpy_budget = 0x7fffffff;

// Start the interpreter with a fixed heap (design doc §3.4). Returns 0 on success.
WASM_EXPORT("mpy_init") int mpy_init(uint32_t heap_size) {
    heap = malloc(heap_size);
    if (heap == NULL) {
        return 1;
    }
    // Leave headroom so "recursion too deep" is a Python error, not a trap.
    mp_cstack_init_with_top(&__stack_high, MPY_STACK_SIZE - 16 * 1024);
    gc_init(heap, heap + heap_size);
    mp_init();
    return 0;
}

// Scratch memory so the host can copy source code in.
WASM_EXPORT("mpy_alloc") void *mpy_alloc(uint32_t size) {
    return malloc(size);
}

WASM_EXPORT("mpy_free") void mpy_free(void *ptr) {
    free(ptr);
}

// Compile and run a script. Globals persist between calls.
// repl = 0: a whole file. repl = 1: one REPL entry; the value of a bare
// expression is printed, as at the Python prompt.
// Returns 0 on success, 1 if it ended with an uncaught exception.
WASM_EXPORT("mpy_exec") int mpy_exec(const char *src, uint32_t len, int repl) {
    nlr_buf_t nlr;
    if (nlr_push(&nlr) == 0) {
        mp_lexer_t *lex = mp_lexer_new_from_str_len(MP_QSTR__lt_stdin_gt_, src, len, 0);
        qstr source_name = lex->source_name;
        mp_parse_tree_t parse_tree = mp_parse(lex, repl ? MP_PARSE_SINGLE_INPUT : MP_PARSE_FILE_INPUT);
        mp_obj_t module_fun = mp_compile(&parse_tree, source_name, repl != 0);
        mp_call_function_0(module_fun);
        nlr_pop();
        return 0;
    } else {
        mp_obj_print_exception(&mp_plat_print, MP_OBJ_FROM_PTR(nlr.ret_val));
        return 1;
    }
}

// REPL helper: does this (NUL-terminated) input need another line before it
// can run? True for an open bracket or string, or a compound statement such
// as "if x:" that hasn't been closed with a blank line.
WASM_EXPORT("mpy_repl_needs_more") int mpy_repl_needs_more(const char *src) {
    return mp_repl_continue_with_input(src) ? 1 : 0;
}

// Let the host set the first budget before a run.
WASM_EXPORT("mpy_set_budget") void mpy_set_budget(int32_t budget) {
    mpy_budget = budget;
}

WASM_EXPORT("mpy_gc_collect") void mpy_gc_collect(void) {
    gc_collect();
}

// Bytes of heap in use; for the benchmark harness.
WASM_EXPORT("mpy_heap_used") uint32_t mpy_heap_used(void) {
    gc_info_t info;
    gc_info(&info);
    return (uint32_t)info.used;
}

void mpy_budget_expired(void) {
    int32_t next = host_slice();
    if (next <= 0) {
        mpy_budget = 0x7fffffff;
        mp_raise_msg(&mp_type_RuntimeError, MP_ERROR_TEXT("program stopped by the computer"));
    }
    mpy_budget = next;
}

// Conservative GC roots. `wasm-opt --spill-pointers` guarantees every live
// pointer held in a Wasm local is also on the shadow stack, so scanning from
// here up to the top of that stack finds them all.
void gc_collect(void) {
    gc_collect_start();
    volatile uintptr_t marker = 0;
    uintptr_t sp = (uintptr_t)&marker;
    uintptr_t top = (uintptr_t)&__stack_high;
    gc_collect_root((void **)sp, (top - sp) / sizeof(void *));
    gc_collect_end();
}

mp_import_stat_t mp_import_stat(const char *path) {
    (void)path;
    return MP_IMPORT_STAT_NO_EXIST;
}

mp_lexer_t *mp_lexer_new_from_file(qstr filename) {
    (void)filename;
    mp_raise_OSError(MP_ENOENT);
}

mp_obj_t mp_builtin_open(size_t n_args, const mp_obj_t *args, mp_map_t *kwargs) {
    (void)n_args;
    (void)args;
    (void)kwargs;
    mp_raise_OSError(MP_ENOENT);
}
MP_DEFINE_CONST_FUN_OBJ_KW(mp_builtin_open_obj, 1, mp_builtin_open);

void nlr_jump_fail(void *val) {
    (void)val;
    __builtin_trap();
}
