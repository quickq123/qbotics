package qbotics.client.ide;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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

    /** One open file. */
    static final class Tab {
        String path;
        /** Null while the file is still loading or if it couldn't be opened. */
        CodeView view;
        /** Why the file couldn't be opened, or null. */
        String problem;

        Tab(String path) {
            this.path = path;
        }
    }

    final UUID deviceId;

    // Explorer
    final Node root = new Node("/", "", true, 0);
    final Set<String> expanded = new HashSet<>(Set.of("/"));
    String selectedPath = "/";
    int explorerScroll;
    boolean filesLoaded;

    // Editor
    final List<Tab> tabs = new ArrayList<>();
    int activeTab = -1;

    // Terminal: the screen comes from the server; the line being typed is local.
    List<String> terminalLines = List.of();
    int terminalCursorX;
    int terminalCursorY;
    final StringBuilder terminalInput = new StringBuilder();
    final List<String> terminalHistory = new ArrayList<>();
    int terminalHistoryIndex;

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
