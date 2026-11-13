#!/usr/bin/env python3
"""Splits QuickJS's interpreter loop into one small function per opcode (design doc §10.3).

Why: JS_CallInternal is one very large C function (about 47 KB of Wasm). Chicory turns it into
one JVM method far over the 8,000 bytes HotSpot will compile, so every JavaScript opcode, and
every call (a call enters JS_CallInternal again), ran in the JVM's own slow interpreter. The
Python engine had the same problem and the same fix (engine/port/vm_split.c).

How: the body of each `CASE(OP_x):` (and of each shared label between cases) is moved,
unchanged, into its own function. A slim loop dispatches to them with a switch. The locals the
loop shared live in a struct on the C stack; each handler copies them in, and copies the ones
handlers change (pc, sp, ret_val, call_argc, call_argv) back when it leaves. Inside a handler,
`BREAK` returns to the loop, `goto exception` / `goto done` / `goto done_generator` return a
code the loop acts on, and a jump to another case's shared label (or falling into the next
case) calls that piece's function.

Usage: split_interp.py quickjs.c quickjs_split.c. It checks its assumptions about the source
(written for quickjs-ng v0.10.1) and stops if one fails, rather than build a wrong engine.
"""
import re
import sys

src_path, out_path = sys.argv[1], sys.argv[2]
text = open(src_path, encoding="utf-8").read()


def fail(message):
    sys.exit("split_interp: " + message)


# ---- find the function and its loop ---------------------------------------------------------
head = "static JSValue JS_CallInternal(JSContext *caller_ctx, JSValueConst func_obj,"
start = text.find(head)
if start < 0 or text.count(head) != 1:
    fail("JS_CallInternal's definition not found exactly once")
end = text.index("\n}\n", start) + 3
func = text[start:end]

loop_head = " restart:\n    for(;;) {\n        int call_argc;\n        JSValue *call_argv;\n\n        SWITCH(pc) {\n"
loop_tail = "        }\n    }\n exception:\n"
if func.count(loop_head) != 1 or func.count(loop_tail) != 1:
    fail("the interpreter loop's start or end has changed")
body_start = func.index(loop_head) + len(loop_head)
body_end = func.index(loop_tail)
body = func[body_start:body_end]

# ---- one nested shared label: lift it to the top level of the switch ------------------------
call_old = """        CASE(OP_call):
        CASE(OP_tail_call):
            {
                call_argc = get_u16(pc);
                pc += 2;
                goto has_call_argc;
            has_call_argc:
"""
call_new = """        CASE(OP_call):
        CASE(OP_tail_call):
                call_argc = get_u16(pc);
                pc += 2;
        has_call_argc:
            {
"""
if body.count(call_old) != 1:
    fail("OP_call's shape has changed")
body = body.replace(call_old, call_new)

# A second: OP_init_ctor jumps into OP_check_ctor's error. It throws the same error itself.
ctor_old = """                if (JS_IsUndefined(new_target))
                    goto non_ctor_call;
"""
ctor_new = """                if (JS_IsUndefined(new_target)) {
                    JS_ThrowTypeError(ctx, "class constructors must be invoked with 'new'");
                    goto exception;
                }
"""
if body.count(ctor_old) != 1:
    fail("OP_init_ctor's shape has changed")
body = body.replace(ctor_old, ctor_new)

# ---- cut the switch into pieces -------------------------------------------------------------
# A piece starts at a top-level label (8 spaces in): CASE(OP_x), DEFAULT, or a shared label;
# or at an OP_CMP(OP_x, ...) line, a macro that expands to a whole case. Some cases are one
# line, their code after the label: CASE(OP_get_loc0): *sp++ = ...; BREAK;
starts = re.compile(r"^ {8}(?:CASE\((OP_\w+)\)|(DEFAULT)|([a-z_]\w*)):(.*)$|^ {12}OP_CMP\((OP_\w+),")
nothing = re.compile(r"^\s*(?:/\*.*\*/\s*|//.*)?$")


def label_only(line):
    """True for a blank line, or a piece's label with no code after it."""
    m = starts.match(line)
    return not line.strip() or (m is not None and m.group(5) is None and nothing.match(m.group(4)) is not None)


lines = body.split("\n")
pieces = []   # dicts: name, opcodes (list), labels (shared label names), lines
for line in lines:
    m = starts.match(line)
    if m:
        opcode = m.group(1) or m.group(5)
        label = m.group(3)
        default = m.group(2)
        current = pieces[-1] if pieces else None
        # Labels with nothing between them (cases sharing one body) make one piece.
        empty = current is not None and all(label_only(l) for l in current["lines"])
        if not empty or m.group(5):
            current = {"opcodes": [], "labels": [], "default": False, "lines": []}
            pieces.append(current)
        if opcode:
            current["opcodes"].append(opcode)
        if label:
            current["labels"].append(label)
        if default:
            current["default"] = True
    elif not pieces:
        if line.strip():
            fail("code before the first case: " + line)
        continue
    pieces[-1]["lines"].append(line)

for i, piece in enumerate(pieces):
    piece["name"] = "js_op_%d_%s" % (i, (piece["opcodes"] or piece["labels"] or ["default"])[0].lower())

shared = {label: piece for piece in pieces for label in piece["labels"]}
if "has_call_argc" not in shared or "binary_arith_slow" not in shared:
    fail("expected shared labels are missing")

# Every goto must stay inside its piece, or go to a shared label, or leave the loop.
leaving = {"exception": "JS_VM_EXCEPTION", "done": "JS_VM_DONE", "done_generator": "JS_VM_DONE_GENERATOR"}
def bare(code):
    """The code without comments and string literals, for checking."""
    code = re.sub(r"/\*.*?\*/", " ", code, flags=re.S)
    code = re.sub(r"//[^\n]*", " ", code)
    return re.sub(r'"(?:\\.|[^"\\])*"', '""', code)


for piece in pieces:
    code = bare("\n".join(piece["lines"]))
    own = set(re.findall(r"^\s*([a-z_]\w*):\s*$", code, re.M))
    for target in re.findall(r"\bgoto\s+(\w+)\s*;", code):
        if target not in own and target not in shared and target not in leaving:
            fail("%s jumps to %s in another piece" % (piece["name"], target))
    if re.search(r"\breturn\b", code):
        fail("%s returns directly" % piece["name"])
    if "alloca" in code:
        fail("%s uses alloca" % piece["name"])

# ---- the shared state -----------------------------------------------------------------------
STATE = [  # (type, name): every local of JS_CallInternal the loop's body may use
    ("JSContext *", "caller_ctx"), ("JSValueConst", "func_obj"), ("JSValueConst", "this_obj"),
    ("JSValueConst", "new_target"), ("int", "argc"), ("JSValueConst *", "argv"), ("int", "flags"),
    ("JSRuntime *", "rt"), ("JSContext *", "ctx"), ("JSObject *", "p"), ("JSFunctionBytecode *", "b"),
    ("JSStackFrame *", "sf"), ("uint8_t *", "pc"), ("int", "opcode"), ("int", "arg_allocated_size"),
    ("JSValue *", "local_buf"), ("JSValue *", "stack_buf"), ("JSValue *", "var_buf"), ("JSValue *", "arg_buf"),
    ("JSValue *", "sp"), ("JSValue", "ret_val"), ("JSVarRef **", "var_refs"), ("size_t", "alloca_size"),
    ("int", "call_argc"), ("JSValue *", "call_argv"),
]
CHANGED = ["pc", "sp", "ret_val", "call_argc", "call_argv", "opcode"]   # what handlers may change and later pieces read
TEMPS = [("int", "i"), ("JSValue *", "pval")]                # scratch locals: fresh in each handler

# Handlers must not change anything else that later pieces read.
# (A piece that declares a local of the same name, as several do with flags, is changing its own.)
for piece in pieces:
    code = bare("\n".join(piece["lines"]))
    for kind, name in STATE:
        if name in CHANGED:
            continue
        declared = re.search(r"^\s*(?:const\s+)?[A-Za-z_]\w*[\s*]+(?:[\w\s,*]*,\s*)?\**%s\s*[;,=\[]" % name, code, re.M)
        if not declared and re.search(r"(?<![\w.>])%s\s*(=(?!=)|\+\+|--|\+=|-=|\|=|&=)|(\+\+|--)%s\b" % (name, name), code):
            fail("%s changes %s, which isn't copied back" % (piece["name"], name))

out = []
out.append("/* ---- QuickJS's interpreter loop, one function per opcode (engine/js/split_interp.py) ---- */")
out.append("enum { JS_VM_NEXT = 0, JS_VM_EXCEPTION, JS_VM_DONE, JS_VM_DONE_GENERATOR };")
out.append("typedef struct JSVMState {")
for kind, name in STATE:
    out.append("    %s %s;" % (kind, name))
out.append("} JSVMState;")
for piece in pieces:
    out.append("static int %s(JSVMState *S);" % piece["name"])
out.append("#define JS_VM_LOAD() \\")
out.append(" \\\n".join("    %s %s = S->%s; (void)%s;" % (kind, name, name, name) for kind, name in STATE) + " \\")
out.append(" \\\n".join("    %s %s; (void)%s;" % (kind, name, name) for kind, name in TEMPS))
out.append("#define JS_VM_SAVE() (%s)" % ", ".join("S->%s = %s" % (n, n) for n in CHANGED))
out.append("#define JS_VM_LEAVE(code) do { JS_VM_SAVE(); return (code); } while (0)")
out.append("#define JS_VM_GOTO(piece) do { JS_VM_SAVE(); return piece(S); } while (0)")
out.append("#define CASE(op) vm_case_ ## op")
out.append("#define DEFAULT vm_case_default")
out.append("#define BREAK JS_VM_LEAVE(JS_VM_NEXT)")
out.append("#pragma clang diagnostic push")
out.append('#pragma clang diagnostic ignored "-Wunused-label"')
out.append('#pragma clang diagnostic ignored "-Wunused-variable"')
out.append('#pragma clang diagnostic ignored "-Wunused-but-set-variable"')

for i, piece in enumerate(pieces):
    code = "\n".join(piece["lines"])
    for target, value in leaving.items():
        code = re.sub(r"\bgoto\s+%s\s*;" % target, "JS_VM_LEAVE(%s);" % value, code)
    for label, owner in shared.items():
        code = re.sub(r"\bgoto\s+%s\s*;" % label, "JS_VM_GOTO(%s);" % owner["name"], code)
    # noinline: every handler stays out of the loop. (Measured: inlining the forty one-line
    # cases made the loop too big to compile again; inlining a few gained nothing.)
    out.append("static __attribute__((noinline)) int %s(JSVMState *S)\n{\n    JS_VM_LOAD();" % piece["name"])
    out.append(code)
    # Falling off the end of a piece runs into the next, as in the switch.
    following = pieces[i + 1]["name"] if i + 1 < len(pieces) else None
    out.append("    JS_VM_GOTO(%s);" % following if following else "    JS_VM_LEAVE(JS_VM_NEXT);")
    out.append("}")

out.append("#pragma clang diagnostic pop")
out.append("#undef CASE\n#undef DEFAULT\n#undef BREAK")
handlers = "\n".join(out) + "\n\n"

# ---- the new loop ---------------------------------------------------------------------------
cases = []
default_piece = None
for piece in pieces:
    if piece["default"]:
        default_piece = piece
    if piece["opcodes"]:
        labels = " ".join("case %s:" % op for op in piece["opcodes"])
        cases.append("            %s r = %s(&S); break;" % (labels, piece["name"]))
if default_piece is None:
    fail("no default case")
# The loop itself is a function of its own, and so is the search for a catch when something
# throws: JS_CallInternal keeps only setting up the frame and tearing it down, so that it and
# the loop are each small enough for the JVM to compile.
run = ["static __attribute__((noinline)) int js_vm_run(JSVMState *S)", "{", "    for(;;) {", "        int r;",
       "        switch (S->opcode = *S->pc++) {"]
run += ["    " + c.replace("(&S)", "(S)") for c in cases]
run += ["            default: r = %s(S); break;" % default_piece["name"], "        }",
        "        if (unlikely(r != JS_VM_NEXT))", "            return r;", "    }", "}", ""]

after = func[body_end + len(loop_tail):]
unwind_end = "    ret_val = JS_EXCEPTION;\n"
if after.count(unwind_end) != 1:
    fail("the end of the exception handler has changed")
unwind_body = after[:after.index(unwind_end)]
if unwind_body.count("goto restart;") != 1 or re.search(r"\bgoto\s+(?!restart)", unwind_body) or "return" in bare(unwind_body):
    fail("the exception handler's shape has changed")
unwind = ["/* Something threw: finds the innermost catch on the stack (1: carry on there) or unwinds it all (0). */",
          "static __attribute__((noinline)) int js_vm_unwind(JSVMState *S)", "{", "    JS_VM_LOAD();",
          unwind_body.replace("goto restart;", "JS_VM_LEAVE(1);"), "    JS_VM_LEAVE(0);", "}", ""]
handlers += "\n".join(run) + "\n" + "\n".join(unwind) + "\n"

store = ["    S.%s = %s;" % (name, name) for kind, name in STATE if name not in ("call_argc", "call_argv")]
store += ["    S.call_argc = 0;", "    S.call_argv = NULL;"]
loop = [" restart:"] + store + ["    r = js_vm_run(&S);"]
loop += ["    %s = S.%s;" % (n, n) for n in ("pc", "sp", "ret_val", "opcode")]
loop += ["    if (r == JS_VM_EXCEPTION)", "        goto exception;", "    if (r == JS_VM_DONE)",
         "        goto done;", "    goto done_generator;", " exception:"] + store
loop += ["    r = js_vm_unwind(&S);", "    pc = S.pc;", "    sp = S.sp;", "    if (r)", "        goto restart;", ""]
new_func = func[:func.index(loop_head)] + "\n".join(loop) + after[after.index(unwind_end):]
# The state, declared where the function's locals are.
first_local = "    JSRuntime *rt = caller_ctx->rt;\n"
if new_func.count(first_local) != 1:
    fail("JS_CallInternal's first local has changed")
new_func = new_func.replace(first_local, first_local + "    JSVMState S;\n    int r;\n")

# The original dispatch macros (and computed-goto table) aren't used by the new loop.
macros = re.search(r"#if !DIRECT_DISPATCH\n.*?\n#endif\n", new_func, re.S)
if not macros:
    fail("the dispatch macros weren't found")
new_func = new_func.replace(macros.group(0), "")
if re.search(r"\b(SWITCH|BREAK|CASE)\b", new_func):
    fail("the new loop still uses the old dispatch macros")

result = text[:start] + handlers + new_func + text[end:]
open(out_path, "w", encoding="utf-8").write(result)
print("split_interp: %d pieces for %d opcodes" % (len(pieces), sum(len(p["opcodes"]) for p in pieces)))
