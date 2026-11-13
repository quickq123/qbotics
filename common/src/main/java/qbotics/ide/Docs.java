package qbotics.ide;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The hints shown when the mouse rests on a name in a Python file: how a function is called
 * and what it does.
 *
 * <p>Most of the text is not written here. It is read from Python source: the modules this
 * mod gives every computer (the files under qbotics/rom, the same ones built into the
 * engine) and the file being edited. A function's hint is its "def" line and its docstring,
 * so documenting a function in Python is all it takes to give it a hint. Only Python's own
 * built-in functions, which have no Python source, are described by hand below.
 *
 * <p>Like {@link Completer} it works from the text and does not understand types. When it
 * can't tell which class a method belongs to, it shows each one that has a method by that name.
 */
public final class Docs {
    private static final int MAX_ENTRIES = 3;

    /**
     * One thing a name can mean.
     *
     * @param module    the module it is in ("" for the file being edited or a built-in)
     * @param owner     the class a method belongs to, or "" for a function or class
     * @param base      for a class, the class it extends, or ""
     * @param signature how it is written, e.g. "Interface.set_slot_filter(slot, item)"
     * @param doc       what it does; may be empty
     */
    public record Entry(String module, String owner, String name, String base, boolean isClass, String signature, String doc) {
    }

    /**
     * What to show for the name at start..end of a line.
     */
    public record Hint(int start, int end, List<Entry> entries) {
    }

    private Docs() {
    }

    // ---- reading Python source -------------------------------------------------------

    private static final Pattern DEF = Pattern.compile("^(\\s*)def\\s+(\\w+)\\s*\\((.*)\\)\\s*(?:->[^:]*)?:\\s*(?:#.*)?$");
    private static final Pattern CLASS = Pattern.compile("^class\\s+(\\w+)\\s*(?:\\(\\s*(\\w*)[^)]*\\))?\\s*:.*$");

    /** Every function, class and method defined in some Python source. */
    static List<Entry> parse(String module, List<String> lines) {
        List<Entry> entries = new ArrayList<>();
        String currentClass = "";
        boolean property = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String stripped = line.strip();
            if (stripped.isEmpty() || stripped.startsWith("#")) {
                continue;
            }
            boolean indented = Character.isWhitespace(line.charAt(0));
            if (!indented) {
                currentClass = "";
            }
            if (stripped.startsWith("@")) {
                property = stripped.equals("@property");
                continue;
            }
            Matcher isClass = CLASS.matcher(line);
            Matcher isDef = DEF.matcher(line);
            if (isClass.matches()) {
                currentClass = isClass.group(1);
                String base = isClass.group(2) == null ? "" : isClass.group(2);
                entries.add(new Entry(module, "", currentClass, base, true, "class " + currentClass + (base.isEmpty() ? "" : "(" + base + ")"),
                        docstring(lines, i + 1)));
            } else if (isDef.matches() && (isDef.group(1).isEmpty() || (!currentClass.isEmpty() && isDef.group(1).length() <= 4))) {
                String name = isDef.group(2);
                String owner = isDef.group(1).isEmpty() ? "" : currentClass;
                String arguments = isDef.group(3).strip();
                if (!owner.isEmpty()) {
                    arguments = arguments.replaceFirst("^(self|cls)\\s*,?\\s*", "");
                }
                String prefix = owner.isEmpty() ? (module.isEmpty() ? "" : module + ".") : owner + ".";
                String signature = property ? prefix + name + "   (a value: no brackets)" : prefix + name + "(" + arguments + ")";
                entries.add(new Entry(module, owner, name, "", false, signature, docstring(lines, i + 1)));
            }
            property = false;
        }
        return entries;
    }

    /** The docstring starting at this line, or failing that the comment lines there. One paragraph per line. */
    private static String docstring(List<String> lines, int from) {
        if (from >= lines.size()) {
            return "";
        }
        String first = lines.get(from).strip();
        StringBuilder text = new StringBuilder();
        String quotes = first.startsWith("\"\"\"") ? "\"\"\"" : first.startsWith("'''") ? "'''" : null;
        if (quotes != null) {
            String rest = first.substring(3);
            for (int i = from; i < lines.size() && i < from + 30; i++) {
                String line = i == from ? rest : lines.get(i).strip();
                int end = line.indexOf(quotes);
                text.append(end >= 0 ? line.substring(0, end) : line).append('\n');
                if (end >= 0) {
                    break;
                }
            }
        } else {
            for (int i = from; i < lines.size() && lines.get(i).strip().startsWith("#"); i++) {
                text.append(lines.get(i).strip().substring(1).strip()).append('\n');
            }
        }
        // Lines of one paragraph are joined; a blank line starts the next.
        StringBuilder joined = new StringBuilder();
        for (String paragraph : text.toString().strip().split("\n\\s*\n")) {
            if (!joined.isEmpty()) {
                joined.append('\n');
            }
            joined.append(paragraph.strip().replaceAll("\\s*\n\\s*", " "));
        }
        return joined.toString();
    }

    // ---- what is known without looking at the file ------------------------------------

    private static final String[] ROM_MODULES = {"logistics", "network", "events", "pip", "display", "qbot", "requests", "git", "game"};
    private static List<Entry> library;
    private static Map<String, java.util.Set<String>> exports;

    /** The names each of the mod's modules offers (for the checker: a misspelt import). */
    public static synchronized Map<String, java.util.Set<String>> moduleExports() {
        if (exports == null) {
            exports = new HashMap<>();
            for (String module : ROM_MODULES) {
                try (InputStream in = Docs.class.getResourceAsStream("/qbotics/rom/" + module + ".py")) {
                    if (in != null) {
                        exports.put(module, Checker.exports(new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r", "").lines().toList()));
                    }
                } catch (IOException e) {
                    // That module's names aren't checked.
                }
            }
        }
        return exports;
    }
    private static final Map<String, String> MODULE_NOTES = new HashMap<>();

    /** The mod's own modules, read from their source, then the hand-written entries. */
    private static synchronized List<Entry> library() {
        if (library == null) {
            List<Entry> all = new ArrayList<>();
            for (String module : ROM_MODULES) {
                try (InputStream in = Docs.class.getResourceAsStream("/qbotics/rom/" + module + ".py")) {
                    if (in != null) {
                        List<String> lines = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r", "").lines().toList();
                        // The comment at the top of the file says what the module is for.
                        if (!lines.isEmpty() && lines.getFirst().startsWith("#")) {
                            MODULE_NOTES.put(module, lines.getFirst().substring(1).replaceAll("\\(design doc[^)]*\\)", "").strip());
                        }
                        for (Entry entry : parse(module, lines)) {
                            if (!entry.name().startsWith("_")) {
                                all.add(entry);
                            }
                        }
                    }
                } catch (IOException e) {
                    // No hints for this module.
                }
            }
            for (String[] row : BUILT_IN) {
                int dot = row[0].indexOf('.');
                int bracket = row[0].indexOf('(');
                String name = row[0].substring(dot + 1, bracket < 0 ? row[0].length() : bracket);
                all.add(new Entry(dot < 0 ? "" : row[0].substring(0, dot), "", name, "", false, row[0], row[1]));
            }
            for (String[] row : METHODS) {
                String name = row[0].substring(row[0].indexOf('.') + 1, row[0].indexOf('('));
                all.add(new Entry("", row[0].substring(0, row[0].indexOf('.')), name, "", false, row[0], row[1]));
            }
            MODULE_NOTES.put("time", "Waiting and measuring time.");
            MODULE_NOTES.put("os", "Files and folders on this computer's drive.");
            MODULE_NOTES.put("json", "Turning lists and dicts into text and back.");
            MODULE_NOTES.put("math", "Mathematical functions and constants.");
            MODULE_NOTES.put("random", "Random numbers and choices.");
            MODULE_NOTES.put("sys", "The Python system itself.");
            MODULE_NOTES.put("gc", "Memory: how much is free, and tidying it up.");
            library = all;
        }
        return library;
    }

    /** Python's built-in functions and the modules written in C: these have no Python source to read. */
    private static final String[][] BUILT_IN = {
        {"print(*values, sep=' ', end='\\n')", "Shows values in the terminal, separated by spaces, then starts a new line."},
        {"input(prompt='')", "Shows the prompt, waits for a line to be typed in the terminal, and returns it as text."},
        {"len(thing)", "How many items are in a list, dict or set, or characters in a string."},
        {"range(start, stop, step=1)", "The numbers from start up to but not including stop. range(5) is 0, 1, 2, 3, 4."},
        {"int(value)", "Turns text or a decimal number into a whole number. int('12') is 12; int(3.9) is 3."},
        {"float(value)", "Turns text or a whole number into a decimal number. float('2.5') is 2.5."},
        {"str(value)", "Turns a value into text. str(12) is '12'."},
        {"bool(value)", "True or False. Empty things, 0 and None are False; everything else is True."},
        {"list(items=())", "A new list, holding the items of whatever is given."},
        {"dict(pairs=())", "A new dictionary: values looked up by key."},
        {"set(items=())", "A new set: a collection in which each item appears once."},
        {"tuple(items=())", "A new tuple: a list that can't be changed."},
        {"abs(number)", "The number without its sign. abs(-3) is 3."},
        {"min(*values)", "The smallest of several values, or of the items in a list."},
        {"max(*values)", "The largest of several values, or of the items in a list."},
        {"sum(numbers)", "All the numbers in a list added together."},
        {"round(number, digits=0)", "The number rounded to the nearest whole number, or to some decimal places."},
        {"sorted(items, key=None, reverse=False)", "A new list with the items in order. The original is left alone."},
        {"reversed(items)", "The items from last to first."},
        {"enumerate(items, start=0)", "Pairs of (position, item), for loops that need both: for i, item in enumerate(items)."},
        {"zip(*lists)", "Pairs up items from several lists: the first of each, then the second of each, and so on."},
        {"any(items)", "True if at least one item is true."},
        {"all(items)", "True if every item is true."},
        {"isinstance(value, kind)", "True if the value is of that kind. isinstance(3, int) is True."},
        {"type(value)", "The kind of a value, such as int, str or list."},
        {"repr(value)", "The value as it would be written in code. repr('hi') is \"'hi'\"."},
        {"chr(number)", "The character with that code. chr(65) is 'A'."},
        {"ord(character)", "The code of a character. ord('A') is 65."},
        {"open(path, mode='r')", "Opens a file on this computer's drive. Mode 'r' reads, 'w' writes a new file, 'a' adds to the end. Best used as: with open(path) as f:"},
        {"execfile(path)", "Runs another Python file."},
        {"time.sleep(seconds)", "Waits for a number of seconds. Decimals work: sleep(0.5)."},
        {"time.sleep_ms(milliseconds)", "Waits for a number of thousandths of a second."},
        {"time.time()", "The time in seconds, for measuring how long something took."},
        {"time.ticks_ms()", "A counter of thousandths of a second. Compare two readings with ticks_diff."},
        {"time.ticks_us()", "A counter of millionths of a second. Compare two readings with ticks_diff."},
        {"time.ticks_diff(later, earlier)", "The time between two readings of ticks_ms or ticks_us."},
        {"json.dumps(value)", "Turns lists, dicts, text and numbers into JSON text."},
        {"json.loads(text)", "Turns JSON text back into lists, dicts, text and numbers."},
        {"json.dump(value, file)", "Writes a value to an open file as JSON."},
        {"json.load(file)", "Reads JSON from an open file."},
        {"os.listdir(folder='.')", "The names of the files and folders in a folder."},
        {"os.mkdir(folder)", "Makes a new folder."},
        {"os.remove(path)", "Deletes a file."},
        {"os.rmdir(folder)", "Deletes an empty folder."},
        {"os.rename(old, new)", "Renames or moves a file or folder."},
        {"os.getcwd()", "The current folder."},
        {"os.chdir(folder)", "Changes the current folder."},
        {"os.stat(path)", "Details of a file, such as its size."},
        {"random.random()", "A random decimal number from 0 up to (not including) 1."},
        {"random.randint(low, high)", "A random whole number from low to high, including both."},
        {"random.randrange(start, stop)", "A random whole number from start up to but not including stop."},
        {"random.uniform(low, high)", "A random decimal number between low and high."},
        {"random.choice(items)", "One item picked at random from a list."},
        {"random.seed(number)", "Makes the random numbers that follow repeatable."},
        {"math.sqrt(number)", "The square root."},
        {"math.floor(number)", "Rounds down to a whole number."},
        {"math.ceil(number)", "Rounds up to a whole number."},
        {"math.pi", "3.14159..., the ratio of a circle's circumference to its diameter."},
        {"math.sin(angle)", "The sine of an angle given in radians."},
        {"math.cos(angle)", "The cosine of an angle given in radians."},
        {"math.atan2(y, x)", "The angle, in radians, of the point (x, y) seen from (0, 0)."},
        {"math.radians(degrees)", "Turns an angle in degrees into radians."},
        {"math.degrees(radians)", "Turns an angle in radians into degrees."},
        {"gc.collect()", "Tidies up memory that is no longer used."},
        {"gc.mem_free()", "How many bytes of memory are free."},
        {"gc.mem_alloc()", "How many bytes of memory are in use."},
        {"sys.exit()", "Stops the program."},
    };

    /** The commonest methods of Python's own lists, strings, dicts and files. */
    private static final String[][] METHODS = {
        {"list.append(item)", "Adds an item to the end of the list."},
        {"list.extend(items)", "Adds every item of another list to the end."},
        {"list.insert(position, item)", "Puts an item in at a position; later items move along."},
        {"list.pop(position=-1)", "Removes and returns an item: the last, unless a position is given."},
        {"list.remove(item)", "Removes the first item equal to this one."},
        {"list.sort(key=None, reverse=False)", "Puts the list itself in order."},
        {"list.index(item)", "The position of the first item equal to this one."},
        {"list.count(item)", "How many times an item appears."},
        {"str.split(separator=None)", "Cuts text into a list of pieces: at spaces, or at a separator."},
        {"str.join(pieces)", "Joins a list of text pieces, with this text between them. ', '.join(names)"},
        {"str.strip()", "The text without spaces and line breaks at its start and end."},
        {"str.replace(old, new)", "The text with every old replaced by new."},
        {"str.upper()", "The text in capital letters."},
        {"str.lower()", "The text in small letters."},
        {"str.startswith(prefix)", "True if the text begins with the prefix."},
        {"str.endswith(suffix)", "True if the text ends with the suffix."},
        {"str.find(piece)", "Where a piece first appears in the text, or -1 if it doesn't."},
        {"str.format(*values)", "Fills the {} places in the text with values."},
        {"dict.get(key, default=None)", "The value for a key, or the default if the key isn't there."},
        {"dict.keys()", "Every key in the dictionary."},
        {"dict.values()", "Every value in the dictionary."},
        {"dict.items()", "Every (key, value) pair: for key, value in d.items()."},
        {"dict.update(other)", "Copies every key and value from another dictionary into this one."},
        {"file.read()", "Everything in the file, as one piece of text."},
        {"file.readline()", "The next line of the file."},
        {"file.write(text)", "Writes text to the file."},
        {"file.close()", "Finishes with the file. Not needed inside a with block."},
    };

    // ---- finding what a name means ---------------------------------------------------

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /** The hint for the name at this place in a Python file, or null if there is none. */
    public static Hint lookup(TextBuffer buffer, int line, int column) {
        if (line < 0 || line >= buffer.lineCount()) {
            return null;
        }
        String text = buffer.line(line);
        if (column < 0 || column >= text.length() || !isNameChar(text.charAt(column))) {
            return null;
        }
        int start = column;
        int end = column;
        while (start > 0 && isNameChar(text.charAt(start - 1))) {
            start--;
        }
        while (end < text.length() && isNameChar(text.charAt(end))) {
            end++;
        }
        String word = text.substring(start, end);
        if (Character.isDigit(word.charAt(0)) || inStringOrComment(buffer, line, column)) {
            return null;
        }
        String owner = "";
        if (start > 0 && text.charAt(start - 1) == '.') {
            int ownerStart = start - 1;
            while (ownerStart > 0 && isNameChar(text.charAt(ownerStart - 1))) {
                ownerStart--;
            }
            owner = text.substring(ownerStart, start - 1);
            if (owner.isEmpty()) {
                owner = "?"; // after a call or an index, such as net.bus().count
            }
        }

        List<String> lines = new ArrayList<>(buffer.lineCount());
        for (int i = 0; i < buffer.lineCount(); i++) {
            lines.add(buffer.line(i));
        }
        List<Entry> known = new ArrayList<>(parse("", lines)); // the file's own definitions come first
        known.addAll(library());
        Map<String, String> modules = Completer.importedModules(buffer);

        List<Entry> found = new ArrayList<>();
        if (owner.isEmpty()) {
            if (modules.containsKey(word)) {
                String module = modules.get(word);
                String note = MODULE_NOTES.get(module);
                if (note != null) {
                    found.add(new Entry(module, "", module, "", false, "module " + module, note));
                }
            } else {
                for (Entry entry : known) {
                    // A module's function counts when it was imported by name: from logistics import network.
                    boolean reachable = entry.module().isEmpty() || importsName(lines, entry.module(), word);
                    if (entry.owner().isEmpty() && entry.name().equals(word) && reachable) {
                        found.add(entry);
                    }
                }
            }
        } else if (modules.containsKey(owner)) {
            for (Entry entry : known) {
                if (entry.owner().isEmpty() && entry.name().equals(word) && entry.module().equals(modules.get(owner))) {
                    found.add(entry);
                }
            }
        } else {
            // A method. If the file shows what the variable holds, only that class (and what it extends) counts.
            String kind = owner.equals("self") ? enclosingClass(lines, line) : guessClass(lines, owner, known);
            for (String c = kind; c != null && !c.isEmpty() && found.isEmpty(); c = baseOf(known, c)) {
                for (Entry entry : known) {
                    if (entry.owner().equals(c) && entry.name().equals(word)) {
                        found.add(entry);
                    }
                }
            }
            if (found.isEmpty()) {
                for (Entry entry : known) {
                    if (!entry.owner().isEmpty() && entry.name().equals(word) && !entry.name().startsWith("__")) {
                        found.add(entry);
                    }
                }
            }
        }
        if (found.isEmpty()) {
            return null;
        }
        return new Hint(start, end, found.size() > MAX_ENTRIES ? found.subList(0, MAX_ENTRIES) : found);
    }

    private static boolean inStringOrComment(TextBuffer buffer, int line, int column) {
        int state = Highlighter.NORMAL;
        for (int i = 0; i < line; i++) {
            state = Highlighter.highlight(Highlighter.Language.PYTHON, buffer.line(i), state).endState();
        }
        for (Highlighter.Token token : Highlighter.highlight(Highlighter.Language.PYTHON, buffer.line(line), state).tokens()) {
            boolean isText = token.kind() == Highlighter.Kind.STRING || token.kind() == Highlighter.Kind.COMMENT;
            if (isText && column >= token.start() && column < token.end()) {
                return true;
            }
        }
        return false;
    }

    /** True if the file has "from module import ..., name, ..." (or "import *"). */
    private static boolean importsName(List<String> lines, String module, String name) {
        String start = "from " + module + " import ";
        for (String line : lines) {
            String stripped = line.strip();
            if (stripped.startsWith(start)) {
                for (String part : stripped.substring(start.length()).split(",")) {
                    String first = part.strip().split("\\s+")[0];
                    if (first.equals(name) || first.equals("*")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static String baseOf(List<Entry> known, String className) {
        for (Entry entry : known) {
            if (entry.isClass() && entry.name().equals(className)) {
                return entry.base();
            }
        }
        return null;
    }

    private static String enclosingClass(List<String> lines, int line) {
        for (int i = line; i >= 0; i--) {
            Matcher match = CLASS.matcher(lines.get(i));
            if (match.matches()) {
                return match.group(1);
            }
        }
        return "";
    }

    /**
     * What class a variable probably holds, from a line that assigns to it: "x = Thing(...)"
     * for a known class, or a call that is known to give one, such as "x = net.bus()".
     */
    private static String guessClass(List<String> lines, String variable, List<Entry> known) {
        Pattern assignment = Pattern.compile("^\\s*" + Pattern.quote(variable) + "\\s*=\\s*(?:[\\w.]*\\.)?(\\w+)\\(.*");
        for (String line : lines) {
            Matcher match = assignment.matcher(line);
            if (match.matches()) {
                String called = match.group(1);
                String made = switch (called) {
                    case "network" -> "Network";
                    case "interface" -> "Interface";
                    case "usb" -> "USB";
                    case "bus" -> "Bus";
                    case "device" -> "Device";
                    case "receive" -> "Message";
                    case "open" -> "file";
                    default -> called;
                };
                if (called.startsWith("fluid_") || called.startsWith("energy_") || called.startsWith("experience_")) {
                    // net.fluid_bus() gives a FluidBus, net.energy_bus() an EnergyBus.
                    String[] words = called.split("_");
                    made = Character.toUpperCase(words[0].charAt(0)) + words[0].substring(1)
                            + Character.toUpperCase(words[1].charAt(0)) + words[1].substring(1);
                }
                for (Entry entry : known) {
                    if (entry.owner().equals(made) || (entry.isClass() && entry.name().equals(made))) {
                        return made;
                    }
                }
            }
            String stripped = line.strip();
            if (stripped.startsWith("with open(") && stripped.endsWith(" as " + variable + ":")) {
                return "file";
            }
        }
        return "";
    }

    /** Breaks text into lines no longer than width, at spaces. Line breaks in the text are kept. */
    public static List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        for (String paragraph : text.split("\n")) {
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.strip().split(" ")) {
                if (!line.isEmpty() && line.length() + 1 + word.length() > width) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                while (word.length() > width) {
                    lines.add(word.substring(0, width));
                    word = word.substring(width);
                }
                line.append(line.isEmpty() ? "" : " ").append(word);
            }
            if (!line.isEmpty()) {
                lines.add(line.toString());
            }
        }
        return lines;
    }
}
