package qbotics.computer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A computer's screen: a fixed grid of characters with a cursor. Lines that scroll off the
 * top are kept as scroll-back, so a player can look at earlier output.
 * Written by the computer's thread and read by the server thread, so every method locks.
 */
public final class Terminal {
    public static final int COLS = 80;
    public static final int ROWS = 14;
    /** How many scrolled-off lines are kept for players who open the screen later. */
    public static final int MAX_SCROLLBACK = 500;
    /** The most scroll-back sent in one update; a program printing faster than this loses the excess for viewers. */
    public static final int MAX_LINES_PER_UPDATE = 200;

    private final char[][] grid = new char[ROWS][COLS];
    private int cursorX;
    private int cursorY;
    private boolean dirty = true;

    private final ArrayDeque<String> scrollback = new ArrayDeque<>();
    /** How many lines have ever scrolled off. The newest scroll-back line has index scrolledTotal - 1. */
    private long scrolledTotal;
    /** Index of the first scrolled-off line not yet handed out by {@link #takeUpdate()}. */
    private long unsentFrom;
    /** Goes up each time the terminal is cleared, telling viewers to drop their scroll-back. */
    private int epoch;

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

    /** Empties the screen and the scroll-back. */
    public synchronized void clear() {
        for (char[] row : grid) {
            Arrays.fill(row, ' ');
        }
        cursorX = 0;
        cursorY = 0;
        scrollback.clear();
        unsentFrom = scrolledTotal;
        epoch++;
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
            scrollback.addLast(new String(top).stripTrailing());
            scrolledTotal++;
            if (scrollback.size() > MAX_SCROLLBACK) {
                scrollback.removeFirst();
            }
            System.arraycopy(grid, 1, grid, 0, ROWS - 1);
            Arrays.fill(top, ' ');
            grid[ROWS - 1] = top;
        }
    }

    /** True if anything changed since the last {@link #takeUpdate()}. */
    public synchronized boolean isDirty() {
        return dirty;
    }

    /** The screen, plus the lines that scrolled off since the last call. For players already watching. */
    public synchronized State takeUpdate() {
        dirty = false;
        long oldest = scrolledTotal - scrollback.size();
        long from = Math.max(Math.max(unsentFrom, oldest), scrolledTotal - MAX_LINES_PER_UPDATE);
        unsentFrom = scrolledTotal;
        return state(from);
    }

    /** The screen and all the scroll-back that is kept. For a player who has just opened the screen. */
    public synchronized State full() {
        return state(scrolledTotal - scrollback.size());
    }

    private State state(long from) {
        List<String> scrolled = new ArrayList<>();
        long index = scrolledTotal - scrollback.size();
        for (String line : scrollback) {
            if (index++ >= from) {
                scrolled.add(line);
            }
        }
        List<String> lines = new ArrayList<>(ROWS);
        for (char[] row : grid) {
            lines.add(new String(row).stripTrailing());
        }
        return new State(epoch, from, scrolled, lines, Math.min(cursorX, COLS - 1), cursorY);
    }

    /**
     * The terminal at one moment. Trailing spaces are trimmed from each line.
     *
     * @param epoch         changes when the terminal was cleared: forget earlier scroll-back
     * @param scrolledStart the index (counting every line that ever scrolled off) of scrolled.get(0)
     * @param scrolled      scroll-back lines, oldest first
     * @param lines         the screen's rows
     */
    public record State(int epoch, long scrolledStart, List<String> scrolled, List<String> lines, int cursorX, int cursorY) {
    }
}
