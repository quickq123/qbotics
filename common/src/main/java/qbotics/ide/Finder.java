package qbotics.ide;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Find, for the editor's find and replace bar. */
public final class Finder {
    private static final int MAX_MATCHES = 5000;

    /** One place the text was found: characters start (inclusive) to end (exclusive) of a line. */
    public record Match(int line, int start, int end) {
    }

    private Finder() {
    }

    /** Every place the query appears, top to bottom. A match never spans lines. */
    public static List<Match> find(TextBuffer buffer, String query, boolean caseSensitive) {
        List<Match> matches = new ArrayList<>();
        if (query.isEmpty()) {
            return matches;
        }
        String needle = caseSensitive ? query : query.toLowerCase(Locale.ROOT);
        for (int line = 0; line < buffer.lineCount() && matches.size() < MAX_MATCHES; line++) {
            String text = caseSensitive ? buffer.line(line) : buffer.line(line).toLowerCase(Locale.ROOT);
            for (int at = text.indexOf(needle); at >= 0 && matches.size() < MAX_MATCHES; at = text.indexOf(needle, at + needle.length())) {
                matches.add(new Match(line, at, at + needle.length()));
            }
        }
        return matches;
    }

    /** The first match at or after a position, wrapping to the top; -1 if there are none. */
    public static int firstAtOrAfter(List<Match> matches, int line, int column) {
        for (int i = 0; i < matches.size(); i++) {
            Match match = matches.get(i);
            if (match.line() > line || (match.line() == line && match.start() >= column)) {
                return i;
            }
        }
        return matches.isEmpty() ? -1 : 0;
    }
}
