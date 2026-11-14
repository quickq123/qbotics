package qbotics.client.display;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;
import qbotics.Qbotics;
import qbotics.QboticsContent;
import qbotics.display.Tablets;
import qbotics.net.TabletPayloads;

/**
 * Tablets in this player's game (design doc §10.6): each held tablet's tabs, and which screen
 * its selected tab is (a made-up position, see {@link Tablets#KEY_Y}); and the tab bar its
 * view shows above the screen.
 */
public final class ClientTablets {
    /** The height of the tab bar at the top of a tablet's view. */
    public static final int BAR = 20;

    private ClientTablets() {
    }

    private record State(List<String> tabs, int selected, BlockPos screen) {
    }

    private static final Map<UUID, State> STATES = new HashMap<>();

    public static void onState(TabletPayloads.State payload) {
        STATES.put(payload.tablet(), new State(List.copyOf(payload.tabs()), payload.selected(), payload.screen().immutable()));
    }

    public static void onOpen(TabletPayloads.Open payload) {
        Minecraft.getInstance().setScreen(screenFor(payload.tablet()));
    }

    /** The screen a tablet's selected tab shows (a made-up position), or null if it has none. */
    public static @Nullable BlockPos screen(@Nullable UUID tablet) {
        State state = tablet == null ? null : STATES.get(tablet);
        return state == null || state.selected() < 0 ? null : state.screen();
    }

    /** For the tooltip: the tablet's tabs, as last heard while it was held. */
    public static List<String> tabs(ItemStack stack) {
        State state = STATES.get(stack.get(QboticsContent.TABLET_ID.get()));
        return state == null ? List.of() : state.tabs();
    }

    /** The view that shows a tablet's selected tab: the web close-up for a web page, the other close-up otherwise. */
    public static Screen screenFor(UUID tablet) {
        BlockPos screen = screen(tablet);
        BlockPos at = screen == null ? Tablets.keyPos(0) : screen;
        return ClientWebPanels.view(at) != null ? new WebCloseUpScreen(at, tablet) : new CloseUpScreen(at, tablet);
    }

    /** True while the player holds this tablet. */
    public static boolean holding(UUID tablet) {
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return false;
        }
        for (InteractionHand hand : InteractionHand.values()) {
            if (tablet.equals(player.getItemInHand(hand).get(QboticsContent.TABLET_ID.get()))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Each tick of a tablet's view: closes it when the tablet is put away, and swaps it for the
     * other view when the selected tab changes or its screen turns out to be another kind.
     */
    static void tick(Screen current, UUID tablet, BlockPos showing, boolean web) {
        if (!holding(tablet)) {
            current.onClose();
            return;
        }
        BlockPos screen = screen(tablet);
        BlockPos want = screen == null ? Tablets.keyPos(0) : screen;
        boolean wantWeb = ClientWebPanels.view(want) != null;
        if (!want.equals(showing) || wantWeb != web) {
            Minecraft.getInstance().setScreen(screenFor(tablet));
        }
    }

    // ---- Delete: unpair the tab showing, after a second press to be sure ----

    /** How long after the first Delete a second one unpairs. */
    private static final long CONFIRM_MILLIS = 3000;
    private static @Nullable UUID armedTablet;
    private static int armedIndex = -1;
    private static long armedAt;

    private static boolean armed(UUID tablet, State state) {
        return tablet.equals(armedTablet) && armedIndex == state.selected() && System.currentTimeMillis() - armedAt < CONFIRM_MILLIS;
    }

    /** Delete was pressed in a tablet's view: the first press asks, a second (within 3 seconds) unpairs the tab showing. */
    static void deletePressed(UUID tablet) {
        State state = STATES.get(tablet);
        if (state == null || state.selected() < 0 || state.selected() >= state.tabs().size()) {
            return;
        }
        if (armed(tablet, state)) {
            Qbotics.platform().sendToServer(new TabletPayloads.Unpair(tablet, state.selected()));
            armedTablet = null;
            armedIndex = -1;
        } else {
            armedTablet = tablet;
            armedIndex = state.selected();
            armedAt = System.currentTimeMillis();
        }
    }

    /** Draws the tab bar: one tab per paired network, the selected one lit. */
    static void drawBar(GuiGraphicsExtractor graphics, Font font, UUID tablet, int width, int mouseX, int mouseY) {
        State state = STATES.get(tablet);
        graphics.fill(0, 0, width, BAR, 0xFF1A1E24);
        if (state == null || state.tabs().isEmpty()) {
            graphics.centeredText(font, Component.translatable("screen.qbotics.tablet.unpaired"), width / 2, (BAR - 8) / 2, 0xFFB0B0B0);
            return;
        }
        int x = 8;
        for (int i = 0; i < state.tabs().size(); i++) {
            String label = state.tabs().get(i);
            int w = font.width(label) + 14;
            boolean selected = i == state.selected();
            boolean over = mouseX >= x && mouseX < x + w && mouseY >= 3 && mouseY < BAR;
            graphics.fill(x, 3, x + w, BAR, selected ? 0xFF3C4148 : over ? 0xFF2C3138 : 0xFF23272D);
            if (selected) {
                graphics.fill(x, 3, x + w, 5, 0xFF8CC8FF);
            }
            graphics.text(font, label, x + 7, 8, selected ? 0xFFFFFFFF : 0xFFB0B0B0, false);
            x += w + 3;
        }
        // At the right: how to unpair the tab showing, or the question after a first Delete.
        boolean asking = state.selected() >= 0 && state.selected() < state.tabs().size() && armed(tablet, state);
        Component hint = asking
                ? Component.translatable("screen.qbotics.tablet.delete_confirm", state.tabs().get(state.selected()))
                : Component.translatable("screen.qbotics.tablet.delete_hint");
        int hintX = width - 8 - font.width(hint);
        if (asking || hintX > x + 8) {
            if (asking) {
                graphics.fill(hintX - 6, 3, width - 2, BAR, 0xFF5A1E1E);
            }
            graphics.text(font, hint, hintX, 8, asking ? 0xFFFFD0D0 : 0xFF6A7078, false);
        }
    }

    /** A click on the tab bar: picks that tab. True if it was on the bar. */
    static boolean clickBar(Font font, UUID tablet, double mouseX, double mouseY) {
        if (mouseY >= BAR) {
            return false;
        }
        State state = STATES.get(tablet);
        if (state != null) {
            int x = 8;
            for (int i = 0; i < state.tabs().size(); i++) {
                int w = font.width(state.tabs().get(i)) + 14;
                if (mouseX >= x && mouseX < x + w && i != state.selected()) {
                    Qbotics.platform().sendToServer(new TabletPayloads.Select(tablet, i));
                }
                x += w + 3;
            }
        }
        return true;
    }
}
