package qbotics.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import qbotics.Qbotics;
import qbotics.block.WorldPlates;
import qbotics.network.NetworkCalls.CallError;
import qbotics.qbot.QbotBlockEntity;
import qbotics.qbot.Qbots;

/**
 * What a Qbot's program asks of its body (design doc §8): moving, turning, looking at the
 * blocks on its six sides, digging, placing, using and attacking, and moving things between
 * its inventory, energy cell and tanks and the blocks beside it. Everything it does to the
 * world it does as a stand-in for its owner, so land claims apply as they would to them.
 */
final class QbotCalls {
    /** The most items one drop or suck moves. */
    private static final int MAX_ITEMS = 64 * QbotBlockEntity.SLOTS;

    private QbotCalls() {
    }

    static JsonElement call(String method, ServerLevel level, BlockPos computerPos, JsonArray args) {
        QbotBlockEntity qbot = qbot(level, computerPos);
        return switch (method) {
            case "qbot_info" -> info(qbot);
            case "qbot_move" -> move(level, qbot, NetworkCalls.string(args, 1));
            case "qbot_turn" -> turn(level, qbot, args.get(1).getAsBoolean());
            case "qbot_inspect" -> inspect(level, qbot, side(qbot, args));
            case "qbot_dig" -> dig(level, qbot, side(qbot, args));
            case "qbot_place" -> place(level, qbot, side(qbot, args), NetworkCalls.string(args, 2));
            case "qbot_use" -> use(level, qbot, side(qbot, args), NetworkCalls.string(args, 2));
            case "qbot_attack" -> attack(level, qbot, side(qbot, args));
            case "qbot_items" -> items(qbot.itemPort());
            case "qbot_drop" -> drop(level, qbot, side(qbot, args), NetworkCalls.string(args, 2), NetworkCalls.wholeNumber(args, 3, "count"),
                    NetworkCalls.string(args, 4));
            case "qbot_suck" -> suck(level, qbot, side(qbot, args), NetworkCalls.string(args, 2), NetworkCalls.wholeNumber(args, 3, "count"),
                    NetworkCalls.string(args, 4));
            case "qbot_read" -> items(container(level, qbot, side(qbot, args), NetworkCalls.string(args, 2)));
            case "qbot_equip" -> equip(qbot, NetworkCalls.string(args, 1));
            case "qbot_unequip" -> unequip(qbot);
            case "qbot_energy" -> energy(level, qbot, side(qbot, args), NetworkCalls.wholeNumber(args, 2, "amount"), args.get(3).getAsBoolean(),
                    NetworkCalls.string(args, 4));
            case "qbot_fluid" -> fluid(level, qbot, side(qbot, args), NetworkCalls.string(args, 2), NetworkCalls.wholeNumber(args, 3, "amount"),
                    args.get(4).getAsBoolean(), NetworkCalls.string(args, 5));
            case "qbot_pump" -> pump(level, qbot, side(qbot, args));
            case "qbot_pour" -> pour(level, qbot, side(qbot, args), NetworkCalls.string(args, 2));
            default -> throw new CallError("This computer doesn't know the call '" + method + "'.");
        };
    }

    // ---- who and where ---------------------------------------------------------------------

    private static QbotBlockEntity qbot(ServerLevel level, BlockPos computerPos) {
        if (level.getBlockEntity(computerPos) instanceof QbotBlockEntity qbot) {
            return qbot;
        }
        throw new CallError("This computer isn't a Qbot: the qbot module works only on a Qbot.");
    }

    private static Direction side(QbotBlockEntity qbot, JsonArray args) {
        String word = NetworkCalls.string(args, 1);
        try {
            return Qbots.side(qbot, word == null ? "front" : word);
        } catch (IllegalArgumentException e) {
            throw new CallError(e.getMessage());
        }
    }

    /** The owner's stand-in, at the Qbot and looking towards a side. */
    private static ServerPlayer standIn(ServerLevel level, QbotBlockEntity qbot, Direction looking) {
        if (qbot.owner() == null) {
            throw new CallError("This Qbot has no owner to act for (a machine placed it), so it can't change the world.");
        }
        return Qbotics.platform().standIn(level, qbot.owner(), qbot.deviceId(), qbot.getBlockPos(), looking);
    }

    private static BlockPos loaded(ServerLevel level, QbotBlockEntity qbot, Direction side) {
        BlockPos target = qbot.getBlockPos().relative(side);
        if (!level.isLoaded(target)) {
            throw new CallError("The block to its " + side.getName() + " isn't loaded.");
        }
        return target;
    }

    private static String blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    private static String fluidId(Fluid fluid) {
        return BuiltInRegistries.FLUID.getKey(fluid).toString();
    }

    private static JsonObject info(QbotBlockEntity qbot) {
        JsonObject info = new JsonObject();
        info.addProperty("tier", qbot.tier().word);
        info.addProperty("facing", qbot.facing().getName());
        info.addProperty("x", qbot.getBlockPos().getX());
        info.addProperty("y", qbot.getBlockPos().getY());
        info.addProperty("z", qbot.getBlockPos().getZ());
        info.addProperty("energy", qbot.energy());
        info.addProperty("energy_capacity", QbotBlockEntity.ENERGY_CAPACITY);
        JsonArray tanks = new JsonArray();
        for (FluidPort.Tank tank : qbot.fluidPort().tanks()) {
            JsonObject t = new JsonObject();
            t.addProperty("fluid", tank.fluid() == null ? null : fluidId(tank.fluid()));
            t.addProperty("amount", tank.amount());
            t.addProperty("capacity", tank.capacity());
            tanks.add(t);
        }
        info.add("tanks", tanks);
        info.add("tool", toolInfo(qbot.tool()));
        return info;
    }

    private static JsonElement toolInfo(ItemStack tool) {
        if (tool.isEmpty()) {
            return JsonNull.INSTANCE;
        }
        JsonObject info = new JsonObject();
        info.addProperty("item", NetworkCalls.itemId(tool.getItem()));
        info.addProperty("damage", tool.getDamageValue());
        info.addProperty("max_damage", tool.getMaxDamage());
        return info;
    }

    // ---- moving ------------------------------------------------------------------------------

    private static JsonObject move(ServerLevel level, QbotBlockEntity qbot, String way) {
        Direction direction = switch (way == null ? "" : way) {
            case "forward" -> qbot.facing();
            case "back" -> qbot.facing().getOpposite();
            case "up" -> Direction.UP;
            case "down" -> Direction.DOWN;
            default -> throw new CallError("A Qbot moves forward, back, up or down.");
        };
        QbotBlockEntity moved;
        try {
            moved = Qbots.step(level, qbot, direction, standIn(level, qbot, direction));
        } catch (IllegalStateException e) {
            throw new CallError(e.getMessage());
        }
        JsonObject reply = info(moved);
        reply.addProperty("wait", Qbots.STEP_TICKS);
        return reply;
    }

    private static JsonObject turn(ServerLevel level, QbotBlockEntity qbot, boolean right) {
        Qbots.turn(level, qbot, right);
        JsonObject reply = new JsonObject();
        reply.addProperty("facing", level.getBlockState(qbot.getBlockPos()).getValue(qbotics.qbot.QbotBlock.FACING).getName());
        reply.addProperty("wait", Qbots.TURN_TICKS);
        return reply;
    }

    // ---- sensing -----------------------------------------------------------------------------

    private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    /** What is on one side: the block (its ID, state and tags), any fluid, and any creatures or items there. */
    private static JsonObject inspect(ServerLevel level, QbotBlockEntity qbot, Direction side) {
        BlockPos target = loaded(level, qbot, side);
        BlockState state = level.getBlockState(target);
        JsonObject seen = new JsonObject();
        seen.addProperty("id", blockId(state));
        seen.addProperty("air", state.isAir());
        JsonObject properties = new JsonObject();
        for (Property<?> property : state.getProperties()) {
            properties.addProperty(property.getName(), valueName(state, property));
        }
        seen.add("state", properties);
        JsonArray tags = new JsonArray();
        state.getBlock().builtInRegistryHolder().tags().forEach(tag -> tags.add("#" + tag.location()));
        seen.add("tags", tags);
        FluidState fluid = state.getFluidState();
        seen.addProperty("fluid", fluid.isEmpty() ? null : fluidId(fluid.getType()));
        seen.addProperty("source", !fluid.isEmpty() && fluid.isSource());
        JsonArray entities = new JsonArray();
        for (Entity entity : level.getEntities((Entity) null, new AABB(target), entity -> entity.isAlive() && !entity.isSpectator())) {
            entities.add(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
        }
        seen.add("entities", entities);
        return seen;
    }

    // ---- digging -----------------------------------------------------------------------------

    /**
     * Digs the block on a side: as long as a player with the same tool would take, into its
     * inventory, all or nothing. With nothing in its hand (or a tool that does no better) it
     * digs as its tier's tools would (an Iron Qbot: iron), and can't dig what needs better.
     */
    private static JsonObject dig(ServerLevel level, QbotBlockEntity qbot, Direction side) {
        BlockPos target = loaded(level, qbot, side);
        BlockState state = level.getBlockState(target);
        if (state.isAir() || (state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock)) {
            throw new CallError(state.isAir() ? "There is nothing to its " + side.getName() + " to dig."
                    : "That is " + blockId(state) + ": take it up into a tank with pump().");
        }
        if (state.getDestroySpeed(level, target) < 0) {
            throw new CallError(blockId(state) + " can't be broken.");
        }
        ItemStack equipped = qbot.tool();
        ItemStack builtIn = qbot.tier().toolFor(state);
        boolean useEquipped;
        if (!equipped.isEmpty() && equipped.isCorrectToolForDrops(state)) {
            useEquipped = !builtIn.isCorrectToolForDrops(state) || equipped.getDestroySpeed(state) >= builtIn.getDestroySpeed(state);
        } else {
            useEquipped = false;
        }
        ItemStack tool = useEquipped ? equipped : builtIn;
        if (state.requiresCorrectToolForDrops() && (tool.isEmpty() || !tool.isCorrectToolForDrops(state))) {
            throw new CallError("An " + qbot.tier().word + " Qbot can't dig " + blockId(state)
                    + ": it needs a better tool. Put one in its hand with tools.equip().");
        }
        // Breaking takes time: the first call starts it, and the program asks again until it gives way.
        int needed = WorldPlates.breakTicks(state, level, target, tool);
        long now = level.getGameTime();
        if (!target.equals(qbot.breakingPos()) || qbot.breakingState() != state) {
            qbot.startBreaking(target, state, now);
            qbot.swingTowards(side);
        }
        long left = qbot.breakingSince() + needed - now;
        if (left > 0) {
            JsonObject reply = new JsonObject();
            reply.addProperty("wait", left);
            return reply;
        }
        ServerPlayer player = standIn(level, qbot, side);
        if (!Qbotics.platform().mayBreak(level, player, target)) {
            throw new CallError("The Qbot's owner may not break blocks there.");
        }
        BlockEntity entity = level.getBlockEntity(target);
        List<ItemStack> drops = new ArrayList<>(Block.getDrops(state, level, target, entity, player, tool));
        boolean spills = WorldPlates.spills(entity, drops);
        if (spills) {
            Container container = (Container) entity;
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                if (!container.getItem(slot).isEmpty()) {
                    drops.add(container.getItem(slot).copy());
                }
            }
        }
        // Every drop must fit, or nothing is dug.
        SimpleCheck room = new SimpleCheck(qbot);
        for (ItemStack drop : drops) {
            if (!room.take(drop)) {
                throw new CallError("No room in the Qbot for " + drop.getCount() + " " + NetworkCalls.itemId(drop.getItem())
                        + " from " + blockId(state) + ", so it wasn't dug.");
            }
        }
        JsonObject got = new JsonObject();
        for (ItemStack drop : drops) {
            qbot.itemPort().insert(drop, drop.getCount(), false);
            String id = NetworkCalls.itemId(drop.getItem());
            got.addProperty(id, (got.has(id) ? got.get(id).getAsInt() : 0) + drop.getCount());
        }
        String broke = null;
        if (useEquipped) {
            // A real tool wears as a player's would.
            player.setItemInHand(InteractionHand.MAIN_HAND, equipped);
            equipped.mineBlock(level, state, target, player);
            ItemStack after = player.getMainHandItem();
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            if (after.isEmpty()) {
                broke = NetworkCalls.itemId(equipped.getItem());
            }
            qbot.hand().setItem(0, after);
        }
        if (spills) {
            ((Container) entity).clearContent();
        }
        level.levelEvent(2001, target, Block.getId(state));
        level.setBlock(target, state.getFluidState().createLegacyBlock(), Block.UPDATE_ALL);
        qbot.startBreaking(null, null, now);
        JsonObject reply = new JsonObject();
        reply.addProperty("dug", blockId(state));
        reply.add("got", got);
        if (broke != null) {
            reply.addProperty("tool_broke", broke);
        }
        return reply;
    }

    /** Tries drops into a copy of the Qbot's inventory, to see whether they all fit before anything moves. */
    private static final class SimpleCheck {
        private final net.minecraft.world.SimpleContainer copy;

        SimpleCheck(QbotBlockEntity qbot) {
            copy = new net.minecraft.world.SimpleContainer(QbotBlockEntity.SLOTS);
            for (int slot = 0; slot < QbotBlockEntity.SLOTS; slot++) {
                copy.setItem(slot, qbot.items().getItem(slot).copy());
            }
        }

        boolean take(ItemStack stack) {
            return new ContainerItemPort(copy).insert(stack, stack.getCount(), false) == stack.getCount();
        }
    }

    // ---- placing, using, attacking -----------------------------------------------------------

    /** The slot holding an item a program named (or with none named, the first that passes a test), or -1. */
    private static int slotOf(QbotBlockEntity qbot, String wanted, java.util.function.Predicate<ItemStack> otherwise) {
        NameMatch<Item> match = wanted == null ? null : NetworkCalls.item(wanted);
        for (int slot = 0; slot < QbotBlockEntity.SLOTS; slot++) {
            ItemStack stack = qbot.items().getItem(slot);
            if (!stack.isEmpty() && (match == null ? otherwise.test(stack) : match.test(stack.getItem()))) {
                return slot;
            }
        }
        return -1;
    }

    /** Places one block from its inventory on a side (the named one, or the first block it holds). */
    private static JsonObject place(ServerLevel level, QbotBlockEntity qbot, Direction side, String wanted) {
        BlockPos target = loaded(level, qbot, side);
        BlockState there = level.getBlockState(target);
        if (!there.canBeReplaced()) {
            throw new CallError("There is no room to its " + side.getName() + ": " + blockId(there) + " is there.");
        }
        int slot = slotOf(qbot, wanted, stack -> stack.getItem() instanceof BlockItem);
        if (slot < 0) {
            throw new CallError(wanted == null ? "The Qbot holds no block to place." : "The Qbot holds no " + wanted + ".");
        }
        ItemStack one = qbot.items().getItem(slot).copyWithCount(1);
        String name = NetworkCalls.itemId(one.getItem());
        ServerPlayer player = standIn(level, qbot, side);
        player.setItemInHand(InteractionHand.MAIN_HAND, one);
        player.gameMode.useItemOn(player, level, one, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(target), side.getOpposite(), target, false));
        ItemStack left = player.getMainHandItem();
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        boolean placed = !(left.getCount() == 1 && ItemStack.isSameItemSameComponents(left, one));
        if (!placed) {
            throw new CallError("The Qbot couldn't place " + name + " there: it can't stand there, or its owner may not build here.");
        }
        qbot.items().removeItem(slot, 1);
        giveBack(level, qbot, left, -1);
        qbot.swingTowards(side);
        JsonObject reply = new JsonObject();
        reply.addProperty("placed", name);
        return reply;
    }

    /**
     * Uses something on a side, as a player right-clicking it would: an item from its
     * inventory (named), else the tool in its hand, else an empty hand. On a creature (shearing
     * a sheep, milking a cow) or a block (a lever, a door, bone meal, a hoe on dirt).
     */
    private static JsonObject use(ServerLevel level, QbotBlockEntity qbot, Direction side, String wanted) {
        BlockPos target = loaded(level, qbot, side);
        int slot = -1;
        ItemStack held;
        boolean fromHand = false;
        if (wanted != null) {
            slot = slotOf(qbot, wanted, stack -> false);
            if (slot < 0) {
                throw new CallError("The Qbot holds no " + wanted + ".");
            }
            held = qbot.items().removeItemNoUpdate(slot);
            qbot.items().setChanged();
        } else {
            held = qbot.hand().removeItemNoUpdate(0);
            fromHand = !held.isEmpty();
        }
        ServerPlayer player = standIn(level, qbot, side);
        String toolBefore = fromHand ? NetworkCalls.itemId(held.getItem()) : null;
        player.setItemInHand(InteractionHand.MAIN_HAND, held);
        InteractionResult result = InteractionResult.PASS;
        String on;
        List<LivingEntity> creatures = level.getEntitiesOfClass(LivingEntity.class, new AABB(target), e -> e.isAlive() && e != player);
        if (!creatures.isEmpty()) {
            LivingEntity creature = creatures.getFirst();
            on = BuiltInRegistries.ENTITY_TYPE.getKey(creature.getType()).toString();
            result = player.interactOn(creature, InteractionHand.MAIN_HAND, creature.position());
        } else {
            BlockState state = level.getBlockState(target);
            on = blockId(state);
            if (!state.isAir()) {
                result = player.gameMode.useItemOn(player, level, player.getMainHandItem(), InteractionHand.MAIN_HAND,
                        new BlockHitResult(Vec3.atCenterOf(target), side.getOpposite(), target, false));
            }
            if (!result.consumesAction() && !player.getMainHandItem().isEmpty()) {
                result = player.gameMode.useItem(player, level, player.getMainHandItem(), InteractionHand.MAIN_HAND);
            }
        }
        ItemStack after = player.getMainHandItem();
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        String broke = null;
        if (fromHand) {
            if (after.isEmpty()) {
                broke = toolBefore;
            }
            qbot.hand().setItem(0, after);
        } else {
            giveBack(level, qbot, after, slot);
        }
        // Whatever the use gave (a filled bottle, wool, milk) goes into its inventory.
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack extra = player.getInventory().removeItemNoUpdate(i);
            giveBack(level, qbot, extra, -1);
        }
        qbot.swingTowards(side);
        JsonObject reply = new JsonObject();
        reply.addProperty("used", result.consumesAction());
        reply.addProperty("on", on);
        if (broke != null) {
            reply.addProperty("tool_broke", broke);
        }
        return reply;
    }

    /** Attacks a creature on a side with the tool in its hand (or its fist). */
    private static JsonObject attack(ServerLevel level, QbotBlockEntity qbot, Direction side) {
        BlockPos target = loaded(level, qbot, side);
        ServerPlayer player = standIn(level, qbot, side);
        List<LivingEntity> creatures = level.getEntitiesOfClass(LivingEntity.class, new AABB(target), e -> e.isAlive() && e != player);
        if (creatures.isEmpty()) {
            throw new CallError("There is nothing to its " + side.getName() + " to attack.");
        }
        LivingEntity creature = creatures.getFirst();
        ItemStack tool = qbot.hand().removeItemNoUpdate(0);
        String toolName = tool.isEmpty() ? null : NetworkCalls.itemId(tool.getItem());
        player.setItemInHand(InteractionHand.MAIN_HAND, tool);
        player.attack(creature);
        ItemStack after = player.getMainHandItem();
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        qbot.hand().setItem(0, after);
        qbot.swingTowards(side);
        JsonObject reply = new JsonObject();
        reply.addProperty("attacked", BuiltInRegistries.ENTITY_TYPE.getKey(creature.getType()).toString());
        reply.addProperty("alive", creature.isAlive());
        if (toolName != null && after.isEmpty()) {
            reply.addProperty("tool_broke", toolName);
        }
        return reply;
    }

    /** Puts a stack back: into a slot it came from if it fits, else anywhere, else onto the ground beside it. */
    private static void giveBack(ServerLevel level, QbotBlockEntity qbot, ItemStack stack, int slot) {
        if (stack.isEmpty()) {
            return;
        }
        int left = stack.getCount();
        ItemPort port = qbot.itemPort();
        if (slot >= 0) {
            left -= port.insertInto(slot, stack, left, false);
        }
        if (left > 0) {
            left -= port.insert(stack, left, false);
        }
        if (left > 0) {
            Block.popResource(level, qbot.getBlockPos(), stack.copyWithCount(left));
        }
    }

    // ---- its inventory and the blocks beside it ----------------------------------------------

    private static JsonArray items(ItemPort port) {
        JsonArray list = new JsonArray();
        List<ItemStack> contents = port.contents();
        for (int slot = 0; slot < contents.size(); slot++) {
            ItemStack stack = contents.get(slot);
            if (!stack.isEmpty()) {
                JsonObject entry = new JsonObject();
                entry.addProperty("slot", slot);
                entry.addProperty("item", NetworkCalls.itemId(stack.getItem()));
                entry.addProperty("count", stack.getCount());
                list.add(entry);
            }
        }
        return list;
    }

    /** The face of the block on a side that a program means: near (the one touching the Qbot, the default), far, top, bottom, left or right. */
    private static Direction face(QbotBlockEntity qbot, Direction side, String word) {
        return switch (word == null ? "near" : word) {
            case "near" -> side.getOpposite();
            case "far" -> side;
            case "top" -> Direction.UP;
            case "bottom" -> Direction.DOWN;
            case "left" -> qbot.facing().getCounterClockWise();
            case "right" -> qbot.facing().getClockWise();
            default -> throw new CallError("A face is near, far, top, bottom, left or right, not " + word + ".");
        };
    }

    private static ItemPort container(ServerLevel level, QbotBlockEntity qbot, Direction side, String faceWord) {
        BlockPos target = loaded(level, qbot, side);
        ItemPort port = Qbotics.platform().itemPort(level, target, face(qbot, side, faceWord));
        if (port == null) {
            throw new CallError("There is nothing that holds items to its " + side.getName() + " (" + blockId(level.getBlockState(target))
                    + "), or not through that face.");
        }
        return port;
    }

    private static java.util.function.Predicate<Item> wantedItems(String wanted) {
        NameMatch<Item> match = wanted == null ? null : NetworkCalls.item(wanted);
        return item -> match == null || match.test(item);
    }

    /** Puts items from its inventory into the block on a side; with no container there (and no face named), onto the ground. */
    private static JsonPrimitive drop(ServerLevel level, QbotBlockEntity qbot, Direction side, String wanted, Integer count, String faceWord) {
        BlockPos target = loaded(level, qbot, side);
        int limit = count == null ? MAX_ITEMS : Math.min(count, MAX_ITEMS);
        ItemPort into = faceWord == null ? Qbotics.platform().itemPort(level, target, side.getOpposite()) : container(level, qbot, side, faceWord);
        if (into != null) {
            return new JsonPrimitive(ItemPort.move(qbot.itemPort(), -1, into, -1, wantedItems(wanted), limit));
        }
        if (!level.getBlockState(target).getCollisionShape(level, target).isEmpty()) {
            throw new CallError("There is no room to its " + side.getName() + " to drop items, and " + blockId(level.getBlockState(target))
                    + " doesn't take them.");
        }
        // Onto the ground: whole stacks, up to the count.
        java.util.function.Predicate<Item> passes = wantedItems(wanted);
        int dropped = 0;
        for (int slot = 0; slot < QbotBlockEntity.SLOTS && dropped < limit; slot++) {
            ItemStack stack = qbot.items().getItem(slot);
            if (stack.isEmpty() || !passes.test(stack.getItem())) {
                continue;
            }
            ItemStack out = qbot.items().removeItem(slot, Math.min(stack.getCount(), limit - dropped));
            dropped += out.getCount();
            Vec3 at = Vec3.atCenterOf(target);
            ItemEntity entity = new ItemEntity(level, at.x, at.y, at.z, out);
            entity.setDeltaMovement(Vec3.ZERO);
            level.addFreshEntity(entity);
        }
        return new JsonPrimitive(dropped);
    }

    /** Takes items into its inventory from the block on a side; with no container there, picks up items lying there. */
    private static JsonPrimitive suck(ServerLevel level, QbotBlockEntity qbot, Direction side, String wanted, Integer count, String faceWord) {
        BlockPos target = loaded(level, qbot, side);
        int limit = count == null ? MAX_ITEMS : Math.min(count, MAX_ITEMS);
        ItemPort from = faceWord == null ? Qbotics.platform().itemPort(level, target, side.getOpposite()) : container(level, qbot, side, faceWord);
        if (from != null) {
            return new JsonPrimitive(ItemPort.move(from, -1, qbot.itemPort(), -1, wantedItems(wanted), limit));
        }
        java.util.function.Predicate<Item> passes = wantedItems(wanted);
        int taken = 0;
        for (ItemEntity entity : level.getEntitiesOfClass(ItemEntity.class, new AABB(target).inflate(0.25), ItemEntity::isAlive)) {
            ItemStack stack = entity.getItem();
            if (taken >= limit || !passes.test(stack.getItem())) {
                continue;
            }
            int put = qbot.itemPort().insert(stack, Math.min(stack.getCount(), limit - taken), false);
            taken += put;
            if (put >= stack.getCount()) {
                entity.discard();
            } else if (put > 0) {
                entity.setItem(stack.copyWithCount(stack.getCount() - put));
            }
        }
        return new JsonPrimitive(taken);
    }

    // ---- tools --------------------------------------------------------------------------------

    /** Puts an item from its inventory into its hand; what was in its hand goes back into the inventory. */
    private static JsonElement equip(QbotBlockEntity qbot, String wanted) {
        if (wanted == null) {
            throw new CallError("equip() needs the item to hold, as in tools.equip(\"minecraft:diamond_pickaxe\").");
        }
        int slot = slotOf(qbot, wanted, stack -> false);
        if (slot < 0) {
            throw new CallError("The Qbot holds no " + wanted + " to equip.");
        }
        ItemStack tool = qbot.items().removeItem(slot, 1);
        ItemStack was = qbot.hand().removeItemNoUpdate(0);
        qbot.hand().setItem(0, tool);
        if (!was.isEmpty() && qbot.itemPort().insert(was, was.getCount(), false) < was.getCount()) {
            qbot.items().setItem(slot, was); // the slot it came from is free again
        }
        return toolInfo(tool);
    }

    private static JsonElement unequip(QbotBlockEntity qbot) {
        ItemStack was = qbot.hand().getItem(0);
        if (was.isEmpty()) {
            return JsonNull.INSTANCE;
        }
        if (qbot.itemPort().insert(was, was.getCount(), true) < was.getCount()) {
            throw new CallError("There is no room in the Qbot's inventory for the tool in its hand.");
        }
        qbot.itemPort().insert(was, was.getCount(), false);
        qbot.hand().setItem(0, ItemStack.EMPTY);
        return new JsonPrimitive(NetworkCalls.itemId(was.getItem()));
    }

    // ---- energy and fluid ---------------------------------------------------------------------

    /** Moves energy between its cell and the block on a side: give=true from the Qbot, false into it. */
    private static JsonPrimitive energy(ServerLevel level, QbotBlockEntity qbot, Direction side, Integer amount, boolean give, String faceWord) {
        BlockPos target = loaded(level, qbot, side);
        EnergyPort there = Qbotics.platform().energyPort(level, target, face(qbot, side, faceWord));
        if (there == null) {
            throw new CallError("There is nothing that holds energy to its " + side.getName() + " (" + blockId(level.getBlockState(target)) + ").");
        }
        int most = amount == null ? QbotBlockEntity.ENERGY_CAPACITY : amount;
        return new JsonPrimitive(give ? EnergyPort.move(qbot.energyPort(), there, most) : EnergyPort.move(there, qbot.energyPort(), most));
    }

    /** Moves fluid between its tanks and the block on a side: give=true from the Qbot, false into it. */
    private static JsonPrimitive fluid(ServerLevel level, QbotBlockEntity qbot, Direction side, String wanted, Integer amount, boolean give, String faceWord) {
        BlockPos target = loaded(level, qbot, side);
        FluidPort there = Qbotics.platform().fluidPort(level, target, face(qbot, side, faceWord));
        if (there == null) {
            throw new CallError("There is nothing that holds fluid to its " + side.getName() + " (" + blockId(level.getBlockState(target))
                    + "). A pool of water or lava is taken up with pump().");
        }
        NameMatch<Fluid> match = wanted == null ? null : NetworkCalls.fluid(wanted);
        int most = amount == null ? QbotBlockEntity.TANK_CAPACITY * QbotBlockEntity.TANKS : amount;
        return new JsonPrimitive(give ? FluidPort.move(qbot.fluidPort(), -1, there, -1, f -> match == null || match.test(f), most)
                : FluidPort.move(there, -1, qbot.fluidPort(), -1, f -> match == null || match.test(f), most));
    }

    /** Takes up a source of fluid on a side (water, lava) into a tank: a bucket's worth. */
    private static JsonObject pump(ServerLevel level, QbotBlockEntity qbot, Direction side) {
        BlockPos target = loaded(level, qbot, side);
        BlockState state = level.getBlockState(target);
        FluidState fluid = state.getFluidState();
        if (fluid.isEmpty() || !fluid.isSource() || !(state.getBlock() instanceof BucketPickup pickup)) {
            throw new CallError("There is no source of fluid to its " + side.getName() + " to take up.");
        }
        Fluid kind = fluid.getType();
        if (qbot.fluidPort().insert(kind, FluidPort.BUCKET, true) < FluidPort.BUCKET) {
            throw new CallError("The Qbot's tanks have no room for 1000 mB of " + fluidId(kind) + ".");
        }
        ServerPlayer player = standIn(level, qbot, side);
        if (!Qbotics.platform().mayBreak(level, player, target)) {
            throw new CallError("The Qbot's owner may not change blocks there.");
        }
        if (pickup.pickupBlock(player, level, target, state).isEmpty()) {
            throw new CallError("The fluid there couldn't be taken up.");
        }
        qbot.fluidPort().insert(kind, FluidPort.BUCKET, false);
        qbot.swingTowards(side);
        JsonObject reply = new JsonObject();
        reply.addProperty("pumped", fluidId(kind));
        return reply;
    }

    /** Pours a bucket's worth from a tank onto a side, making a source block there. */
    private static JsonObject pour(ServerLevel level, QbotBlockEntity qbot, Direction side, String wanted) {
        BlockPos target = loaded(level, qbot, side);
        NameMatch<Fluid> match = wanted == null ? null : NetworkCalls.fluid(wanted);
        for (FluidPort.Tank tank : qbot.fluidPort().tanks()) {
            Fluid kind = tank.fluid();
            if (kind == null || tank.amount() < FluidPort.BUCKET || (match != null && !match.test(kind)) || !(kind.getBucket() instanceof BucketItem bucket)) {
                continue;
            }
            ServerPlayer player = standIn(level, qbot, side);
            boolean[] poured = {false};
            boolean allowed = Qbotics.platform().placeChecked(level, player, target, side.getOpposite(),
                    () -> poured[0] = bucket.emptyContents(player, level, target, null));
            if (!allowed) {
                throw new CallError("The Qbot's owner may not build there, so it poured nothing.");
            }
            if (!poured[0]) {
                throw new CallError("The Qbot couldn't pour " + fluidId(kind) + " there.");
            }
            qbot.fluidPort().extract(kind, FluidPort.BUCKET, false);
            qbot.swingTowards(side);
            JsonObject reply = new JsonObject();
            reply.addProperty("poured", fluidId(kind));
            return reply;
        }
        throw new CallError("The Qbot's tanks hold no bucket's worth (1000 mB) of " + (wanted == null ? "fluid" : wanted) + " to pour.");
    }
}
