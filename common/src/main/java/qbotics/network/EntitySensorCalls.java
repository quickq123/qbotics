package qbotics.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import qbotics.Qbotics;
import qbotics.block.EntitySensorBlockEntity;
import qbotics.block.ExperienceInterfaceBlockEntity;
import qbotics.network.NetworkCalls.CallError;
import qbotics.network.NetworkCalls.End;

/**
 * The Entity Sensor's calls (docs/reference.md §6.12): what is in its area, and acting on it as
 * its owner would: using an item on a creature (milking, shearing, breeding), attacking with a
 * weapon from storage, trading with villagers, and picking up dropped things and experience.
 * Each call acts on one creature (or, picking up, on everything lying in the area).
 */
final class EntitySensorCalls {
    /** The most entities one look lists, nearest first. */
    private static final int MOST_LISTED = 256;
    /** The most trades one call makes. */
    private static final int MOST_TRADES = 64;
    /** The most items one pick-up gathers. */
    private static final int MOST_COLLECTED = 64 * 27;

    private EntitySensorCalls() {
    }

    static JsonElement call(String method, ServerLevel level, CableNetwork network, JsonArray args) {
        NetworkDevice device = NetworkCalls.byMac(network, args, 1);
        if (!(device instanceof EntitySensorBlockEntity sensor)) {
            throw new CallError("That call is for an Entity Sensor; this is " + NetworkCalls.article(device) + ".");
        }
        level = NetworkCalls.levelOf(device, level);
        return switch (method) {
            case "sensor_entities" -> list(level, sensor, args);
            case "sensor_entity" -> describe(sensor, find(level, sensor, args, 2));
            case "sensor_contents" -> contents(level, sensor, args);
            case "sensor_slots" -> {
                // ["sensor_slots", mac, uuid]: every slot of an entity's inventory, as a chest's slots().
                JsonObject written = new JsonObject();
                written.addProperty("entity", NetworkCalls.string(args, 2));
                yield NetworkCalls.slotList(inventory(level, new End(sensor, null, -1, written, null)));
            }
            case "sensor_use" -> use(level, network, sensor, args);
            case "sensor_attack" -> attack(level, network, sensor, args);
            case "sensor_offers" -> offers(level, find(level, sensor, args, 2));
            case "sensor_trade" -> trade(level, network, sensor, args);
            case "sensor_collect" -> collect(level, network, sensor, args);
            default -> throw new CallError("This computer doesn't know the call '" + method + "'.");
        };
    }

    // ---- the area and what is in it ------------------------------------------------------

    /** ["sensor_entities", mac, name or filter of a kind of entity, or null]: what is in the area, nearest first. */
    private static JsonArray list(ServerLevel level, EntitySensorBlockEntity sensor, JsonArray args) {
        String wanted = NetworkCalls.string(args, 2);
        NameMatch<EntityType<?>> match = wanted == null ? null : NetworkCalls.entityType(wanted);
        Vec3 centre = Vec3.atCenterOf(sensor.getBlockPos());
        List<Entity> found = level.getEntities((Entity) null, sensor.area(),
                entity -> entity.isAlive() && sensor.holds(entity) && !(entity instanceof ServerPlayer player && player.isSpectator()) && !isStandIn(entity)
                        && (match == null || match.test(entity.getType())));
        found.sort(Comparator.comparingDouble(entity -> entity.distanceToSqr(centre)));
        JsonArray list = new JsonArray();
        for (int i = 0; i < Math.min(found.size(), MOST_LISTED); i++) {
            list.add(describe(sensor, found.get(i)));
        }
        return list;
    }

    private static boolean isStandIn(Entity entity) {
        return entity instanceof ServerPlayer player && player.getClass().getSimpleName().equals("StandIn");
    }

    /** The entity with the UUID at args[index], alive and still in the area. */
    private static Entity find(ServerLevel level, EntitySensorBlockEntity sensor, JsonArray args, int index) {
        String written = NetworkCalls.string(args, index);
        UUID id;
        try {
            id = UUID.fromString(written == null ? "" : written);
        } catch (IllegalArgumentException e) {
            throw new CallError("'" + written + "' isn't an entity's ID.");
        }
        Entity entity = level.getEntity(id);
        if (entity == null || !entity.isAlive()) {
            throw new CallError("That " + (entity == null ? "entity" : name(entity)) + " is gone.");
        }
        if (!sensor.holds(entity)) {
            throw new CallError("The " + name(entity) + " has left the sensor's area.");
        }
        return entity;
    }

    private static String typeId(Entity entity) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
    }

    private static String name(Entity entity) {
        return entity.getName().getString();
    }

    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }

    /** What a program learns about an entity: where it is, and what its kind has to say. */
    static JsonObject describe(EntitySensorBlockEntity sensor, Entity entity) {
        JsonObject info = new JsonObject();
        info.addProperty("uuid", entity.getUUID().toString());
        info.addProperty("type", typeId(entity));
        info.addProperty("name", name(entity));
        if (entity.hasCustomName() && entity.getCustomName() != null) {
            info.addProperty("custom_name", entity.getCustomName().getString());
        } else {
            info.add("custom_name", JsonNull.INSTANCE);
        }
        Vec3 centre = Vec3.atCenterOf(sensor.getBlockPos());
        JsonArray pos = new JsonArray();
        JsonArray offset = new JsonArray();
        for (int axis = 0; axis < 3; axis++) {
            double at = axis == 0 ? entity.getX() : axis == 1 ? entity.getY() : entity.getZ();
            double from = axis == 0 ? centre.x : axis == 1 ? centre.y : centre.z;
            pos.add(round(at));
            offset.add(round(at - from));
        }
        info.add("pos", pos);
        info.add("offset", offset);
        info.addProperty("distance", round(Math.sqrt(entity.distanceToSqr(centre))));
        info.addProperty("player", entity instanceof net.minecraft.world.entity.player.Player);
        if (entity instanceof LivingEntity living) {
            info.addProperty("health", round(living.getHealth()));
            info.addProperty("max_health", round(living.getMaxHealth()));
            info.addProperty("baby", living.isBaby());
            ItemStack held = living.getMainHandItem();
            info.add("held", held.isEmpty() ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(NetworkCalls.itemId(held.getItem())));
            info.addProperty("hostile", entity instanceof net.minecraft.world.entity.monster.Enemy);
        }
        if (entity instanceof net.minecraft.world.entity.Mob mob) {
            info.addProperty("leashed", mob.isLeashed());
        }
        if (entity instanceof LivingEntity living) {
            // What it wears: the armour a zombie picked up, or what an armor stand shows.
            JsonObject armor = new JsonObject();
            for (net.minecraft.world.entity.EquipmentSlot slot : new net.minecraft.world.entity.EquipmentSlot[] {
                    net.minecraft.world.entity.EquipmentSlot.HEAD, net.minecraft.world.entity.EquipmentSlot.CHEST,
                    net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.entity.EquipmentSlot.FEET}) {
                ItemStack worn = living.getItemBySlot(slot);
                armor.add(slot.getName(), worn.isEmpty() ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(NetworkCalls.itemId(worn.getItem())));
            }
            info.add("armor", armor);
            ItemStack off = living.getOffhandItem();
            info.add("offhand", off.isEmpty() ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(NetworkCalls.itemId(off.getItem())));
        }
        if (entity instanceof net.minecraft.world.entity.decoration.ItemFrame frame) {
            ItemStack shown = frame.getItem();
            info.add("framed", shown.isEmpty() ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(NetworkCalls.itemId(shown.getItem())));
            info.addProperty("rotation", frame.getRotation());
        }
        ItemPort items = entity instanceof net.minecraft.world.entity.player.Player ? null : Qbotics.platform().entityItems(entity);
        info.addProperty("slots", items == null ? 0 : items.contents().size());
        if (entity instanceof net.minecraft.world.entity.AgeableMob ageable) {
            info.addProperty("age", ageable.getAge());
        }
        if (entity instanceof net.minecraft.world.entity.animal.Animal animal) {
            info.addProperty("in_love", animal.isInLove());
            info.addProperty("can_breed", animal.getAge() == 0 && animal.canFallInLove());
        }
        if (entity instanceof net.minecraft.world.entity.animal.sheep.Sheep sheep) {
            info.addProperty("sheared", sheep.isSheared());
            info.addProperty("color", sheep.getColor().getName());
        }
        if (entity instanceof net.minecraft.world.entity.TamableAnimal pet) {
            info.addProperty("tame", pet.isTame());
            var owner = pet.getOwnerReference();
            info.add("owner", owner == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(owner.getUUID().toString()));
        }
        if (entity instanceof net.minecraft.world.entity.npc.villager.AbstractVillager trader) {
            info.addProperty("trading", trader.isTrading());
        }
        if (entity instanceof net.minecraft.world.entity.npc.villager.Villager villager) {
            var data = villager.getVillagerData();
            info.addProperty("profession", data.profession().unwrapKey().map(key -> key.identifier().getPath()).orElse("none"));
            info.addProperty("level", data.level());
        }
        if (entity instanceof ItemEntity item) {
            info.addProperty("item", NetworkCalls.itemId(item.getItem().getItem()));
            info.addProperty("count", item.getItem().getCount());
        }
        if (entity instanceof ExperienceOrb orb) {
            info.addProperty("value", qbotics.block.Experience.orbPoints(orb));
        }
        return info;
    }

    // ---- an entity's inventory ---------------------------------------------------------------

    /**
     * The items of the entity an end of a move names ({"mac": sensor, "entity": uuid}): in the
     * sensor's area, alive, and not a player. A move can take from it and put into it, slot by
     * slot, as from a chest behind a bus.
     */
    static ItemPort inventory(ServerLevel level, End end) {
        if (!(end.device() instanceof EntitySensorBlockEntity sensor)) {
            throw new CallError("An entity is reached through the Entity Sensor that sees it.");
        }
        JsonArray args = new JsonArray();
        args.add("");
        args.add("");
        args.add(end.written().get("entity").getAsString());
        Entity entity = find(level, sensor, args, 2);
        if (entity instanceof net.minecraft.world.entity.player.Player) {
            throw new CallError("A player's inventory is reached with a Player Interface, not a sensor.");
        }
        ItemPort items = Qbotics.platform().entityItems(entity);
        if (items == null) {
            throw new CallError("The " + name(entity) + " has no inventory to reach.");
        }
        if (!Boundaries.mayReach(level, sensor, entity.blockPosition())) {
            throw new CallError("The " + name(entity) + " is on land the sensor's owner may not change, so its inventory can't be reached.");
        }
        return items;
    }

    /** ["sensor_contents", mac, uuid, details]: what an entity holds, slot by slot. */
    private static JsonArray contents(ServerLevel level, EntitySensorBlockEntity sensor, JsonArray args) {
        JsonObject written = new JsonObject();
        written.addProperty("entity", NetworkCalls.string(args, 2));
        ItemPort items = inventory(level, new End(sensor, null, -1, written, null));
        boolean details = args.size() > 3 && !args.get(3).isJsonNull() && args.get(3).getAsBoolean();
        JsonArray list = new JsonArray();
        List<ItemStack> stacks = items.contents();
        for (int slot = 0; slot < stacks.size(); slot++) {
            ItemStack stack = stacks.get(slot);
            if (stack.isEmpty()) {
                continue;
            }
            JsonObject entry = details ? stackJson(stack, level) : new JsonObject();
            entry.addProperty("slot", slot);
            entry.addProperty("item", NetworkCalls.itemId(stack.getItem()));
            entry.addProperty("count", stack.getCount());
            list.add(entry);
        }
        return list;
    }

    // ---- acting as the owner -------------------------------------------------------------

    /** The owner's stand-in, at the sensor, looking at the entity. A sensor nobody owns can't act. */
    private static ServerPlayer standIn(ServerLevel level, EntitySensorBlockEntity sensor, Entity facing) {
        UUID owner = sensor.owner();
        if (owner == null) {
            throw new CallError("This Entity Sensor has no owner to act for (it wasn't placed by a player), so it can only look.");
        }
        ServerPlayer player = Qbotics.platform().standIn(level, owner, sensor.deviceId(), sensor.getBlockPos(), net.minecraft.core.Direction.UP);
        player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, facing.getEyePosition());
        return player;
    }

    /** One item taken from sources: what it is and where it came from (to go back to). */
    record Taken(ItemStack stack, ItemPort from) {
    }

    /** Takes one item that a name or filter (or anything, with none) passes, from the first source that has one. */
    static @Nullable Taken takeOne(ServerLevel level, CableNetwork network, JsonElement written, @Nullable String wanted) {
        return takeOne(level, network, written, wanted, stack -> true);
    }

    /** The same, of the items that also pass a test (only blocks, say). */
    static @Nullable Taken takeOne(ServerLevel level, CableNetwork network, JsonElement written, @Nullable String wanted,
            java.util.function.Predicate<ItemStack> also) {
        NameMatch<Item> match = wanted == null ? null : NetworkCalls.item(wanted);
        for (End end : NetworkCalls.asMedium(NetworkCalls.ends(network, written, "source"), Medium.ITEM)) {
            if (end.device().medium() != Medium.ITEM) {
                continue;
            }
            ItemPort port = NetworkCalls.itemSource(level, end);
            java.util.function.Predicate<ItemStack> own = NetworkCalls.itemFilter(end);
            List<ItemStack> contents = port.contents();
            for (int slot = 0; slot < contents.size(); slot++) {
                ItemStack stack = contents.get(slot);
                if (!stack.isEmpty() && own.test(stack) && also.test(stack) && (match == null || match.testStack(stack)) && (end.slot() < 0 || slot == end.slot())
                        && port.extractFrom(slot, stack, 1, false) == 1) {
                    return new Taken(stack.copyWithCount(1), port);
                }
            }
        }
        return null;
    }

    /** The item destinations named at args[index], or none. */
    static List<ItemPort> destinations(ServerLevel level, CableNetwork network, JsonArray args, int index) {
        List<ItemPort> out = new ArrayList<>();
        if (index >= args.size() || args.get(index).isJsonNull()) {
            return out;
        }
        for (End end : NetworkCalls.asMedium(NetworkCalls.ends(network, args.get(index), "destination"), Medium.ITEM)) {
            if (end.device().medium() == Medium.ITEM) {
                out.add(NetworkCalls.itemDestination(level, end));
            }
        }
        return out;
    }

    /** Puts things away: into the first places that take them, and what none takes onto the ground at the entity. */
    static void putAway(ServerLevel level, List<ItemStack> things, List<ItemPort> into, Vec3 at, JsonObject gave) {
        long carried = 0;
        for (ItemStack thing : things) {
            carried += thing.getCount();
        }
        NetworkCalls.transportSpend(level, carried); // across antennas, it counts against their Transport rate
        for (ItemStack thing : things) {
            if (thing.isEmpty()) {
                continue;
            }
            gave.addProperty(NetworkCalls.itemId(thing.getItem()),
                    (gave.has(NetworkCalls.itemId(thing.getItem())) ? gave.get(NetworkCalls.itemId(thing.getItem())).getAsInt() : 0) + thing.getCount());
            int left = thing.getCount();
            for (ItemPort port : into) {
                if (left <= 0) {
                    break;
                }
                left -= port.insert(thing, left, false);
            }
            if (left > 0) {
                Block.popResource(level, net.minecraft.core.BlockPos.containing(at), thing.copyWithCount(left));
            }
        }
    }

    /** Everything the stand-in holds now (in hand and in its pockets), taken from it. */
    static List<ItemStack> emptyHands(ServerPlayer player) {
        List<ItemStack> held = new ArrayList<>();
        held.add(player.getMainHandItem());
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            held.add(player.getInventory().removeItemNoUpdate(i));
        }
        return held;
    }

    /**
     * ["sensor_use", mac, uuid, sources or null, name or filter or null, results or null]: right-clicks
     * the entity as the owner, holding one item from the sources (or nothing): a bucket on a cow
     * milks it, wheat breeds it, shears shear a sheep. What comes of it (the milk bucket, the
     * wool) goes to the results, or back to the sources; an item that wasn't used goes back.
     */
    private static JsonObject use(ServerLevel level, CableNetwork network, EntitySensorBlockEntity sensor, JsonArray args) {
        NetworkCalls.transportCheck(level);
        Entity entity = find(level, sensor, args, 2);
        ServerPlayer player = standIn(level, sensor, entity);
        String wanted = NetworkCalls.string(args, 4);
        boolean withItem = args.size() > 3 && !args.get(3).isJsonNull();
        // Before an item is taken: a destination that can't be used (its chest gone, its owner away) stops the call here.
        List<ItemPort> into = destinations(level, network, args, 5);
        Taken taken = null;
        if (withItem) {
            taken = takeOne(level, network, args.get(3), wanted);
            if (taken == null) {
                throw new CallError("The sources hold no " + (wanted == null ? "items" : wanted) + " to use on the " + name(entity) + ".");
            }
            if (taken.stack().is(net.minecraft.world.item.Items.LEAD)) {
                taken.from().insert(taken.stack(), 1, false);
                throw new CallError("A lead would tie the " + name(entity) + " to the sensor's stand-in, which can't lead it anywhere; it went back.");
            }
        } else if (wanted != null) {
            throw new CallError("Say where the " + wanted + " comes from: use(\"" + wanted + "\", sources).");
        }
        emptyHands(player);
        player.setItemInHand(InteractionHand.MAIN_HAND, taken == null ? ItemStack.EMPTY : taken.stack().copy());
        InteractionResult result = player.interactOn(entity, InteractionHand.MAIN_HAND, entity.position());
        doneTrading(entity, player);
        boolean used = result.consumesAction();
        List<ItemStack> back = emptyHands(player);
        if (taken != null) {
            if (!used) {
                into.addFirst(taken.from()); // nothing happened: the item goes back where it came from
            } else if (into.isEmpty()) {
                into.add(taken.from());
            }
        }
        JsonObject gave = new JsonObject();
        putAway(level, back, into, entity.position(), gave);
        JsonObject answer = new JsonObject();
        answer.addProperty("used", used);
        answer.add("gave", gave);
        answer.add("entity", entity.isAlive() ? describe(sensor, entity) : JsonNull.INSTANCE);
        return answer;
    }

    /**
     * ["sensor_attack", mac, uuid, sources or null, weapon name or filter or null, results or null, full]:
     * hits the entity as the owner, with a weapon from the sources (its damage, enchantments,
     * Looting and wear all count) or a fist. A full swing waits for the weapon's cooldown since
     * the sensor's last: the answer is {"wait": ticks} until then. The weapon goes back afterwards.
     */
    private static JsonObject attack(ServerLevel level, CableNetwork network, EntitySensorBlockEntity sensor, JsonArray args) {
        NetworkCalls.transportCheck(level);
        Entity entity = find(level, sensor, args, 2);
        if (entity instanceof net.minecraft.world.entity.player.Player && !qbotics.QboticsConfig.sensorsAttackPlayers) {
            throw new CallError("An Entity Sensor doesn't attack players here (the server's sensors.attack_players setting is off).");
        }
        ServerPlayer player = standIn(level, sensor, entity);
        boolean withWeapon = args.size() > 3 && !args.get(3).isJsonNull();
        String wanted = NetworkCalls.string(args, 4);
        boolean full = args.size() <= 6 || args.get(6).isJsonNull() || args.get(6).getAsBoolean();
        // Before the weapon is taken: a destination that can't be used stops the call here.
        List<ItemPort> into = destinations(level, network, args, 5);
        Taken weapon = null;
        if (withWeapon) {
            weapon = takeOne(level, network, args.get(3), wanted);
            if (weapon == null) {
                throw new CallError("The sources hold no " + (wanted == null ? "weapon" : wanted) + ".");
            }
        }
        emptyHands(player);
        Swings.Armed armed = Swings.arm(player, weapon == null ? ItemStack.EMPTY : weapon.stack().copy());
        long now = level.getGameTime();
        int since = Swings.since(now, sensor.lastSwing());
        JsonObject answer = new JsonObject();
        if (weapon != null) {
            into.addFirst(weapon.from());
        }
        int wait = Swings.waitFor(player, since);
        if (full && wait > 0) {
            armed.disarm();
            putAway(level, emptyHands(player), into, entity.position(), new JsonObject());
            answer.addProperty("wait", wait);
            return answer;
        }
        Swings.swing(player, entity, since);
        sensor.swung(now);
        armed.disarm();
        List<ItemStack> back = emptyHands(player);
        answer.addProperty("attacked", typeId(entity));
        answer.addProperty("alive", entity.isAlive());
        answer.addProperty("health", entity instanceof LivingEntity living ? round(Math.max(0, living.getHealth())) : 0);
        answer.add("entity", entity.isAlive() ? describe(sensor, entity) : JsonNull.INSTANCE);
        if (weapon != null && back.getFirst().isEmpty()) {
            answer.addProperty("weapon_broke", NetworkCalls.itemId(weapon.stack().getItem()));
        }
        putAway(level, back, into, entity.position(), new JsonObject());
        return answer;
    }

    // ---- trading -------------------------------------------------------------------------

    /**
     * A villager right-clicked by a stand-in takes it for its customer, and the stand-in opens no
     * trading screen whose closing would end that: it is ended here, or no player could trade with it.
     */
    static void doneTrading(Entity entity, ServerPlayer player) {
        if (entity instanceof net.minecraft.world.entity.npc.villager.AbstractVillager trader && trader.getTradingPlayer() == player) {
            trader.setTradingPlayer(null);
        }
    }

    private static net.minecraft.world.entity.npc.villager.AbstractVillager trader(Entity entity) {
        if (entity instanceof net.minecraft.world.entity.npc.villager.AbstractVillager trader) {
            return trader;
        }
        throw new CallError("Only villagers and wandering traders trade; this is a " + name(entity) + ".");
    }

    private static JsonObject stackJson(ItemStack stack, ServerLevel level) {
        JsonObject out = new JsonObject();
        out.addProperty("item", NetworkCalls.itemId(stack.getItem()));
        out.addProperty("count", stack.getCount());
        if (!stack.getComponentsPatch().isEmpty()) {
            JsonArray words = new JsonArray();
            ItemData.words(stack).forEach(words::add);
            out.add("words", words);
            out.addProperty("filter", ItemData.exactFilter(stack, level.registryAccess()));
        }
        return out;
    }

    /** ["sensor_offers", mac, uuid]: what a villager or wandering trader offers now, at its prices now. */
    private static JsonArray offers(ServerLevel level, Entity entity) {
        MerchantOffers offers = trader(entity).getOffers();
        JsonArray list = new JsonArray();
        for (int i = 0; i < offers.size(); i++) {
            MerchantOffer offer = offers.get(i);
            JsonObject one = new JsonObject();
            one.addProperty("index", i);
            one.add("buy", stackJson(offer.getCostA(), level));
            one.add("buy_b", offer.getCostB().isEmpty() ? JsonNull.INSTANCE : stackJson(offer.getCostB(), level));
            one.add("sell", stackJson(offer.getResult(), level));
            one.addProperty("uses", offer.getUses());
            one.addProperty("max_uses", offer.getMaxUses());
            one.addProperty("out_of_stock", offer.isOutOfStock());
            list.add(one);
        }
        return list;
    }

    /** Takes exactly count items a cost accepts from the sources, or nothing (what was taken goes back). */
    private static boolean pay(List<ItemPort> from, java.util.function.Predicate<ItemStack> accepts, int count, List<Taken> paid) {
        int left = count;
        for (ItemPort port : from) {
            List<ItemStack> contents = port.contents();
            for (int slot = 0; slot < contents.size() && left > 0; slot++) {
                ItemStack stack = contents.get(slot);
                if (stack.isEmpty() || !accepts.test(stack)) {
                    continue;
                }
                int got = port.extractFrom(slot, stack, left, false);
                if (got > 0) {
                    paid.add(new Taken(stack.copyWithCount(got), port));
                    left -= got;
                }
            }
        }
        return left <= 0;
    }

    /**
     * ["sensor_trade", mac, uuid, index, sources, results or null, times]: makes one of a trader's
     * offers, up to times times, as the owner: the price taken from the sources (checked before
     * anything moves), what it sells to the results (or back to the sources). Stops early when
     * the offer runs out or the price can't be paid.
     */
    private static JsonObject trade(ServerLevel level, CableNetwork network, EntitySensorBlockEntity sensor, JsonArray args) {
        Entity entity = find(level, sensor, args, 2);
        var trader = trader(entity);
        Integer index = args.size() > 3 && args.get(3).isJsonPrimitive() ? args.get(3).getAsInt() : null;
        MerchantOffers offers = trader.getOffers();
        if (index == null || index < 0 || index >= offers.size()) {
            throw new CallError("The " + name(entity) + " has " + offers.size() + " offers, numbered from 0; there is no offer " + index + ".");
        }
        if (trader.isTrading()) {
            throw new CallError("The " + name(entity) + " is trading with a player right now.");
        }
        NetworkCalls.transportCheck(level);
        ServerPlayer player = standIn(level, sensor, entity);
        Integer times = NetworkCalls.wholeNumber(args, 6, "times");
        int wanted = Math.clamp(times == null ? 1 : times, 0, MOST_TRADES);
        List<ItemPort> from = new ArrayList<>();
        for (End end : NetworkCalls.asMedium(NetworkCalls.ends(network, args.get(4), "source"), Medium.ITEM)) {
            if (end.device().medium() == Medium.ITEM) {
                from.add(NetworkCalls.itemSource(level, end));
            }
        }
        List<ItemPort> into = destinations(level, network, args, 5);
        if (into.isEmpty()) {
            into.addAll(from);
        }
        MerchantOffer offer = offers.get(index);
        int made = 0;
        String stopped = null;
        JsonObject gave = new JsonObject();
        while (made < wanted) {
            if (NetworkCalls.transportRoom(level) <= 0) {
                stopped = "antennas full this second";
                break;
            }
            if (offer.isOutOfStock()) {
                stopped = "out of stock";
                break;
            }
            ItemStack costA = offer.getCostA();
            ItemStack costB = offer.getCostB();
            List<Taken> paid = new ArrayList<>();
            boolean ok = pay(from, offer.getItemCostA()::test, costA.getCount(), paid)
                    && (costB.isEmpty() || pay(from, stack -> offer.getItemCostB().map(cost -> cost.test(stack)).orElse(false), costB.getCount(), paid));
            if (!ok) {
                for (Taken back : paid) {
                    back.from().insert(back.stack(), back.stack().getCount(), false);
                }
                stopped = "can't pay";
                break;
            }
            ItemStack bought = offer.assemble();
            trader.setTradingPlayer(player);
            trader.notifyTrade(offer);
            trader.setTradingPlayer(null);
            putAway(level, List.of(bought), into, entity.position(), gave);
            made++;
        }
        JsonObject answer = new JsonObject();
        answer.addProperty("traded", made);
        answer.add("gave", gave);
        answer.add("stopped", stopped == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(stopped));
        return answer;
    }

    // ---- picking things up ---------------------------------------------------------------

    /**
     * ["sensor_collect", mac, uuid or null, destinations, name or filter or null]: picks up dropped
     * items (into item destinations) and experience orbs (into Experience Interfaces among them):
     * one entity, or everything lying in the area. Needs no owner, as a hopper doesn't.
     */
    private static JsonObject collect(ServerLevel level, CableNetwork network, EntitySensorBlockEntity sensor, JsonArray args) {
        List<Entity> targets = new ArrayList<>();
        if (NetworkCalls.string(args, 2) != null) {
            targets.add(find(level, sensor, args, 2));
        } else {
            targets.addAll(level.getEntities((Entity) null, sensor.area(), entity -> entity.isAlive() && sensor.holds(entity)
                    && (entity instanceof ItemEntity || entity instanceof ExperienceOrb)));
        }
        String wanted = NetworkCalls.string(args, 4);
        NameMatch<Item> match = wanted == null ? null : NetworkCalls.item(wanted);
        List<ItemPort> items = new ArrayList<>();
        List<ExperienceInterfaceBlockEntity> banks = new ArrayList<>();
        for (End end : NetworkCalls.ends(network, args.get(3), "destination")) {
            if (end.device() instanceof ExperienceInterfaceBlockEntity bank) {
                banks.add(bank);
            } else if (end.group() != null || end.device().medium() == Medium.ITEM || end.device().medium() == Medium.UNIVERSAL) {
                items.add(NetworkCalls.itemDestination(level, NetworkCalls.asMedium(List.of(end), Medium.ITEM).getFirst()));
            }
        }
        int gathered = 0;
        long points = 0;
        long room = Math.min(MOST_COLLECTED, NetworkCalls.transportRoom(level)); // across antennas, their Transport rate
        for (Entity entity : targets) {
            if (!Boundaries.mayReach(level, sensor, entity.blockPosition())) {
                continue; // lying on land the sensor's owner may not change
            }
            if (entity instanceof ItemEntity dropped && gathered < room) {
                ItemStack stack = dropped.getItem().copy();
                if (match != null && !match.testStack(stack)) {
                    continue;
                }
                int left = (int) Math.min(stack.getCount(), room - gathered);
                int start = left;
                for (ItemPort port : items) {
                    if (left <= 0) {
                        break;
                    }
                    left -= port.insert(stack, left, false);
                }
                int put = start - left;
                gathered += put;
                if (put >= stack.getCount()) {
                    dropped.discard();
                } else if (put > 0) {
                    dropped.setItem(stack.copyWithCount(stack.getCount() - put));
                }
            } else if (entity instanceof ExperienceOrb orb && match == null && !banks.isEmpty()) {
                int worth = qbotics.block.Experience.orbPoints(orb);
                long left = worth;
                for (ExperienceInterfaceBlockEntity bank : banks) {
                    left -= bank.add(left);
                    if (left <= 0) {
                        break;
                    }
                }
                if (left <= 0) {
                    orb.discard();
                    points += worth;
                }
            }
        }
        NetworkCalls.transportSpend(level, gathered);
        JsonObject answer = new JsonObject();
        answer.addProperty("items", gathered);
        answer.addProperty("points", points);
        return answer;
    }
}
