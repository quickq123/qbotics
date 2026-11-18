package qbotics.devkit;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import qbotics.devkit.Tokens.Token;
import qbotics.devkit.Tokens.Type;
import qbotics.engine.Computer;
import qbotics.engine.Engine;
import qbotics.engine.SyntaxCheck;
import qbotics.ide.Checker;
import qbotics.ide.Docs;

/**
 * The devkit's check command: will a Qbotics program compile, and what does it need to run?
 *
 * <pre>
 *     check mine.py            one file
 *     check .                  every .py file in a folder and below
 * </pre>
 *
 * Each file gets the in-game editor's own checker, then the computers' own Python compiler
 * (the same engine as in the game, inside this jar), then a check that what it uses from a
 * module is really there on a computer (time.ticks_ms is; time.monotonic isn't). Then it lists
 * what the program needs from the world: the devices it looks up, a Qbot, an antenna, files.
 *
 * <p>Every line is "file:line:column: kind: message", which VS Code (and most editors) turn
 * into a link, and which the kit's VS Code task reads into the Problems panel.
 */
public final class Check {
    /** The computers' own modules, frozen into the engine. */
    private static final List<String> ROM = List.of("logistics", "network", "events", "pip", "display", "qbot", "requests", "git", "game", "env");
    /** MicroPython's modules that might be built in; the engine says which are. */
    private static final List<String> MAYBE_BUILT_IN = List.of(
            "array", "binascii", "builtins", "cmath", "collections", "deflate", "errno", "gc", "hashlib", "heapq", "io", "json",
            "math", "micropython", "os", "platform", "random", "re", "select", "socket", "struct", "sys", "time", "uctypes",
            "vfs", "_thread", "asyncio", "machine");

    PrintStream out;
    private final Map<String, Set<String>> moduleNames;
    private final Computer python;
    int errors;
    int warnings;

    Check(PrintStream out) {
        this.out = out;
        this.python = new Computer(Engine.bundled(), 2 * 1024 * 1024,
                call -> CompletableFuture.failedFuture(new IllegalStateException("no world here")));
        this.moduleNames = askModules();
    }

    public static void main(String[] args) throws IOException {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        boolean needs = true;
        List<Path> targets = new ArrayList<>();
        for (String arg : args) {
            switch (arg) {
                case "--no-needs" -> needs = false;
                case "-h", "--help" -> {
                    usage(out);
                    return;
                }
                default -> targets.add(Path.of(arg));
            }
        }
        if (targets.isEmpty()) {
            usage(out);
            System.exit(2);
        }
        List<Path> files = new ArrayList<>();
        for (Path target : targets) {
            if (Files.isDirectory(target)) {
                try (Stream<Path> walk = Files.walk(target)) {
                    walk.filter(p -> p.toString().endsWith(".py") && Files.isRegularFile(p) && !hidden(target, p)).sorted().forEach(files::add);
                }
            } else if (Files.isRegularFile(target)) {
                files.add(target);
            } else {
                out.println(target + ": there's no file or folder here.");
                System.exit(2);
            }
        }
        Check check = new Check(out);
        check.run(files, needs);
        System.exit(check.errors > 0 ? 1 : 0);
    }

    private static void usage(PrintStream out) {
        out.println("""
                Checks Qbotics programs without the game: will they compile, and what do they need to run?

                  check FILE.py         check one program (and the files of its own it imports)
                  check FOLDER          check every .py file in a folder and the folders inside it
                  --no-needs            only mistakes: don't list what the programs need

                Each line is file:line:column: then error, warning or needs, and what it is.""");
    }

    /** A file in a folder starting with "." (the kit's own .qbotics, say) under the target. */
    private static boolean hidden(Path root, Path file) {
        for (Path part : root.relativize(file)) {
            if (part.toString().startsWith(".")) {
                return true;
            }
        }
        return false;
    }

    void reset() {
        errors = 0;
        warnings = 0;
    }

    void run(List<Path> files, boolean listNeeds) throws IOException {
        // Read every file, and the files of the program's own they import, beside them or in lib/.
        Map<Path, List<String>> sources = new LinkedHashMap<>();
        Map<Path, List<Token>> tokens = new HashMap<>();
        Set<Path> checked = new LinkedHashSet<>();
        for (Path file : files) {
            checked.add(file.toAbsolutePath().normalize());
        }
        Set<String> known = new HashSet<>(moduleNames.keySet());
        known.addAll(ROM);
        List<Path> queue = new ArrayList<>(checked);
        Map<Path, List<Needs.LocalImport>> locals = new HashMap<>();
        Map<String, String> receivers = new HashMap<>();
        for (int i = 0; i < queue.size(); i++) {
            Path file = queue.get(i);
            List<String> lines = read(file);
            sources.put(file, lines);
            List<Token> words = Tokens.read(lines);
            tokens.put(file, words);
            Needs.receivers(words, receivers);
            List<Needs.LocalImport> imports = new ArrayList<>();
            Needs.of(words, Map.of(), known, imports);
            locals.put(file, imports);
            for (Needs.LocalImport local : imports) {
                Path found = findModule(file, local.name());
                if (found != null && !queue.contains(found)) {
                    queue.add(found);
                }
            }
        }

        int clean = 0;
        int listed = 0;
        // Each program's needs once: a file checked, with the files of its own it imports.
        Map<Path, Set<String>> told = new HashMap<>();
        for (Path file : queue) {
            List<String> lines = sources.get(file);
            String shown = shown(file);
            List<Checker.Problem> problems = problems(file, lines, tokens.get(file));
            boolean ours = checked.contains(file);
            if (ours || !problems.isEmpty()) {
                for (Checker.Problem p : problems) {
                    boolean error = p.severity() == Checker.Severity.ERROR;
                    if (error) {
                        errors++;
                    } else {
                        warnings++;
                    }
                    out.println(shown + ":" + (p.line() + 1) + ":" + (p.start() + 1) + ": " + (error ? "error" : "warning") + ": " + p.message());
                }
                if (problems.isEmpty()) {
                    clean++;
                }
            }
            if (!listNeeds) {
                continue;
            }
            List<Needs.Need> needs = Needs.of(tokens.get(file), receivers, known, new ArrayList<>());
            for (Needs.LocalImport local : locals.get(file)) {
                if (findModule(file, local.name()) == null) {
                    needs.add(new Needs.Need(local.line(), local.col(), "the module \"" + local.name()
                            + "\" on its drive (" + local.name() + ".py beside it, or in /lib); it isn't one of the computers' own, and isn't here"));
                }
            }
            needs.sort((a, b) -> a.line() != b.line() ? Integer.compare(a.line(), b.line()) : Integer.compare(a.col(), b.col()));
            List<Path> programs = new ArrayList<>();
            for (Path program : checked) {
                if (program.equals(file) || imports(program, file, locals, new HashSet<>())) {
                    programs.add(program);
                }
            }
            for (Needs.Need need : needs) {
                // Said once for each program it's part of; shown if some program hasn't heard it yet.
                boolean fresh = false;
                for (Path program : programs) {
                    fresh |= told.computeIfAbsent(program, p -> new HashSet<>()).add(need.what());
                }
                if (fresh) {
                    listed++;
                    out.println(shown + ":" + (need.line() + 1) + ":" + (need.col() + 1) + ": needs: " + need.what());
                }
            }
        }
        out.println();
        if (errors == 0 && warnings == 0) {
            out.println(checked.size() == 1 ? "No mistakes found." : "No mistakes found in " + checked.size() + " files.");
        } else {
            out.println(count(errors, "error") + " and " + count(warnings, "warning")
                    + (checked.size() == 1 ? "." : " in " + count(checked.size(), "file") + " (" + clean + " with none)."));
        }
        if (listNeeds && listed > 0) {
            out.println("To run, " + (checked.size() == 1 ? "it needs" : "they need") + " what the \"needs\" lines say: "
                    + count(listed, "thing") + ".");
        }
    }

    /** The editor's checker; if that finds no error, the engine's compiler; then what modules really have. */
    private List<Checker.Problem> problems(Path file, List<String> lines, List<Token> words) {
        List<Checker.Problem> problems = new ArrayList<>(Checker.check(lines, Docs.moduleExports()));
        if (problems.stream().noneMatch(p -> p.severity() == Checker.Severity.ERROR)) {
            String answer = SyntaxCheck.check(python, "/" + file.getFileName(), String.join("\n", lines));
            int tab = answer == null ? -1 : answer.indexOf('\t');
            if (tab > 0) {
                int line = Math.clamp(Integer.parseInt(answer.substring(0, tab)) - 1, 0, Math.max(0, lines.size() - 1));
                String text = lines.isEmpty() ? "" : lines.get(line);
                int start = text.length() - text.stripLeading().length();
                problems.add(new Checker.Problem(line, start, Math.max(start + 1, text.stripTrailing().length()), Checker.Severity.ERROR,
                        "Python can't read this line: " + answer.substring(tab + 1) + "."));
            }
        }
        problems.addAll(missingNames(words, problems));
        problems.sort((a, b) -> a.line() != b.line() ? Integer.compare(a.line(), b.line()) : Integer.compare(a.start(), b.start()));
        return problems;
    }

    /**
     * module.name where the computers' module has no such name (time.monotonic), and
     * from module import name for MicroPython's own modules (the editor checks the mod's).
     */
    private List<Checker.Problem> missingNames(List<Token> t, List<Checker.Problem> already) {
        Map<String, String> aliases = new HashMap<>();
        Set<String> reused = new HashSet<>();
        List<Checker.Problem> found = new ArrayList<>();
        for (int i = 0; i < t.size(); i++) {
            Token token = t.get(i);
            boolean lineStart = i == 0 || t.get(i - 1).type() == Type.NEWLINE;
            if (lineStart && token.is("import")) {
                for (int j = i + 1; j < t.size() && t.get(j).type() != Type.NEWLINE; j++) {
                    if (t.get(j).type() == Type.NAME && (t.get(j - 1).is("import") || t.get(j - 1).is(","))
                            && moduleNames.containsKey(t.get(j).text()) && !(j + 1 < t.size() && t.get(j + 1).is("."))) {
                        String module = t.get(j).text();
                        boolean as = j + 2 < t.size() && t.get(j + 1).is("as") && t.get(j + 2).type() == Type.NAME;
                        aliases.put(as ? t.get(j + 2).text() : module, module);
                    }
                }
            } else if (lineStart && token.is("from") && i + 2 < t.size() && t.get(i + 2).is("import")
                    && moduleNames.containsKey(t.get(i + 1).text()) && !ROM.contains(t.get(i + 1).text())) {
                String module = t.get(i + 1).text();
                for (int j = i + 3; j < t.size() && t.get(j).type() != Type.NEWLINE; j++) {
                    Token name = t.get(j);
                    if (name.type() == Type.NAME && (t.get(j - 1).is("import") || t.get(j - 1).is(",") || t.get(j - 1).is("("))
                            && !name.is("*") && !moduleNames.get(module).contains(name.text())) {
                        found.add(missing(module, name));
                    }
                }
            }
            // A name the program gives a value of its own isn't the module any more.
            if (token.type() == Type.NAME && i + 1 < t.size() && (t.get(i + 1).is("=")
                    || (i > 0 && (t.get(i - 1).is("def") || t.get(i - 1).is("class") || t.get(i - 1).is("for")
                    || (t.get(i - 1).is("as") && !importLine(t, i)))))) {
                reused.add(token.text());
            }
        }
        for (int i = 0; i + 2 < t.size(); i++) {
            Token module = t.get(i);
            Token name = t.get(i + 2);
            String real = aliases.get(module.text());
            if (real == null || reused.contains(module.text()) || module.type() != Type.NAME || !t.get(i + 1).is(".")
                    || name.type() != Type.NAME || (i > 0 && t.get(i - 1).is("."))) {
                continue;
            }
            if (!moduleNames.get(real).contains(name.text()) && !(i + 3 < t.size() && t.get(i + 3).is("="))) {
                boolean seen = already.stream().anyMatch(p -> p.line() == name.line() && p.start() <= name.col() && name.col() < p.end());
                if (!seen) {
                    found.add(missing(real, name));
                }
            }
        }
        return found;
    }

    /** Whether one file imports another, itself or through others of the program's. */
    private static boolean imports(Path from, Path target, Map<Path, List<Needs.LocalImport>> locals, Set<Path> seen) {
        if (!seen.add(from)) {
            return false;
        }
        for (Needs.LocalImport local : locals.getOrDefault(from, List.of())) {
            Path found = findModule(from, local.name());
            if (found != null && (found.equals(target) || imports(found, target, locals, seen))) {
                return true;
            }
        }
        return false;
    }

    private static boolean importLine(List<Token> t, int at) {
        for (int i = at; i >= 0 && t.get(i).type() != Type.NEWLINE; i--) {
            if (t.get(i).is("import")) {
                return true;
            }
        }
        return false;
    }

    private Checker.Problem missing(String module, Token name) {
        List<String> has = moduleNames.get(module).stream().filter(n -> !n.startsWith("_")).sorted().toList();
        String like = Checker.closest(name.text(), has);
        String message = module + " has no " + name.text() + ".";
        if (like != null) {
            message += " Did you mean " + like + "?";
        } else if (has.size() <= 16) {
            message += " It has: " + String.join(", ", has) + ".";
        }
        if (!ROM.contains(module)) {
            message += " (The computers' Python is MicroPython, whose " + module + " has less than the one on a PC.)";
        }
        return new Checker.Problem(name.line(), name.col(), name.col() + name.text().length(), Checker.Severity.ERROR, message);
    }

    /** What each module on a computer has, asked of the engine itself. */
    private Map<String, Set<String>> askModules() {
        List<String> all = new ArrayList<>(ROM);
        all.addAll(MAYBE_BUILT_IN);
        StringBuilder names = new StringBuilder();
        for (String module : all) {
            names.append(names.isEmpty() ? "" : ", ").append('\'').append(module).append('\'');
        }
        python.takeOutput();
        python.exec(String.join("\n",
                "import json",
                "_found = {}",
                "for _m in [" + names + "]:",
                "    try:",
                "        _found[_m] = dir(__import__(_m))",
                "    except Exception:",
                "        pass",
                "print('MODULES' + json.dumps(_found))",
                ""));
        String output = python.takeOutput();
        Map<String, Set<String>> modules = new HashMap<>();
        int at = output.indexOf("MODULES");
        if (at >= 0) {
            JsonObject found = JsonParser.parseString(output.substring(at + 7).strip()).getAsJsonObject();
            for (String module : found.keySet()) {
                Set<String> members = new HashSet<>();
                found.getAsJsonArray(module).forEach(e -> members.add(e.getAsString()));
                modules.put(module, members);
            }
        }
        // A mod module that does something as it's imported, here with no world: its names from its source.
        for (Map.Entry<String, Set<String>> rom : Docs.moduleExports().entrySet()) {
            modules.computeIfAbsent(rom.getKey(), k -> new HashSet<>(rom.getValue()));
        }
        return modules;
    }

    /** A module of the program's own: name.py or name/__init__.py beside the file, or in a lib/ folder above it. */
    private static Path findModule(Path file, String name) {
        Path beside = file.toAbsolutePath().getParent();
        for (Path folder = beside; folder != null; folder = folder.getParent()) {
            // Beside the file, or in a lib/ folder there or above it.
            for (Path base : folder.equals(beside) ? List.of(folder, folder.resolve("lib")) : List.of(folder.resolve("lib"))) {
                Path single = base.resolve(name + ".py");
                if (Files.isRegularFile(single)) {
                    return single.toAbsolutePath().normalize();
                }
                Path pkg = base.resolve(name).resolve("__init__.py");
                if (Files.isRegularFile(pkg)) {
                    return pkg.toAbsolutePath().normalize();
                }
            }
        }
        return null;
    }

    private static List<String> read(Path file) throws IOException {
        return List.of(Files.readString(file, StandardCharsets.UTF_8).replace("\r", "").split("\n", -1));
    }

    private static String shown(Path file) {
        Path here = Path.of("").toAbsolutePath();
        return file.startsWith(here) ? here.relativize(file).toString().replace('\\', '/') : file.toString();
    }

    private static String count(int n, String word) {
        return n + " " + word + (n == 1 ? "" : "s");
    }
}
