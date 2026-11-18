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
    /** Where the player's own code is running (vm_split.c), for the editor's current-line display. */
    private final ExportFunction where;
    private int whereBuf;
    private static final int WHERE_CAP = 256;
    /**
     * The budget's bits that switch the engine to its traced loop (see mpy_budget_expired):
     * to step, or (watched) to note the line of the player's code that is running.
     */
    private static final int STEP_BIT = 0x40000000;
    private static final int WATCH_BIT = 0x20000000;
    /** Whether the last budget handed out asked the engine to note lines. */
    private boolean watchSent;

    /** Where queued world calls go; stands in for the main-thread executor (design doc §2.2). */
    private final Function<String, CompletableFuture<String>> worldCalls;
    private final Map<Integer, CompletableFuture<String>> tickets = new HashMap<>();
    /** Tickets kept when a program ends, because the next program may still wait on them. */
    private final java.util.Set<Integer> lasting = new java.util.HashSet<>();
    /** Which calls those are (a receive() that timed out keeps its place for the next one). */
    public java.util.function.Predicate<String> lastsAcrossRuns = call -> false;
    /** How many Ctrl+Cs have reached the running program; see {@link #beginRun()}. */
    public volatile int stopsDelivered;
    private final HostFileSystem fileSystem;
    /** The result of the last file operation, waiting for the instance to fetch it. */
    private byte[] fsResult = new byte[0];
    private int nextTicket = 1;
    private static final int MAX_WAITING_CALLS = 32;
    /**
     * The largest answer handed to a program: a quarter of its memory, at least 48 KB (an
     * uplink answer is wrapped in JSON, which can make it up to twice as long). Must not exceed
     * RESULT_MAX_SIZE in the engine's modhost.c.
     */
    private final int maxResultBytes;

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
     * Set to hold the program where it is, like a breakpoint: it waits at its next step
     * (between instructions, in a sleep, or as a world call returns) until cleared. A stop
     * still ends it.
     */
    public volatile boolean paused;
    /**
     * Held by the server rather than the player: a laptop whose owner is offline or carries no
     * laptop. It waits exactly as when paused, and carries on when let go.
     */
    public volatile boolean held;

    /** A line of the player's own code: its number and its file ("/startup.py"). */
    public record Location(int line, String file) {
    }

    /**
     * Set while the player steps through the program (F8): it stops at each new line of
     * their own code. Set while running, it stops at the next one.
     */
    public volatile boolean stepping;
    /** Set while someone has this computer's editor open: then where it is gets noted as it runs. */
    public volatile boolean watched;
    /** True while the AI helper's look-only scratchpad runs: only calls that read are made (see LookOnly). */
    public volatile boolean lookOnly;
    /** The editor's Dry run: moves are only tried, and other changes stop the program (DryRun). */
    public volatile boolean dryRun;
    /** Where the program was last seen (while watched, or where it paused); null if not in the player's code. */
    public volatile Location location;
    /** Told where the program is each time it pauses (Ctrl+Z, Pause, or a step). */
    public java.util.function.Consumer<Location> onPause;

    /** Where the player's own code is running now, or null. On the computer's thread only. */
    private Location where() {
        if (where == null) {
            return null; // an engine built without it (an older one, in the benchmark)
        }
        int line = (int) where.apply(whereBuf, WHERE_CAP)[0];
        if (line <= 0) {
            return null;
        }
        byte[] raw = instance.memory().readBytes(whereBuf, WHERE_CAP);
        int n = 0;
        while (n < raw.length && raw[n] != 0) {
            n++;
        }
        String file = new String(raw, 0, n, StandardCharsets.UTF_8);
        // A module imported by a program in the top folder is named "//name.py": import puts "/"
        // and a slash before the name (rom/_boot.py _start).
        return new Location(line, file.startsWith("//") ? file.substring(1) : file);
    }

    private static ExportFunction optionalExport(Instance instance, String name) {
        try {
            return instance.export(name);
        } catch (RuntimeException missing) {
            return null;
        }
    }

    /** Notes where the program is, if someone is watching. Cheap: a few frames are looked at. */
    private void sample() {
        if (watched) {
            location = where();
        }
    }

    /**
     * How long a waiting program sleeps before it looks again by itself. It is woken at once when
     * it is stopped, paused, let go or answered (wake()), so this is only for a wake that was
     * missed. (It used to look every 50 ms: twenty wakings a second for every waiting computer.)
     */
    private static final long WAIT_NANOS = 1_000_000_000L;
    /** The thread running this computer's program, while it waits (to be woken). */
    private volatile Thread waiter;
    /** The answers that already wake it when they come (each is told to once). Its own thread's. */
    private final java.util.Set<CompletableFuture<String>> hooked = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    /** Its stop, paused or held setting changed (or a call was answered): a waiting program looks again now. */
    public void wake() {
        Thread thread = waiter;
        if (thread != null) {
            java.util.concurrent.locks.LockSupport.unpark(thread);
        }
    }

    /** Sleeps until woken, or for this long. Call {@link #waiting()} before the check that decides to sleep. */
    private void parkFor(long nanos) throws InterruptedException {
        java.util.concurrent.locks.LockSupport.parkNanos(this, Math.min(nanos, WAIT_NANOS));
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
    }

    /** This thread is about to wait: a wake() from now on reaches it (even one that comes before it sleeps). */
    private void waiting() {
        waiter = Thread.currentThread();
    }

    /** Waits while paused (or until stopped). With the CPU held, it is given up while waiting. */
    private void holdWhilePaused(boolean holdingCpu) {
        waiting();
        if (!paused && !held) {
            return;
        }
        if (paused) {
            Location here = where();
            location = here;
            if (onPause != null && !stepping) {
                onPause.accept(here); // a step's pause is shown by the editor, not the terminal
            }
        }
        if (holdingCpu) {
            releaseCpu();
        }
        try {
            while ((paused || held) && stop == StopMode.NONE) {
                parkFor(WAIT_NANOS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (holdingCpu) {
                acquireCpu();
            }
        }
    }

    /** A program is starting: no Ctrl+C has reached it yet. */
    public void beginRun() {
        stopsDelivered = 0;
    }

    /**
     * A program has ended: forget the calls it left unanswered (one stopped by Ctrl+C, or
     * one it never waited for), so they don't pile up across programs.
     */
    public void forgetCalls() {
        tickets.keySet().retainAll(lasting);
        lasting.retainAll(tickets.keySet());
    }

    /** At a sleep or a wait: true if a Ctrl+C is waiting to be delivered (and now is). */
    private boolean takeStop() {
        switch (stop) {
            case POLITE:
                stop = StopMode.NONE;
                stopsDelivered++;
                return true;
            case HARD:
                throw new HardStop();
            default:
                return false;
        }
    }

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
        this.maxResultBytes = Math.min(1024 * 1024, Math.max(48 * 1024, heapBytes / 4));
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
        where = optionalExport(instance, "mpy_where");
        long rc = instance.export("mpy_init").apply(heapBytes)[0];
        if (rc != 0) {
            throw new IllegalStateException("mpy_init failed: " + rc + " " + takeOutput());
        }
        whereBuf = (int) alloc.apply(WHERE_CAP)[0];
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
        // Stepping from the start (F8 with nothing running): end the first slice at once, so the
        // engine runs its traced loop before the program's first line, not after a whole slice.
        setBudget.apply(stepping ? 1 : sliceBudget);
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

    /**
     * After a sleep or a world call, if a step was asked for (or watching began or ended): end
     * the slice at once, so the engine is told at the very next chance rather than a slice later.
     */
    private void stepSoon() {
        if (stepping || watched != watchSent) {
            setBudget.apply(1);
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
            new HostFunction("host", "sleep_ms", FunctionType.of(List.of(I32), List.of(I32)),
                    (inst, args) -> {
                        sample();
                        releaseCpu();
                        try {
                            // Asleep until the time is up, or woken to stop or pause (wake()).
                            waiting();
                            long end = System.nanoTime() + args[0] * 1_000_000L;
                            for (long left; stop == StopMode.NONE && (left = end - System.nanoTime()) > 0; ) {
                                if (paused || held) {
                                    // Paused: the sleep stops counting down until it resumes.
                                    long since = System.nanoTime();
                                    holdWhilePaused(false);
                                    end += System.nanoTime() - since;
                                    continue;
                                }
                                parkFor(left);
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            acquireCpu();
                        }
                        stepSoon();
                        return new long[] {takeStop() ? 1 : 0};
                    }),
            new HostFunction("host", "slice", FunctionType.of(List.of(), List.of(I32)),
                    (inst, args) -> {
                        if (++slicesUsed > maxSlices) {
                            throw new HardStop();
                        }
                        switch (stop) {
                            case POLITE:
                                stop = StopMode.NONE;
                                stopsDelivered++;
                                return new long[] {0};
                            case HARD:
                                throw new HardStop();
                            default:
                                sample();
                                holdWhilePaused(true);
                                // End of slice: let any waiting computer have the core.
                                if (cpu != null && cpu.hasQueuedThreads()) {
                                    cpu.release();
                                    cpu.acquireUninterruptibly();
                                }
                                // Watched or stepping, the engine runs its traced loop (vm_split.c);
                                // otherwise its plain one, which costs nothing extra.
                                watchSent = watched;
                                return new long[] {sliceBudget | (stepping ? STEP_BIT : 0) | (watched ? WATCH_BIT : 0)};
                        }
                    }),
            new HostFunction("host", "submit", FunctionType.of(List.of(I32, I32), List.of(I32)),
                    (inst, args) -> {
                        String call = readString((int) args[0], (int) args[1]);
                        int ticket = nextTicket++;
                        String refused = lookOnly ? LookOnly.refusal(call) : dryRun ? DryRun.refusal(call) : null;
                        if (dryRun && refused == null) {
                            call = DryRun.rewrite(call);
                        }
                        // A program can submit without ever waiting: don't let calls pile up.
                        tickets.put(ticket, refused != null ? CompletableFuture.completedFuture(refused)
                                : tickets.size() >= MAX_WAITING_CALLS
                                ? CompletableFuture.completedFuture("error:too many calls are waiting for an answer")
                                : worldCalls.apply(call));
                        if (lastsAcrossRuns.test(call)) {
                            lasting.add(ticket);
                        }
                        return new long[] {ticket};
                    }),
            new HostFunction("host", "fs_call", FunctionType.of(List.of(I32, I32, I32, I32, I32), List.of(I32)),
                    (inst, args) -> {
                        String path = readString((int) args[1], (int) args[2]);
                        byte[] arg = instance.memory().readBytes((int) args[3], (int) args[4]);
                        if ((lookOnly || dryRun) && LookOnly.writes((int) args[0])) {
                            fsResult = new byte[0];
                            return new long[] {-HostFileSystem.EACCES}; // the look-only scratchpad doesn't change files
                        }
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
            new HostFunction("host", "line", FunctionType.of(List.of(I32), List.of(I32)),
                    (inst, args) -> {
                        // Stepping, and the program reached a new line of the player's code: hold it there.
                        if (stepping && stop == StopMode.NONE) {
                            paused = true;
                            holdWhilePaused(true);
                        }
                        if (stop == StopMode.HARD) {
                            throw new HardStop();
                        }
                        if (stop == StopMode.POLITE && stepping) {
                            // Ctrl+C while held on a step: the engine raises KeyboardInterrupt here, before
                            // the line runs (its usual check comes only at jumps: a short program would finish first).
                            stop = StopMode.NONE;
                            stopsDelivered++;
                            stepping = false;
                            paused = false;
                            return new long[] {-1};
                        }
                        return new long[] {stepping ? 1 : 0};
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
            // Waits until it is answered, the time is up, or it is woken to stop (Ctrl+C ends a long wait, such as one for a message).
            waiting();
            // Once for an answer, however often it is waited for: a program waiting on events comes back to
            // the same unanswered call each time one of its timers is due.
            if (mustBlock && hooked.add(future)) {
                future.whenComplete((answer, error) -> wake());
            }
            long end = timeoutMs < 0 ? Long.MAX_VALUE : System.nanoTime() + timeoutMs * 1_000_000L;
            while (true) {
                long left = end - System.nanoTime();
                if (future.isDone() || timeoutMs == 0) {
                    result = future.get(0, TimeUnit.MILLISECONDS);
                    break;
                }
                if (stop != StopMode.NONE) {
                    // Ctrl+C: the call is left unanswered (forgetCalls() clears it when the program ends).
                    return takeStop() ? -3 : -1;
                }
                if (left <= 0) {
                    return -1;
                }
                parkFor(left); // then look at the answer, the stop flag and the clock again
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
        holdWhilePaused(true); // paused while the world answered: hold here, with the answer kept
        stepSoon();
        byte[] bytes = result.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxResultBytes) {
            bytes = "error:the answer is too large for this computer".getBytes(StandardCharsets.UTF_8);
            result = "error:the answer is too large for this computer";
        }
        if (bytes.length > cap) {
            // Too big for the buffer offered: the answer is kept, and the caller asks again with a bigger one.
            tickets.put(ticket, CompletableFuture.completedFuture(result));
            return bytes.length;
        }
        tickets.remove(ticket);
        lasting.remove(ticket);
        instance.memory().write(bufPtr, bytes);
        return bytes.length;
    }
}
