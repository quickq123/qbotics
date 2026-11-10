# MicroPython -> WebAssembly build for the mod's engine (design doc §3).
# Runs inside the toolchain container (see ../Dockerfile, ../build.sh).

MPY_DIR ?= /opt/micropython
WASI_SDK ?= /opt/wasi-sdk
WASM_OPT ?= wasm-opt
BUILD ?= /work/build
OUT ?= /work/out

include $(MPY_DIR)/py/mkenv.mk

CROSS = 0
QSTR_DEFS = qstrdefsport.h

include $(TOP)/py/py.mk
include $(TOP)/extmod/extmod.mk

CC = $(WASI_SDK)/bin/clang
LD = $(WASI_SDK)/bin/clang
SIZE = $(WASI_SDK)/bin/llvm-size
STRIP = $(WASI_SDK)/bin/llvm-strip

# C shadow-stack size inside Wasm linear memory. The GC scans it for roots.
STACK_SIZE ?= 262144

TARGET_FLAGS = --target=wasm32-wasip1 -mllvm -wasm-enable-sjlj

INC += -I. -I$(TOP) -I$(BUILD)
CFLAGS += $(INC) $(TARGET_FLAGS) -Wall -std=gnu99 -O2 -DNDEBUG \
	-DMPY_STACK_SIZE=$(STACK_SIZE) \
	-fdata-sections -ffunction-sections
LDFLAGS += $(TARGET_FLAGS) -mexec-model=reactor -lsetjmp \
	-Wl,--gc-sections -Wl,-z,stack-size=$(STACK_SIZE) \
	-Wl,--export=__stack_pointer

SRC_C = \
	main.c \
	modhost.c \
	mphalport.c \

# SPLIT_VM=1 (default) replaces MicroPython's py/vm.c with vm_split.c, which
# the JVM can JIT-compile (see the comment at the top of that file).
# SPLIT_VM=0 builds the unmodified interpreter as micropython-ref.wasm, the
# reference that the bench harness's "difftest" compares against.
SPLIT_VM ?= 1
ifeq ($(SPLIT_VM),1)
WASM_NAME = micropython
SRC_C += vm_split.c
VM_O = $(filter-out $(PY_BUILD)/vm.o, $(PY_O))
else
WASM_NAME = micropython-ref
VM_O = $(PY_O)
endif

SRC_QSTR += main.c modhost.c mphalport.c vm_split.c

OBJ += $(VM_O)
OBJ += $(addprefix $(BUILD)/, $(SRC_C:.c=.o))

all: $(OUT)/$(WASM_NAME).wasm

$(BUILD)/$(WASM_NAME).raw.wasm: $(OBJ)
	$(ECHO) "LINK $@"
	$(Q)$(LD) $(LDFLAGS) -o $@ $^ $(LIBS)

# --spill-pointers: Wasm locals are invisible to a conservative GC, so move
#   anything that might be a pointer onto the C shadow stack where gc_collect
#   can scan it.
# --translate-to-exnref: LLVM emits the legacy exception-handling encoding for
#   setjmp/longjmp; convert it to the standard (exnref) one.
$(OUT)/$(WASM_NAME).wasm: $(BUILD)/$(WASM_NAME).raw.wasm
	$(ECHO) "WASM-OPT $@"
	$(Q)mkdir -p $(OUT)
	$(Q)$(WASM_OPT) --enable-exception-handling --enable-bulk-memory \
		--enable-sign-ext --enable-mutable-globals --enable-nontrapping-float-to-int \
		--enable-reference-types --enable-multivalue \
		--spill-pointers --translate-to-exnref -o $@ $<
	$(Q)ls -l $@

include $(TOP)/py/mkrules.mk
