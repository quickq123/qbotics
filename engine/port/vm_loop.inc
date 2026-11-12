// The dispatch loop of vm_split.c. It is compiled twice: plain (VM_TRACED 0), which is
// what runs nearly always and does nothing extra, and traced (VM_TRACED 1), which runs only
// while someone watches or steps through the program and calls vm_trace before each opcode.
// The plain loop hands over to the traced one itself, on entry and on its rare switch exit,
// so mp_execute_bytecode (run on every call) is exactly as it was. Included by vm_split.c.

// *FORMAT-OFF*

static MP_NOINLINE int VM_RUN_NAME(vm_t *vm) {
    mp_code_state_t *const cs = code_state;
    const byte *ip = cs->ip;
    vm->sp = cs->sp;
    #if !VM_TRACED
    // Watched or stepping: a function starts in the traced loop (so stepping goes into it).
    if (MP_UNLIKELY(mpy_trace)) {
        int s = vm_enter_traced(vm);
        if (s != VM_BACK) {
            return s;
        }
        ip = cs->ip;
        vm->sp = cs->sp;
    }
    #endif

    // If we have exception to inject, now that we finish setting up
    // execution context, raise it. This works as if MP_BC_RAISE_OBJ
    // bytecode was executed.
    // Injecting exc into yield from generator is a special case,
    // handled by MP_BC_YIELD_FROM itself
    if (vm->inject_exc != MP_OBJ_NULL && *ip != MP_BC_YIELD_FROM) {
        mp_obj_t exc = vm->inject_exc;
        vm->inject_exc = MP_OBJ_NULL;
        vm->raise_obj = mp_make_raise_obj(exc);
        return VM_RAISE;
    }

    for (;;) {
        // The exception handler needs the ip of the opcode being executed.
        cs->ip = ip;
        #if VM_TRACED
        vm_trace(cs, ip);
        #endif
        switch (*ip++) {
            // The simplest and most frequent opcodes are done right here.
            case MP_BC_LOAD_CONST_FALSE: *++vm->sp = mp_const_false; continue;
            case MP_BC_LOAD_CONST_NONE: *++vm->sp = mp_const_none; continue;
            case MP_BC_LOAD_CONST_TRUE: *++vm->sp = mp_const_true; continue;
            case MP_BC_LOAD_NULL: *++vm->sp = MP_OBJ_NULL; continue;
            case MP_BC_DUP_TOP: { mp_obj_t *sp = vm->sp; sp[1] = sp[0]; vm->sp = sp + 1; continue; }
            case MP_BC_POP_TOP: vm->sp -= 1; continue;
            case MULTI(MP_BC_LOAD_CONST_SMALL_INT_MULTI):
                *++vm->sp = MP_OBJ_NEW_SMALL_INT((mp_int_t)ip[-1] - MP_BC_LOAD_CONST_SMALL_INT_MULTI - MP_BC_LOAD_CONST_SMALL_INT_MULTI_EXCESS);
                continue;
            case MULTI(MP_BC_LOAD_FAST_MULTI): {
                mp_obj_t obj = fastn[MP_BC_LOAD_FAST_MULTI - (mp_int_t)ip[-1]];
                if (obj == MP_OBJ_NULL) {
                    ip = vm_local_name_error(vm);
                    break;
                }
                *++vm->sp = obj;
                continue;
            }
            case MULTI(MP_BC_STORE_FAST_MULTI):
                fastn[MP_BC_STORE_FAST_MULTI - (mp_int_t)ip[-1]] = *vm->sp--;
                continue;

            case MP_BC_LOAD_CONST_SMALL_INT: CALL(LOAD_CONST_SMALL_INT);
            case MP_BC_LOAD_CONST_STRING: CALL(LOAD_CONST_STRING);
            case MP_BC_LOAD_CONST_OBJ: CALL(LOAD_CONST_OBJ);
            case MP_BC_LOAD_FAST_N: CALL(LOAD_FAST_N);
            case MP_BC_LOAD_DEREF: CALL(LOAD_DEREF);
            case MP_BC_LOAD_NAME: CALL(LOAD_NAME);
            case MP_BC_LOAD_GLOBAL: CALL(LOAD_GLOBAL);
            case MP_BC_LOAD_ATTR: CALL(LOAD_ATTR);
            case MP_BC_LOAD_METHOD: CALL(LOAD_METHOD);
            case MP_BC_LOAD_SUPER_METHOD: CALL(LOAD_SUPER_METHOD);
            case MP_BC_LOAD_BUILD_CLASS: CALL(LOAD_BUILD_CLASS);
            case MP_BC_LOAD_SUBSCR: CALL(LOAD_SUBSCR);
            case MP_BC_STORE_FAST_N: CALL(STORE_FAST_N);
            case MP_BC_STORE_DEREF: CALL(STORE_DEREF);
            case MP_BC_STORE_NAME: CALL(STORE_NAME);
            case MP_BC_STORE_GLOBAL: CALL(STORE_GLOBAL);
            case MP_BC_STORE_ATTR: CALL(STORE_ATTR);
            case MP_BC_STORE_SUBSCR: CALL(STORE_SUBSCR);
            case MP_BC_DELETE_FAST: CALL(DELETE_FAST);
            case MP_BC_DELETE_DEREF: CALL(DELETE_DEREF);
            case MP_BC_DELETE_NAME: CALL(DELETE_NAME);
            case MP_BC_DELETE_GLOBAL: CALL(DELETE_GLOBAL);
            case MP_BC_DUP_TOP_TWO: CALL(DUP_TOP_TWO);
            case MP_BC_ROT_TWO: CALL(ROT_TWO);
            case MP_BC_ROT_THREE: CALL(ROT_THREE);
            case MP_BC_JUMP: CALL(JUMP);
            case MP_BC_POP_JUMP_IF_TRUE: CALL(POP_JUMP_IF_TRUE);
            case MP_BC_POP_JUMP_IF_FALSE: CALL(POP_JUMP_IF_FALSE);
            case MP_BC_JUMP_IF_TRUE_OR_POP: CALL(JUMP_IF_TRUE_OR_POP);
            case MP_BC_JUMP_IF_FALSE_OR_POP: CALL(JUMP_IF_FALSE_OR_POP);
            case MP_BC_SETUP_WITH: CALL(SETUP_WITH);
            case MP_BC_WITH_CLEANUP: CALL(WITH_CLEANUP);
            case MP_BC_UNWIND_JUMP: CALL(UNWIND_JUMP);
            case MP_BC_SETUP_EXCEPT:
            case MP_BC_SETUP_FINALLY: CALL(SETUP_EXCEPT_OR_FINALLY);
            case MP_BC_END_FINALLY: CALL(END_FINALLY);
            case MP_BC_GET_ITER: CALL(GET_ITER);
            case MP_BC_GET_ITER_STACK: CALL(GET_ITER_STACK);
            case MP_BC_FOR_ITER: CALL(FOR_ITER);
            case MP_BC_POP_EXCEPT_JUMP: CALL(POP_EXCEPT_JUMP);
            case MP_BC_BUILD_TUPLE: CALL(BUILD_TUPLE);
            case MP_BC_BUILD_LIST: CALL(BUILD_LIST);
            case MP_BC_BUILD_MAP: CALL(BUILD_MAP);
            case MP_BC_STORE_MAP: CALL(STORE_MAP);
            #if MICROPY_PY_BUILTINS_SET
            case MP_BC_BUILD_SET: CALL(BUILD_SET);
            #endif
            #if MICROPY_PY_BUILTINS_SLICE
            case MP_BC_BUILD_SLICE: CALL(BUILD_SLICE);
            #endif
            case MP_BC_STORE_COMP: CALL(STORE_COMP);
            case MP_BC_UNPACK_SEQUENCE: CALL(UNPACK_SEQUENCE);
            case MP_BC_UNPACK_EX: CALL(UNPACK_EX);
            case MP_BC_MAKE_FUNCTION: CALL(MAKE_FUNCTION);
            case MP_BC_MAKE_FUNCTION_DEFARGS: CALL(MAKE_FUNCTION_DEFARGS);
            case MP_BC_MAKE_CLOSURE: CALL(MAKE_CLOSURE);
            case MP_BC_MAKE_CLOSURE_DEFARGS: CALL(MAKE_CLOSURE_DEFARGS);
            case MP_BC_CALL_FUNCTION: CALL(CALL_FUNCTION);
            case MP_BC_CALL_FUNCTION_VAR_KW: CALL(CALL_FUNCTION_VAR_KW);
            case MP_BC_CALL_METHOD: CALL(CALL_METHOD);
            case MP_BC_CALL_METHOD_VAR_KW: CALL(CALL_METHOD_VAR_KW);
            case MP_BC_RETURN_VALUE: ip = vm_unwind_return(vm, ip, vm->sp); break;
            case MP_BC_RAISE_LAST: CALL(RAISE_LAST);
            case MP_BC_RAISE_OBJ: CALL(RAISE_OBJ);
            case MP_BC_RAISE_FROM: CALL(RAISE_FROM);
            case MP_BC_YIELD_VALUE: ip = vm_yield(vm, ip, vm->sp); break;
            case MP_BC_YIELD_FROM: CALL(YIELD_FROM);
            case MP_BC_IMPORT_NAME: CALL(IMPORT_NAME);
            case MP_BC_IMPORT_FROM: CALL(IMPORT_FROM);
            case MP_BC_IMPORT_STAR: CALL(IMPORT_STAR);
            case MULTI(MP_BC_UNARY_OP_MULTI): CALL(UNARY_OP_MULTI);
            case MULTI(MP_BC_BINARY_OP_MULTI): CALL(BINARY_OP_MULTI);
            default: CALL(UNKNOWN);
        }
        if (MP_UNLIKELY(ip == NULL)) {
            #if !VM_TRACED
            if (vm->status == VM_SWITCH) {
                // Tracing began: the traced loop runs this function on, until it ends or tracing stops.
                int s = vm_enter_traced(vm);
                if (s != VM_BACK) {
                    return s;
                }
                ip = cs->ip; // tracing stopped: carry on here, in the plain loop
                vm->sp = cs->sp;
                continue;
            }
            #endif
            return vm->status;
        }
    }
}
