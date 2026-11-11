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

/** A Qbot's screen: its 4x4 inventory, hand and upgrade slots, and gauges for its energy cell and two tanks. */
public class QbotScreen extends AbstractContainerScreen<QbotMenu> {
    private static final int GAUGE_W = 14;
    private static final int GAUGE_H = 72;
    private static final int ENERGY = PlateTints.ENERGY;
    private static final int ENERGY_DARK = 0xFF5A1410;

    public QbotScreen(QbotMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, Panels.WIDTH, QbotMenu.TOP_HEIGHT + 96);
        this.inventoryLabelY = QbotMenu.TOP_HEIGHT + 2;
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
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        super.extractLabels(graphics, mouseX, mouseY);
        graphics.text(font, Component.translatable("screen.qbotics.qbot.hand"), QbotMenu.HAND_X - 1, QbotMenu.HAND_Y + 18, Panels.LABEL, false);
        graphics.text(font, Component.translatable("screen.qbotics.qbot.upgrade"), QbotMenu.HAND_X - 4, QbotMenu.UPGRADE_Y + 18, Panels.LABEL, false);
    }

    @Override
    protected void extractTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
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
