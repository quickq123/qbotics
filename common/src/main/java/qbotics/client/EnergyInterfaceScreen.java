package qbotics.client;

import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import qbotics.block.EnergyInterfaceMenu;

/**
 * The Energy Interface's screen: a gauge of what it stores, between a slot that charges a
 * battery and a slot that drains one (see {@link EnergyInterfaceMenu}).
 */
public class EnergyInterfaceScreen extends AbstractContainerScreen<EnergyInterfaceMenu> {
    /** The red of energy, as on its plates (PlateTints), and a darker one for the gauge's empty part. */
    private static final int ENERGY = PlateTints.ENERGY;
    private static final int ENERGY_DARK = 0xFF5A1410;

    public EnergyInterfaceScreen(EnergyInterfaceMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, Panels.WIDTH, EnergyInterfaceMenu.TOP_HEIGHT + 96);
        this.inventoryLabelY = EnergyInterfaceMenu.TOP_HEIGHT + 2;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick);
        int x = leftPos;
        int y = topPos;
        Panels.panel(graphics, x, y, EnergyInterfaceMenu.TOP_HEIGHT);
        Panels.slot(graphics, x + EnergyInterfaceMenu.CHARGE_X, y + EnergyInterfaceMenu.SLOT_Y);
        Panels.slot(graphics, x + EnergyInterfaceMenu.DRAIN_X, y + EnergyInterfaceMenu.SLOT_Y);
        // The gauge fills from the bottom; a line at each quarter.
        int left = x + EnergyInterfaceMenu.GAUGE_X;
        int top = y + EnergyInterfaceMenu.GAUGE_Y;
        int width = EnergyInterfaceMenu.GAUGE_WIDTH;
        int height = EnergyInterfaceMenu.GAUGE_HEIGHT;
        Panels.sunken(graphics, left, top, width, height, ENERGY_DARK);
        int inside = height - 2;
        int filled = (int) Math.ceil(inside * (double) menu.stored() / menu.capacity());
        graphics.fill(left + 1, top + 1 + inside - filled, left + width - 1, top + height - 1, ENERGY);
        for (int quarter = 1; quarter < 4; quarter++) {
            int mark = top + 1 + inside - inside * quarter / 4;
            graphics.fill(left + 1, mark, left + 6, mark + 1, 0x90FFFFFF);
        }
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        super.extractLabels(graphics, mouseX, mouseY);
        String amount = String.format("%,d FE", menu.stored());
        graphics.text(font, amount, EnergyInterfaceMenu.GAUGE_X + (EnergyInterfaceMenu.GAUGE_WIDTH - font.width(amount)) / 2,
                EnergyInterfaceMenu.GAUGE_Y + EnergyInterfaceMenu.GAUGE_HEIGHT + 4, Panels.LABEL, false);
        Component charge = Component.translatable("screen.qbotics.charge");
        Component drain = Component.translatable("screen.qbotics.drain");
        graphics.text(font, charge, EnergyInterfaceMenu.CHARGE_X + 9 - font.width(charge) / 2, EnergyInterfaceMenu.SLOT_Y - 11, Panels.LABEL, false);
        graphics.text(font, drain, EnergyInterfaceMenu.DRAIN_X + 9 - font.width(drain) / 2, EnergyInterfaceMenu.SLOT_Y - 11, Panels.LABEL, false);
    }

    @Override
    protected void extractTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        super.extractTooltip(graphics, mouseX, mouseY);
        int mx = mouseX - leftPos;
        int my = mouseY - topPos;
        if (mx >= EnergyInterfaceMenu.GAUGE_X && mx < EnergyInterfaceMenu.GAUGE_X + EnergyInterfaceMenu.GAUGE_WIDTH
                && my >= EnergyInterfaceMenu.GAUGE_Y && my < EnergyInterfaceMenu.GAUGE_Y + EnergyInterfaceMenu.GAUGE_HEIGHT) {
            graphics.setComponentTooltipForNextFrame(font, List.of(Component.literal(String.format("%,d / %,d FE", menu.stored(), menu.capacity()))),
                    mouseX, mouseY);
        } else if (hoveredSlot != null && !hoveredSlot.hasItem() && my >= EnergyInterfaceMenu.SLOT_Y && my < EnergyInterfaceMenu.SLOT_Y + 18) {
            boolean charging = mx < Panels.WIDTH / 2;
            graphics.setComponentTooltipForNextFrame(font, List.of(Component.translatable(charging ? "screen.qbotics.charge_help" : "screen.qbotics.drain_help")),
                    mouseX, mouseY);
        }
    }
}
