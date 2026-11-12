package qbotics.web;

import com.dylibso.chicory.compiler.InterpreterFallback;
import com.dylibso.chicory.compiler.MachineFactoryCompiler;
import com.dylibso.chicory.runtime.ExportFunction;
import com.dylibso.chicory.runtime.HostFunction;
import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Machine;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.wasm.types.FunctionType;
import com.dylibso.chicory.wasm.types.ValType;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * A web page's JavaScript (design doc §10.3): QuickJS in WebAssembly (engine/js), one runtime
 * per page, in the player's game. Scripts reach the game only through {@code __host(op, text)},
 * answered by the function given here. Each run has a time budget; memory is limited.
 * Not thread-safe: use it from one thread.
 */
public final class JsRuntime {
    private static final ValType I32 = ValType.I32;
    private static final ValType I64 = ValType.I64;
    private static final ValType F64 = ValType.F64;

    private static WasmModule module;
    private static Function<Instance, Machine> machines;

    /** Parsed and translated to JVM bytecode once for every page. */
    private static synchronized void loadModule() {
        if (module != null) {
            return;
        }
        try (InputStream in = JsRuntime.class.getResourceAsStream("js.wasm")) {
            if (in == null) {
                throw new IllegalStateException("js.wasm is missing from the mod jar");
            }
            module = Parser.parse(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String fallback = System.getProperty("qbotics.js.fallback", "WARN");
        machines = MachineFactoryCompiler.builder(module).withInterpreterFallback(InterpreterFallback.valueOf(fallback)).compile();
    }

    /** The result of running some code: what it gave (as text), or the error and where. */
    public record Result(boolean ok, String text) {
    }

    private final Instance instance;
    private final ExportFunction alloc;
    private final ExportFunction free;
    private final ExportFunction eval;
    private final ExportFunction result;
    private final ExportFunction resultLength;
    private final ExportFunction memory;
    private final BiFunction<String, String, @Nullable String> host;
    private byte @Nullable [] answer;

    /**
     * @param memoryLimit bytes scripts may use
     * @param host        answers __host(op, text); null means undefined
     */
    public JsRuntime(int memoryLimit, BiFunction<String, String, @Nullable String> host) {
        loadModule();
        this.host = host;
        instance = Instance.builder(module)
                .withImportValues(ImportValues.builder().addFunction(hostFunctions()).build())
                .withMachineFactory(machines)
                .withStart(false)
                .build();
        instance.export("_initialize").apply();
        alloc = instance.export("js_alloc");
        free = instance.export("js_free");
        eval = instance.export("js_eval");
        result = instance.export("js_result");
        resultLength = instance.export("js_result_len");
        memory = instance.export("js_memory");
        long rc = instance.export("js_init").apply(memoryLimit)[0];
        if (rc != 0) {
            throw new IllegalStateException("The JavaScript engine didn't start (" + rc + ")");
        }
    }

    /** Runs code, stopping it after budgetMs (0: no limit). */
    public Result eval(String code, String name, double budgetMs) {
        byte[] src = code.getBytes(StandardCharsets.UTF_8);
        byte[] file = (name + "\0").getBytes(StandardCharsets.UTF_8);
        int sp = (int) alloc.apply(Math.max(1, src.length))[0];
        int np = (int) alloc.apply(file.length)[0];
        instance.memory().write(sp, src);
        instance.memory().write(np, file);
        boolean failed;
        try {
            failed = eval.apply(sp, src.length, np, Double.doubleToRawLongBits(budgetMs))[0] != 0;
        } finally {
            free.apply(sp);
            free.apply(np);
        }
        int at = (int) result.apply()[0];
        int length = (int) resultLength.apply()[0];
        String text = at == 0 || length == 0 ? "" : new String(instance.memory().readBytes(at, length), StandardCharsets.UTF_8);
        return new Result(!failed, text);
    }

    /** Bytes scripts are using now. */
    public int memoryUsed() {
        return (int) memory.apply()[0];
    }

    private String readString(int ptr, int len) {
        return new String(instance.memory().readBytes(ptr, len), StandardCharsets.UTF_8);
    }

    private HostFunction[] hostFunctions() {
        return new HostFunction[] {
            new HostFunction("js", "now", FunctionType.of(List.of(), List.of(F64)),
                    (inst, args) -> new long[] {Double.doubleToRawLongBits(System.nanoTime() / 1_000_000.0)}),
            new HostFunction("js", "host", FunctionType.of(List.of(I32, I32, I32, I32), List.of(I32)), (inst, args) -> {
                String reply = host.apply(readString((int) args[0], (int) args[1]), readString((int) args[2], (int) args[3]));
                answer = reply == null ? null : reply.getBytes(StandardCharsets.UTF_8);
                return new long[] {answer == null ? -1 : answer.length};
            }),
            new HostFunction("js", "host_take", FunctionType.of(List.of(I32), List.of()), (inst, args) -> {
                if (answer != null) {
                    inst.memory().write((int) args[0], answer);
                    answer = null;
                }
                return null;
            }),
            // The system calls the C library links: a clock, and nothing to read or write.
            new HostFunction("wasi_snapshot_preview1", "clock_time_get", FunctionType.of(List.of(I32, I64, I32), List.of(I32)), (inst, args) -> {
                long nanos = (int) args[0] == 0 ? System.currentTimeMillis() * 1_000_000L : System.nanoTime();
                inst.memory().writeLong((int) args[2], nanos);
                return new long[] {0};
            }),
            new HostFunction("wasi_snapshot_preview1", "fd_close", FunctionType.of(List.of(I32), List.of(I32)), (inst, args) -> new long[] {8}),
            new HostFunction("wasi_snapshot_preview1", "fd_fdstat_get", FunctionType.of(List.of(I32, I32), List.of(I32)), (inst, args) -> new long[] {8}),
            new HostFunction("wasi_snapshot_preview1", "fd_seek", FunctionType.of(List.of(I32, I64, I32, I32), List.of(I32)),
                    (inst, args) -> new long[] {8}),
            new HostFunction("wasi_snapshot_preview1", "fd_write", FunctionType.of(List.of(I32, I32, I32, I32), List.of(I32)), (inst, args) -> {
                int total = 0;
                for (int i = 0; i < (int) args[2]; i++) {
                    total += inst.memory().readInt((int) args[1] + 8 * i + 4);
                }
                inst.memory().writeI32((int) args[3], total);
                return new long[] {0};
            }),
        };
    }
}
