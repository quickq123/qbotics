// The web panel's JavaScript engine (design doc §10.3): QuickJS (quickjs-ng), run in each
// player's game, one runtime per page. No network, no files: a script reaches the world only
// through __host(op, text), which the game answers. A memory limit, and a time budget per run
// (the interrupt handler asks the game for the time), keep a page from taking the game down.
//
// Built to WebAssembly with wasi-sdk (see build.sh), run by Chicory.

#include "quickjs.h"

#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#define IMPORT(name) __attribute__((import_module("js"), import_name(name)))
#define EXPORT(name) __attribute__((export_name(name)))

// The game answers op with text; returns the answer's length, kept for js_host_take.
IMPORT("host") int32_t host_call(const char* op, int32_t op_len, const char* arg, int32_t arg_len);
// Copies the answer just made into buf.
IMPORT("host_take") void host_take(char* buf);
// Milliseconds, for the time budget.
IMPORT("now") double host_now(void);

static JSRuntime* rt;
static JSContext* ctx;
static double deadline;
static char* result;
static int32_t result_len;

static int interrupt(JSRuntime* r, void* opaque) {
    (void)r;
    (void)opaque;
    return deadline > 0 && host_now() > deadline;
}

static JSValue js_host(JSContext* c, JSValueConst this_val, int argc, JSValueConst* argv) {
    (void)this_val;
    size_t op_len = 0, arg_len = 0;
    const char* op = argc > 0 ? JS_ToCStringLen(c, &op_len, argv[0]) : NULL;
    const char* arg = argc > 1 ? JS_ToCStringLen(c, &arg_len, argv[1]) : NULL;
    if (!op) {
        return JS_ThrowTypeError(c, "__host(op, text)");
    }
    int32_t n = host_call(op, (int32_t)op_len, arg ? arg : "", (int32_t)arg_len);
    JS_FreeCString(c, op);
    if (arg) {
        JS_FreeCString(c, arg);
    }
    if (n < 0) {
        return JS_UNDEFINED;
    }
    char* buf = malloc((size_t)n + 1);
    if (!buf) {
        return JS_ThrowOutOfMemory(c);
    }
    host_take(buf);
    JSValue out = JS_NewStringLen(c, buf, (size_t)n);
    free(buf);
    return out;
}

EXPORT("js_alloc") void* bridge_alloc(int32_t n) { return malloc((size_t)n); }
EXPORT("js_free") void bridge_free(void* p) { free(p); }

// A fresh runtime with this much memory for scripts. Returns 0 on success.
EXPORT("js_init") int32_t js_init(int32_t memory_limit) {
    if (ctx) {
        JS_FreeContext(ctx);
        JS_FreeRuntime(rt);
    }
    rt = JS_NewRuntime();
    if (!rt) {
        return 1;
    }
    JS_SetMemoryLimit(rt, (size_t)memory_limit);
    JS_SetMaxStackSize(rt, 192 * 1024);
    JS_SetInterruptHandler(rt, interrupt, NULL);
    ctx = JS_NewContext(rt);
    if (!ctx) {
        return 2;
    }
    JSValue global = JS_GetGlobalObject(ctx);
    JS_SetPropertyStr(ctx, global, "__host", JS_NewCFunction(ctx, js_host, "__host", 2));
    JS_FreeValue(ctx, global);
    return 0;
}

// Runs code with a time budget (ms; 0 for none). Returns 0 if it ran, 1 if it threw; either
// way js_result then gives the value as text (String(value)) or the error and its stack.
EXPORT("js_eval") int32_t js_eval(const char* code, int32_t len, const char* name, double budget_ms) {
    free(result);
    result = NULL;
    result_len = 0;
    if (!ctx) {
        return 1;
    }
    deadline = budget_ms > 0 ? host_now() + budget_ms : 0;
    // JS_Eval wants the source to end with a 0 byte.
    char* src = malloc((size_t)len + 1);
    memcpy(src, code, (size_t)len);
    src[len] = 0;
    JSValue value = JS_Eval(ctx, src, (size_t)len, name, JS_EVAL_TYPE_GLOBAL);
    free(src);
    // Promises and their callbacks run now, within the same budget.
    JSContext* job_ctx;
    while (!JS_IsException(value) && JS_IsJobPending(rt) && JS_ExecutePendingJob(rt, &job_ctx) > 0) {
    }
    deadline = 0;
    int failed = JS_IsException(value);
    JSValue shown;
    if (failed) {
        JSValue error = JS_GetException(ctx);
        JSValue stack = JS_IsError(ctx, error) ? JS_GetPropertyStr(ctx, error, "stack") : JS_UNDEFINED;
        const char* message = JS_ToCString(ctx, error);
        const char* trace = JS_IsUndefined(stack) ? NULL : JS_ToCString(ctx, stack);
        size_t ml = message ? strlen(message) : 0, tl = trace ? strlen(trace) : 0;
        result = malloc(ml + tl + 2);
        memcpy(result, message ? message : "", ml);
        result[ml] = '\n';
        memcpy(result + ml + 1, trace ? trace : "", tl);
        result_len = (int32_t)(ml + 1 + tl);
        if (message) {
            JS_FreeCString(ctx, message);
        }
        if (trace) {
            JS_FreeCString(ctx, trace);
        }
        JS_FreeValue(ctx, stack);
        JS_FreeValue(ctx, error);
    } else {
        size_t n = 0;
        const char* text = JS_IsUndefined(value) ? NULL : JS_ToCStringLen(ctx, &n, value);
        result = malloc(n + 1);
        if (text) {
            memcpy(result, text, n);
            JS_FreeCString(ctx, text);
        }
        result_len = (int32_t)n;
    }
    JS_FreeValue(ctx, value);
    return failed;
}

EXPORT("js_result") const char* js_result(void) { return result; }
EXPORT("js_result_len") int32_t js_result_len(void) { return result_len; }

// How much memory scripts use now, in bytes.
EXPORT("js_memory") int32_t js_memory(void) {
    if (!rt) {
        return 0;
    }
    JSMemoryUsage usage;
    JS_ComputeMemoryUsage(rt, &usage);
    return (int32_t)usage.memory_used_size;
}
