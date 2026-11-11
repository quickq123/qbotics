package qbotics.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import org.jspecify.annotations.Nullable;
import qbotics.block.CablePort;
import qbotics.block.ExperienceInterfaceBlockEntity;
import qbotics.block.InterfaceBlockEntity;
import qbotics.network.NetworkCalls.CallError;

/**
 * Watching a container for changes (design doc §11.0, "Events"), so a program can sleep
 * until something arrives instead of reading the container again and again.
 *
 * <p>A watch keeps the last contents it reported. Each look reads the container once and
 * returns only what differs from then: a slot whose count went up or down, a tank whose
 * amount changed, a store's energy or experience. So nothing that happens between two looks
 * is missed, and a big chest isn't sent to the program every time.
 *
 * <p>Watches belong to the computer that opened them and are closed when it isn't looking:
 * after a minute unused, or when there are too many. Used on the server thread only.
 */
final class Watches {
    /** A watch not looked at for this long (in game ticks) is closed. */
    private static final long IDLE_TICKS = 20 * 60;
    /** The most watches one computer may have open. */
    private static final int MOST_PER_COMPUTER = 16;

    /** What a watch reads, and what it read last time. */
    private static final class Watch {
        final BlockPos computer;
        final String mac;
        final @Nullable String face;
        final @Nullable String what;
        long lastUsed;
        /** False until the first read, which only notes what is there. */
        boolean known;
        List<ItemStack> items = List.of();
        List<FluidPort.Tank> tanks = List.of();
        long amount;

        Watch(BlockPos computer, String mac, @Nullable String face, @Nullable String what) {
            this.computer = computer;
            this.mac = mac;
            this.face = face;
            this.what = what;
        }
    }

    private static final Map<String, Watch> OPEN = new HashMap<>();

    private Watches() {
    }

    /** ["watch_open", mac, face or null, name or filter or null]: starts watching; returns the watch's name. */
    static JsonElement open(ServerLevel level, CableNetwork network, BlockPos computer, JsonArray args) {
        long now = level.getGameTime();
        OPEN.values().removeIf(watch -> now - watch.lastUsed > IDLE_TICKS);
        List<String> mine = new ArrayList<>();
        OPEN.forEach((token, watch) -> {
            if (watch.computer.equals(computer)) {
                mine.add(token);
            }
        });
        if (mine.size() >= MOST_PER_COMPUTER) {
            // The one looked at least recently makes way.
            mine.sort((a, b) -> Long.compare(OPEN.get(a).lastUsed, OPEN.get(b).lastUsed));
            OPEN.remove(mine.getFirst());
        }
        NetworkDevice device = NetworkCalls.byMac(network, args, 1);
        // The address as the program gave it: behind another antenna it says which.
        Watch watch = new Watch(computer.immutable(), args.get(1).getAsString(), NetworkCalls.string(args, 2), NetworkCalls.string(args, 3));
        watch.lastUsed = now;
        read(NetworkCalls.levelOf(device, level), device, watch, new JsonArray()); // what is there now is where it starts from
        String token = UUID.randomUUID().toString();
        OPEN.put(token, watch);
        return new JsonPrimitive(token);
    }

    /** ["watch_poll", token]: what changed since the last look, as a list (empty if nothing did). */
    static JsonElement poll(ServerLevel level, CableNetwork network, BlockPos computer, JsonArray args) {
        Watch watch = OPEN.get(NetworkCalls.string(args, 1));
        if (watch == null || !watch.computer.equals(computer)) {
            throw new CallError("This watch has ended: it went unused for a minute, or its computer restarted. Start a new one with watch().");
        }
        watch.lastUsed = level.getGameTime();
        NetworkDevice device = NetworkCalls.resolve(network, watch.mac);
        if (device == null) {
            throw new CallError("The device this watch was on is no longer on the network.");
        }
        JsonArray changes = new JsonArray();
        read(NetworkCalls.levelOf(device, level), device, watch, changes);
        return changes;
    }

    /** ["watch_close", token] */
    static JsonElement close(JsonArray args) {
        OPEN.remove(NetworkCalls.string(args, 1));
        return com.google.gson.JsonNull.INSTANCE;
    }

    /** Reads the device now, adds what differs from last time to {@code changes}, and keeps what it read. */
    private static void read(ServerLevel level, NetworkDevice device, Watch watch, JsonArray changes) {
        switch (device.medium()) {
            case ITEM -> {
                List<ItemStack> now = copies(itemsOf(level, device, watch.face));
                NameMatch<Item> match = watch.what == null ? null : NetworkCalls.item(watch.what);
                int slots = Math.max(now.size(), watch.items.size());
                for (int slot = 0; slot < slots && watch.known; slot++) {
                    ItemStack was = slot < watch.items.size() ? watch.items.get(slot) : ItemStack.EMPTY;
                    ItemStack is = slot < now.size() ? now.get(slot) : ItemStack.EMPTY;
                    if (ItemStack.isSameItemSameComponents(was, is) || (was.isEmpty() && is.isEmpty())) {
                        if (was.getCount() != is.getCount()) {
                            itemChange(changes, match, slot, is.isEmpty() ? was : is, was.getCount(), is.getCount());
                        }
                    } else {
                        // One kind of item left the slot and another came: two changes.
                        if (!was.isEmpty()) {
                            itemChange(changes, match, slot, was, was.getCount(), 0);
                        }
                        if (!is.isEmpty()) {
                            itemChange(changes, match, slot, is, 0, is.getCount());
                        }
                    }
                }
                watch.items = now;
            }
            case FLUID -> {
                List<FluidPort.Tank> now = NetworkCalls.fluidSource(level, device, watch.face).tanks();
                NameMatch<Fluid> match = watch.what == null ? null : NetworkCalls.fluid(watch.what);
                for (int tank = 0; tank < now.size() && watch.known; tank++) {
                    FluidPort.Tank was = tank < watch.tanks.size() ? watch.tanks.get(tank) : new FluidPort.Tank(null, 0, 0);
                    FluidPort.Tank is = now.get(tank);
                    boolean sameFluid = was.amount() == 0 || is.amount() == 0 || was.fluid() == is.fluid();
                    if (sameFluid && was.amount() != is.amount()) {
                        fluidChange(changes, match, tank, is.amount() == 0 ? was.fluid() : is.fluid(), was.amount(), is.amount());
                    } else if (!sameFluid) {
                        fluidChange(changes, match, tank, was.fluid(), was.amount(), 0);
                        fluidChange(changes, match, tank, is.fluid(), 0, is.amount());
                    }
                }
                watch.tanks = List.copyOf(now);
            }
            default -> {
                // Energy, or experience: one amount.
                long now = device instanceof ExperienceInterfaceBlockEntity bank ? bank.points()
                        : NetworkCalls.energySource(level, device, watch.face).stored();
                if (watch.known && watch.amount != now) {
                    JsonObject change = new JsonObject();
                    change.addProperty("was", watch.amount);
                    change.addProperty("now", now);
                    changes.add(change);
                }
                watch.amount = now;
            }
        }
        watch.known = true;
    }

    private static List<ItemStack> itemsOf(ServerLevel level, NetworkDevice device, @Nullable String face) {
        if (device instanceof InterfaceBlockEntity store) {
            return store.contents();
        }
        if (device instanceof CablePort port && port.gives()) {
            return (face == null ? NetworkCalls.wholeTarget(level, port) : NetworkCalls.target(level, port, face)).contents();
        }
        throw new CallError("Only an interface, an input or a bus can be watched for items; this is " + NetworkCalls.article(device) + ".");
    }

    private static List<ItemStack> copies(List<ItemStack> stacks) {
        List<ItemStack> copied = new ArrayList<>(stacks.size());
        for (ItemStack stack : stacks) {
            copied.add(stack.copy());
        }
        return copied;
    }

    private static void itemChange(JsonArray changes, @Nullable NameMatch<Item> match, int slot, ItemStack stack, int was, int now) {
        if (match != null && !match.test(stack.getItem())) {
            return;
        }
        JsonObject change = new JsonObject();
        change.addProperty("slot", slot);
        change.addProperty("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        change.addProperty("was", was);
        change.addProperty("now", now);
        changes.add(change);
    }

    private static void fluidChange(JsonArray changes, @Nullable NameMatch<Fluid> match, int tank, @Nullable Fluid fluid, int was, int now) {
        if (fluid == null || (match != null && !match.test(fluid))) {
            return;
        }
        JsonObject change = new JsonObject();
        change.addProperty("tank", tank);
        change.addProperty("fluid", BuiltInRegistries.FLUID.getKey(fluid).toString());
        change.addProperty("was", was);
        change.addProperty("now", now);
        changes.add(change);
    }
}
