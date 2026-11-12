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
extern int32_t mpy_where_line; // vm_split.c: the last line of the player's own code that ran
extern qstr mpy_where_file;

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

    // Run the frozen ROM's boot module, which mounts the drive at "/".
    nlr_buf_t nlr;
    if (nlr_push(&nlr) == 0) {
        mp_import_name(MP_QSTR__boot, mp_const_none, MP_OBJ_NEW_SMALL_INT(0));
        nlr_pop();
    } else {
        mp_obj_print_exception(&mp_plat_print, MP_OBJ_FROM_PTR(nlr.ret_val));
        return 2;
    }
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
    mpy_where_line = 0; // nothing of this run has been seen yet
    mpy_where_file = 0;
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

// Where the program is, for the editor (see vm_split.c): the line of the player's own code
// that is running, with its file's name copied into buf (up to cap bytes, then a 0).
// Returns the line, or 0 if no code of theirs is running.
extern int32_t mpy_where_now(qstr *file);
extern int32_t mpy_trace, mpy_stepping;

WASM_EXPORT("mpy_where") int32_t mpy_where(char *buf, int32_t cap) {
    qstr file = 0;
    int32_t line = mpy_where_now(&file);
    if (cap > 0) {
        buf[0] = 0;
    }
    if (line > 0 && cap > 1) {
        size_t len;
        const char *name = (const char *)qstr_data(file, &len);
        size_t n = len < (size_t)(cap - 1) ? len : (size_t)(cap - 1);
        memcpy(buf, name, n);
        buf[n] = 0;
    }
    return line;
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

#define MPY_STEP_BIT (0x40000000)
#define MPY_WATCH_BIT (0x20000000)

void mpy_budget_expired(void) {
    int32_t next = host_slice();
    if (next <= 0) {
        mpy_budget = 0x7fffffff;
        // Ctrl+C. KeyboardInterrupt, as in Python, so "except Exception" doesn't swallow it.
        mp_raise_type(&mp_type_KeyboardInterrupt);
    }
    // The host asks for the traced loop (vm_split.c) with high bits of the budget: one
    // while someone watches (to note the line), one to step.
    mpy_stepping = (next & MPY_STEP_BIT) != 0;
    mpy_trace = mpy_stepping ? 1 : (next & MPY_WATCH_BIT) != 0 ? 2 : 0;
    mpy_budget = next & ~(MPY_STEP_BIT | MPY_WATCH_BIT);
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

void nlr_jump_fail(void *val) {
    (void)val;
    __builtin_trap();
}
