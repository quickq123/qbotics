// MicroPython configuration for the mod's Wasm engine (design doc §3).
#include <stdint.h>
#include <limits.h>
#include <alloca.h>

#define MICROPY_CONFIG_ROM_LEVEL          (MICROPY_CONFIG_ROM_LEVEL_EXTRA_FEATURES)

#define MICROPY_ENABLE_COMPILER           (1)
#define MICROPY_ENABLE_GC                 (1)
#define MICROPY_STACK_CHECK               (1)
#define MICROPY_LONGINT_IMPL              (MICROPY_LONGINT_IMPL_MPZ)
#define MICROPY_FLOAT_IMPL                (MICROPY_FLOAT_IMPL_DOUBLE)
#define MICROPY_ERROR_REPORTING           (MICROPY_ERROR_REPORTING_DETAILED)
#define MICROPY_WARNINGS                  (1)
#define MICROPY_ALLOC_PATH_MAX            (256)

// Non-local returns (exceptions) use setjmp/longjmp, which wasi-sdk lowers to
// Wasm exception handling. Registers don't exist in Wasm; roots are found on
// the shadow stack (see gc_collect in main.c).
#define MICROPY_NLR_SETJMP                (1)
#define MICROPY_GCREGS_SETJMP             (0)

// No host filesystem or stdio: everything goes through host imports.
#define MICROPY_HELPER_REPL               (1)
#define MICROPY_VFS                       (0)
#define MICROPY_READER_POSIX              (0)
#define MICROPY_READER_VFS                (0)
#define MICROPY_ENABLE_EXTERNAL_IMPORT    (0)
#define MICROPY_PY_SYS_STDFILES           (0)
#define MICROPY_PY_BUILTINS_INPUT         (0)
#define MICROPY_PY_IO                     (1)
#define MICROPY_PY_OS                     (0)
#define MICROPY_PY_SELECT                 (0)
#define MICROPY_PY_MACHINE                (0)
#define MICROPY_PY_ASYNCIO                (0)
#define MICROPY_PY_SYS_PLATFORM           "qbot"

#define MICROPY_PY_TIME                   (1)
#define MICROPY_PY_TIME_GMTIME_LOCALTIME_MKTIME (0)
#define MICROPY_PY_TIME_TIME_TIME_NS      (0)

// Metering (design doc §3.3): the VM calls this on every backward jump, so a
// loop can't run forever without the host getting a say. The host hands out a
// budget; when it runs out we ask for more (the host may pause us there) or
// get told to stop.
extern int32_t mpy_budget;
void mpy_budget_expired(void);
#define MICROPY_VM_HOOK_LOOP \
    if (--mpy_budget <= 0) { \
        mpy_budget_expired(); \
    }

typedef long mp_off_t;

#define MICROPY_HW_BOARD_NAME "Computer"
#define MICROPY_HW_MCU_NAME "wasm32"

#define MP_STATE_PORT MP_STATE_VM
