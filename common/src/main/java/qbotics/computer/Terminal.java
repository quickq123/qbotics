package qbotics.computer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A computer's screen: a fixed grid of characters with a cursor, scrolling when full.
 * Written by the computer's thread and read by the server thread, so every method locks.
 */
public final class Terminal {
    public static final int COLS = 80;
    public static final int ROWS = 14;

    private final char[][] grid = new char[ROWS][COLS];
    private int cursorX;
    private int cursorY;
    private boolean dirty = true;

    public Terminal() {
        for (char[] row : grid) {
            Arrays.fill(row, ' ');
        }
    }

    public synchronized void write(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\n' -> newLine();
                case '\r' -> cursorX = 0;
                case '\b' -> cursorX = Math.max(0, cursorX - 1);
                case '\t' -> {
                    do {
                        put(' ');
                    } while (cursorX % 4 != 0);
                }
                default -> {
                    if (c >= ' ') {
                        put(c);
                    }
                }
            }
        }
        dirty = true;
    }

    public synchronized void clear() {
        for (char[] row : grid) {
            Arrays.fill(row, ' ');
        }
        cursorX = 0;
        cursorY = 0;
        dirty = true;
    }

    private void put(char c) {
        if (cursorX >= COLS) {
            newLine();
        }
        grid[cursorY][cursorX++] = c;
    }

    private void newLine() {
        cursorX = 0;
        if (cursorY < ROWS - 1) {
            cursorY++;
        } else {
            char[] top = grid[0];
            System.arraycopy(grid, 1, grid, 0, ROWS - 1);
            Arrays.fill(top, ' ');
            grid[ROWS - 1] = top;
        }
    }

    /** True if the screen changed since the last call; clears the flag. */
    public synchronized boolean takeDirty() {
        boolean was = dirty;
        dirty = false;
        return was;
    }

    public synchronized Snapshot snapshot() {
        List<String> lines = new ArrayList<>(ROWS);
        for (char[] row : grid) {
            lines.add(new String(row).stripTrailing());
        }
        return new Snapshot(lines, Math.min(cursorX, COLS - 1), cursorY);
    }

    /** The screen at one moment. Trailing spaces are trimmed from each line. */
    public record Snapshot(List<String> lines, int cursorX, int cursorY) {
    }
}
