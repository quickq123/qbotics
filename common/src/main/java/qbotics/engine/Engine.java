package qbotics.engine;

import com.dylibso.chicory.compiler.InterpreterFallback;
import com.dylibso.chicory.compiler.MachineFactoryCompiler;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Machine;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.function.Function;

/** The shared, immutable part of the engine: the parsed (and optionally compiled) Wasm module. */
public final class Engine {
    final WasmModule module;
    /** Null means "use Chicory's interpreter". */
    final Function<Instance, Machine> machineFactory;

    private Engine(WasmModule module, Function<Instance, Machine> machineFactory) {
        this.module = module;
        this.machineFactory = machineFactory;
    }

    /** The engine shipped inside the mod jar, compiled to JVM bytecode. */
    public static Engine bundled() {
        WasmModule module;
        try (InputStream in = Engine.class.getResourceAsStream("micropython.wasm")) {
            if (in == null) {
                throw new IllegalStateException("micropython.wasm is missing from the mod jar");
            }
            module = Parser.parse(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new Engine(module, MachineFactoryCompiler.builder(module)
                .withInterpreterFallback(InterpreterFallback.FAIL)
                .compile());
    }

    /** Interpreted: slow, but nothing to compile. */
    public static Engine interpreted(Path wasm) {
        return new Engine(Parser.parse(wasm), null);
    }

    /** Wasm translated to JVM bytecode. The translation is done once and shared by every computer. */
    public static Engine compiled(Path wasm) {
        WasmModule module = Parser.parse(wasm);
        // FAIL: refuse to silently run any function in Chicory's (much slower) interpreter.
        String fallback = System.getProperty("engine.fallback", "FAIL");
        return new Engine(module, MachineFactoryCompiler.builder(module)
                .withInterpreterFallback(InterpreterFallback.valueOf(fallback))
                .compile());
    }
}
