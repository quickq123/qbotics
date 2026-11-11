package qbotics.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import qbotics.block.FluidInterfaceBlockEntity;
import qbotics.block.FluidInterfaceMenu;

/**
 * The Fluid Interface's screen: four tanks, each with its filter socket above and its bucket
 * slot below (see {@link FluidInterfaceMenu}). Hovering a tank or a socket says what it holds
 * or is kept for.
 */
public class FluidInterfaceScreen extends AbstractContainerScreen<FluidInterfaceMenu> {
    private static final int TANK_WIDTH = 18;

    public FluidInterfaceScreen(FluidInterfaceMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, Panels.WIDTH, FluidInterfaceMenu.TOP_HEIGHT + 96);
        this.inventoryLabelY = FluidInterfaceMenu.TOP_HEIGHT + 2;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick);
        int x = leftPos;
        int y = topPos;
        Panels.panel(graphics, x, y, FluidInterfaceMenu.TOP_HEIGHT);
        for (int tank = 0; tank < FluidInterfaceMenu.TANKS; tank++) {
            int left = x + FluidInterfaceMenu.columnX(tank);
            Panels.socket(graphics, left + 1, y + FluidInterfaceMenu.SOCKET_Y);
            // The tank: a sunken window, fluid filling it from the bottom, and a mark at each bucket.
            int top = y + FluidInterfaceMenu.TANK_Y;
            int height = FluidInterfaceMenu.TANK_HEIGHT;
            Panels.sunken(graphics, left, top, TANK_WIDTH, height, 0xFF2B2B2B);
            int inside = height - 2;
            int filled = (int) Math.ceil(inside * (double) menu.amount(tank) / FluidInterfaceBlockEntity.TANK_CAPACITY);
            Panels.fluid(graphics, menu.fluid(tank), left + 1, top + 1 + inside - filled, TANK_WIDTH - 2, filled);
            for (int bucket = 1; bucket < 4; bucket++) {
                int mark = top + 1 + inside - inside * bucket / 4;
                graphics.fill(left + 1, mark, left + 6, mark + 1, 0x90FFFFFF);
            }
            Panels.slot(graphics, left, y + FluidInterfaceMenu.BUCKET_Y - 1);
        }
    }

    @Override
    protected void extractTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        super.extractTooltip(graphics, mouseX, mouseY);
        int mx = mouseX - leftPos;
        int my = mouseY - topPos;
        for (int tank = 0; tank < FluidInterfaceMenu.TANKS; tank++) {
            int left = FluidInterfaceMenu.columnX(tank);
            if (mx < left || mx >= left + TANK_WIDTH) {
                continue;
            }
            Fluid filter = menu.filter(tank);
            List<Component> lines = new ArrayList<>();
            if (my >= FluidInterfaceMenu.TANK_Y && my < FluidInterfaceMenu.TANK_Y + FluidInterfaceMenu.TANK_HEIGHT) {
                Fluid fluid = menu.fluid(tank);
                lines.add(Component.translatable("screen.qbotics.tank", tank));
                lines.add(fluid == Fluids.EMPTY
                        ? Component.translatable("screen.qbotics.tank_empty", FluidInterfaceBlockEntity.TANK_CAPACITY)
                        : Component.translatable("screen.qbotics.tank_holds", Panels.fluidName(fluid), menu.amount(tank),
                                FluidInterfaceBlockEntity.TANK_CAPACITY));
                lines.add(filter == Fluids.EMPTY ? Component.translatable("screen.qbotics.tank_any")
                        : Component.translatable("screen.qbotics.tank_only", Panels.fluidName(filter)));
            } else if (my >= FluidInterfaceMenu.SOCKET_Y && my < FluidInterfaceMenu.SOCKET_Y + 16) {
                lines.add(filter == Fluids.EMPTY ? Component.translatable("screen.qbotics.tank_any")
                        : Component.translatable("screen.qbotics.tank_only", Panels.fluidName(filter)));
                lines.add(Component.translatable("screen.qbotics.socket_fluid_help"));
            } else if (my >= FluidInterfaceMenu.BUCKET_Y - 1 && my < FluidInterfaceMenu.BUCKET_Y + 17 && hoveredSlot != null && !hoveredSlot.hasItem()) {
                lines.add(Component.translatable("screen.qbotics.bucket_help"));
            }
            if (!lines.isEmpty()) {
                graphics.setComponentTooltipForNextFrame(font, lines, mouseX, mouseY);
            }
        }
    }
}
