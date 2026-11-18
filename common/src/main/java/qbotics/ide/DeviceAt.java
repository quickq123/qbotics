package qbotics.ide;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Which network device a place in a Python file names, for the editor's Ctrl+click (design doc
 * §17.1): the finder call itself ({@code net.bus("chest")}, clicked anywhere on it), or a name
 * the file sets from one ({@code chest = net.bus("chest")}, clicked wherever {@code chest} is
 * used). Read from the text as written: a name worked out while the program runs isn't followed.
 */
public final class DeviceAt {
    private DeviceAt() {
    }

    /**
     * A device by kind ("bus") and name (null: the network's only one of that kind); or, for
     * kind "storage", the names and patterns a storage is made of, in group.
     */
    public record Found(String kind, @Nullable String name, List<String> group) {
        public Found(String kind, @Nullable String name) {
            this(kind, name, List.of());
        }
    }

    /** The network's finders that name one device. */
    private static final String KINDS = "bus|usb|interface|fluid_interface|fluid_bus|energy_interface|energy_bus|experience_interface"
            + "|player_interface|chat_interface|command_interface|entity_sensor|block_sensor|collector|monitor|panel|web_panel"
            + "|programmer|drive_bay|pin|placer|breaker|dropper";
    /** .bus("name"), .bus('name') or .bus(). */
    private static final Pattern FINDER = Pattern.compile("\\.(" + KINDS + ")\\(\\s*(?:([\"'])([^\"']*)\\2)?\\s*\\)");
    /** .storage(...): its values, which may hold one level of brackets (net.bus("chest-d")). */
    private static final Pattern STORAGE = Pattern.compile("\\.storage\\(((?:[^()]|\\([^()]*\\))*)\\)");
    private static final Pattern QUOTED = Pattern.compile("([\"'])([^\"']*)\\1");

    /** The device named at this place (0-based line and column), or null if none is. */
    public static @Nullable Found at(List<String> lines, int line, int column) {
        if (line < 0 || line >= lines.size()) {
            return null;
        }
        String text = lines.get(line);
        Matcher call = FINDER.matcher(text);
        while (call.find()) {
            // From the dot's word to the closing bracket, and the object before it ("net").
            int start = call.start();
            while (start > 0 && isNameChar(text.charAt(start - 1))) {
                start--;
            }
            if (column >= start && column <= call.end()) {
                return found(call);
            }
        }
        Matcher group = STORAGE.matcher(text);
        while (group.find()) {
            int start = group.start();
            while (start > 0 && isNameChar(text.charAt(start - 1))) {
                start--;
            }
            if (column >= start && column <= group.end()) {
                return storage(group.group(1));
            }
        }
        String word = wordAt(text, column);
        if (word == null) {
            return null;
        }
        Pattern assigned = Pattern.compile("^\\s*" + Pattern.quote(word) + "\\s*=(?!=)[^#]*?" + FINDER.pattern());
        Pattern assignedGroup = Pattern.compile("^\\s*" + Pattern.quote(word) + "\\s*=(?!=)[^#]*?" + STORAGE.pattern());
        // The nearest setting at or above the click, else the first one below it.
        for (int i = line; i >= 0; i--) {
            Found found = setting(assigned, assignedGroup, lines.get(i));
            if (found != null) {
                return found;
            }
        }
        for (int i = line + 1; i < lines.size(); i++) {
            Found found = setting(assigned, assignedGroup, lines.get(i));
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static @Nullable Found setting(Pattern assigned, Pattern assignedGroup, String text) {
        Matcher m = assigned.matcher(text);
        if (m.find()) {
            return found(m, m.groupCount() - 2);
        }
        m = assignedGroup.matcher(text);
        return m.find() ? storage(m.group(m.groupCount())) : null;
    }

    /** A storage from the text of its values: the names and patterns written in it. */
    private static @Nullable Found storage(String values) {
        List<String> names = new java.util.ArrayList<>();
        Matcher quoted = QUOTED.matcher(values);
        while (quoted.find()) {
            names.add(quoted.group(2));
        }
        return names.isEmpty() ? null : new Found("storage", null, List.copyOf(names));
    }

    private static Found found(Matcher call) {
        return found(call, 1);
    }

    /** The kind is in the group given; the name two groups later. */
    private static Found found(Matcher call, int kindGroup) {
        String name = call.group(kindGroup + 2);
        return new Found(call.group(kindGroup), name);
    }

    private static @Nullable String wordAt(String text, int column) {
        int at = column < text.length() && isNameChar(text.charAt(column)) ? column
                : column > 0 && column <= text.length() && isNameChar(text.charAt(column - 1)) ? column - 1 : -1;
        if (at < 0) {
            return null;
        }
        int start = at;
        int end = at;
        while (start > 0 && isNameChar(text.charAt(start - 1))) {
            start--;
        }
        while (end < text.length() && isNameChar(text.charAt(end))) {
            end++;
        }
        String word = text.substring(start, end);
        return Character.isDigit(word.charAt(0)) ? null : word;
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
