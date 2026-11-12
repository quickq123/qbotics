/*
 * This file is derived from py/vm.c of the MicroPython project, http://micropython.org/
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2013-2019 Damien P. George
 * Copyright (c) 2014-2015 Paul Sokolovsky
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

// A replacement for py/vm.c with the same behaviour but a different shape.
//
// Why: py/vm.c is one very large function. Compiled to Wasm and then to JVM
// bytecode it becomes a 42 KB method, and HotSpot never JIT-compiles methods
// over 8000 bytes, so the whole interpreter ran in the JVM's own interpreter
// (design doc §14.5).
//
// How: every opcode's body is moved, unchanged where possible, into its own
// small function ("handler"). A slim dispatch loop calls them. Shared state
// that py/vm.c kept in C locals lives in a vm_t struct on the C stack.
//
// Keeping in sync: when MicroPython is updated, diff the new py/vm.c against
// the old one and apply the same changes to the matching handler here. The
// handlers are in the same order as py/vm.c. tests/ in MicroPython is run
// against both this file and py/vm.c by the bench harness ("difftest").

#include <stdio.h>
#include <string.h>
#include <assert.h>

#include "py/emitglue.h"
#include "py/objtype.h"
#include "py/objfun.h"
#include "py/runtime.h"
#include "py/bc0.h"

#if MICROPY_STACKLESS || MICROPY_PY_SYS_SETTRACE || MICROPY_PY_THREAD
#error "vm_split.c does not support MICROPY_STACKLESS, MICROPY_PY_SYS_SETTRACE or MICROPY_PY_THREAD"
#endif

// *FORMAT-OFF*

// How a run of the dispatch loop ended.
enum {
    VM_RAISE = 1,       // raise vm->raise_obj at the current opcode
    VM_RETURN,          // function returned; value in *code_state->sp
    VM_YIELD,           // generator yielded; value in *code_state->sp
    VM_RETURN_EXCEPTION, // unrecoverable; exception in code_state->state[0]
    VM_SWITCH,          // tracing started or stopped: carry on in the other copy of the loop
    VM_BACK,            // (vm_enter_traced) tracing stopped: the plain loop carries on
};

typedef struct _vm_t {
    mp_obj_t *sp;                   // Python value stack pointer, between handlers
    mp_code_state_t *code_state;
    mp_obj_t *fastn;                // fastn[0] is local 0, fastn[-1] is local 1, ...
    mp_exc_stack_t *exc_stack;
    mp_exc_stack_t *exc_sp;         // exception stack grows up, points at top
    #if MICROPY_EMIT_BYTECODE_USES_QSTR_TABLE
    const qstr_short_t *qstr_table;
    #endif
    mp_obj_t inject_exc;
    mp_obj_t raise_obj;
    int status;
    int traced;                     // which copy of the loop is running (0 plain)
} vm_t;

// The names py/vm.c uses for its locals, so handler bodies read the same.
// `ip` is a parameter of each handler and `sp` a local loaded from the vm.
#define code_state (vm->code_state)
#define fastn (vm->fastn)
#define exc_stack (vm->exc_stack)
#define exc_sp (vm->exc_sp)

#define DECODE_UINT \
    mp_uint_t unum = 0; \
    do { \
        unum = (unum << 7) + (*ip & 0x7f); \
    } while ((*ip++ & 0x80) != 0)

#define DECODE_ULABEL \
    size_t ulab; \
    do { \
        if (ip[0] & 0x80) { \
            ulab = ((ip[0] & 0x7f) | (ip[1] << 7)); \
            ip += 2; \
        } else { \
            ulab = ip[0]; \
            ip += 1; \
        } \
    } while (0)

#define DECODE_SLABEL \
    size_t slab; \
    do { \
        if (ip[0] & 0x80) { \
            slab = ((ip[0] & 0x7f) | (ip[1] << 7)) - 0x4000; \
            ip += 2; \
        } else { \
            slab = ip[0] - 0x40; \
            ip += 1; \
        } \
    } while (0)

#if MICROPY_EMIT_BYTECODE_USES_QSTR_TABLE

#define DECODE_QSTR \
    DECODE_UINT; \
    qstr qst = vm->qstr_table[unum]

#else

#define DECODE_QSTR \
    DECODE_UINT; \
    qstr qst = unum;

#endif

#define DECODE_PTR \
    DECODE_UINT; \
    void *ptr = (void *)(uintptr_t)code_state->fun_bc->child_table[unum]

#define DECODE_OBJ \
    DECODE_UINT; \
    mp_obj_t obj = (mp_obj_t)code_state->fun_bc->context->constants.obj_table[unum]

#define PUSH(val) *++sp = (val)
#define POP() (*sp--)
#define TOP() (*sp)
#define SET_TOP(val) *sp = (val)

#if MICROPY_PY_SYS_EXC_INFO
#define CLEAR_SYS_EXC_INFO() MP_STATE_VM(cur_exception) = NULL;
#else
#define CLEAR_SYS_EXC_INFO()
#endif

#define PUSH_EXC_BLOCK(with_or_finally) do { \
    DECODE_ULABEL; /* except labels are always forward */ \
    ++exc_sp; \
    exc_sp->handler = ip + ulab; \
    exc_sp->val_sp = MP_TAGPTR_MAKE(sp, ((with_or_finally) << 1)); \
    exc_sp->prev_exc = NULL; \
} while (0)

#define POP_EXC_BLOCK() \
    exc_sp--; /* pop back to previous exception handler */ \
    CLEAR_SYS_EXC_INFO() /* just clear sys.exc_info(), not compliant, but it shouldn't be used in 1st place */

#define CANCEL_ACTIVE_FINALLY(sp) do { \
    if (mp_obj_is_small_int(sp[-1])) { \
        /* Stack: (..., prev_dest_ip, prev_cause, dest_ip) */ \
        /* Cancel the unwind through the previous finally, replace with current one */ \
        sp[-2] = sp[0]; \
        sp -= 2; \
    } else { \
        assert(sp[-1] == mp_const_none || mp_obj_is_exception_instance(sp[-1])); \
        /* Stack: (..., None/exception, dest_ip) */ \
        /* Silence the finally's exception value (may be None or an exception) */ \
        sp[-1] = sp[0]; \
        --sp; \
    } \
} while (0)

// A handler receives ip pointing just past its opcode byte and returns the ip
// of the next opcode to run, or NULL with vm->status set to stop the loop.
#define OP(name) static MP_NOINLINE const byte *op_##name(vm_t *vm, const byte *ip)
#define SP_LOAD() mp_obj_t *sp = vm->sp
#define DISPATCH() do { vm->sp = sp; return ip; } while (0)
#define DISPATCH_WITH_PEND_EXC_CHECK() do { vm->sp = sp; if (vm_pending_check(vm)) { return vm_switch(vm, ip); } return ip; } while (0)
#define STOP(why) do { vm->status = (why); return NULL; } while (0)
// Raising directly (without nlr) lets the exception handler run without a longjmp.
#define RAISE(o) do { vm->raise_obj = (o); STOP(VM_RAISE); } while (0)

// We've just done a branch, use this as a convenient point to run periodic
// code/checks: not _every_ instruction, but on average a branch should occur
// every few instructions. May raise (via nlr).
//
// It also counts the host's budget (MICROPY_VM_HOOK_LOOP, written out here). Only when the
// budget runs out, the rare branch, can the host have asked for line tracing to start or
// stop; then this returns true and the loop switches copies. The rare paths are out of
// line, so the handlers stay as small as before (the JVM compiles small methods best).
extern int32_t mpy_trace;

static MP_NOINLINE bool vm_budget_out(vm_t *vm) {
    mpy_budget_expired();
    return mpy_trace != vm->traced;
}

static MP_NOINLINE const byte *vm_switch(vm_t *vm, const byte *at) {
    code_state->ip = at;
    code_state->sp = vm->sp;
    vm->status = VM_SWITCH;
    return NULL;
}

static inline bool vm_pending_check(vm_t *vm) {
    if (--mpy_budget <= 0 && vm_budget_out(vm)) {
        return true;
    }

    // Check for pending exceptions or scheduled tasks to run.
    if (
    #if MICROPY_ENABLE_SCHEDULER
        // Pending exception sets sched_state, only check sched_state.
        MP_STATE_VM(sched_state) == MP_SCHED_PENDING
    #else
        MP_STATE_THREAD(mp_pending_exception) != MP_OBJ_NULL
    #endif
    #if MICROPY_ENABLE_VM_ABORT
        || MP_STATE_VM(vm_abort)
    #endif
    ) {
        mp_handle_pending(MP_HANDLE_PENDING_CALLBACKS_AND_EXCEPTIONS);
    }
    return false;
}

#if MICROPY_PY_BUILTINS_SLICE
MP_NOINLINE static mp_obj_t *build_slice_stack_allocated(byte op, mp_obj_t *sp, mp_obj_t step) {
    mp_obj_t stop = sp[2];
    mp_obj_t start = sp[1];
    mp_obj_slice_t slice = { .base = { .type = &mp_type_slice }, .start = start, .stop = stop, .step = step };
    if (op == MP_BC_LOAD_SUBSCR) {
        SET_TOP(mp_obj_subscr(TOP(), MP_OBJ_FROM_PTR(&slice), MP_OBJ_SENTINEL));
    } else { // MP_BC_STORE_SUBSCR
        mp_obj_subscr(TOP(), MP_OBJ_FROM_PTR(&slice), sp[-1]);
        sp -= 2;
    }
    return sp;
}
#endif

// ---- shared tails (labels in py/vm.c) --------------------------------------

// local_name_error:
static MP_NOINLINE const byte *vm_local_name_error(vm_t *vm) {
    mp_obj_t obj = mp_obj_new_exception_msg(&mp_type_NameError, MP_ERROR_TEXT("local variable referenced before assignment"));
    RAISE(obj);
}

// unwind_jump: stack is (..., dest_ip, num_exc_handlers_to_unwind)
static MP_NOINLINE const byte *vm_unwind_jump(vm_t *vm, const byte *ip, mp_obj_t *sp) {
    mp_uint_t unum = (mp_uint_t)POP(); // get number of exception handlers to unwind
    while ((unum & 0x7f) > 0) {
        unum -= 1;
        assert(exc_sp >= exc_stack);

        if (MP_TAGPTR_TAG1(exc_sp->val_sp)) {
            if (exc_sp->handler >= ip) {
                // Found a finally handler that isn't active; run it.
                // Getting here the stack looks like:
                //     (..., X, dest_ip)
                // where X is pointed to by exc_sp->val_sp and in the case
                // of a "with" block contains the context manager info.
                assert(&sp[-1] == MP_TAGPTR_PTR(exc_sp->val_sp));
                // We're going to run "finally" code as a coroutine
                // (not calling it recursively). Set up a sentinel
                // on the stack so it can return back to us when it is
                // done (when WITH_CLEANUP or END_FINALLY reached).
                // The sentinel is the number of exception handlers left to
                // unwind, which is a non-negative integer.
                PUSH(MP_OBJ_NEW_SMALL_INT(unum));
                ip = exc_sp->handler;
                DISPATCH();
            } else {
                // Found a finally handler that is already active; cancel it.
                CANCEL_ACTIVE_FINALLY(sp);
            }
        }
        POP_EXC_BLOCK();
    }
    ip = (const byte*)MP_OBJ_TO_PTR(POP()); // pop destination ip for jump
    if (unum != 0) {
        // pop the exhausted iterator
        sp -= MP_OBJ_ITER_BUF_NSLOTS;
    }
    DISPATCH_WITH_PEND_EXC_CHECK();
}

// unwind_return: stack is (..., ret_val)
static MP_NOINLINE const byte *vm_unwind_return(vm_t *vm, const byte *ip, mp_obj_t *sp) {
    // Search for and execute finally handlers that aren't already active
    while (exc_sp >= exc_stack) {
        if (MP_TAGPTR_TAG1(exc_sp->val_sp)) {
            if (exc_sp->handler >= ip) {
                // Found a finally handler that isn't active; run it.
                // Getting here the stack looks like:
                //     (..., X, [iter0, iter1, ...,] ret_val)
                // where X is pointed to by exc_sp->val_sp and in the case
                // of a "with" block contains the context manager info.
                // There may be 0 or more for-iterators between X and the
                // return value, and these must be removed before control can
                // pass to the finally code.  We simply copy the ret_value down
                // over these iterators, if they exist.  If they don't then the
                // following is a null operation.
                mp_obj_t *finally_sp = MP_TAGPTR_PTR(exc_sp->val_sp);
                finally_sp[1] = sp[0];
                sp = &finally_sp[1];
                // We're going to run "finally" code as a coroutine
                // (not calling it recursively). Set up a sentinel
                // on a stack so it can return back to us when it is
                // done (when WITH_CLEANUP or END_FINALLY reached).
                PUSH(MP_OBJ_NEW_SMALL_INT(-1));
                ip = exc_sp->handler;
                DISPATCH();
            } else {
                // Found a finally handler that is already active; cancel it.
                CANCEL_ACTIVE_FINALLY(sp);
            }
        }
        POP_EXC_BLOCK();
    }
    code_state->sp = sp;
    assert(exc_sp == exc_stack - 1);
    MICROPY_VM_HOOK_RETURN
    STOP(VM_RETURN);
}

// yield:
static MP_NOINLINE const byte *vm_yield(vm_t *vm, const byte *ip, mp_obj_t *sp) {
    code_state->ip = ip;
    code_state->sp = sp;
    code_state->exc_sp_idx = MP_CODE_STATE_EXC_SP_IDX_FROM_PTR(exc_stack, exc_sp);
    STOP(VM_YIELD);
}

// ---- opcode handlers, in py/vm.c order -------------------------------------

OP(LOAD_CONST_SMALL_INT) {
    SP_LOAD();
    mp_uint_t num = 0;
    if ((ip[0] & 0x40) != 0) {
        // Number is negative
        num--;
    }
    do {
        num = (num << 7) | (*ip & 0x7f);
    } while ((*ip++ & 0x80) != 0);
    PUSH(MP_OBJ_NEW_SMALL_INT(num));
    DISPATCH();
}

OP(LOAD_CONST_STRING) {
    SP_LOAD();
    DECODE_QSTR;
    PUSH(MP_OBJ_NEW_QSTR(qst));
    DISPATCH();
}

OP(LOAD_CONST_OBJ) {
    SP_LOAD();
    DECODE_OBJ;
    PUSH(obj);
    DISPATCH();
}

OP(LOAD_FAST_N) {
    SP_LOAD();
    DECODE_UINT;
    mp_obj_t obj_shared = fastn[-unum];
    if (obj_shared == MP_OBJ_NULL) {
        return vm_local_name_error(vm);
    }
    PUSH(obj_shared);
    DISPATCH();
}

OP(LOAD_DEREF) {
    SP_LOAD();
    DECODE_UINT;
    mp_obj_t obj_shared = mp_obj_cell_get(fastn[-unum]);
    if (obj_shared == MP_OBJ_NULL) {
        return vm_local_name_error(vm);
    }
    PUSH(obj_shared);
    DISPATCH();
}

OP(LOAD_NAME) {
    SP_LOAD();
    DECODE_QSTR;
    PUSH(mp_load_name(qst));
    DISPATCH();
}

OP(LOAD_GLOBAL) {
    SP_LOAD();
    DECODE_QSTR;
    PUSH(mp_load_global(qst));
    DISPATCH();
}

OP(LOAD_ATTR) {
    SP_LOAD();
    DECODE_QSTR;
    mp_obj_t top = TOP();
    mp_obj_t obj;
    #if MICROPY_OPT_LOAD_ATTR_FAST_PATH
    // For the specific case of an instance type, it implements .attr
    // and forwards to its members map. Attribute lookups on instance
    // types are extremely common, so avoid all the other checks and
    // calls that normally happen first.
    mp_map_elem_t *elem = NULL;
    if (mp_obj_is_instance_type(mp_obj_get_type(top))) {
        mp_obj_instance_t *self = MP_OBJ_TO_PTR(top);
        elem = mp_map_lookup(&self->members, MP_OBJ_NEW_QSTR(qst), MP_MAP_LOOKUP);
    }
    if (elem) {
        obj = elem->value;
    } else
    #endif
    {
        obj = mp_load_attr(top, qst);
    }
    SET_TOP(obj);
    DISPATCH();
}

OP(LOAD_METHOD) {
    SP_LOAD();
    DECODE_QSTR;
    mp_load_method(*sp, qst, sp);
    sp += 1;
    DISPATCH();
}

OP(LOAD_SUPER_METHOD) {
    SP_LOAD();
    DECODE_QSTR;
    sp -= 1;
    mp_load_super_method(qst, sp - 1);
    DISPATCH();
}

OP(LOAD_BUILD_CLASS) {
    SP_LOAD();
    PUSH(mp_load_build_class());
    DISPATCH();
}

OP(LOAD_SUBSCR) {
    SP_LOAD();
    mp_obj_t index = POP();
    SET_TOP(mp_obj_subscr(TOP(), index, MP_OBJ_SENTINEL));
    DISPATCH();
}

OP(STORE_FAST_N) {
    SP_LOAD();
    DECODE_UINT;
    fastn[-unum] = POP();
    DISPATCH();
}

OP(STORE_DEREF) {
    SP_LOAD();
    DECODE_UINT;
    mp_obj_cell_set(fastn[-unum], POP());
    DISPATCH();
}

OP(STORE_NAME) {
    SP_LOAD();
    DECODE_QSTR;
    mp_store_name(qst, POP());
    DISPATCH();
}

OP(STORE_GLOBAL) {
    SP_LOAD();
    DECODE_QSTR;
    mp_store_global(qst, POP());
    DISPATCH();
}

OP(STORE_ATTR) {
    SP_LOAD();
    DECODE_QSTR;
    mp_store_attr(sp[0], qst, sp[-1]);
    sp -= 2;
    DISPATCH();
}

OP(STORE_SUBSCR) {
    SP_LOAD();
    mp_obj_subscr(sp[-1], sp[0], sp[-2]);
    sp -= 3;
    DISPATCH();
}

OP(DELETE_FAST) {
    DECODE_UINT;
    if (fastn[-unum] == MP_OBJ_NULL) {
        return vm_local_name_error(vm);
    }
    fastn[-unum] = MP_OBJ_NULL;
    return ip;
}

OP(DELETE_DEREF) {
    DECODE_UINT;
    if (mp_obj_cell_get(fastn[-unum]) == MP_OBJ_NULL) {
        return vm_local_name_error(vm);
    }
    mp_obj_cell_set(fastn[-unum], MP_OBJ_NULL);
    return ip;
}

OP(DELETE_NAME) {
    DECODE_QSTR;
    mp_delete_name(qst);
    return ip;
}

OP(DELETE_GLOBAL) {
    DECODE_QSTR;
    mp_delete_global(qst);
    return ip;
}

OP(DUP_TOP_TWO) {
    SP_LOAD();
    sp += 2;
    sp[0] = sp[-2];
    sp[-1] = sp[-3];
    DISPATCH();
}

OP(ROT_TWO) {
    SP_LOAD();
    mp_obj_t top = sp[0];
    sp[0] = sp[-1];
    sp[-1] = top;
    return ip;
}

OP(ROT_THREE) {
    SP_LOAD();
    mp_obj_t top = sp[0];
    sp[0] = sp[-1];
    sp[-1] = sp[-2];
    sp[-2] = top;
    return ip;
}

OP(JUMP) {
    DECODE_SLABEL;
    ip += slab;
    if (vm_pending_check(vm)) {
        return vm_switch(vm, ip);
    }
    return ip;
}

OP(POP_JUMP_IF_TRUE) {
    SP_LOAD();
    DECODE_SLABEL;
    if (mp_obj_is_true(POP())) {
        ip += slab;
    }
    DISPATCH_WITH_PEND_EXC_CHECK();
}

OP(POP_JUMP_IF_FALSE) {
    SP_LOAD();
    DECODE_SLABEL;
    if (!mp_obj_is_true(POP())) {
        ip += slab;
    }
    DISPATCH_WITH_PEND_EXC_CHECK();
}

OP(JUMP_IF_TRUE_OR_POP) {
    SP_LOAD();
    DECODE_ULABEL;
    if (mp_obj_is_true(TOP())) {
        ip += ulab;
    } else {
        sp--;
    }
    DISPATCH_WITH_PEND_EXC_CHECK();
}

OP(JUMP_IF_FALSE_OR_POP) {
    SP_LOAD();
    DECODE_ULABEL;
    if (mp_obj_is_true(TOP())) {
        sp--;
    } else {
        ip += ulab;
    }
    DISPATCH_WITH_PEND_EXC_CHECK();
}

OP(SETUP_WITH) {
    SP_LOAD();
    // stack: (..., ctx_mgr)
    mp_obj_t obj = TOP();
    mp_load_method(obj, MP_QSTR___exit__, sp);
    mp_load_method(obj, MP_QSTR___enter__, sp + 2);
    mp_obj_t ret = mp_call_method_n_kw(0, 0, sp + 2);
    sp += 1;
    PUSH_EXC_BLOCK(1);
    PUSH(ret);
    // stack: (..., __exit__, ctx_mgr, as_value)
    DISPATCH();
}

OP(WITH_CLEANUP) {
    SP_LOAD();
    // Arriving here, there's "exception control block" on top of stack,
    // and __exit__ method (with self) underneath it. Bytecode calls __exit__,
    // and "deletes" it off stack, shifting "exception control block"
    // to its place.
    // The bytecode emitter ensures that there is enough space on the Python
    // value stack to hold the __exit__ method plus an additional 4 entries.
    if (TOP() == mp_const_none) {
        // stack: (..., __exit__, ctx_mgr, None)
        sp[1] = mp_const_none;
        sp[2] = mp_const_none;
        sp -= 2;
        mp_call_method_n_kw(3, 0, sp);
        SET_TOP(mp_const_none);
    } else if (mp_obj_is_small_int(TOP())) {
        // Getting here there are two distinct cases:
        //  - unwind return, stack: (..., __exit__, ctx_mgr, ret_val, SMALL_INT(-1))
        //  - unwind jump, stack:   (..., __exit__, ctx_mgr, dest_ip, SMALL_INT(num_exc))
        // For both cases we do exactly the same thing.
        mp_obj_t data = sp[-1];
        mp_obj_t cause = sp[0];
        sp[-1] = mp_const_none;
        sp[0] = mp_const_none;
        sp[1] = mp_const_none;
        mp_call_method_n_kw(3, 0, sp - 3);
        sp[-3] = data;
        sp[-2] = cause;
        sp -= 2; // we removed (__exit__, ctx_mgr)
    } else {
        assert(mp_obj_is_exception_instance(TOP()));
        // stack: (..., __exit__, ctx_mgr, exc_instance)
        // Need to pass (exc_type, exc_instance, None) as arguments to __exit__.
        sp[1] = sp[0];
        sp[0] = MP_OBJ_FROM_PTR(mp_obj_get_type(sp[0]));
        sp[2] = mp_const_none;
        sp -= 2;
        mp_obj_t ret_value = mp_call_method_n_kw(3, 0, sp);
        if (mp_obj_is_true(ret_value)) {
            // We need to silence/swallow the exception.  This is done
            // by popping the exception and the __exit__ handler and
            // replacing it with None, which signals END_FINALLY to just
            // execute the finally handler normally.
            SET_TOP(mp_const_none);
        } else {
            // We need to re-raise the exception.  We pop __exit__ handler
            // by copying the exception instance down to the new top-of-stack.
            sp[0] = sp[3];
        }
    }
    DISPATCH();
}

OP(UNWIND_JUMP) {
    SP_LOAD();
    DECODE_SLABEL;
    PUSH((mp_obj_t)(mp_uint_t)(uintptr_t)(ip + slab)); // push destination ip for jump
    PUSH((mp_obj_t)(mp_uint_t)(*ip)); // push number of exception handlers to unwind (0x80 bit set if we also need to pop stack)
    return vm_unwind_jump(vm, ip, sp);
}

// MP_BC_SETUP_EXCEPT and MP_BC_SETUP_FINALLY
OP(SETUP_EXCEPT_OR_FINALLY) {
    SP_LOAD();
    int is_finally = (ip[-1] == MP_BC_SETUP_FINALLY) ? 1 : 0;
    PUSH_EXC_BLOCK(is_finally);
    DISPATCH();
}

OP(END_FINALLY) {
    SP_LOAD();
    // if TOS is None, just pops it and continues
    // if TOS is an integer, finishes coroutine and returns control to caller
    // if TOS is an exception, reraises the exception
    assert(exc_sp >= exc_stack);
    POP_EXC_BLOCK();
    if (TOP() == mp_const_none) {
        sp--;
    } else if (mp_obj_is_small_int(TOP())) {
        // We finished "finally" coroutine and now dispatch back
        // to our caller, based on TOS value
        mp_int_t cause = MP_OBJ_SMALL_INT_VALUE(POP());
        if (cause < 0) {
            // A negative cause indicates unwind return
            return vm_unwind_return(vm, ip, sp);
        } else {
            // Otherwise it's an unwind jump and we must push as a raw
            // number the number of exception handlers to unwind
            PUSH((mp_obj_t)cause);
            return vm_unwind_jump(vm, ip, sp);
        }
    } else {
        assert(mp_obj_is_exception_instance(TOP()));
        RAISE(TOP());
    }
    DISPATCH();
}

OP(GET_ITER) {
    SP_LOAD();
    SET_TOP(mp_getiter(TOP(), NULL));
    return ip;
}

// An iterator for a for-loop takes MP_OBJ_ITER_BUF_NSLOTS slots on
// the Python value stack.  These slots are either used to store the
// iterator object itself, or the first slot is MP_OBJ_NULL and
// the second slot holds a reference to the iterator object.
OP(GET_ITER_STACK) {
    SP_LOAD();
    mp_obj_t obj = TOP();
    mp_obj_iter_buf_t *iter_buf = (mp_obj_iter_buf_t*)sp;
    sp += MP_OBJ_ITER_BUF_NSLOTS - 1;
    obj = mp_getiter(obj, iter_buf);
    if (obj != MP_OBJ_FROM_PTR(iter_buf)) {
        // Iterator didn't use the stack so indicate that with MP_OBJ_NULL.
        *(sp - MP_OBJ_ITER_BUF_NSLOTS + 1) = MP_OBJ_NULL;
        *(sp - MP_OBJ_ITER_BUF_NSLOTS + 2) = obj;
    }
    DISPATCH();
}

OP(FOR_ITER) {
    SP_LOAD();
    DECODE_ULABEL; // the jump offset if iteration finishes; for labels are always forward
    code_state->sp = sp;
    mp_obj_t obj;
    if (*(sp - MP_OBJ_ITER_BUF_NSLOTS + 1) == MP_OBJ_NULL) {
        obj = *(sp - MP_OBJ_ITER_BUF_NSLOTS + 2);
    } else {
        obj = MP_OBJ_FROM_PTR(&sp[-MP_OBJ_ITER_BUF_NSLOTS + 1]);
    }
    mp_obj_t value = mp_iternext_allow_raise(obj);
    if (value == MP_OBJ_STOP_ITERATION) {
        sp -= MP_OBJ_ITER_BUF_NSLOTS; // pop the exhausted iterator
        ip += ulab; // jump to after for-block
    } else {
        PUSH(value); // push the next iteration value
    }
    DISPATCH();
}

OP(POP_EXCEPT_JUMP) {
    assert(exc_sp >= exc_stack);
    POP_EXC_BLOCK();
    DECODE_ULABEL;
    ip += ulab;
    if (vm_pending_check(vm)) {
        return vm_switch(vm, ip);
    }
    return ip;
}

OP(BUILD_TUPLE) {
    SP_LOAD();
    DECODE_UINT;
    sp -= unum - 1;
    SET_TOP(mp_obj_new_tuple(unum, sp));
    DISPATCH();
}

OP(BUILD_LIST) {
    SP_LOAD();
    DECODE_UINT;
    sp -= unum - 1;
    SET_TOP(mp_obj_new_list(unum, sp));
    DISPATCH();
}

OP(BUILD_MAP) {
    SP_LOAD();
    DECODE_UINT;
    PUSH(mp_obj_new_dict(unum));
    DISPATCH();
}

OP(STORE_MAP) {
    SP_LOAD();
    sp -= 2;
    mp_obj_dict_store(sp[0], sp[2], sp[1]);
    DISPATCH();
}

#if MICROPY_PY_BUILTINS_SET
OP(BUILD_SET) {
    SP_LOAD();
    DECODE_UINT;
    sp -= unum - 1;
    SET_TOP(mp_obj_new_set(unum, sp));
    DISPATCH();
}
#endif

#if MICROPY_PY_BUILTINS_SLICE
OP(BUILD_SLICE) {
    SP_LOAD();
    mp_obj_t step = mp_const_none;
    if (*ip++ == 3) {
        // 3-argument slice includes step
        step = POP();
    }
    if ((*ip == MP_BC_LOAD_SUBSCR || *ip == MP_BC_STORE_SUBSCR)
        && (mp_obj_get_type(sp[-2])->flags & MP_TYPE_FLAG_SUBSCR_ALLOWS_STACK_SLICE)) {
        // Fast path optimisation for when the BUILD_SLICE is immediately followed
        // by a LOAD/STORE_SUBSCR for an accepting type, to avoid needing to allocate
        // the slice on the heap.  In some cases (e.g. a[1:3] = x) this can result
        // in no allocations at all.  We can't do this for instance types because
        // the get/set/delattr implementation may keep a reference to the slice.
        byte op = *ip++;
        sp = build_slice_stack_allocated(op, sp - 2, step);
    } else {
        mp_obj_t stop = POP();
        mp_obj_t start = TOP();
        SET_TOP(mp_obj_new_slice(start, stop, step));
    }
    DISPATCH();
}
#endif

OP(STORE_COMP) {
    SP_LOAD();
    DECODE_UINT;
    mp_obj_t obj = sp[-(unum >> 2)];
    if ((unum & 3) == 0) {
        mp_obj_list_append(obj, sp[0]);
        sp--;
    } else if (!MICROPY_PY_BUILTINS_SET || (unum & 3) == 1) {
        mp_obj_dict_store(obj, sp[0], sp[-1]);
        sp -= 2;
    #if MICROPY_PY_BUILTINS_SET
    } else {
        mp_obj_set_store(obj, sp[0]);
        sp--;
    #endif
    }
    DISPATCH();
}

OP(UNPACK_SEQUENCE) {
    SP_LOAD();
    DECODE_UINT;
    mp_unpack_sequence(sp[0], unum, sp);
    sp += unum - 1;
    DISPATCH();
}

OP(UNPACK_EX) {
    SP_LOAD();
    DECODE_UINT;
    mp_unpack_ex(sp[0], unum, sp);
    sp += (unum & 0xff) + ((unum >> 8) & 0xff);
    DISPATCH();
}

OP(MAKE_FUNCTION) {
    SP_LOAD();
    DECODE_PTR;
    PUSH(mp_make_function_from_proto_fun(ptr, code_state->fun_bc->context, NULL));
    DISPATCH();
}

OP(MAKE_FUNCTION_DEFARGS) {
    SP_LOAD();
    DECODE_PTR;
    // Stack layout: def_tuple def_dict <- TOS
    sp -= 1;
    SET_TOP(mp_make_function_from_proto_fun(ptr, code_state->fun_bc->context, sp));
    DISPATCH();
}

OP(MAKE_CLOSURE) {
    SP_LOAD();
    DECODE_PTR;
    size_t n_closed_over = *ip++;
    // Stack layout: closed_overs <- TOS
    sp -= n_closed_over - 1;
    SET_TOP(mp_make_closure_from_proto_fun(ptr, code_state->fun_bc->context, n_closed_over, sp));
    DISPATCH();
}

OP(MAKE_CLOSURE_DEFARGS) {
    SP_LOAD();
    DECODE_PTR;
    size_t n_closed_over = *ip++;
    // Stack layout: def_tuple def_dict closed_overs <- TOS
    sp -= 2 + n_closed_over - 1;
    SET_TOP(mp_make_closure_from_proto_fun(ptr, code_state->fun_bc->context, 0x100 | n_closed_over, sp));
    DISPATCH();
}

OP(CALL_FUNCTION) {
    SP_LOAD();
    DECODE_UINT;
    // unum & 0xff == n_positional
    // (unum >> 8) & 0xff == n_keyword
    sp -= (unum & 0xff) + ((unum >> 7) & 0x1fe);
    SET_TOP(mp_call_function_n_kw(*sp, unum & 0xff, (unum >> 8) & 0xff, sp + 1));
    DISPATCH();
}

OP(CALL_FUNCTION_VAR_KW) {
    SP_LOAD();
    DECODE_UINT;
    // unum & 0xff == n_positional
    // (unum >> 8) & 0xff == n_keyword
    // We have following stack layout here:
    // fun arg0 arg1 ... kw0 val0 kw1 val1 ... bitmap <- TOS
    sp -= (unum & 0xff) + ((unum >> 7) & 0x1fe) + 1;
    SET_TOP(mp_call_method_n_kw_var(false, unum, sp));
    DISPATCH();
}

OP(CALL_METHOD) {
    SP_LOAD();
    DECODE_UINT;
    // unum & 0xff == n_positional
    // (unum >> 8) & 0xff == n_keyword
    sp -= (unum & 0xff) + ((unum >> 7) & 0x1fe) + 1;
    SET_TOP(mp_call_method_n_kw(unum & 0xff, (unum >> 8) & 0xff, sp));
    DISPATCH();
}

OP(CALL_METHOD_VAR_KW) {
    SP_LOAD();
    DECODE_UINT;
    // unum & 0xff == n_positional
    // (unum >> 8) & 0xff == n_keyword
    // We have following stack layout here:
    // fun self arg0 arg1 ... kw0 val0 kw1 val1 ... bitmap <- TOS
    sp -= (unum & 0xff) + ((unum >> 7) & 0x1fe) + 2;
    SET_TOP(mp_call_method_n_kw_var(true, unum, sp));
    DISPATCH();
}

OP(RAISE_LAST) {
    // search for the inner-most previous exception, to reraise it
    mp_obj_t obj = MP_OBJ_NULL;
    for (mp_exc_stack_t *e = exc_sp; e >= exc_stack; --e) {
        if (e->prev_exc != NULL) {
            obj = MP_OBJ_FROM_PTR(e->prev_exc);
            break;
        }
    }
    if (obj == MP_OBJ_NULL) {
        obj = mp_obj_new_exception_msg(&mp_type_RuntimeError, MP_ERROR_TEXT("no active exception to reraise"));
    }
    RAISE(obj);
}

OP(RAISE_OBJ) {
    SP_LOAD();
    mp_obj_t obj = mp_make_raise_obj(TOP());
    RAISE(obj);
}

OP(RAISE_FROM) {
    SP_LOAD();
    mp_obj_t from_value = POP();
    if (from_value != mp_const_none) {
        mp_warning(NULL, "exception chaining not supported");
    }
    mp_obj_t obj = mp_make_raise_obj(TOP());
    RAISE(obj);
}

OP(YIELD_FROM) {
    SP_LOAD();
    mp_vm_return_kind_t ret_kind;
    mp_obj_t send_value = POP();
    mp_obj_t t_exc = MP_OBJ_NULL;
    mp_obj_t ret_value;
    code_state->sp = sp; // Save sp because it's needed if mp_resume raises StopIteration
    if (vm->inject_exc != MP_OBJ_NULL) {
        t_exc = vm->inject_exc;
        vm->inject_exc = MP_OBJ_NULL;
        ret_kind = mp_resume(TOP(), MP_OBJ_NULL, t_exc, &ret_value);
    } else {
        ret_kind = mp_resume(TOP(), send_value, MP_OBJ_NULL, &ret_value);
    }

    if (ret_kind == MP_VM_RETURN_YIELD) {
        ip--;
        PUSH(ret_value);
        return vm_yield(vm, ip, sp);
    } else if (ret_kind == MP_VM_RETURN_NORMAL) {
        // The generator has finished, and returned a value via StopIteration
        // Replace exhausted generator with the returned value
        SET_TOP(ret_value);
        // If we injected GeneratorExit downstream, then even
        // if it was swallowed, we re-raise GeneratorExit
        if (t_exc != MP_OBJ_NULL && mp_obj_exception_match(t_exc, MP_OBJ_FROM_PTR(&mp_type_GeneratorExit))) {
            mp_obj_t raise_t = mp_make_raise_obj(t_exc);
            RAISE(raise_t);
        }
        DISPATCH();
    } else {
        assert(ret_kind == MP_VM_RETURN_EXCEPTION);
        assert(!mp_obj_exception_match(ret_value, MP_OBJ_FROM_PTR(&mp_type_StopIteration)));
        // Pop exhausted gen
        sp--;
        RAISE(ret_value);
    }
}

OP(IMPORT_NAME) {
    SP_LOAD();
    DECODE_QSTR;
    mp_obj_t obj = POP();
    SET_TOP(mp_import_name(qst, obj, TOP()));
    DISPATCH();
}

OP(IMPORT_FROM) {
    SP_LOAD();
    DECODE_QSTR;
    mp_obj_t obj = mp_import_from(TOP(), qst);
    PUSH(obj);
    DISPATCH();
}

OP(IMPORT_STAR) {
    SP_LOAD();
    mp_import_all(POP());
    DISPATCH();
}

OP(UNARY_OP_MULTI) {
    SP_LOAD();
    SET_TOP(mp_unary_op(ip[-1] - MP_BC_UNARY_OP_MULTI, TOP()));
    return ip;
}

OP(BINARY_OP_MULTI) {
    SP_LOAD();
    mp_obj_t rhs = POP();
    mp_obj_t lhs = TOP();
    SET_TOP(mp_binary_op(ip[-1] - MP_BC_BINARY_OP_MULTI, lhs, rhs));
    DISPATCH();
}

OP(UNKNOWN) {
    mp_obj_t obj = mp_obj_new_exception_msg(&mp_type_NotImplementedError, MP_ERROR_TEXT("opcode"));
    code_state->state[0] = obj;
    STOP(VM_RETURN_EXCEPTION);
}

// ---- dispatch loop ---------------------------------------------------------

#define CALL(name) ip = op_##name(vm, ip); break
#define MULTI(base) base ... (base + base##_NUM - 1)

// Runs opcodes until the function returns, yields or raises directly.
// An exception raised by a callee leaves through nlr (longjmp) instead.
// ---- where the program is, and stepping (for the editor) -------------------
//
// The plain copy of the dispatch loop does nothing extra, so a program nobody watches pays
// nothing. While someone has its editor open (watching) or steps through it, the host
// switches the program to the traced copy (vm_loop.inc), which calls vm_trace before each
// opcode. vm_trace notes the last line of the player's own code that ran: while their code
// is inside a library (qbot.py), that is the line that called it, which is what the editor
// highlights. Stepping stops at each new line of their code.

int32_t mpy_trace = 0;     // run the traced loop (watching or stepping); set from the budget
int32_t mpy_stepping = 0;  // stop at each new line of the player's code; set from the budget
int32_t mpy_where_line = 0;   // the last line of the player's own code that ran (0: none yet)
qstr mpy_where_file = 0;      // its file

// The player's own files are on the drive, so their names start with "/"; the libraries
// built into the engine (qbot.py, logistics.py) and typed lines (<stdin>) don't.
static bool own_file(qstr file) {
    size_t len;
    const byte *name = qstr_data(file, &len);
    return len > 0 && name[0] == '/';
}

// The host is told of each new line of the player's code while stepping; it returns
// whether to go on stepping (it may hold the program there first).
__attribute__((import_module("host"), import_name("line"))) int32_t host_line(int32_t line);

// What vm_trace worked out last: a function, the stretch of its bytecode on one line, that
// line and file. Most opcodes fall in the same stretch as the one before, so most calls of
// vm_trace are three comparisons.
static mp_code_state_t *line_cs;
static const byte *line_lo;
static const byte *line_hi;
static size_t line_no;
static bool line_own;

// The line of an opcode, and the stretch of bytecode [lo, hi) that is on the same line.
static void find_line(mp_code_state_t *cs, const byte *at) {
    const byte *ip = cs->fun_bc->bytecode;
    MP_BC_PRELUDE_SIG_DECODE(ip);
    MP_BC_PRELUDE_SIZE_DECODE(ip);
    (void)n_state; (void)n_exc_stack; (void)scope_flags; (void)n_def_pos_args;
    const byte *line_info_top = ip + n_info;
    const byte *bytecode_start = ip + n_info + n_cell;
    size_t bc = at - bytecode_start;
    ip = mp_decode_uint_skip(ip); // the block's name
    for (size_t i = 0; i < n_pos_args + n_kwonly_args; ++i) {
        ip = mp_decode_uint_skip(ip);
    }
    #if MICROPY_EMIT_BYTECODE_USES_QSTR_TABLE
    qstr file = cs->fun_bc->context->constants.qstr_table[0];
    #else
    qstr file = cs->fun_bc->context->constants.source_file;
    #endif
    // As mp_bytecode_get_source_line, noting where the line's stretch begins and ends.
    size_t line = 1;
    size_t pos = 0;
    size_t until = (size_t)-1;
    const byte *info = ip;
    while (info < line_info_top) {
        mp_code_lineinfo_t decoded = mp_bytecode_decode_lineinfo(&info);
        if (bc >= decoded.bc_increment) {
            bc -= decoded.bc_increment;
            pos += decoded.bc_increment;
            line += decoded.line_increment;
        } else {
            until = pos + decoded.bc_increment;
            break;
        }
    }
    line_cs = cs;
    line_lo = bytecode_start + pos;
    line_hi = until == (size_t)-1 ? (const byte *)-1 : bytecode_start + until;
    line_no = line;
    line_own = own_file(file);
    if (line_own) {
        mpy_where_file = file;
    }
}

// Stepping: where the last stop was, so a new line (or the same line again, after a jump
// back for the next turn of a loop) is told apart from the next opcode of the same line.
static mp_code_state_t *step_cs;
static size_t step_line;
static const byte *step_at;

// Runs before each opcode in the traced copy of the loop.
static MP_NOINLINE void vm_trace(mp_code_state_t *cs, const byte *at) {
    if (cs != line_cs || at < line_lo || at >= line_hi) {
        find_line(cs, at);
    }
    if (!line_own) {
        return; // inside a library: the player's last line stays the one that called it
    }
    mpy_where_line = (int32_t)line_no;
    if (!mpy_stepping) {
        return;
    }
    bool fresh = cs != step_cs || line_no != step_line || at < step_at;
    step_cs = cs;
    step_line = line_no;
    step_at = at;
    if (fresh) {
        mpy_stepping = host_line((int32_t)line_no);
    }
}

// Where the program is, for the host: the last line of the player's own code, and its file.
int32_t mpy_where_now(qstr *file) {
    *file = mpy_where_file;
    return mpy_where_file == 0 ? 0 : mpy_where_line;
}

static int vm_enter_traced(vm_t *vm);

#define VM_TRACED 1
#define VM_RUN_NAME vm_run_traced
#include "vm_loop.inc"
#undef VM_TRACED
#undef VM_RUN_NAME

// Runs the traced loop until the function ends (its status) or tracing stops (VM_BACK).
static MP_NOINLINE int vm_enter_traced(vm_t *vm) {
    for (;;) {
        vm->traced = mpy_trace;
        int s = vm_run_traced(vm);
        if (s != VM_SWITCH) {
            return s;
        }
        if (!mpy_trace) {
            vm->traced = 0;
            return VM_BACK;
        }
    }
}

#define VM_TRACED 0
#define VM_RUN_NAME vm_run
#include "vm_loop.inc"
#undef VM_TRACED
#undef VM_RUN_NAME

// ---- exception handling ----------------------------------------------------

// An exception reached this function, either raised directly by an opcode or
// by a callee through nlr. Returns true if a handler in this function takes
// it (execution continues at code_state->ip), false to pass it to the caller
// (the exception is then in code_state->state[0]).
static MP_NOINLINE bool vm_handle_exception(vm_t *vm, mp_obj_base_t *exc) {
    #if MICROPY_PY_SYS_EXC_INFO
    MP_STATE_VM(cur_exception) = exc;
    #endif

    if (mp_obj_is_subclass_fast(MP_OBJ_FROM_PTR(exc->type), MP_OBJ_FROM_PTR(&mp_type_StopIteration))) {
        // check if it's a StopIteration within a for block
        if (*code_state->ip == MP_BC_FOR_ITER) {
            const byte *ip = code_state->ip + 1;
            DECODE_ULABEL; // the jump offset if iteration finishes; for labels are always forward
            code_state->ip = ip + ulab; // jump to after for-block
            code_state->sp -= MP_OBJ_ITER_BUF_NSLOTS; // pop the exhausted iterator
            return true; // continue with dispatch loop
        } else if (*code_state->ip == MP_BC_YIELD_FROM) {
            // StopIteration inside yield from call means return a value of
            // yield from, so inject exception's value as yield from's result
            // (Instead of stack pop then push we just replace exhausted gen with value)
            *code_state->sp = mp_obj_exception_get_value(MP_OBJ_FROM_PTR(exc));
            code_state->ip++; // yield from is over, move to next instruction
            return true; // continue with dispatch loop
        }
    }

    // Set traceback info (file and line number) where the exception occurred, but not for:
    // - constant GeneratorExit object, because it's const
    // - exceptions re-raised by END_FINALLY
    // - exceptions re-raised explicitly by "raise"
    if ((void *)exc != (void *)&mp_const_GeneratorExit_obj
        && *code_state->ip != MP_BC_END_FINALLY
        && *code_state->ip != MP_BC_RAISE_LAST) {
        const byte *ip = code_state->fun_bc->bytecode;
        MP_BC_PRELUDE_SIG_DECODE(ip);
        MP_BC_PRELUDE_SIZE_DECODE(ip);
        const byte *line_info_top = ip + n_info;
        const byte *bytecode_start = ip + n_info + n_cell;
        size_t bc = code_state->ip - bytecode_start;
        qstr block_name = mp_decode_uint_value(ip);
        for (size_t i = 0; i < 1 + n_pos_args + n_kwonly_args; ++i) {
            ip = mp_decode_uint_skip(ip);
        }
        #if MICROPY_EMIT_BYTECODE_USES_QSTR_TABLE
        block_name = code_state->fun_bc->context->constants.qstr_table[block_name];
        qstr source_file = code_state->fun_bc->context->constants.qstr_table[0];
        #else
        qstr source_file = code_state->fun_bc->context->constants.source_file;
        #endif
        size_t source_line = mp_bytecode_get_source_line(ip, line_info_top, bc);
        mp_obj_exception_add_traceback(MP_OBJ_FROM_PTR(exc), source_file, source_line, block_name);
    }

    while (exc_sp >= exc_stack && exc_sp->handler <= code_state->ip) {
        // nested exception: move up to previous exception handler
        POP_EXC_BLOCK();
    }

    if (exc_sp >= exc_stack) {
        // catch exception and pass to byte code
        code_state->ip = exc_sp->handler;
        mp_obj_t *sp = MP_TAGPTR_PTR(exc_sp->val_sp);
        // save this exception in the stack so it can be used in a reraise, if needed
        exc_sp->prev_exc = exc;
        // push exception object so it can be handled by bytecode
        PUSH(MP_OBJ_FROM_PTR(exc));
        code_state->sp = sp;
        return true;
    }

    // propagate exception to higher level
    // Note: ip and sp don't have usable values at this point
    code_state->state[0] = MP_OBJ_FROM_PTR(exc); // put exception here because sp is invalid
    return false;
}

#undef code_state
#undef fastn
#undef exc_stack
#undef exc_sp

// fastn has items in reverse order (fastn[0] is local[0], fastn[-1] is local[1], etc)
// sp points to bottom of stack which grows up
// returns:
//  MP_VM_RETURN_NORMAL, sp valid, return value in *sp
//  MP_VM_RETURN_YIELD, ip, sp valid, yielded value in *sp
//  MP_VM_RETURN_EXCEPTION, exception in state[0]
mp_vm_return_kind_t MICROPY_WRAP_MP_EXECUTE_BYTECODE(mp_execute_bytecode)(mp_code_state_t *code_state, volatile mp_obj_t inject_exc) {
    // All state lives in this struct, in memory, so it stays valid across a longjmp.
    vm_t vm;
    {
        size_t n_state = code_state->n_state;
        vm.code_state = code_state;
        vm.fastn = &code_state->state[n_state - 1];
        vm.exc_stack = (mp_exc_stack_t *)(code_state->state + n_state);
        vm.exc_sp = MP_CODE_STATE_EXC_SP_IDX_TO_PTR(vm.exc_stack, code_state->exc_sp_idx);
        #if MICROPY_EMIT_BYTECODE_USES_QSTR_TABLE
        vm.qstr_table = code_state->fun_bc->context->constants.qstr_table;
        #endif
        vm.inject_exc = inject_exc;
        vm.raise_obj = MP_OBJ_NULL;
        vm.status = 0;
        vm.traced = 0;
    }

    // outer exception handling loop
    for (;;) {
        nlr_buf_t nlr;
        mp_obj_base_t *exc;
        if (nlr_push(&nlr) == 0) {
            int status = vm_run(&vm);
            nlr_pop();
            switch (status) {
                case VM_RETURN:
                    return MP_VM_RETURN_NORMAL;
                case VM_YIELD:
                    return MP_VM_RETURN_YIELD;
                case VM_RETURN_EXCEPTION:
                    return MP_VM_RETURN_EXCEPTION;
                default: // VM_RAISE
                    exc = MP_OBJ_TO_PTR(vm.raise_obj);
                    break;
            }
        } else {
            exc = nlr.ret_val;
        }
        if (!vm_handle_exception(&vm, exc)) {
            return MP_VM_RETURN_EXCEPTION;
        }
    }
}
