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
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import qbotics.Qbotics;
import qbotics.block.CablePort;
import qbotics.block.FluidInterfaceBlockEntity;
import qbotics.block.WorldPlates;
import qbotics.network.NetworkCalls.CallError;
import qbotics.network.NetworkCalls.End;
import qbotics.network.NetworkDevice.Kind;

/**
 * The calls for the world plates (design doc §11.0.1): what a plate sees, the Logic Pin's
 * redstone, and placing and breaking. Each acts on the block its plate points at; the Placer
 * and Breaker act as a stand-in for the plate's owner (§11.0.3).
 */
final class WorldPlateCalls {
    private WorldPlateCalls() {
    }

    static JsonElement call(String method, ServerLevel level, CableNetwork network, JsonArray args) {
        NetworkDevice device = NetworkCalls.byMac(network, args, 1);
        level = NetworkCalls.levelOf(device, level); // behind another antenna, perhaps in another dimension
        return switch (method) {
            case "look" -> look(level, plate(device, null));
            case "pin" -> pinState(level, plate(device, Kind.PIN));
            case "pin_set" -> setPin(level, plate(device, Kind.PIN), args);
            case "place" -> place(level, network, plate(device, Kind.PLACER), args);
            case "pour" -> pour(level, network, plate(device, Kind.PLACER), args);
            case "break" -> breakBlock(level, network, plate(device, Kind.BREAKER), args);
            case "drop" -> drop(level, network, plate(device, Kind.DROPPER), args);
            case "drop_xp" -> dropExperience(level, network, plate(device, Kind.DROPPER), args);
            case "dropper_set" -> {
                CablePort dropper = plate(device, Kind.DROPPER);
                dropper.setDispense(args.size() > 2 && !args.get(2).isJsonNull() && args.get(2).getAsBoolean());
                JsonObject state = new JsonObject();
                state.addProperty("dispense", dropper.dispense());
                yield state;
            }
            default -> throw new CallError("This computer doesn't know the call '" + method + "'.");
        };
    }

    /** The plate a call is for: of one kind, or with none given, any world plate. */
    private static CablePort plate(NetworkDevice device, Kind kind) {
        if (device instanceof CablePort plate && (kind == null ? !plate.kind().isLogistics() : plate.kind() == kind)) {
            return plate;
        }
        String wanted = kind == null ? "a logic pin, a placer, a breaker or a dropper" : kind == Kind.PIN ? "a logic pin" : "a " + kind.word;
        throw new CallError("That call is for " + wanted + "; this is " + NetworkCalls.article(device) + ".");
    }

    /** The plate's owner, whom it acts for. A plate nobody owns has nobody to act for. */
    private static ServerPlayer standIn(ServerLevel level, CablePort plate) {
        UUID owner = plate.owner();
        if (owner == null) {
            throw new CallError("This " + plate.kind().word + " has no owner to act for (it wasn't placed by a player), so it can't change the world.");
        }
        return Qbotics.platform().standIn(level, owner, plate.deviceId(), plate.getBlockPos(), plate.pointing());
    }

    private static BlockPos loadedTarget(ServerLevel level, CablePort plate) {
        BlockPos target = plate.targetPos();
        if (!level.isLoaded(target)) {
            throw new CallError("What the " + plate.kind().word + " points at isn't loaded.");
        }
        return target;
    }

    private static String blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    private static String fluidId(Fluid fluid) {
        return BuiltInRegistries.FLUID.getKey(fluid).toString();
    }

    // ---- what a plate sees ---------------------------------------------------------------

    private static JsonObject look(ServerLevel level, CablePort plate) {
        BlockState state = level.getBlockState(loadedTarget(level, plate));
        JsonObject seen = new JsonObject();
        seen.addProperty("block", blockId(state));
        JsonObject properties = new JsonObject();
        for (Property<?> property : state.getProperties()) {
            properties.addProperty(property.getName(), valueName(state, property));
        }
        seen.add("state", properties);
        seen.addProperty("air", state.isAir());
        FluidState fluid = state.getFluidState();
        if (fluid.isEmpty()) {
            seen.add("fluid", JsonNull.INSTANCE);
        } else {
            seen.addProperty("fluid", fluidId(fluid.getType()));
        }
        return seen;
    }

    private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    // ---- Logic Pin -----------------------------------------------------------------------

    private static JsonObject pinState(ServerLevel level, CablePort pin) {
        WorldPlates.sense(level, pin);
        CablePort.PinState state = pin.pinState();
        JsonObject reply = new JsonObject();
        reply.addProperty("mode", state.output() ? "output" : "input");
        reply.addProperty("level", state.output() ? state.driving() : state.reading());
        reply.addProperty("rising", state.rising());
        reply.addProperty("falling", state.falling());
        reply.addProperty("changes", state.changes());
        reply.addProperty("blocks", state.blockChanges());
        return reply;
    }

    /** ["pin_set", mac, mode or null, level or null, pulse ticks or null] */
    private static JsonObject setPin(ServerLevel level, CablePort pin, JsonArray args) {
        String mode = NetworkCalls.string(args, 2);
        Integer value = args.size() > 3 && !args.get(3).isJsonNull() ? args.get(3).getAsInt() : null;
        Integer pulse = NetworkCalls.wholeNumber(args, 4, "pulse length");
        if (mode != null && !mode.equals("input") && !mode.equals("output")) {
            throw new CallError("A pin's mode is \"input\" or \"output\", not \"" + mode + "\".");
        }
        if (value != null && (value < 0 || value > 15)) {
            throw new CallError("A redstone level is 0 to 15, not " + value + ".");
        }
        if ("input".equals(mode) && value == null && pulse == null) {
            WorldPlates.drive(level, pin, false, 0, 0);
        } else if ("input".equals(mode)) {
            throw new CallError("A pin set to input reads; it can't also drive a level. Set it to output, or leave out the level.");
        } else {
            int drive = value != null ? value : pulse != null ? 15 : pin.pinState().driving();
            WorldPlates.drive(level, pin, true, drive, pulse == null ? 0 : pulse);
        }
        return pinState(level, pin);
    }

    // ---- Dropper -------------------------------------------------------------------------

    /** The most a dropper throws out in one call, as one stack. A dispenser fires one item a call. */
    private static final int MOST_DROPPED = 64;

    /**
     * ["drop", mac, sources, name or filter or null, count or null, dispense or null]: takes
     * items from the sources and sends them out of the plate, as a dropper throws them or (dispense)
     * as a dispenser fires them: arrows fly, bone meal is used, water is poured, armour is put on
     * whoever stands there. One kind a call; a dispenser one item. Whatever a dispenser hands back
     * (an empty bucket, a glass bottle, an item it couldn't use) goes back where it came from.
     */
    private static JsonObject drop(ServerLevel level, CableNetwork network, CablePort dropper, JsonArray args) {
        BlockPos target = loadedTarget(level, dropper);
        String wanted = NetworkCalls.string(args, 3);
        NameMatch<Item> match = wanted == null ? null : NetworkCalls.item(wanted);
        Integer asked = NetworkCalls.wholeNumber(args, 4, "count");
        boolean dispense = args.size() > 5 && !args.get(5).isJsonNull() ? args.get(5).getAsBoolean() : dropper.dispense();
        int most = dispense ? 1 : Math.clamp(asked == null ? 1 : asked, 0, MOST_DROPPED);
        JsonObject answer = new JsonObject();
        answer.addProperty("dropped", 0);
        answer.add("item", JsonNull.INSTANCE);
        if (most == 0) {
            return answer;
        }
        for (End end : NetworkCalls.asMedium(NetworkCalls.ends(network, args.get(2), "source"), Medium.ITEM)) {
            if (end.device().medium() != Medium.ITEM) {
                continue;
            }
            ItemPort port = NetworkCalls.itemSource(level, end);
            java.util.function.Predicate<ItemStack> own = NetworkCalls.itemFilter(end);
            List<ItemStack> contents = port.contents();
            for (int slot = 0; slot < contents.size(); slot++) {
                ItemStack stack = contents.get(slot);
                if (stack.isEmpty() || !own.test(stack) || (match != null && !match.testStack(stack))) {
                    continue;
                }
                int taken = port.extractFrom(slot, stack, most, false);
                if (taken <= 0) {
                    continue;
                }
                ItemStack out = stack.copyWithCount(taken);
                answer.addProperty("item", NetworkCalls.itemId(out.getItem()));
                if (dispense) {
                    answer.addProperty("dropped", fire(level, dropper, target, out, port) ? 1 : 0);
                } else {
                    Direction facing = dropper.pointing();
                    net.minecraft.core.dispenser.DefaultDispenseItemBehavior.spawnItem(level, out, 6, facing, mouth(dropper));
                    level.levelEvent(1000, dropper.getBlockPos(), 0);                       // the dropper's click
                    level.levelEvent(2000, dropper.getBlockPos(), facing.get3DDataValue()); // and its puff of smoke
                    answer.addProperty("dropped", taken);
                }
                return answer;
            }
        }
        return answer;
    }

    /** The most experience a dropper sends out in one call, in points (about level 480's worth from nothing). */
    private static final long MOST_POINTS = 1_000_000;

    /**
     * ["drop_xp", mac, sources, points or null, levels or null]: takes exactly that much
     * experience from the sources (Experience Interfaces, liquid experience in fluid stores, in
     * order) and sends it out as orbs, or nothing at all if they don't hold that much. Levels are
     * counted from 0: 30 levels is the 1,395 points that take a player from level 0 to 30.
     */
    private static JsonObject dropExperience(ServerLevel level, CableNetwork network, CablePort dropper, JsonArray args) {
        loadedTarget(level, dropper);
        Integer points = NetworkCalls.wholeNumber(args, 3, "points");
        Integer levels = NetworkCalls.wholeNumber(args, 4, "levels");
        if ((points == null) == (levels == null)) {
            throw new CallError("Say how much experience: points= or levels=, one of them.");
        }
        long wanted = points != null ? points : qbotics.block.Experience.pointsAtLevel(levels);
        if (wanted > MOST_POINTS) {
            throw new CallError("A dropper sends out at most " + MOST_POINTS + " points at a time; " + wanted + " were asked for.");
        }
        List<ScreenSessions.Payer> payers = NetworkCalls.payers(level, network, args.get(2));
        if (payers.isEmpty()) {
            throw new CallError("drop_xp() needs sources= holding experience: an Experience Interface, or liquid experience in a fluid store.");
        }
        long held = 0;
        java.util.Set<String> counted = new java.util.HashSet<>();
        for (ScreenSessions.Payer payer : payers) {
            if (counted.add(payer.label())) { // a source named twice holds its experience once
                held += payer.points();
            }
        }
        if (held < wanted) {
            throw new CallError("The sources hold " + held + " points of experience; " + wanted + " were asked for. Nothing was sent out.");
        }
        // What each source really gave is counted (a source listed twice, or two buses on one tank,
        // hold their experience once): only that much is sent out.
        long left = wanted;
        for (ScreenSessions.Payer payer : payers) {
            long before = payer.points();
            long taken = Math.min(left, before);
            if (taken > 0) {
                payer.take(taken);
                left -= Math.clamp(before - payer.points(), 0, taken);
            }
        }
        if (left > 0) {
            wanted -= left;
        }
        net.minecraft.core.Position at = mouth(dropper);
        if (wanted > 0) {
            // The game splits it into orbs of its usual sizes.
            net.minecraft.world.entity.ExperienceOrb.award(level, new Vec3(at.x(), at.y(), at.z()), (int) wanted);
            level.levelEvent(1000, dropper.getBlockPos(), 0);
            level.levelEvent(2000, dropper.getBlockPos(), dropper.pointing().get3DDataValue());
        }
        JsonObject answer = new JsonObject();
        answer.addProperty("dropped", wanted);
        return answer;
    }

    /** Where things leave the plate: just past its face, as a dispenser's mouth. */
    private static net.minecraft.core.Position mouth(CablePort dropper) {
        Direction facing = dropper.pointing();
        Vec3 centre = Vec3.atCenterOf(dropper.getBlockPos());
        return new Vec3(centre.x + 0.7 * facing.getStepX(), centre.y + 0.7 * facing.getStepY(), centre.z + 0.7 * facing.getStepZ());
    }

    /**
     * Fires one item as a dispenser at the plate, facing where it points, would. Returns false if
     * the dispenser had nothing to do with it (bone meal with no plant there): it goes back.
     */
    private static boolean fire(ServerLevel level, CablePort dropper, BlockPos target, ItemStack one, ItemPort from) {
        BlockPos at = dropper.getBlockPos();
        BlockState state = net.minecraft.world.level.block.Blocks.DISPENSER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.DispenserBlock.FACING, dropper.pointing());
        // A dispenser of its own that is never placed: it holds what a behaviour hands back.
        net.minecraft.world.level.block.entity.DispenserBlockEntity holder = new net.minecraft.world.level.block.entity.DispenserBlockEntity(at, state);
        holder.setLevel(level);
        net.minecraft.core.dispenser.BlockSource source = new net.minecraft.core.dispenser.BlockSource(level, at, state, holder);
        ItemStack left = behaviour(level, one).dispense(source, one.copy());
        boolean fired = !(left.getCount() == one.getCount() && ItemStack.isSameItemSameComponents(left, one));
        List<ItemStack> back = new ArrayList<>();
        back.add(left);
        for (int i = 0; i < holder.getContainerSize(); i++) {
            back.add(holder.getItem(i));
        }
        for (ItemStack thing : back) {
            if (thing.isEmpty()) {
                continue;
            }
            int put = from.insert(thing, thing.getCount(), false);
            if (put < thing.getCount()) {
                Block.popResource(level, target, thing.copyWithCount(thing.getCount() - put)); // nowhere to go back to
            }
        }
        return fired;
    }

    /** What a dispenser does with an item, as the game's own DispenserBlock chooses it. */
    private static net.minecraft.core.dispenser.DispenseItemBehavior behaviour(ServerLevel level, ItemStack stack) {
        if (!stack.isItemEnabled(level.enabledFeatures())) {
            return new net.minecraft.core.dispenser.DefaultDispenseItemBehavior();
        }
        net.minecraft.core.dispenser.DispenseItemBehavior known = net.minecraft.world.level.block.DispenserBlock.DISPENSER_REGISTRY.get(stack.getItem());
        if (known != null) {
            return known;
        }
        if (stack.has(net.minecraft.core.component.DataComponents.EQUIPPABLE)) {
            return net.minecraft.core.dispenser.EquipmentDispenseItemBehavior.INSTANCE;
        }
        if (stack.getItem() instanceof net.minecraft.world.item.SpawnEggItem && stack.has(net.minecraft.core.component.DataComponents.ENTITY_DATA)) {
            return net.minecraft.core.dispenser.SpawnEggItemBehavior.INSTANCE;
        }
        return new net.minecraft.core.dispenser.DefaultDispenseItemBehavior();
    }

    // ---- Placer --------------------------------------------------------------------------

    private static void needsRoom(ServerLevel level, CablePort placer, BlockPos target) {
        BlockState there = level.getBlockState(target);
        if (!there.canBeReplaced() || there.getFluidState().isSource()) {
            throw new CallError("The space in front of the placer is taken by " + blockId(there)
                    + ". It places only into air, or into things that give way, such as grass or flowing water.");
        }
    }

    /** ["place", mac, sources, name or filter or null]: one item from the sources, placed as its owner would. */
    private static JsonObject place(ServerLevel level, CableNetwork network, CablePort placer, JsonArray args) {
        BlockPos target = loadedTarget(level, placer);
        needsRoom(level, placer, target);
        String wanted = NetworkCalls.string(args, 3);
        NameMatch<Item> match = wanted == null ? null : NetworkCalls.item(wanted);
        for (End end : NetworkCalls.asMedium(NetworkCalls.ends(network, args.get(2), "source"), Medium.ITEM)) {
            if (end.device().medium() != Medium.ITEM) {
                continue;
            }
            ItemPort port = NetworkCalls.itemSource(level, end);
            java.util.function.Predicate<ItemStack> own = NetworkCalls.itemFilter(end);
            List<ItemStack> contents = port.contents();
            for (int slot = 0; slot < contents.size(); slot++) {
                ItemStack stack = contents.get(slot);
                // With nothing named, only blocks: a program saying place() means "a block from here".
                if (stack.isEmpty() || !own.test(stack) || (match == null ? !(stack.getItem() instanceof BlockItem) : !match.testStack(stack))) {
                    continue;
                }
                ServerPlayer player = standIn(level, placer); // before anything is taken: a plate with no owner takes nothing
                if (port.extractFrom(slot, stack, 1, false) < 1) {
                    continue;
                }
                ItemStack one = stack.copyWithCount(1);
                String name = NetworkCalls.itemId(one.getItem()); // before it is used up
                player.setItemInHand(InteractionHand.MAIN_HAND, one);
                BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(target), placer.pointing().getOpposite(), target, false);
                player.gameMode.useItemOn(player, level, one, InteractionHand.MAIN_HAND, hit);
                ItemStack left = player.getMainHandItem();
                player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                boolean placed = !(left.getCount() == 1 && ItemStack.isSameItemSameComponents(left, one));
                if (!left.isEmpty()) {
                    // Not placed, or something was left in hand (an emptied bucket): back where it came from.
                    int back = port.insert(left, left.getCount(), false);
                    if (back < left.getCount()) {
                        Block.popResource(level, placer.getBlockPos(), left.copyWithCount(left.getCount() - back));
                    }
                }
                if (!placed) {
                    throw new CallError("The placer couldn't place " + name
                            + " there: it can't stand there, or its owner may not build here.");
                }
                JsonObject reply = new JsonObject();
                reply.addProperty("placed", name);
                return reply;
            }
        }
        throw new CallError(wanted == null ? "None of the sources holds a block to place." : "None of the sources holds " + wanted + " to place.");
    }

    /** ["pour", mac, sources, fluid name or filter or null]: a bucket's worth from the sources, poured as its owner would. */
    private static JsonObject pour(ServerLevel level, CableNetwork network, CablePort placer, JsonArray args) {
        BlockPos target = loadedTarget(level, placer);
        needsRoom(level, placer, target);
        String wanted = NetworkCalls.string(args, 3);
        NameMatch<Fluid> match = wanted == null ? null : NetworkCalls.fluid(wanted);
        boolean anyFluidSource = false;
        for (End end : NetworkCalls.asMedium(NetworkCalls.ends(network, args.get(2), "source"), Medium.FLUID)) {
            if (end.device().medium() != Medium.FLUID) {
                continue;
            }
            anyFluidSource = true;
            FluidPort tanks = NetworkCalls.fluidSource(level, end.device(), end.face());
            java.util.function.Predicate<Fluid> own = NetworkCalls.endFilter(end, NetworkCalls::fluid);
            for (FluidPort.Tank tank : tanks.tanks()) {
                Fluid fluid = tank.fluid();
                if (fluid == null || tank.amount() < FluidPort.BUCKET || !own.test(fluid) || (match != null && !match.test(fluid))) {
                    continue;
                }
                if (tanks.extract(fluid, FluidPort.BUCKET, true) < FluidPort.BUCKET || !(fluid.getBucket() instanceof BucketItem bucket)) {
                    continue;
                }
                ServerPlayer player = standIn(level, placer);
                boolean[] poured = {false};
                boolean allowed = Qbotics.platform().placeChecked(level, player, target, placer.pointing().getOpposite(),
                        () -> poured[0] = bucket.emptyContents(player, level, target, null));
                if (!allowed) {
                    throw new CallError("The placer's owner may not build there, so it poured nothing.");
                }
                if (!poured[0]) {
                    throw new CallError("The placer couldn't pour " + fluidId(fluid) + " there.");
                }
                tanks.extract(fluid, FluidPort.BUCKET, false);
                JsonObject reply = new JsonObject();
                reply.addProperty("poured", fluidId(fluid));
                return reply;
            }
        }
        if (!anyFluidSource) {
            throw new CallError("Pouring takes fluid: give a fluid interface or a fluid bus as the source.");
        }
        throw new CallError("None of the sources holds a bucket's worth (1000 mB) of " + (wanted == null ? "fluid" : wanted) + " that can be poured.");
    }

    // ---- Breaker -------------------------------------------------------------------------

    /** ["break", mac, destinations]: breaks the block in front, delivering every drop, or does nothing. */
    private static JsonObject breakBlock(ServerLevel level, CableNetwork network, CablePort breaker, JsonArray args) {
        BlockPos target = loadedTarget(level, breaker);
        BlockState state = level.getBlockState(target);
        if (state.isAir()) {
            throw new CallError("There is nothing in front of the breaker to break.");
        }
        List<End> destinations = NetworkCalls.ends(network, args.get(2), "destination");
        ServerPlayer player = standIn(level, breaker);
        if (state.getBlock() instanceof LiquidBlock) {
            return takeFluid(level, breaker, target, state, destinations, player);
        }
        if (state.getDestroySpeed(level, target) < 0) {
            throw new CallError(blockId(state) + " can't be broken.");
        }
        ItemStack tool = WorldPlates.toolFor(state);
        int wait = WorldPlates.breakWait(level, breaker, state, tool);
        if (wait > 0) {
            JsonObject reply = new JsonObject();
            reply.addProperty("wait", wait);
            return reply;
        }
        if (!Qbotics.platform().mayBreak(level, player, target)) {
            throw new CallError("The breaker's owner may not break blocks there.");
        }
        WorldPlates.Drops drops = WorldPlates.dropsOf(level, breaker, state, player, tool);
        List<ItemPort> to = new ArrayList<>();
        for (End end : NetworkCalls.asMedium(destinations, Medium.ITEM)) {
            if (end.device().medium() == Medium.ITEM) {
                to.add(NetworkCalls.itemDestination(level, end));
            }
        }
        if (!drops.stacks().isEmpty() && to.isEmpty()) {
            throw new CallError("Breaking " + blockId(state) + " gives items: give an interface or a bus as the destination.");
        }
        // All or nothing, asked before anything moves: a destination that takes items need not give them
        // back (a composter, a machine's input side), so nothing is put in until every drop has room.
        for (ItemStack drop : drops.stacks()) {
            int fits = 0;
            for (ItemPort port : to) {
                if (fits >= drop.getCount()) {
                    break;
                }
                fits += port.insert(drop, drop.getCount() - fits, true);
            }
            if (fits < drop.getCount()) {
                throw new CallError("No room for " + (drop.getCount() - fits) + " " + NetworkCalls.itemId(drop.getItem()) + " from " + blockId(state)
                        + ", so it was not broken. Every drop must have somewhere to go.");
            }
        }
        JsonObject got = new JsonObject();
        List<ItemStack> spilled = new ArrayList<>();
        for (ItemStack drop : drops.stacks()) {
            int left = drop.getCount();
            for (ItemPort port : to) {
                if (left <= 0) {
                    break;
                }
                left -= port.insert(drop, left, false);
            }
            if (left > 0) {
                // Two kinds of drop were each promised the same last space: the rest falls where the block was.
                spilled.add(drop.copyWithCount(left));
            }
            String id = NetworkCalls.itemId(drop.getItem());
            got.addProperty(id, (got.has(id) ? got.get(id).getAsInt() : 0) + drop.getCount() - left);
        }
        WorldPlates.remove(level, breaker, state, drops.contents());
        for (ItemStack stack : spilled) {
            net.minecraft.world.level.block.Block.popResource(level, target, stack);
        }
        JsonObject reply = new JsonObject();
        reply.addProperty("broke", blockId(state));
        reply.add("got", got);
        return reply;
    }

    /** A fluid source in front of a Breaker: taken up whole, a bucket's worth, into a fluid store. */
    private static JsonObject takeFluid(ServerLevel level, CablePort breaker, BlockPos target, BlockState state, List<End> destinations, ServerPlayer player) {
        FluidState fluid = state.getFluidState();
        if (!fluid.isSource() || !(state.getBlock() instanceof BucketPickup pickup)) {
            throw new CallError("Only a fluid's source can be taken up, not fluid flowing from one.");
        }
        Fluid kind = fluid.getType();
        FluidPort room = null;
        boolean anyFluid = false;
        for (End end : NetworkCalls.asMedium(destinations, Medium.FLUID)) {
            FluidPort port = null;
            if (end.device() instanceof FluidInterfaceBlockEntity store) {
                port = store;
            } else if (end.device() instanceof CablePort plate && plate.medium() == Medium.FLUID && plate.takes()) {
                port = NetworkCalls.fluidTarget(level, plate, end.face(), end.slot() >= 0);
            }
            if (port != null) {
                anyFluid = true;
                if (port.insert(kind, FluidPort.BUCKET, true) >= FluidPort.BUCKET) {
                    room = port;
                    break;
                }
            }
        }
        if (!anyFluid) {
            throw new CallError("To take up " + fluidId(kind) + ", give a fluid interface or a fluid bus as the destination.");
        }
        if (room == null) {
            throw new CallError("No room for a bucket's worth of " + fluidId(kind) + ", so it was left where it is.");
        }
        if (!Qbotics.platform().mayBreak(level, player, target)) {
            throw new CallError("The breaker's owner may not take anything from there.");
        }
        if (pickup.pickupBlock(player, level, target, state).isEmpty()) {
            throw new CallError("That fluid can't be taken up.");
        }
        room.insert(kind, FluidPort.BUCKET, false);
        JsonObject got = new JsonObject();
        got.addProperty(fluidId(kind), FluidPort.BUCKET);
        JsonObject reply = new JsonObject();
        reply.addProperty("broke", fluidId(kind));
        reply.add("got", got);
        return reply;
    }
}
