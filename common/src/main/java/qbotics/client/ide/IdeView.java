package qbotics.client.ide;

import com.mojang.blaze3d.platform.InputConstants;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.input.KeyEvent;
import qbotics.client.ide.IdeSession.Focus;
import qbotics.client.ide.IdeSession.Node;
import qbotics.client.ide.IdeSession.Tab;
import qbotics.client.ide.Ui.Rect;
import qbotics.computer.ServerComputer;
import qbotics.computer.Terminal;
import qbotics.engine.HostFileSystem;
import qbotics.ide.Highlighter.Language;
import qbotics.ide.JsonTools;
import qbotics.ide.Paths;
import qbotics.ide.TextBuffer;
import qbotics.net.IdePayloads;
import qbotics.net.IdePayloads.FileRequest;
import qbotics.net.TerminalInputPayload;

/**
 * A computer's screen: a small code editor laid out like the ones developers use.
 * Files on the left, open files in tabs in the middle, the computer's terminal below
 * them, and an optional Python scratchpad on the right.
 *
 * <p>This class is the whole of the editor's behaviour and appearance, but it knows nothing
 * about Minecraft's screens or renderer: it draws through {@link Ui} and is told about keys
 * and the mouse. {@link IdeScreen} connects it to the game; the offline preview tool draws
 * the same thing to image files.
 */
final class IdeView {
    /** What the editor needs from whatever is showing it. */
    interface Host {
        String clipboard();

        void setClipboard(String text);

        /** Ctrl+Plus and Ctrl+Minus: make everything larger (+1) or smaller (-1). */
        void changeZoom(int by);
    }

    private static final int TITLE_H = 16;
    private static final int STATUS_H = 13;
    private static final int TAB_H = 15;
    private static final int HEADER_H = 14;
    private static final int ROW_H = 11;
    private static final int EXPLORER_W = 160;
    private static final int SCRATCH_W = 236;
    private static final int TERMINAL_H = HEADER_H + Terminal.ROWS * Ui.CELL_H + 6;

    private final IdeSession session;
    private final IdeBackend backend;
    private final Host host;
    /** The drawing surface from the last frame, kept for measuring text between frames. */
    private Ui ui;

    // Layout, recomputed every frame.
    private int viewWidth;
    private int viewHeight;
    private Rect explorerRect;
    private Rect tabsRect;
    private Rect editorRect;
    private Rect terminalRect;
    private Rect scratchRect;
    private Rect scratchCodeRect;

    /** Something clickable drawn this frame. */
    private record Button(Rect rect, Runnable action) {
    }

    private final List<Button> buttons = new ArrayList<>();
    private final List<Node> visibleNodes = new ArrayList<>();
    private final List<Integer> visibleDepths = new ArrayList<>();

    private record MenuItem(String label, Runnable action) {
    }

    /** A right-click menu. */
    private record Menu(int x, int y, List<MenuItem> items) {
    }

    private Menu menu;

    /** A question in the middle of the screen: a text prompt or a confirmation. */
    private static final class Dialog {
        String title;
        String message;
        /** Null for a plain confirmation. */
        StringBuilder input;
        String confirmLabel;
        Consumer<String> onConfirm;
        /** An optional middle choice, such as "Don't Save". */
        String otherLabel;
        Runnable onOther;
    }

    private Dialog dialog;

    private String toast = "";
    private boolean toastIsError;
    private long toastUntil;
    private int blink;
    private double mouseX;
    private double mouseY;

    IdeView(IdeSession session, IdeBackend backend, Host host) {
        this.session = session;
        this.backend = backend;
        this.host = host;
        if (!session.filesLoaded) {
            session.filesLoaded = true;
            loadFolder("/");
        }
    }

    IdeSession session() {
        return session;
    }

    /** Twenty times a second; only the cursor's blinking depends on it. */
    void tick() {
        blink++;
    }

    /** The screen is going away. */
    void closed() {
        backend.terminalInput(TerminalInputPayload.CLOSE, "");
    }

    // ---- layout ------------------------------------------------------------------

    private void layout(int width, int height) {
        viewWidth = width;
        viewHeight = height;

        int bodyY = TITLE_H;
        int bodyH = viewHeight - TITLE_H - STATUS_H;
        explorerRect = new Rect(0, bodyY, EXPLORER_W, bodyH);
        int centerX = EXPLORER_W + 1;
        int centerW = viewWidth - centerX - (session.scratchOpen ? SCRATCH_W + 1 : 0);
        int terminalH = Math.min(TERMINAL_H, bodyH / 2);
        tabsRect = new Rect(centerX, bodyY, centerW, TAB_H);
        editorRect = new Rect(centerX, bodyY + TAB_H, centerW, bodyH - TAB_H - terminalH - 1);
        terminalRect = new Rect(centerX, bodyY + bodyH - terminalH, centerW, terminalH);
        scratchRect = session.scratchOpen ? new Rect(viewWidth - SCRATCH_W, bodyY, SCRATCH_W, bodyH) : new Rect(viewWidth, bodyY, 0, bodyH);
        scratchCodeRect = new Rect(scratchRect.x() + 4, scratchRect.y() + HEADER_H + 16, SCRATCH_W - 8, Math.max(40, bodyH * 2 / 5));
    }

    // ---- drawing -----------------------------------------------------------------

    /** Draws the whole editor into a width x height area, with the mouse at (pointerX, pointerY). */
    void render(Ui surface, int width, int height, double pointerX, double pointerY) {
        ui = surface;
        layout(width, height);
        mouseX = pointerX;
        mouseY = pointerY;
        buttons.clear();
        boolean cursorOn = blink / 10 % 2 == 0;

        drawExplorer(ui);
        drawTabs(ui);
        drawEditor(ui, cursorOn);
        drawTerminal(ui, cursorOn);
        if (session.scratchOpen) {
            drawScratch(ui, cursorOn);
        }
        drawTitleBar(ui);
        drawStatusBar(ui);
        // Thin lines between the panels.
        ui.fill(EXPLORER_W, TITLE_H, 1, viewHeight - TITLE_H - STATUS_H, Ui.BORDER);
        ui.fill(terminalRect.x(), terminalRect.y() - 1, terminalRect.width(), 1, Ui.BORDER);
        if (session.scratchOpen) {
            ui.fill(scratchRect.x() - 1, TITLE_H, 1, viewHeight - TITLE_H - STATUS_H, Ui.BORDER);
        }
        if (menu != null) {
            drawMenu(ui);
        }
        if (dialog != null) {
            drawDialog(ui, cursorOn);
        }
    }

    private boolean hovered(Rect rect) {
        return dialog == null && rect.contains(mouseX, mouseY);
    }

    /** A flat text button; returns the x just past it. */
    private int button(Ui ui, int x, int y, int height, String label, boolean playIcon, boolean enabled, int background, Runnable action) {
        int width = ui.textWidth(label) + 10 + (playIcon ? 8 : 0);
        Rect rect = new Rect(x, y, width, height);
        if (enabled) {
            ui.fill(x, y, width, height, hovered(rect) ? Ui.HOVER : background);
            buttons.add(new Button(rect, action));
        } else {
            ui.fill(x, y, width, height, background);
        }
        int textX = x + 5;
        int color = enabled ? Ui.TEXT : Ui.TEXT_DIM;
        if (playIcon) {
            ui.playIcon(textX, y + (height - 7) / 2, enabled ? Ui.OK_GREEN : Ui.TEXT_DIM);
            textX += 8;
        }
        ui.text(label, textX, y + (height - 8) / 2, color);
        return x + width;
    }

    private void drawTitleBar(Ui ui) {
        ui.fill(0, 0, viewWidth, TITLE_H, Ui.BAR_BG);
        ui.text("QBOTICS", 6, 4, Ui.TEXT_BRIGHT);
        ui.text("Computer", 6 + ui.textWidth("QBOTICS") + 6, 4, Ui.TEXT_DIM);

        Tab tab = session.active();
        boolean hasFile = tab != null && tab.view != null;
        boolean isPython = hasFile && tab.view.language == Language.PYTHON;
        boolean isJson = hasFile && tab.view.language == Language.JSON;
        // Laid out from the right edge.
        String scratchLabel = session.scratchOpen ? "Hide Scratchpad" : "Python Scratchpad";
        int x = viewWidth - 4 - (ui.textWidth(scratchLabel) + 10);
        button(ui, x, 1, TITLE_H - 2, scratchLabel, false, true, session.scratchOpen ? Ui.ACCENT : Ui.BAR_BG, this::toggleScratch);
        x -= ui.textWidth("Save") + 10 + 4;
        button(ui, x, 1, TITLE_H - 2, "Save", false, hasFile && tab.view.buffer.isDirty(), Ui.BAR_BG, this::saveActive);
        if (isJson) {
            x -= ui.textWidth("Format JSON") + 10 + 4;
            button(ui, x, 1, TITLE_H - 2, "Format JSON", false, true, Ui.BAR_BG, this::formatActive);
        }
        x -= ui.textWidth("Run") + 18 + 4;
        button(ui, x, 1, TITLE_H - 2, "Run", true, isPython, Ui.BAR_BG, this::runActive);
    }

    private void drawStatusBar(Ui ui) {
        int y = viewHeight - STATUS_H;
        boolean showToast = System.currentTimeMillis() < toastUntil;
        Tab tab = session.active();
        JsonTools.Result problem = tab != null && tab.view != null ? tab.view.jsonProblem() : null;
        ui.fill(0, y, viewWidth, STATUS_H, showToast && toastIsError ? 0xFFA1260D : Ui.STATUS_BG);

        String left;
        if (showToast) {
            left = toast;
        } else if (problem != null) {
            left = "JSON problem on line " + problem.line() + ": " + problem.error();
        } else {
            left = "F5 Run   Ctrl+S Save   Ctrl+/ Comment   Ctrl+Plus / Ctrl+Minus Text size   Esc Close";
        }
        String right = "";
        if (tab != null && tab.view != null) {
            TextBuffer buffer = tab.view.buffer;
            right = "Ln " + (buffer.cursorLine() + 1) + ", Col " + (buffer.cursorColumn() + 1)
                    + "    Spaces: " + TextBuffer.INDENT + "    " + tab.view.language.label
                    + (buffer.isDirty() ? "    Unsaved" : "    Saved");
        }
        int rightWidth = ui.textWidth(right);
        ui.text(ui.fit(left, viewWidth - rightWidth - 24), 6, y + 3, Ui.TEXT_BRIGHT);
        ui.text(right, viewWidth - rightWidth - 6, y + 3, Ui.TEXT_BRIGHT);
    }

    // ---- explorer ----------------------------------------------------------------

    private void drawExplorer(Ui ui) {
        Rect r = explorerRect;
        ui.fill(r.x(), r.y(), r.width(), r.height(), Ui.SIDE_BG);
        ui.text("EXPLORER", r.x() + 6, r.y() + 3, Ui.TEXT_DIM);
        int x = r.right() - 4 - (ui.textWidth("+ Folder") + 10);
        button(ui, x, r.y() + 1, HEADER_H - 2, "+ Folder", false, true, Ui.SIDE_BG, () -> promptNewFolder(folderForNew()));
        x -= ui.textWidth("+ File") + 10;
        button(ui, x, r.y() + 1, HEADER_H - 2, "+ File", false, true, Ui.SIDE_BG, () -> promptNewFile(folderForNew()));

        visibleNodes.clear();
        visibleDepths.clear();
        collectVisible(session.root, 0);

        int listY = r.y() + HEADER_H;
        int listH = r.height() - HEADER_H;
        int maxScroll = Math.max(0, visibleNodes.size() * ROW_H - listH);
        session.explorerScroll = Math.clamp(session.explorerScroll, 0, maxScroll);
        ui.clip(r.x(), listY, r.width(), listH);
        if (visibleNodes.isEmpty()) {
            ui.text("No files yet.", r.x() + 8, listY + 6, Ui.TEXT_DIM);
            ui.text("Use + File to make one.", r.x() + 8, listY + 18, Ui.TEXT_DIM);
        }
        for (int i = 0; i < visibleNodes.size(); i++) {
            Node node = visibleNodes.get(i);
            int y = listY + i * ROW_H - session.explorerScroll;
            if (y + ROW_H < listY || y > listY + listH) {
                continue;
            }
            Rect row = new Rect(r.x(), y, r.width(), ROW_H);
            Tab active = session.active();
            if (active != null && active.path.equals(node.path)) {
                ui.fill(row.x(), row.y(), row.width(), ROW_H, Ui.ACTIVE_ROW);
            } else if (node.path.equals(session.selectedPath)) {
                ui.fill(row.x(), row.y(), row.width(), ROW_H, Ui.HOVER);
            } else if (hovered(row) && menu == null) {
                ui.fill(row.x(), row.y(), row.width(), ROW_H, Ui.LINE_HIGHLIGHT);
            }
            int indent = r.x() + 6 + visibleDepths.get(i) * 8;
            if (node.folder) {
                ui.arrow(indent, y + 3, session.expanded.contains(node.path), Ui.TEXT);
                ui.text(ui.fit(node.name, r.right() - indent - 12), indent + 8, y + 2, Ui.FOLDER);
            } else {
                ui.text(ui.fit(node.name, r.right() - indent - 12), indent + 8, y + 2, fileColor(node.name));
            }
        }
        ui.unclip();
    }

    private static int fileColor(String name) {
        return switch (Language.forFile(name)) {
            case PYTHON -> 0xFF8FC7F0;
            case JSON -> 0xFFE8D06B;
            case PLAIN -> Ui.TEXT;
        };
    }

    private void collectVisible(Node folder, int depth) {
        for (Node child : folder.children) {
            visibleNodes.add(child);
            visibleDepths.add(depth);
            if (child.folder && session.expanded.contains(child.path)) {
                collectVisible(child, depth + 1);
            }
        }
    }

    private Node nodeAt(double y) {
        int index = (int) Math.floor((y - explorerRect.y() - HEADER_H + session.explorerScroll) / ROW_H);
        return y >= explorerRect.y() + HEADER_H && index >= 0 && index < visibleNodes.size() ? visibleNodes.get(index) : null;
    }

    /** New files go in the selected folder, or beside the selected file. */
    private String folderForNew() {
        Node selected = session.find(session.selectedPath);
        if (selected == null) {
            return "/";
        }
        return selected.folder ? selected.path : Paths.parent(selected.path);
    }

    /** Asks the server for a folder's contents and updates the tree. */
    private void loadFolder(String path) {
        backend.file(FileRequest.LIST, path, new byte[0], (errno, data) -> {
            Node folder = session.find(path);
            if (folder == null || !folder.folder) {
                return;
            }
            if (errno != 0) {
                toast(describe(errno, path), true);
                return;
            }
            List<Node> fresh = new ArrayList<>();
            for (String entry : new String(data, StandardCharsets.UTF_8).split("\n")) {
                String[] fields = entry.split("\t");
                if (fields.length == 3) {
                    boolean isFolder = fields[0].equals("d");
                    String childPath = Paths.join(path, fields[2]);
                    Node child = new Node(childPath, fields[2], isFolder, Long.parseLong(fields[1]));
                    // Keep what was already loaded below a folder that is still there.
                    for (Node old : folder.children) {
                        if (old.path.equals(childPath) && old.folder == isFolder) {
                            child.children.addAll(old.children);
                        }
                    }
                    fresh.add(child);
                }
            }
            fresh.sort(Comparator.comparing((Node n) -> !n.folder).thenComparing(n -> n.name.toLowerCase(java.util.Locale.ROOT)));
            folder.children.clear();
            folder.children.addAll(fresh);
            for (Node child : fresh) {
                if (child.folder && session.expanded.contains(child.path)) {
                    loadFolder(child.path);
                }
            }
        });
    }

    private void openMenuFor(Node node, int x, int y) {
        List<MenuItem> items = new ArrayList<>();
        if (node == null) {
            items.add(new MenuItem("New File...", () -> promptNewFile("/")));
            items.add(new MenuItem("New Folder...", () -> promptNewFolder("/")));
            items.add(new MenuItem("Refresh", () -> loadFolder("/")));
        } else if (node.folder) {
            items.add(new MenuItem("New File...", () -> promptNewFile(node.path)));
            items.add(new MenuItem("New Folder...", () -> promptNewFolder(node.path)));
            items.add(new MenuItem("Rename...", () -> promptRename(node)));
            items.add(new MenuItem("Delete", () -> confirmDelete(node)));
        } else {
            items.add(new MenuItem("Open", () -> openFile(node.path)));
            if (Language.forFile(node.name) == Language.PYTHON) {
                items.add(new MenuItem("Run", () -> backend.terminalInput(TerminalInputPayload.RUN_FILE, node.path)));
            }
            items.add(new MenuItem("Rename...", () -> promptRename(node)));
            items.add(new MenuItem("Delete", () -> confirmDelete(node)));
        }
        menu = new Menu(x, y, items);
    }

    private Rect menuRect(Ui ui) {
        int width = 60;
        for (MenuItem item : menu.items()) {
            width = Math.max(width, ui.textWidth(item.label()) + 20);
        }
        int height = menu.items().size() * 13 + 4;
        return new Rect(Math.min(menu.x(), viewWidth - width - 2), Math.min(menu.y(), viewHeight - height - 2), width, height);
    }

    private void drawMenu(Ui ui) {
        Rect r = menuRect(ui);
        ui.fill(r.x() + 2, r.y() + 2, r.width(), r.height(), Ui.SHADOW);
        ui.fill(r.x(), r.y(), r.width(), r.height(), Ui.MENU_BG);
        ui.outline(r.x(), r.y(), r.width(), r.height(), Ui.BORDER);
        for (int i = 0; i < menu.items().size(); i++) {
            Rect row = new Rect(r.x() + 1, r.y() + 2 + i * 13, r.width() - 2, 13);
            if (row.contains(mouseX, mouseY)) {
                ui.fill(row.x(), row.y(), row.width(), row.height(), Ui.ACTIVE_ROW);
            }
            ui.text(menu.items().get(i).label(), row.x() + 9, row.y() + 3, Ui.TEXT);
        }
    }

    // ---- file actions ------------------------------------------------------------

    private static String describe(int errno, String path) {
        return ServerComputer.describe(new HostFileSystem.FsException(errno), Paths.name(path));
    }

    private void toast(String message, boolean error) {
        toast = message;
        toastIsError = error;
        toastUntil = System.currentTimeMillis() + 6000;
    }

    private void ask(String title, String message, String initial, String confirmLabel, Consumer<String> onConfirm) {
        dialog = new Dialog();
        dialog.title = title;
        dialog.message = message;
        dialog.input = initial == null ? null : new StringBuilder(initial);
        dialog.confirmLabel = confirmLabel;
        dialog.onConfirm = onConfirm;
    }

    private void promptNewFile(String folder) {
        ask("New File", "Name for the new file in " + folder + "  (for Python, end it with .py)", "", "Create", name -> {
            String path = Paths.join(folder, name.strip());
            backend.file(FileRequest.CREATE, path, new byte[0], (errno, data) -> {
                if (errno != 0) {
                    toast(describe(errno, path), true);
                    return;
                }
                session.expanded.add(folder);
                loadFolder(folder);
                openFile(path);
            });
        });
    }

    private void promptNewFolder(String folder) {
        ask("New Folder", "Name for the new folder in " + folder, "", "Create", name -> {
            String path = Paths.join(folder, name.strip());
            backend.file(FileRequest.MKDIR, path, new byte[0], (errno, data) -> {
                if (errno != 0) {
                    toast(describe(errno, path), true);
                    return;
                }
                session.expanded.add(folder);
                session.expanded.add(path);
                session.selectedPath = path;
                loadFolder(folder);
            });
        });
    }

    private void promptRename(Node node) {
        ask("Rename", "New name for " + node.name, node.name, "Rename", name -> {
            String target = Paths.join(Paths.parent(node.path), name.strip());
            if (target.equals(node.path)) {
                return;
            }
            backend.file(FileRequest.RENAME, node.path, target.getBytes(StandardCharsets.UTF_8), (errno, data) -> {
                if (errno != 0) {
                    toast(describe(errno, node.path), true);
                    return;
                }
                // Open tabs follow the file (or everything in the folder) to its new name.
                for (Tab tab : session.tabs) {
                    if (Paths.isInside(tab.path, node.path)) {
                        tab.path = target + tab.path.substring(node.path.length());
                    }
                }
                if (session.expanded.remove(node.path)) {
                    session.expanded.add(target);
                }
                session.selectedPath = target;
                loadFolder(Paths.parent(node.path));
            });
        });
    }

    private void confirmDelete(Node node) {
        String what = node.folder ? "the folder " + node.name + " and everything in it" : node.name;
        ask("Delete", "Delete " + what + "? This can't be undone.", null, "Delete", ignored ->
                backend.file(FileRequest.DELETE, node.path, new byte[0], (errno, data) -> {
                    if (errno != 0) {
                        toast(describe(errno, node.path), true);
                        return;
                    }
                    for (int i = session.tabs.size() - 1; i >= 0; i--) {
                        if (Paths.isInside(session.tabs.get(i).path, node.path)) {
                            removeTab(i);
                        }
                    }
                    session.selectedPath = Paths.parent(node.path);
                    loadFolder(Paths.parent(node.path));
                }));
    }

    /** Opens a file in a tab, or switches to its tab if it is already open. */
    void openFile(String path) {
        session.focus = Focus.EDITOR;
        session.selectedPath = path;
        for (int i = 0; i < session.tabs.size(); i++) {
            if (session.tabs.get(i).path.equals(path)) {
                session.activeTab = i;
                return;
            }
        }
        Tab tab = new Tab(path);
        session.tabs.add(tab);
        session.activeTab = session.tabs.size() - 1;
        backend.file(FileRequest.READ, path, new byte[0], (errno, data) -> {
            if (errno == HostFileSystem.ENOENT) {
                // "edit new.py" in the terminal: start an empty file; saving creates it.
                tab.view = new CodeView(new TextBuffer(""), Language.forFile(Paths.name(tab.path)), true);
            } else if (errno == HostFileSystem.EFBIG) {
                tab.problem = "This file is too large for the editor (the limit is " + IdePayloads.MAX_FILE_BYTES / 1000 + " KB).";
            } else if (errno != 0) {
                tab.problem = describe(errno, path);
            } else {
                tab.view = new CodeView(new TextBuffer(new String(data, StandardCharsets.UTF_8)), Language.forFile(Paths.name(tab.path)), true);
            }
        });
    }

    private void saveActive() {
        save(session.active(), null);
    }

    private void save(Tab tab, Runnable afterwards) {
        if (tab == null || tab.view == null) {
            return;
        }
        TextBuffer buffer = tab.view.buffer;
        byte[] bytes = buffer.text().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > IdePayloads.MAX_FILE_BYTES) {
            toast("This file is too large to save from the editor (the limit is " + IdePayloads.MAX_FILE_BYTES / 1000 + " KB).", true);
            return;
        }
        long version = buffer.version();
        backend.file(FileRequest.WRITE, tab.path, bytes, (errno, data) -> {
            if (errno != 0) {
                toast("Could not save: " + describe(errno, tab.path), true);
                return;
            }
            buffer.markSaved(version);
            toast("Saved " + tab.path, false);
            loadFolder(Paths.parent(tab.path));
            if (afterwards != null) {
                afterwards.run();
            }
        });
    }

    /** Saves the file if needed, then runs it in the terminal. */
    private void runActive() {
        Tab tab = session.active();
        if (tab == null || tab.view == null || tab.view.language != Language.PYTHON) {
            toast("Open a Python (.py) file to run it.", true);
            return;
        }
        Runnable run = () -> backend.terminalInput(TerminalInputPayload.RUN_FILE, tab.path);
        if (tab.view.buffer.isDirty()) {
            save(tab, run);
        } else {
            run.run();
        }
    }

    private void formatActive() {
        Tab tab = session.active();
        if (tab == null || tab.view == null || tab.view.language != Language.JSON) {
            return;
        }
        JsonTools.Result result = JsonTools.format(tab.view.buffer.text(), 2);
        if (result.ok()) {
            tab.view.buffer.replaceAll(result.formatted().stripTrailing());
            toast("Formatted.", false);
        } else {
            toast("Can't format: line " + result.line() + ": " + result.error(), true);
        }
    }

    private void closeTab(int index) {
        Tab tab = session.tabs.get(index);
        if (tab.view != null && tab.view.buffer.isDirty()) {
            ask("Unsaved Changes", "Save the changes to " + Paths.name(tab.path) + " before closing it?", null, "Save",
                    ignored -> save(tab, () -> removeTab(session.tabs.indexOf(tab))));
            dialog.otherLabel = "Don't Save";
            dialog.onOther = () -> removeTab(session.tabs.indexOf(tab));
        } else {
            removeTab(index);
        }
    }

    private void removeTab(int index) {
        if (index < 0) {
            return;
        }
        session.tabs.remove(index);
        if (session.activeTab >= index) {
            session.activeTab = Math.max(session.tabs.isEmpty() ? -1 : 0, session.activeTab - 1);
        }
    }

    // ---- tabs and editor ---------------------------------------------------------

    private int tabWidth(Ui ui, Tab tab) {
        return ui.textWidth(Paths.name(tab.path)) + 30;
    }

    private void drawTabs(Ui ui) {
        Rect r = tabsRect;
        ui.fill(r.x(), r.y(), r.width(), r.height(), Ui.SIDE_BG);
        ui.clip(r.x(), r.y(), r.width(), r.height());
        int x = r.x();
        for (int i = 0; i < session.tabs.size(); i++) {
            Tab tab = session.tabs.get(i);
            int width = tabWidth(ui, tab);
            boolean active = i == session.activeTab;
            ui.fill(x, r.y(), width, r.height(), active ? Ui.EDITOR_BG : Ui.TAB_BG);
            if (active) {
                ui.fill(x, r.y(), width, 1, Ui.STATUS_BG);
            }
            ui.text(Paths.name(tab.path), x + 8, r.y() + 4, active ? Ui.TEXT_BRIGHT : Ui.TEXT_DIM);
            boolean dirty = tab.view != null && tab.view.buffer.isDirty();
            Rect close = new Rect(x + width - 15, r.y() + 2, 11, 11);
            if (hovered(close)) {
                ui.fill(close.x(), close.y(), close.width(), close.height(), Ui.HOVER);
                ui.text("x", close.x() + 3, close.y() + 1, Ui.TEXT_BRIGHT);
            } else if (dirty) {
                ui.fill(close.x() + 3, close.y() + 3, 5, 5, Ui.TEXT); // unsaved marker
            } else {
                ui.text("x", close.x() + 3, close.y() + 1, Ui.TEXT_DIM);
            }
            ui.fill(x + width, r.y(), 1, r.height(), Ui.SIDE_BG);
            x += width + 1;
        }
        ui.unclip();
    }

    private void drawEditor(Ui ui, boolean cursorOn) {
        Rect r = editorRect;
        Tab tab = session.active();
        if (tab != null && tab.view != null) {
            tab.view.render(ui, r, session.focus == Focus.EDITOR && dialog == null, cursorOn);
            return;
        }
        ui.fill(r.x(), r.y(), r.width(), r.height(), Ui.EDITOR_BG);
        String[] lines;
        if (tab != null && tab.problem != null) {
            lines = new String[] {tab.problem};
        } else if (tab != null) {
            lines = new String[] {"Opening " + tab.path + " ..."};
        } else {
            lines = new String[] {
                "Click a file in the Explorer to open it here.",
                "Use + File, or right-click in the Explorer, to make a new one.",
                "",
                "The terminal below is this computer. Type help there for its commands.",
                "Python Scratchpad (top right) is a place to try a few lines of Python.",
            };
        }
        int y = r.y() + r.height() / 2 - lines.length * 6;
        for (String line : lines) {
            ui.text(line, r.x() + (r.width() - ui.textWidth(line)) / 2, y, tab != null && tab.problem != null ? Ui.ERROR : Ui.TEXT_DIM);
            y += 12;
        }
    }

    // ---- terminal ----------------------------------------------------------------

    private void drawTerminal(Ui ui, boolean cursorOn) {
        Rect r = terminalRect;
        boolean focused = session.focus == Focus.TERMINAL && dialog == null;
        ui.fill(r.x(), r.y(), r.width(), r.height(), Ui.EDITOR_BG);
        ui.text("TERMINAL", r.x() + 8, r.y() + 3, focused ? Ui.TEXT_BRIGHT : Ui.TEXT_DIM);
        if (focused) {
            ui.fill(r.x() + 8, r.y() + HEADER_H - 2, ui.textWidth("TERMINAL"), 1, Ui.STATUS_BG);
        }
        String hint = "Ctrl+C stops a program";
        ui.text(hint, r.right() - ui.textWidth(hint) - 8, r.y() + 3, Ui.TEXT_DIM);

        int left = r.x() + 8;
        int top = r.y() + HEADER_H + 2;
        ui.clip(r.x(), top, r.width(), r.height() - HEADER_H - 2);
        List<String> lines = session.terminalLines;
        for (int row = 0; row < lines.size() && row < Terminal.ROWS; row++) {
            ui.mono(lines.get(row), left, top + row * Ui.CELL_H, Ui.TEXT);
        }
        // The line being typed starts at the server's cursor, just after the prompt.
        int room = Math.max(1, Terminal.COLS - session.terminalCursorX - 1);
        StringBuilder input = session.terminalInput;
        String shown = input.length() <= room ? input.toString() : input.substring(input.length() - room);
        int inputX = left + session.terminalCursorX * Ui.CELL_W;
        int inputY = top + session.terminalCursorY * Ui.CELL_H;
        ui.mono(shown, inputX, inputY, Ui.TEXT_BRIGHT);
        if (focused && cursorOn) {
            ui.fill(inputX + shown.length() * Ui.CELL_W, inputY, Ui.CELL_W - 1, Ui.CELL_H - 1, 0xC0D4D4D4);
        }
        ui.unclip();
    }

    private void terminalKey(KeyEvent event) {
        StringBuilder input = session.terminalInput;
        if (event.hasControlDown() && event.key() == InputConstants.KEY_C) {
            input.setLength(0);
            backend.terminalInput(TerminalInputPayload.INTERRUPT, "");
        } else if (event.isPaste()) {
            // Pasted text may hold several lines: each complete line is entered.
            String[] parts = host.clipboard().replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
            for (int i = 0; i < parts.length; i++) {
                terminalAppend(parts[i].replace("\t", "    "));
                if (i < parts.length - 1) {
                    terminalEnter();
                }
            }
        } else {
            switch (event.key()) {
                case InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> terminalEnter();
                case InputConstants.KEY_BACKSPACE -> {
                    if (!input.isEmpty()) {
                        input.setLength(input.length() - 1);
                    }
                }
                case InputConstants.KEY_UP -> terminalRecall(-1);
                case InputConstants.KEY_DOWN -> terminalRecall(1);
                default -> {
                }
            }
        }
    }

    private void terminalAppend(String text) {
        int room = TerminalInputPayload.MAX_LINE_LENGTH - session.terminalInput.length();
        session.terminalInput.append(text, 0, Math.min(room, text.length()));
    }

    private void terminalEnter() {
        String line = session.terminalInput.toString();
        session.terminalInput.setLength(0);
        List<String> history = session.terminalHistory;
        if (!line.isBlank() && (history.isEmpty() || !history.getLast().equals(line))) {
            history.add(line);
        }
        session.terminalHistoryIndex = history.size();
        backend.terminalInput(TerminalInputPayload.LINE, line);
    }

    private void terminalRecall(int direction) {
        List<String> history = session.terminalHistory;
        if (history.isEmpty()) {
            return;
        }
        session.terminalHistoryIndex = Math.clamp(session.terminalHistoryIndex + direction, 0, history.size());
        session.terminalInput.setLength(0);
        if (session.terminalHistoryIndex < history.size()) {
            session.terminalInput.append(history.get(session.terminalHistoryIndex));
        }
    }

    // ---- scratchpad --------------------------------------------------------------

    private void toggleScratch() {
        session.scratchOpen = !session.scratchOpen;
        session.focus = session.scratchOpen ? Focus.SCRATCH : Focus.EDITOR;
    }

    private void runScratch() {
        String code = session.scratch.buffer.text();
        if (session.scratchRunning || code.isBlank()) {
            return;
        }
        if (code.length() > IdePayloads.MAX_SCRATCH_CODE) {
            toast("That is too much code for the scratchpad. Put it in a file instead.", true);
            return;
        }
        session.scratchRunning = true;
        backend.runScratch(code, (ok, output) -> {
            session.scratchRunning = false;
            session.scratchOk = ok;
            session.scratchOutput = output.isEmpty() ? (ok ? "(ran with no output)" : "(stopped)") : output;
        });
    }

    private void drawScratch(Ui ui, boolean cursorOn) {
        Rect r = scratchRect;
        boolean focused = session.focus == Focus.SCRATCH && dialog == null;
        ui.fill(r.x(), r.y(), r.width(), r.height(), Ui.SIDE_BG);
        ui.text("PYTHON SCRATCHPAD", r.x() + 6, r.y() + 3, focused ? Ui.TEXT_BRIGHT : Ui.TEXT_DIM);

        int y = r.y() + HEADER_H;
        int x = button(ui, r.x() + 4, y, 13, session.scratchRunning ? "Running..." : "Run", true, !session.scratchRunning, Ui.ACCENT, this::runScratch);
        button(ui, x + 4, y, 13, "Clear Output", false, !session.scratchOutput.isEmpty(), Ui.SIDE_BG, () -> session.scratchOutput = "");
        String hint = "Ctrl+Enter";
        ui.text(hint, r.right() - ui.textWidth(hint) - 6, y + 3, Ui.TEXT_DIM);

        Rect code = scratchCodeRect;
        ui.outline(code.x() - 1, code.y() - 1, code.width() + 2, code.height() + 2, focused ? Ui.STATUS_BG : Ui.BORDER);
        session.scratch.render(ui, code, focused, cursorOn);

        int outY = code.bottom() + 6;
        ui.text("OUTPUT", r.x() + 6, outY, Ui.TEXT_DIM);
        outY += 12;
        int columns = Math.max(8, (r.width() - 12) / Ui.CELL_W);
        ui.clip(r.x(), outY, r.width(), r.bottom() - outY);
        int color = session.scratchOk ? Ui.TEXT : Ui.ERROR;
        if (session.scratchOutput.isEmpty() && !session.scratchRunning) {
            ui.text("Type Python above and press Run.", r.x() + 6, outY, Ui.TEXT_DIM);
            ui.text("It shares variables with the", r.x() + 6, outY + 11, Ui.TEXT_DIM);
            ui.text("terminal's Python.", r.x() + 6, outY + 22, Ui.TEXT_DIM);
        }
        int lineY = outY;
        for (String line : session.scratchOutput.split("\n", -1)) {
            // Long lines wrap onto the next row.
            for (int start = 0; start == 0 || start < line.length(); start += columns) {
                ui.mono(line, start, Math.min(line.length(), start + columns), r.x() + 6, lineY, color);
                lineY += Ui.CELL_H;
            }
        }
        ui.unclip();
    }

    // ---- dialog ------------------------------------------------------------------

    private Rect dialogRect() {
        int width = Math.min(360, viewWidth - 20);
        int height = dialog.input != null ? 78 : 62;
        return new Rect((viewWidth - width) / 2, Math.max(4, viewHeight / 3 - height / 2), width, height);
    }

    private void drawDialog(Ui ui, boolean cursorOn) {
        ui.fill(0, 0, viewWidth, viewHeight, 0x70000000);
        Rect r = dialogRect();
        ui.fill(r.x() + 3, r.y() + 3, r.width(), r.height(), Ui.SHADOW);
        ui.fill(r.x(), r.y(), r.width(), r.height(), Ui.MENU_BG);
        ui.outline(r.x(), r.y(), r.width(), r.height(), Ui.STATUS_BG);
        ui.text(dialog.title, r.x() + 10, r.y() + 8, Ui.TEXT_BRIGHT);
        ui.text(ui.fit(dialog.message, r.width() - 20), r.x() + 10, r.y() + 22, Ui.TEXT);
        int y = r.y() + 36;
        if (dialog.input != null) {
            ui.fill(r.x() + 10, y, r.width() - 20, 14, Ui.EDITOR_BG);
            ui.outline(r.x() + 10, y, r.width() - 20, 14, Ui.STATUS_BG);
            String typed = dialog.input.toString();
            ui.text(typed, r.x() + 14, y + 3, Ui.TEXT_BRIGHT);
            if (cursorOn) {
                ui.fill(r.x() + 14 + ui.textWidth(typed), y + 2, 1, 10, Ui.TEXT_BRIGHT);
            }
            y += 20;
        }
        // Buttons, right to left. The mouse works here even though the dialog is modal.
        int x = r.right() - 10;
        x = dialogButton(ui, x, y, "Cancel  (Esc)", Ui.BAR_BG, () -> dialog = null);
        if (dialog.otherLabel != null) {
            x = dialogButton(ui, x, y, dialog.otherLabel, Ui.BAR_BG, () -> {
                Runnable other = dialog.onOther;
                dialog = null;
                other.run();
            });
        }
        dialogButton(ui, x, y, dialog.confirmLabel + "  (Enter)", Ui.ACCENT, this::confirmDialog);
    }

    private int dialogButton(Ui ui, int right, int y, String label, int background, Runnable action) {
        int width = ui.textWidth(label) + 12;
        Rect rect = new Rect(right - width, y, width, 14);
        ui.fill(rect.x(), rect.y(), rect.width(), rect.height(), rect.contains(mouseX, mouseY) ? Ui.HOVER : background);
        ui.text(label, rect.x() + 6, rect.y() + 3, Ui.TEXT_BRIGHT);
        buttons.add(new Button(rect, action));
        return rect.x() - 6;
    }

    private void confirmDialog() {
        Dialog current = dialog;
        String value = current.input == null ? "" : current.input.toString();
        if (current.input != null && value.isBlank()) {
            return;
        }
        dialog = null;
        current.onConfirm.accept(value);
    }

    // ---- input -------------------------------------------------------------------

    void charTyped(int codepoint) {
        blink = 0;
        if (codepoint < ' ' || codepoint == 127 || !Character.isBmpCodePoint(codepoint)) {
            return;
        }
        if (dialog != null) {
            if (dialog.input != null && dialog.input.length() < 64) {
                dialog.input.append((char) codepoint);
            }
            return;
        }
        switch (session.focus) {
            case EDITOR -> {
                Tab tab = session.active();
                if (tab != null && tab.view != null) {
                    tab.view.charTyped(codepoint);
                }
            }
            case TERMINAL -> terminalAppend(String.valueOf((char) codepoint));
            case SCRATCH -> session.scratch.charTyped(codepoint);
        }
    }

    /** Returns false only for an Escape that nothing here used: the screen should close. */
    boolean keyPressed(KeyEvent event) {
        blink = 0;
        boolean control = event.hasControlDown();
        int key = event.key();

        if (dialog != null) {
            if (event.isEscape()) {
                dialog = null;
            } else if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) {
                confirmDialog();
            } else if (key == InputConstants.KEY_BACKSPACE && dialog.input != null && !dialog.input.isEmpty()) {
                dialog.input.setLength(dialog.input.length() - 1);
            }
            return true;
        }
        if (menu != null) {
            menu = null;
            if (event.isEscape()) {
                return true;
            }
        }
        if (event.isEscape()) {
            return false; // the session keeps everything, so closing loses nothing
        }

        // Shortcuts that work wherever the cursor is.
        if (control && key == InputConstants.KEY_S) {
            saveActive();
            return true;
        }
        if (key == InputConstants.KEY_F5) {
            runActive();
            return true;
        }
        if (control && key == InputConstants.KEY_W && session.active() != null) {
            closeTab(session.activeTab);
            return true;
        }
        if (event.hasShiftDown() && event.hasAltDown() && key == InputConstants.KEY_F) {
            formatActive();
            return true;
        }
        if (control && (key == InputConstants.KEY_EQUALS || key == InputConstants.KEY_ADD)) {
            host.changeZoom(1);
            return true;
        }
        if (control && (key == InputConstants.KEY_MINUS || key == KEY_NUMPAD_MINUS)) {
            host.changeZoom(-1);
            return true;
        }

        switch (session.focus) {
            case EDITOR -> {
                Tab tab = session.active();
                if (tab != null && tab.view != null) {
                    tab.view.keyPressed(event, host);
                }
            }
            case TERMINAL -> terminalKey(event);
            case SCRATCH -> {
                if (control && (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER)) {
                    runScratch();
                } else {
                    session.scratch.keyPressed(event, host);
                }
            }
        }
        return true;
    }

    private static final int KEY_NUMPAD_MINUS = 333;
    static final int LEFT_BUTTON = 0;
    static final int RIGHT_BUTTON = 1;
    static final int MIDDLE_BUTTON = 2;

    void mouseClicked(double x, double y, int button, boolean shift, boolean doubleClick) {
        blink = 0;
        if (ui == null) {
            return; // nothing has been drawn yet, so there is nothing to click
        }
        if (dialog != null) {
            // Only the dialog's own buttons respond.
            for (Button candidate : buttons) {
                if (candidate.rect().contains(x, y) && dialogRect().contains(x, y)) {
                    candidate.action().run();
                    break;
                }
            }
            return;
        }
        if (menu != null) {
            Menu open = menu;
            Rect r = menuRect(ui);
            menu = null;
            int index = (int) Math.floor((y - r.y() - 2) / 13);
            if (r.contains(x, y) && index >= 0 && index < open.items().size()) {
                open.items().get(index).action().run();
            }
            return;
        }
        if (button == LEFT_BUTTON) {
            for (Button candidate : buttons) {
                if (candidate.rect().contains(x, y)) {
                    candidate.action().run();
                    return;
                }
            }
        }

        if (explorerRect.contains(x, y)) {
            Node node = nodeAt(y);
            if (node != null) {
                session.selectedPath = node.path;
            }
            if (button == RIGHT_BUTTON) {
                openMenuFor(node, (int) x, (int) y);
            } else if (button == LEFT_BUTTON && node != null) {
                if (!node.folder) {
                    openFile(node.path);
                } else if (!session.expanded.remove(node.path)) {
                    session.expanded.add(node.path);
                    loadFolder(node.path);
                }
            }
        } else if (tabsRect.contains(x, y)) {
            int tabX = tabsRect.x();
            for (int i = 0; i < session.tabs.size(); i++) {
                int width = tabWidth(ui, session.tabs.get(i));
                if (x >= tabX && x < tabX + width) {
                    if (button == MIDDLE_BUTTON || (button == LEFT_BUTTON && x >= tabX + width - 15)) {
                        closeTab(i);
                    } else {
                        session.activeTab = i;
                        session.focus = Focus.EDITOR;
                        session.selectedPath = session.tabs.get(i).path;
                    }
                    break;
                }
                tabX += width + 1;
            }
        } else if (editorRect.contains(x, y)) {
            session.focus = Focus.EDITOR;
            Tab tab = session.active();
            if (button == LEFT_BUTTON && tab != null && tab.view != null) {
                tab.view.mouseDown(x, y, shift, doubleClick);
            }
        } else if (terminalRect.contains(x, y)) {
            session.focus = Focus.TERMINAL;
        } else if (session.scratchOpen && scratchRect.contains(x, y)) {
            session.focus = Focus.SCRATCH;
            if (button == LEFT_BUTTON && scratchCodeRect.contains(x, y)) {
                session.scratch.mouseDown(x, y, shift, doubleClick);
            }
        }
    }

    void mouseDragged(double x, double y, int button) {
        if (dialog == null && button == LEFT_BUTTON) {
            Tab tab = session.active();
            if (session.focus == Focus.EDITOR && tab != null && tab.view != null) {
                tab.view.mouseDrag(x, y);
            } else if (session.focus == Focus.SCRATCH) {
                session.scratch.mouseDrag(x, y);
            }
        }
    }

    void mouseReleased() {
        for (Tab tab : session.tabs) {
            if (tab.view != null) {
                tab.view.mouseUp();
            }
        }
        session.scratch.mouseUp();
    }

    void mouseScrolled(double x, double y, double amount) {
        if (dialog != null || explorerRect == null) {
            return;
        }
        if (explorerRect.contains(x, y)) {
            session.explorerScroll -= (int) Math.round(amount * ROW_H * 3);
        } else if (editorRect.contains(x, y)) {
            Tab tab = session.active();
            if (tab != null && tab.view != null) {
                tab.view.scroll(amount);
            }
        } else if (session.scratchOpen && scratchCodeRect.contains(x, y)) {
            session.scratch.scroll(amount);
        }
    }
}
