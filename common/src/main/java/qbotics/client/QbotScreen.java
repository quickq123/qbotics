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
        return (logArea()[3] - 2 * LOG_PADDING - 14) / LOG_LINE;
    }

    private void drawLog(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int[] a = logArea();
        graphics.fill(0, 0, width, height, 0x90000000);
        graphics.fill(a[0] - 1, a[1] - 1, a[0] + a[2] + 1, a[1] + a[3] + 1, 0xFF808080);
        graphics.fill(a[0], a[1], a[0] + a[2], a[1] + a[3], 0xFF101010);
        graphics.text(font, Component.translatable("screen.qbotics.qbot.log_title", title), a[0] + LOG_PADDING, a[1] + LOG_PADDING, 0xFFFFFFFF, false);
        Component back = Component.translatable("screen.qbotics.qbot.log_back");
        int backX = a[0] + a[2] - LOG_PADDING - font.width(back);
        boolean overBack = mouseX >= backX && mouseX < a[0] + a[2] - LOG_PADDING && mouseY >= a[1] + LOG_PADDING - 1 && mouseY < a[1] + LOG_PADDING + 9;
        graphics.text(font, back, backX, a[1] + LOG_PADDING, overBack ? 0xFFFFFF60 : 0xFFB0B0B0, false);
        List<String> lines = qbotics.client.ide.ClientIde.terminalHistory(menu.qbotId());
        int rows = logRows();
        logScroll = Math.clamp(logScroll, 0, Math.max(0, lines.size() - rows));
        int end = lines.size() - logScroll;
        int start = Math.max(0, end - rows);
        int top = a[1] + LOG_PADDING + 14;
        for (int i = start; i < end; i++) {
            graphics.text(font, font.plainSubstrByWidth(lines.get(i), a[2] - 2 * LOG_PADDING), a[0] + LOG_PADDING, top + (i - start) * LOG_LINE,
                    0xFFD8D8D8, false);
        }
        if (logScroll > 0) {
            Component more = Component.translatable("screen.qbotics.qbot.log_more", logScroll);
            graphics.text(font, more, a[0] + a[2] - LOG_PADDING - font.width(more), a[1] + a[3] - LOG_PADDING - 8, 0xFFF0B030, false);
        }
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
        // Only "Back" works while the log is open; the slots under it don't.
        int[] a = logArea();
        int backX = a[0] + a[2] - LOG_PADDING - font.width(Component.translatable("screen.qbotics.qbot.log_back"));
        if (event.x() >= backX && event.x() < a[0] + a[2] - LOG_PADDING && event.y() >= a[1] + LOG_PADDING - 1 && event.y() < a[1] + LOG_PADDING + 9) {
            logOpen = false;
        }
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
            logOpen = false; // Escape closes the log, not the screen
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
        Panels.slot(graphics, x + QbotMenu.HAND_X - 1, y + QbotMenu.HAND_Y - 1);
        Panels.socket(graphics, x + QbotMenu.HAND_X, y + QbotMenu.UPGRADE_Y);
        Panels.slot(graphics, x + QbotMenu.HAND_X - 1, y + QbotMenu.UPGRADE_Y - 1);
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
        graphics.text(font, Component.translatable("screen.qbotics.qbot.upgrade"), QbotMenu.HAND_X - 4, QbotMenu.UPGRADE_Y + 18, Panels.LABEL, false);
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
