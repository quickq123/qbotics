package qbotics.network;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import org.jspecify.annotations.Nullable;
import qbotics.Qbotics;
import qbotics.block.CablePort;

/**
 * Screen access (design doc §11.0.2): a Bus working the slots of a block's screen, for
 * blocks whose slots exist only there (a crafting table) or that offer nothing to pipes.
 *
 * <p>The Bus opens the screen on the server as a stand-in for its owner, the same way a
 * player's right-click would, so the block and any protection mod see the owner. While the
 * screen is open its slots can be a source or a destination of a move. Closing it gives the
 * block the chance to hand back what was left in it, and that is delivered to interfaces;
 * anything with nowhere to go is dropped at the Bus, never destroyed.
 *
 * <p>Used on the server thread only.
 */
public final class ScreenSessions {
    /** A screen left open this long with nothing done to it is closed, so a stopped program can't strand items. */
    private static final int IDLE_TICKS = 20 * 60;

    /** What the loader hands back when a stand-in opens a block's screen. */
    public record Opened(ServerPlayer player, AbstractContainerMenu menu) {
    }

    /** One open screen: who is standing in, the screen, and when it was last used. */
    public static final class Session {
        final ServerPlayer player;
        final AbstractContainerMenu menu;
        final ServerLevel level;
        final BlockPos busPos;
        final ItemPort port;
        long lastUsed;

        Session(Opened opened, ServerLevel level, BlockPos busPos) {
            this.player = opened.player();
            this.menu = opened.menu();
            this.level = level;
            this.busPos = busPos;
            this.port = new MenuPort(this);
            this.lastUsed = level.getGameTime();
        }

        /** The screen's slots as somewhere items can be read, put in and taken out. */
        public ItemPort port() {
            lastUsed = level.getGameTime();
            return port;
        }

        /** True for a slot that belongs to the screen, not to the stand-in's own inventory. */
        boolean ownSlot(Slot slot) {
            return !(slot.container instanceof Inventory);
        }

        /** The screen's own slots, each with its number on the screen. */
        public List<Slot> slots() {
            List<Slot> own = new ArrayList<>();
            for (Slot slot : menu.slots) {
                if (ownSlot(slot)) {
                    own.add(slot);
                }
            }
            return own;
        }
    }

    private static final Map<MinecraftServer, Map<UUID, Session>> OPEN = new WeakHashMap<>();

    private ScreenSessions() {
    }

    private static Map<UUID, Session> sessions(MinecraftServer server) {
        return OPEN.computeIfAbsent(server, s -> new HashMap<>());
    }

    /** The screen a Bus has open, or null. A screen whose block has gone, or is out of reach, is closed first. */
    public static @Nullable Session of(ServerLevel level, CablePort bus) {
        Session session = sessions(level.getServer()).get(bus.deviceId());
        if (session != null && (bus.isRemoved() || !session.menu.stillValid(session.player))) {
            close(level.getServer(), bus.deviceId(), List.of());
            return null;
        }
        return session;
    }

    /**
     * Opens the screen of the block a Bus points at. If the Bus already has one open, that one is used.
     *
     * @return the session, or null if the block has no screen to open (or refused the stand-in)
     */
    public static @Nullable Session open(ServerLevel level, CablePort bus, UUID owner) {
        Session existing = of(level, bus);
        if (existing != null) {
            return existing;
        }
        Opened opened = Qbotics.platform().openScreenAs(level, bus.targetPos(), bus.pointing().getOpposite(), owner, bus.deviceId());
        if (opened == null) {
            return null;
        }
        Session session = new Session(opened, level, bus.getBlockPos().immutable());
        sessions(level.getServer()).put(bus.deviceId(), session);
        return session;
    }

    /**
     * Closes a Bus's screen. The block gets to hand back what was in its slots, as it does for
     * a player; that goes into the given places in order, and whatever is left over is dropped
     * at the Bus.
     *
     * @return how many items had nowhere to go and were dropped
     */
    public static int close(MinecraftServer server, UUID busId, List<ItemPort> leftoversTo) {
        Session session = sessions(server).remove(busId);
        if (session == null) {
            return 0;
        }
        ServerPlayer player = session.player;
        try {
            session.menu.removed(player);
        } catch (RuntimeException e) {
            Qbotics.LOGGER.warn("A screen opened by a bus failed while closing", e);
        }
        player.containerMenu = player.inventoryMenu;
        int dropped = 0;
        Inventory inventory = player.getInventory();
        // Whatever the block gave back is now in the stand-in's hands and pockets.
        List<ItemStack> returned = new ArrayList<>();
        returned.add(session.menu.getCarried());
        session.menu.setCarried(ItemStack.EMPTY);
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            returned.add(inventory.removeItemNoUpdate(i));
        }
        for (ItemStack stack : returned) {
            int left = stack.getCount();
            for (ItemPort place : leftoversTo) {
                if (left <= 0) {
                    break;
                }
                left -= place.insert(stack, left, false);
            }
            if (left > 0) {
                dropped += left;
                Block.popResource(session.level, session.busPos, stack.copyWithCount(left));
            }
        }
        return dropped;
    }

    /** Once a tick: closes screens that have been left open and unused. */
    public static void tick(MinecraftServer server, java.util.function.Function<Session, List<ItemPort>> leftoversFor) {
        Map<UUID, Session> open = OPEN.get(server);
        if (open == null || open.isEmpty()) {
            return;
        }
        List<UUID> stale = new ArrayList<>();
        for (Iterator<Map.Entry<UUID, Session>> it = open.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Session> entry = it.next();
            Session session = entry.getValue();
            if (session.level.getGameTime() - session.lastUsed > IDLE_TICKS || !session.menu.stillValid(session.player)) {
                stale.add(entry.getKey());
            }
        }
        for (UUID id : stale) {
            Session session = open.get(id);
            close(server, id, leftoversFor.apply(session));
        }
    }

    /** The level and position of the Bus that opened a session, for finding where its leftovers may go. */
    public static ServerLevel levelOf(Session session) {
        return session.level;
    }

    public static BlockPos busPosOf(Session session) {
        return session.busPos;
    }

    /**
     * A screen's slots as an {@link ItemPort}. Slots are numbered as the screen numbers them;
     * the stand-in's own inventory slots are there but always read as empty and take nothing.
     */
    private static final class MenuPort implements ItemPort {
        private final Session session;

        MenuPort(Session session) {
            this.session = session;
        }

        private @Nullable Slot slot(int index) {
            if (index < 0 || index >= session.menu.slots.size()) {
                return null;
            }
            Slot slot = session.menu.slots.get(index);
            return session.ownSlot(slot) && slot.isActive() ? slot : null;
        }

        @Override
        public List<ItemStack> contents() {
            List<ItemStack> stacks = new ArrayList<>(session.menu.slots.size());
            for (int i = 0; i < session.menu.slots.size(); i++) {
                Slot slot = slot(i);
                stacks.add(slot == null ? ItemStack.EMPTY : slot.getItem().copy());
            }
            return stacks;
        }

        @Override
        public int insertInto(int index, ItemStack like, int max, boolean simulate) {
            Slot slot = slot(index);
            if (slot == null || like.isEmpty() || max <= 0 || !slot.mayPlace(like)) {
                return 0;
            }
            ItemStack held = slot.getItem();
            if (!held.isEmpty() && !ItemStack.isSameItemSameComponents(held, like)) {
                return 0;
            }
            int room = Math.min(max, slot.getMaxStackSize(like) - held.getCount());
            if (room <= 0) {
                return 0;
            }
            if (!simulate) {
                slot.setByPlayer(like.copyWithCount(held.getCount() + room));
                session.menu.broadcastChanges();
            }
            return room;
        }

        @Override
        public int extractFrom(int index, ItemStack like, int max, boolean simulate) {
            Slot slot = slot(index);
            if (slot == null || max <= 0) {
                return 0;
            }
            ItemStack held = slot.getItem();
            if (held.isEmpty() || !ItemStack.isSameItemSameComponents(held, like) || !slot.mayPickup(session.player)) {
                return 0;
            }
            // A slot nothing can be put into is a product slot: taking from it uses up the
            // ingredients, and it only ever gives its whole stack.
            boolean product = !slot.mayPlace(held);
            if (product && max < held.getCount()) {
                return 0;
            }
            int take = product ? held.getCount() : Math.min(max, held.getCount());
            if (!simulate) {
                ItemStack taken = slot.remove(take);
                slot.onTake(session.player, taken);
                session.menu.broadcastChanges();
                return taken.getCount();
            }
            return take;
        }

        @Override
        public int insert(ItemStack like, int max, boolean simulate) {
            // Into whichever slots will take it, in the screen's order.
            int left = max;
            for (int i = 0; i < session.menu.slots.size() && left > 0; i++) {
                left -= insertInto(i, like, left, simulate);
            }
            return max - left;
        }

        @Override
        public int extract(ItemStack like, int max, boolean simulate) {
            int left = max;
            for (int i = 0; i < session.menu.slots.size() && left > 0; i++) {
                left -= extractFrom(i, like, left, simulate);
            }
            return max - left;
        }
    }
}
