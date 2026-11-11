package qbotics.ide;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Suggests how to finish the word being typed in a Python file. It works from the text
 * alone (names used in the file, Python's own words, the members of the modules this mod
 * provides), so it is quick and predictable, though it does not understand types.
 */
public final class Completer {
    private static final int MAX_ITEMS = 40;

    /**
     * @param start  the column where the word being completed begins
     * @param prefix what has been typed of it so far
     * @param items  suggestions, best first; never empty
     */
    public record Result(int start, String prefix, List<String> items) {
    }

    private static final List<String> KEYWORDS = List.of(
            "False", "None", "True", "and", "as", "assert", "async", "await", "break", "class", "continue",
            "def", "del", "elif", "else", "except", "finally", "for", "from", "global", "if", "import", "in",
            "is", "lambda", "nonlocal", "not", "or", "pass", "raise", "return", "try", "while", "with", "yield");
    private static final List<String> BUILTINS = List.of(
            "abs", "all", "any", "bin", "bool", "bytearray", "bytes", "chr", "dict", "divmod", "enumerate",
            "execfile", "filter", "float", "getattr", "hasattr", "hex", "id", "input", "int", "isinstance",
            "len", "list", "map", "max", "min", "object", "open", "ord", "pow", "print", "range", "repr",
            "reversed", "round", "set", "setattr", "sorted", "str", "sum", "super", "tuple", "type", "zip",
            "Exception", "IndexError", "KeyError", "OSError", "RuntimeError", "StopIteration", "TypeError", "ValueError");
    /** Modules a computer has, and what is in them. */
    private static final Map<String, List<String>> MODULES = Map.of(
            "logistics", List.of("Face", "NetworkError", "Slot", "network"),
            "network", List.of("Radio", "hostname", "ifconfig", "receive", "scan", "send"),
            "time", List.of("sleep", "sleep_ms", "ticks_diff", "ticks_ms", "ticks_us", "time"),
            "os", List.of("chdir", "getcwd", "listdir", "mkdir", "remove", "rename", "rmdir", "stat"),
            "math", List.of("atan2", "ceil", "cos", "degrees", "e", "fabs", "floor", "log", "pi", "pow", "radians", "sin", "sqrt", "tan"),
            "json", List.of("dump", "dumps", "load", "loads"),
            "sys", List.of("exit", "modules", "path", "platform", "print_exception", "version"),
            "random", List.of("choice", "getrandbits", "randint", "random", "randrange", "seed", "uniform"),
            "gc", List.of("collect", "mem_alloc", "mem_free"));
    /** Methods common enough on strings, lists, dicts and files to offer after any dot. */
    private static final List<String> COMMON_METHODS = List.of(
            "append", "close", "count", "endswith", "extend", "find", "format", "get", "index", "insert",
            "items", "join", "keys", "lower", "pop", "read", "readline", "remove", "replace", "sort", "split",
            "startswith", "strip", "update", "upper", "values", "write");

    private Completer() {
    }

    /**
     * Suggestions for the word ending at the cursor, or null if there is nothing to offer.
     *
     * @param asked true if the player pressed Ctrl+Space; otherwise suggestions wait until two
     *              letters have been typed (or a dot), so they don't pop up constantly
     */
    public static Result complete(TextBuffer buffer, boolean asked) {
        if (buffer.hasSelection()) {
            return null;
        }
        String text = buffer.line(buffer.cursorLine());
        int column = buffer.cursorColumn();
        int start = column;
        while (start > 0 && isNameChar(text.charAt(start - 1))) {
            start--;
        }
        String prefix = text.substring(start, column);
        if (!prefix.isEmpty() && Character.isDigit(prefix.charAt(0))) {
            return null; // a number, not a name
        }
        if (insideStringOrComment(buffer, start)) {
            return null;
        }

        Set<String> candidates = new LinkedHashSet<>();
        boolean afterDot = start > 0 && text.charAt(start - 1) == '.';
        if (afterDot) {
            int ownerEnd = start - 1;
            int ownerStart = ownerEnd;
            while (ownerStart > 0 && isNameChar(text.charAt(ownerStart - 1))) {
                ownerStart--;
            }
            String owner = text.substring(ownerStart, ownerEnd);
            if (owner.isEmpty()) {
                return null;
            }
            List<String> members = MODULES.get(importedModules(buffer).getOrDefault(owner, ""));
            if (members != null) {
                candidates.addAll(members);
            } else {
                // Names this file itself uses after "owner." come first, then other attribute names.
                candidates.addAll(namesAfter(buffer, owner + "."));
                candidates.addAll(namesAfter(buffer, "."));
                candidates.addAll(namesAfter(buffer, "def ")); // methods the file defines
                candidates.addAll(COMMON_METHODS);
            }
        } else {
            if (prefix.length() < (asked ? 0 : 2)) {
                return null;
            }
            candidates.addAll(names(buffer));
            candidates.addAll(new TreeSet<>(importedModules(buffer).keySet()));
            candidates.addAll(BUILTINS);
            candidates.addAll(KEYWORDS);
            candidates.addAll(new TreeSet<>(MODULES.keySet()));
        }

        // Exact-case matches first, then matches that differ only in capitals.
        List<String> items = new ArrayList<>();
        String lower = prefix.toLowerCase(Locale.ROOT);
        for (String candidate : candidates) {
            if (candidate.startsWith(prefix) && !candidate.equals(prefix)) {
                items.add(candidate);
            }
        }
        for (String candidate : candidates) {
            if (!candidate.startsWith(prefix) && candidate.toLowerCase(Locale.ROOT).startsWith(lower)) {
                items.add(candidate);
            }
        }
        if (items.isEmpty()) {
            return null;
        }
        return new Result(start, prefix, items.size() > MAX_ITEMS ? items.subList(0, MAX_ITEMS) : items);
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static boolean insideStringOrComment(TextBuffer buffer, int column) {
        int state = Highlighter.NORMAL;
        for (int i = 0; i < buffer.cursorLine(); i++) {
            state = Highlighter.highlight(Highlighter.Language.PYTHON, buffer.line(i), state).endState();
        }
        if (state != Highlighter.NORMAL) {
            return true; // inside a triple-quoted string that began on an earlier line
        }
        for (Highlighter.Token token : Highlighter.highlight(Highlighter.Language.PYTHON, buffer.line(buffer.cursorLine()), state).tokens()) {
            boolean text = token.kind() == Highlighter.Kind.STRING || token.kind() == Highlighter.Kind.COMMENT;
            if (text && column > token.start() && column <= token.end()) {
                return true;
            }
        }
        return false;
    }

    /** Every name in the file's code (not its strings or comments), alphabetically. */
    private static Set<String> names(TextBuffer buffer) {
        Set<String> found = new TreeSet<>();
        int state = Highlighter.NORMAL;
        for (int i = 0; i < buffer.lineCount(); i++) {
            String text = buffer.line(i);
            Highlighter.Line line = Highlighter.highlight(Highlighter.Language.PYTHON, text, state);
            state = line.endState();
            int at = 0;
            while (at < text.length()) {
                Highlighter.Token skip = null;
                for (Highlighter.Token token : line.tokens()) {
                    boolean isText = token.kind() == Highlighter.Kind.STRING || token.kind() == Highlighter.Kind.COMMENT;
                    if (isText && at >= token.start() && at < token.end()) {
                        skip = token;
                    }
                }
                if (skip != null) {
                    at = skip.end();
                } else if (Character.isLetter(text.charAt(at)) || text.charAt(at) == '_') {
                    int end = at;
                    while (end < text.length() && isNameChar(text.charAt(end))) {
                        end++;
                    }
                    // The word under the cursor is the one being typed: not a suggestion yet.
                    boolean beingTyped = i == buffer.cursorLine() && end == buffer.cursorColumn();
                    if (end - at > 1 && !beingTyped) {
                        found.add(text.substring(at, end));
                    }
                    at = end;
                } else {
                    at++;
                }
            }
        }
        return found;
    }

    /** Names that appear in the file directly after some text, such as "self." or just ".". */
    private static Set<String> namesAfter(TextBuffer buffer, String before) {
        Set<String> found = new TreeSet<>();
        for (int i = 0; i < buffer.lineCount(); i++) {
            String text = buffer.line(i);
            for (int at = text.indexOf(before); at >= 0; at = text.indexOf(before, at + 1)) {
                int start = at + before.length();
                int end = start;
                while (end < text.length() && isNameChar(text.charAt(end))) {
                    end++;
                }
                boolean wholeOwner = before.equals(".") || at == 0 || !isNameChar(text.charAt(at - 1));
                if (end < text.length() && before.equals("def ") && text.startsWith("__", start)) {
                    continue; // __init__ and friends are not called by name
                }
                boolean beingTyped = i == buffer.cursorLine() && end == buffer.cursorColumn();
                if (end > start && wholeOwner && !beingTyped && !Character.isDigit(text.charAt(start))) {
                    found.add(text.substring(start, end));
                }
            }
        }
        return found;
    }

    /** The name used in this file -> the module it stands for ("import time", "import random as rnd"). */
    static Map<String, String> importedModules(TextBuffer buffer) {
        Map<String, String> modules = new java.util.HashMap<>();
        for (int i = 0; i < buffer.lineCount(); i++) {
            String text = buffer.line(i).strip();
            if (!text.startsWith("import ")) {
                continue;
            }
            for (String part : text.substring(7).split(",")) {
                String[] words = part.strip().split("\\s+");
                if (words.length == 1 && !words[0].isEmpty()) {
                    modules.put(words[0], words[0]);
                } else if (words.length == 3 && words[1].equals("as")) {
                    modules.put(words[2], words[0]);
                }
            }
        }
        return modules;
    }
}
