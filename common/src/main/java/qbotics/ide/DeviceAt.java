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

    /** A device by kind ("bus") and name (null: the network's only one of that kind). */
    public record Found(String kind, @Nullable String name) {
    }

    /** The network's finders that name one device. */
    private static final String KINDS = "bus|usb|interface|fluid_interface|fluid_bus|energy_interface|energy_bus|experience_interface"
            + "|player_interface|chat_interface|command_interface|entity_sensor|block_sensor|collector|monitor|panel|web_panel"
            + "|programmer|drive_bay|pin|placer|breaker|dropper";
    /** .bus("name"), .bus('name') or .bus(). */
    private static final Pattern FINDER = Pattern.compile("\\.(" + KINDS + ")\\(\\s*(?:([\"'])([^\"']*)\\2)?\\s*\\)");

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
        String word = wordAt(text, column);
        if (word == null) {
            return null;
        }
        Pattern assigned = Pattern.compile("^\\s*" + Pattern.quote(word) + "\\s*=(?!=)[^#]*?" + FINDER.pattern());
        // The nearest setting at or above the click, else the first one below it.
        for (int i = line; i >= 0; i--) {
            Matcher m = assigned.matcher(lines.get(i));
            if (m.find()) {
                return found(m, m.groupCount() - 2);
            }
        }
        for (int i = line + 1; i < lines.size(); i++) {
            Matcher m = assigned.matcher(lines.get(i));
            if (m.find()) {
                return found(m, m.groupCount() - 2);
            }
        }
        return null;
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
