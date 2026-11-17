package qbotics.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import org.jspecify.annotations.Nullable;
import qbotics.lesson.Lessons;

/**
 * Where the game's calls tell the lessons (qbotics.lesson) what a program did: after each call
 * that worked ({@link #afterCall}), and around each move of items ({@link #beforeMove},
 * {@link #afterMove}), which can tell sorting, storage, smelting and taking what a screen made.
 * Server thread only, while NetworkCalls answers.
 */
final class LessonHooks {
    private LessonHooks() {
    }

    private static final TagKey<Item> CROPS = TagKey.create(Registries.ITEM, net.minecraft.resources.Identifier.fromNamespaceAndPath("c", "crops"));
    /** A storage this big counts as "many chests as one"; the final lesson asks for twice as many. */
    static final int STORAGE = 4;
    static final int BIG_STORAGE = 8;

    /** Whose program is calling: the owner of the computer, Qbot or Server at that place. */
    static @Nullable UUID owner(ServerLevel level, BlockPos computerPos) {
        return level.getBlockEntity(computerPos) instanceof qbotics.block.ComputerBlockEntity computer ? computer.owner() : null;
    }

    /** After a call that worked: the steps a call shows by itself. */
    static void afterCall(ServerLevel level, CableNetwork network, BlockPos computerPos, JsonArray args, JsonElement result, boolean reachedRemote) {
        UUID owner = owner(level, computerPos);
        if (owner == null) {
            return;
        }
        String method = args.get(0).getAsString();
        if (reachedRemote) {
            Lessons.award(owner, "radio_device");
        }
        switch (method) {
            case "find" -> {
                if (result != null && !result.isJsonNull()) {
                    Lessons.award(owner, "network");
                }
            }
            case "send" -> {
                if (result.isJsonPrimitive() && result.getAsBoolean() && NetworkCalls.EXTERNAL.matcher(args.get(1).getAsString()).matches()) {
                    Lessons.award(owner, "radio_message");
                }
            }
            case "pin_set" -> {
                // ["pin_set", mac, mode, level, pulse]: a signal sent out.
                boolean on = (args.size() > 3 && !args.get(3).isJsonNull() && args.get(3).getAsInt() > 0)
                        || (args.size() > 4 && !args.get(4).isJsonNull());
                if (on) {
                    Lessons.award(owner, "pin_out");
                }
            }
            case "watch_poll" -> {
                if (result.isJsonArray() && !result.getAsJsonArray().isEmpty()) {
                    Lessons.award(owner, "events");
                }
            }
            case "display_open" -> {
                if (args.size() == 2 && network.byMac(args.get(1).getAsString()) instanceof qbotics.block.DisplayBlockEntity display
                        && display.content() instanceof qbotics.display.TextScreen) {
                    Lessons.award(owner, "monitor");
                }
            }
            case "bsensor_break" -> {
                if (result.isJsonObject() && result.getAsJsonObject().has("got")) {
                    for (String id : result.getAsJsonObject().getAsJsonObject("got").keySet()) {
                        var item = BuiltInRegistries.ITEM.getOptional(net.minecraft.resources.Identifier.tryParse(id));
                        if (item.isPresent() && item.get().builtInRegistryHolder().is(CROPS)) {
                            Lessons.award(owner, "harvest");
                        }
                    }
                }
            }
            case "bsensor_place" -> {
                // ["bsensor_place", mac, [dx, dy, dz], ...]: what it placed there is a crop.
                if (result.isJsonObject() && result.getAsJsonObject().get("placed").getAsBoolean()
                        && network.byMac(args.get(1).getAsString()) instanceof qbotics.block.BlockSensorBlockEntity sensor) {
                    JsonArray pos = args.get(2).getAsJsonArray();
                    BlockPos at = sensor.getBlockPos().offset(pos.get(0).getAsInt(), pos.get(1).getAsInt(), pos.get(2).getAsInt());
                    if (level.getBlockState(at).getBlock() instanceof net.minecraft.world.level.block.CropBlock) {
                        Lessons.award(owner, "replant");
                    }
                }
            }
            case "sensor_use" -> {
                // ["sensor_use", mac, uuid, ...]: used on an animal (bred, milked, sheared).
                if (result.isJsonObject() && result.getAsJsonObject().has("used") && result.getAsJsonObject().get("used").getAsBoolean()
                        && level.getEntity(UUID.fromString(args.get(2).getAsString())) instanceof net.minecraft.world.entity.animal.Animal) {
                    Lessons.award(owner, "rancher");
                }
            }
            case "qbot_move" -> {
                if (result.isJsonObject() && result.getAsJsonObject().size() > 1) { // not only {"wait"}: it went
                    Lessons.award(owner, "qbot_move");
                }
            }
            case "qbot_dig" -> {
                if (result.isJsonObject() && result.getAsJsonObject().has("dug")) {
                    Lessons.award(owner, "qbot_dig");
                }
            }
            default -> {
            }
        }
    }

    /** What a move's ends hold before it: a furnace's product, and a screen's product slot. */
    record Before(@Nullable UUID owner, List<int[]> furnaces, List<AbstractFurnaceBlockEntity> furnaceBlocks,
            List<ScreenSessions.Session> products, List<ScreenSessions.Session> fed) {
    }

    static @Nullable Before beforeMove(ServerLevel level, List<NetworkCalls.End> sources, List<NetworkCalls.End> destinations,
            java.util.function.Function<NetworkCalls.End, ScreenSessions.@Nullable Session> sessionOf) {
        UUID owner = NetworkCalls.callerOwner();
        if (owner == null) {
            return null;
        }
        List<int[]> furnaces = new ArrayList<>();
        List<AbstractFurnaceBlockEntity> furnaceBlocks = new ArrayList<>();
        List<ScreenSessions.Session> products = new ArrayList<>();
        for (NetworkCalls.End end : sources) {
            ScreenSessions.Session session = sessionOf.apply(end);
            if (session != null) {
                // Taking from a slot nothing can be put into: what the screen made.
                if (end.slot() >= 0 && end.slot() < session.menu.slots.size()) {
                    var slot = session.menu.slots.get(end.slot());
                    if (slot.hasItem() && !slot.mayPlace(slot.getItem())) {
                        products.add(session);
                    }
                }
            } else if (end.device() instanceof qbotics.block.CablePort plate && plate.level() != null
                    && plate.level().isLoaded(plate.targetPos()) && plate.level().getBlockEntity(plate.targetPos()) instanceof AbstractFurnaceBlockEntity furnace) {
                furnaceBlocks.add(furnace);
                furnaces.add(new int[] {furnace.getItem(2).getCount()});
            }
        }
        // Fed from a big storage: a crafting screen remembers it, for the final lesson.
        List<ScreenSessions.Session> fed = new ArrayList<>();
        boolean big = false;
        for (NetworkCalls.End end : sources) {
            big |= end.group() != null && end.group().size() >= BIG_STORAGE;
        }
        if (big) {
            for (NetworkCalls.End end : destinations) {
                ScreenSessions.Session session = sessionOf.apply(end);
                if (session != null && session.menu instanceof net.minecraft.world.inventory.CraftingMenu) {
                    fed.add(session);
                }
            }
        }
        return new Before(owner, furnaces, furnaceBlocks, products, fed);
    }

    static void afterMove(@Nullable Before before, List<NetworkCalls.End> sources, List<NetworkCalls.End> destinations, long moved) {
        if (before == null || moved <= 0) {
            return;
        }
        UUID owner = before.owner();
        Lessons.award(owner, "move");
        boolean sorted = destinations.size() > 1;
        for (NetworkCalls.End end : destinations) {
            sorted |= end.written().has("only") || end.written().has("without");
            if (end.group() != null) {
                for (NetworkDevice member : end.group()) {
                    sorted |= member instanceof qbotics.block.CablePort port && port.filter() != null;
                }
            } else {
                sorted |= end.device() instanceof qbotics.block.CablePort port && port.filter() != null;
            }
        }
        if (sorted) {
            Lessons.award(owner, "sorting");
        }
        for (NetworkCalls.End end : concat(sources, destinations)) {
            if (end.group() != null && end.group().size() >= STORAGE) {
                Lessons.award(owner, "storage");
            }
        }
        for (int i = 0; i < before.furnaceBlocks().size(); i++) {
            if (before.furnaceBlocks().get(i).getItem(2).getCount() < before.furnaces().get(i)[0]) {
                Lessons.award(owner, "smelting");
            }
        }
        for (ScreenSessions.Session session : before.fed()) {
            session.fedFromBigStorage = true;
        }
        for (ScreenSessions.Session session : before.products()) {
            if (session.menu instanceof net.minecraft.world.inventory.CraftingMenu) {
                Lessons.award(owner, "craft");
                if (session.fedFromBigStorage) {
                    Lessons.award(owner, "factory_craft");
                }
            } else {
                Lessons.award(owner, "workstation");
            }
        }
    }

    private static List<NetworkCalls.End> concat(List<NetworkCalls.End> a, List<NetworkCalls.End> b) {
        List<NetworkCalls.End> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }
}
