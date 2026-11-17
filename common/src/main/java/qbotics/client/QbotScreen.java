package qbotics.client;

import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import qbotics.qbot.QbotBlockEntity;
import qbotics.qbot.QbotMenu;

/**
 * A Qbot's screen: its 4x4 inventory, hand and upgrade slots, gauges for its energy cell and
 * two tanks, and its program's Run (Resume), Pause and Stop buttons over a strip showing what
 * the program is doing and the last lines it printed.
 */
public class QbotScreen extends AbstractContainerScreen<QbotMenu> {
    private static final int GAUGE_W = 14;
    private static final int GAUGE_H = 72;
    private static final int ENERGY = PlateTints.ENERGY;
    private static final int ENERGY_DARK = 0xFF5A1410;
    /** The crafting table's top, shown faintly over the slots that are its crafting grid. */
    private static final net.minecraft.resources.Identifier CRAFTING_TOP =
            net.minecraft.resources.Identifier.withDefaultNamespace("textures/block/crafting_table_top.png");
    /** The slot what it crafts from its grid goes to first (right of the grid's middle row). */
    public static final int CRAFT_OUTPUT = 7;

    private net.minecraft.client.gui.components.Button run;
    private net.minecraft.client.gui.components.Button pause;
    private net.minecraft.client.gui.components.Button stop;
    /** How many of the program's last lines show under its state. */
    private static final int OUTPUT_LINES = 2;
    private net.minecraft.client.gui.components.Button log;
    /** True while the log is shown over the screen: everything the program printed, read-only. */
    private boolean logOpen;
    /** How many lines the log is scrolled up from its end; 0 follows new output. */
    private int logScroll;
    private static final int LOG_LINE = 10;
    private static final int LOG_PADDING = 6;

    public QbotScreen(QbotMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, Panels.WIDTH, QbotMenu.TOP_HEIGHT + 96);
        this.inventoryLabelY = QbotMenu.TOP_HEIGHT + 2;
    }

    @Override
    protected void init() {
        super.init();
        run = addRenderableWidget(button("screen.qbotics.qbot.run", QbotMenu.RUN_BUTTON, 0));
        pause = addRenderableWidget(button("screen.qbotics.qbot.pause", QbotMenu.PAUSE_BUTTON, 1));
        stop = addRenderableWidget(button("screen.qbotics.qbot.stop", QbotMenu.STOP_BUTTON, 2));
        // In the corner of the program's strip: open its whole output, read-only.
        log = addRenderableWidget(net.minecraft.client.gui.components.Button.builder(Component.translatable("screen.qbotics.qbot.log"), button -> {
            logOpen = true;
            logScroll = 0;
        }).bounds(leftPos + Panels.WIDTH - 40, topPos + QbotMenu.OUTPUT_Y + 2, 32, 12).build());
        update();
    }

    private net.minecraft.client.gui.components.Button button(String label, int id, int place) {
        return net.minecraft.client.gui.components.Button.builder(Component.translatable(label), button -> {
            net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
            if (minecraft.gameMode != null) {
                minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
            }
        }).bounds(leftPos + 8 + place * 54, topPos + QbotMenu.BUTTONS_Y, 52, 16).build();
    }

    /** Run says Resume while paused; each button works only when it would do something. */
    private void update() {
        var state = menu.programState();
        run.setMessage(Component.translatable(state == qbotics.computer.ServerComputer.ProgramState.PAUSED
                ? "screen.qbotics.qbot.resume" : "screen.qbotics.qbot.run"));
        run.active = state != qbotics.computer.ServerComputer.ProgramState.RUNNING;
        pause.active = state == qbotics.computer.ServerComputer.ProgramState.RUNNING;
        stop.active = state != qbotics.computer.ServerComputer.ProgramState.STOPPED;
    }

    @Override
    public void containerTick() {
        super.containerTick();
        update();
        for (var button : java.util.List.of(run, pause, stop, log)) {
            button.visible = !logOpen;
        }
    }

    // ---- the log: the program's whole output, read-only, over the screen ----

    /** Shows the log, as its button does. */
    public void openLog() {
        logOpen = true;
        logScroll = 0;
    }

    /** Where the log is drawn: most of the window, as wide as a terminal line needs. */
    private int[] logArea() {
        int w = Math.min(width - 20, qbotics.computer.Terminal.COLS * 6 + 2 * LOG_PADDING);
        int h = height - 30;
        return new int[] {(width - w) / 2, 15, w, h};
    }

    private int logRows() {
        return (logArea()[3] - 2 * LOG_PADDING - 14 - 12) / LOG_LINE; // the title above, the hint below
    }

    /** Lines selected in the log by dragging, as indexes into its lines (from, to, either order), or -1. */
    private int selectFrom = -1;
    private int selectTo = -1;
    private boolean selecting;
    /** When the log was last copied, for the "Copied" note. */
    private long copiedAt;

    /** Selects lines of the log, as dragging across them does. */
    public void selectLog(int from, int to) {
        selectFrom = from;
        selectTo = to;
    }

    private List<String> logLines() {
        return qbotics.client.ide.ClientIde.terminalHistory(menu.qbotId());
    }

    /** The first and one-past-last line shown, for this many lines in all. */
    private int[] shown(int count) {
        int rows = logRows();
        logScroll = Math.clamp(logScroll, 0, Math.max(0, count - rows));
        int end = count - logScroll;
        return new int[] {Math.max(0, end - rows), end};
    }

    private int logTop() {
        return logArea()[1] + LOG_PADDING + 14;
    }

    /** The line under a height on the screen, kept within what is shown. */
    private int lineAt(double y, int[] shown) {
        int line = shown[0] + (int) Math.floor((y - logTop()) / LOG_LINE);
        return Math.clamp(line, shown[0], Math.max(shown[0], shown[1] - 1));
    }

    /** The header's two words, right-aligned: Copy, then Back. Returns {copyX, backX, right}. */
    private int[] headerXs() {
        int[] a = logArea();
        int right = a[0] + a[2] - LOG_PADDING;
        int backX = right - font.width(Component.translatable("screen.qbotics.qbot.log_back"));
        int copyX = backX - 14 - font.width(Component.translatable("screen.qbotics.qbot.log_copy"));
        return new int[] {copyX, backX, right};
    }

    private boolean inHeader(double x, double y, int from, int to) {
        int top = logArea()[1] + LOG_PADDING;
        return x >= from && x < to && y >= top - 1 && y < top + 9;
    }

    /** Copies the selected lines, or the whole log if none are selected. */
    private void copyLog() {
        List<String> lines = logLines();
        if (lines.isEmpty()) {
            return;
        }
        int from = 0;
        int to = lines.size() - 1;
        if (selectFrom >= 0) {
            from = Math.clamp(Math.min(selectFrom, selectTo), 0, lines.size() - 1);
            to = Math.clamp(Math.max(selectFrom, selectTo), 0, lines.size() - 1);
        }
        net.minecraft.client.Minecraft.getInstance().keyboardHandler.setClipboard(String.join("\n", lines.subList(from, to + 1)));
        copiedAt = System.currentTimeMillis();
    }

    private void drawLog(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int[] a = logArea();
        graphics.fill(0, 0, width, height, 0x90000000);
        graphics.fill(a[0] - 1, a[1] - 1, a[0] + a[2] + 1, a[1] + a[3] + 1, 0xFF808080);
        graphics.fill(a[0], a[1], a[0] + a[2], a[1] + a[3], 0xFF101010);
        graphics.text(font, Component.translatable("screen.qbotics.qbot.log_title", title), a[0] + LOG_PADDING, a[1] + LOG_PADDING, 0xFFFFFFFF, false);
        int[] h = headerXs();
        boolean justCopied = System.currentTimeMillis() - copiedAt < 1500;
        Component copy = Component.translatable(justCopied ? "screen.qbotics.qbot.log_copied"
                : selectFrom >= 0 ? "screen.qbotics.qbot.log_copy_selected" : "screen.qbotics.qbot.log_copy");
        int copyX = h[1] - 14 - font.width(copy);
        graphics.text(font, copy, copyX, a[1] + LOG_PADDING,
                justCopied ? 0xFF58E060 : inHeader(mouseX, mouseY, copyX, h[1] - 14) ? 0xFFFFFF60 : 0xFFB0B0B0, false);
        graphics.text(font, Component.translatable("screen.qbotics.qbot.log_back"), h[1], a[1] + LOG_PADDING,
                inHeader(mouseX, mouseY, h[1], h[2]) ? 0xFFFFFF60 : 0xFFB0B0B0, false);
        List<String> lines = logLines();
        int[] s = shown(lines.size());
        int top = logTop();
        int low = selectFrom < 0 ? -1 : Math.min(selectFrom, selectTo);
        int high = selectFrom < 0 ? -1 : Math.max(selectFrom, selectTo);
        for (int i = s[0]; i < s[1]; i++) {
            int y = top + (i - s[0]) * LOG_LINE;
            if (i >= low && i <= high) {
                graphics.fill(a[0] + LOG_PADDING - 2, y - 1, a[0] + a[2] - LOG_PADDING + 2, y + LOG_LINE - 1, 0xFF2C4A78);
            }
            graphics.text(font, font.plainSubstrByWidth(lines.get(i), a[2] - 2 * LOG_PADDING), a[0] + LOG_PADDING, y, 0xFFD8D8D8, false);
        }
        Component hint = Component.translatable(logScroll > 0 ? "screen.qbotics.qbot.log_more" : "screen.qbotics.qbot.log_hint", logScroll);
        graphics.text(font, hint, a[0] + a[2] - LOG_PADDING - font.width(hint), a[1] + a[3] - LOG_PADDING - 8,
                logScroll > 0 ? 0xFFF0B030 : 0xFF707070, false);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        if (logOpen) {
            graphics.nextStratum(); // over the slots and their items
            drawLog(graphics, mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        if (!logOpen) {
            return super.mouseClicked(event, doubleClick);
        }
        // While the log is open only it takes clicks; the slots under it don't.
        int[] h = headerXs();
        if (inHeader(event.x(), event.y(), h[1], h[2])) {
            logOpen = false;
            selectFrom = selectTo = -1;
        } else if (inHeader(event.x(), event.y(), h[0], h[1] - 14)) {
            copyLog();
        } else if (event.button() == 0 && event.y() >= logTop()) {
            List<String> lines = logLines();
            if (!lines.isEmpty()) {
                // Start a selection; shift-click extends the one there is.
                int line = lineAt(event.y(), shown(lines.size()));
                if (!(event.hasShiftDown() && selectFrom >= 0)) {
                    selectFrom = line;
                }
                selectTo = line;
                selecting = true;
            }
        } else {
            selectFrom = selectTo = -1;
        }
        return true;
    }

    @Override
    public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event, double dx, double dy) {
        if (!logOpen) {
            return super.mouseDragged(event, dx, dy);
        }
        if (selecting) {
            int[] a = logArea();
            // Dragging past the top or bottom scrolls the log.
            if (event.y() < logTop()) {
                logScroll++;
            } else if (event.y() > a[1] + a[3] - LOG_PADDING) {
                logScroll--;
            }
            List<String> lines = logLines();
            selectTo = lineAt(event.y(), shown(lines.size()));
        }
        return true;
    }

    @Override
    public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
        if (!logOpen) {
            return super.mouseReleased(event);
        }
        selecting = false;
        return true;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (!logOpen) {
            return super.mouseScrolled(x, y, scrollX, scrollY);
        }
        logScroll += (int) Math.signum(scrollY) * 3;
        return true;
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (logOpen && event.key() == com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE) {
            if (selectFrom >= 0) {
                selectFrom = selectTo = -1; // Escape first clears a selection
            } else {
                logOpen = false; // then closes the log, not the screen
            }
            return true;
        }
        if (logOpen && event.hasControlDown() && event.key() == com.mojang.blaze3d.platform.InputConstants.KEY_C) {
            copyLog();
            return true;
        }
        if (logOpen && event.hasControlDown() && event.key() == com.mojang.blaze3d.platform.InputConstants.KEY_A) {
            List<String> lines = logLines();
            if (!lines.isEmpty()) {
                selectFrom = 0;
                selectTo = lines.size() - 1;
            }
            return true;
        }
        return super.keyPressed(event);
    }

    private int gaugeX(int gauge) {
        return QbotMenu.GAUGES_X + gauge * (GAUGE_W + 4);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick);
        int x = leftPos;
        int y = topPos;
        Panels.panel(graphics, x, y, QbotMenu.TOP_HEIGHT);
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                Panels.slot(graphics, x + QbotMenu.GRID_X - 1 + col * 18, y + QbotMenu.GRID_Y - 1 + row * 18);
            }
        }
        // Its built-in crafting table (qbot.craft()): the table's top, faint, over the 3x3 grid
        // (slots 0-2, 4-6, 8-10), and the slot its product goes to first shaded.
        int gridX = x + QbotMenu.GRID_X - 1;
        int gridY = y + QbotMenu.GRID_Y - 1;
        graphics.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, CRAFTING_TOP, gridX + 3, gridY + 3, 0.0F, 0.0F,
                48, 48, 16, 16, 16, 16, 0x70FFFFFF);
        int outX = gridX + (CRAFT_OUTPUT % 4) * 18;
        int outY = gridY + (CRAFT_OUTPUT / 4) * 18;
        graphics.fill(outX + 1, outY + 1, outX + 17, outY + 17, 0x50E8B040);
        Panels.slot(graphics, x + QbotMenu.HAND_X - 1, y + QbotMenu.HAND_Y - 1);
        for (int socket = 0; socket < QbotBlockEntity.UPGRADE_SLOTS; socket++) {
            int sx = x + QbotMenu.UPGRADE_X + (socket % 2) * 18;
            int sy = y + QbotMenu.UPGRADE_Y + (socket / 2) * 18;
            Panels.socket(graphics, sx, sy);
            Panels.slot(graphics, sx - 1, sy - 1);
        }
        int top = y + QbotMenu.GRID_Y - 1;
        int inside = GAUGE_H - 2;
        // The energy cell.
        int ex = x + gaugeX(0);
        Panels.sunken(graphics, ex, top, GAUGE_W, GAUGE_H, ENERGY_DARK);
        int filled = (int) Math.ceil(inside * (double) menu.energy() / QbotBlockEntity.ENERGY_CAPACITY);
        graphics.fill(ex + 1, top + 1 + inside - filled, ex + GAUGE_W - 1, top + GAUGE_H - 1, ENERGY);
        // The two tanks, with a mark at each bucket.
        for (int tank = 0; tank < QbotBlockEntity.TANKS; tank++) {
            int tx = x + gaugeX(1 + tank);
            Panels.sunken(graphics, tx, top, GAUGE_W, GAUGE_H, 0xFF2B2B2B);
            int level = (int) Math.ceil(inside * (double) menu.amount(tank) / QbotBlockEntity.TANK_CAPACITY);
            Panels.fluid(graphics, menu.fluid(tank), tx + 1, top + 1 + inside - level, GAUGE_W - 2, level);
            for (int bucket = 1; bucket < 4; bucket++) {
                int mark = top + 1 + inside - inside * bucket / 4;
                graphics.fill(tx + 1, mark, tx + 5, mark + 1, 0x90FFFFFF);
            }
        }
        // The program's strip: what it is doing, then its last lines.
        Panels.sunken(graphics, x + 7, y + QbotMenu.OUTPUT_Y, Panels.WIDTH - 14, 31, 0xFF141414);
    }

    /**
     * Each inventory slot's number (0 to 15, as inventory.list() and the grid count them),
     * small, in its top-left corner. A probe in Slots mode draws its own numbers instead.
     */
    private void drawSlotNumbers(GuiGraphicsExtractor graphics) {
        var player = net.minecraft.client.Minecraft.getInstance().player;
        if (player != null && qbotics.block.ProbeItem.showsSlots(player)) {
            return;
        }
        graphics.pose().pushMatrix();
        graphics.pose().scale(0.5F, 0.5F);
        for (int slot = 0; slot < QbotBlockEntity.SLOTS; slot++) {
            int sx = QbotMenu.GRID_X + (slot % 4) * 18;
            int sy = QbotMenu.GRID_Y + (slot / 4) * 18;
            graphics.text(font, String.valueOf(slot), sx * 2, sy * 2, 0xFFE8E8E8, true);
        }
        graphics.pose().popMatrix();
    }

    private void drawProgram(GuiGraphicsExtractor graphics) {
        var state = menu.programState();
        int colour = switch (state) {
            case RUNNING -> 0xFF58E060;
            case PAUSED -> 0xFFF0B030;
            case STOPPED -> menu.ending() == QbotMenu.Ending.ERROR ? 0xFFF05050 : 0xFFB0B0B0;
        };
        String key = switch (state) {
            case RUNNING -> "screen.qbotics.qbot.state.running";
            case PAUSED -> "screen.qbotics.qbot.state.paused";
            case STOPPED -> "screen.qbotics.qbot.ended." + menu.ending().name().toLowerCase(java.util.Locale.ROOT);
        };
        int textX = 10;
        int width = Panels.WIDTH - 20;
        graphics.text(font, Component.translatable(key), textX, QbotMenu.OUTPUT_Y + 3, colour, false);
        List<String> lines = new java.util.ArrayList<>();
        for (String line : qbotics.client.ide.ClientIde.terminalLines(menu.qbotId())) {
            if (!line.isBlank() && !line.strip().endsWith("$")) {
                lines.add(line.strip());
            }
        }
        List<String> last = lines.subList(Math.max(0, lines.size() - OUTPUT_LINES), lines.size());
        for (int i = 0; i < last.size(); i++) {
            graphics.text(font, font.plainSubstrByWidth(last.get(i), width), textX, QbotMenu.OUTPUT_Y + 13 + i * 9, 0xFFD8D8D8, false);
        }
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        super.extractLabels(graphics, mouseX, mouseY);
        graphics.text(font, Component.translatable("screen.qbotics.qbot.hand"), QbotMenu.HAND_X - 1, QbotMenu.HAND_Y + 18, Panels.LABEL, false);
        Component upgradeLabel = Component.translatable("screen.qbotics.qbot.upgrade");
        // Centred in the column between the grid and the gauges (40 wide, from 80).
        graphics.text(font, upgradeLabel, 80 + (40 - font.width(upgradeLabel)) / 2, QbotMenu.UPGRADE_Y + 37, Panels.LABEL, false);
        drawSlotNumbers(graphics);
        drawProgram(graphics);
    }

    @Override
    protected void extractTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        if (logOpen) {
            return;
        }
        super.extractTooltip(graphics, mouseX, mouseY);
        int mx = mouseX - leftPos;
        int my = mouseY - topPos;
        if (my < QbotMenu.GRID_Y - 1 || my >= QbotMenu.GRID_Y - 1 + GAUGE_H) {
            return;
        }
        for (int gauge = 0; gauge < 3; gauge++) {
            if (mx < gaugeX(gauge) || mx >= gaugeX(gauge) + GAUGE_W) {
                continue;
            }
            Component line;
            if (gauge == 0) {
                line = Component.translatable("screen.qbotics.qbot.energy", menu.energy(), QbotBlockEntity.ENERGY_CAPACITY);
            } else {
                Fluid fluid = menu.fluid(gauge - 1);
                line = fluid == Fluids.EMPTY
                        ? Component.translatable("screen.qbotics.tank_empty", QbotBlockEntity.TANK_CAPACITY)
                        : Component.translatable("screen.qbotics.tank_holds", Panels.fluidName(fluid), menu.amount(gauge - 1), QbotBlockEntity.TANK_CAPACITY);
            }
            graphics.setComponentTooltipForNextFrame(font, List.of(Component.translatable("screen.qbotics.tank", gauge - 1), line).subList(gauge == 0 ? 1 : 0, 2),
                    mouseX, mouseY);
        }
    }
}
