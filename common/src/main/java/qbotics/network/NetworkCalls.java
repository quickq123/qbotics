package qbotics.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import qbotics.Qbotics;
import qbotics.computer.ComputerManager;
import qbotics.block.AntennaBlockEntity;
import qbotics.block.InterfaceBlockEntity;
import qbotics.block.CablePort;
import qbotics.block.EnergyInterfaceBlockEntity;
import qbotics.block.FluidInterfaceBlockEntity;
import qbotics.network.NetworkDevice.Kind;

/**
 * What a computer's program can ask of its cable network (design doc §2.3, §11.0). A
 * request arrives as a JSON list, ["method", argument, ...], from the computer's own
 * thread; it is answered here on the server thread, the only place the world may be
 * touched. The answer is {"r": result} or {"e": "what went wrong"}.
 *
 * <p>Everything in a request comes from a player's program: nothing in it is trusted.
 */
public final class NetworkCalls {
    /** The most one call may move. A program that wants more calls again. */
    private static final int MAX_ITEMS_PER_CALL = 64 * 9;

    private NetworkCalls() {
    }

    /** A mistake in the request, reported to the program as an exception it can read. */
    static final class CallError extends RuntimeException {
        CallError(String message) {
            super(message, null, false, false);
        }
    }

    public static String handle(MinecraftServer server, ResourceKey<Level> dimension, BlockPos computerPos, String request) {
        return handle(server, dimension, computerPos, request, null);
    }

    /** The program asking, when it isn't the one its block runs: a Server's service. Server thread only. */
    private static qbotics.computer.@org.jspecify.annotations.Nullable ServerComputer callAs;

    /**
     * @param as a Server's service making the call (its events and displays are its own), or
     *           null for the computer at {@code computerPos}
     */
    public static String handle(MinecraftServer server, ResourceKey<Level> dimension, BlockPos computerPos, String request,
            qbotics.computer.@org.jspecify.annotations.Nullable ServerComputer as) {
        callAs = as;
        try {
            return handleAs(server, dimension, computerPos, request);
        } finally {
            callAs = null;
        }
    }

    private static String handleAs(MinecraftServer server, ResourceKey<Level> dimension, BlockPos computerPos, String request) {
        JsonObject reply = new JsonObject();
        try {
            JsonArray args = JsonParser.parseString(request).getAsJsonArray();
            ServerLevel level = server.getLevel(dimension);
            if (level == null || !level.isLoaded(computerPos)) {
                throw new CallError("This computer's block isn't loaded, so it can't reach its network.");
            }
            CableNetwork network = CableNetwork.at(level, computerPos);
            if (network.tooLarge()) {
                throw new CallError("This network is too large: it has more than " + CableNetwork.MAX_BLOCKS
                        + " cables and devices, or more than " + (Addresses.LAST_HOST - Addresses.FIRST_HOST + 1) + " devices.");
            }
            callLevel = level;
            callPos = computerPos;
            LINKS.clear();
            REMOTE.clear();
            JsonElement result = run(level, network, computerPos, args);
            reply.add("r", result);
            try {
                if (result != null) {
                    LessonHooks.afterCall(level, network, computerPos, args, result, !REMOTE.isEmpty());
                }
            } catch (RuntimeException e) {
                Qbotics.LOGGER.debug("A lesson's check failed on {}", request, e); // never the call's problem
            }
        } catch (CallError e) {
            reply.addProperty("e", e.getMessage());
        } catch (RuntimeException e) {
            // A malformed request (wrong argument types or count), or a fault in a container.
            Qbotics.LOGGER.warn("Network call failed: {}", request, e);
            reply = new JsonObject();
            reply.addProperty("e", "The call failed: its arguments are wrong, or something went wrong in the game ("
                    + e.getClass().getSimpleName() + ").");
        } finally {
            callLevel = null;
            callPos = null;
            LINKS.clear();
            REMOTE.clear();
        }
        return reply.toString();
    }

    // ---- devices behind another antenna (the Transport Upgrade) ----------------------------

    /**
     * For a call that carries items through antennas other than by move() (a sensor across the
     * radio): refuses when the antennas have carried all they may this second.
     */
    static void transportCheck(ServerLevel level) {
        if (!LINKS.isEmpty() && qbotics.upgrade.TransportRate.room(LINKS, Medium.ITEM, level.getServer().getTickCount()) <= 0) {
            throw new CallError("The antennas have carried all the items they may this second; try again in a moment.");
        }
    }

    /** What such a call carried, counted against the antennas' Transport rate. */
    static void transportSpend(ServerLevel level, long items) {
        if (!LINKS.isEmpty() && items > 0) {
            qbotics.upgrade.TransportRate.spend(LINKS, Medium.ITEM, items, level.getServer().getTickCount());
        }
    }

    /** How many items such a call may still carry this second (unlimited when no antenna is crossed). */
    static long transportRoom(ServerLevel level) {
        return LINKS.isEmpty() ? Long.MAX_VALUE : qbotics.upgrade.TransportRate.room(LINKS, Medium.ITEM, level.getServer().getTickCount());
    }

    /** The computer the call being answered came from. Server thread only, while a call is answered. */
    private static ServerLevel callLevel;
    private static BlockPos callPos;
    /** The antennas the call being answered has reached through: what it moves counts against their rate. */
    private static final java.util.Set<AntennaBlockEntity> LINKS = new java.util.HashSet<>();
    /** Devices this call reached behind another antenna, and that antenna's external address. */
    private static final java.util.Map<NetworkDevice, String> REMOTE = new java.util.IdentityHashMap<>();

    /**
     * The device a program means by an address: a hardware address on this network, or
     * "external IP/hardware address" for one behind another antenna. Reaching one there needs
     * a Transport Upgrade in this network's antenna and in that one, and that antenna's
     * range, frequency and access rules to allow it. Returns null if there is no such device.
     */
    static NetworkDevice resolve(CableNetwork network, String mac) {
        // A Universal Serial Bus's view: its address, then "@" and the medium ("...@fluid").
        int at = mac.lastIndexOf('@');
        if (at > 0) {
            Medium wanted = Medium.carried(mac.substring(at + 1));
            if (wanted == null) {
                throw new CallError("'" + mac.substring(at + 1) + "' isn't something a USB carries: it carries item, fluid and energy.");
            }
            NetworkDevice device = resolve(network, mac.substring(0, at));
            if (device != null && wanted == Medium.ITEM && itemSlotsOf(device) instanceof SlotsView slots) {
                return slots; // a fluid or energy store's bucket or battery slots
            }
            return device == null ? null : concrete(device, wanted);
        }
        int slash = mac.indexOf('/');
        if (slash < 0) {
            return network.byMac(mac);
        }
        String ip = mac.substring(0, slash);
        if (callLevel == null) {
            throw new CallError("Devices behind another antenna can only be reached from a program.");
        }
        Far far = far(callLevel, network, ip, callPos);
        AntennaBlockEntity near = network.gateway();
        if (near == null) {
            // A Qbot away from any antenna: far() reached that one by the Qbot's own radio (in the antenna's
            // range, and let in by it). The Qbot's Transport Upgrade stands where a network's antenna's would.
            if (!(callLevel.getBlockEntity(callPos) instanceof qbotics.qbot.QbotBlockEntity qbot)
                    || !qbot.hasUpgrade(qbotics.upgrade.UpgradeItem.Kind.TRANSPORT)) {
                throw new CallError("Reaching devices behind an antenna needs a Transport Upgrade in this Qbot. Right-click the Qbot to fit one.");
            }
            if (!far.antenna().hasUpgrade(qbotics.upgrade.UpgradeItem.Kind.TRANSPORT)) {
                throw new CallError("The antenna at " + ip + " has no Transport Upgrade, so the devices behind it can't be reached from here.");
            }
            if (far.network() == null) {
                throw new CallError("The antenna at " + ip + " isn't its network's router, so nothing behind it can be reached.");
            }
            NetworkDevice device = far.network().byMac(mac.substring(slash + 1));
            if (device != null) {
                LINKS.add(far.antenna()); // what crosses counts against that antenna's rate
                REMOTE.put(device, ip);
            }
            return device;
        }
        if (far.antenna() == near) {
            return network.byMac(mac.substring(slash + 1)); // this network's own antenna: the device is here
        }
        if (!near.hasUpgrade(qbotics.upgrade.UpgradeItem.Kind.TRANSPORT)) {
            throw new CallError("Reaching devices behind another antenna needs a Transport Upgrade in this network's antenna ("
                    + near.externalIp() + "). Right-click the antenna to fit one.");
        }
        if (!far.antenna().hasUpgrade(qbotics.upgrade.UpgradeItem.Kind.TRANSPORT)) {
            throw new CallError("The antenna at " + ip + " has no Transport Upgrade, so the devices behind it can't be reached from here.");
        }
        if (far.network() == null) {
            throw new CallError("The antenna at " + ip + " isn't its network's router, so nothing behind it can be reached.");
        }
        NetworkDevice device = far.network().byMac(mac.substring(slash + 1));
        if (device != null) {
            LINKS.add(near);
            LINKS.add(far.antenna());
            REMOTE.put(device, ip);
        }
        return device;
    }

    /** The level a device is in: another network's may be in another dimension. */
    static ServerLevel levelOf(NetworkDevice device, ServerLevel fallback) {
        if (device instanceof SlotsView slots) {
            device = slots.store();
        }
        if (device instanceof net.minecraft.world.level.block.entity.BlockEntity block && block.getLevel() instanceof ServerLevel own) {
            return own;
        }
        if (device instanceof CablePort port && port.level() instanceof ServerLevel own) {
            return own;
        }
        return fallback;
    }

    /** The antennas this call has reached through, for Transport's rate. */
    static java.util.Set<AntennaBlockEntity> links() {
        return LINKS;
    }

    private static JsonElement run(ServerLevel level, CableNetwork network, BlockPos computerPos, JsonArray args) {
        String method = args.get(0).getAsString();
        if (method.startsWith("qbot_")) {
            return QbotCalls.call(method, level, computerPos, args); // a Qbot's program asking its body
        }
        switch (method) {
            case "self": {
                for (NetworkDevice device : network.devices()) {
                    if (device.getBlockPos().equals(computerPos)) {
                        JsonObject me = describe(level, network, device);
                        if (device instanceof qbotics.qbot.QbotBlockEntity qbot) {
                            // A Qbot's radio is its own: reached there wherever it goes.
                            me.addProperty("external_ip", qbot.radioIp(level.getServer()));
                            me.addProperty("port", qbotics.qbot.QbotBlockEntity.RADIO_PORT);
                        }
                        return me;
                    }
                }
                throw new CallError("This computer's block is missing.");
            }
            case "devices": {
                // With an address, the devices behind another antenna; with none, this network's own.
                String address = string(args, 1);
                ServerLevel where = level;
                CableNetwork listed = network;
                String farIp = null;
                if (address != null) {
                    Matcher external = EXTERNAL_OR_IP.matcher(address.strip());
                    if (!external.matches()) {
                        throw new CallError("'" + address + "' isn't an external address. They look like 100.64.0.12.");
                    }
                    Far far = far(level, network, external.group(1), computerPos);
                    if (far.network() == null) {
                        throw new CallError("The antenna at " + external.group(1) + " isn't its network's router, so nothing behind it can be reached.");
                    }
                    where = far.level();
                    listed = far.network();
                    farIp = external.group(1);
                }
                JsonArray list = new JsonArray();
                for (NetworkDevice device : listed.devices()) {
                    if (listed != network) {
                        REMOTE.put(device, farIp);
                    }
                    list.add(describe(where, listed, device));
                }
                return list;
            }
            case "find": {
                // ["find", key, kind, external IP]: with an address, among the devices behind that antenna.
                String address = string(args, 3);
                String key = string(args, 1);
                if (address == null && key != null && key.indexOf('/') > 0) {
                    // A device behind another antenna, by its full address (a device object refreshing itself).
                    NetworkDevice device = resolve(network, key);
                    if (device == null) {
                        throw new CallError("The device " + key + " is no longer on its network.");
                    }
                    return describe(levelOf(device, level), network, device);
                }
                if (address == null) {
                    return describe(level, network, find(network, key, string(args, 2)));
                }
                String ip = address.strip();
                Far far = far(level, network, ip, computerPos);
                if (far.network() == null) {
                    throw new CallError("The antenna at " + ip + " isn't its network's router, so nothing behind it can be reached.");
                }
                NetworkDevice found = find(far.network(), string(args, 1), string(args, 2));
                if (far.network() != network) {
                    REMOTE.put(found, ip);
                }
                return describe(far.level(), far.network(), found);
            }
            case "chunk_loading": {
                // ["chunk_loading", on]: switches this network's antenna's Chunk Loader Upgrade on or off.
                AntennaBlockEntity antenna = router(network);
                antenna.setChunkLoading(args.get(1).getAsBoolean());
                return describe(level, network, antenna);
            }
            case "tunnel": {
                // ["tunnel", mac, other mac or null]: opens a tunnel between two buses, or closes this one's.
                CablePort end = port(byMac(network, args, 1)).base();
                ServerLevel endLevel = levelOf(end, level);
                String other = string(args, 2);
                if (other == null) {
                    Tunnels.unlink(endLevel, end);
                    return describe(endLevel, network, end);
                }
                NetworkDevice found = resolve(network, other);
                if (found == null) {
                    throw new CallError("The device " + other + " is no longer on its network.");
                }
                CablePort farEnd = port(found).base();
                String problem = Tunnels.link(endLevel, end, levelOf(farEnd, level), farEnd);
                if (problem != null) {
                    throw new CallError("Can't open a tunnel: " + problem);
                }
                return describe(endLevel, network, end);
            }
            case "rename": {
                NetworkDevice device = byMac(network, args, 1);
                String wanted = args.get(2).getAsString();
                String name = Addresses.cleanName(wanted);
                if (name.isEmpty() && !wanted.isBlank()) {
                    throw new CallError("A name can use letters, digits, _ - and . (up to " + Addresses.MAX_NAME_LENGTH + " characters).");
                }
                if (!name.equals(wanted.strip())) {
                    throw new CallError("A name can use letters, digits, _ - and . only (no spaces; up to " + Addresses.MAX_NAME_LENGTH
                            + " characters), so '" + wanted + "' can't be one. Try '" + name + "'. Capitals count: Chest and chest are two names.");
                }
                device.setDeviceName(name);
                return describe(level, network, device);
            }
            case "contents": {
                NetworkDevice device = byMac(network, args, 1);
                if (device.medium() == Medium.FLUID) {
                    return tanks(fluidSource(level, device, string(args, 2)));
                }
                if (device.medium() == Medium.ENERGY) {
                    throw new CallError("Energy has no contents to list. Ask how much is stored instead.");
                }
                // ["contents", mac, face, details]: with details, each stack's data and an exact filter for it.
                boolean details = args.size() > 3 && !args.get(3).isJsonNull() && args.get(3).getAsBoolean();
                ServerLevel where = levelOf(device, level);
                if (ownItems(device) instanceof ItemPort own) {
                    return contents(own, details ? where : null);
                }
                if (device instanceof CablePort port && port.gives()) {
                    // With no face named, a bus reads the whole container, not only what one face shows
                    // (looking is of everything; moving, with no face named, goes through the bus's own face).
                    String faceWord = string(args, 2);
                    return contents(faceWord == null ? wholeTarget(where, port) : target(where, port, faceWord), details ? where : null);
                }
                throw new CallError("Only an interface or a bus can list contents; this is " + article(device) + ".");
            }
            case "inspect":
                return inspect(level, network, computerPos, args);
            case "slots": {
                // ["slots", mac, face]: every slot, empty ones too, with whether anything may be put in it.
                NetworkDevice device = byMac(network, args, 1);
                ServerLevel where = levelOf(device, level);
                if (ownItems(device) instanceof ItemPort own) {
                    return slotList(own);
                }
                if (device instanceof CablePort port && port.gives() && port.medium() != Medium.FLUID && port.medium() != Medium.ENERGY) {
                    String faceWord = string(args, 2);
                    return slotList(faceWord == null ? wholeTarget(where, port) : target(where, port, faceWord));
                }
                throw new CallError("Only an interface or a bus has slots to list; this is " + article(device) + ".");
            }
            case "energy": {
                // How much energy an energy interface holds, or the machine an energy bus points at.
                EnergyPort store = energySource(level, byMac(network, args, 1), string(args, 2));
                JsonObject held = new JsonObject();
                held.addProperty("stored", store.stored());
                held.addProperty("capacity", store.capacity());
                return held;
            }
            case "count": {
                // How much a device (or its target) holds of whatever passes a name or filter, narrowed by face and slot.
                NetworkDevice device = byMac(network, args, 1);
                String wanted = string(args, 2);
                int slot = slot(args, 4);
                long total = 0;
                if (device.medium() == Medium.FLUID) {
                    NameMatch<Fluid> match = wanted == null ? null : fluid(wanted);
                    List<FluidPort.Tank> tanks = fluidSource(level, device, string(args, 3)).tanks();
                    for (int i = 0; i < tanks.size(); i++) {
                        FluidPort.Tank tank = tanks.get(i);
                        if (tank.amount() > 0 && (slot < 0 || slot == i) && (match == null || match.test(tank.fluid()))) {
                            total += tank.amount();
                        }
                    }
                    return new JsonPrimitive(total);
                }
                NameMatch<Item> match = wanted == null ? null : item(wanted);
                List<ItemStack> stacks;
                if (ownItems(device) instanceof ItemPort own) {
                    stacks = own.contents();
                } else if (device instanceof CablePort port && port.medium() == Medium.ITEM && port.gives()) {
                    ServerLevel where = levelOf(device, level); // a bus behind another antenna may be in another dimension
                    stacks = (string(args, 3) == null ? wholeTarget(where, port) : target(where, port, string(args, 3))).contents();
                } else {
                    throw new CallError("Only an interface or a bus can count what is there; this is " + article(device) + ".");
                }
                for (int i = 0; i < stacks.size(); i++) {
                    ItemStack stack = stacks.get(i);
                    if (!stack.isEmpty() && (slot < 0 || slot == i) && (match == null || match.testStack(stack))) {
                        total += stack.getCount();
                    }
                }
                return new JsonPrimitive(total);
            }
            case "filters": {
                if (byMac(network, args, 1) instanceof FluidInterfaceBlockEntity tanks) {
                    // The fluid each tank is kept for, or null where it takes any.
                    JsonArray kept = new JsonArray();
                    for (int tank = 0; tank < FluidInterfaceBlockEntity.TANKS; tank++) {
                        Fluid filter = tanks.filter(tank);
                        kept.add(filter == net.minecraft.world.level.material.Fluids.EMPTY ? JsonNull.INSTANCE
                                : new JsonPrimitive(BuiltInRegistries.FLUID.getKey(filter).toString()));
                    }
                    return kept;
                }
                InterfaceBlockEntity input = input(byMac(network, args, 1));
                JsonArray list = new JsonArray();
                for (int slot = 0; slot < InterfaceBlockEntity.SLOTS; slot++) {
                    String filter = input.filterText(slot);
                    list.add(filter == null ? JsonNull.INSTANCE : new JsonPrimitive(filter));
                }
                return list;
            }
            case "set_filter": {
                if (byMac(network, args, 1) instanceof FluidInterfaceBlockEntity tanks) {
                    int tank = args.get(2).getAsInt();
                    if (tank < 0 || tank >= FluidInterfaceBlockEntity.TANKS) {
                        throw new CallError("A fluid interface has tanks 0 to " + (FluidInterfaceBlockEntity.TANKS - 1) + "; there is no tank " + tank + ".");
                    }
                    String name = string(args, 3);
                    Fluid kept = null;
                    if (name != null) {
                        kept = fluid(name).single();
                        if (kept == null) {
                            throw new CallError("A tank is kept for one fluid; '" + name + "' names more than one. Name just one, such as \"water\".");
                        }
                    }
                    tanks.setFilter(tank, kept);
                    return JsonNull.INSTANCE;
                }
                InterfaceBlockEntity input = input(byMac(network, args, 1));
                int slot = args.get(2).getAsInt();
                if (slot < 0 || slot >= InterfaceBlockEntity.SLOTS) {
                    throw new CallError("This interface has slots 0 to " + (InterfaceBlockEntity.SLOTS - 1) + "; there is no slot " + slot + ".");
                }
                String item = string(args, 3);
                input.setFilter(slot, item == null ? null : item(item));
                return JsonNull.INSTANCE;
            }
            case "set_bus_filter": {
                // ["set_bus_filter", mac, [names] or null, matching]: what an item bus lets into its container.
                CablePort port = port(byMac(network, args, 1));
                if (port.kind() != Kind.BUS || (port.base().medium() != Medium.ITEM && port.base().medium() != Medium.UNIVERSAL)) {
                    throw new CallError("Only an Item Serial Bus or a USB keeps an item filter.");
                }
                List<String> names = new ArrayList<>();
                if (args.size() > 2 && args.get(2).isJsonArray()) {
                    for (JsonElement name : args.get(2).getAsJsonArray()) {
                        String word = name.getAsString().strip();
                        item(word); // says now if it isn't a name or filter
                        names.add(word);
                    }
                }
                if (names.size() > BusFilter.MOST_NAMES) {
                    throw new CallError("A bus keeps at most " + BusFilter.MOST_NAMES + " names or filters.");
                }
                // Matching: false, true (the same item), or "exact" (identical, data and all).
                JsonElement how = args.size() > 3 ? args.get(3) : JsonNull.INSTANCE;
                boolean exact = how.isJsonPrimitive() && how.getAsJsonPrimitive().isString() && how.getAsString().equals("exact");
                if (how.isJsonPrimitive() && how.getAsJsonPrimitive().isString() && !exact) {
                    throw new CallError("matching= is True, False or \"exact\", not '" + how.getAsString() + "'.");
                }
                boolean matching = exact || (how.isJsonPrimitive() && how.getAsJsonPrimitive().isBoolean() && how.getAsBoolean());
                port.setFilter(names.isEmpty() && !matching ? null : new BusFilter(names, matching, exact));
                return describe(level, network, port);
            }
            case "set_face": {
                CablePort port = port(byMac(network, args, 1));
                port.setAccessFace(face(args.get(2).getAsString()));
                return describe(level, network, port);
            }
            case "radio":
                return describe(level, network, router(network));
            case "tune": {
                AntennaBlockEntity antenna = router(network);
                if (!antenna.tune(args.get(1).getAsString())) {
                    throw new CallError("A frequency can use letters, digits, _ - and . (up to " + Addresses.MAX_NAME_LENGTH + " characters).");
                }
                return describe(level, network, antenna);
            }
            case "ssh_open":
            case "ssh_reach": {
                // Gate one, the network: can that computer be reached at all (cables, or antennas that let this owner in)?
                String address = args.get(1).getAsString();
                NetworkDevice found;
                String from;
                Matcher external = EXTERNAL.matcher(address);
                if (external.matches()) {
                    Far far = far(level, network, external.group(1), computerPos);
                    int port = Integer.parseInt(external.group(2));
                    found = far.network() != null ? far.network().byHost(port - RadioNetwork.PORT_BASE) : null;
                    if (found == null) {
                        throw new CallError("Nothing is on port " + port + " of " + external.group(1) + ".");
                    }
                    from = network.gateway().externalIp();
                } else {
                    found = find(network, address, null);
                    from = CableNetwork.at(level, computerPos).devices().stream()
                            .filter(device -> device.getBlockPos().equals(computerPos)).map(NetworkDevice::ip).findFirst().orElse("this network");
                }
                if (found instanceof qbotics.block.ComputerBlockEntity remote && remote.getLevel() == level && remote.getBlockPos().equals(computerPos)) {
                    throw new CallError("That is this computer.");
                }
                return sshSession(level.getServer(), method, found, address, from, ownerAt(level, computerPos), string(args, 2));
            }
            case "access": {
                // Who may reach this network from outside: private, team or public.
                AntennaBlockEntity antenna = router(network);
                RadioNetwork.Access access = RadioNetwork.Access.parse(args.get(1).getAsString());
                if (access == null) {
                    throw new CallError("'" + args.get(1).getAsString() + "' isn't an access setting. Use private, team or public.");
                }
                antenna.setAccess(access);
                return describe(level, network, antenna);
            }
            case "send":
                return new JsonPrimitive(send(level, network, computerPos, args.get(1).getAsString(), args.get(2).getAsString()));
            case "move":
                return moveAll(level, network, args);
            case "available": {
                // How many of a name or filter a list of item sources could give between them.
                List<End> sources = ends(network, args.get(1), "source");
                String wanted = string(args, 2);
                NameMatch<Item> match = wanted == null ? null : item(wanted);
                long total = 0;
                for (End end : sources) {
                    ItemPort port = itemSource(level, end);
                    java.util.function.Predicate<ItemStack> own = itemFilter(end);
                    List<ItemStack> stacks = port.contents();
                    for (int i = 0; i < stacks.size(); i++) {
                        ItemStack stack = stacks.get(i);
                        if (!stack.isEmpty() && (end.slot() < 0 || end.slot() == i) && (match == null || match.testStack(stack))
                                && own.test(stack)) {
                            total += stack.getCount();
                        }
                    }
                }
                return new JsonPrimitive(total);
            }
            case "stock": {
                // ["stock", sources, name or filter or null, details]: what item sources hold between them, by
                // item (most first) and by container, with each container's slots and empty slots. With
                // details, stacks differing in data (two potions) are counted apart, each with its details.
                List<End> sources = ends(network, args.get(1), "source");
                String wanted = string(args, 2);
                boolean details = args.size() > 3 && !args.get(3).isJsonNull() && args.get(3).getAsBoolean();
                NameMatch<Item> match = wanted == null ? null : item(wanted);
                java.util.Map<String, Long> byItem = new java.util.HashMap<>();
                java.util.Map<String, ItemStack> sample = new java.util.HashMap<>();
                JsonArray members = new JsonArray();
                for (End end : sources) {
                    java.util.function.Predicate<ItemStack> own = itemFilter(end);
                    ItemPort port = itemSource(level, end);
                    List<StoragePort.Member> parts = port instanceof StoragePort group ? group.members()
                            : List.of(new StoragePort.Member(end.device(), port, java.util.Set.of()));
                    for (StoragePort.Member member : parts) {
                        List<ItemStack> stacks = member.port.contents();
                        BusFilter filter = member.device instanceof CablePort plate ? plate.filter() : null;
                        long count = 0;
                        int free = 0;
                        long strays = 0;
                        for (ItemStack stack : stacks) {
                            if (stack.isEmpty()) {
                                free++;
                                continue;
                            }
                            if ((match == null || match.testStack(stack)) && own.test(stack)) {
                                count += stack.getCount();
                                String kind = details ? ItemData.exactFilter(stack, level.registryAccess()) : itemId(stack.getItem());
                                byItem.merge(kind, (long) stack.getCount(), Long::sum);
                                sample.putIfAbsent(kind, stack);
                            }
                            if (filter != null && !filter.matching() && !filter.named(stack)) {
                                strays += stack.getCount(); // put there by hand, say: its filter doesn't let it in
                            }
                        }
                        JsonObject entry = new JsonObject();
                        if (filter != null) {
                            entry.add("filter", filterJson(filter));
                        }
                        entry.addProperty("strays", strays);
                        entry.add("device", describe(levelOf(member.device, level), network, member.device));
                        entry.addProperty("count", count);
                        entry.addProperty("slots", stacks.size());
                        entry.addProperty("free", free);
                        members.add(entry);
                    }
                }
                List<java.util.Map.Entry<String, Long>> sorted = new ArrayList<>(byItem.entrySet());
                sorted.sort(java.util.Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(java.util.Map.Entry.comparingByKey()));
                JsonArray items = new JsonArray();
                for (java.util.Map.Entry<String, Long> entry : sorted) {
                    JsonObject one = new JsonObject();
                    ItemStack stack = sample.get(entry.getKey());
                    one.addProperty("item", itemId(stack.getItem()));
                    one.addProperty("count", entry.getValue());
                    if (details) {
                        addDetails(one, stack, level);
                    }
                    items.add(one);
                }
                JsonObject answer = new JsonObject();
                answer.add("items", items);
                answer.add("members", members);
                return answer;
            }
            case "screen_open": {
                CablePort bus = screenBus(byMac(network, args, 1));
                if (bus.owner() == null) {
                    throw new CallError("This bus has no owner to act for (it wasn't placed by a player), so it can't open screens.");
                }
                ScreenSessions.Session session = ScreenSessions.open(level, bus, bus.owner());
                if (session == null) {
                    String block = BuiltInRegistries.BLOCK.getKey(level.getBlockState(bus.targetPos()).getBlock()).toString();
                    throw new CallError("The bus points at " + block + ", which opened no screen. It may have none, or its owner may not be allowed to use it here.");
                }
                return screenContents(session, true);
            }
            case "screen_contents":
                return screenContents(openScreen(level, screenBus(byMac(network, args, 1))), false);
            case "screen_slots": {
                // Every slot of the screen's own, empty ones too, with whether anything may be put in it.
                ScreenSessions.Session session = openScreen(level, screenBus(byMac(network, args, 1)));
                ItemPort port = session.port();
                JsonArray list = new JsonArray();
                for (net.minecraft.world.inventory.Slot slot : session.slots()) {
                    list.add(slotEntry(slot.index, slot.getItem(), port.acceptsAny(slot.index)));
                }
                return list;
            }
            case "screen_button": {
                // A button on the screen, as a player clicking it: a stonecutter's or loom's choices.
                ScreenSessions.Session session = openScreen(level, screenBus(byMac(network, args, 1)));
                if (args.size() < 3 || !args.get(2).isJsonPrimitive() || !args.get(2).getAsJsonPrimitive().isNumber()) {
                    throw new CallError("press() needs a button number: 0 for the first.");
                }
                int button = args.get(2).getAsInt();
                if (button < 0) {
                    throw new CallError("Buttons are numbered from 0; there is no number " + button + ".");
                }
                session.port(); // counts as using the screen
                session.payers = payers(level, network, args.size() > 3 ? args.get(3) : null);
                // The enchanting table's offers ask the player to have a level, and charge less than that.
                int levels = session.menu instanceof net.minecraft.world.inventory.EnchantmentMenu table && button < 3 ? table.costs[button] : 0;
                String asks = "Enchanting with button " + button + " asks for level " + levels + " (and uses " + (button + 1) + ")";
                boolean took;
                try {
                    took = ScreenSessions.paying(session, levels, asks, () -> session.menu.clickMenuButton(session.player, button));
                } catch (CallError e) {
                    throw e;
                } catch (RuntimeException e) {
                    throw new CallError("The screen failed when button " + button + " was pressed.");
                }
                if (took) {
                    session.menu.broadcastChanges();
                }
                return new JsonPrimitive(took);
            }
            case "events_start": {
                // A program starts handling events: earlier sign-ups are dropped; messages may come as events.
                qbotics.computer.ServerComputer computer = runningAt(level, computerPos);
                boolean messages = args.size() > 1 && args.get(1).getAsBoolean();
                return new JsonPrimitive(computer.startEvents(messages));
            }
            case "pin_listen": {
                // ["pin_listen", mac, session]: this computer wants the pin's events pushed to it.
                NetworkDevice device = byMac(network, args, 1);
                if (!(device instanceof CablePort pin) || pin.kind() != Kind.PIN) {
                    throw new CallError("Only a logic pin sends pin events; this is " + article(device) + ".");
                }
                qbotics.computer.ServerComputer computer = runningAt(level, computerPos);
                pin.listen(computer.deviceId(), args.get(2).getAsInt());
                qbotics.block.WorldPlates.sense(level, pin); // starts from what is there now
                return JsonNull.INSTANCE;
            }
            case "tablet_find": {
                // ["tablet_find", key]: a tablet paired with this network, by address, name or the player carrying it.
                java.util.UUID tabletKey = tabletKey(level, network, computerPos);
                qbotics.display.Tablets tablets = qbotics.display.Tablets.get(level.getServer());
                try {
                    return tablets.describe(tablets.find(tabletKey, string(args, 1)), tabletKey);
                } catch (IllegalArgumentException e) {
                    throw new CallError(e.getMessage());
                }
            }
            case "tablets": {
                // ["tablets"]: every tablet paired with this network (or, on a Qbot, with the Qbot).
                java.util.UUID tabletKey = tabletKeyOrNull(level, network, computerPos);
                JsonArray list = new JsonArray();
                if (tabletKey != null) {
                    qbotics.display.Tablets tablets = qbotics.display.Tablets.get(level.getServer());
                    tablets.pairedWith(tabletKey).forEach(tablet -> list.add(tablets.describe(tablet, tabletKey)));
                }
                return list;
            }
            case "tablet_title": {
                // ["tablet_title", mac, title]: names this network's tab on the tablet.
                qbotics.display.Tablets.App app = tabletApp(level, network, args);
                qbotics.display.Tablets.get(level.getServer()).setTitle(app, string(args, 2) == null ? "" : string(args, 2));
                return JsonNull.INSTANCE;
            }
            case "display_open": {
                // ["display_open", mac]: a handle for drawing on a monitor or panel (see Displays.drawFast).
                // ["display_open", mac, kind]: on a tablet, its screen for this network, made that kind.
                if (args.size() > 2) {
                    qbotics.display.Tablets.App app = tabletApp(level, network, args);
                    qbotics.display.ScreenContent content;
                    try {
                        content = qbotics.display.Tablets.get(level.getServer()).use(app, string(args, 2));
                    } catch (IllegalArgumentException e) {
                        throw new CallError(e.getMessage());
                    }
                    qbotics.computer.ServerComputer computer = runningAt(level, computerPos);
                    JsonObject opened = new JsonObject();
                    opened.addProperty("width", qbotics.display.Tablets.WIDTH);
                    opened.addProperty("height", qbotics.display.Tablets.HEIGHT);
                    opened.addProperty("handle", qbotics.display.Displays.openTablet(level, computerPos, computer.deviceId(), content));
                    return opened;
                }
                NetworkDevice device = byMac(network, args, 1);
                if (!(device instanceof qbotics.block.DisplayBlockEntity display) || display.content() == null) {
                    throw new CallError("Only a text monitor, a control panel or a web panel can be drawn on; this is " + article(device) + ".");
                }
                if (REMOTE.containsKey(display)) {
                    throw new CallError("A display can only be drawn on from a computer on its own network.");
                }
                String refused = qbotics.display.Displays.overLimit(level, display);
                if (refused != null) {
                    throw new CallError(refused);
                }
                qbotics.computer.ServerComputer computer = runningAt(level, computerPos);
                JsonObject opened = display.describe();
                opened.addProperty("handle", qbotics.display.Displays.open(level, computerPos, computer.deviceId(), display));
                return opened;
            }
            case "display_listen": {
                // ["display_listen", mac, session]: this computer wants the display's touches pushed to it.
                java.util.UUID tabletKey = tabletKeyOrNull(level, network, computerPos);
                qbotics.display.Tablets.App tabletApp = tabletKey == null || args.get(1).isJsonNull() ? null
                        : qbotics.display.Tablets.get(level.getServer()).app(tabletKey, args.get(1).getAsString());
                if (tabletApp != null) {
                    tabletApp.content().listeners().put(runningAt(level, computerPos).deviceId(), args.get(2).getAsInt());
                    return JsonNull.INSTANCE;
                }
                NetworkDevice device = byMac(network, args, 1);
                if (!(device instanceof qbotics.block.DisplayBlockEntity display)) {
                    throw new CallError("Only a text monitor, a control panel or a web panel is touched; this is " + article(device) + ".");
                }
                qbotics.computer.ServerComputer computer = runningAt(level, computerPos);
                qbotics.display.Displays.listen(display, computer.deviceId(), args.get(2).getAsInt());
                return JsonNull.INSTANCE;
            }
            case "program": {
                NetworkDevice device = byMac(network, args, 1);
                if (!(device instanceof qbotics.block.ProgrammerBlockEntity programmer)) {
                    throw new CallError("Only a programmer programs; this is " + article(device) + ".");
                }
                String choice = args.size() > 2 && !args.get(2).isJsonNull() ? args.get(2).getAsString() : "*";
                qbotics.block.Programmers.Result result = qbotics.block.Programmers.program(level, programmer, choice);
                if (!result.ok()) {
                    throw new CallError(result.message());
                }
                return new JsonPrimitive(result.message());
            }
            case "repo_list", "repo_tree", "repo_read":
                return Repositories.call(method, level, network, computerPos, args);
            case "watch_open":
                return Watches.open(level, network, computerPos, args);
            case "watch_poll":
                return Watches.poll(level, network, computerPos, args);
            case "watch_close":
                return Watches.close(args);
            case "env":
                // ["env"]: the time, weather, biome and light where this computer is (a Qbot: where it stands now).
                return Environment.read(level, computerPos);
            case "area": {
                // ["area", mac, radius or null, height or null]: an Entity Sensor's or a Collector's box; sets what is given.
                if (!(byMac(network, args, 1) instanceof qbotics.block.AreaBlockEntity device)) {
                    throw new CallError("Only an Entity Sensor or a Collector has an area.");
                }
                Integer radius = wholeNumber(args, 2, "radius");
                Integer height = wholeNumber(args, 3, "height");
                for (Integer given : new Integer[] {radius, height}) {
                    if (given != null && given > qbotics.block.AreaBlockEntity.MOST) {
                        throw new CallError("An area reaches from 1 to " + qbotics.block.AreaBlockEntity.MOST + " blocks each way, not " + given + ".");
                    }
                }
                if (radius != null || height != null) {
                    device.setArea(radius != null ? radius : device.radius(), height != null ? height : device.height());
                }
                return describe(level, network, device);
            }
            case "bsensor_box", "bsensor_blocks", "bsensor_block", "bsensor_break", "bsensor_place", "bsensor_use", "bsensor_sign":
                return BlockSensorCalls.call(method, level, network, args);
            case "sensor_entities", "sensor_entity", "sensor_contents", "sensor_slots", "sensor_use", "sensor_attack", "sensor_offers", "sensor_trade", "sensor_collect":
                return EntitySensorCalls.call(method, level, network, args);
            case "player_info", "chat_say", "chat_tell", "chat_command", "chat_listen", "command_run":
                return GameInterfaceCalls.call(method, level, network, computerPos, args);
            case "look", "pin", "pin_set", "place", "pour", "break", "drop", "dropper_set", "drop_xp":
                return WorldPlateCalls.call(method, level, network, args);
            case "screen_cost": {
                // What the screen asks in levels now: the anvil one number, the enchanting table one per offer.
                ScreenSessions.Session session = openScreen(level, screenBus(byMac(network, args, 1)));
                session.port();
                if (session.menu instanceof net.minecraft.world.inventory.AnvilMenu anvil) {
                    return new JsonPrimitive(anvil.getCost());
                }
                if (session.menu instanceof net.minecraft.world.inventory.EnchantmentMenu table) {
                    JsonArray costs = new JsonArray();
                    for (int cost : table.costs) {
                        costs.add(cost);
                    }
                    return costs;
                }
                return JsonNull.INSTANCE;
            }
            case "screen_close": {
                CablePort bus = screenBus(byMac(network, args, 1));
                // What the block hands back goes to the places named, or failing that to any interface on the network.
                List<ItemPort> places = new ArrayList<>();
                if (args.size() > 2 && args.get(2).isJsonArray() && !args.get(2).getAsJsonArray().isEmpty()) {
                    for (End end : ends(network, args.get(2), "destination")) {
                        places.add(itemDestination(level, end));
                    }
                }
                places.addAll(interfaces(network));
                return new JsonPrimitive(ScreenSessions.close(level.getServer(), bus.deviceId(), places));
            }
            default:
                throw new CallError("This computer doesn't know the call '" + method + "'.");
        }
    }

    // ---- moving: from sources to destinations --------------------------------------------

    private static final int MAX_ENDS = 32;
    private static final int MAX_END_FILTERS = 32;

    /**
     * One end of a move, as the program described it: a device, and optionally a face and a
     * slot (or tank) to work through, and lists of what it will and won't pass.
     */
    /**
     * One end of a move. For a storage group (logistics.Storage), group lists its members and
     * device is the first of them, as an item device.
     */
    record End(NetworkDevice device, String face, int slot, JsonObject written, @org.jspecify.annotations.Nullable List<NetworkDevice> group) {
        String label() {
            if (group != null) {
                return "the storage";
            }
            if (written.has("entity")) {
                return "the entity";
            }
            return device.deviceName().isEmpty() ? device.ip() : device.deviceName();
        }
    }

    static List<End> ends(CableNetwork network, JsonElement written, String what) {
        JsonArray list = written.getAsJsonArray();
        if (list.isEmpty()) {
            throw new CallError("A move needs at least one " + what + ".");
        }
        if (list.size() > MAX_ENDS) {
            throw new CallError("A move can name at most " + MAX_ENDS + " " + what + "s.");
        }
        List<End> ends = new ArrayList<>();
        for (JsonElement element : list) {
            JsonObject spec = element.getAsJsonObject();
            if (spec.has("group")) {
                List<NetworkDevice> members = storageMembers(network, spec);
                ends.add(new End(members.getFirst(), null, -1, spec, members));
                continue;
            }
            String mac = spec.get("mac").getAsString();
            NetworkDevice device = resolve(network, mac);
            if (device == null) {
                throw new CallError("The device " + mac + " is no longer on this network.");
            }
            String face = spec.has("face") && !spec.get("face").isJsonNull() ? spec.get("face").getAsString() : null;
            int slot = spec.has("slot") && !spec.get("slot").isJsonNull() ? spec.get("slot").getAsInt() : -1;
            if (slot < -1) {
                throw new CallError("Slots and tanks are numbered from 0; there is no number " + slot + ".");
            }
            ends.add(new End(device, face, slot, spec, null));
        }
        return ends;
    }

    private static <T> List<NameMatch<T>> names(JsonObject spec, String key, java.util.function.Function<String, NameMatch<T>> parse) {
        List<NameMatch<T>> names = new ArrayList<>();
        if (spec.has(key) && spec.get(key).isJsonArray()) {
            JsonArray written = spec.getAsJsonArray(key);
            if (written.size() > MAX_END_FILTERS) {
                throw new CallError("A source or destination can list at most " + MAX_END_FILTERS + " names or filters.");
            }
            for (JsonElement name : written) {
                names.add(parse.apply(name.getAsString()));
            }
        }
        return names;
    }

    /** What one end lets through: anything in its "only" list (or anything, if that is empty) that is not in its "without" list. */
    /** The most containers one storage may have. */
    private static final int MAX_STORAGE_MEMBERS = 256;

    /**
     * The members of a storage group, found again on every call so that a container named to
     * match joins at once: {"group": [names, patterns ("chest*"), addresses], "address": external
     * IP or null}. Each name or address is every item device it finds (all of them, where
     * several share a name); a pattern is every device whose name matches, in name order
     * (chest2 before chest10),
     * that holds items (devices of other kinds are passed over). A USB takes part as its item bus.
     */
    static List<NetworkDevice> storageMembers(CableNetwork network, JsonObject spec) {
        String address = spec.has("address") && !spec.get("address").isJsonNull() ? spec.get("address").getAsString().strip() : null;
        CableNetwork where = network;
        if (address != null) {
            if (callLevel == null) {
                throw new CallError("Devices behind another antenna can only be reached from a program.");
            }
            Far far = far(callLevel, network, address, callPos);
            if (far.network() == null) {
                throw new CallError("The antenna at " + address + " isn't its network's router, so nothing behind it can be reached.");
            }
            where = far.network();
        }
        List<NetworkDevice> members = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        JsonArray keys = spec.getAsJsonArray("group");
        if (keys.isEmpty()) {
            throw new CallError("A storage needs at least one name or device: net.storage(\"chest*\").");
        }
        for (JsonElement written : keys) {
            String key = written.getAsString();
            List<NetworkDevice> found = new ArrayList<>();
            boolean pattern = key.indexOf('*') >= 0 || key.indexOf('?') >= 0;
            if (pattern) {
                Pattern names = glob(key);
                for (NetworkDevice device : where.devices()) {
                    if (!device.deviceName().isEmpty() && names.matcher(device.deviceName()).matches()) {
                        found.add(device);
                    }
                }
                found.sort(java.util.Comparator.comparing(NetworkDevice::deviceName, NetworkCalls::byName)
                        .thenComparingInt(NetworkDevice::hostNumber));
            } else if (address == null && key.indexOf('/') > 0) {
                NetworkDevice device = resolve(network, key); // one behind another antenna, by its full address
                if (device != null) {
                    found.add(device);
                }
            } else {
                found.addAll(where.find(key));
                if (found.isEmpty()) {
                    throw new CallError("The storage names '" + key + "', but nothing on " + (address == null ? "this network" : "the network behind " + address)
                            + " has that name or address.");
                }
            }
            for (NetworkDevice device : found) {
                if (where != network) {
                    device = resolve(network, address + "/" + device.mac()); // reached through both antennas, as any remote device is
                    if (device == null) {
                        continue;
                    }
                }
                NetworkDevice items = concrete(device, Medium.ITEM);
                if (!holdsItems(items)) {
                    if (pattern) {
                        continue;
                    }
                    throw new CallError("The storage names '" + key + "', which is " + article(device) + ": only interfaces and item buses hold items.");
                }
                if (!seen.add(items.mac())) {
                    continue; // named twice (by a name and a pattern, say): once is enough
                }
                if (members.size() >= MAX_STORAGE_MEMBERS) {
                    throw new CallError("A storage can have at most " + MAX_STORAGE_MEMBERS + " containers.");
                }
                members.add(items);
            }
        }
        if (members.isEmpty()) {
            throw new CallError("The storage is empty: no interface or item bus " + (address == null ? "on this network" : "behind " + address)
                    + " has a name matching " + keys + ".");
        }
        return members;
    }

    /** True for a device that holds items a storage can use: an interface, a player interface, an item bus. */
    private static boolean holdsItems(NetworkDevice device) {
        if (device instanceof qbotics.block.GameInterfaceBlockEntity game && game.kind() == Kind.PLAYER) {
            return true; // also while its player is away: it is left out then, not an error
        }
        return ownItems(device) != null || (device instanceof CablePort port && port.medium() == Medium.ITEM && port.gives() && port.takes());
    }

    /** Names in the order a person would put them: runs of digits compare as numbers, so chest2 comes before chest10. */
    private static int byName(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            char x = a.charAt(i);
            char y = b.charAt(j);
            if (Character.isDigit(x) && Character.isDigit(y)) {
                int si = i;
                int sj = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) {
                    i++;
                }
                while (j < b.length() && Character.isDigit(b.charAt(j))) {
                    j++;
                }
                java.math.BigInteger m = new java.math.BigInteger(a.substring(si, i));
                java.math.BigInteger n = new java.math.BigInteger(b.substring(sj, j));
                if (m.compareTo(n) != 0) {
                    return m.compareTo(n);
                }
            } else {
                if (x != y) {
                    return Character.compare(x, y);
                }
                i++;
                j++;
            }
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }

    /** A device name pattern: * is any run of characters, ? any one. */
    private static Pattern glob(String written) {
        StringBuilder regex = new StringBuilder();
        StringBuilder literal = new StringBuilder();
        for (char c : written.toCharArray()) {
            if (c == '*' || c == '?') {
                if (!literal.isEmpty()) {
                    regex.append(Pattern.quote(literal.toString()));
                    literal.setLength(0);
                }
                regex.append(c == '*' ? ".*" : ".");
            } else {
                literal.append(c);
            }
        }
        if (!literal.isEmpty()) {
            regex.append(Pattern.quote(literal.toString()));
        }
        return Pattern.compile(regex.toString());
    }

    /**
     * A storage group as one container. A member whose container can't be reached now (its
     * area unloaded, the chest gone) is left out; so is one reaching a container an earlier
     * member already reaches (two buses on one chest, or on the two halves of a double chest).
     */
    static StoragePort storage(ServerLevel level, List<NetworkDevice> group) {
        List<StoragePort.Member> members = new ArrayList<>();
        java.util.Set<String> taken = new java.util.HashSet<>();
        for (NetworkDevice device : group) {
            ServerLevel at = levelOf(device, level);
            ItemPort port;
            try {
                port = ownItems(device);
            } catch (CallError unreachable) {
                continue; // a Player Interface whose player is away: left out, as any member that can't be reached
            }
            if (port == null && device instanceof CablePort plate) {
                try {
                    port = wholeTarget(at, plate);
                } catch (CallError unreachable) {
                    port = null;
                }
            }
            if (port == null) {
                continue;
            }
            java.util.Set<String> places = places(at, device);
            boolean again = false;
            for (String place : places) {
                again |= taken.contains(place);
            }
            if (again) {
                continue;
            }
            taken.addAll(places);
            members.add(new StoragePort.Member(device, port, places));
        }
        return new StoragePort(members);
    }

    /**
     * Where a device's items are, as words that are equal for the same container: an
     * interface's own block, a player interface's player, the block a bus points at (both
     * halves of a double chest).
     */
    static java.util.Set<String> places(ServerLevel level, NetworkDevice device) {
        ServerLevel at = levelOf(device, level);
        String dimension = at.dimension().toString();
        if (device instanceof qbotics.block.GameInterfaceBlockEntity game && game.kind() == Kind.PLAYER) {
            return java.util.Set.of("player " + game.owner());
        }
        if (device instanceof CablePort plate) {
            BlockPos target = plate.targetPos();
            net.minecraft.world.level.block.state.BlockState state = at.isLoaded(target) ? at.getBlockState(target) : null;
            if (state != null && state.getBlock() instanceof net.minecraft.world.level.block.ChestBlock
                    && state.getValue(net.minecraft.world.level.block.ChestBlock.TYPE) != net.minecraft.world.level.block.state.properties.ChestType.SINGLE) {
                BlockPos other = target.relative(net.minecraft.world.level.block.ChestBlock.getConnectedDirection(state));
                return java.util.Set.of(dimension + " " + target.asLong(), dimension + " " + other.asLong());
            }
            return java.util.Set.of(dimension + " " + target.asLong());
        }
        return java.util.Set.of(dimension + " " + device.getBlockPos().asLong());
    }

    /** What one item end lets through, judged on each stack (its data and all). */
    static java.util.function.Predicate<ItemStack> itemFilter(End end) {
        List<NameMatch<Item>> only = names(end.written(), "only", NetworkCalls::item);
        List<NameMatch<Item>> without = names(end.written(), "without", NetworkCalls::item);
        return stack -> (only.isEmpty() || only.stream().anyMatch(filter -> filter.testStack(stack)))
                && without.stream().noneMatch(filter -> filter.testStack(stack));
    }

    static <T> java.util.function.Predicate<T> endFilter(End end, java.util.function.Function<String, NameMatch<T>> parse) {
        List<NameMatch<T>> only = names(end.written(), "only", parse);
        List<NameMatch<T>> without = names(end.written(), "without", parse);
        return thing -> (only.isEmpty() || only.stream().anyMatch(filter -> filter.test(thing)))
                && without.stream().noneMatch(filter -> filter.test(thing));
    }

    // ---- screen access (design doc §11.0.2) --------------------------------------------------

    private static CablePort screenBus(NetworkDevice device) {
        if (device instanceof CablePort port && port.kind() == Kind.BUS && port.medium() == Medium.ITEM) {
            return port;
        }
        throw new CallError("Only a bus can open the screen of the block it points at; this is " + article(device) + ".");
    }

    private static ScreenSessions.Session openScreen(ServerLevel level, CablePort bus) {
        level = levelOf(bus, level);
        ScreenSessions.Session session = ScreenSessions.of(level, bus);
        if (session == null) {
            throw new CallError("This bus has no screen open. Open one first, with open_screen(); a screen left unused for a minute closes itself.");
        }
        return session;
    }

    /** Fluids other mods mark as liquid experience. */
    private static final net.minecraft.tags.TagKey<Fluid> LIQUID_EXPERIENCE = net.minecraft.tags.TagKey.create(
            net.minecraft.core.registries.Registries.FLUID, net.minecraft.resources.Identifier.fromNamespaceAndPath("c", "experience"));
    /** Assumed for every liquid experience for now (design doc §11.0.4). */
    static final int MB_PER_POINT = 20;

    /**
     * Where a screen's level costs are paid from, as a program named them: Experience
     * Interfaces, and fluid stores or plates holding liquid experience. Null or empty: nowhere.
     */
    static List<ScreenSessions.Payer> payers(ServerLevel level, CableNetwork network, @org.jspecify.annotations.Nullable JsonElement written) {
        if (written == null || !written.isJsonArray() || written.getAsJsonArray().isEmpty()) {
            return List.of();
        }
        List<ScreenSessions.Payer> payers = new ArrayList<>();
        for (End end : asMedium(ends(network, written, "experience source"), Medium.FLUID)) {
            String label = end.label();
            if (end.device() instanceof qbotics.block.ExperienceInterfaceBlockEntity bank) {
                payers.add(new ScreenSessions.Payer() {
                    @Override
                    public long points() {
                        return bank.points();
                    }

                    @Override
                    public void take(long points) {
                        bank.take(points);
                    }

                    @Override
                    public String label() {
                        return label;
                    }
                });
            } else if (end.device().medium() == Medium.FLUID && !(end.device() instanceof CablePort plate && !plate.gives())) {
                FluidPort tanks = fluidSource(level, end.device(), end.face());
                payers.add(new ScreenSessions.Payer() {
                    // Only liquid experience counts, and it is only ever used up, never turned back into levels.
                    private List<Fluid> kinds() {
                        List<Fluid> kinds = new ArrayList<>();
                        for (FluidPort.Tank tank : tanks.tanks()) {
                            if (tank.fluid() != null && tank.amount() > 0 && tank.fluid().is(LIQUID_EXPERIENCE) && !kinds.contains(tank.fluid())) {
                                kinds.add(tank.fluid());
                            }
                        }
                        return kinds;
                    }

                    @Override
                    public long points() {
                        long millibuckets = 0;
                        for (Fluid kind : kinds()) {
                            millibuckets += tanks.extract(kind, Integer.MAX_VALUE, true);
                        }
                        return millibuckets / MB_PER_POINT;
                    }

                    @Override
                    public void take(long points) {
                        long left = points * MB_PER_POINT;
                        for (Fluid kind : kinds()) {
                            if (left <= 0) {
                                break;
                            }
                            left -= tanks.extract(kind, (int) Math.min(left, Integer.MAX_VALUE), false);
                        }
                    }

                    @Override
                    public String label() {
                        return label;
                    }
                });
            } else {
                throw new CallError("Levels are paid from an Experience Interface, or from liquid experience in a fluid interface or behind a"
                        + " fluid bus; " + label + " is " + article(end.device()) + ".");
            }
        }
        return payers;
    }

    /** Every interface on a network, as places for leftovers to go. */
    public static List<ItemPort> interfaces(CableNetwork network) {
        List<ItemPort> all = new ArrayList<>();
        for (NetworkDevice device : network.devices()) {
            if (device instanceof InterfaceBlockEntity store) {
                all.add(store);
            }
        }
        return all;
    }

    /** A screen's own slots: every one of them when it has just been opened, otherwise those that hold something. */
    private static JsonArray screenContents(ScreenSessions.Session session, boolean all) {
        JsonArray list = new JsonArray();
        session.port(); // counts as using the screen
        for (net.minecraft.world.inventory.Slot slot : session.slots()) {
            ItemStack stack = slot.getItem();
            if (all || !stack.isEmpty()) {
                JsonObject entry = new JsonObject();
                entry.addProperty("slot", slot.index);
                if (stack.isEmpty()) {
                    entry.add("item", JsonNull.INSTANCE);
                } else {
                    entry.addProperty("item", itemId(stack.getItem()));
                }
                entry.addProperty("count", stack.getCount());
                list.add(entry);
            }
        }
        return list;
    }

    /** True if an end of a move means "the screen this bus has open", not the container it points at. */
    private static boolean onScreen(End end) {
        return end.written().has("screen") && end.written().get("screen").getAsBoolean();
    }

    static ItemPort itemSource(ServerLevel level, End end) {
        if (end.group() != null) {
            return storage(level, end.group());
        }
        if (end.written().has("entity")) {
            return EntitySensorCalls.inventory(levelOf(end.device(), level), end);
        }
        level = levelOf(end.device(), level);
        if (onScreen(end)) {
            return openScreen(level, screenBus(end.device())).port();
        }
        if (ownItems(end.device()) instanceof ItemPort own) {
            return own;
        }
        if (end.device() instanceof CablePort plate && plate.medium() == Medium.ITEM && plate.gives()) {
            // With no face named, a source is what the bus's own face shows, as a destination is; a slot
            // by number is one of the whole container's.
            return end.slot() >= 0 && end.face() == null ? wholeTarget(level, plate) : target(level, plate, end.face());
        }
        throw notASource(end);
    }

    static ItemPort itemDestination(ServerLevel level, End end) {
        if (end.group() != null) {
            return storage(level, end.group());
        }
        if (end.written().has("entity")) {
            return EntitySensorCalls.inventory(levelOf(end.device(), level), end);
        }
        level = levelOf(end.device(), level);
        if (onScreen(end)) {
            return openScreen(level, screenBus(end.device())).port();
        }
        if (ownItems(end.device()) instanceof ItemPort own) {
            return own;
        }
        if (end.device() instanceof CablePort plate && plate.medium() == Medium.ITEM && plate.takes()) {
            // A slot number with no face counts slots across the whole container.
            return end.slot() >= 0 && end.face() == null ? wholeTarget(level, plate) : target(level, plate, end.face());
        }
        throw notADestination(end);
    }

    static Integer wholeNumber(JsonArray args, int index, String what) {
        if (index >= args.size() || args.get(index).isJsonNull()) {
            return null;
        }
        long number = args.get(index).getAsLong();
        if (number < 1) {
            throw new CallError("The " + what + " must be 1 or more, not " + number + ".");
        }
        return (int) Math.min(number, Integer.MAX_VALUE);
    }

    private static CallError notASource(End end) {
        return new CallError(end.label() + " is " + article(end.device()) + ", which can't be a source.");
    }

    private static CallError notADestination(End end) {
        return new CallError(end.label() + " is " + article(end.device()) + ", which can't be a destination.");
    }

    /** True if something in this registry passes the name or filter (and it can be read at all). */
    private static <T> boolean namesOnly(net.minecraft.core.Registry<T> registry, String text) {
        try {
            return NameMatch.parse(registry, text).first() != null;
        } catch (NameMatch.BadFilter e) {
            return false;
        }
    }

    private static List<End> concat(List<End> a, List<End> b) {
        List<End> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }

    /** The same ends, with any USB among them working as a bus for this medium. */
    static List<End> asMedium(List<End> ends, Medium medium) {
        List<End> out = new ArrayList<>(ends.size());
        for (End end : ends) {
            if (end.group() != null && medium != Medium.ITEM) {
                throw new CallError("A storage holds items only; it has no " + medium.word + ".");
            }
            out.add(new End(concrete(end.device(), medium), end.face(), end.slot(), end.written(), end.group()));
        }
        return out;
    }

    private interface Step {
        /** Moves (or, in a trial, counts) up to a limit from one source to one destination. */
        long run(int source, int destination, long limit, boolean simulate);
    }

    /**
     * Moves things from sources to destinations (design doc §11.0.5). Sources are drawn on in
     * the order given, and each fills the destinations in the order given, until the amount
     * asked for has moved. Nothing has to pass through an interface: one bus's chest can feed
     * another bus's furnace directly.
     *
     * <p>Arguments: the sources, the destinations (each a list of {"mac", "face", "slot",
     * "only": [...], "without": [...]}), a name or filter (or null), "count" (move up to this
     * many; or null), "need" (move exactly this many or nothing; or null).
     *
     * <p>The answer is {"moved": n}. With "need", if that many can't be moved now, nothing is
     * moved and the answer is {"moved": 0, "short": true, "could": how many}: the program then
     * waits and asks again, or reports it.
     */
    private static JsonObject moveAll(ServerLevel level, CableNetwork network, JsonArray args) {
        List<End> givenSources = ends(network, args.get(1), "source");
        List<End> givenDestinations = ends(network, args.get(2), "destination");
        String wanted = string(args, 3);
        Integer count = wholeNumber(args, 4, "count");
        Integer need = wholeNumber(args, 5, "amount needed");
        // The editor's Dry run: only tried, and said in words (engine.DryRun).
        boolean dry = args.size() > 6 && args.get(6).isJsonPrimitive() && args.get(6).getAsBoolean();
        // A USB carries whatever the other ends do. Between USBs only, the thing named decides
        // (a fluid's name moves fluid); with nothing named, items.
        Medium medium = null;
        for (End end : concat(givenSources, givenDestinations)) {
            // (A Qbot carries items, fluid and energy: the other ends say which this move is.)
            if (end.device().medium() != Medium.UNIVERSAL && !(end.device() instanceof qbotics.qbot.QbotBlockEntity)) {
                medium = end.device().medium();
                break;
            }
        }
        if (medium == null) {
            medium = wanted != null && namesOnly(BuiltInRegistries.FLUID, wanted) && !namesOnly(BuiltInRegistries.ITEM, wanted)
                    ? Medium.FLUID : Medium.ITEM;
        }
        List<End> sources = asMedium(givenSources, medium);
        List<End> destinations = asMedium(givenDestinations, medium);
        for (End end : sources) {
            if ((end.device().kind() == Kind.COMPUTER || end.device().kind() == Kind.ANTENNA) && !(end.device() instanceof qbotics.qbot.QbotBlockEntity)) {
                throw notASource(end);
            }
        }
        for (End end : destinations) {
            if ((end.device().kind() == Kind.COMPUTER || end.device().kind() == Kind.ANTENNA) && !(end.device() instanceof qbotics.qbot.QbotBlockEntity)) {
                throw notADestination(end);
            }
        }
        for (End end : destinations) {
            if (end.device().medium() != medium && !(end.device() instanceof qbotics.qbot.QbotBlockEntity)) {
                throw new CallError("A move carries one kind of thing: " + sources.getFirst().label() + " handles "
                        + medium.word + (medium == Medium.ITEM ? "s" : "") + " but " + end.label() + " handles " + end.device().medium().word
                        + (end.device().medium() == Medium.ITEM ? "s" : "") + ".");
            }
        }
        for (End end : sources) {
            if (end.device().medium() != medium && !(end.device() instanceof qbotics.qbot.QbotBlockEntity)) {
                throw new CallError("A move carries one kind of thing, but its sources handle both " + medium.word + " and "
                        + end.device().medium().word + ".");
            }
        }

        long most;
        long byDefault;
        Step step;
        switch (medium) {
            case ITEM -> {
                NameMatch<Item> match = wanted == null ? null : item(wanted);
                List<ItemPort> from = new ArrayList<>();
                List<ItemPort> to = new ArrayList<>();
                List<java.util.function.Predicate<ItemStack>> passes = new ArrayList<>();
                for (End end : sources) {
                    if (onScreen(end)) {
                        // Taking from a screen may cost levels (the anvil): paid from what this call names.
                        openScreen(level, screenBus(end.device())).payers = payers(level, network, end.written().get("xp"));
                    }
                }
                for (End end : sources) {
                    ItemPort port = itemSource(level, end);
                    checkSlot(end.slot(), port.contents().size(), end.label(), "slot");
                    from.add(port);
                }
                for (End end : destinations) {
                    ItemPort port = itemDestination(level, end);
                    checkSlot(end.slot(), port.contents().size(), end.label(), "slot");
                    to.add(port);
                }
                // A storage and one of its own members: the storage without that member, so
                // nothing is moved from a chest into itself.
                List<ItemPort> fromPair = new ArrayList<>();
                List<ItemPort> toPair = new ArrayList<>();
                for (int s = 0; s < sources.size(); s++) {
                    for (int d = 0; d < destinations.size(); d++) {
                        ItemPort a = from.get(s);
                        ItemPort b = to.get(d);
                        if (a instanceof StoragePort group && sources.get(s).group() != null && destinations.get(d).group() == null) {
                            a = group.without(places(level, destinations.get(d).device()));
                        }
                        if (b instanceof StoragePort group && destinations.get(d).group() != null && sources.get(s).group() == null) {
                            b = group.without(places(level, sources.get(s).device()));
                        }
                        fromPair.add(a);
                        toPair.add(b);
                    }
                }
                for (End source : sources) {
                    java.util.function.Predicate<ItemStack> own = itemFilter(source);
                    for (End destination : destinations) {
                        java.util.function.Predicate<ItemStack> theirs = itemFilter(destination);
                        passes.add(stack -> (match == null || match.testStack(stack)) && own.test(stack) && theirs.test(stack));
                    }
                }
                most = MAX_ITEMS_PER_CALL;
                byDefault = most; // with no count, everything that fits (a plain name used to move just one)
                step = (s, d, limit, simulate) -> {
                    int pair = s * destinations.size() + d;
                    return from.get(s) == to.get(d) ? 0 : ItemPort.move(fromPair.get(pair), sources.get(s).slot(),
                            toPair.get(pair), destinations.get(d).slot(), passes.get(pair), (int) limit, simulate);
                };
            }
            case FLUID -> {
                NameMatch<Fluid> match = wanted == null ? null : fluid(wanted);
                List<FluidPort> from = new ArrayList<>();
                List<FluidPort> to = new ArrayList<>();
                List<java.util.function.Predicate<Fluid>> passes = new ArrayList<>();
                for (End end : sources) {
                    if (end.device() instanceof CablePort plate && !plate.gives()) {
                        throw notASource(end);
                    }
                    FluidPort port = fluidSource(level, end.device(), end.face());
                    checkSlot(end.slot(), port.tanks().size(), end.label(), "tank");
                    from.add(port);
                }
                for (End end : destinations) {
                    FluidPort port;
                    if (end.device() instanceof FluidInterfaceBlockEntity store) {
                        port = store;
                    } else if (end.device() instanceof qbotics.qbot.QbotBlockEntity qbot) {
                        port = qbot.fluidPort();
                    } else if (end.device() instanceof CablePort plate && plate.takes()) {
                        port = fluidTarget(level, plate, end.face(), end.slot() >= 0);
                    } else {
                        throw notADestination(end);
                    }
                    checkSlot(end.slot(), port.tanks().size(), end.label(), "tank");
                    to.add(port);
                }
                for (End source : sources) {
                    java.util.function.Predicate<Fluid> own = endFilter(source, NetworkCalls::fluid);
                    for (End destination : destinations) {
                        java.util.function.Predicate<Fluid> theirs = endFilter(destination, NetworkCalls::fluid);
                        passes.add(thing -> (match == null || match.test(thing)) && own.test(thing) && theirs.test(thing));
                    }
                }
                most = MAX_FLUID_PER_CALL;
                byDefault = most;
                step = (s, d, limit, simulate) -> from.get(s) == to.get(d) ? 0 : FluidPort.move(from.get(s), sources.get(s).slot(),
                        to.get(d), destinations.get(d).slot(), passes.get(s * destinations.size() + d), (int) limit, simulate);
            }
            case ENERGY -> {
                if (wanted != null) {
                    throw new CallError("Energy has no kinds to name: give only the amount, as in move(battery, machine, count=5000).");
                }
                List<EnergyPort> from = new ArrayList<>();
                List<EnergyPort> to = new ArrayList<>();
                for (End end : sources) {
                    if (end.device() instanceof CablePort plate && !plate.gives()) {
                        throw notASource(end);
                    }
                    from.add(energySource(level, end.device(), end.face()));
                }
                for (End end : destinations) {
                    if (end.device() instanceof EnergyInterfaceBlockEntity store) {
                        to.add(store);
                    } else if (end.device() instanceof qbotics.qbot.QbotBlockEntity qbot) {
                        to.add(qbot.energyPort());
                    } else if (end.device() instanceof CablePort plate && plate.takes()) {
                        to.add(energyTarget(level, plate, end.face(), false));
                    } else {
                        throw notADestination(end);
                    }
                }
                most = MAX_ENERGY_PER_CALL;
                byDefault = most;
                step = (s, d, limit, simulate) -> {
                    if (from.get(s) == to.get(d)) {
                        return 0;
                    }
                    if (!simulate) {
                        return EnergyPort.move(from.get(s), to.get(d), (int) limit);
                    }
                    int available = from.get(s).extract((int) limit, true);
                    return available == 0 ? 0 : to.get(d).insert(available, true);
                };
            }
            default -> {
                // Experience, between Experience Interfaces. (Paying for things with it comes with screen access.)
                if (wanted != null) {
                    throw new CallError("Experience has no kinds to name: give only the amount, in points.");
                }
                List<qbotics.block.ExperienceInterfaceBlockEntity> from = new ArrayList<>();
                List<qbotics.block.ExperienceInterfaceBlockEntity> to = new ArrayList<>();
                for (End end : sources) {
                    if (!(end.device() instanceof qbotics.block.ExperienceInterfaceBlockEntity store)) {
                        throw notASource(end);
                    }
                    from.add(store);
                }
                for (End end : destinations) {
                    if (!(end.device() instanceof qbotics.block.ExperienceInterfaceBlockEntity store)) {
                        throw notADestination(end);
                    }
                    to.add(store);
                }
                most = Integer.MAX_VALUE;
                byDefault = most;
                step = (s, d, limit, simulate) -> {
                    long room = qbotics.block.ExperienceInterfaceBlockEntity.MAX_POINTS - to.get(d).points();
                    long amount = from.get(s) == to.get(d) ? 0 : Math.min(limit, Math.min(from.get(s).points(), room));
                    return simulate ? amount : to.get(d).add(from.get(s).take(amount));
                };
            }
        }

        if (need != null && need > most) {
            throw new CallError("One move can carry at most " + most + "; " + need + " were asked for.");
        }
        long limit = need != null ? need : count != null ? Math.min(count, most) : byDefault;
        JsonObject answer = new JsonObject();
        // Through other antennas, a move is held to what their Transport Upgrades may carry this second.
        long tick = level.getServer().getTickCount();
        boolean linked = !LINKS.isEmpty();
        if (linked) {
            if (need != null && need > qbotics.upgrade.TransportRate.perSecond(medium)) {
                throw new CallError("An antenna carries at most " + qbotics.upgrade.TransportRate.perSecond(medium) + " "
                        + medium.word + (medium == Medium.ITEM ? "s" : "") + " a second; " + need + " can't cross in one move.");
            }
            long room = qbotics.upgrade.TransportRate.room(LINKS, medium, tick);
            if (need != null && room < need) {
                answer.addProperty("moved", 0);
                answer.addProperty("short", true);
                answer.addProperty("could", room);
                return answer;
            }
            limit = Math.min(limit, room);
        }
        if (need != null) {
            // All or nothing: find out first whether that many can move, and change nothing if not.
            long could = sweep(step, sources.size(), destinations.size(), limit, true);
            if (could < need) {
                answer.addProperty("moved", 0);
                answer.addProperty("short", true);
                answer.addProperty("could", could);
                return answer;
            }
        }
        if (dry) {
            long could = sweep(step, sources.size(), destinations.size(), limit, true);
            List<String> from = new ArrayList<>();
            sources.forEach(end -> from.add(end.label()));
            List<String> to = new ArrayList<>();
            destinations.forEach(end -> to.add(end.label()));
            answer.addProperty("moved", could);
            answer.addProperty("said", "would move " + could + (medium == Medium.FLUID ? " mB of " : medium == Medium.ENERGY ? " FE of " : " ")
                    + (wanted == null ? "anything" : wanted) + ": " + String.join(", ", from) + " -> " + String.join(", ", to));
            return answer;
        }
        LessonHooks.Before lesson = null;
        if (medium == Medium.ITEM) {
            try {
                lesson = LessonHooks.beforeMove(level, sources, destinations, end -> onScreen(end) ? openScreen(level, screenBus(end.device())) : null);
            } catch (RuntimeException e) {
                Qbotics.LOGGER.debug("A lesson's check failed before a move", e);
            }
        }
        long moved = sweep(step, sources.size(), destinations.size(), limit, false);
        try {
            LessonHooks.afterMove(lesson, sources, destinations, moved);
        } catch (RuntimeException e) {
            Qbotics.LOGGER.debug("A lesson's check failed after a move", e);
        }
        if (need != null && moved < need) {
            // Several kinds competing for the same free slots can make the trial run optimistic.
            throw new CallError("Only " + moved + " of the " + need + " needed could be moved; the rest had nowhere to go.");
        }
        if (linked) {
            qbotics.upgrade.TransportRate.spend(LINKS, medium, moved, tick);
        }
        answer.addProperty("moved", moved);
        return answer;
    }

    private static long sweep(Step step, int sources, int destinations, long limit, boolean simulate) {
        long moved = 0;
        for (int s = 0; s < sources && moved < limit; s++) {
            for (int d = 0; d < destinations && moved < limit; d++) {
                moved += step.run(s, d, limit - moved, simulate);
            }
        }
        return moved;
    }

    // ---- fluid and energy ----------------------------------------------------------------

    /** The most one call may move: sixteen buckets of fluid, or a whole energy interface's worth. */
    private static final int MAX_FLUID_PER_CALL = 16 * FluidPort.BUCKET;
    private static final int MAX_ENERGY_PER_CALL = EnergyInterfaceBlockEntity.CAPACITY;

    /** The fluid for an ID such as "minecraft:water"; "water" alone means the same. */
    static NameMatch<Fluid> fluid(String id) {
        return match(BuiltInRegistries.FLUID, id, "fluid", "minecraft:water or minecraft:lava");
    }

    /**
     * What a program wrote where an item or fluid is named: one name, or a filter (see
     * {@link NameMatch}). A name or filter that nothing in the game passes is a mistake.
     */
    private static <T> NameMatch<T> match(net.minecraft.core.Registry<T> registry, String text, String what, String examples) {
        NameMatch<T> match;
        try {
            match = NameMatch.parse(registry, text);
        } catch (NameMatch.BadFilter e) {
            throw new CallError(e.getMessage());
        }
        if (match.first() == null) {
            boolean plain = !text.matches(".*[\\s|*?@#$].*");
            if (!plain) {
                throw new CallError("No " + what + " passes the filter '" + text + "'." + tagHint(registry, text, what));
            }
            List<String> near = NameMatch.closest(registry, text);
            throw new CallError("There is no " + what + " called '" + text + "'. " + (near.isEmpty()
                    ? Character.toUpperCase(what.charAt(0)) + what.substring(1) + " names look like " + examples + "."
                    : "Did you mean " + String.join(near.size() == 2 ? " or " : ", ", near).replaceAll(", ([^,]+)$", " or $1") + "?"));
        }
        return match;
    }

    /**
     * Why the tags in a filter that matched nothing might not be what was meant. The usual
     * mistake is a block tag (as F3 shows for the block looked at): blocks in the world carry
     * those, items don't.
     */
    private static <T> String tagHint(net.minecraft.core.Registry<T> registry, String text, String what) {
        StringBuilder hint = new StringBuilder();
        java.util.regex.Matcher words = java.util.regex.Pattern.compile("(?<![^\\s|-])[#$]([^\\s|]+)").matcher(text.toLowerCase(java.util.Locale.ROOT));
        while (words.find()) {
            String name = words.group(1);
            List<String> ids = name.contains(":") ? List.of(name) : List.of("minecraft:" + name, "c:" + name);
            boolean itemTag = false;
            boolean blockTag = false;
            for (String id : ids) {
                net.minecraft.resources.Identifier key = net.minecraft.resources.Identifier.tryParse(id);
                if (key == null) {
                    continue;
                }
                itemTag |= registry.get(net.minecraft.tags.TagKey.create(registry.key(), key)).map(set -> set.size() > 0).orElse(false);
                blockTag |= registry != BuiltInRegistries.BLOCK
                        && BuiltInRegistries.BLOCK.get(net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.BLOCK, key)).map(set -> set.size() > 0).orElse(false);
            }
            if (itemTag) {
                continue;
            }
            hint.append(blockTag
                    ? " '#" + name + "' is a block tag: blocks in the world carry it, but no " + what + " does."
                    : " No " + what + " carries the tag '#" + name + "'.");
        }
        if (!hint.isEmpty() && registry == BuiltInRegistries.ITEM) {
            hint.append(" To see an item's tags, hold the Network Probe, point at the item in an inventory and hold Shift.");
        }
        return hint.toString();
    }

    private static String nothingThere(ServerLevel level, CablePort port, String what) {
        level = levelOf(port, level);
        if (!level.isLoaded(port.targetPos())) {
            return notLoaded(port);
        }
        String block = BuiltInRegistries.BLOCK.getKey(level.getBlockState(port.targetPos()).getBlock()).toString();
        return "The " + port.kindWord().replace('_', ' ') + " at " + port.ip() + " points at " + block + ", which holds no " + what + ".";
    }

    /** The side of its target a plate works through: none named reads the whole block, where the block allows it. */
    private static Direction sideFor(CablePort port, String faceWord) {
        return (faceWord == null ? port.accessFace() : face(faceWord)).resolve(port.pointing());
    }

    static FluidPort fluidTarget(ServerLevel level, CablePort port, String faceWord, boolean whole) {
        level = levelOf(port, level);
        BlockPos pos = port.targetPos();
        FluidPort target = null;
        if (level.isLoaded(pos)) {
            target = whole && faceWord == null ? Qbotics.platform().fluidPort(level, pos, null) : null;
            if (target == null) {
                target = Qbotics.platform().fluidPort(level, pos, sideFor(port, faceWord));
            }
        }
        if (target == null) {
            throw new CallError(nothingThere(level, port, "fluid"));
        }
        return target;
    }

    private static EnergyPort energyTarget(ServerLevel level, CablePort port, String faceWord, boolean whole) {
        level = levelOf(port, level);
        BlockPos pos = port.targetPos();
        EnergyPort target = null;
        if (level.isLoaded(pos)) {
            target = whole && faceWord == null ? Qbotics.platform().energyPort(level, pos, null) : null;
            if (target == null) {
                target = Qbotics.platform().energyPort(level, pos, sideFor(port, faceWord));
            }
        }
        if (target == null) {
            throw new CallError(nothingThere(level, port, "energy"));
        }
        return target;
    }

    /** What a fluid device reads: a fluid interface's own tanks, or the tanks a fluid bus points at. */
    static FluidPort fluidSource(ServerLevel level, NetworkDevice device, String faceWord) {
        if (device instanceof FluidInterfaceBlockEntity store) {
            return store;
        }
        if (device instanceof qbotics.qbot.QbotBlockEntity qbot) {
            return qbot.fluidPort(); // its two tanks
        }
        if (device instanceof CablePort port && port.gives()) {
            return fluidTarget(level, port, faceWord, true);
        }
        throw new CallError("Only a fluid interface or a fluid bus can list contents; this is " + article(device) + ".");
    }

    static EnergyPort energySource(ServerLevel level, NetworkDevice device, String faceWord) {
        if (device instanceof EnergyInterfaceBlockEntity store) {
            return store;
        }
        if (device instanceof qbotics.qbot.QbotBlockEntity qbot) {
            return qbot.energyPort(); // its cell
        }
        if (device instanceof CablePort port && port.medium() == Medium.ENERGY && port.gives()) {
            return energyTarget(level, port, faceWord, true);
        }
        throw new CallError("Only an energy interface or an energy bus can say how much energy there is; this is "
                + article(device) + ".");
    }

    private static JsonArray tanks(FluidPort port) {
        JsonArray list = new JsonArray();
        List<FluidPort.Tank> tanks = port.tanks();
        for (int i = 0; i < tanks.size(); i++) {
            FluidPort.Tank tank = tanks.get(i);
            JsonObject entry = new JsonObject();
            entry.addProperty("tank", i);
            if (tank.amount() > 0) {
                entry.addProperty("fluid", BuiltInRegistries.FLUID.getKey(tank.fluid()).toString());
            } else {
                entry.add("fluid", JsonNull.INSTANCE);
            }
            entry.addProperty("amount", tank.amount());
            entry.addProperty("capacity", tank.capacity());
            list.add(entry);
        }
        return list;
    }

    /** A slot or tank number argument, or -1 if none was given. */
    private static int slot(JsonArray args, int index) {
        if (index >= args.size() || args.get(index).isJsonNull()) {
            return -1;
        }
        int slot = args.get(index).getAsInt();
        if (slot < 0) {
            throw new CallError("Slots and tanks are numbered from 0; there is no number " + slot + ".");
        }
        return slot;
    }

    private static void checkSlot(int slot, int size, String owner, String word) {
        if (slot >= size) {
            throw new CallError(size == 0 ? owner + " has no " + word + "s that can be reached here."
                    : owner + " has " + word + "s 0 to " + (size - 1) + " here; there is no " + word + " " + slot + ".");
        }
    }

    // ---- messages between computers (design doc §9.3) ---------------------------------

    /** "100.64.0.12:1003": an antenna's external address and a device's port on it. */
    static final Pattern EXTERNAL = Pattern.compile("(\\d{1,3}(?:\\.\\d{1,3}){3}):(\\d{1,5})");
    /** The same, with the port left out or ignored: an antenna's whole network. */
    private static final Pattern EXTERNAL_OR_IP = Pattern.compile("(\\d{1,3}(?:\\.\\d{1,3}){3})(?::\\d{1,5})?");
    private static final int MAX_MESSAGE_CHARS = 4096;

    /** Another antenna this network can reach by radio, and the network behind it (null if it isn't that network's router). */
    record Far(AntennaBlockEntity antenna, ServerLevel level, CableNetwork network) {
    }

    /** The owner of the computer that made a call, or null if nobody owns it. */
    /** The running computer whose block is at a position: the one making the call. */
    static qbotics.computer.ServerComputer runningAt(ServerLevel level, BlockPos computerPos) {
        if (callAs != null) {
            return callAs;
        }
        qbotics.computer.ServerComputer computer = level.getBlockEntity(computerPos) instanceof qbotics.block.ComputerBlockEntity block
                ? qbotics.computer.ComputerManager.get(level.getServer()).computer(block.deviceId()) : null;
        if (computer == null) {
            throw new CallError("This computer isn't running.");
        }
        return computer;
    }

    /** The owner of the computer whose call is being answered (a Qbot's, a Server's), or null. Server thread only. */
    static java.util.@org.jspecify.annotations.Nullable UUID callerOwner() {
        return callLevel == null || callPos == null ? null : ownerAt(callLevel, callPos);
    }

    /**
     * The items a device holds itself, rather than a container it points at: an Interface's
     * slots, or a Player Interface's player. Null for a device that holds none.
     */
    static @org.jspecify.annotations.Nullable ItemPort ownItems(NetworkDevice device) {
        if (device instanceof InterfaceBlockEntity store) {
            return store;
        }
        if (device instanceof qbotics.block.GameInterfaceBlockEntity game && game.kind() == Kind.PLAYER) {
            return GameInterfaceCalls.playerItems(game);
        }
        if (device instanceof qbotics.block.CollectorBlockEntity collector) {
            return new ContainerItemPort(collector);
        }
        if (device instanceof qbotics.qbot.QbotBlockEntity qbot) {
            return qbot.itemPort(); // its 16 slots
        }
        if (device instanceof SlotsView slots) {
            return slots.port(); // a fluid or energy store's bucket or battery slots
        }
        return null;
    }

    private static java.util.UUID ownerAt(ServerLevel level, BlockPos computerPos) {
        return level.getBlockEntity(computerPos) instanceof qbotics.block.ComputerBlockEntity computer ? computer.owner() : null;
    }

    static Far far(ServerLevel level, CableNetwork network, String ip, BlockPos computerPos) {
        AntennaBlockEntity near = network.gateway();
        if (near == null) {
            if (level.getBlockEntity(computerPos) instanceof qbotics.qbot.QbotBlockEntity qbot) {
                // A Qbot has a radio of its own: it reaches an antenna it is in range of, and that lets its owner in.
                return radioFar(level.getServer(), level.dimension(), computerPos, qbot.owner(), ip);
            }
            throw new CallError("Reaching an external address needs an antenna cabled to this computer.");
        }
        AntennaBlockEntity far = RadioNetwork.get(level.getServer()).byExternalIp(ip);
        if (far == null || !(far.getLevel() instanceof ServerLevel farLevel)) {
            throw new CallError("No antenna with the address " + ip + " can be reached. It may not exist, or its area isn't loaded.");
        }
        String problem = far == near ? null : RadioNetwork.linkProblem(near, far);
        if (problem == null && far != near) {
            // In range and on the same frequency; now, is this computer's owner let in?
            problem = RadioNetwork.accessProblem(level.getServer(), far, ownerAt(level, computerPos));
        }
        if (problem != null) {
            throw new CallError("Can't reach " + ip + ": " + problem + ".");
        }
        CableNetwork farNetwork = CableNetwork.at(farLevel, far.getBlockPos());
        return new Far(far, farLevel, farNetwork.gateway() == far && !farNetwork.tooLarge() ? farNetwork : null);
    }

    /**
     * The second half of an ssh login, once the computer it reaches has been found: that
     * computer's own setting decides who gets in. Used from computers and from laptops.
     *
     * @param caller who is logging in: the calling computer's owner, or the laptop's player
     */
    private static JsonObject sshSession(net.minecraft.server.MinecraftServer server, String method, NetworkDevice found, String address,
            String from, java.util.@org.jspecify.annotations.Nullable UUID caller, @org.jspecify.annotations.Nullable String password) {
        if (!(found instanceof qbotics.block.ComputerBlockEntity remote)) {
            throw new CallError("ssh connects to computers; " + address + " is " + article(found) + ".");
        }
        JsonObject session = new JsonObject();
        if (method.equals("ssh_open")) {
            // Gate two, the computer: does its own setting let the caller in?
            boolean owner = java.util.Objects.equals(caller, remote.owner());
            switch (remote.remoteLogin()) {
                case OFF -> throw new CallError("That computer doesn't allow remote login. Its Settings page can change that.");
                case OWNER -> {
                    if (!owner) {
                        throw new CallError("That computer only lets its owner's computers log in. Its Settings page can allow a password instead.");
                    }
                }
                case PASSWORD -> {
                    if (!owner) {
                        if (password == null) {
                            session.addProperty("need_password", true);
                            return session;
                        }
                        if (!remote.checkPassword(password)) {
                            throw new CallError("Wrong password.");
                        }
                    }
                }
            }
            ComputerManager.get(server).getOrStart(remote); // switch it on if nobody has yet
        }
        session.addProperty("id", remote.deviceId().toString());
        session.addProperty("label", remote.deviceName().isEmpty() ? address : remote.deviceName());
        session.addProperty("from", from);
        return session;
    }

    // ---- laptops (design doc §5.6): a computer that is a player's, with a built-in Infinite antenna ----

    /**
     * The antenna at an external address, as a player's laptop reaches it: the laptop is an
     * Infinite antenna where the player stands, that hears every frequency. The far antenna's
     * range rules and access (who it lets in) still apply.
     */
    private static Far laptopFar(net.minecraft.server.level.ServerPlayer player, String ip) {
        return radioFar(player.level().getServer(), player.level().dimension(), player.blockPosition(), player.getUUID(), ip);
    }

    /**
     * The antenna at an external address, as an Infinite antenna that hears every frequency
     * reaches it from here: a laptop where its player stands, or a Qbot's radio. The far
     * antenna's range rules and access (who it lets in) still apply.
     */
    static Far radioFar(MinecraftServer server, ResourceKey<Level> dimension, BlockPos from, java.util.@org.jspecify.annotations.Nullable UUID caller, String ip) {
        AntennaBlockEntity far = RadioNetwork.get(server).byExternalIp(ip);
        if (far == null || !(far.getLevel() instanceof ServerLevel farLevel)) {
            throw new CallError("No antenna with the address " + ip + " can be reached. It may not exist, or its area isn't loaded.");
        }
        String problem = far.bricked() ? "that antenna is inactive: its owner has more antennas than they may have"
                : RadioNetwork.rangeProblem(RadioNetwork.Tier.INFINITE, dimension, from,
                        far.tier(), farLevel.dimension(), far.getBlockPos());
        if (problem == null) {
            problem = RadioNetwork.accessProblem(server, far, caller);
        }
        if (problem != null) {
            throw new CallError("Can't reach " + ip + ": " + problem + ".");
        }
        CableNetwork farNetwork = CableNetwork.at(farLevel, far.getBlockPos());
        return new Far(far, farLevel, farNetwork.gateway() == far && !farNetwork.tooLarge() ? farNetwork : null);
    }

    /**
     * A call from a player's laptop. A laptop isn't on a cable network: it reaches computers
     * and Qbots behind antennas, by ssh, and lists what is behind them.
     */
    public static String handleLaptop(net.minecraft.server.MinecraftServer server, java.util.UUID playerId, String request) {
        net.minecraft.server.level.ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null) {
            JsonObject offline = new JsonObject();
            offline.addProperty("e", "This laptop's owner is offline.");
            return offline.toString();
        }
        return handleLaptop(player, request);
    }

    /** A call from this player's laptop. */
    public static String handleLaptop(net.minecraft.server.level.ServerPlayer player, String request) {
        JsonObject reply = new JsonObject();
        try {
            JsonArray args = JsonParser.parseString(request).getAsJsonArray();
            callLevel = player.level();
            callPos = player.blockPosition();
            LINKS.clear();
            REMOTE.clear();
            reply.add("r", runLaptop(player, args));
        } catch (CallError e) {
            reply.addProperty("e", e.getMessage());
        } catch (RuntimeException e) {
            Qbotics.LOGGER.warn("Laptop call failed: {}", request, e);
            reply = new JsonObject();
            reply.addProperty("e", "The call failed: its arguments are wrong, or something went wrong in the game ("
                    + e.getClass().getSimpleName() + ").");
        } finally {
            callLevel = null;
            callPos = null;
            LINKS.clear();
            REMOTE.clear();
        }
        return reply.toString();
    }

    private static JsonElement runLaptop(net.minecraft.server.level.ServerPlayer player, JsonArray args) {
        String method = args.get(0).getAsString();
        switch (method) {
            case "env":
                // A laptop is where its player is.
                return Environment.read(player.level(), player.blockPosition());
            case "ssh_open":
            case "ssh_reach": {
                String address = args.get(1).getAsString();
                Matcher external = EXTERNAL.matcher(address);
                if (!external.matches()) {
                    throw new CallError("A laptop reaches computers through their antennas: give an external address and port, like 100.64.0.12:1005.");
                }
                Far far = laptopFar(player, external.group(1));
                int port = Integer.parseInt(external.group(2));
                NetworkDevice found = far.network() != null ? far.network().byHost(port - RadioNetwork.PORT_BASE) : null;
                if (found == null) {
                    throw new CallError("Nothing is on port " + port + " of " + external.group(1) + ".");
                }
                return sshSession(player.level().getServer(), method, found, address, "the laptop of " + player.getName().getString(),
                        player.getUUID(), string(args, 2));
            }
            case "devices": {
                String address = string(args, 1);
                Matcher external = address == null ? null : EXTERNAL_OR_IP.matcher(address.strip());
                if (external == null || !external.matches()) {
                    throw new CallError("A laptop has no cable network of its own. Give an antenna's external address: devices 100.64.0.12");
                }
                Far far = laptopFar(player, external.group(1));
                if (far.network() == null) {
                    throw new CallError("The antenna at " + external.group(1) + " isn't its network's router, so nothing behind it can be reached.");
                }
                JsonArray list = new JsonArray();
                for (NetworkDevice device : far.network().devices()) {
                    REMOTE.put(device, external.group(1));
                    list.add(describe(far.level(), far.network(), device));
                }
                return list;
            }
            default:
                throw new CallError("A laptop isn't on a cable network: it reaches computers and Qbots with ssh (ssh 100.64.0.12:1005).");
        }
    }

    /** This network's screen on the tablet a program named by its address. */
    private static qbotics.display.Tablets.App tabletApp(ServerLevel level, CableNetwork network, JsonArray args) {
        String mac = string(args, 1);
        qbotics.display.Tablets.App app = mac == null ? null
                : qbotics.display.Tablets.get(level.getServer()).app(tabletKey(level, network, callPos), mac);
        if (app == null) {
            throw new CallError("That tablet is no longer paired with this network.");
        }
        return app;
    }

    /**
     * Whose tabs a program draws on: a Qbot's own (a tablet paired with the Qbot, reached by its
     * radio wherever it is), else its network's (a tablet paired with the network's antenna).
     */
    private static java.util.UUID tabletKey(ServerLevel level, CableNetwork network, BlockPos computerPos) {
        if (level.getBlockEntity(computerPos) instanceof qbotics.qbot.QbotBlockEntity qbot) {
            return qbot.driveId(); // its tab follows its drive, kept when it is broken and placed again
        }
        return router(network).deviceId();
    }

    /** The same, or null for a computer whose network has no antenna (it has no tablets). */
    private static java.util.@org.jspecify.annotations.Nullable UUID tabletKeyOrNull(ServerLevel level, CableNetwork network, BlockPos computerPos) {
        if (level.getBlockEntity(computerPos) instanceof qbotics.qbot.QbotBlockEntity qbot) {
            return qbot.driveId();
        }
        return network.gateway() == null ? null : network.gateway().deviceId();
    }

    private static AntennaBlockEntity router(CableNetwork network) {
        AntennaBlockEntity antenna = network.gateway();
        if (antenna == null) {
            throw new CallError("There is no antenna on this network. Cable one to this computer first.");
        }
        return antenna;
    }

    /**
     * Sends a message to another computer: one on this cable network (by name or address,
     * no antenna needed) or one behind another antenna (external address and port). Returns
     * false if the address is right but that computer can't take the message just now.
     *
     * @param data the message as JSON text; it is handed over as it is, never interpreted here
     */
    private static boolean send(ServerLevel level, CableNetwork network, BlockPos computerPos, String address, String data) {
        if (data.length() > MAX_MESSAGE_CHARS) {
            throw new CallError("That message is too long (" + data.length() + " characters; the most is " + MAX_MESSAGE_CHARS + ").");
        }
        NetworkDevice self = network.devices().stream().filter(device -> device.getBlockPos().equals(computerPos)).findFirst()
                .orElseThrow(() -> new CallError("This computer's block is missing."));
        NetworkDevice target;
        String sender;
        Matcher external = EXTERNAL.matcher(address);
        qbotics.qbot.QbotBlockEntity fromQbot = self instanceof qbotics.qbot.QbotBlockEntity q ? q : null;
        if (external.matches()) {
            String ip = external.group(1);
            int port = Integer.parseInt(external.group(2));
            MinecraftServer server = level.getServer();
            qbotics.qbot.QbotBlockEntity toQbot = ComputerManager.get(server).qbotByRadio(server, ip);
            if (toQbot != null) {
                // A Qbot's own radio (an Infinite antenna), wherever it is.
                String problem;
                if (fromQbot != null) {
                    problem = RadioNetwork.rangeProblem(RadioNetwork.Tier.INFINITE, level.dimension(), fromQbot.getBlockPos(),
                            RadioNetwork.Tier.INFINITE, toQbot.getLevel().dimension(), toQbot.getBlockPos());
                } else {
                    AntennaBlockEntity near = network.gateway();
                    if (near == null) {
                        throw new CallError("Reaching an external address needs an antenna cabled to this computer.");
                    }
                    problem = near.bricked() ? "this network's antenna is inactive: its owner has more antennas than they may have"
                            : RadioNetwork.rangeProblem(near.tier(), level.dimension(), near.getBlockPos(),
                                    RadioNetwork.Tier.INFINITE, toQbot.getLevel().dimension(), toQbot.getBlockPos());
                }
                if (problem == null && !RadioNetwork.allowed(server, RadioNetwork.Access.PRIVATE, toQbot.owner(), ownerAt(level, computerPos))) {
                    problem = "a Qbot's radio lets in only its owner's computers and Qbots";
                }
                if (problem != null) {
                    throw new CallError("Can't reach " + ip + ": " + problem + ".");
                }
                return ComputerManager.get(server).deliver(toQbot.deviceId(), senderAddress(level, network, self, fromQbot), data);
            }
            Far far = fromQbot != null ? radioFar(server, level.dimension(), fromQbot.getBlockPos(), fromQbot.owner(), ip)
                    : far(level, network, ip, computerPos);
            target = far.network() != null ? far.network().byHost(port - RadioNetwork.PORT_BASE) : null;
            if (target == null) {
                throw new CallError("Nothing is on port " + port + " of " + ip + ".");
            }
            sender = senderAddress(level, network, self, fromQbot);
        } else {
            target = find(network, address, null);
            sender = self.ip();
        }
        if (target.kind() != Kind.COMPUTER) {
            throw new CallError("Only computers receive messages; " + address + " is " + article(target) + ".");
        }
        return ComputerManager.get(level.getServer()).deliver(target.deviceId(), sender, data);
    }

    /** Where an answer to a message from this computer goes: a Qbot's own radio, else its network's antenna and its port. */
    private static String senderAddress(ServerLevel level, CableNetwork network, NetworkDevice self, qbotics.qbot.@org.jspecify.annotations.Nullable QbotBlockEntity qbot) {
        if (qbot != null) {
            return qbot.radioAddress(level.getServer());
        }
        AntennaBlockEntity near = network.gateway();
        return near == null ? self.ip() : near.externalIp() + ":" + (RadioNetwork.PORT_BASE + self.hostNumber());
    }

    // ---- looking things up -----------------------------------------------------------

    static String string(JsonArray args, int index) {
        return index >= args.size() || args.get(index).isJsonNull() ? null : args.get(index).getAsString();
    }

    static NetworkDevice byMac(CableNetwork network, JsonArray args, int index) {
        NetworkDevice device = resolve(network, args.get(index).getAsString());
        if (device == null) {
            throw new CallError("The device " + args.get(index).getAsString() + " is no longer on this network.");
        }
        return concrete(device, Medium.ITEM);
    }

    /** A Universal Serial Bus working as a bus for one medium (items, unless a call needs another); anything else as it is. */
    static NetworkDevice concrete(NetworkDevice device, Medium medium) {
        if (device instanceof CablePort port && port.universal()) {
            CablePort view = port.as(medium);
            String remote = REMOTE.get(port);
            if (remote != null) {
                REMOTE.put(view, remote);
            }
            return view;
        }
        return device;
    }

    /** A fluid or energy store's item slots ("...@item"): its bucket or battery slots. Null for any other device. */
    static @org.jspecify.annotations.Nullable SlotsView itemSlotsOf(NetworkDevice device) {
        SlotsView view = device instanceof qbotics.block.FluidInterfaceBlockEntity tanks ? tanks.bucketsView()
                : device instanceof qbotics.block.EnergyInterfaceBlockEntity cell ? cell.batteriesView() : null;
        String remote = view == null ? null : REMOTE.get(device);
        if (remote != null) {
            REMOTE.put(view, remote);
        }
        return view;
    }

    /** A device by IP address, hardware address or name; with no key, the only device of a kind. */
    private static NetworkDevice find(CableNetwork network, String key, String kindWord) {
        List<NetworkDevice> matches = new ArrayList<>();
        if (key != null && key.lastIndexOf('@') > 0) {
            NetworkDevice view = resolve(network, key); // a USB's view, by its full address
            if (view == null) {
                throw new CallError("The device " + key + " is no longer on this network.");
            }
            return view;
        }
        List<NetworkDevice> usbs = new ArrayList<>();
        for (NetworkDevice device : key == null ? network.devices() : network.find(key)) {
            if (kindWord == null || device.kindWord().equals(kindWord)) {
                matches.add(device);
            } else if (device instanceof CablePort port && port.universal()) {
                // A USB is also an item, fluid and energy bus: asked for one of those, it answers as that.
                for (Medium medium : Medium.CARRIED) {
                    if (kindWord.equals(medium.prefix + Kind.BUS.word)) {
                        usbs.add(concrete(port, medium));
                    }
                }
            }
        }
        // By name or address a USB is found as readily as a bus; asked for "the only bus", a USB
        // counts only when there is no plain one.
        if (key != null || matches.isEmpty()) {
            matches.addAll(usbs);
        }
        String what = kindWord == null ? "device" : kindWord.replace('_', ' ');
        if (matches.size() == 1) {
            return matches.getFirst();
        }
        if (key == null) {
            throw new CallError(matches.isEmpty()
                    ? "There is no " + what + " on this network."
                    : "There are " + matches.size() + " " + what + " blocks on this network. Say which one by name or address.");
        }
        if (matches.isEmpty()) {
            List<NetworkDevice> otherKinds = network.find(key);
            throw new CallError(otherKinds.isEmpty()
                    ? "No " + what + " on this network has the name or address '" + key + "'."
                    : "'" + key + "' is " + article(otherKinds.getFirst()) + ", not " + article(what) + ".");
        }
        throw new CallError(matches.size() + " devices are named '" + key + "'. Use an address, or rename one.");
    }

    static String article(NetworkDevice device) {
        return article(device.kindWord().replace('_', ' '));
    }

    private static String article(String word) {
        if (word.equals("usb")) {
            return "a USB";
        }
        return ("aeiou".indexOf(word.charAt(0)) >= 0 ? "an " : "a ") + word;
    }

    private static InterfaceBlockEntity input(NetworkDevice device) {
        if (device instanceof InterfaceBlockEntity input) {
            return input;
        }
        throw new CallError("That needs an interface; this is " + article(device) + ".");
    }

    private static CablePort port(NetworkDevice device) {
        if (device instanceof CablePort port) {
            return port;
        }
        throw new CallError("That needs a bus; this is " + article(device) + ".");
    }

    private static AccessFace face(String word) {
        AccessFace face = AccessFace.parse(word);
        if (face == null) {
            throw new CallError("'" + word + "' isn't a face. Use near, far, top, bottom, left or right (or all, for the whole block).");
        }
        return face;
    }

    /** The item for an ID such as "minecraft:coal"; "coal" alone means the same. */
    /** The item, or the filter of items, for what a program wrote: "minecraft:coal", "coal", "#minecraft:logs", "@create". */
    /** A name or filter of a block: "wheat", "#minecraft:crops", "wheat age=7". */
    static NameMatch<net.minecraft.world.level.block.Block> block(String text) {
        return match(BuiltInRegistries.BLOCK, text, "block", "minecraft:wheat");
    }

    /** A name or filter of a kind of entity: "cow", "#minecraft:undead", "@minecraft -player". */
    static NameMatch<net.minecraft.world.entity.EntityType<?>> entityType(String text) {
        return match(BuiltInRegistries.ENTITY_TYPE, text, "entity", "minecraft:cow");
    }

    static NameMatch<Item> item(String id) {
        return match(BuiltInRegistries.ITEM, id, "item", "minecraft:iron_ingot");
    }

    static String itemId(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    /** Everything in the container a bus points at, whichever face it would go in or out through. */
    static ItemPort wholeTarget(ServerLevel level, CablePort port) {
        BlockPos pos = port.targetPos();
        ItemPort whole = level.isLoaded(pos) ? Qbotics.platform().itemPort(level, pos, null) : null;
        return whole != null ? BusFilter.wrap(whole, port.filter()) : target(level, port, null);
    }

    /** The container a bus points at, seen through a face. */
    static ItemPort target(ServerLevel level, CablePort port, String faceWord) {
        if ("all".equals(faceWord)) {
            return wholeTarget(level, port); // every slot, whichever face it would go in or out through
        }
        AccessFace face = faceWord == null ? port.accessFace() : face(faceWord);
        BlockPos pos = port.targetPos();
        Direction side = face.resolve(port.pointing());
        if (!level.isLoaded(pos)) {
            throw new CallError(notLoaded(port));
        }
        ItemPort target = Qbotics.platform().itemPort(level, pos, side);
        if (target == null) {
            String block = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString();
            throw new CallError("The " + port.kindWord().replace('_', ' ') + " at " + port.ip() + " points at " + block
                    + ", which holds no items" + (face == AccessFace.NEAR ? "." : " that can be reached through its " + face.word() + " face."));
        }
        return BusFilter.wrap(target, port.filter());
    }

    /** Asking what block is there would load that part of the world: it is said not to be loaded instead. */
    private static String notLoaded(CablePort port) {
        return "The " + port.kindWord().replace('_', ' ') + " at " + port.ip() + " points into a part of the world that isn't loaded now.";
    }

    // ---- describing things -----------------------------------------------------------

    /** Every device on the network at a place, as "devices --json" lists them (for the AI agent). */
    public static JsonArray describeAll(ServerLevel level, BlockPos at) {
        CableNetwork network = CableNetwork.at(level, at);
        JsonArray list = new JsonArray();
        for (NetworkDevice device : network.devices()) {
            list.add(describe(level, network, device));
        }
        return list;
    }

    private static JsonObject describe(ServerLevel level, CableNetwork network, NetworkDevice device) {
        JsonObject info = new JsonObject();
        info.addProperty("ip", device.ip());
        String remote = REMOTE.get(device);
        // Behind another antenna, its address says which: "100.64.0.2/02:1a:...".
        info.addProperty("mac", remote == null ? device.mac() : remote + "/" + device.mac());
        if (remote != null) {
            info.addProperty("remote", remote);
        }
        info.addProperty("kind", device.kindWord());
        info.addProperty("medium", device.medium().word);
        info.addProperty("name", device.deviceName());
        // With an antenna on the network, every device can be reached from outside on its own port.
        AntennaBlockEntity gateway = network.gateway();
        if (gateway != null && device.hostNumber() > 0) {
            info.addProperty("gateway", gateway.ip());
            info.addProperty("external_ip", gateway.externalIp());
            info.addProperty("port", RadioNetwork.PORT_BASE + device.hostNumber());
        }
        if (device instanceof AntennaBlockEntity antenna) {
            info.addProperty("frequency", antenna.frequency());
            info.addProperty("access", antenna.access().word);
            info.addProperty("tier", antenna.tier().word);
            info.addProperty("range", antenna.tier() == RadioNetwork.Tier.INFINITE ? -1 : antenna.tier().radius);
            info.addProperty("router", antenna == gateway);
            JsonArray upgrades = new JsonArray();
            for (qbotics.upgrade.UpgradeItem.Kind kind : qbotics.upgrade.UpgradeItem.Kind.values()) {
                if (antenna.hasUpgrade(kind)) {
                    upgrades.add(kind.word);
                }
            }
            info.add("upgrades", upgrades);
            info.addProperty("chunk_loading", antenna.chunkLoading());
            info.addProperty("chunk_status", qbotics.upgrade.ChunkLoaders.Status.of(antenna.loaderStatus()).word);
            info.addProperty("chunks", antenna.loadedChunks());
            info.addProperty("chunks_wanted", antenna.wantedChunks());
        } else if (device instanceof CablePort port) {
            if (port.tunnel() != null) {
                // The other end of its tunnel, by the address a program here uses; "" if it can't be reached now.
                String to = Tunnels.addressFrom(levelOf(port, level), network, port);
                info.addProperty("tunnel", to == null ? "" : to);
            }
            info.addProperty("face", port.accessFace().word());
            if (port.kind() == Kind.DROPPER) {
                info.addProperty("dispense", port.dispense());
            }
            if (port.filter() != null) {
                info.add("filter", filterJson(port.filter()));
            }
            info.addProperty("target", level.isLoaded(port.targetPos())
                    ? BuiltInRegistries.BLOCK.getKey(level.getBlockState(port.targetPos()).getBlock()).toString() : "not loaded");
        } else if (device instanceof qbotics.block.DisplayBlockEntity display) {
            display.describe().entrySet().forEach(entry -> info.add(entry.getKey(), entry.getValue()));
        } else if (device instanceof qbotics.block.ProgrammerBlockEntity programmer) {
            info.addProperty("status", programmer.status());
            info.addProperty("has_drive", programmer.mediaId() != null);
        } else if (device instanceof qbotics.block.DriveBayBlockEntity bay) {
            // Where its drives appear on this network's computers, and their names.
            info.addProperty("folder", bay.folderName());
            JsonArray drives = new JsonArray();
            bay.mounted().keySet().forEach(drives::add);
            info.add("drives", drives);
        } else if (device instanceof qbotics.block.BlockSensorBlockEntity sensor) {
            JsonArray box = new JsonArray();
            for (int value : sensor.box()) {
                box.add(value);
            }
            info.add("box", box);
        } else if (device instanceof qbotics.block.AreaBlockEntity area) {
            info.addProperty("radius", area.radius());
            info.addProperty("height", area.height());
            if (area instanceof qbotics.block.CollectorBlockEntity) {
                info.addProperty("slots", qbotics.block.CollectorBlockEntity.SLOTS);
            }
        } else if (device instanceof qbotics.block.GameInterfaceBlockEntity game) {
            // Whose it is; a Player Interface also says whether they are online, and its slots.
            info.addProperty("player", game.ownerName(level.getServer()));
            info.addProperty("online", game.onlineOwner(level.getServer()) != null);
            if (game.kind() == Kind.PLAYER) {
                info.addProperty("slots", PlayerItemPort.SLOTS);
            }
        } else if (device instanceof InterfaceBlockEntity) {
            info.addProperty("slots", InterfaceBlockEntity.SLOTS);
        } else if (device instanceof FluidInterfaceBlockEntity) {
            info.addProperty("tanks", FluidInterfaceBlockEntity.TANKS);
            info.addProperty("tank_capacity", FluidInterfaceBlockEntity.TANK_CAPACITY);
        } else if (device instanceof EnergyInterfaceBlockEntity) {
            info.addProperty("capacity", EnergyInterfaceBlockEntity.CAPACITY);
        } else if (device instanceof qbotics.block.ExperienceInterfaceBlockEntity store) {
            info.addProperty("points", store.points());
            info.addProperty("levels", qbotics.block.Experience.levelAt(store.points()));
            info.addProperty("lock", store.lock().word);
        }
        return info;
    }

    private static JsonObject filterJson(BusFilter filter) {
        JsonObject written = new JsonObject();
        JsonArray names = new JsonArray();
        filter.names().forEach(names::add);
        written.add("only", names);
        written.add("matching", filter.matchingJson());
        return written;
    }

    /** What a container holds, slot by slot; with a level to read registries from, each stack's details as well. */
    /**
     * ["inspect", key, kind]: what the editor shows when a device's name is Ctrl+clicked. The
     * device as the devices call describes it; for one holding items its slots; for a bus or USB
     * also the block it works ("block", "title"), the face it uses ("face"), which slots each
     * face of the block reaches ("faces"), and the block's screen as a player would see it
     * ("screen": its menu type, slot count and each of its own slots with its place, number,
     * item and whether it takes anything), opened for a moment as open_screen() does. Fluid
     * and energy show their tanks or store. Only looks: nothing is moved.
     */
    private static JsonObject inspect(ServerLevel level, CableNetwork network, BlockPos computerPos, JsonArray args) {
        if ("filter".equals(string(args, 2))) {
            return inspectFilter(level, network, string(args, 1));
        }
        if ("storage".equals(string(args, 2))) {
            // A storage: its containers in the order it fills them, and what they hold between them.
            JsonObject spec = new JsonObject();
            spec.add("group", args.get(1).isJsonArray() ? args.get(1) : new JsonArray());
            spec.add("address", com.google.gson.JsonNull.INSTANCE);
            spec.add("only", new JsonArray());
            spec.add("without", new JsonArray());
            JsonArray sources = new JsonArray();
            sources.add(spec);
            JsonArray ask = new JsonArray();
            ask.add("stock");
            ask.add(sources);
            JsonObject look = run(level, network, computerPos, ask).getAsJsonObject();
            look.addProperty("kind", "storage");
            look.add("group", spec.get("group"));
            return look;
        }
        NetworkDevice device = find(network, string(args, 1), string(args, 2));
        ServerLevel where = levelOf(device, level);
        JsonObject look = describe(where, network, device);
        if (ownItems(device) instanceof ItemPort own) {
            look.add("slots", slotList(own));
            return look;
        }
        if (!(device instanceof CablePort port) || !port.gives()) {
            return look;
        }
        BlockPos pos = port.targetPos();
        if (!where.isLoaded(pos)) {
            throw new CallError(notLoaded(port));
        }
        net.minecraft.world.level.block.state.BlockState state = where.getBlockState(pos);
        look.addProperty("block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        look.addProperty("title", state.getBlock().getName().getString());
        look.addProperty("face", port.accessFace().word());
        if (port.medium() == Medium.FLUID || port.medium() == Medium.UNIVERSAL) {
            try {
                look.add("tanks", tanks(fluidSource(where, device, null)));
            } catch (CallError e) {
                // holds no fluid
            }
        }
        if (port.medium() == Medium.ENERGY || port.medium() == Medium.UNIVERSAL) {
            try {
                JsonArray ask = new JsonArray();
                ask.add("energy");
                ask.add(device.mac());
                look.add("energy", run(level, network, computerPos, ask));
            } catch (CallError e) {
                // holds no energy
            }
        }
        if (port.medium() != Medium.ITEM && port.medium() != Medium.UNIVERSAL) {
            return look;
        }
        ItemPort whole = null;
        try {
            whole = wholeTarget(where, port);
            look.add("slots", slotList(whole));
        } catch (CallError e) {
            // holds no items of its own (a crafting table): its screen may still have slots
        }
        JsonArray faces = new JsonArray();
        net.minecraft.world.level.block.entity.BlockEntity entity = where.getBlockEntity(pos);
        for (AccessFace face : AccessFace.values()) {
            Direction side = face.resolve(port.pointing());
            ItemPort through = Qbotics.platform().itemPort(where, pos, side);
            JsonObject reach = new JsonObject();
            reach.addProperty("face", face.word());
            reach.addProperty("side", side.getSerializedName());
            reach.addProperty("count", through == null ? 0 : through.contents().size());
            if (through != null && entity instanceof net.minecraft.world.WorldlyContainer sided) {
                JsonArray numbers = new JsonArray();
                for (int slot : sided.getSlotsForFace(side)) {
                    numbers.add(slot);
                }
                reach.add("slots", numbers);
            }
            faces.add(reach);
        }
        if (whole != null) {
            look.add("faces", faces);
        }
        if (port.owner() != null) {
            ScreenSessions.Session had = ScreenSessions.of(where, port);
            ScreenSessions.Session session = had != null ? had : ScreenSessions.open(where, port, port.owner());
            if (session != null) {
                try {
                    look.add("screen", screenLayout(session));
                } finally {
                    if (had == null) {
                        ScreenSessions.close(where.getServer(), port.deviceId(), whole == null ? List.of() : List.of(whole));
                    }
                }
            }
        }
        return look;
    }

    /**
     * ["inspect", filter, "filter"]: an item name or filter Ctrl+clicked in the code, or "*" for
     * the editor's suggestions: the items it matches ("matches", at most 200, and "total"; none
     * for "*"), and how many of them each device holding items on the network has ("where", most
     * first) and in all by item ("items", most first). A container reached by two buses counts once.
     */
    private static JsonObject inspectFilter(ServerLevel level, CableNetwork network, String text) {
        if (text == null || text.isBlank()) {
            throw new CallError("An empty name matches nothing.");
        }
        boolean everything = text.strip().equals("*");
        NameMatch<Item> match = everything ? null : item(text);
        JsonObject look = new JsonObject();
        look.addProperty("kind", "filter");
        look.addProperty("filter", text);
        if (!everything) {
            JsonArray matches = new JsonArray();
            int total = 0;
            for (Item item : BuiltInRegistries.ITEM) {
                if (item != net.minecraft.world.item.Items.AIR && match.testStack(new ItemStack(item))) {
                    if (total++ < 200) {
                        matches.add(itemId(item));
                    }
                }
            }
            look.add("matches", matches);
            look.addProperty("total", total);
        }
        java.util.Map<String, Long> byItem = new java.util.HashMap<>();
        List<JsonObject> where = new ArrayList<>();
        java.util.Set<BlockPos> seen = new java.util.HashSet<>();
        for (NetworkDevice device : network.devices()) {
            ItemPort port = ownItems(device);
            if (port == null && device instanceof CablePort plate && plate.gives()
                    && (plate.medium() == Medium.ITEM || plate.medium() == Medium.UNIVERSAL) && seen.add(plate.targetPos())) {
                try {
                    port = wholeTarget(levelOf(device, level), plate);
                } catch (CallError e) {
                    // holds no items, or not loaded
                }
            }
            if (port == null) {
                continue;
            }
            long count = 0;
            for (ItemStack stack : port.contents()) {
                if (!stack.isEmpty() && (match == null || match.testStack(stack))) {
                    count += stack.getCount();
                    byItem.merge(itemId(stack.getItem()), (long) stack.getCount(), Long::sum);
                }
            }
            if (count > 0) {
                JsonObject entry = new JsonObject();
                entry.add("device", describe(levelOf(device, level), network, device));
                entry.addProperty("count", count);
                where.add(entry);
            }
        }
        where.sort((a, b) -> Long.compare(b.get("count").getAsLong(), a.get("count").getAsLong()));
        JsonArray places = new JsonArray();
        where.forEach(places::add);
        look.add("where", places);
        List<java.util.Map.Entry<String, Long>> sorted = new ArrayList<>(byItem.entrySet());
        sorted.sort(java.util.Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(java.util.Map.Entry.comparingByKey()));
        JsonArray items = new JsonArray();
        for (java.util.Map.Entry<String, Long> entry : sorted) {
            JsonObject one = new JsonObject();
            one.addProperty("item", entry.getKey());
            one.addProperty("count", entry.getValue());
            items.add(one);
        }
        look.add("items", items);
        return look;
    }

    /** A screen's layout for the editor to draw it: as the probe numbers its slots (SlotLabels). */
    private static JsonObject screenLayout(ScreenSessions.Session session) {
        net.minecraft.world.inventory.AbstractContainerMenu menu = session.menu;
        JsonObject screen = new JsonObject();
        try {
            screen.addProperty("type", BuiltInRegistries.MENU.getKey(menu.getType()).toString());
        } catch (UnsupportedOperationException e) {
            // a menu with no type of its own: drawn as plain slots
        }
        screen.addProperty("total", menu.slots.size());
        java.util.Set<net.minecraft.world.Container> inventories = new java.util.HashSet<>();
        for (net.minecraft.world.inventory.Slot slot : menu.slots) {
            if (!(slot.container instanceof net.minecraft.world.entity.player.Inventory)) {
                inventories.add(slot.container);
            }
        }
        boolean byScreen = inventories.size() > 1 && !(menu instanceof qbotics.block.InterfaceMenu);
        ItemPort port = session.port();
        JsonArray slots = new JsonArray();
        for (net.minecraft.world.inventory.Slot slot : session.slots()) {
            JsonObject entry = slotEntry(byScreen ? slot.index : slot.getContainerSlot(), slot.getItem(), port.acceptsAny(slot.index));
            entry.addProperty("i", slot.index);
            entry.addProperty("x", slot.x);
            entry.addProperty("y", slot.y);
            slots.add(entry);
        }
        screen.add("slots", slots);
        return screen;
    }

    /**
     * Every slot, empty ones too, as slots() gives them: {"slot", "item" (null when empty),
     * "count", "accepts"}, the same for a bus's container, an interface, a screen and an entity.
     */
    static JsonArray slotList(ItemPort port) {
        JsonArray list = new JsonArray();
        List<ItemStack> stacks = port.contents();
        for (int slot = 0; slot < stacks.size(); slot++) {
            list.add(slotEntry(slot, stacks.get(slot), port.acceptsAny(slot)));
        }
        return list;
    }

    private static JsonObject slotEntry(int slot, ItemStack stack, boolean accepts) {
        JsonObject entry = new JsonObject();
        entry.addProperty("slot", slot);
        if (stack.isEmpty()) {
            entry.add("item", JsonNull.INSTANCE);
        } else {
            entry.addProperty("item", itemId(stack.getItem()));
        }
        entry.addProperty("count", stack.getCount());
        entry.addProperty("accepts", accepts);
        return entry;
    }

    private static JsonArray contents(ItemPort port, @org.jspecify.annotations.Nullable ServerLevel details) {
        JsonArray list = new JsonArray();
        List<ItemStack> stacks = port.contents();
        for (int slot = 0; slot < stacks.size(); slot++) {
            ItemStack stack = stacks.get(slot);
            if (!stack.isEmpty()) {
                JsonObject entry = new JsonObject();
                entry.addProperty("slot", slot);
                entry.addProperty("item", itemId(stack.getItem()));
                entry.addProperty("count", stack.getCount());
                if (details != null) {
                    addDetails(entry, stack, details);
                }
                list.add(entry);
            }
        }
        return list;
    }

    /** A stack's data, the exact filter that names it, and the short filter words that hold for it. */
    private static void addDetails(JsonObject entry, ItemStack stack, ServerLevel level) {
        entry.add("data", ItemData.data(stack, level.registryAccess()));
        entry.addProperty("filter", ItemData.exactFilter(stack, level.registryAccess()));
        JsonArray words = new JsonArray();
        ItemData.words(stack).forEach(words::add);
        entry.add("words", words);
    }
}
