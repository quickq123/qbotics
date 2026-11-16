package qbotics.computer;

import java.util.ArrayList;
import java.util.List;

/**
 * Markdown laid out as plain text for the terminal (the shell's {@code read}): no colours or
 * bold to be had, so headings are underlined, the markers ({@code **}, backticks, links) are
 * dropped, paragraphs and list items are wrapped to the width, and tables are lined up in
 * columns, their cells wrapped to fit, or listed row by row when they can't be made to fit.
 * Code (fenced or indented) is kept as it is.
 */
public final class MarkdownText {
    private MarkdownText() {
    }

    /** The narrowest a table column may be squeezed to before the table is listed instead. */
    private static final int MIN_COLUMN = 10;
    private static final String GAP = "  ";

    public static List<String> render(String markdown, int width) {
        List<String> out = new ArrayList<>();
        String[] lines = markdown.replace("\r", "").replace("\t", "    ").split("\n", -1);
        StringBuilder paragraph = new StringBuilder();
        String listIndent = null;   // the hanging indent of the list item being gathered, or null
        String listFirst = null;    // its first line's prefix ("- ", "1. ")
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            String trimmed = line.strip();
            boolean blank = trimmed.isEmpty();
            boolean fence = trimmed.startsWith("```") || trimmed.startsWith("~~~");
            boolean indentedCode = line.startsWith("    ") && paragraph.isEmpty() && listIndent == null && !blank;
            boolean heading = trimmed.matches("#{1,6} .*");
            boolean table = trimmed.startsWith("|");
            String bullet = bullet(line);
            boolean continues = !blank && !fence && !heading && !table && bullet == null && !indentedCode;
            if (continues) {
                if (!paragraph.isEmpty()) {
                    paragraph.append(' ');
                }
                paragraph.append(trimmed);
                i++;
                continue;
            }
            flush(out, paragraph, listFirst, listIndent, width);
            listFirst = null;
            listIndent = null;
            if (blank) {
                gap(out);
                i++;
            } else if (fence) {
                String marker = trimmed.substring(0, 3);
                i++;
                while (i < lines.length && !lines[i].strip().startsWith(marker)) {
                    out.add("    " + lines[i]);
                    i++;
                }
                i++;
            } else if (indentedCode) {
                while (i < lines.length && (lines[i].startsWith("    ") || lines[i].isBlank())) {
                    out.add(lines[i].stripTrailing());
                    i++;
                }
                while (!out.isEmpty() && out.getLast().isEmpty()) {
                    out.removeLast();
                }
                gap(out);
            } else if (heading) {
                int level = trimmed.indexOf(' ');
                String text = inline(trimmed.substring(level + 1).strip());
                gap(out);
                if (level == 1) {
                    text = text.toUpperCase(java.util.Locale.ROOT);
                }
                List<String> wrapped = wrap(text, width, "", "");
                out.addAll(wrapped);
                if (level <= 2) {
                    int longest = wrapped.stream().mapToInt(String::length).max().orElse(0);
                    out.add((level == 1 ? "=" : "-").repeat(longest));
                }
                out.add("");
                i++;
            } else if (table) {
                List<List<String>> rows = new ArrayList<>();
                while (i < lines.length && lines[i].strip().startsWith("|")) {
                    String row = lines[i].strip();
                    if (!row.matches("\\|?[\\s:|-]+\\|?") || !row.contains("-")) {
                        rows.add(cells(row));
                    }
                    i++;
                }
                gap(out);
                table(out, rows, width);
                gap(out);
            } else {
                // A list item: its marker, and the lines after it that are indented under it.
                String indent = " ".repeat(line.length() - line.stripLeading().length());
                listFirst = indent + bullet;
                listIndent = " ".repeat(listFirst.length());
                paragraph.append(line.stripLeading().substring(bullet.length()).strip());
                i++;
                while (i < lines.length && !lines[i].isBlank() && bullet(lines[i]) == null
                        && lines[i].startsWith(" ") && !lines[i].strip().startsWith("|")) {
                    paragraph.append(' ').append(lines[i].strip());
                    i++;
                }
            }
        }
        flush(out, paragraph, listFirst, listIndent, width);
        while (!out.isEmpty() && out.getLast().isEmpty()) {
            out.removeLast();
        }
        while (!out.isEmpty() && out.getFirst().isEmpty()) {
            out.removeFirst();
        }
        return out;
    }

    /** A list item's marker with the space after it ("- ", "12. "), or null for a line that isn't one. */
    private static String bullet(String line) {
        String s = line.stripLeading();
        if (s.length() > 2 && (s.startsWith("- ") || s.startsWith("* ") || s.startsWith("+ "))) {
            return s.substring(0, 2);
        }
        int digits = 0;
        while (digits < s.length() && Character.isDigit(s.charAt(digits))) {
            digits++;
        }
        if (digits > 0 && digits < 4 && s.length() > digits + 2 && (s.charAt(digits) == '.' || s.charAt(digits) == ')')
                && s.charAt(digits + 1) == ' ') {
            return s.substring(0, digits + 2);
        }
        return null;
    }

    private static void flush(List<String> out, StringBuilder paragraph, String first, String indent, int width) {
        if (paragraph.isEmpty()) {
            return;
        }
        String text = inline(paragraph.toString());
        out.addAll(first == null ? wrap(text, width, "", "") : wrap(text, width, first, indent));
        paragraph.setLength(0);
    }

    /** One blank line, never two, and none at the top. */
    private static void gap(List<String> out) {
        if (!out.isEmpty() && !out.getLast().isEmpty()) {
            out.add("");
        }
    }

    /** A table row's cells: split on the bars that aren't escaped or inside code. */
    private static List<String> cells(String row) {
        List<String> found = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean code = false;
        String body = row.startsWith("|") ? row.substring(1) : row;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\\' && i + 1 < body.length() && body.charAt(i + 1) == '|') {
                cell.append('|');
                i++;
            } else if (c == '`') {
                code = !code;
                cell.append(c);
            } else if (c == '|' && !code) {
                found.add(inline(cell.toString().strip()));
                cell.setLength(0);
            } else {
                cell.append(c);
            }
        }
        if (!cell.toString().isBlank()) {
            found.add(inline(cell.toString().strip()));
        }
        return found;
    }

    private static void table(List<String> out, List<List<String>> rows, int width) {
        if (rows.isEmpty()) {
            return;
        }
        int columns = rows.stream().mapToInt(List::size).max().orElse(0);
        for (List<String> row : rows) {
            while (row.size() < columns) {
                row.add("");
            }
        }
        int[] natural = new int[columns];
        for (List<String> row : rows) {
            for (int c = 0; c < columns; c++) {
                natural[c] = Math.max(natural[c], row.get(c).length());
            }
        }
        int room = width - GAP.length() * (columns - 1);
        int[] widths = fit(natural, room);
        if (widths == null) {
            listed(out, rows, width);
            return;
        }
        boolean wraps = false;
        for (int c = 0; c < columns; c++) {
            wraps |= widths[c] < natural[c];
        }
        for (int r = 0; r < rows.size(); r++) {
            List<List<String>> wrapped = new ArrayList<>();
            int height = 1;
            for (int c = 0; c < columns; c++) {
                List<String> cell = wrap(rows.get(r).get(c), widths[c], "", "");
                if (cell.isEmpty()) {
                    cell = List.of("");
                }
                wrapped.add(cell);
                height = Math.max(height, cell.size());
            }
            for (int h = 0; h < height; h++) {
                StringBuilder line = new StringBuilder();
                for (int c = 0; c < columns; c++) {
                    String part = h < wrapped.get(c).size() ? wrapped.get(c).get(h) : "";
                    line.append(c == columns - 1 ? part : pad(part, widths[c]) + GAP);
                }
                out.add(line.toString().stripTrailing());
            }
            if (r == 0) {
                StringBuilder rule = new StringBuilder();
                for (int c = 0; c < columns; c++) {
                    rule.append("-".repeat(widths[c])).append(c == columns - 1 ? "" : GAP);
                }
                out.add(rule.toString());
            } else if (wraps && r < rows.size() - 1) {
                out.add("");
            }
        }
    }

    /**
     * Column widths within room: each column its natural width if all fit; otherwise the narrow
     * ones keep theirs and the rest share what is left equally. Null if a column would be too narrow.
     */
    private static int[] fit(int[] natural, int room) {
        int columns = natural.length;
        int[] widths = natural.clone();
        int total = 0;
        for (int w : natural) {
            total += w;
        }
        if (total <= room) {
            return widths;
        }
        boolean[] squeezed = new boolean[columns];
        int left = room;
        int sharing = columns;
        boolean changed = true;
        while (changed) {
            changed = false;
            int share = sharing == 0 ? 0 : left / sharing;
            for (int c = 0; c < columns; c++) {
                if (!squeezed[c] && natural[c] > share) {
                    continue;
                }
                if (!squeezed[c] && natural[c] <= share) {
                    squeezed[c] = true;   // fits in its share: keeps its natural width
                    left -= natural[c];
                    sharing--;
                    changed = true;
                }
            }
        }
        if (sharing == 0) {
            return widths;
        }
        int share = left / sharing;
        if (share < MIN_COLUMN) {
            return null;
        }
        int extra = left - share * sharing;
        for (int c = 0; c < columns; c++) {
            if (!squeezed[c]) {
                widths[c] = share + (extra-- > 0 ? 1 : 0);
            }
        }
        return widths;
    }

    /** A table too wide for columns: each row's first cell, then its other cells under their headings. */
    private static void listed(List<String> out, List<List<String>> rows, int width) {
        List<String> head = rows.getFirst();
        for (int r = 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            out.addAll(wrap(row.getFirst(), width, "", "  "));
            for (int c = 1; c < row.size(); c++) {
                if (row.get(c).isEmpty()) {
                    continue;
                }
                String label = c < head.size() && !head.get(c).isEmpty() ? head.get(c) + ": " : "";
                out.addAll(wrap(label + row.get(c), width, "    ", "      "));
            }
            if (r < rows.size() - 1) {
                out.add("");
            }
        }
    }

    /** Drops the inline markers: code backticks, ** and __ for bold, links' brackets (keeping the address). */
    static String inline(String text) {
        StringBuilder out = new StringBuilder();
        String[] parts = text.split("`", -1);
        for (int p = 0; p < parts.length; p++) {
            boolean code = p % 2 == 1 && p < parts.length - 1;
            String part = parts[p];
            if (!code) {
                part = part.replace("**", "").replace("__", "")
                        .replaceAll("!?\\[([^\\]]*)\\]\\(([^)]*)\\)", "$1 ($2)")
                        .replace("<br>", " ").replace("&nbsp;", " ")
                        .replace("\\*", "*").replace("\\_", "_").replace("\\#", "#");
            } else if (p == parts.length - 1) {
                part = "`" + part;
            }
            out.append(part);
        }
        return out.toString();
    }

    /** Words wrapped to width, the first line after first and the rest after indent; long words are broken. */
    static List<String> wrap(String text, int width, String first, String indent) {
        List<String> lines = new ArrayList<>();
        String[] words = text.strip().split(" +");
        StringBuilder line = new StringBuilder(first);
        boolean empty = true;
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (!empty && line.length() + 1 + word.length() > width) {
                lines.add(line.toString());
                line = new StringBuilder(indent);
                empty = true;
            }
            if (!empty) {
                line.append(' ');
            }
            while (line.length() + word.length() > width && width - line.length() > 0) {
                int fits = width - line.length();
                line.append(word, 0, fits);
                lines.add(line.toString());
                word = word.substring(fits);
                line = new StringBuilder(indent);
            }
            line.append(word);
            empty = false;
        }
        if (!empty) {
            lines.add(line.toString());
        }
        return lines;
    }

    private static String pad(String text, int width) {
        return text.length() >= width ? text : text + " ".repeat(width - text.length());
    }
}
