package qbotics.block;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;
import qbotics.Qbotics;
import qbotics.network.NetworkDevice.Kind;

/**
 * What the world plates do (design doc §11.0.1): the Logic Pin's redstone and watching, and
 * the Breaker's timing and drops. The Placer's work, and the parts that need a program's
 * sources and destinations, are in {@code NetworkCalls}.
 */
public final class WorldPlates {
    private WorldPlates() {
    }

    // ---- Logic Pin ----------------------------------------------------------------------

    /** The signal a cable gives out on one side: a Logic Pin's level if it is driving one there, else 0. */
    public static int signalOut(BlockGetter level, BlockPos pos, Direction side) {
        if (!(level.getBlockEntity(pos) instanceof PortBlockEntity host)) {
            return 0;
        }
        CablePort pin = host.port(side);
        return pin != null && pin.kind == Kind.PIN && pin.pinOutput ? pin.pinLevel : 0;
    }

    /** True if a cable has a Logic Pin on that side, for redstone dust to join to. */
    public static boolean pinOn(BlockGetter level, BlockPos pos, Direction side) {
        return level.getBlockEntity(pos) instanceof PortBlockEntity host && host.kindAt(side) == Kind.PIN;
    }

    /** Something next to a cable changed: each Logic Pin on it looks again at what it points at. */
    public static void sense(Level level, BlockPos pos) {
        if (level.isClientSide() || !(level.getBlockEntity(pos) instanceof PortBlockEntity host)) {
            return;
        }
        for (CablePort port : host.ports()) {
            if (port.kind == Kind.PIN) {
                sense(level, port);
            }
        }
    }

    /**
     * Brings a pin up to date: the block it points at (counting a change, as an observer
     * would) and, when it is reading, the signal coming from there (counting rises and falls).
     * The first look only sets what it has seen, so putting a pin on counts as nothing.
     */
    public static void sense(Level level, CablePort pin) {
        BlockPos target = pin.targetPos();
        if (!level.isLoaded(target)) {
            return;
        }
        BlockState seen = level.getBlockState(target);
        if (pin.lastSeen == null) {
            pin.lastSeen = seen;
        } else if (seen != pin.lastSeen) {
            pin.lastSeen = seen;
            pin.blockChanges++;
        }
        // A pin that drives a signal doesn't read: it would only read its own.
        int now = pin.pinOutput ? 0 : level.getSignal(target, pin.pointing());
        if (pin.inputKnown && now != pin.inputLevel) {
            pin.signalChanges++;
            if (pin.inputLevel == 0) {
                pin.rising++;
            } else if (now == 0) {
                pin.falling++;
            }
        }
        pin.inputLevel = now;
        pin.inputKnown = true;
    }

    /** What a pin reads now, fresh from the world. */
    public static int read(Level level, CablePort pin) {
        sense(level, pin);
        return pin.inputLevel;
    }

    /**
     * Sets a pin to drive a signal (or to read, with {@code output} false). A pulse drives the
     * level for that many ticks and then drops to 0 by itself.
     */
    public static void drive(ServerLevel level, CablePort pin, boolean output, int value, int pulseTicks) {
        boolean wasOutput = pin.pinOutput;
        pin.pinOutput = output;
        pin.pinLevel = output ? Math.clamp(value, 0, 15) : 0;
        pin.pulseUntil = output && pulseTicks > 0 ? level.getGameTime() + pulseTicks : 0;
        if (pin.pulseUntil > 0) {
            level.scheduleTick(pin.getBlockPos(), level.getBlockState(pin.getBlockPos()).getBlock(), pulseTicks);
        }
        pin.host.setChanged();
        if (wasOutput != output) {
            pin.inputKnown = false; // starts reading afresh, without counting its own signal going away
        }
        changed(level, pin);
        sense(level, pin);
    }

    /** Tells the blocks around the cable and around the pin's target that its signal changed. */
    private static void changed(ServerLevel level, CablePort pin) {
        Block cable = level.getBlockState(pin.getBlockPos()).getBlock();
        level.updateNeighborsAt(pin.getBlockPos(), cable, null);
        level.updateNeighborsAt(pin.targetPos(), cable, null);
    }

    /** A scheduled tick on a cable: pulses that have run their time drop to 0. */
    public static void endPulses(ServerLevel level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof PortBlockEntity host)) {
            return;
        }
        long now = level.getGameTime();
        long next = Long.MAX_VALUE;
        for (CablePort pin : host.ports()) {
            if (pin.kind != Kind.PIN || pin.pulseUntil == 0) {
                continue;
            }
            if (now >= pin.pulseUntil) {
                pin.pulseUntil = 0;
                pin.pinLevel = 0;
                host.setChanged();
                changed(level, pin);
            } else {
                next = Math.min(next, pin.pulseUntil);
            }
        }
        if (next != Long.MAX_VALUE) {
            level.scheduleTick(pos, level.getBlockState(pos).getBlock(), (int) (next - now));
        }
    }

    // ---- Breaker -----------------------------------------------------------------------

    /** The tools a Breaker breaks with, the best of each kind: it uses whichever is right for the block. */
    private static final List<Item> TOOLS = List.of(Items.NETHERITE_PICKAXE, Items.NETHERITE_AXE, Items.NETHERITE_SHOVEL,
            Items.NETHERITE_HOE, Items.NETHERITE_SWORD);

    /** The tool a Breaker uses on a block: the right one if any is, else the fastest. */
    public static ItemStack toolFor(BlockState state) {
        ItemStack fastest = ItemStack.EMPTY;
        float best = 1.0f;
        for (Item item : TOOLS) {
            ItemStack tool = new ItemStack(item);
            if (tool.isCorrectToolForDrops(state)) {
                return tool;
            }
            float speed = tool.getDestroySpeed(state);
            if (speed > best) {
                best = speed;
                fastest = tool;
            }
        }
        return fastest;
    }

    /** How many ticks breaking a block takes, as a player with that tool (and no enchantments or effects) would. */
    public static int breakTicks(BlockState state, BlockGetter level, BlockPos pos, ItemStack tool) {
        float hardness = state.getDestroySpeed(level, pos);
        if (hardness <= 0) {
            return 0;
        }
        float speed = tool.isEmpty() ? 1.0f : Math.max(1.0f, tool.getDestroySpeed(state));
        boolean right = !state.requiresCorrectToolForDrops() || (!tool.isEmpty() && tool.isCorrectToolForDrops(state));
        float perTick = speed / hardness / (right ? 30.0f : 100.0f);
        return perTick >= 1.0f ? 0 : (int) Math.ceil(1.0f / perTick);
    }

    /**
     * How many more ticks a Breaker must work at a block before it gives way. The first call
     * for a block starts the work; another block in its place starts it again.
     */
    public static int breakWait(ServerLevel level, CablePort breaker, BlockState state, ItemStack tool) {
        int needed = breakTicks(state, level, breaker.targetPos(), tool);
        long now = level.getGameTime();
        if (breaker.breaking != state) {
            breaker.breaking = state;
            breaker.breakingSince = now;
        }
        return (int) Math.max(0, breaker.breakingSince + needed - now);
    }

    /** What breaking a block gives, and whether that includes what its container held. */
    public record Drops(List<ItemStack> stacks, boolean contents) {
    }

    /**
     * What breaking a block will give: its drops for that tool, plus what a container holds
     * if breaking would spill it. Drops are worked out once for a block and remembered until
     * the block changes, so a program can't break and retry until chance gives it a better
     * drop. A block with a block entity is worked out afresh each time, since what it holds
     * can change.
     */
    public static Drops dropsOf(ServerLevel level, CablePort breaker, BlockState state, ServerPlayer player, ItemStack tool) {
        BlockPos pos = breaker.targetPos();
        BlockEntity entity = level.getBlockEntity(pos);
        List<ItemStack> drops;
        if (entity == null && state == breaker.dropsFor) {
            drops = breaker.drops;
        } else {
            drops = List.copyOf(Block.getDrops(state, level, pos, entity, player, tool));
            breaker.dropsFor = entity == null ? state : null;
            breaker.drops = drops;
        }
        List<ItemStack> all = new ArrayList<>();
        for (ItemStack drop : drops) {
            all.add(drop.copy());
        }
        boolean contents = spills(entity, drops);
        if (contents) {
            Container container = (Container) entity;
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack held = container.getItem(slot);
                if (!held.isEmpty()) {
                    all.add(held.copy());
                }
            }
        }
        return new Drops(all, contents);
    }

    /**
     * True if breaking this block spills its container's contents into the world (a chest,
     * a barrel). A block whose own drop carries its contents (a shulker box) doesn't spill.
     */
    public static boolean spills(@Nullable BlockEntity entity, List<ItemStack> drops) {
        if (!(entity instanceof Container)) {
            return false;
        }
        for (ItemStack drop : drops) {
            if (drop.has(DataComponents.CONTAINER)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Removes the block a Breaker has broken, leaving whatever fluid was in it. Its contents,
     * already delivered, are cleared first so nothing spills.
     */
    public static void remove(ServerLevel level, CablePort breaker, BlockState state, boolean contentsTaken) {
        BlockPos pos = breaker.targetPos();
        if (contentsTaken && level.getBlockEntity(pos) instanceof Container container) {
            container.clearContent();
        }
        level.levelEvent(2001, pos, Block.getId(state)); // the breaking sound and pieces
        level.setBlock(pos, state.getFluidState().createLegacyBlock(), Block.UPDATE_ALL);
        breaker.breaking = null;
        breaker.dropsFor = null;
        breaker.drops = List.of();
        Qbotics.LOGGER.debug("Breaker {} broke {} at {}", breaker.mac(), state, pos);
    }
}
