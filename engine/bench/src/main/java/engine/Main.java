package engine;

import java.nio.file.Files;
import qbotics.engine.Computer;
import qbotics.engine.Engine;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/** Milestone 0: functional checks and the benchmark harness (design doc §3.3, §16). */
public final class Main {
    static final int KB = 1024;
    static final int BASIC = 256 * KB;
    static final int ADVANCED = 1024 * KB;
    static final int SERVER = 2048 * KB;

    static int failures;

    /** Completes a world call instantly: measures pure overhead. */
    static final Function<String, CompletableFuture<String>> INSTANT =
            call -> CompletableFuture.completedFuture("ok:" + call);

    /** Stand-in for the server's main thread: completes queued world calls once per 50 ms tick. */
    static final class TickLoop implements Function<String, CompletableFuture<String>>, AutoCloseable {
        private record Pending(String call, CompletableFuture<String> future) {}

        private final Queue<Pending> queue = new ConcurrentLinkedQueue<>();
        private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        volatile int ticks;

        TickLoop() {
            timer.scheduleAtFixedRate(this::tick, 50, 50, TimeUnit.MILLISECONDS);
        }

        private void tick() {
            ticks++;
            for (Pending p; (p = queue.poll()) != null; ) {
                p.future.complete("ok:" + p.call);
            }
        }

        @Override
        public CompletableFuture<String> apply(String call) {
            var future = new CompletableFuture<String>();
            queue.add(new Pending(call, future));
            return future;
        }

        @Override
        public void close() {
            timer.shutdownNow();
        }
    }

    public static void main(String[] args) throws Exception {
        // Everything that runs Python needs the big computer-thread stack.
        Throwable[] error = {null};
        Thread t = Computer.newThread(() -> {
            try {
                run(args);
            } catch (Throwable e) {
                error[0] = e;
            }
        }, "bench-main");
        t.start();
        t.join();
        if (error[0] != null) {
            error[0].printStackTrace();
            System.exit(2);
        }
        System.out.println();
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    static void run(String[] args) throws Exception {
        Path wasm = Path.of(args.length > 0 ? args[0] : "../out/micropython.wasm");
        Path scripts = Path.of("scripts");
        boolean quick = args.length > 1 && args[1].equals("quick");

        section("Load");
        long t = System.nanoTime();
        Engine interp = Engine.interpreted(wasm);
        System.out.printf("parse module:              %7.0f ms%n", ms(t));
        t = System.nanoTime();
        Engine compiled = Engine.compiled(wasm);
        System.out.printf("parse + compile to JVM:    %7.0f ms  (one-time; build-time compilation removes it)%n", ms(t));

        if (args.length > 1 && args[1].equals("throughput")) {
            for (String name : List.of("loop", "fib", "objects")) {
                throughput(name, "compiled", compiled, Files.readString(scripts.resolve(name + ".py")), 3);
            }
            return;
        }

        if (args.length > 1 && args[1].equals("stackprobe")) {
            stackProbe(compiled);
            return;
        }

        if (args.length > 1 && args[1].equals("difftest")) {
            diffTest(Engine.compiled(Path.of(args[2])), compiled, Path.of(args[3]));
            return;
        }

        section("Functional checks (compiled)");
        functional(compiled);
        section("Functional checks (interpreter)");
        functional(interp);

        if (!quick) {
            section("Boot time per computer");
            bootTime("compiled", compiled, 300);
            bootTime("interpreter", interp, 100);

            section("Memory per computer (compiled)");
            memory(compiled, "Basic", BASIC, 300);
            memory(compiled, "Advanced", ADVANCED, 150);
            memory(compiled, "Server", SERVER, 100);

            section("Interpreter throughput");
            for (String name : List.of("loop", "fib", "objects")) {
                String src = Files.readString(scripts.resolve(name + ".py"));
                throughput(name, "compiled", compiled, src, 5);
                throughput(name, "interpreter", interp, src, 1);
            }

            section("Host calls");
            hostCalls(compiled);

            section("Concurrency: one thread per computer, 2 CPU permits");
            concurrency(compiled, 200, 10);
            cpuSharing(compiled, 8);
        }
    }

    // ---- functional checks -------------------------------------------------

    static void functional(Engine engine) throws Exception {
        Computer c = new Computer(engine, BASIC, INSTANT);

        expect("print", c, "print('hello', 1 + 1)", true, "hello 2\n");
        expect("f-string, big int, float", c, "print(f'{2**100} {1/4}')", true,
                "1267650600228229401496703205376 0.25\n");
        expect("caught exception", c,
                "try:\n    1 / 0\nexcept ZeroDivisionError as e:\n    print('caught', e)", true,
                "caught divide by zero\n");

        boolean ok = c.exec("def f():\n    raise ValueError('boom')\nf()");
        String out = c.takeOutput();
        check("uncaught exception gives a traceback",
                !ok && out.contains("Traceback") && out.contains("line 2") && out.contains("ValueError: boom"), out);

        c.exec("x = 41");
        expect("globals persist between runs", c, "print(x + 1)", true, "42\n");

        String recursion = "def f(n):\n    global d\n    d = n\n    return f(n + 1)\ntry:\n    f(0)\nexcept RuntimeError as e:\n    print(e, d > 300)";
        expect("deep recursion (300+ levels) is a Python error", c, recursion, true,
                "maximum recursion depth exceeded True\n");

        // Live objects must survive many collections, including ones referenced
        // only from locals deep in the interpreter's own call stack.
        String gc = String.join("\n",
                "import gc",
                "keep = []",
                "for i in range(20000):",
                "    s = [i, str(i), {'k': i}]",
                "    if i % 100 == 0:",
                "        keep.append(s)",
                "    if len(keep) > 50:",
                "        keep.pop(0)",
                "assert all(x[1] == str(x[0]) and x[2]['k'] == x[0] for x in keep)",
                "def build(n):",
                "    if n == 0:",
                "        return []",
                "    a = [n] * 10",
                "    b = build(n - 1)",
                "    junk = [bytes(64) for _ in range(20)]",
                "    assert a == [n] * 10, a",
                "    return a + b[:10]",
                "for _ in range(200):",
                "    assert build(30)[:10] == [30] * 10",
                "print(sorted(str(i) for i in range(300))[:3], sum(x[0] for x in keep))");
        expect("garbage collector keeps live objects (256 KB heap)", c, gc, true,
                "['0', '1', '10'] " + sumKept() + "\n");

        ok = c.exec("x = []\nwhile True:\n    x.append(bytes(1000))");
        out = c.takeOutput();
        check("out of memory is a MemoryError", !ok && out.contains("MemoryError"), out);
        c.exec("x = None");
        expect("computer still works after MemoryError", c, "print(sum(range(10)))", true, "45\n");

        c.stop = Computer.StopMode.POLITE;
        ok = c.exec("while True:\n    pass");
        out = c.takeOutput();
        check("endless loop: polite stop", !ok && out.contains("program stopped by the computer"), out);
        expect("computer still works after polite stop", c, "print('alive')", true, "alive\n");

        Computer doomed = new Computer(engine, BASIC, INSTANT);
        doomed.stop = Computer.StopMode.HARD;
        boolean hardStopped = false;
        try {
            doomed.exec("while True:\n    try:\n        while True:\n            pass\n    except:\n        pass");
        } catch (Throwable e) {
            for (Throwable x = e; x != null; x = x.getCause()) {
                hardStopped |= x instanceof Computer.HardStop;
            }
        }
        check("endless loop that swallows exceptions: hard stop", hardStopped, "");

        try (TickLoop world = new TickLoop()) {
            Computer w = new Computer(engine, BASIC, world);
            String calls = String.join("\n",
                    "import _host",
                    "t = _host.submit('move.forward')",
                    "print(_host.wait(t, 0))",
                    "print(_host.wait(t))",
                    "a = _host.submit('inspect.front')",
                    "b = _host.submit('inspect.top')",
                    "print(_host.wait(b), _host.wait(a))");
            int before = world.ticks;
            expect("submit/wait: timeout, block, gather", w, calls, true,
                    "None\nok:move.forward\nok:inspect.top ok:inspect.front\n");
            int used = world.ticks - before;
            check("two sequential waits take two ticks (took " + used + ")", used >= 2 && used <= 3, "");
        }
    }

    /**
     * How much JVM stack does the deepest Python recursion need? MicroPython's own limit
     * (set by the Wasm C stack size) must trip before the JVM's does.
     */
    static void stackProbe(Engine engine) throws Exception {
        String plain = "def f(n):\n    global d\n    d = n\n    return f(n + 1)\n";
        String method = "class A:\n    def f(self, n):\n        global d\n        d = n\n        return self.f(n + 1)\nf = A().f\n";
        String viaBuiltin = "def f(n):\n    global d\n    d = n\n    return sorted([1, 2], key=lambda x: f(n + 1))\n";
        String tail = "d = 0\ntry:\n    f(0)\nexcept RuntimeError as e:\n    print('depth', d, e)";
        for (var shape : List.of(List.of("plain call", plain), List.of("method call", method), List.of("via sorted(key=)", viaBuiltin))) {
            System.out.println(shape.get(0) + ":");
            for (int kb : new int[] {1024, 2048, 4096, 8192}) {
                String[] result = {"?"};
                Thread t = new Thread(null, () -> result[0] = runProbe(engine, shape.get(1) + tail), "probe", kb * 1024L);
                t.start();
                t.join();
                System.out.printf("  platform thread, %4d KB stack: %s%n", kb, result[0]);
            }
            try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
                System.out.printf("  virtual thread:                 %s%n", exec.submit(() -> runProbe(engine, shape.get(1) + tail)).get());
            }
        }
    }

    static String runProbe(Engine engine, String source) {
        try {
            Computer c = new Computer(engine, BASIC, INSTANT);
            c.exec(source);
            return c.takeOutput().trim();
        } catch (Throwable e) {
            Throwable root = e;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            return "JVM " + root.getClass().getSimpleName();
        }
    }

    static long sumKept() {
        long sum = 0;
        for (int i = 15000; i <= 19900; i += 100) {
            sum += i;
        }
        return sum;
    }

    static Void expect(String name, Computer c, String source, boolean wantOk, String wantOut) {
        boolean ok = c.exec(source);
        String out = c.takeOutput();
        check(name, ok == wantOk && out.equals(wantOut), out);
        return null;
    }

    static synchronized void check(String name, boolean pass, String detail) {
        System.out.printf("  [%s] %s%n", pass ? "PASS" : "FAIL", name);
        if (!pass) {
            failures++;
            System.out.println("         got: " + detail.replace("\n", "\\n"));
        }
    }

    // ---- benchmarks ----------------------------------------------------------

    static void bootTime(String label, Engine engine, int n) {
        for (int i = 0; i < n / 3; i++) {
            new Computer(engine, BASIC, INSTANT).exec("pass");
        }
        long t = System.nanoTime();
        for (int i = 0; i < n; i++) {
            new Computer(engine, BASIC, INSTANT).exec("pass");
        }
        System.out.printf("%-12s instantiate + init + first script: %6.2f ms%n", label, ms(t) / n);
    }

    static void memory(Engine engine, String tier, int heap, int n) {
        long before = usedHeap();
        List<Computer> computers = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Computer c = new Computer(engine, heap, INSTANT);
            c.exec("data = [i for i in range(100)]");
            computers.add(c);
        }
        long after = usedHeap();
        System.out.printf("%-8s (%4d KB Python heap): %5d KB JVM heap each, %5d KB Wasm memory, idle Python heap use %d KB%n",
                tier, heap / KB, (after - before) / n / KB,
                computers.get(0).wasmMemoryBytes() / KB, computers.get(0).pythonHeapUsed() / KB);
    }

    static long usedHeap() {
        Runtime rt = Runtime.getRuntime();
        for (int i = 0; i < 3; i++) {
            System.gc();
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
            }
        }
        return rt.totalMemory() - rt.freeMemory();
    }

    static void throughput(String name, String label, Engine engine, String source, int runs) {
        double first = 0;
        double best = Double.MAX_VALUE;
        String out = "";
        for (int i = 0; i < runs; i++) {
            Computer c = new Computer(engine, SERVER, INSTANT);
            long t = System.nanoTime();
            c.exec(source);
            double took = ms(t);
            out = c.takeOutput().trim();
            if (i == 0) {
                first = took;
            }
            best = Math.min(best, took);
        }
        System.out.printf("%-8s %-12s first %8.0f ms, best %8.0f ms   (result %s)%n", name, label, first, best, out);
    }

    static void hostCalls(Engine engine) {
        int n = 300_000;
        Computer c = new Computer(engine, SERVER, INSTANT);
        String empty = "def run():\n    for i in range(" + n + "):\n        pass\nrun()";
        String ticks = "import time\ndef run():\n    f = time.ticks_us\n    for i in range(" + n + "):\n        f()\nrun()";
        String roundTrip = "import _host\ndef run():\n    s = _host.submit\n    w = _host.wait\n    for i in range(" + n
                + "):\n        w(s('inspect.front'))\nrun()";
        double base = 0;
        double direct = 0;
        double world = 0;
        for (int i = 0; i < 4; i++) {
            base = time(c, empty);
            direct = time(c, ticks);
            world = time(c, roundTrip);
        }
        System.out.printf("direct host call (time.ticks_us):       %,10.0f calls/s%n", n / ((direct - base) / 1000));
        System.out.printf("submit + wait round trip (no tick wait): %,9.0f calls/s%n", n / ((world - base) / 1000));
        System.out.println("  (real world calls are capped at 20/s per computer by the 50 ms tick, so this overhead is negligible)");
    }

    static double time(Computer c, String source) {
        long t = System.nanoTime();
        c.exec(source);
        return ms(t);
    }

    static void concurrency(Engine engine, int computers, int calls) throws Exception {
        String script = "import _host\nfor i in range(" + calls + "):\n    _host.wait(_host.submit('move.forward'))\nprint('done')";
        Semaphore cpu = new Semaphore(2, true);
        try (TickLoop world = new TickLoop()) {
            List<Computer> all = new ArrayList<>();
            for (int i = 0; i < computers; i++) {
                Computer c = new Computer(engine, BASIC, world);
                c.cpu = cpu;
                all.add(c);
            }
            long t = System.nanoTime();
            AtomicInteger okCount = new AtomicInteger();
            List<Thread> threads = new ArrayList<>();
            for (Computer c : all) {
                Thread th = Computer.newThread(() -> {
                    if (c.exec(script)) {
                        okCount.incrementAndGet();
                    }
                }, "computer");
                th.start();
                threads.add(th);
            }
            for (Thread th : threads) {
                th.join();
            }
            double took = ms(t);
            System.out.printf("%d computers x %d world calls each: %.0f ms wall (ideal %d ms; one-at-a-time would be %d ms)%n",
                    computers, calls, took, calls * 50, computers * calls * 50 / 2);
            check("all computers finished, and waiting did not hold a CPU permit",
                    okCount.get() == computers && took < calls * 50 * 3, okCount + " ok, " + took + " ms");
        }
    }

    /** Busy computers must not starve a computer that only makes world calls. */
    static void cpuSharing(Engine engine, int busy) throws Exception {
        String spin = "def run():\n    n = 0\n    for i in range(3000000):\n        n += i\nrun()";
        String polite = "import _host\nfor i in range(10):\n    _host.wait(_host.submit('move.forward'))";
        Semaphore cpu = new Semaphore(2, true);
        try (TickLoop world = new TickLoop()) {
            List<Thread> spinners = new ArrayList<>();
            long t0 = System.nanoTime();
            for (int i = 0; i < busy; i++) {
                Computer c = new Computer(engine, BASIC, world);
                c.cpu = cpu;
                Thread th = Computer.newThread(() -> c.exec(spin), "spinner");
                th.start();
                spinners.add(th);
            }
            Computer p = new Computer(engine, BASIC, world);
            p.cpu = cpu;
            long t = System.nanoTime();
            Thread pt = Computer.newThread(() -> p.exec(polite), "polite");
            pt.start();
            pt.join();
            double took = ms(t);
            for (Thread th : spinners) {
                th.join();
            }
            System.out.printf("10 world calls while %d computers spin on 2 CPU permits: %.0f ms (ideal 500 ms); spinners finished after %.0f ms%n",
                    busy, took, ms(t0));
            check("busy computers do not starve a waiting one", took < 1500, took + " ms");
        }
    }

    // ---- differential test against the unmodified interpreter ----------------

    /** Runs every MicroPython test on both engines; the outputs must be identical. */
    static void diffTest(Engine ref, Engine split, Path testsDir) throws Exception {
        List<Path> tests;
        try (var walk = Files.walk(testsDir)) {
            tests = walk.filter(f -> f.toString().endsWith(".py"))
                    // Prints the interpreter's own C stack usage, which legitimately differs.
                    .filter(f -> !f.endsWith(Path.of("micropython", "meminfo.py")))
                    .sorted().toList();
        }
        int same = 0;
        int sameButAbnormal = 0;
        List<String> different = new ArrayList<>();
        for (Path test : tests) {
            String source = Files.readString(test);
            String a = runTest(ref, source);
            String b = runTest(split, source);
            if (a.equals(b)) {
                same++;
                if (a.startsWith("JVM ")) {
                    sameButAbnormal++;
                }
            } else {
                different.add(testsDir.relativize(test) + "\n    ref:   " + clip(a) + "\n    split: " + clip(b));
            }
        }
        System.out.printf("%d tests: %d identical (%d of those ended in the same host-side stop), %d different%n",
                tests.size(), same, sameButAbnormal, different.size());
        different.forEach(System.out::println);
        check("split interpreter matches the reference on MicroPython's test suite", different.isEmpty(), "");
    }

    static String runTest(Engine engine, String source) {
        try {
            Computer c = new Computer(engine, 4 * 1024 * KB, INSTANT);
            c.maxSlices = 300;
            boolean ok = c.exec(source);
            return (ok ? "ok\n" : "exception\n") + c.takeOutput();
        } catch (Throwable e) {
            Throwable root = e;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            return "JVM " + root.getClass().getSimpleName();
        }
    }

    static String clip(String s) {
        s = s.replace("\n", "\\n");
        return s.length() > 400 ? "..." + s.substring(s.length() - 400) : s;
    }

    static void section(String title) {
        System.out.println();
        System.out.println("== " + title + " ==");
    }

    static double ms(long startNanos) {
        return (System.nanoTime() - startNanos) / 1e6;
    }
}
