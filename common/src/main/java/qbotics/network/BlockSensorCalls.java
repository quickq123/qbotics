package qbotics.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import qbotics.Qbotics;
import qbotics.block.BlockSensorBlockEntity;
import qbotics.block.WorldPlates;
import qbotics.network.NetworkCalls.CallError;

/**
 * The Block Sensor's calls (docs/reference.md §6.14): the blocks in its box, and breaking,
 * placing and using items there as its owner. Positions are offsets from the sensor,
 * [dx, dy, dz], so a program keeps working wherever the farm is rebuilt. What changes in the
 * box comes to programs through a watch (Watches), fed by the game's own block changes.
 */
final class BlockSensorCalls {
    private BlockSensorCalls() {
    }

    static JsonElement call(String method, ServerLevel level, CableNetwork network, JsonArray args) {
        NetworkDevice device = NetworkCalls.byMac(network, args, 1);
        if (!(device instanceof BlockSensorBlockEntity sensor)) {
            throw new CallError("That call is for a Block Sensor; this is " + NetworkCalls.article(device) + ".");
        }
        level = NetworkCalls.levelOf(device, level);
        return switch (method) {
            case "bsensor_box" -> box(sensor, args);
            case "bsensor_blocks" -> blocks(level, sensor, args);
            case "bsensor_block" -> {
                BlockPos pos = at(sensor, args, 2);
                JsonObject info = describe(level, sensor, pos);
                if (args.size() > 3 && !args.get(3).isJsonNull() && args.get(3).getAsBoolean()) {
                    addData(level, sensor, pos, info);
                }
                yield info;
            }
            case "bsensor_sign" -> writeSign(level, sensor, args);
            case "bsensor_break" -> breakBlock(level, network, sensor, args);
            case "bsensor_place" -> place(level, network, sensor, args);
            case "bsensor_use" -> use(level, network, sensor, args);
            default -> throw new CallError("This computer doesn't know the call '" + method + "'.");
        };
    }

    /** ["bsensor_box", mac, [minX, minY, minZ, maxX, maxY, maxZ] or null]: sets its box (offsets from it); returns it. */
    private static JsonArray box(BlockSensorBlockEntity sensor, JsonArray args) {
        if (args.size() > 2 && args.get(2).isJsonArray()) {
            JsonArray written = args.get(2).getAsJsonArray();
            if (written.size() != 6) {
                throw new CallError("A box is six numbers: the lowest and highest offset on each of x, y and z.");
            }
            int[] box = new int[6];
            for (int i = 0; i < 6; i++) {
                box[i] = written.get(i).getAsInt();
            }
            String problem = BlockSensorBlockEntity.problem(box);
            if (problem != null) {
                throw new CallError("That box can't be had: " + problem + ".");
            }
            sensor.setBox(box);
        }
        JsonArray out = new JsonArray();
        for (int value : sensor.box()) {
            out.add(value);
        }
        return out;
    }

    /** The position at args[index], an offset [dx, dy, dz] from the sensor, which must be in its box. */
    private static BlockPos at(BlockSensorBlockEntity sensor, JsonArray args, int index) {
        if (index >= args.size() || !args.get(index).isJsonArray() || args.get(index).getAsJsonArray().size() != 3) {
            throw new CallError("A place is an offset from the sensor: (dx, dy, dz), as blocks() gives it.");
        }
        JsonArray offset = args.get(index).getAsJsonArray();
        BlockPos pos = sensor.getBlockPos().offset(offset.get(0).getAsInt(), offset.get(1).getAsInt(), offset.get(2).getAsInt());
        if (!sensor.covers(pos)) {
            throw new CallError("(" + offset.get(0) + ", " + offset.get(1) + ", " + offset.get(2) + ") is outside the sensor's area.");
        }
        if (sensor.getLevel() != null && !sensor.getLevel().isLoaded(pos)) {
            throw new CallError("(" + offset.get(0) + ", " + offset.get(1) + ", " + offset.get(2) + ") is in a part of the world that isn't loaded now.");
        }
        return pos;
    }

    static String blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    /** A block's state as programs read it: {"age": "7"}. */
    static JsonObject stateJson(BlockState state) {
        JsonObject properties = new JsonObject();
        for (Property<?> property : state.getProperties()) {
            properties.addProperty(property.getName(), valueName(state, property));
        }
        return properties;
    }

    static JsonArray offset(BlockSensorBlockEntity sensor, BlockPos pos) {
        JsonArray out = new JsonArray();
        out.add(pos.getX() - sensor.getBlockPos().getX());
        out.add(pos.getY() - sensor.getBlockPos().getY());
        out.add(pos.getZ() - sensor.getBlockPos().getZ());
        return out;
    }

    private static JsonObject describe(ServerLevel level, BlockSensorBlockEntity sensor, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        JsonObject info = new JsonObject();
        info.add("pos", offset(sensor, pos));
        info.addProperty("block", blockId(state));
        info.add("state", stateJson(state));
        info.addProperty("air", state.isAir());
        info.add("fluid", state.getFluidState().isEmpty() ? JsonNull.INSTANCE
                : new com.google.gson.JsonPrimitive(BuiltInRegistries.FLUID.getKey(state.getFluidState().getType()).toString()));
        info.addProperty("biome", level.getBiome(pos).unwrapKey().map(key -> key.identifier().toString()).orElse("unknown"));
        JsonObject light = new JsonObject();
        light.addProperty("block", level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, pos));
        light.addProperty("sky", level.getBrightness(net.minecraft.world.level.LightLayer.SKY, pos));
        light.addProperty("now", level.getMaxLocalRawBrightness(pos));
        info.add("light", light);
        var entity = level.getBlockEntity(pos);
        if (entity instanceof net.minecraft.world.level.block.entity.SignBlockEntity sign) {
            JsonObject text = new JsonObject();
            text.add("front", signSide(sign.getFrontText()));
            text.add("back", signSide(sign.getBackText()));
            text.addProperty("waxed", sign.isWaxed());
            info.add("sign", text);
        }
        if (entity instanceof net.minecraft.world.level.block.entity.LecternBlockEntity lectern && lectern.hasBook()) {
            info.add("book", book(lectern.getBook(), lectern.getPage()));
        }
        return info;
    }

    /** The most of a block's own data one read returns, as JSON text. */
    private static final int MOST_DATA = 32 * 1024;

    /**
     * A block's own saved data (a spawner's mob, a beehive's bees, a modded machine's), in the
     * game's raw form, as "data"; null for a block without any. Read-only.
     */
    private static void addData(ServerLevel level, BlockSensorBlockEntity sensor, BlockPos pos, JsonObject info) {
        var entity = level.getBlockEntity(pos);
        if (entity == null) {
            info.add("data", JsonNull.INSTANCE);
            return;
        }
        // A computer's, Qbot's or antenna's data holds its owner, its drive and its password's hash.
        // Another player's is read only where the server says so (sensors.read_other_players_computers).
        if (entity instanceof qbotics.limits.Limited device && !qbotics.QboticsConfig.sensorsReadOtherPlayers
                && !java.util.Objects.equals(device.owner(), sensor.owner())) {
            info.add("data", JsonNull.INSTANCE);
            info.addProperty("data_private", true);
            return;
        }
        net.minecraft.nbt.CompoundTag tag = entity.saveWithoutMetadata(level.registryAccess());
        JsonElement json = new com.mojang.serialization.Dynamic<>(net.minecraft.nbt.NbtOps.INSTANCE, tag)
                .convert(com.mojang.serialization.JsonOps.INSTANCE).getValue();
        String text = json.toString();
        if (text.length() > MOST_DATA) {
            info.add("data", JsonNull.INSTANCE);
            info.addProperty("data_too_large", text.length());
        } else {
            info.add("data", json);
        }
    }

    /** The most letters on one line of a sign that a program writes. */
    private static final int MOST_LINE = 80;

    /**
     * ["bsensor_sign", mac, pos, [up to 4 lines], back, colour or null, glowing or null]: writes a
     * sign (its front, or its back) as the owner would. A waxed sign is refused: wax is how a
     * player locks one.
     */
    private static JsonObject writeSign(ServerLevel level, BlockSensorBlockEntity sensor, JsonArray args) {
        BlockPos pos = at(sensor, args, 2);
        if (!(level.getBlockEntity(pos) instanceof net.minecraft.world.level.block.entity.SignBlockEntity sign)) {
            throw new CallError("There is no sign there, but " + blockId(level.getBlockState(pos)) + ".");
        }
        if (sign.isWaxed()) {
            throw new CallError("That sign is waxed: it can't be changed.");
        }
        ServerPlayer player = standIn(level, sensor);
        if (!Qbotics.platform().mayBreak(level, player, pos)) {
            throw new CallError("The sensor's owner may not change blocks there.");
        }
        if (args.size() <= 3 || !args.get(3).isJsonArray() || args.get(3).getAsJsonArray().size() > 4) {
            throw new CallError("A sign has four lines: give a list of up to four.");
        }
        boolean front = !(args.size() > 4 && !args.get(4).isJsonNull() && args.get(4).getAsBoolean());
        net.minecraft.world.level.block.entity.SignText text = sign.getText(front);
        JsonArray lines = args.get(3).getAsJsonArray();
        for (int line = 0; line < 4; line++) {
            String words = line < lines.size() && !lines.get(line).isJsonNull() ? lines.get(line).getAsString() : "";
            if (words.length() > MOST_LINE) {
                words = words.substring(0, MOST_LINE);
            }
            text = text.setMessage(line, net.minecraft.network.chat.Component.literal(words));
        }
        String colour = NetworkCalls.string(args, 5);
        if (colour != null) {
            net.minecraft.world.item.DyeColor dye = net.minecraft.world.item.DyeColor.byName(colour, null);
            if (dye == null) {
                throw new CallError("'" + colour + "' isn't a dye colour: black, white, red, green, blue, yellow, ...");
            }
            text = text.setColor(dye);
        }
        if (args.size() > 6 && !args.get(6).isJsonNull()) {
            text = text.setHasGlowingText(args.get(6).getAsBoolean());
        }
        sign.setText(text, front);
        return describe(level, sensor, pos);
    }

    /** One side of a sign: its four lines, its colour, and whether it glows. */
    private static JsonObject signSide(net.minecraft.world.level.block.entity.SignText side) {
        JsonObject out = new JsonObject();
        JsonArray lines = new JsonArray();
        for (int line = 0; line < 4; line++) {
            lines.add(side.getMessage(line, false).getString());
        }
        out.add("lines", lines);
        out.addProperty("color", side.getColor().getName());
        out.addProperty("glowing", side.hasGlowingText());
        return out;
    }

    /** A book on a lectern: title, author, its pages as text, and the page it is open at (from 0). */
    private static JsonObject book(ItemStack stack, int page) {
        JsonObject out = new JsonObject();
        JsonArray pages = new JsonArray();
        net.minecraft.world.item.component.WrittenBookContent written = stack.get(net.minecraft.core.component.DataComponents.WRITTEN_BOOK_CONTENT);
        net.minecraft.world.item.component.WritableBookContent writable = stack.get(net.minecraft.core.component.DataComponents.WRITABLE_BOOK_CONTENT);
        if (written != null) {
            out.addProperty("title", written.title().raw());
            out.addProperty("author", written.author());
            written.pages().forEach(text -> pages.add(text.raw().getString()));
        } else {
            out.add("title", JsonNull.INSTANCE);
            out.add("author", JsonNull.INSTANCE);
            if (writable != null) {
                writable.pages().forEach(text -> pages.add(text.raw()));
            }
        }
        out.add("pages", pages);
        out.addProperty("page", page);
        out.addProperty("item", NetworkCalls.itemId(stack.getItem()));
        return out;
    }

    /** ["bsensor_blocks", mac, name or filter or null]: every block in the box that isn't air (or that the filter passes). */
    private static JsonArray blocks(ServerLevel level, BlockSensorBlockEntity sensor, JsonArray args) {
        String wanted = NetworkCalls.string(args, 2);
        NameMatch<Block> match = wanted == null ? null : NetworkCalls.block(wanted);
        JsonArray list = new JsonArray();
        int[] box = sensor.box();
        BlockPos at = sensor.getBlockPos();
        for (int y = box[1]; y <= box[4]; y++) {
            for (int z = box[2]; z <= box[5]; z++) {
                for (int x = box[0]; x <= box[3]; x++) {
                    BlockPos pos = at.offset(x, y, z);
                    if (!level.isLoaded(pos)) {
                        continue;
                    }
                    BlockState state = level.getBlockState(pos);
                    if (match == null ? !state.isAir() : match.testState(state)) {
                        list.add(describe(level, sensor, pos));
                    }
                }
            }
        }
        return list;
    }

    /** The owner's stand-in, at the sensor. A sensor nobody owns can't act. */
    private static ServerPlayer standIn(ServerLevel level, BlockSensorBlockEntity sensor) {
        UUID owner = sensor.owner();
        if (owner == null) {
            throw new CallError("This Block Sensor has no owner to act for (it wasn't placed by a player), so it can only look.");
        }
        return Qbotics.platform().standIn(level, owner, sensor.deviceId(), sensor.getBlockPos(), Direction.UP);
    }

    /**
     * ["bsensor_break", mac, pos, destinations or null]: breaks a block as the owner would with the
     * right netherite tool, taking as long as that does (the answer is {"wait": ticks} until then;
     * crops give way at once). The drops go to the destinations, all or nothing; with none, they
     * fall where the block was (for a Collector, say).
     */
    private static JsonObject breakBlock(ServerLevel level, CableNetwork network, BlockSensorBlockEntity sensor, JsonArray args) {
        NetworkCalls.transportCheck(level);
        BlockPos pos = at(sensor, args, 2);
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            throw new CallError("There is nothing there to break.");
        }
        if (state.getBlock() instanceof LiquidBlock) {
            throw new CallError("That is " + blockId(state) + "; take it up with use(pos, \"bucket\", ...) instead.");
        }
        if (state.getDestroySpeed(level, pos) < 0) {
            throw new CallError(blockId(state) + " can't be broken.");
        }
        ServerPlayer player = standIn(level, sensor);
        if (!Qbotics.platform().mayBreak(level, player, pos)) {
            throw new CallError("The sensor's owner may not break blocks there.");
        }
        ItemStack tool = WorldPlates.toolFor(state);
        int needed = WorldPlates.breakTicks(state, level, pos, tool);
        long done = sensor.breakingFor(pos, state, level.getGameTime());
        if (done < needed) {
            JsonObject reply = new JsonObject();
            reply.addProperty("wait", needed - done);
            return reply;
        }
        List<ItemStack> drops = Block.getDrops(state, level, pos, level.getBlockEntity(pos), player, tool);
        List<ItemPort> into = EntitySensorCalls.destinations(level, network, args, 3);
        if (!into.isEmpty()) {
            for (ItemStack drop : drops) {
                int fits = 0;
                for (ItemPort port : into) {
                    fits += port.insert(drop, drop.getCount() - fits, true);
                    if (fits >= drop.getCount()) {
                        break;
                    }
                }
                if (fits < drop.getCount()) {
                    sensor.doneBreaking();
                    throw new CallError("No room for the " + NetworkCalls.itemId(drop.getItem()) + " it would drop; nothing was broken.");
                }
            }
        }
        level.destroyBlock(pos, false, player);
        sensor.doneBreaking();
        JsonObject got = new JsonObject();
        if (into.isEmpty()) {
            for (ItemStack drop : drops) {
                Block.popResource(level, pos, drop);
                got.addProperty(NetworkCalls.itemId(drop.getItem()), (got.has(NetworkCalls.itemId(drop.getItem()))
                        ? got.get(NetworkCalls.itemId(drop.getItem())).getAsInt() : 0) + drop.getCount());
            }
        } else {
            EntitySensorCalls.putAway(level, drops, into, Vec3.atCenterOf(pos), got);
        }
        JsonObject reply = new JsonObject();
        reply.addProperty("broke", blockId(state));
        reply.add("got", got);
        return reply;
    }

    /**
     * ["bsensor_place", mac, pos, sources, name or filter or null]: places one item from the sources
     * at a spot, as the owner would: a block, or seeds on farmland. The spot must be empty or give
     * way. An item that can't be placed there goes back.
     */
    private static JsonObject place(ServerLevel level, CableNetwork network, BlockSensorBlockEntity sensor, JsonArray args) {
        BlockPos pos = at(sensor, args, 2);
        BlockState there = level.getBlockState(pos);
        if (!there.canBeReplaced() || there.getFluidState().isSource()) {
            throw new CallError("The spot is taken by " + blockId(there) + ".");
        }
        String wanted = NetworkCalls.string(args, 4);
        ServerPlayer player = standIn(level, sensor); // before anything is taken: a sensor with no owner takes nothing
        EntitySensorCalls.Taken taken = EntitySensorCalls.takeOne(level, network, args.get(3), wanted);
        if (taken == null) {
            throw new CallError("The sources hold no " + (wanted == null ? "items" : wanted) + " to place.");
        }
        EntitySensorCalls.emptyHands(player);
        player.setItemInHand(InteractionHand.MAIN_HAND, taken.stack().copy());
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        player.gameMode.useItemOn(player, level, player.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
        boolean placed = level.getBlockState(pos) != there;
        List<ItemPort> back = new ArrayList<>(List.of(taken.from()));
        EntitySensorCalls.putAway(level, EntitySensorCalls.emptyHands(player), back, Vec3.atCenterOf(pos), new JsonObject());
        JsonObject reply = new JsonObject();
        reply.addProperty("placed", placed);
        reply.addProperty("block", blockId(level.getBlockState(pos)));
        return reply;
    }

    /**
     * ["bsensor_use", mac, pos, sources or null, name or filter or null, destinations or null]:
     * right-clicks the block at a spot as the owner, holding one item from the sources (or
     * nothing): bone meal grows what is there, a hoe tills dirt, a bucket fills or empties, a
     * lever flips. What comes of it goes to the destinations, or back to the sources.
     */
    private static JsonObject use(ServerLevel level, CableNetwork network, BlockSensorBlockEntity sensor, JsonArray args) {
        NetworkCalls.transportCheck(level);
        BlockPos pos = at(sensor, args, 2);
        boolean withItem = args.size() > 3 && !args.get(3).isJsonNull();
        String wanted = NetworkCalls.string(args, 4);
        // Before an item is taken: the owner, and where things go, must both be there.
        ServerPlayer player = standIn(level, sensor);
        List<ItemPort> into = EntitySensorCalls.destinations(level, network, args, 5);
        EntitySensorCalls.Taken taken = null;
        if (withItem) {
            taken = EntitySensorCalls.takeOne(level, network, args.get(3), wanted);
            if (taken == null) {
                throw new CallError("The sources hold no " + (wanted == null ? "items" : wanted) + " to use.");
            }
        } else if (wanted != null) {
            throw new CallError("Say where the " + wanted + " comes from: use(pos, \"" + wanted + "\", sources).");
        }
        EntitySensorCalls.emptyHands(player);
        player.setItemInHand(InteractionHand.MAIN_HAND, taken == null ? ItemStack.EMPTY : taken.stack().copy());
        BlockState before = level.getBlockState(pos);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        InteractionResult result = player.gameMode.useItemOn(player, level, player.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
        if (!result.consumesAction() && !player.getMainHandItem().isEmpty()) {
            result = player.gameMode.useItem(player, level, player.getMainHandItem(), InteractionHand.MAIN_HAND); // a bucket on water, say
        }
        boolean used = result.consumesAction() || level.getBlockState(pos) != before;
        if (taken != null) {
            if (!used) {
                into.addFirst(taken.from());
            } else if (into.isEmpty()) {
                into.add(taken.from());
            }
        }
        JsonObject gave = new JsonObject();
        EntitySensorCalls.putAway(level, EntitySensorCalls.emptyHands(player), into, Vec3.atCenterOf(pos), gave);
        JsonObject reply = new JsonObject();
        reply.addProperty("used", used);
        reply.add("gave", gave);
        reply.add("block", describe(level, sensor, pos));
        return reply;
    }
}
