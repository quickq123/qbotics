package qbotics.computer;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A plain-language hint under a Python traceback, for players new to programming: what the
 * error usually means, and what to look at. Written under the error by the terminal
 * ({@link Watcher}), as "Hint: ...". Only the common mistakes have one; the game's own errors
 * (NetworkError and the like) already say what is wrong in words.
 */
public final class ErrorHints {
    private ErrorHints() {
    }

    /** Where the game's names come from, for a NameError: "move" is from logistics. */
    private static final Map<String, String> FROM = Map.ofEntries(
            Map.entry("network", "from logistics import network"), Map.entry("move", "from logistics import move"),
            Map.entry("Face", "from logistics import Face"), Map.entry("Slot", "from logistics import Slot"),
            Map.entry("Recipe", "from logistics import Recipe"), Map.entry("Filter", "from logistics import Filter"),
            Map.entry("NetworkError", "from logistics import NetworkError"), Map.entry("logistics", "import logistics"),
            Map.entry("events", "import events"), Map.entry("time", "import time"), Map.entry("json", "import json"),
            Map.entry("math", "import math"), Map.entry("random", "import random"), Map.entry("env", "import env"),
            Map.entry("qbot", "import qbot"), Map.entry("game", "import game"), Map.entry("display", "import display"),
            Map.entry("requests", "import requests"), Map.entry("os", "import os"), Map.entry("sys", "import sys"));

    private static final Map<String, String> CAPITALS = Map.of("true", "True", "false", "False", "none", "None", "null", "None");

    private static final Pattern QUOTED = Pattern.compile("'([^']*)'");
    private static final Pattern TAKES = Pattern.compile("takes (\\d+) positional arguments? but (\\d+) (?:were|was) given");

    /**
     * The hint for an error, or null for none.
     *
     * @param type    the error's kind: "NameError"
     * @param message what Python said after the colon (may be empty)
     * @param line    the line of the player's file it happened on, or -1
     */
    public static @Nullable String hint(String type, String message, int line) {
        String at = line > 0 ? "line " + line : "that line";
        String name = quoted(message);
        switch (type) {
            case "NameError" -> {
                if (name == null) {
                    return null;
                }
                if (FROM.containsKey(name)) {
                    return "`" + name + "` comes from a module. Put this line at the top of the program:\n" + FROM.get(name);
                }
                if (CAPITALS.containsKey(name.toLowerCase(java.util.Locale.ROOT)) && !CAPITALS.containsValue(name)) {
                    return "Python writes it " + CAPITALS.get(name.toLowerCase(java.util.Locale.ROOT)) + ", with a capital letter.";
                }
                return "Python doesn't know `" + name + "` on " + at + ". Check the spelling (capitals count), and that it is made "
                        + "(" + name + " = ...) or imported above that line. Text needs quotes: \"" + name + "\".";
            }
            case "SyntaxError" -> {
                return "Python couldn't read " + at + ". Look there and on the line above for: a missing : at the end of an if, "
                        + "for, while or def line; a bracket or quote that isn't closed; = where == was meant.";
            }
            case "IndentationError" -> {
                if (message.contains("unexpected indent")) {
                    return at.substring(0, 1).toUpperCase() + at.substring(1) + " starts further in than Python expects. "
                            + "Lines in the same block start at the same place.";
                }
                if (message.contains("expected")) {
                    return "A line ending with : needs the lines under it moved in (4 spaces), as the body of the if, for or def.";
                }
                return "The spaces at the start of " + at + " don't line up with any line above it. Use 4 spaces for each step in.";
            }
            case "TypeError" -> {
                Matcher takes = TAKES.matcher(message);
                if (takes.find()) {
                    return "A function was given " + takes.group(2) + " values but takes " + takes.group(1) + ". For an events "
                            + "handler, take what events gives it: def handler(change=None):";
                }
                if (message.contains("missing") && message.contains("required")) {
                    return "A function was called without all the values it needs: check what goes in its brackets.";
                }
                if (message.contains("isn't callable") || message.contains("not callable")) {
                    return "Something that isn't a function was called with ( ). Did a variable take a function's name?";
                }
                if (message.contains("str") && (message.contains("__add__") || message.contains("convert") || message.contains("concatenate"))) {
                    return "Text and numbers can't be joined with +. Use str(n), or print them with commas: print(\"Moved\", n)";
                }
                if (message.contains("unsupported types")) {
                    return "Those two kinds of value can't be used together that way (a number and text, say). Print them to see what they are.";
                }
                return null;
            }
            case "AttributeError" -> {
                if (message.contains("NoneType")) {
                    return "Something on " + at + " is None (nothing): a function gave nothing back, or found nothing. "
                            + "Check first: if thing is not None:";
                }
                if (name == null) {
                    return null;
                }
                String attribute = lastQuoted(message);
                return "`" + attribute + "` isn't something " + (name.equals(attribute) ? "that" : "a " + name) + " has. Check the "
                        + "spelling; in the editor, hover over the name before the dot to see what it has.";
            }
            case "KeyError" -> {
                name = name != null ? name : message.isBlank() ? null : message.strip(); // MicroPython: KeyError: pos
                return "There is no " + (name != null ? "\"" + name + "\"" : "such key") + " in that dictionary. If it may be missing, "
                        + "use .get(" + (name != null ? "\"" + name + "\"" : "key") + "), which gives None instead.";
            }
            case "IndexError" -> {
                return "That asks for an item past the end of a list. A list of 3 has items [0], [1] and [2]; an empty list has none.";
            }
            case "ImportError" -> {
                return "There is no module " + (name != null ? "`" + name + "`" : "by that name") + ". The game's are: logistics, events, "
                        + "network, display, qbot, env, game, requests, git. Your own: a file name.py beside the program.";
            }
            case "ZeroDivisionError" -> {
                return "A number was divided by zero. Check it isn't 0 before dividing.";
            }
            case "ValueError" -> {
                if (message.contains("integer") || message.contains("int()")) {
                    return "int() was given text that isn't a whole number.";
                }
                return null;
            }
            case "OSError" -> {
                if (message.contains("ENOENT") || message.contains("Errno 2")) {
                    return "That file or folder isn't there. ls lists what is in a folder, and pwd says which folder you are in.";
                }
                if (message.contains("EEXIST") || message.contains("Errno 17")) {
                    return "Something with that name is already there.";
                }
                return null;
            }
            case "MemoryError" -> {
                return "The program ran out of memory: a list or a text grew too big. Keep less at once.";
            }
            case "RecursionError" -> {
                return "A function keeps calling itself without stopping.";
            }
            default -> {
                return null;
            }
        }
    }

    private static @Nullable String quoted(String message) {
        Matcher m = QUOTED.matcher(message);
        return m.find() ? m.group(1) : null;
    }

    private static String lastQuoted(String message) {
        Matcher m = QUOTED.matcher(message);
        String last = "";
        while (m.find()) {
            last = m.group(1);
        }
        return last;
    }

    private static final Pattern FILE_LINE = Pattern.compile("^\\s*File \"([^\"]*)\", line (\\d+)");
    private static final Pattern ERROR_LINE = Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*(?:Error|Exception))(?::\\s?(.*))?$");

    /**
     * Follows what a program prints, line by line, and gives the hint for a traceback's last
     * line. One per computer; the terminal's own thread.
     */
    public static final class Watcher {
        private final StringBuilder line = new StringBuilder();
        /** Lines since a traceback began, or -1 when not in one. */
        private int inTraceback = -1;
        private int errorLine = -1;
        private @Nullable String lastError;

        /** A program is starting: nothing of the last one's output is remembered. */
        public void reset() {
            line.setLength(0);
            inTraceback = -1;
            errorLine = -1;
            lastError = null;
        }

        /** The player typed a line (for input()): what follows starts a new line, whatever the prompt left unfinished. */
        public void typed() {
            line.setLength(0);
        }

        /** The error that ended the program's last traceback ("NameError: name 'x' isn't defined"), or null. */
        public @Nullable String lastError() {
            return lastError;
        }

        /** Takes printed text; returns the hint to print after it, or null. */
        public @Nullable String accept(String text) {
            String found = null;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '\n') {
                    String done = line.toString();
                    line.setLength(0);
                    String hint = lineDone(done);
                    if (hint != null) {
                        found = hint;
                    }
                } else if (c != '\r' && line.length() < 400) {
                    line.append(c);
                }
            }
            return found;
        }

        private @Nullable String lineDone(String done) {
            if (done.startsWith("Traceback (most recent call last)")) {
                inTraceback = 0;
                errorLine = -1;
                return null;
            }
            if (inTraceback < 0) {
                return null;
            }
            if (++inTraceback > 60) {
                inTraceback = -1;
                return null;
            }
            Matcher file = FILE_LINE.matcher(done);
            if (file.find()) {
                // The player's own files (/my/farm.py, or what was typed), not the game's modules (logistics.py).
                if (file.group(1).startsWith("/") || file.group(1).equals("<stdin>")) {
                    // Any number of digits can be printed; only a real line number is kept.
                    errorLine = file.group(2).length() <= 7 ? Integer.parseInt(file.group(2)) : -1;
                }
                return null;
            }
            Matcher error = ERROR_LINE.matcher(done);
            if (error.find()) {
                inTraceback = -1;
                lastError = done.strip();
                return hint(error.group(1), error.group(2) == null ? "" : error.group(2), errorLine);
            }
            return null;
        }
    }
}
