package engine;

import java.nio.file.Files;
import qbotics.engine.Computer;
import qbotics.engine.DriveFileSystem;
import qbotics.ide.Completer;
import qbotics.ide.Finder;
import qbotics.ide.Highlighter;
import qbotics.ide.JsonTools;
import qbotics.ide.Paths;
import qbotics.ide.TextBuffer;
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

        if (args.length > 1 && args[1].equals("exec")) {
            // Run one script and print what it printed: a quick way to try something on the engine.
            Computer c = new Computer(compiled, SERVER, INSTANT);
            // "\n" typed literally on the command line stands for a line break.
            boolean ok = c.exec(args[2].replace("\\n", "\n"));
            System.out.println(ok ? "[ok]" : "[exception]");
            System.out.println(c.takeOutput());
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
        section("File system (compiled)");
        fileSystem(compiled);
        section("Editor logic");
        editorLogic();

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
        check("endless loop: polite stop", !ok && out.contains("KeyboardInterrupt"), out);
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

    /** A computer's drive: ordinary Python file use, and the limits the host enforces. */
    static void fileSystem(Engine engine) throws Exception {
        Path drive = Files.createTempDirectory("qbotics-drive-test").resolve("drive");
        Path outside = drive.resolveSibling("outside.txt");
        Computer c = new Computer(engine, BASIC, INSTANT, new DriveFileSystem(drive, 64 * KB));

        expect("write, read back, list", c, String.join("\n",
                "import os",
                "with open('notes.txt', 'w') as f:",
                "    f.write('line one\\n')",
                "    print('line two', file=f)",
                "print(open('notes.txt').read(), end='')",
                "print(os.listdir('/'), os.stat('notes.txt')[6])"), true,
                "line one\nline two\n['notes.txt'] 18\n");
        check("the file is a real file on the server", Files.readString(drive.resolve("notes.txt")).equals("line one\nline two\n"), "");

        expect("a file that is never closed still has its data", c,
                "open('unclosed.txt', 'w').write('kept')\nprint(open('unclosed.txt').read())", true, "kept\n");
        expect("append, readline, iterate", c, String.join("\n",
                "with open('notes.txt', 'a') as f:",
                "    f.write('line three\\n')",
                "f = open('notes.txt')",
                "print(repr(f.readline()), [line.strip() for line in f])"), true,
                "'line one\\n' ['line two', 'line three']\n");
        expect("binary files and read-write mode", c, String.join("\n",
                "open('data.bin', 'wb').write(bytes(range(8)))",
                "with open('data.bin', 'r+b') as f:",
                "    f.seek(2)",
                "    f.write(b'\\xff\\xfe')",
                "print(open('data.bin', 'rb').read())"), true,
                "b'\\x00\\x01\\xff\\xfe\\x04\\x05\\x06\\x07'\n");
        expect("folders, rename, remove, chdir", c, String.join("\n",
                "os.mkdir('lib')",
                "os.rename('notes.txt', 'lib/notes.txt')",
                "os.chdir('lib')",
                "print(os.getcwd(), os.listdir(), open('notes.txt').readline().strip())",
                "os.chdir('/')",
                "os.remove('lib/notes.txt')",
                "os.rmdir('lib')",
                "print(sorted(os.listdir()))"), true,
                "/lib ['notes.txt'] line one\n['data.bin', 'unclosed.txt']\n");
        expect("import a module from the drive, with a real traceback", c, String.join("\n",
                "open('tools.py', 'w').write('def double(x):\\n    return x * 2\\n\\ndef broken():\\n    return 1 / 0\\n')",
                "import tools",
                "print(tools.double(21))",
                "try:",
                "    tools.broken()",
                "except ZeroDivisionError as e:",
                "    import sys",
                "    sys.print_exception(e)"), true,
                "42\nTraceback (most recent call last):\n  File \"<stdin>\", line 5, in <module>\n"
                        + "  File \"tools.py\", line 5, in broken\nZeroDivisionError: divide by zero\n");
        expect("a missing file is the usual error", c,
                "try:\n    open('nope.txt')\nexcept OSError as e:\n    print(e.errno, e)", true, "2 [Errno 2] ENOENT\n");

        // Limits enforced by the host.
        expect("a path can't climb out of the drive", c, String.join("\n",
                "open('/../../outside.txt', 'w').write('contained')",
                "print('outside.txt' in os.listdir('/'))"), true, "True\n");
        check("nothing was written outside the drive", !Files.exists(outside) && Files.exists(drive.resolve("outside.txt")), "");
        expect("awkward and reserved names are refused", c, String.join("\n",
                "for name in ('a:b', 'a\\\\b', 'con.txt', 'trailing.', '.~temp', 'x' * 65, 'tab\\tname'):",
                "    try:",
                "        open(name, 'w')",
                "        print('ALLOWED', name)",
                "    except OSError as e:",
                "        print(e.errno, end=' ')",
                "print()"), true, "22 22 22 22 22 22 22 \n");
        expect("the drive can't grow past its quota", c, String.join("\n",
                "f = open('big.bin', 'wb')",
                "try:",
                "    for i in range(100):",
                "        f.write(bytes(1024))",
                "except OSError as e:",
                "    print(e.errno, os.stat('big.bin')[6] <= 64 * 1024, os.stat('big.bin')[6] > 60 * 1024)",
                "os.remove('big.bin')",
                "open('small.txt', 'w').write('room again')",
                "print(open('small.txt').read())"), true, "28 True True\nroom again\n");

        // input(): lines come from whoever is running the computer.
        java.util.ArrayDeque<String> typed = new java.util.ArrayDeque<>(List.of("Ada", "36"));
        c.lineReader = typed::pollFirst;
        expect("input() returns typed lines; Ctrl+C is KeyboardInterrupt", c, String.join("\n",
                "name = input('Name? ')",
                "age = int(input())",
                "print(name, age + 1)",
                "try:",
                "    input('more? ')",
                "except KeyboardInterrupt:",
                "    print('stopped')"), true, "Name? Ada 37\nmore? stopped\n");
        c.lineReader = null;
        expect("input() with nowhere to ask is an EOFError", c,
                "try:\n    input()\nexcept EOFError as e:\n    print('EOFError', e)", true, "EOFError input() needs the terminal\n");

        Computer none = new Computer(engine, BASIC, INSTANT);
        expect("a computer without a drive still runs", none,
                "try:\n    open('x', 'w')\nexcept OSError as e:\n    print(e.errno)", true, "19\n");
    }

    /** The editor's text handling, highlighting and JSON tools: plain logic, no screen needed. */
    static void editorLogic() {
        TextBuffer b = new TextBuffer("");
        b.type('a');
        b.type('b');
        b.type('c');
        boolean typed = b.text().equals("abc") && b.isDirty();
        b.undo();
        boolean undone = b.text().isEmpty();
        b.redo();
        check("typing is undone and redone as one step", typed && undone && b.text().equals("abc"), b.text());

        b = new TextBuffer("def f():");
        b.moveEnd(false);
        b.newLine();
        b.type('x');
        b.newLine();
        boolean indented = b.text().equals("def f():\n    x\n    ") && b.cursorColumn() == 4;
        b.backspace();
        check("Enter indents after a colon; Backspace removes one indent level",
                indented && b.cursorColumn() == 0 && b.text().equals("def f():\n    x\n"), b.text());

        b = new TextBuffer("hello world");
        b.setCursor(0, 0, false);
        b.setCursor(0, 5, true);
        String selected = b.selectedText();
        b.insert("bye");
        check("typing over a selection replaces it", selected.equals("hello") && b.text().equals("bye world"), b.text());

        b = new TextBuffer("XY");
        b.setCursor(0, 1, false);
        b.insert("a\r\nb\n\tc");
        check("pasting several lines, with tabs and Windows line endings",
                b.text().equals("Xa\nb\n    cY") && b.cursorLine() == 2 && b.cursorColumn() == 5, b.text());

        b = new TextBuffer("a\nb\nc");
        b.setCursor(0, 0, false);
        b.setCursor(1, 1, true);
        b.indent();
        boolean in = b.text().equals("    a\n    b\nc");
        b.outdent();
        check("Tab and Shift+Tab move the selected lines", in && b.text().equals("a\nb\nc"), b.text());

        b = new TextBuffer("x = 1\n  y = 2");
        b.selectAll();
        b.toggleComment("#");
        boolean commented = b.text().equals("# x = 1\n#   y = 2");
        b.toggleComment("#");
        check("Ctrl+/ comments and uncomments", commented && b.text().equals("x = 1\n  y = 2"), b.text());

        b = new TextBuffer("foo.bar(baz)");
        b.moveWordRight(false);
        int first = b.cursorColumn();
        b.moveWordRight(false);
        b.selectWordAt(0, 9);
        check("word movement and double-click selection",
                first == 3 && b.selectionStartColumn() == 8 && b.selectedText().equals("baz"), first + " " + b.selectedText());

        b = new TextBuffer("one");
        boolean cleanAtStart = !b.isDirty();
        b.type('!');
        long version = b.version();
        b.type('?');
        b.markSaved(version);
        boolean stillDirty = b.isDirty(); // typed again while the save was on its way
        b.markSaved();
        check("unsaved-changes tracking", cleanAtStart && stillDirty && !b.isDirty(), "");

        var py = Highlighter.Language.PYTHON;
        check("Python colours: keyword, function name, self, number, comment",
                kinds(py, "def go(self, n=3):  # hi", 0).equals("KEYWORD FUNCTION SELF NUMBER COMMENT"),
                kinds(py, "def go(self, n=3):  # hi", 0));
        check("a # inside a string is not a comment",
                kinds(py, "s = f\"a # b\" + 'c'", 0).equals("STRING STRING"), kinds(py, "s = f\"a # b\" + 'c'", 0));
        var open = Highlighter.highlight(py, "x = \"\"\"abc", 0);
        var close = Highlighter.highlight(py, "def\"\"\" + 1", open.endState());
        check("a triple-quoted string continues onto the next line",
                open.endState() != 0 && close.endState() == 0 && close.tokens().get(0).end() == 6
                        && close.tokens().get(1).kind() == Highlighter.Kind.NUMBER, close.tokens().toString());
        check("JSON colours: key, number, literal, mistake",
                kinds(Highlighter.Language.JSON, "\"k\": [1, true, nope]", 0).equals("KEY NUMBER LITERAL ERROR"),
                kinds(Highlighter.Language.JSON, "\"k\": [1, true, nope]", 0));

        var formatted = JsonTools.format("{\"b\":1,\"a\":[true,null,{\"c\":\"x\"}],\"e\":{}}", 2);
        String want = "{\n  \"b\": 1,\n  \"a\": [\n    true,\n    null,\n    {\n      \"c\": \"x\"\n    }\n  ],\n  \"e\": {}\n}\n";
        check("JSON formatting keeps key order", formatted.ok() && formatted.formatted().equals(want), String.valueOf(formatted.formatted()));
        var comma = JsonTools.check("{\n  \"a\": 1,\n}");
        var missing = JsonTools.check("[1 2]");
        var quote = JsonTools.check("{\"a\": \"oops}");
        check("JSON mistakes are located and explained",
                comma != null && comma.line() == 3 && comma.error().contains("comma")
                        && missing != null && missing.error().contains("Expected ,") && missing.column() == 4
                        && quote != null && quote.error().contains("closing")
                        && JsonTools.check("{\"ok\": [1.5e3, -2]}") == null,
                comma + " | " + missing + " | " + quote);

        check("paths",
                Paths.resolve("/lib", "../a/./b.py").equals("/a/b.py") && Paths.resolve("/", "..").equals("/")
                        && Paths.parent("/a").equals("/") && Paths.parent("/a/b").equals("/a") && Paths.name("/a/b.py").equals("b.py")
                        && Paths.isInside("/lib/x", "/lib") && !Paths.isInside("/library", "/lib") && Paths.join("/", "a").equals("/a"), "");

        b = new TextBuffer("Layer one\nlayer two, layer three\nnone here");
        var loose = Finder.find(b, "layer", false);
        var exact = Finder.find(b, "layer", true);
        check("find: every match, with and without matching capitals",
                loose.size() == 3 && exact.size() == 2 && exact.get(1).equals(new Finder.Match(1, 11, 16))
                        && Finder.firstAtOrAfter(loose, 1, 1) == 2 && Finder.firstAtOrAfter(loose, 2, 0) == 0
                        && Finder.find(b, "", false).isEmpty(), loose.toString());
        b.replaceMatches(loose, "level");
        boolean replaced = b.text().equals("level one\nlevel two, level three\nnone here");
        b.undo();
        check("replace all is one undoable step", replaced && b.text().startsWith("Layer one\nlayer two, layer"), b.text());

        String program = String.join("\n",
                "import time",
                "import random as rnd",
                "class Miner:",
                "    def __init__(self):",
                "        self.layer = 0",
                "        self.torches = 9",
                "    def dig_layer(self):",
                "        pass",
                "miner = Miner()",
                "# a comment mentioning mineshaft",
                "");
        check("autocomplete: names in the file, then Python's own",
                complete(program, "mi").startsWith("[miner") && complete(program, "pri").equals("[print]")
                        && complete(program, "Mi").startsWith("[Miner") && complete(program, "m").equals("nothing"), complete(program, "mi") + complete(program, "pri") + complete(program, "Mi"));
        check("autocomplete: after a dot",
                complete(program, "time.sl").equals("[sleep, sleep_ms]") && complete(program, "rnd.ch").equals("[choice]")
                        && complete(program, "self.").startsWith("[layer, torches") && complete(program, "miner.dig").equals("[dig_layer]"),
                complete(program, "self.") + " " + complete(program, "time.sl"));
        check("autocomplete stays out of strings, comments and numbers",
                complete(program, "x = 'mi").equals("nothing") && complete(program, "# mi").equals("nothing") && complete(program, "12").equals("nothing"),
                String.valueOf(complete(program, "x = 'mi")));
    }

    /** What autocomplete offers when this text is typed on a new last line of the program. */
    static String complete(String program, String typed) {
        TextBuffer buffer = new TextBuffer(program + typed);
        buffer.moveDocumentEnd(false);
        var result = Completer.complete(buffer, false);
        return result == null ? "nothing" : result.items().toString();
    }

    static String kinds(Highlighter.Language language, String line, int state) {
        StringBuilder out = new StringBuilder();
        for (var token : Highlighter.highlight(language, line, state).tokens()) {
            out.append(out.isEmpty() ? "" : " ").append(token.kind());
        }
        return out.toString();
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
