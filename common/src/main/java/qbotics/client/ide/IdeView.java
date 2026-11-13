package qbotics.client.ide;

import com.mojang.blaze3d.platform.InputConstants;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.input.KeyEvent;
import qbotics.client.ide.IdeSession.FindBar;
import qbotics.client.ide.IdeSession.Focus;
import qbotics.client.ide.IdeSession.Node;
import qbotics.client.ide.IdeSession.Tab;
import qbotics.client.ide.Ui.Rect;
import qbotics.computer.ComputerSettings;
import qbotics.computer.ServerComputer;
import qbotics.computer.Terminal;
import qbotics.engine.HostFileSystem;
import qbotics.ide.Finder;
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

        /** Ctrl+P: sharp screen fonts or Minecraft's pixel font. */
        default void toggleFont() {
        }

        /** The Chat panel: a message (or a /command) to the game's chat, as the chat box sends it. */
        default void sendChat(String text) {
        }
    }

    private static final int TITLE_H = 16;
    private static final int STATUS_H = 13;
    private static final int TAB_H = 15;
    private static final int HEADER_H = 14;
    private static final int ROW_H = 11;
    private static final int EXPLORER_W = 160;
    private static final int SCRATCH_W = 236;
    private static final int TERMINAL_H = HEADER_H + Terminal.ROWS * Ui.CELL_H + 6;
    private static final int MIN_TERMINAL_H = HEADER_H + 3 * Ui.CELL_H + 6;

    /** What a held-down left mouse button is doing. */
    private enum Drag { NONE, TEXT, TERMINAL_TEXT, TERMINAL_EDGE, NODE }

    private Drag drag = Drag.NONE;

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
    private Rect chatInputRect;
    private Rect chatTextRect = new Rect(0, 0, 0, 0);
    private static final int CHAT_INPUT_H = 3 * Ui.CELL_H + 4;

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
        // Every time the screen opens: the files may have changed while it was closed. The
        // tree shown meanwhile is the one from last time.
        loadFolder("/");
    }

    /** Lists the open folders again (the files changed outside the editor). */
    void reloadFiles() {
        loadFolder("/");
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
        boolean right = session.scratchOpen || session.chatOpen;
        int centerW = viewWidth - centerX - (right ? SCRATCH_W + 1 : 0);
        // The terminal's height can be changed by dragging the line above it.
        int wanted = session.terminalHeight > 0 ? session.terminalHeight : TERMINAL_H;
        int terminalH = session.editOnly ? 0 : Math.clamp(wanted, Math.min(MIN_TERMINAL_H, bodyH / 2), Math.max(MIN_TERMINAL_H, bodyH - 80));
        tabsRect = new Rect(centerX, bodyY, centerW, TAB_H);
        editorRect = new Rect(centerX, bodyY + TAB_H, centerW, bodyH - TAB_H - terminalH - 1);
        terminalRect = new Rect(centerX, bodyY + bodyH - terminalH, centerW, terminalH);
        scratchRect = right ? new Rect(viewWidth - SCRATCH_W, bodyY, SCRATCH_W, bodyH) : new Rect(viewWidth, bodyY, 0, bodyH);
        scratchCodeRect = new Rect(scratchRect.x() + 4, scratchRect.y() + HEADER_H + 16, SCRATCH_W - 8, Math.max(40, bodyH * 2 / 5));
        chatInputRect = new Rect(scratchRect.x() + 4, scratchRect.bottom() - 4 - CHAT_INPUT_H, SCRATCH_W - 8, CHAT_INPUT_H);
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
        tickNodeDrag();
        drawTabs(ui);
        drawEditor(ui, cursorOn);
        if (!session.editOnly) {
            drawTerminal(ui, cursorOn);
        }
        if (session.scratchOpen) {
            drawScratch(ui, cursorOn);
        } else if (session.chatOpen) {
            drawChat(ui, cursorOn);
        }
        drawTitleBar(ui);
        drawStatusBar(ui);
        // Thin lines between the panels.
        ui.fill(EXPLORER_W, TITLE_H, 1, viewHeight - TITLE_H - STATUS_H, Ui.BORDER);
        if (!session.editOnly) {
            boolean onEdge = drag == Drag.TERMINAL_EDGE || (dialog == null && menu == null && onTerminalEdge(mouseX, mouseY));
            ui.fill(terminalRect.x(), terminalRect.y() - 1, terminalRect.width(), onEdge ? 2 : 1, onEdge ? Ui.STATUS_BG : Ui.BORDER);
        }
        if (session.scratchOpen || session.chatOpen) {
            ui.fill(scratchRect.x() - 1, TITLE_H, 1, viewHeight - TITLE_H - STATUS_H, Ui.BORDER);
        }
        // The autocomplete list goes on top of the panels around the text it belongs to.
        Tab active = session.active();
        if (session.focus == Focus.EDITOR && active != null && active.view != null) {
            active.view.renderPopup(ui, viewHeight - STATUS_H);
        } else if (session.focus == Focus.SCRATCH && session.scratchOpen) {
            session.scratch.renderPopup(ui, viewHeight - STATUS_H);
        }
        // A hint for the name the mouse is resting on, unless something is open on top of the text.
        if (dialog == null && menu == null && drag == Drag.NONE) {
            boolean overFindBar = active != null && active.find.open && mouseY < editorRect.y() + 36 && mouseX > editorRect.right() - 360;
            if (active != null && active.view != null && editorRect.contains(mouseX, mouseY) && !overFindBar) {
                active.view.renderHover(ui, mouseX, mouseY, viewWidth, viewHeight - STATUS_H);
            } else if (session.scratchOpen && scratchCodeRect.contains(mouseX, mouseY)) {
                session.scratch.renderHover(ui, mouseX, mouseY, viewWidth, viewHeight - STATUS_H);
            }
        }
        if (menu != null) {
            drawMenu(ui);
        }
        if (dialog != null) {
            drawDialog(ui, cursorOn);
        }
        drawNodeGhost(ui);
    }

    /** The line between the editor and the terminal, which can be dragged. */
    private boolean onTerminalEdge(double x, double y) {
        return !session.editOnly && x >= terminalRect.x() && x < terminalRect.right() && Math.abs(y - terminalRect.y()) <= 2;
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
        int computerX = 6 + ui.textWidth("QBOTICS") + 6;
        ui.text(session.title, computerX, 4, Ui.TEXT_DIM);
        if (session.laptop) {
            ui.text("Your laptop: ssh 100.64.0.12:1005 reaches a computer or Qbot", computerX + ui.textWidth(session.title) + 12, 4, Ui.TEXT_DIM);
        } else if (!session.editOnly) {
            // This computer's settings page opens in the editor, like any other file.
            button(ui, computerX + ui.textWidth(session.title) + 8, 1, TITLE_H - 2, "Settings", false, true, Ui.BAR_BG,
                    () -> openFile(ComputerSettings.PATH));
        } else {
            ui.text("Its hard drive: edit here, nothing runs", computerX + ui.textWidth(session.title) + 12, 4, Ui.TEXT_DIM);
        }

        Tab tab = session.active();
        boolean hasFile = tab != null && tab.view != null;
        boolean isPython = hasFile && tab.view.language == Language.PYTHON;
        boolean isJson = hasFile && tab.view.language == Language.JSON;
        // Laid out from the right edge.
        int x = viewWidth - 4;
        if (!session.editOnly) {
            String scratchLabel = session.scratchOpen ? "Hide Scratchpad" : "Python Scratchpad";
            x -= ui.textWidth(scratchLabel) + 10;
            button(ui, x, 1, TITLE_H - 2, scratchLabel, false, true, session.scratchOpen ? Ui.ACCENT : Ui.BAR_BG, this::toggleScratch);
        }
        String chatLabel = session.chatOpen ? "Hide Chat" : "Chat";
        x -= ui.textWidth(chatLabel) + 10 + 4;
        button(ui, x, 1, TITLE_H - 2, chatLabel, false, true, session.chatOpen ? Ui.ACCENT : Ui.BAR_BG, this::toggleChat);
        x -= ui.textWidth("Save") + 10 + 4;
        button(ui, x, 1, TITLE_H - 2, "Save", false, hasFile && tab.view.buffer.isDirty(), Ui.BAR_BG, this::saveActive);
        if (isJson) {
            x -= ui.textWidth("Format JSON") + 10 + 4;
            button(ui, x, 1, TITLE_H - 2, "Format JSON", false, true, Ui.BAR_BG, this::formatActive);
        }
        if (!session.editOnly) {
            x -= ui.textWidth("Run") + 18 + 4;
            button(ui, x, 1, TITLE_H - 2, "Run", true, isPython, Ui.BAR_BG, this::runActive);
        }
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
        } else if (tab != null && tab.view != null && !tab.view.problems().isEmpty()) {
            // The mistake on the cursor's line, or how many there are.
            qbotics.ide.Checker.Problem here = tab.view.problemOn(tab.view.buffer.cursorLine());
            int count = tab.view.problems().size();
            left = here != null ? "Line " + (here.line() + 1) + ": " + here.message()
                    : count + (count == 1 ? " problem" : " problems") + " in this file: hover over the underlined code to see what is wrong";
        } else {
            left = (session.editOnly ? "" : "F5 Run   F8 Step   ") + "Ctrl+S Save   Ctrl+F Find   Ctrl+H Replace   Ctrl+Space Suggest   Ctrl+/ Comment   Ctrl+Plus/Minus Text size   Esc Close";
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
            if (moving != null && node.folder && node.path.equals(dropFolder)) {
                // Where a dragged file or folder would go.
                ui.fill(row.x(), row.y(), row.width(), ROW_H, Ui.ACTIVE_ROW);
                ui.outline(row.x(), row.y(), row.width(), ROW_H, Ui.STATUS_BG);
            }
            int indent = r.x() + 6 + visibleDepths.get(i) * 8;
            if (node.folder) {
                ui.arrow(indent, y + 3, session.expanded.contains(node.path), Ui.TEXT);
                ui.text(ui.fit(node.name, r.right() - indent - 12), indent + 8, y + 2, Ui.FOLDER);
            } else {
                ui.text(ui.fit(node.name, r.right() - indent - 12), indent + 8, y + 2, fileColor(node.name));
            }
        }
        if (moving != null && "/".equals(dropFolder)) {
            ui.outline(r.x(), listY, r.width(), listH, Ui.STATUS_BG); // the top of the drive
        }
        ui.unclip();
    }

    // ---- dragging files and folders to move them ---------------------------------

    /** How far the mouse must move, held down on a file or folder, before it is being dragged. */
    private static final double DRAG_START = 4;
    /** How long a dragged file rests on a closed folder before the folder opens. */
    private static final long OPEN_AFTER_MILLIS = 600;
    /** The file or folder the left button went down on, in the explorer, or null. */
    private Node pressedNode;
    private double pressX;
    private double pressY;
    /** The file or folder being dragged, once the mouse has moved far enough, or null. */
    private Node moving;
    private double dragX;
    private double dragY;
    /** The folder it would be dropped into ("/" for the top of the drive), or null: nowhere. */
    private String dropFolder;
    /** The closed folder the drag is resting on, and since when. */
    private String restingOn;
    private long restingSince;

    /** The folder a drop at this point would move into: the folder under it, a file's folder, or the top. */
    private String dropFolderAt(double x, double y) {
        if (!explorerRect.contains(x, y) || y < explorerRect.y() + HEADER_H) {
            return null;
        }
        Node node = nodeAt(y);
        if (node == null) {
            return "/";
        }
        return node.folder ? node.path : Paths.parent(node.path);
    }

    /** Each frame of a drag: open a folder it rests on, and scroll near the explorer's top and bottom. */
    private void tickNodeDrag() {
        if (moving == null) {
            return;
        }
        Node under = explorerRect.contains(dragX, dragY) ? nodeAt(dragY) : null;
        String resting = under != null && under.folder && !session.expanded.contains(under.path) ? under.path : null;
        if (resting == null || !resting.equals(restingOn)) {
            restingOn = resting;
            restingSince = System.currentTimeMillis();
        } else if (System.currentTimeMillis() - restingSince > OPEN_AFTER_MILLIS) {
            session.expanded.add(resting);
            loadFolder(resting);
            restingOn = null;
        }
        if (dragX >= explorerRect.x() && dragX < explorerRect.right()) {
            int listY = explorerRect.y() + HEADER_H;
            if (dragY < listY + ROW_H) {
                session.explorerScroll = Math.max(0, session.explorerScroll - 2);
            } else if (dragY > explorerRect.y() + explorerRect.height() - ROW_H) {
                session.explorerScroll += 2;
            }
        }
        dropFolder = dropFolderAt(dragX, dragY);
    }

    /** The dragged item's name, beside the mouse. */
    private void drawNodeGhost(Ui ui) {
        if (moving == null) {
            return;
        }
        String label = moving.name;
        int w = ui.textWidth(label) + 8;
        int x = (int) dragX + 10;
        int y = (int) dragY + 4;
        ui.fill(x, y, w, ROW_H + 2, Ui.MENU_BG);
        ui.outline(x, y, w, ROW_H + 2, dropFolder == null ? Ui.BORDER : Ui.STATUS_BG);
        ui.text(label, x + 4, y + 3, moving.folder ? Ui.FOLDER : fileColor(moving.name));
    }

    /**
     * For the screenshot run: presses on one file or folder in the explorer and drags it over
     * another (as the mouse would), or with over null lets go. Paths must be showing.
     */
    void dragForShots(String from, String over) {
        if (over == null) {
            mouseReleased();
            return;
        }
        double x = explorerRect.x() + 30;
        mouseClicked(x, rowY(from), LEFT_BUTTON, false, false);
        mouseDragged(x + 20, rowY(from) + 6, LEFT_BUTTON);
        mouseDragged(x + 24, rowY(over), LEFT_BUTTON);
    }

    private double rowY(String path) {
        for (int i = 0; i < visibleNodes.size(); i++) {
            if (visibleNodes.get(i).path.equals(path)) {
                return explorerRect.y() + HEADER_H + i * ROW_H - session.explorerScroll + ROW_H / 2.0;
            }
        }
        throw new IllegalStateException(path + " isn't showing in the explorer");
    }

    /** Moves a file or folder into a folder, as dropping it there does. */
    private void moveInto(Node node, String folder) {
        String target = Paths.join(folder, node.name);
        if (target.equals(node.path) || folder.equals(Paths.parent(node.path))) {
            return; // dropped where it already is
        }
        if (node.folder && Paths.isInside(folder, node.path)) {
            toast("A folder can't go inside itself.", true);
            return;
        }
        Node into = session.find(folder);
        if (into != null) {
            for (Node child : into.children) {
                if (child.name.equals(node.name)) {
                    toast((folder.equals("/") ? "The top of the drive" : Paths.name(folder)) + " already has a " + node.name
                            + ". Rename one of them first.", true);
                    return;
                }
            }
        }
        backend.file(FileRequest.RENAME, node.path, target.getBytes(StandardCharsets.UTF_8), (errno, data) -> {
            if (errno != 0) {
                toast(describe(errno, node.path), true);
                return;
            }
            followMove(node.path, target);
            session.expanded.add(folder);
            loadFolder(Paths.parent(node.path));
            loadFolder(folder);
            toast("Moved " + node.name + " to " + folder, false);
        });
    }

    /** Open tabs, open folders and the selection follow a file or folder to its new path. */
    private void followMove(String from, String to) {
        for (Tab tab : session.tabs) {
            if (Paths.isInside(tab.path, from)) {
                tab.path = to + tab.path.substring(from.length());
                if (tab.view != null) {
                    // A file renamed to end in .py becomes a Python file, coloured and runnable.
                    tab.view.setLanguage(Language.forFile(Paths.name(tab.path)));
                    tab.view.path = tab.path;
                }
            }
        }
        List<String> opened = new ArrayList<>(session.expanded);
        for (String path : opened) {
            if (Paths.isInside(path, from)) {
                session.expanded.remove(path);
                session.expanded.add(to + path.substring(from.length()));
            }
        }
        session.selectedPath = to;
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
                if (!session.editOnly) {
                    items.add(new MenuItem("Run", () -> backend.terminalInput(TerminalInputPayload.RUN_FILE, node.path)));
                }
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
                // Open tabs (and open folders) follow the file, or everything in the folder, to its new name.
                followMove(node.path, target);
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
    /**
     * Shows where the program paused: its file (opened if it isn't), scrolled to the line.
     * Where the player was typing stays where it was.
     */
    void showLine(String path, int line) {
        if (path.isEmpty() || line <= 0) {
            return;
        }
        Focus was = session.focus;
        openFile(path);
        session.focus = was;
        Tab tab = session.active();
        if (tab != null) {
            tab.revealLine = line - 1;
        }
    }

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
                tab.view.compiler = backend;
                tab.view.path = tab.path;
            } else if (errno == HostFileSystem.EFBIG) {
                tab.problem = "This file is too large for the editor (the limit is " + IdePayloads.MAX_FILE_BYTES / 1000 + " KB).";
            } else if (errno != 0) {
                tab.problem = describe(errno, path);
            } else {
                tab.view = new CodeView(new TextBuffer(new String(data, StandardCharsets.UTF_8)), Language.forFile(Paths.name(tab.path)), true);
                tab.view.compiler = backend;
                tab.view.path = tab.path;
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
                // The settings page says in words what was wrong with it.
                toast("Could not save: " + (data.length > 0 ? new String(data, StandardCharsets.UTF_8) : describe(errno, tab.path)), true);
                return;
            }
            buffer.markSaved(version);
            if (tab.path.equals(ComputerSettings.PATH)) {
                // Show the settings as the computer now has them (a new password is hidden again).
                toast("Settings saved.", false);
                backend.file(FileRequest.READ, tab.path, new byte[0], (readErrno, fresh) -> {
                    if (readErrno == 0 && tab.view != null && !tab.view.buffer.isDirty()) {
                        tab.view.buffer.replaceAll(new String(fresh, StandardCharsets.UTF_8).stripTrailing());
                        tab.view.buffer.markSaved();
                    }
                });
            } else {
                toast("Saved " + tab.path, false);
                loadFolder(Paths.parent(tab.path));
            }
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

    // ---- find and replace -----------------------------------------------------------

    /** Finds the matches again if the text or what is being searched for has changed. */
    private void refreshMatches(Tab tab) {
        FindBar find = tab.find;
        CodeView view = tab.view;
        if (!find.open) {
            view.matches = List.of();
            view.currentMatch = -1;
            return;
        }
        String query = find.query.toString();
        if (find.matchedVersion != view.buffer.version() || !query.equals(find.matchedQuery) || find.matchedCase != find.caseSensitive) {
            find.matches = Finder.find(view.buffer, query, find.caseSensitive);
            find.matchedVersion = view.buffer.version();
            find.matchedQuery = query;
            find.matchedCase = find.caseSensitive;
            find.current = Finder.firstAtOrAfter(find.matches, view.buffer.selectionStartLine(), view.buffer.selectionStartColumn());
        }
        view.matches = find.matches;
        view.currentMatch = find.current;
    }

    /** Selects the current match and scrolls to it. */
    private void showMatch(Tab tab) {
        refreshMatches(tab);
        if (tab.find.current >= 0) {
            Finder.Match match = tab.find.matches.get(tab.find.current);
            tab.view.buffer.select(match.line(), match.start(), match.end());
            tab.view.reveal();
        }
    }

    private void findStep(Tab tab, int direction) {
        refreshMatches(tab);
        if (!tab.find.matches.isEmpty()) {
            tab.find.current = Math.floorMod(tab.find.current + direction, tab.find.matches.size());
            showMatch(tab);
        }
    }

    private void openFind(Tab tab, boolean withReplace) {
        FindBar find = tab.find;
        find.open = true;
        find.focused = true;
        find.replaceOpen |= withReplace;
        find.field = 0;
        session.focus = Focus.EDITOR;
        tab.view.dismissPopup();
        // Start from the selected text, as editors do.
        String selected = tab.view.buffer.selectedText();
        if (!selected.isEmpty() && !selected.contains("\n")) {
            find.query.setLength(0);
            find.query.append(selected);
        }
        showMatch(tab);
    }

    private void closeFind(Tab tab) {
        tab.find.open = false;
        tab.find.focused = false;
    }

    private void replaceCurrent(Tab tab) {
        refreshMatches(tab);
        FindBar find = tab.find;
        if (find.current < 0) {
            return;
        }
        Finder.Match match = find.matches.get(find.current);
        tab.view.buffer.select(match.line(), match.start(), match.end());
        tab.view.buffer.insert(find.replacement.toString());
        showMatch(tab); // the text changed: this finds again and moves to the next match
    }

    private void replaceAll(Tab tab) {
        refreshMatches(tab);
        int count = tab.find.matches.size();
        if (count > 0) {
            tab.view.buffer.replaceMatches(tab.find.matches, tab.find.replacement.toString());
            toast("Replaced " + count + (count == 1 ? " match." : " matches."), false);
        }
    }

    private void findKey(Tab tab, KeyEvent event) {
        FindBar find = tab.find;
        StringBuilder field = find.field == 0 ? find.query : find.replacement;
        int key = event.key();
        if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) {
            if (find.field == 0) {
                findStep(tab, event.hasShiftDown() ? -1 : 1);
            } else if (event.hasControlDown()) {
                replaceAll(tab);
            } else {
                replaceCurrent(tab);
            }
        } else if (key == InputConstants.KEY_TAB && find.replaceOpen) {
            find.field = 1 - find.field;
        } else if (key == InputConstants.KEY_BACKSPACE && !field.isEmpty()) {
            field.setLength(field.length() - 1);
            findEdited(tab);
        } else if (event.isPaste()) {
            findType(tab, host.clipboard().lines().findFirst().orElse(""));
        }
    }

    private void findType(Tab tab, String text) {
        StringBuilder field = tab.find.field == 0 ? tab.find.query : tab.find.replacement;
        field.append(text, 0, Math.min(text.length(), Math.max(0, 100 - field.length())));
        findEdited(tab);
    }

    /** The search text changed: jump to the first match as it is typed. */
    private void findEdited(Tab tab) {
        if (tab.find.field == 0) {
            showMatch(tab);
        }
    }

    private void drawFindBar(Ui ui, Tab tab, boolean cursorOn) {
        FindBar find = tab.find;
        int width = Math.min(346, editorRect.width() - 24);
        int rows = find.replaceOpen ? 2 : 1;
        int height = rows * 16 + 3;
        int x = editorRect.right() - width - 12;
        int y = editorRect.y();
        ui.fill(x + 2, y + 2, width, height, Ui.SHADOW);
        ui.fill(x, y, width, height, Ui.MENU_BG);
        ui.outline(x, y, width, height, Ui.BORDER);

        boolean active = find.focused && session.focus == Focus.EDITOR && dialog == null;
        int fieldWidth = Math.max(60, width - 190);
        int at = x + 3;
        at = button(ui, at, y + 2, 14, find.replaceOpen ? "v" : ">", false, true, Ui.MENU_BG, () -> find.replaceOpen = !find.replaceOpen) + 2;
        int fieldX = at;
        findField(ui, find, 0, fieldX, y + 2, fieldWidth, active && cursorOn, "Find");
        at = fieldX + fieldWidth + 4;
        String count = find.query.isEmpty() ? "" : find.matches.isEmpty() ? "No results" : (find.current + 1) + " of " + find.matches.size();
        ui.text(count, at, y + 5, find.matches.isEmpty() ? Ui.ERROR : Ui.TEXT_DIM);

        // Buttons from the right edge.
        int right = x + width - 3;
        right -= ui.textWidth("x") + 10;
        button(ui, right, y + 2, 14, "x", false, true, Ui.MENU_BG, () -> closeFind(tab));
        right -= ui.textWidth(">") + 10;
        button(ui, right, y + 2, 14, ">", false, true, Ui.MENU_BG, () -> findStep(tab, 1));
        right -= ui.textWidth("<") + 10;
        button(ui, right, y + 2, 14, "<", false, true, Ui.MENU_BG, () -> findStep(tab, -1));
        right -= ui.textWidth("Aa") + 10 + 2;
        button(ui, right, y + 2, 14, "Aa", false, true, find.caseSensitive ? Ui.ACCENT : Ui.MENU_BG, () -> find.caseSensitive = !find.caseSensitive);

        if (find.replaceOpen) {
            findField(ui, find, 1, fieldX, y + 18, fieldWidth, active && cursorOn, "Replace with");
            int next = button(ui, fieldX + fieldWidth + 4, y + 18, 14, "Replace", false, !find.matches.isEmpty(), Ui.BAR_BG, () -> replaceCurrent(tab));
            button(ui, next + 3, y + 18, 14, "All", false, !find.matches.isEmpty(), Ui.BAR_BG, () -> replaceAll(tab));
        }
        // Clicking anywhere else on the bar gives it the keyboard instead of reaching the text beneath.
        buttons.add(new Button(new Rect(x, y, width, height), () -> find.focused = true));
    }

    private void findField(Ui ui, FindBar find, int field, int x, int y, int width, boolean showCursor, String placeholder) {
        boolean focused = find.focused && find.field == field;
        StringBuilder text = field == 0 ? find.query : find.replacement;
        ui.fill(x, y, width, 14, Ui.EDITOR_BG);
        ui.outline(x, y, width, 14, focused ? Ui.STATUS_BG : Ui.BORDER);
        ui.clip(x + 1, y, width - 2, 14);
        if (text.isEmpty() && !focused) {
            ui.text(placeholder, x + 4, y + 3, Ui.TEXT_DIM);
        }
        // When the text is longer than the box, show its end.
        String shown = text.toString();
        int offset = Math.max(0, ui.textWidth(shown) - (width - 10));
        ui.text(shown, x + 4 - offset, y + 3, Ui.TEXT_BRIGHT);
        if (focused && showCursor) {
            ui.fill(x + 4 - offset + ui.textWidth(shown), y + 2, 1, 10, Ui.TEXT_BRIGHT);
        }
        ui.unclip();
        buttons.add(new Button(new Rect(x, y, width, 14), () -> {
            find.focused = true;
            find.field = field;
            session.focus = Focus.EDITOR;
        }));
    }

    private void drawEditor(Ui ui, boolean cursorOn) {
        Rect r = editorRect;
        Tab tab = session.active();
        if (tab != null && tab.view != null) {
            refreshMatches(tab);
            boolean typingInFind = tab.find.open && tab.find.focused;
            if (tab.revealLine >= 0) {
                tab.view.reveal(tab.revealLine);
                tab.revealLine = -1;
            }
            boolean here = session.traceLine > 0 && session.traceFile.equals(tab.path);
            tab.view.runLine = here ? session.traceLine - 1 : -1;
            tab.view.runPaused = session.tracePaused;
            tab.view.render(ui, r, session.focus == Focus.EDITOR && dialog == null && !typingInFind, cursorOn);
            if (tab.find.open) {
                drawFindBar(ui, tab, cursorOn);
            }
            return;
        }
        ui.fill(r.x(), r.y(), r.width(), r.height(), Ui.EDITOR_BG);
        String[] lines;
        if (tab != null && tab.problem != null) {
            lines = new String[] {tab.problem};
        } else if (tab != null) {
            lines = new String[] {"Opening " + tab.path + " ..."};
        } else if (session.editOnly) {
            lines = new String[] {
                "Click a file in the Explorer to open it here.",
                "Use + File, or right-click in the Explorer, to make a new one.",
                "",
                "Each folder can be a program: what goes onto a computer.",
                "programs.txt picks one per redstone level, as in \"3: miner\" and \"*: basic\".",
            };
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
        int[] selection = terminalSelection();
        String hint = selection != null ? "Ctrl+C copies the selected text"
                : session.terminalScroll > 0 ? "Scrolled back - type or press End to return" : "Ctrl+C stops a program   Ctrl+Z pauses   F8 steps   Drag to select";
        ui.text(hint, r.right() - ui.textWidth(hint) - 10, r.y() + 3, session.terminalScroll > 0 ? Ui.FOLDER : Ui.TEXT_DIM);

        int left = r.x() + 8;
        int top = r.y() + HEADER_H + 2;
        int height = r.height() - HEADER_H - 4;
        int rows = terminalRows();
        // Everything there is to show: the scroll-back, then the screen down to its last used row.
        List<String> scrollback = session.terminalScrollback;
        int used = terminalUsedRows();
        int total = scrollback.size() + used;
        session.terminalScroll = Math.clamp(session.terminalScroll, 0, Math.max(0, total - rows));
        int first = Math.max(0, total - rows - session.terminalScroll);

        ui.clip(r.x(), top, r.width(), height);
        for (int row = 0; row < rows && first + row < total; row++) {
            int index = first + row;
            String line = index < scrollback.size() ? scrollback.get(index) : session.terminalLines.get(index - scrollback.size());
            if (selection != null && index >= selection[0] && index <= selection[2]) {
                int from = index == selection[0] ? selection[1] : 0;
                // A line selected through to its end shows one more cell, for the line break.
                int to = index == selection[2] ? selection[3] : Math.max(from, terminalLine(index).length()) + 1;
                ui.fill(left + from * Ui.CELL_W, top + row * Ui.CELL_H, (to - from) * Ui.CELL_W, Ui.CELL_H, Ui.SELECTION);
            }
            ui.mono(line, left, top + row * Ui.CELL_H, Ui.TEXT);
        }
        // The line being typed starts at the server's cursor, just after the prompt.
        int cursorRow = scrollback.size() + session.terminalCursorY - first;
        if (cursorRow >= 0 && cursorRow < rows) {
            StringBuilder input = session.terminalInput;
            int start = inputWindowStart();
            String shown = input.substring(start, Math.min(input.length(), start + inputRoom()));
            int inputX = left + session.terminalCursorX * Ui.CELL_W;
            int inputY = top + cursorRow * Ui.CELL_H;
            ui.mono(shown, inputX, inputY, Ui.TEXT_BRIGHT);
            if (focused && cursorOn) {
                int at = inputX + (session.terminalInputCursor - start) * Ui.CELL_W;
                if (session.terminalInputCursor < input.length()) {
                    // On a character: a thin bar before it, so the character stays readable.
                    ui.fill(at, inputY, 1, Ui.CELL_H - 1, 0xF0E8E8E8);
                } else {
                    ui.fill(at, inputY, Ui.CELL_W - 1, Ui.CELL_H - 1, 0xC0D4D4D4);
                }
            }
        }
        ui.unclip();
        if (total > rows) {
            int thumb = Math.max(8, height * rows / total);
            int thumbTop = (height - thumb) * first / (total - rows);
            ui.fill(r.right() - 4, top + thumbTop, 3, thumb, 0x60FFFFFF);
        }
    }

    /** How many characters of the line being typed fit after the prompt. */
    private int inputRoom() {
        return Math.max(1, Terminal.COLS - session.terminalCursorX - 1);
    }

    /** The first character of the line being typed that is shown: a long line scrolls to keep the cursor in view. */
    private int inputWindowStart() {
        int length = session.terminalInput.length();
        int room = inputRoom();
        if (length < room) {
            return 0;
        }
        return Math.clamp(session.terminalInputCursor - room + 1, 0, length - room + 1);
    }

    /** Moves the cursor in the line being typed, keeping it within the line. */
    private void inputCursorTo(int position) {
        session.terminalInputCursor = Math.clamp(position, 0, session.terminalInput.length());
    }

    /** Where a word starts or ends, from the cursor, for Ctrl with the arrow keys and Backspace. */
    private int inputWordEdge(int direction) {
        StringBuilder input = session.terminalInput;
        int at = session.terminalInputCursor;
        if (direction < 0) {
            while (at > 0 && input.charAt(at - 1) == ' ') {
                at--;
            }
            while (at > 0 && input.charAt(at - 1) != ' ') {
                at--;
            }
        } else {
            while (at < input.length() && input.charAt(at) == ' ') {
                at++;
            }
            while (at < input.length() && input.charAt(at) != ' ') {
                at++;
            }
        }
        return at;
    }

    /** How many rows of text fit in the terminal panel. */
    private int terminalRows() {
        return Math.max(1, (terminalRect.height() - HEADER_H - 4) / Ui.CELL_H);
    }

    /** Rows of the terminal's screen that are in use: down to the cursor or the last text. */
    private int terminalUsedRows() {
        List<String> lines = session.terminalLines;
        int used = Math.min(lines.size(), session.terminalCursorY + 1);
        for (int row = lines.size() - 1; row >= used; row--) {
            if (!lines.get(row).isEmpty()) {
                used = row + 1;
                break;
            }
        }
        return used;
    }

    private void terminalKey(KeyEvent event) {
        StringBuilder input = session.terminalInput;
        if (event.hasControlDown() && event.key() == InputConstants.KEY_C) {
            // With text selected, Ctrl+C copies it, as in other terminals; otherwise it stops the program.
            // Ctrl+Shift+C only ever copies.
            if (terminalSelection() != null) {
                terminalCopy();
            } else if (!event.hasShiftDown()) {
                input.setLength(0);
                session.terminalInputCursor = 0;
                backend.terminalInput(TerminalInputPayload.INTERRUPT, "");
            }
        } else if (event.hasControlDown() && event.key() == InputConstants.KEY_Z) {
            backend.terminalInput(TerminalInputPayload.PAUSE, ""); // pause the program, or carry on
        } else if (event.isPaste()) {
            terminalPaste();
        } else {
            switch (event.key()) {
                case InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> terminalEnter();
                case InputConstants.KEY_BACKSPACE -> {
                    // Ctrl+Backspace takes the word before the cursor; Backspace, one character.
                    int from = event.hasControlDown() ? inputWordEdge(-1) : session.terminalInputCursor - 1;
                    if (from >= 0 && from < session.terminalInputCursor) {
                        input.delete(from, session.terminalInputCursor);
                        session.terminalInputCursor = from;
                    }
                }
                case InputConstants.KEY_DELETE -> {
                    int to = event.hasControlDown() ? inputWordEdge(1) : session.terminalInputCursor + 1;
                    if (to <= input.length() && to > session.terminalInputCursor) {
                        input.delete(session.terminalInputCursor, to);
                    }
                }
                case InputConstants.KEY_LEFT -> inputCursorTo(event.hasControlDown() ? inputWordEdge(-1) : session.terminalInputCursor - 1);
                case InputConstants.KEY_RIGHT -> inputCursorTo(event.hasControlDown() ? inputWordEdge(1) : session.terminalInputCursor + 1);
                case InputConstants.KEY_HOME -> inputCursorTo(0);
                case InputConstants.KEY_UP -> terminalRecall(-1);
                case InputConstants.KEY_DOWN -> terminalRecall(1);
                case InputConstants.KEY_PAGEUP -> session.terminalScroll += Math.max(1, terminalRows() - 1);
                case InputConstants.KEY_PAGEDOWN -> session.terminalScroll = Math.max(0, session.terminalScroll - Math.max(1, terminalRows() - 1));
                case InputConstants.KEY_END -> {
                    // To the end of the line, and back down to the prompt if scrolled up.
                    inputCursorTo(input.length());
                    session.terminalScroll = 0;
                }
                default -> {
                }
            }
        }
    }

    /** Pasted text may hold several lines: each complete line is entered. */
    private void terminalPaste() {
        String[] parts = host.clipboard().replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        for (int i = 0; i < parts.length; i++) {
            terminalAppend(parts[i].replace("\t", "    "));
            if (i < parts.length - 1) {
                terminalEnter();
            }
        }
    }

    private void terminalAppend(String text) {
        session.terminalSelection = null;
        session.terminalScroll = 0; // typing brings the prompt back into view
        int room = TerminalInputPayload.MAX_LINE_LENGTH - session.terminalInput.length();
        String added = text.substring(0, Math.max(0, Math.min(room, text.length())));
        int at = Math.clamp(session.terminalInputCursor, 0, session.terminalInput.length());
        session.terminalInput.insert(at, added);
        session.terminalInputCursor = at + added.length();
    }

    private void terminalEnter() {
        String line = session.terminalInput.toString();
        session.terminalInput.setLength(0);
        session.terminalInputCursor = 0;
        List<String> history = session.terminalHistory;
        if (!line.isBlank() && (history.isEmpty() || !history.getLast().equals(line))) {
            history.add(line);
        }
        session.terminalHistoryIndex = history.size();
        session.terminalScroll = 0;
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
        session.terminalInputCursor = session.terminalInput.length();
    }

    // ---- selecting and copying terminal text ---------------------------------------

    /** How many lines there are to show: the scroll-back, then the screen's used rows. */
    private int terminalTotalLines() {
        return session.terminalScrollback.size() + terminalUsedRows();
    }

    /** One line of everything the terminal can show, including what is being typed on the cursor's line. */
    private String terminalLine(int index) {
        List<String> scrollback = session.terminalScrollback;
        if (index < scrollback.size()) {
            return scrollback.get(index);
        }
        int row = index - scrollback.size();
        String line = row < session.terminalLines.size() ? session.terminalLines.get(row) : "";
        if (row == session.terminalCursorY && !session.terminalInput.isEmpty()) {
            line = line + " ".repeat(Math.max(0, session.terminalCursorX - line.length())) + session.terminalInput;
        }
        return line;
    }

    /** The line and column under the mouse. The column is the nearest gap between characters. */
    private int[] terminalCellAt(double x, double y) {
        int total = terminalTotalLines();
        int rows = terminalRows();
        int first = Math.max(0, total - rows - Math.clamp(session.terminalScroll, 0, Math.max(0, total - rows)));
        int row = (int) Math.floor((y - (terminalRect.y() + HEADER_H + 2)) / Ui.CELL_H);
        int line = Math.clamp(first + row, 0, Math.max(0, total - 1));
        int column = Math.clamp((int) Math.round((x - (terminalRect.x() + 8)) / Ui.CELL_W), 0, Terminal.COLS);
        return new int[] {line, column};
    }

    /** The selection as {first line, column, last line, column}, or null if nothing is selected. */
    private int[] terminalSelection() {
        int[] s = session.terminalSelection;
        if (s == null || (s[0] == s[2] && s[1] == s[3])) {
            return null;
        }
        boolean forwards = s[0] < s[2] || (s[0] == s[2] && s[1] < s[3]);
        int last = Math.max(0, terminalTotalLines() - 1);
        int[] ordered = forwards ? new int[] {s[0], s[1], s[2], s[3]} : new int[] {s[2], s[3], s[0], s[1]};
        ordered[0] = Math.min(ordered[0], last);
        ordered[2] = Math.min(ordered[2], last);
        return ordered;
    }

    private String terminalSelectedText() {
        int[] s = terminalSelection();
        if (s == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (int index = s[0]; index <= s[2]; index++) {
            String line = terminalLine(index);
            int from = Math.min(line.length(), index == s[0] ? s[1] : 0);
            int to = Math.min(line.length(), index == s[2] ? s[3] : line.length());
            text.append(line, from, Math.max(from, to));
            if (index < s[2]) {
                text.append('\n');
            }
        }
        return text.toString();
    }

    private void terminalCopy() {
        String text = terminalSelectedText();
        if (!text.isEmpty()) {
            host.setClipboard(text);
            toast("Copied.", false);
        }
        session.terminalSelection = null;
    }

    private void terminalSelectAll() {
        int last = Math.max(0, terminalTotalLines() - 1);
        session.terminalSelection = new int[] {0, 0, last, terminalLine(last).length()};
    }

    private void terminalMouseDown(double x, double y, int button, boolean shift, boolean doubleClick) {
        if (button == RIGHT_BUTTON) {
            List<MenuItem> items = new ArrayList<>();
            if (terminalSelection() != null) {
                items.add(new MenuItem("Copy", this::terminalCopy));
            }
            items.add(new MenuItem("Copy All", () -> {
                terminalSelectAll();
                terminalCopy();
            }));
            items.add(new MenuItem("Paste", this::terminalPaste));
            items.add(new MenuItem("Select All", this::terminalSelectAll));
            menu = new Menu((int) x, (int) y, items);
            return;
        }
        if (button != LEFT_BUTTON) {
            return;
        }
        int[] cell = terminalCellAt(x, y);
        if (doubleClick) {
            // A double-click selects a whole word, which is how an address is picked up.
            String line = terminalLine(cell[0]);
            int at = Math.clamp((int) Math.floor((x - (terminalRect.x() + 8)) / Ui.CELL_W), 0, Math.max(0, line.length() - 1));
            int start = at;
            int end = at;
            if (at < line.length() && line.charAt(at) != ' ') {
                while (start > 0 && line.charAt(start - 1) != ' ') {
                    start--;
                }
                while (end < line.length() && line.charAt(end) != ' ') {
                    end++;
                }
            }
            session.terminalSelection = new int[] {cell[0], start, cell[0], end};
            drag = Drag.NONE;
        } else if (shift && session.terminalSelection != null) {
            session.terminalSelection[2] = cell[0];
            session.terminalSelection[3] = cell[1];
            drag = Drag.TERMINAL_TEXT;
        } else {
            session.terminalSelection = new int[] {cell[0], cell[1], cell[0], cell[1]};
            drag = Drag.TERMINAL_TEXT;
            if (cell[0] == session.terminalScrollback.size() + session.terminalCursorY && cell[1] >= session.terminalCursorX) {
                // On the line being typed: the cursor goes where the click was.
                inputCursorTo(cell[1] - session.terminalCursorX + inputWindowStart());
            }
        }
    }
    // ---- scratchpad --------------------------------------------------------------

    private void toggleScratch() {
        session.scratchOpen = !session.scratchOpen;
        session.chatOpen = false;
        session.focus = session.scratchOpen ? Focus.SCRATCH : Focus.EDITOR;
    }

    // ---- chat --------------------------------------------------------------------

    /** For the screenshot run: open the Chat panel, choose where typing goes, and send a message. */
    void chatForShots(boolean toAi, String text) {
        session.chatOpen = true;
        session.scratchOpen = false;
        session.focus = Focus.CHAT;
        session.chatToAi = toAi;
        session.chatInput.buffer.replaceAll(text);
        if (!text.isEmpty()) {
            sendChat();
        }
    }

    private void toggleChat() {
        session.chatOpen = !session.chatOpen;
        session.scratchOpen = false;
        session.chatScroll = 0;
        session.focus = session.chatOpen ? Focus.CHAT : Focus.EDITOR;
    }

    /** Enter in the Chat panel: to the AI agent (with what the editor shows), or to the game's chat. */
    private void sendChat() {
        String text = session.chatInput.buffer.text().strip();
        if (text.isEmpty()) {
            return;
        }
        if (session.chatToAi) {
            // Never to the public chat instead: what was meant for the AI may not be meant for everyone.
            if (!qbotics.client.agent.ClientAgent.available()) {
                toast("The AI isn't answering. Nothing was sent; /ai status in chat shows its settings.", true);
                return;
            }
            if (qbotics.client.agent.ClientAgent.busy()) {
                toast("The AI is still answering the last question.", true);
                return;
            }
            qbotics.client.agent.ClientAgent.askFromEditor(text, editorContext());
        } else {
            host.sendChat(text.replaceAll("\\s+", " "));
        }
        session.chatInput.buffer.replaceAll("");
        session.chatScroll = 0;
    }

    /** What the AI is told about the editor with a question asked here. */
    private String editorContext() {
        StringBuilder context = new StringBuilder("(Asked from the editor of the computer the player opened last");
        Tab tab = session.active();
        if (tab != null && tab.view != null) {
            TextBuffer buffer = tab.view.buffer;
            context.append(". Open there: ").append(tab.path).append(", cursor on line ").append(buffer.cursorLine() + 1);
            if (buffer.hasSelection() && buffer.selectedText().length() < 4000) {
                context.append(". They have selected:\n```\n").append(buffer.selectedText()).append("\n```");
            }
            if (buffer.isDirty() && buffer.text().length() < 12_000) {
                context.append("\nIt has unsaved changes; the text in the editor now:\n```\n").append(buffer.text()).append("\n```");
            }
            if (!tab.view.problems().isEmpty()) {
                context.append("\nMistakes the editor marks:");
                for (qbotics.ide.Checker.Problem problem : tab.view.problems()) {
                    context.append("\nline ").append(problem.line() + 1).append(": ").append(problem.message());
                }
            }
        }
        return context.append(")").toString();
    }

    // The chat's lines, wrapped to the panel, kept until the chat, the width or the font changes.
    private List<List<ChatLog.Piece>> chatRows = List.of();
    private long chatRowsVersion = -1;
    private int chatRowsWidth = -1;
    private Object chatRowsFont;

    private List<List<ChatLog.Piece>> chatRows(Ui ui, int width) {
        long version = ChatLog.version();
        if (version == chatRowsVersion && width == chatRowsWidth && ui.codeFont().equals(chatRowsFont)) {
            return chatRows;
        }
        List<List<ChatLog.Piece>> rows = new ArrayList<>();
        for (List<ChatLog.Piece> line : ChatLog.lines()) {
            List<ChatLog.Piece> row = new ArrayList<>();
            int x = 0;
            for (ChatLog.Piece piece : line) {
                // Words, each with the space after it.
                for (String word : piece.text().split("(?<= )")) {
                    int w = ui.textWidth(word);
                    if (x + w > width && x > 0) {
                        rows.add(row);
                        row = new ArrayList<>();
                        x = 0;
                        word = word.stripLeading();
                        w = ui.textWidth(word);
                    }
                    while (w > width && word.length() > 1) {
                        // A word wider than the panel (a long address): cut it.
                        int fits = 1;
                        while (fits < word.length() && ui.textWidth(word.substring(0, fits + 1)) <= width) {
                            fits++;
                        }
                        row.add(new ChatLog.Piece(word.substring(0, fits), piece.color()));
                        rows.add(row);
                        row = new ArrayList<>();
                        word = word.substring(fits);
                        w = ui.textWidth(word);
                    }
                    row.add(new ChatLog.Piece(word, piece.color()));
                    x += w;
                }
            }
            rows.add(row);
        }
        chatRows = rows;
        chatRowsVersion = version;
        chatRowsWidth = width;
        chatRowsFont = ui.codeFont();
        return rows;
    }

    private void drawChat(Ui ui, boolean cursorOn) {
        Rect r = scratchRect;
        boolean focused = session.focus == Focus.CHAT && dialog == null;
        boolean aiHere = qbotics.client.agent.ClientAgent.available();
        if (!aiHere) {
            session.chatToAi = false;
        }
        ui.fill(r.x(), r.y(), r.width(), r.height(), Ui.SIDE_BG);
        ui.text("CHAT", r.x() + 6, r.y() + 3, focused ? Ui.TEXT_BRIGHT : Ui.TEXT_DIM);
        if (aiHere) {
            // Where typing goes: the game's chat, or the AI.
            int x = r.right() - 4 - (ui.textWidth("AI") + 10);
            button(ui, x, r.y() + 1, HEADER_H - 2, "AI", false, true, session.chatToAi ? Ui.ACCENT : Ui.SIDE_BG, () -> session.chatToAi = true);
            x -= ui.textWidth("Chat") + 10 + 2;
            button(ui, x, r.y() + 1, HEADER_H - 2, "Chat", false, true, session.chatToAi ? Ui.SIDE_BG : Ui.ACCENT, () -> session.chatToAi = false);
            ui.text("To:", x - ui.textWidth("To:") - 4, r.y() + 3, Ui.TEXT_DIM);
        }

        int y = r.y() + HEADER_H + 1;
        if (session.chatToAi && qbotics.client.agent.ClientAgent.busy()) {
            ui.text("The AI is working...", r.x() + 6, y + 2, Ui.FOLDER);
            button(ui, r.right() - 4 - (ui.textWidth("Stop") + 10), y, 12, "Stop", false, true, Ui.SIDE_BG, qbotics.client.agent.ClientAgent::stopTurn);
        } else {
            ui.text(ui.fit(session.chatToAi ? "To the AI, with the open file" : "To everyone in the game's chat", r.width() - 12),
                    r.x() + 6, y + 2, Ui.TEXT_DIM);
        }
        y += 14;

        // The AI waiting for an answer: Allow or Deny, here as well as in the chat.
        int bottom = chatInputRect.y() - 4;
        String pending = qbotics.client.agent.ClientAgent.pendingApproval();
        if (pending != null) {
            List<String> words = qbotics.ide.Docs.wrap("The AI wants to " + pending + ".", Math.max(10, (r.width() - 16) / 5));
            int boxH = words.size() * Ui.CELL_H + 22;
            int boxY = bottom - boxH;
            ui.fill(r.x() + 4, boxY, r.width() - 8, boxH, Ui.MENU_BG);
            ui.outline(r.x() + 4, boxY, r.width() - 8, boxH, 0xFFE0B040);
            for (int i = 0; i < words.size(); i++) {
                ui.text(words.get(i), r.x() + 8, boxY + 3 + i * Ui.CELL_H, Ui.TEXT_BRIGHT);
            }
            int bx = button(ui, r.x() + 8, boxY + boxH - 16, 13, "Allow", false, true, 0xFF2E7D32, () -> qbotics.client.agent.ClientAgent.answer(true));
            button(ui, bx + 4, boxY + boxH - 16, 13, "Deny", false, true, 0xFF8E2A2A, () -> qbotics.client.agent.ClientAgent.answer(false));
            bottom = boxY - 4;
        }

        // The messages, newest at the bottom.
        chatTextRect = new Rect(r.x() + 4, y, r.width() - 8, Math.max(0, bottom - y));
        List<List<ChatLog.Piece>> rows = chatRows(ui, chatTextRect.width() - 4);
        int fit = Math.max(1, chatTextRect.height() / Ui.CELL_H);
        session.chatScroll = Math.clamp(session.chatScroll, 0, Math.max(0, rows.size() - fit));
        int last = rows.size() - session.chatScroll;
        int first = Math.max(0, last - fit);
        ui.clip(chatTextRect.x(), chatTextRect.y(), chatTextRect.width(), chatTextRect.height());
        if (rows.isEmpty()) {
            ui.text("Nothing said yet.", chatTextRect.x() + 2, chatTextRect.y() + 2, Ui.TEXT_DIM);
        }
        int rowY = chatTextRect.bottom() - (last - first) * Ui.CELL_H;
        for (int i = first; i < last; i++) {
            int x = chatTextRect.x() + 2;
            for (ChatLog.Piece piece : rows.get(i)) {
                ui.text(piece.text(), x, rowY + 1, piece.color());
                x += ui.textWidth(piece.text());
            }
            rowY += Ui.CELL_H;
        }
        ui.unclip();

        // Where the message is typed.
        Rect input = chatInputRect;
        ui.outline(input.x() - 1, input.y() - 1, input.width() + 2, input.height() + 2, focused ? Ui.STATUS_BG : Ui.BORDER);
        session.chatInput.render(ui, input, focused, cursorOn);
        if (session.chatInput.buffer.text().isEmpty()) {
            ui.text(session.chatToAi ? "Ask the AI... (Enter sends)" : "Say something... (Enter sends)", input.x() + 6, input.y() + 2, Ui.TEXT_DIM);
        }
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
                if (tab != null && tab.view != null && tab.find.open && tab.find.focused) {
                    findType(tab, String.valueOf((char) codepoint));
                } else if (tab != null && tab.view != null) {
                    tab.view.charTyped(codepoint);
                }
            }
            case TERMINAL -> terminalAppend(String.valueOf((char) codepoint));
            case SCRATCH -> session.scratch.charTyped(codepoint);
            case CHAT -> session.chatInput.charTyped(codepoint);
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
        Tab active = session.active();
        boolean hasFile = active != null && active.view != null;
        if (event.isEscape()) {
            // Escape closes the innermost thing that is open; only then the screen itself.
            if (session.focus == Focus.EDITOR && hasFile && active.view.dismissPopup()) {
                return true;
            }
            if (session.focus == Focus.SCRATCH && session.scratch.dismissPopup()) {
                return true;
            }
            if (hasFile && active.find.open) {
                closeFind(active);
                return true;
            }
            return false; // the session keeps everything, so closing loses nothing
        }

        // Shortcuts that work wherever the cursor is.
        if (control && !event.hasAltDown() && hasFile && (key == InputConstants.KEY_F || key == KEY_H)) {
            openFind(active, key == KEY_H);
            return true;
        }
        if (key == KEY_F3 && hasFile && active.find.open) {
            findStep(active, event.hasShiftDown() ? -1 : 1);
            return true;
        }
        if (control && key == InputConstants.KEY_S) {
            saveActive();
            return true;
        }
        if (key == InputConstants.KEY_F5 && !session.editOnly) {
            runActive();
            return true;
        }
        if (key == InputConstants.KEY_F8 && !session.editOnly) {
            backend.terminalInput(TerminalInputPayload.STEP, ""); // run to the next line and hold there
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
        if (control && key == InputConstants.KEY_P) {
            host.toggleFont();
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
                if (hasFile && active.find.open && active.find.focused) {
                    findKey(active, event);
                } else if (hasFile) {
                    active.view.keyPressed(event, host);
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
            case CHAT -> {
                // Enter sends; Shift+Enter starts a new line (for code to ask the AI about).
                if ((key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) && !event.hasShiftDown()) {
                    sendChat();
                } else {
                    session.chatInput.keyPressed(event, host);
                }
            }
        }
        return true;
    }

    private static final int KEY_NUMPAD_MINUS = 333;
    private static final int KEY_H = 72;
    private static final int KEY_F3 = 292;
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
            if (onTerminalEdge(x, y)) {
                drag = Drag.TERMINAL_EDGE;
                return;
            }
            drag = Drag.TEXT;
        }

        if (explorerRect.contains(x, y)) {
            Node node = nodeAt(y);
            if (node != null) {
                session.selectedPath = node.path;
            }
            if (button == RIGHT_BUTTON) {
                openMenuFor(node, (int) x, (int) y);
            } else if (button == LEFT_BUTTON && node != null) {
                // Opened when the button comes up, unless the mouse moved: then it is dragged.
                drag = Drag.NODE;
                pressedNode = node;
                pressX = x;
                pressY = y;
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
                tab.find.focused = false; // typing goes to the text again; the find bar stays open
                tab.view.mouseDown(x, y, shift, doubleClick);
            }
        } else if (terminalRect.contains(x, y)) {
            session.focus = Focus.TERMINAL;
            terminalMouseDown(x, y, button, shift, doubleClick);
        } else if (session.scratchOpen && scratchRect.contains(x, y)) {
            session.focus = Focus.SCRATCH;
            if (button == LEFT_BUTTON && scratchCodeRect.contains(x, y)) {
                session.scratch.mouseDown(x, y, shift, doubleClick);
            }
        } else if (session.chatOpen && scratchRect.contains(x, y)) {
            session.focus = Focus.CHAT;
            if (button == LEFT_BUTTON && chatInputRect.contains(x, y)) {
                session.chatInput.mouseDown(x, y, shift, doubleClick);
            }
        }
    }

    void mouseDragged(double x, double y, int button) {
        if (drag == Drag.NODE) {
            dragX = x;
            dragY = y;
            if (moving == null && Math.hypot(x - pressX, y - pressY) >= DRAG_START) {
                moving = pressedNode;
            }
            if (moving != null) {
                dropFolder = dropFolderAt(x, y);
            }
        } else if (drag == Drag.TERMINAL_EDGE) {
            session.terminalHeight = Math.max(MIN_TERMINAL_H, viewHeight - STATUS_H - (int) y);
        } else if (drag == Drag.TERMINAL_TEXT && session.terminalSelection != null) {
            int[] cell = terminalCellAt(x, y);
            session.terminalSelection[2] = cell[0];
            session.terminalSelection[3] = cell[1];
        } else if (dialog == null && button == LEFT_BUTTON && drag == Drag.TEXT) {
            Tab tab = session.active();
            if (session.focus == Focus.EDITOR && tab != null && tab.view != null) {
                tab.view.mouseDrag(x, y);
            } else if (session.focus == Focus.SCRATCH) {
                session.scratch.mouseDrag(x, y);
            } else if (session.focus == Focus.CHAT) {
                session.chatInput.mouseDrag(x, y);
            }
        }
    }

    void mouseReleased() {
        if (drag == Drag.NODE && pressedNode != null) {
            Node node = pressedNode;
            if (moving != null) {
                if (dropFolder != null) {
                    moveInto(moving, dropFolder);
                }
            } else if (!node.folder) {
                openFile(node.path);
            } else if (!session.expanded.remove(node.path)) {
                session.expanded.add(node.path);
                loadFolder(node.path);
            }
            pressedNode = null;
            moving = null;
            dropFolder = null;
            restingOn = null;
        }
        drag = Drag.NONE;
        for (Tab tab : session.tabs) {
            if (tab.view != null) {
                tab.view.mouseUp();
            }
        }
        session.scratch.mouseUp();
        session.chatInput.mouseUp();
    }

    /**
     * The mouse wheel. amountY is positive for "wheel up"; amountX is sideways scrolling.
     * Holding Shift turns the wheel sideways, for long lines.
     */
    void mouseScrolled(double x, double y, double amountX, double amountY, boolean shift) {
        if (dialog != null || explorerRect == null) {
            return;
        }
        double vertical = shift ? 0 : amountY;
        double horizontal = shift ? amountY : amountX;
        if (explorerRect.contains(x, y)) {
            session.explorerScroll -= (int) Math.round(vertical * ROW_H * 3);
        } else if (editorRect.contains(x, y)) {
            Tab tab = session.active();
            if (tab != null && tab.view != null) {
                tab.view.scroll(vertical, horizontal);
            }
        } else if (terminalRect.contains(x, y)) {
            session.terminalScroll = Math.max(0, session.terminalScroll + (int) Math.round(vertical * 3));
        } else if (session.scratchOpen && scratchCodeRect.contains(x, y)) {
            session.scratch.scroll(vertical, horizontal);
        } else if (session.chatOpen && chatTextRect.contains(x, y)) {
            session.chatScroll = Math.max(0, session.chatScroll + (int) Math.round(vertical * 3));
        }
    }
}
