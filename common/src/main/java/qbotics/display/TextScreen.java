package qbotics.display;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A Text Monitor's screen: a grid of characters, each with a text and a background colour
 * (design doc §10.1). At text scale 1 each block holds 15 columns and 10 rows; a smaller
 * scale fits more, a larger one less.
 */
public final class TextScreen extends ScreenContent {
    public static final int COLS_PER_BLOCK = 15;
    public static final int ROWS_PER_BLOCK = 10;
    public static final double MIN_SCALE = 0.5;
    public static final double MAX_SCALE = 5;
    private static final int WHITE = 0;
    private static final int BLACK = 15;

    private double scale = 1;
    private int cols;
    private int rows;
    private char[] text;
    /** Per cell: text colour in the high four bits, background in the low four. */
    private byte[] colors;
    /** The version at which each row last changed. */
    private long[] rowVersions;
    private int cursorX;
    private int cursorY;
    private int fg = WHITE;
    private int bg = BLACK;

    public TextScreen() {
        relayout();
    }

    /** One row, as sent to players. */
    public record Row(int index, String text, byte[] colors) {
    }

    /** The size and scale, then the rows changed since a version (all of them for 0). */
    public synchronized List<Row> rowsSince(long since) {
        List<Row> changed = new ArrayList<>();
        for (int y = 0; y < rows; y++) {
            if (rowVersions[y] > since) {
                changed.add(new Row(y, new String(text, y * cols, cols), Arrays.copyOfRange(colors, y * cols, (y + 1) * cols)));
            }
        }
        return changed;
    }

    public synchronized int cols() {
        return cols;
    }

    public synchronized int rows() {
        return rows;
    }

    public synchronized double scale() {
        return scale;
    }

    @Override
    protected void relayout() {
        int newCols = Math.max(1, (int) Math.floor(widthBlocks * COLS_PER_BLOCK / scale));
        int newRows = Math.max(1, (int) Math.floor(heightBlocks * ROWS_PER_BLOCK / scale));
        char[] newText = new char[newCols * newRows];
        byte[] newColors = new byte[newCols * newRows];
        Arrays.fill(newText, ' ');
        Arrays.fill(newColors, cell(WHITE, BLACK));
        // Keep what fits, from the top left.
        if (text != null) {
            for (int y = 0; y < Math.min(rows, newRows); y++) {
                int keep = Math.min(cols, newCols);
                System.arraycopy(text, y * cols, newText, y * newCols, keep);
                System.arraycopy(colors, y * cols, newColors, y * newCols, keep);
            }
        }
        cols = newCols;
        rows = newRows;
        text = newText;
        colors = newColors;
        rowVersions = new long[rows];
        Arrays.fill(rowVersions, version + 1);
        cursorX = Math.min(cursorX, cols);
        cursorY = Math.min(cursorY, rows - 1);
    }

    private static byte cell(int fg, int bg) {
        return (byte) ((fg << 4) | bg);
    }

    private void touched(int y) {
        rowVersions[y] = version + 1;
    }

    /** Puts text at a place without moving the cursor or wrapping; what runs off the right is cut. */
    private void put(int x, int y, String line, int textColor, int background) {
        if (y < 0 || y >= rows) {
            return;
        }
        for (int i = 0; i < line.length(); i++) {
            int at = x + i;
            if (at >= 0 && at < cols) {
                text[y * cols + at] = line.charAt(i);
                colors[y * cols + at] = cell(textColor, background);
            }
        }
        touched(y);
    }

    private void scroll(int lines) {
        if (lines == 0) {
            return;
        }
        char[] newText = new char[text.length];
        byte[] newColors = new byte[colors.length];
        Arrays.fill(newText, ' ');
        Arrays.fill(newColors, cell(fg, bg));
        for (int y = 0; y < rows; y++) {
            int from = y + lines;
            if (from >= 0 && from < rows) {
                System.arraycopy(text, from * cols, newText, y * cols, cols);
                System.arraycopy(colors, from * cols, newColors, y * cols, cols);
            }
        }
        text = newText;
        colors = newColors;
        for (int y = 0; y < rows; y++) {
            touched(y);
        }
    }

    private void newline() {
        cursorX = 0;
        cursorY++;
        if (cursorY >= rows) {
            scroll(cursorY - rows + 1);
            cursorY = rows - 1;
        }
    }

    /** Writes at the cursor and moves it on. A newline starts the next row; with wrap, so does the right edge. */
    private void write(String what, boolean wrap) {
        for (int i = 0; i < what.length(); i++) {
            char c = what.charAt(i);
            if (c == '\n') {
                newline();
                continue;
            }
            if (cursorX >= cols && wrap) {
                newline();
            }
            if (cursorX >= 0 && cursorX < cols && cursorY >= 0 && cursorY < rows) {
                text[cursorY * cols + cursorX] = c;
                colors[cursorY * cols + cursorX] = cell(fg, bg);
                touched(cursorY);
            }
            cursorX++;
        }
    }

    private JsonArray pair(int a, int b) {
        JsonArray pair = new JsonArray();
        pair.add(a);
        pair.add(b);
        return pair;
    }

    @Override
    public synchronized JsonElement op(String method, JsonArray args) {
        JsonElement answer = JsonNull.INSTANCE;
        switch (method) {
            case "size" -> answer = pair(cols, rows);
            case "info" -> {
                JsonObject info = new JsonObject();
                info.addProperty("cols", cols);
                info.addProperty("rows", rows);
                info.addProperty("scale", scale);
                info.addProperty("width", widthBlocks);
                info.addProperty("height", heightBlocks);
                return info;
            }
            case "write" -> write(printable(stringArg(args, 0)), false);
            case "print" -> write(printable(stringArg(args, 0)) + "\n", true);
            case "write_at" -> {
                int x = intArg(args, 0, "x");
                int y = intArg(args, 1, "y");
                put(x, y, printable(stringArg(args, 2)).replace('\n', ' '), color(arg(args, 3), fg), color(arg(args, 4), bg));
            }
            case "cursor" -> {
                cursorX = Math.clamp(intArg(args, 0, "x"), 0, cols);
                cursorY = Math.clamp(intArg(args, 1, "y"), 0, rows - 1);
                return answer;
            }
            case "get_cursor" -> {
                return pair(cursorX, cursorY);
            }
            case "color" -> {
                fg = color(arg(args, 0), fg);
                bg = color(arg(args, 1), bg);
                return answer;
            }
            case "clear" -> {
                bg = color(arg(args, 0), bg);
                Arrays.fill(text, ' ');
                Arrays.fill(colors, cell(fg, bg));
                for (int y = 0; y < rows; y++) {
                    touched(y);
                }
                cursorX = 0;
                cursorY = 0;
            }
            case "clear_line" -> {
                int y = args.size() > 0 && !args.get(0).isJsonNull() ? intArg(args, 0, "y") : cursorY;
                if (y >= 0 && y < rows) {
                    Arrays.fill(text, y * cols, (y + 1) * cols, ' ');
                    Arrays.fill(colors, y * cols, (y + 1) * cols, cell(fg, bg));
                    touched(y);
                }
            }
            case "fill" -> {
                int x0 = intArg(args, 0, "x");
                int y0 = intArg(args, 1, "y");
                int w = intArg(args, 2, "width");
                int h = intArg(args, 3, "height");
                int background = color(arg(args, 4), bg);
                String with = printable(stringArg(args, 5));
                char c = with.isEmpty() ? ' ' : with.charAt(0);
                for (int y = Math.max(0, y0); y < Math.min(rows, y0 + h); y++) {
                    for (int x = Math.max(0, x0); x < Math.min(cols, x0 + w); x++) {
                        text[y * cols + x] = c;
                        colors[y * cols + x] = cell(fg, background);
                    }
                    touched(y);
                }
            }
            case "scroll" -> scroll(intArg(args, 0, "lines"));
            case "scale" -> {
                JsonElement value = arg(args, 0);
                if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                    throw new IllegalArgumentException("The text scale is a number from 0.5 to 5, in steps of 0.5.");
                }
                double wanted = Math.round(value.getAsDouble() * 2) / 2.0;
                if (wanted < MIN_SCALE || wanted > MAX_SCALE) {
                    throw new IllegalArgumentException("The text scale is a number from 0.5 to 5, in steps of 0.5.");
                }
                if (wanted != scale) {
                    scale = wanted;
                    relayout();
                    shapeVersion++;
                }
            }
            case "get_scale" -> {
                return new JsonPrimitive(scale);
            }
            default -> throw new IllegalArgumentException("A text monitor has no " + method + "().");
        }
        version++;
        return answer;
    }

    @Override
    public synchronized JsonObject save() {
        JsonObject saved = new JsonObject();
        saved.addProperty("scale", scale);
        saved.addProperty("cols", cols);
        saved.addProperty("rows", rows);
        saved.addProperty("text", new String(text));
        StringBuilder hex = new StringBuilder(colors.length * 2);
        for (byte b : colors) {
            hex.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        }
        saved.addProperty("colors", hex.toString());
        saved.addProperty("cursor_x", cursorX);
        saved.addProperty("cursor_y", cursorY);
        saved.addProperty("fg", fg);
        saved.addProperty("bg", bg);
        return saved;
    }

    @Override
    public synchronized void load(JsonObject saved) {
        scale = Math.clamp(saved.has("scale") ? saved.get("scale").getAsDouble() : 1, MIN_SCALE, MAX_SCALE);
        int savedCols = saved.has("cols") ? saved.get("cols").getAsInt() : 0;
        int savedRows = saved.has("rows") ? saved.get("rows").getAsInt() : 0;
        String savedText = saved.has("text") ? saved.get("text").getAsString() : "";
        String hex = saved.has("colors") ? saved.get("colors").getAsString() : "";
        if (savedCols > 0 && savedRows > 0 && savedText.length() == savedCols * savedRows && hex.length() == savedText.length() * 2) {
            cols = savedCols;
            rows = savedRows;
            text = savedText.toCharArray();
            colors = new byte[text.length];
            for (int i = 0; i < colors.length; i++) {
                colors[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            }
        }
        cursorX = saved.has("cursor_x") ? saved.get("cursor_x").getAsInt() : 0;
        cursorY = saved.has("cursor_y") ? saved.get("cursor_y").getAsInt() : 0;
        fg = saved.has("fg") ? Math.clamp(saved.get("fg").getAsInt(), 0, 15) : WHITE;
        bg = saved.has("bg") ? Math.clamp(saved.get("bg").getAsInt(), 0, 15) : BLACK;
        relayout(); // fits it to the size the screen has now
        shapeVersion++;
        version++;
    }

    /** The character cell at a point on the screen, given as fractions of its width and height. */
    public synchronized int[] cellAt(double fx, double fy) {
        return new int[] {Math.clamp((int) (fx * cols), 0, cols - 1), Math.clamp((int) (fy * rows), 0, rows - 1)};
    }
}
