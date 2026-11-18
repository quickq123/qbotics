package qbotics.ide;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * What the editor can tell from the world while a program is written (design doc §17.1), so
 * fewer trips to the game are needed: device names the code uses that aren't on the network
 * (warned as you type), item and tag names inside the strings that name items (suggested, those
 * the network holds first), and which item string a place in the code is (for Ctrl+click). From
 * the text and the lists the editor was given; nothing here asks the game.
 */
public final class WorldHints {
    private WorldHints() {
    }

    /**
     * An item ("minecraft:coal") or tag ("#minecraft:logs") name, for suggestions.
     *
     * @param held how many of it the computer's network holds (0 for none, or for a tag)
     */
    public record ItemName(String id, long held) {
    }

    private static final String KINDS = "bus|usb|interface|fluid_interface|fluid_bus|energy_interface|energy_bus|experience_interface"
            + "|player_interface|chat_interface|command_interface|entity_sensor|block_sensor|collector|monitor|panel|web_panel"
            + "|programmer|drive_bay|pin|placer|breaker|dropper|device";
    private static final Pattern FINDER = Pattern.compile("\\.(" + KINDS + ")\\(\\s*([\"'])([^\"']*)\\2\\s*\\)");
    private static final Pattern STORAGE = Pattern.compile("\\.storage\\(((?:[^()]|\\([^()]*\\))*)\\)");
    private static final Pattern QUOTED = Pattern.compile("([\"'])([^\"']*)\\1");
    private static final Pattern IP = Pattern.compile("\\d+\\.\\d+\\.\\d+\\.\\d+");
    private static final Set<String> HOLDERS = Set.of("bus", "usb", "interface", "collector", "player_interface");
    private static final Set<String> USB_WORKS_AS = Set.of("bus", "fluid_bus", "energy_bus");

    // ---- 1. device names that aren't on the network ------------------------------------

    /**
     * Warnings for the devices the code names in net.bus("...") and the like, and the patterns
     * of net.storage("..."), that nothing on the network answers to now. None when the network
     * isn't known (no devices listed), or when the file reaches other networks (remote()).
     */
    public static List<Checker.Problem> deviceProblems(List<String> lines, List<Completer.Device> devices) {
        List<Checker.Problem> problems = new ArrayList<>();
        if (devices.isEmpty() || lines.stream().anyMatch(l -> l.contains(".remote("))) {
            return problems;
        }
        for (int i = 0; i < lines.size(); i++) {
            String text = lines.get(i);
            if (text.strip().startsWith("#")) {
                continue;
            }
            Matcher call = FINDER.matcher(text);
            while (call.find()) {
                String message = deviceProblem(call.group(1), call.group(3), devices);
                if (message != null) {
                    problems.add(new Checker.Problem(i, call.start(3), Math.max(call.start(3) + 1, call.end(3)), Checker.Severity.WARNING, message));
                }
            }
            Matcher group = STORAGE.matcher(text);
            while (group.find()) {
                Matcher name = QUOTED.matcher(group.group(1));
                while (name.find()) {
                    String message = storageProblem(name.group(2), devices);
                    if (message != null) {
                        int start = group.start(1) + name.start(2);
                        problems.add(new Checker.Problem(i, start, Math.max(start + 1, group.start(1) + name.end(2)), Checker.Severity.WARNING, message));
                    }
                }
            }
        }
        return problems;
    }

    private static boolean fits(String kind, Completer.Device device) {
        return kind.equals("device") ? !device.kind().equals("computer")
                : device.kind().equals(kind) || (device.kind().equals("usb") && USB_WORKS_AS.contains(kind));
    }

    private static String article(String kind) {
        String words = kind.replace('_', ' ');
        return ("aeiou".indexOf(words.charAt(0)) >= 0 ? "an " : "a ") + words;
    }

    private static @Nullable String deviceProblem(String kind, String key, List<Completer.Device> devices) {
        if (key.isEmpty() || key.indexOf(':') >= 0) {
            return null; // a hardware address: not checked
        }
        if (IP.matcher(key).matches()) {
            return devices.stream().anyMatch(d -> d.ip().equals(key)) ? null : "Nothing on this network has the address " + key + " now.";
        }
        if (devices.stream().anyMatch(d -> d.name().equals(key) && fits(kind, d))) {
            return null;
        }
        for (Completer.Device d : devices) {
            if (d.name().equals(key)) {
                return "\"" + key + "\" on this network is " + article(d.kind()) + ", not " + article(kind) + ".";
            }
        }
        String closest = closest(key, devices.stream().filter(d -> fits(kind, d) && !d.name().isEmpty()).map(Completer.Device::name).toList());
        return "No " + kind.replace('_', ' ') + " named \"" + key + "\" on this network now."
                + (closest == null ? " Name one with the Network Probe." : " Did you mean \"" + closest + "\"?");
    }

    private static @Nullable String storageProblem(String key, List<Completer.Device> devices) {
        if (key.isEmpty() || key.indexOf(':') >= 0 || IP.matcher(key).matches()) {
            return null;
        }
        List<String> names = devices.stream().filter(d -> HOLDERS.contains(d.kind()) && !d.name().isEmpty()).map(Completer.Device::name).toList();
        if (key.indexOf('*') >= 0 || key.indexOf('?') >= 0) {
            Pattern glob = Pattern.compile(Pattern.quote(key).replace("*", "\\E.*\\Q").replace("?", "\\E.\\Q"));
            return names.stream().anyMatch(n -> glob.matcher(n).matches()) ? null
                    : "Nothing on this network matches \"" + key + "\" now, so this storage would be empty.";
        }
        if (names.contains(key)) {
            return null;
        }
        String closest = closest(key, names);
        return "Nothing that holds items is named \"" + key + "\" on this network now." + (closest == null ? "" : " Did you mean \"" + closest + "\"?");
    }

    /** The name nearest to a mistyped one, if one is near enough to be what was meant. */
    private static @Nullable String closest(String typed, List<String> names) {
        String best = null;
        int bestDistance = Math.max(2, typed.length() / 3) + 1;
        for (String name : names) {
            int distance = distance(typed.toLowerCase(Locale.ROOT), name.toLowerCase(Locale.ROOT));
            if (distance < bestDistance) {
                best = name;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int change = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + change);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    // ---- 2 and 3. strings that name items -----------------------------------------------

    /** Calls whose strings name items or filters of them. */
    private static final Set<String> ITEM_CALLS = Set.of("move", "count", "where", "ingredient", "result", "put", "take", "craft",
            "recipes", "uses", "item", "plan", "Filter", "available");
    /** Keyword values that name items: only="coal", without=["dirt", "gravel"]. */
    private static final Pattern ITEM_KEYWORD = Pattern.compile("\\b(only|without|what|item)\\s*=\\s*\\[?(?:\\s*([\"'])[^\"']*\\2\\s*,)*\\s*$");

    /** The column of the quote opening the string that is still open at the end of this text, or -1. */
    private static int openString(String before) {
        int open = -1;
        char quote = 0;
        for (int i = 0; i < before.length(); i++) {
            char c = before.charAt(i);
            if (open >= 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    open = -1;
                }
            } else if (c == '#') {
                return -1; // a comment
            } else if (c == '"' || c == '\'') {
                open = i;
                quote = c;
            }
        }
        return open;
    }

    /** Whether a string starting at this quote is one that names items, from the code before it. */
    private static boolean namesItems(String beforeQuote) {
        if (ITEM_KEYWORD.matcher(beforeQuote).find()) {
            return true;
        }
        // The call the string is a value of: the name before the nearest bracket still open.
        int depth = 0;
        for (int i = beforeQuote.length() - 1; i >= 0; i--) {
            char c = beforeQuote.charAt(i);
            if (c == ')' || c == ']') {
                depth++;
            } else if (c == '[') {
                if (depth > 0) {
                    depth--;
                }
            } else if (c == '(') {
                if (depth > 0) {
                    depth--;
                    continue;
                }
                int end = i;
                int start = end;
                while (start > 0 && (Character.isLetterOrDigit(beforeQuote.charAt(start - 1)) || beforeQuote.charAt(start - 1) == '_')) {
                    start--;
                }
                return ITEM_CALLS.contains(beforeQuote.substring(start, end));
            }
        }
        return false;
    }

    /**
     * Item and tag names for the string being typed, when it is one that names items
     * (move(a, b, "co|"), only="#c:o|", count("coal | ch|")): the network's own first, with how
     * many it holds. Null when the cursor isn't in such a string, or nothing fits.
     */
    public static Completer.@Nullable Result itemNames(String before, List<ItemName> items, boolean asked) {
        int quote = openString(before);
        if (quote < 0 || items.isEmpty() || !namesItems(before.substring(0, quote))) {
            return null;
        }
        String inside = before.substring(quote + 1);
        int bar = inside.lastIndexOf('|');
        String typed = (bar < 0 ? inside : inside.substring(bar + 1)).stripLeading();
        if (!asked && typed.length() < 2) {
            return null;
        }
        String lower = typed.toLowerCase(Locale.ROOT);
        boolean tag = lower.startsWith("#");
        String bare = tag ? lower.substring(1) : lower;
        List<ItemName> fitting = new ArrayList<>();
        for (ItemName item : items) {
            if (item.id().startsWith("#") != tag) {
                continue;
            }
            String id = tag ? item.id().substring(1) : item.id();
            String path = id.substring(id.indexOf(':') + 1);
            if (id.startsWith(bare) || path.startsWith(bare)) {
                fitting.add(item);
            }
        }
        fitting.sort(Comparator.comparingLong((ItemName i) -> -i.held()).thenComparing(i -> written(i.id())));
        List<String> words = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (ItemName item : fitting) {
            String word = written(item.id());
            if (word.equals(typed) || words.contains(word)) {
                continue;
            }
            words.add(word);
            notes.add(item.held() > 0 ? String.format("%,d on this network", item.held()) : tag ? "tag" : "");
            if (words.size() >= 40) {
                break;
            }
        }
        return words.isEmpty() ? null : new Completer.Result(before.length() - typed.length(), typed, words, notes);
    }

    /** How a program writes a name: a vanilla item without "minecraft:". */
    private static String written(String id) {
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }

    /** The item string (a name or filter) at this place in the code, for Ctrl+click; null if there is none. */
    public static @Nullable String filterAt(List<String> lines, int line, int column) {
        if (line < 0 || line >= lines.size()) {
            return null;
        }
        String text = lines.get(line);
        int open = -1;
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (open >= 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    if (column >= open && column <= i) {
                        String inside = text.substring(open + 1, i).strip();
                        return !inside.isEmpty() && namesItems(text.substring(0, open)) ? inside : null;
                    }
                    open = -1;
                }
            } else if (c == '#') {
                return null;
            } else if (c == '"' || c == '\'') {
                open = i;
                quote = c;
            }
        }
        return null;
    }
}
