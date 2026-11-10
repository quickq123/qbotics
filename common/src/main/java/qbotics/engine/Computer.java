package qbotics.engine;

import com.dylibso.chicory.runtime.ExportFunction;
import com.dylibso.chicory.runtime.HostFunction;
import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.wasm.types.FunctionType;
import com.dylibso.chicory.wasm.types.ValType;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * One in-game computer: a private MicroPython instance with a fixed heap.
 * Not thread-safe; a computer is driven by one (virtual) thread at a time.
 */
public final class Computer {
    /** Thrown from the metering hook to abandon a program that ignores a polite stop. */
    public static final class HardStop extends RuntimeException {
        HardStop() {
            super("computer hard-stopped", null, false, false);
        }
    }

    public enum StopMode { NONE, POLITE, HARD }

    private static final ValType I32 = ValType.I32;

    private final Instance instance;
    private final ExportFunction alloc;
    private final ExportFunction free;
    private final ExportFunction exec;
    private final ExportFunction setBudget;
    private final ExportFunction heapUsed;
    private final ExportFunction replNeedsMore;

    /** Where queued world calls go; stands in for the main-thread executor (design doc §2.2). */
    private final Function<String, CompletableFuture<String>> worldCalls;
    private final Map<Integer, CompletableFuture<String>> tickets = new HashMap<>();
    private final HostFileSystem fileSystem;
    /** The result of the last file operation, waiting for the instance to fetch it. */
    private byte[] fsResult = new byte[0];
    private int nextTicket = 1;
    private static final int MAX_WAITING_CALLS = 32;
    /** Must not exceed RESULT_MAX_SIZE in the engine's modhost.c. */
    private static final int MAX_RESULT_BYTES = 48 * 1024;

    private final StringBuilder output = new StringBuilder();
    /** If set, printed text goes here as it is produced instead of being buffered for takeOutput(). */
    public Consumer<String> outputSink;

    /** Where Python's input() gets a line from. */
    public interface LineReader {
        /** Waits for a line to be entered. Returns null if the wait was interrupted (Ctrl+C). */
        String readLine() throws InterruptedException;
    }

    /** Null means this computer has nowhere to ask: input() raises EOFError. */
    public LineReader lineReader;

    /** Backward jumps a program may execute before the host gets control again. */
    public int sliceBudget = 20_000;
    public long slicesUsed;
    /** Hard-stop a run after this many slices (a runaway-program guard for tests). */
    public long maxSlices = Long.MAX_VALUE;
    public volatile StopMode stop = StopMode.NONE;

    /**
     * Design doc §4: a fair semaphore with one permit per worker core. A computer holds a
     * permit only while running Python. It gives the permit up while waiting on the world,
     * and goes to the back of the queue at the end of every slice. Null = no CPU cap.
     */
    public Semaphore cpu;

    /**
     * JVM stack for a thread that runs a computer. Each Python call level needs roughly
     * 2-4 KB of JVM stack; MicroPython's own recursion limit (from the 256 KB Wasm C stack)
     * must trip first. Virtual threads can't be used: their stack can't be enlarged.
     */
    public static final long THREAD_STACK_BYTES = 8L * 1024 * 1024;

    public static Thread newThread(Runnable body, String name) {
        return new Thread(null, body, name, THREAD_STACK_BYTES);
    }

    /** A computer with no drive. */
    public Computer(Engine engine, int heapBytes, Function<String, CompletableFuture<String>> worldCalls) {
        this(engine, heapBytes, worldCalls, HostFileSystem.NONE);
    }

    public Computer(Engine engine, int heapBytes, Function<String, CompletableFuture<String>> worldCalls,
            HostFileSystem fileSystem) {
        this.worldCalls = worldCalls;
        this.fileSystem = fileSystem;
        var builder = Instance.builder(engine.module)
                .withImportValues(ImportValues.builder().addFunction(hostFunctions()).build())
                .withStart(false);
        if (engine.machineFactory != null) {
            builder.withMachineFactory(engine.machineFactory);
        }
        instance = builder.build();
        instance.export("_initialize").apply();
        alloc = instance.export("mpy_alloc");
        free = instance.export("mpy_free");
        exec = instance.export("mpy_exec");
        setBudget = instance.export("mpy_set_budget");
        heapUsed = instance.export("mpy_heap_used");
        replNeedsMore = instance.export("mpy_repl_needs_more");
        long rc = instance.export("mpy_init").apply(heapBytes)[0];
        if (rc != 0) {
            throw new IllegalStateException("mpy_init failed: " + rc + " " + takeOutput());
        }
    }

    /** Runs a script. Returns true if it finished without an uncaught Python exception. */
    public boolean exec(String source) {
        return exec(source, false);
    }

    /** Runs one REPL entry: like exec, but the value of a bare expression is printed. */
    public boolean execRepl(String source) {
        return exec(source, true);
    }

    /**
     * REPL helper: true if this input needs another line before it can run (an open bracket
     * or string, or a compound statement not yet closed with a blank line).
     */
    public boolean replNeedsMore(String source) {
        int ptr = copyIn(source);
        try {
            return replNeedsMore.apply(ptr)[0] != 0;
        } finally {
            free.apply(ptr);
        }
    }

    /** Copies a string into the instance as NUL-terminated UTF-8; the caller frees it. */
    private int copyIn(String source) {
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        int ptr = (int) alloc.apply(bytes.length + 1)[0];
        instance.memory().write(ptr, bytes);
        instance.memory().writeByte(ptr + bytes.length, (byte) 0);
        return ptr;
    }

    private boolean exec(String source, boolean repl) {
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        int ptr = copyIn(source);
        setBudget.apply(sliceBudget);
        slicesUsed = 0;
        boolean finished = false;
        acquireCpu();
        try {
            boolean ok = exec.apply(ptr, bytes.length, repl ? 1 : 0)[0] == 0;
            finished = true;
            return ok;
        } finally {
            releaseCpu();
            // After a hard stop the interpreter's state is undefined: don't call back into it.
            if (finished) {
                free.apply(ptr);
            }
        }
    }

    private void acquireCpu() {
        if (cpu != null) {
            cpu.acquireUninterruptibly();
        }
    }

    private void releaseCpu() {
        if (cpu != null) {
            cpu.release();
        }
    }

    /** Returns everything printed since the last call. */
    public String takeOutput() {
        String s = output.toString();
        output.setLength(0);
        return s;
    }

    public int wasmMemoryBytes() {
        return instance.memory().pages() * 65536;
    }

    public int pythonHeapUsed() {
        return (int) heapUsed.apply()[0];
    }

    private String readString(int ptr, int len) {
        return new String(instance.memory().readBytes(ptr, len), StandardCharsets.UTF_8);
    }

    private HostFunction[] hostFunctions() {
        return new HostFunction[] {
            new HostFunction("host", "write", FunctionType.of(List.of(I32, I32), List.of()),
                    (inst, args) -> {
                        String text = readString((int) args[0], (int) args[1]);
                        if (outputSink != null) {
                            outputSink.accept(text);
                        } else {
                            output.append(text);
                        }
                        return null;
                    }),
            new HostFunction("host", "ticks_us", FunctionType.of(List.of(), List.of(ValType.I64)),
                    (inst, args) -> new long[] {System.nanoTime() / 1000}),
            new HostFunction("host", "sleep_ms", FunctionType.of(List.of(I32), List.of()),
                    (inst, args) -> {
                        releaseCpu();
                        try {
                            // Sleep in short steps so a stop request doesn't wait out a long sleep.
                            long end = System.nanoTime() + args[0] * 1_000_000L;
                            for (long left; stop == StopMode.NONE && (left = end - System.nanoTime()) > 0; ) {
                                Thread.sleep(Math.min(50, left / 1_000_000L + 1));
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            acquireCpu();
                        }
                        return null;
                    }),
            new HostFunction("host", "slice", FunctionType.of(List.of(), List.of(I32)),
                    (inst, args) -> {
                        if (++slicesUsed > maxSlices) {
                            throw new HardStop();
                        }
                        switch (stop) {
                            case POLITE:
                                stop = StopMode.NONE;
                                return new long[] {0};
                            case HARD:
                                throw new HardStop();
                            default:
                                // End of slice: let any waiting computer have the core.
                                if (cpu != null && cpu.hasQueuedThreads()) {
                                    cpu.release();
                                    cpu.acquireUninterruptibly();
                                }
                                return new long[] {sliceBudget};
                        }
                    }),
            new HostFunction("host", "submit", FunctionType.of(List.of(I32, I32), List.of(I32)),
                    (inst, args) -> {
                        String call = readString((int) args[0], (int) args[1]);
                        int ticket = nextTicket++;
                        // A program can submit without ever waiting: don't let calls pile up.
                        tickets.put(ticket, tickets.size() >= MAX_WAITING_CALLS
                                ? CompletableFuture.completedFuture("error:too many calls are waiting for an answer")
                                : worldCalls.apply(call));
                        return new long[] {ticket};
                    }),
            new HostFunction("host", "fs_call", FunctionType.of(List.of(I32, I32, I32, I32, I32), List.of(I32)),
                    (inst, args) -> {
                        String path = readString((int) args[1], (int) args[2]);
                        byte[] arg = instance.memory().readBytes((int) args[3], (int) args[4]);
                        try {
                            fsResult = fileSystem.call((int) args[0], path, arg);
                            return new long[] {fsResult.length};
                        } catch (HostFileSystem.FsException e) {
                            fsResult = new byte[0];
                            return new long[] {-e.errno};
                        }
                    }),
            new HostFunction("host", "fs_result", FunctionType.of(List.of(I32, I32), List.of()),
                    (inst, args) -> {
                        if (fsResult.length > 0 && fsResult.length <= args[1]) {
                            instance.memory().write((int) args[0], fsResult);
                        }
                        fsResult = new byte[0];
                        return null;
                    }),
            new HostFunction("host", "read_line", FunctionType.of(List.of(I32, I32), List.of(I32)),
                    (inst, args) -> {
                        LineReader reader = lineReader;
                        if (reader == null) {
                            return new long[] {-2};
                        }
                        String line;
                        releaseCpu(); // waiting for a person can take a while
                        try {
                            line = reader.readLine();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            line = null;
                        } finally {
                            acquireCpu();
                        }
                        if (line == null) {
                            return new long[] {-1};
                        }
                        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
                        int length = Math.min(bytes.length, (int) args[1]);
                        instance.memory().write((int) args[0], java.util.Arrays.copyOf(bytes, length));
                        return new long[] {length};
                    }),
            new HostFunction("host", "wait", FunctionType.of(List.of(I32, I32, I32, I32), List.of(I32)),
                    (inst, args) -> new long[] {
                        await((int) args[0], (int) args[1], (int) args[2], (int) args[3])
                    }),
        };
    }

    /** Parks this computer's thread until the world call completes. */
    private int await(int ticket, int timeoutMs, int bufPtr, int cap) {
        CompletableFuture<String> future = tickets.get(ticket);
        if (future == null) {
            return -1;
        }
        String result;
        boolean mustBlock = !future.isDone() && timeoutMs != 0;
        if (mustBlock) {
            releaseCpu();
        }
        try {
            // Wait in short steps, so a stop request (Ctrl+C) ends a long wait, such as one for a message.
            long end = timeoutMs < 0 ? Long.MAX_VALUE : System.nanoTime() + timeoutMs * 1_000_000L;
            while (true) {
                long left = end - System.nanoTime();
                if (future.isDone() || timeoutMs == 0) {
                    result = future.get(0, TimeUnit.MILLISECONDS);
                    break;
                }
                if (left <= 0 || stop != StopMode.NONE) {
                    return -1;
                }
                try {
                    result = future.get(Math.min(50_000_000L, left), TimeUnit.NANOSECONDS);
                    break;
                } catch (TimeoutException stillWaiting) {
                    // look at the stop flag and the clock again
                }
            }
        } catch (TimeoutException e) {
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        } catch (ExecutionException e) {
            result = "error:" + e.getCause();
        } finally {
            if (mustBlock) {
                acquireCpu();
            }
        }
        byte[] bytes = result.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_RESULT_BYTES) {
            bytes = "error:the answer is too large for this computer".getBytes(StandardCharsets.UTF_8);
            result = "error:the answer is too large for this computer";
        }
        if (bytes.length > cap) {
            // Too big for the buffer offered: the answer is kept, and the caller asks again with a bigger one.
            tickets.put(ticket, CompletableFuture.completedFuture(result));
            return bytes.length;
        }
        tickets.remove(ticket);
        instance.memory().write(bufPtr, bytes);
        return bytes.length;
    }
}
