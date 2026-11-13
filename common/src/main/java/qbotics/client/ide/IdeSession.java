package qbotics.client.ide;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import qbotics.computer.Terminal;
import qbotics.ide.Highlighter.Language;
import qbotics.ide.TextBuffer;

/**
 * What a player has open on one computer: tabs, unsaved edits, the scratchpad, which
 * folders are expanded. It outlives the screen, so closing the screen with Escape and
 * coming back loses nothing (until the game is closed).
 */
final class IdeSession {
    private static final Map<UUID, IdeSession> SESSIONS = new HashMap<>();

    static IdeSession of(UUID deviceId) {
        return SESSIONS.computeIfAbsent(deviceId, IdeSession::new);
    }

    enum Focus { EDITOR, TERMINAL, SCRATCH }

    /** A file or folder in the explorer. */
    static final class Node {
        final String path;
        final String name;
        final boolean folder;
        long size;
        final List<Node> children = new ArrayList<>();

        Node(String path, String name, boolean folder, long size) {
            this.path = path;
            this.name = name;
            this.folder = folder;
            this.size = size;
        }
    }

    /** The find and replace bar of one open file. */
    static final class FindBar {
        boolean open;
        boolean replaceOpen;
        /** True while typing goes into the bar and not into the file. */
        boolean focused;
        boolean caseSensitive;
        /** Which box is being typed in: 0 = find, 1 = replace. */
        int field;
        final StringBuilder query = new StringBuilder();
        final StringBuilder replacement = new StringBuilder();

        // The matches, remembered until the text or the query changes.
        List<qbotics.ide.Finder.Match> matches = List.of();
        int current = -1;
        long matchedVersion = -1;
        String matchedQuery;
        boolean matchedCase;
    }

    /** One open file. */
    static final class Tab {
        String path;
        /** A line (0-based) to bring into view once the file has loaded, or -1. */
        int revealLine = -1;
        /** Null while the file is still loading or if it couldn't be opened. */
        CodeView view;
        /** Why the file couldn't be opened, or null. */
        String problem;
        final FindBar find = new FindBar();

        Tab(String path) {
            this.path = path;
        }
    }

    final UUID deviceId;
    /** True for a drive with no computer behind it (a Programmer's): files only, no terminal, nothing runs. */
    boolean editOnly;
    /** What the title bar calls it: "Computer", or "Programmer". */
    String title = "Computer";
    /** A laptop: it has no settings page (it isn't a block with a name or a login rule). */
    boolean laptop;
    /** Where the program is (sent while the editor is open): its file, line (1-based, 0 none) and whether paused. */
    String traceFile = "";
    int traceLine;
    boolean tracePaused;

    // Explorer
    final Node root = new Node("/", "", true, 0);
    final Set<String> expanded = new HashSet<>(Set.of("/"));
    String selectedPath = "/";
    int explorerScroll;

    // Editor
    final List<Tab> tabs = new ArrayList<>();
    int activeTab = -1;

    // Terminal: the screen comes from the server; the line being typed is local.
    List<String> terminalLines = List.of();
    int terminalCursorX;
    int terminalCursorY;
    /** Lines that have scrolled off the top of the terminal, oldest first. */
    final List<String> terminalScrollback = new ArrayList<>();
    /** The server's count of scrolled-off lines up to the end of terminalScrollback. */
    private long terminalScrollbackEnd;
    private int terminalEpoch = -1;
    /** How many rows the view is scrolled up from the bottom; 0 follows new output. */
    int terminalScroll;
    /** Height of the terminal panel, set by dragging its top edge; 0 = the default. */
    int terminalHeight;
    final StringBuilder terminalInput = new StringBuilder();
    /** Where in the line being typed the cursor is: 0 is before its first character. */
    int terminalInputCursor;
    /** Commands entered earlier, recalled with the Up and Down keys. */
    final List<String> terminalHistory = new ArrayList<>();
    int terminalHistoryIndex;
    /**
     * Text selected in the terminal with the mouse, or null: where the drag began (line,
     * column) and where it is now (line, column). Lines count the scroll-back, then the screen.
     */
    int[] terminalSelection;

    private static final int MAX_CLIENT_SCROLLBACK = 2000;

    /** Takes in a terminal update from the server. */
    void applyTerminal(Terminal.State state) {
        if (state.epoch() != terminalEpoch) {
            // First update, or the terminal was cleared.
            terminalEpoch = state.epoch();
            terminalScrollback.clear();
            terminalSelection = null;
            terminalScrollbackEnd = state.scrolledStart();
            terminalScroll = 0;
        }
        long index = state.scrolledStart();
        for (String line : state.scrolled()) {
            if (index >= terminalScrollbackEnd) {
                terminalScrollback.add(line);
                if (terminalScroll > 0) {
                    terminalScroll++; // keep showing the same lines while more arrive below
                }
            }
            index++;
        }
        terminalScrollbackEnd = Math.max(terminalScrollbackEnd, index);
        if (terminalScrollback.size() > MAX_CLIENT_SCROLLBACK) {
            int dropped = terminalScrollback.size() - MAX_CLIENT_SCROLLBACK;
            terminalScrollback.subList(0, dropped).clear();
            if (terminalSelection != null) {
                // The selected lines have moved up with everything else.
                terminalSelection[0] = Math.max(0, terminalSelection[0] - dropped);
                terminalSelection[2] = Math.max(0, terminalSelection[2] - dropped);
            }
        }
        terminalLines = state.lines();
        terminalCursorX = state.cursorX();
        terminalCursorY = state.cursorY();
    }

    // Scratchpad
    boolean scratchOpen;
    final CodeView scratch = new CodeView(new TextBuffer("print(\"hello world\")"), Language.PYTHON, false);
    String scratchOutput = "";
    boolean scratchOk = true;
    boolean scratchRunning;

    Focus focus = Focus.TERMINAL;

    private IdeSession(UUID deviceId) {
        this.deviceId = deviceId;
    }

    Tab active() {
        return activeTab >= 0 && activeTab < tabs.size() ? tabs.get(activeTab) : null;
    }

    Node find(String path) {
        return find(root, path);
    }

    private static Node find(Node node, String path) {
        if (node.path.equals(path)) {
            return node;
        }
        for (Node child : node.children) {
            if (child.folder ? qbotics.ide.Paths.isInside(path, child.path) : child.path.equals(path)) {
                Node found = find(child, path);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
