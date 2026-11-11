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
            reply.add("r", run(level, network, computerPos, args));
        } catch (CallError e) {
            reply.addProperty("e", e.getMessage());
        } catch (RuntimeException e) {
            // A malformed request (wrong argument types or count), or a fault in a container.
            Qbotics.LOGGER.warn("Network call failed: {}", request, e);
            reply = new JsonObject();
            reply.addProperty("e", "The call failed: its arguments are wrong, or something went wrong in the game ("
                    + e.getClass().getSimpleName() + ").");
        }
        return reply.toString();
    }

    private static JsonElement run(ServerLevel level, CableNetwork network, BlockPos computerPos, JsonArray args) {
        String method = args.get(0).getAsString();
        switch (method) {
            case "self": {
                for (NetworkDevice device : network.devices()) {
                    if (device.getBlockPos().equals(computerPos)) {
                        return describe(level, network, device);
                    }
                }
                throw new CallError("This computer's block is missing.");
            }
            case "devices": {
                // With an address, the devices behind another antenna; with none, this network's own.
                String address = string(args, 1);
                ServerLevel where = level;
                CableNetwork listed = network;
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
                }
                JsonArray list = new JsonArray();
                for (NetworkDevice device : listed.devices()) {
                    list.add(describe(where, listed, device));
                }
                return list;
            }
            case "find":
                return describe(level, network, find(network, string(args, 1), string(args, 2)));
            case "rename": {
                NetworkDevice device = byMac(network, args, 1);
                String wanted = args.get(2).getAsString();
                String name = Addresses.cleanName(wanted);
                if (name.isEmpty() && !wanted.isBlank()) {
                    throw new CallError("A name can use letters, digits, _ - and . (up to " + Addresses.MAX_NAME_LENGTH + " characters).");
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
                if (device instanceof InterfaceBlockEntity input) {
                    return contents(input);
                }
                if (device instanceof CablePort port && port.gives()) {
                    // With no face named, an input or bus reads the whole container, not only what one face shows.
                    String faceWord = string(args, 2);
                    return contents(faceWord == null ? wholeTarget(level, port) : target(level, port, faceWord));
                }
                throw new CallError("Only an interface, an input or a bus can list contents; this is " + article(device) + ".");
            }
            case "energy": {
                // How much energy an energy interface holds, or the machine an energy input or bus points at.
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
                if (device instanceof InterfaceBlockEntity store) {
                    stacks = store.contents();
                } else if (device instanceof CablePort port && port.medium() == Medium.ITEM && port.gives()) {
                    stacks = (string(args, 3) == null ? wholeTarget(level, port) : target(level, port, string(args, 3))).contents();
                } else {
                    throw new CallError("Only an interface, an input or a bus can count what is there; this is " + article(device) + ".");
                }
                for (int i = 0; i < stacks.size(); i++) {
                    ItemStack stack = stacks.get(i);
                    if (!stack.isEmpty() && (slot < 0 || slot == i) && (match == null || match.test(stack.getItem()))) {
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
                if (!(found instanceof qbotics.block.ComputerBlockEntity remote)) {
                    throw new CallError("ssh connects to computers; " + address + " is " + article(found) + ".");
                }
                if (remote.getLevel() == level && remote.getBlockPos().equals(computerPos)) {
                    throw new CallError("That is this computer.");
                }
                JsonObject session = new JsonObject();
                if (method.equals("ssh_open")) {
                    // Gate two, the computer: does its own setting let this computer's owner in?
                    boolean owner = java.util.Objects.equals(ownerAt(level, computerPos), remote.owner());
                    switch (remote.remoteLogin()) {
                        case OFF -> throw new CallError("That computer doesn't allow remote login. Its Settings page can change that.");
                        case OWNER -> {
                            if (!owner) {
                                throw new CallError("That computer only lets its owner's computers log in. Its Settings page can allow a password instead.");
                            }
                        }
                        case PASSWORD -> {
                            if (!owner) {
                                String password = string(args, 2);
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
                    ComputerManager.get(level.getServer()).getOrStart(remote); // switch it on if nobody has yet
                }
                session.addProperty("id", remote.deviceId().toString());
                session.addProperty("label", remote.deviceName().isEmpty() ? address : remote.deviceName());
                session.addProperty("from", from);
                return session;
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
                    java.util.function.Predicate<Item> own = endFilter(end, NetworkCalls::item);
                    List<ItemStack> stacks = port.contents();
                    for (int i = 0; i < stacks.size(); i++) {
                        ItemStack stack = stacks.get(i);
                        if (!stack.isEmpty() && (end.slot() < 0 || end.slot() == i) && (match == null || match.test(stack.getItem()))
                                && own.test(stack.getItem())) {
                            total += stack.getCount();
                        }
                    }
                }
                return new JsonPrimitive(total);
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
            case "watch_open":
                return Watches.open(level, network, computerPos, args);
            case "watch_poll":
                return Watches.poll(level, network, computerPos, args);
            case "watch_close":
                return Watches.close(args);
            case "look", "pin", "pin_set", "place", "pour", "break":
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
    record End(NetworkDevice device, String face, int slot, JsonObject written) {
        String label() {
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
            String mac = spec.get("mac").getAsString();
            NetworkDevice device = network.byMac(mac);
            if (device == null) {
                throw new CallError("The device " + mac + " is no longer on this network.");
            }
            String face = spec.has("face") && !spec.get("face").isJsonNull() ? spec.get("face").getAsString() : null;
            int slot = spec.has("slot") && !spec.get("slot").isJsonNull() ? spec.get("slot").getAsInt() : -1;
            if (slot < -1) {
                throw new CallError("Slots and tanks are numbered from 0; there is no number " + slot + ".");
            }
            ends.add(new End(device, face, slot, spec));
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
    private static List<ScreenSessions.Payer> payers(ServerLevel level, CableNetwork network, @org.jspecify.annotations.Nullable JsonElement written) {
        if (written == null || !written.isJsonArray() || written.getAsJsonArray().isEmpty()) {
            return List.of();
        }
        List<ScreenSessions.Payer> payers = new ArrayList<>();
        for (End end : ends(network, written, "experience source")) {
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
                        + " fluid input or bus; " + label + " is " + article(end.device()) + ".");
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
        if (onScreen(end)) {
            return openScreen(level, screenBus(end.device())).port();
        }
        if (end.device() instanceof InterfaceBlockEntity store) {
            return store;
        }
        if (end.device() instanceof CablePort plate && plate.medium() == Medium.ITEM && plate.gives()) {
            // With no face named, a source is the whole container, as contents() shows it.
            return end.face() == null ? wholeTarget(level, plate) : target(level, plate, end.face());
        }
        throw notASource(end);
    }

    static ItemPort itemDestination(ServerLevel level, End end) {
        if (onScreen(end)) {
            return openScreen(level, screenBus(end.device())).port();
        }
        if (end.device() instanceof InterfaceBlockEntity store) {
            return store;
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
        return new CallError(end.device() instanceof CablePort port && port.kind() == Kind.OUTPUT
                ? "An output can't be a source: it only puts things in. Use an input or a bus."
                : end.label() + " is " + article(end.device()) + ", which can't be a source.");
    }

    private static CallError notADestination(End end) {
        return new CallError(end.device() instanceof CablePort port && port.kind() == Kind.INPUT
                ? "An input can't be a destination: it only takes things out. Use an output or a bus."
                : end.label() + " is " + article(end.device()) + ", which can't be a destination.");
    }

    private interface Step {
        /** Moves (or, in a trial, counts) up to a limit from one source to one destination. */
        long run(int source, int destination, long limit, boolean simulate);
    }

    /**
     * Moves things from sources to destinations (design doc §11.0.5). Sources are drawn on in
     * the order given, and each fills the destinations in the order given, until the amount
     * asked for has moved. Nothing has to pass through an interface: an input's chest can feed
     * an output's furnace directly.
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
        List<End> sources = ends(network, args.get(1), "source");
        List<End> destinations = ends(network, args.get(2), "destination");
        String wanted = string(args, 3);
        Integer count = wholeNumber(args, 4, "count");
        Integer need = wholeNumber(args, 5, "amount needed");
        Medium medium = sources.getFirst().device().medium();
        for (End end : sources) {
            if (end.device().kind() == Kind.COMPUTER || end.device().kind() == Kind.ANTENNA) {
                throw notASource(end);
            }
        }
        for (End end : destinations) {
            if (end.device().kind() == Kind.COMPUTER || end.device().kind() == Kind.ANTENNA) {
                throw notADestination(end);
            }
        }
        for (End end : destinations) {
            if (end.device().medium() != medium) {
                throw new CallError("A move carries one kind of thing: " + sources.getFirst().label() + " handles "
                        + medium.word + (medium == Medium.ITEM ? "s" : "") + " but " + end.label() + " handles " + end.device().medium().word
                        + (end.device().medium() == Medium.ITEM ? "s" : "") + ".");
            }
        }
        for (End end : sources) {
            if (end.device().medium() != medium) {
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
                List<java.util.function.Predicate<Item>> passes = new ArrayList<>();
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
                for (End source : sources) {
                    java.util.function.Predicate<Item> own = endFilter(source, NetworkCalls::item);
                    for (End destination : destinations) {
                        java.util.function.Predicate<Item> theirs = endFilter(destination, NetworkCalls::item);
                        passes.add(thing -> (match == null || match.test(thing)) && own.test(thing) && theirs.test(thing));
                    }
                }
                most = MAX_ITEMS_PER_CALL;
                byDefault = match != null && match.single() != null ? 1 : most;
                step = (s, d, limit, simulate) -> from.get(s) == to.get(d) ? 0 : ItemPort.move(from.get(s), sources.get(s).slot(),
                        to.get(d), destinations.get(d).slot(), passes.get(s * destinations.size() + d), (int) limit, simulate);
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
                byDefault = match != null && match.single() != null ? FluidPort.BUCKET : most;
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
        long moved = sweep(step, sources.size(), destinations.size(), limit, false);
        if (need != null && moved < need) {
            // Several kinds competing for the same free slots can make the trial run optimistic.
            throw new CallError("Only " + moved + " of the " + need + " needed could be moved; the rest had nowhere to go.");
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
                throw new CallError("No " + what + " passes the filter '" + text + "'.");
            }
            List<String> near = NameMatch.closest(registry, text);
            throw new CallError("There is no " + what + " called '" + text + "'. " + (near.isEmpty()
                    ? Character.toUpperCase(what.charAt(0)) + what.substring(1) + " names look like " + examples + "."
                    : "Did you mean " + String.join(near.size() == 2 ? " or " : ", ", near).replaceAll(", ([^,]+)$", " or $1") + "?"));
        }
        return match;
    }

    private static String nothingThere(ServerLevel level, CablePort port, String what) {
        String block = BuiltInRegistries.BLOCK.getKey(level.getBlockState(port.targetPos()).getBlock()).toString();
        return "The " + port.kindWord().replace('_', ' ') + " at " + port.ip() + " points at " + block + ", which holds no " + what + ".";
    }

    /** The side of its target a plate works through: none named reads the whole block, where the block allows it. */
    private static Direction sideFor(CablePort port, String faceWord) {
        return (faceWord == null ? port.accessFace() : face(faceWord)).resolve(port.pointing());
    }

    static FluidPort fluidTarget(ServerLevel level, CablePort port, String faceWord, boolean whole) {
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

    /** What a fluid device reads: a fluid interface's own tanks, or the tanks an input or bus points at. */
    static FluidPort fluidSource(ServerLevel level, NetworkDevice device, String faceWord) {
        if (device instanceof FluidInterfaceBlockEntity store) {
            return store;
        }
        if (device instanceof CablePort port && port.gives()) {
            return fluidTarget(level, port, faceWord, true);
        }
        throw new CallError("Only a fluid interface, a fluid input or a fluid bus can list contents; this is " + article(device) + ".");
    }

    static EnergyPort energySource(ServerLevel level, NetworkDevice device, String faceWord) {
        if (device instanceof EnergyInterfaceBlockEntity store) {
            return store;
        }
        if (device instanceof CablePort port && port.medium() == Medium.ENERGY && port.gives()) {
            return energyTarget(level, port, faceWord, true);
        }
        throw new CallError("Only an energy interface, an energy input or an energy bus can say how much energy there is; this is "
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
    private static final Pattern EXTERNAL = Pattern.compile("(\\d{1,3}(?:\\.\\d{1,3}){3}):(\\d{1,5})");
    /** The same, with the port left out or ignored: an antenna's whole network. */
    private static final Pattern EXTERNAL_OR_IP = Pattern.compile("(\\d{1,3}(?:\\.\\d{1,3}){3})(?::\\d{1,5})?");
    private static final int MAX_MESSAGE_CHARS = 4096;

    /** Another antenna this network can reach by radio, and the network behind it (null if it isn't that network's router). */
    private record Far(AntennaBlockEntity antenna, ServerLevel level, CableNetwork network) {
    }

    /** The owner of the computer that made a call, or null if nobody owns it. */
    /** The running computer whose block is at a position: the one making the call. */
    private static qbotics.computer.ServerComputer runningAt(ServerLevel level, BlockPos computerPos) {
        qbotics.computer.ServerComputer computer = level.getBlockEntity(computerPos) instanceof qbotics.block.ComputerBlockEntity block
                ? qbotics.computer.ComputerManager.get(level.getServer()).computer(block.deviceId()) : null;
        if (computer == null) {
            throw new CallError("This computer isn't running.");
        }
        return computer;
    }

    private static java.util.UUID ownerAt(ServerLevel level, BlockPos computerPos) {
        return level.getBlockEntity(computerPos) instanceof qbotics.block.ComputerBlockEntity computer ? computer.owner() : null;
    }

    private static Far far(ServerLevel level, CableNetwork network, String ip, BlockPos computerPos) {
        AntennaBlockEntity near = network.gateway();
        if (near == null) {
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
        if (external.matches()) {
            String ip = external.group(1);
            Far far = far(level, network, ip, computerPos);
            AntennaBlockEntity near = network.gateway();
            int port = Integer.parseInt(external.group(2));
            target = far.network() != null ? far.network().byHost(port - RadioNetwork.PORT_BASE) : null;
            if (target == null) {
                throw new CallError("Nothing is on port " + port + " of " + ip + ".");
            }
            sender = near.externalIp() + ":" + (RadioNetwork.PORT_BASE + self.hostNumber());
        } else {
            target = find(network, address, null);
            sender = self.ip();
        }
        if (target.kind() != Kind.COMPUTER) {
            throw new CallError("Only computers receive messages; " + address + " is " + article(target) + ".");
        }
        return ComputerManager.get(level.getServer()).deliver(target.deviceId(), sender, data);
    }

    // ---- looking things up -----------------------------------------------------------

    static String string(JsonArray args, int index) {
        return index >= args.size() || args.get(index).isJsonNull() ? null : args.get(index).getAsString();
    }

    static NetworkDevice byMac(CableNetwork network, JsonArray args, int index) {
        NetworkDevice device = network.byMac(args.get(index).getAsString());
        if (device == null) {
            throw new CallError("The device " + args.get(index).getAsString() + " is no longer on this network.");
        }
        return device;
    }

    /** A device by IP address, hardware address or name; with no key, the only device of a kind. */
    private static NetworkDevice find(CableNetwork network, String key, String kindWord) {
        List<NetworkDevice> matches = new ArrayList<>();
        for (NetworkDevice device : key == null ? network.devices() : network.find(key)) {
            if (kindWord == null || device.kindWord().equals(kindWord)) {
                matches.add(device);
            }
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
        throw new CallError("That needs an input, an output or a bus; this is " + article(device) + ".");
    }

    private static AccessFace face(String word) {
        AccessFace face = AccessFace.parse(word);
        if (face == null) {
            throw new CallError("'" + word + "' isn't a face. Use near, far, top, bottom, left or right.");
        }
        return face;
    }

    /** The item for an ID such as "minecraft:coal"; "coal" alone means the same. */
    /** The item, or the filter of items, for what a program wrote: "minecraft:coal", "coal", "#minecraft:logs", "@create". */
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
        return whole != null ? whole : target(level, port, null);
    }

    /** The container a bus or output points at, seen through a face. */
    static ItemPort target(ServerLevel level, CablePort port, String faceWord) {
        AccessFace face = faceWord == null ? port.accessFace() : face(faceWord);
        BlockPos pos = port.targetPos();
        Direction side = face.resolve(port.pointing());
        ItemPort target = level.isLoaded(pos) ? Qbotics.platform().itemPort(level, pos, side) : null;
        if (target == null) {
            String block = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString();
            throw new CallError("The " + port.kindWord().replace('_', ' ') + " at " + port.ip() + " points at " + block
                    + ", which holds no items" + (face == AccessFace.NEAR ? "." : " that can be reached through its " + face.word() + " face."));
        }
        return target;
    }

    // ---- describing things -----------------------------------------------------------

    private static JsonObject describe(ServerLevel level, CableNetwork network, NetworkDevice device) {
        JsonObject info = new JsonObject();
        info.addProperty("ip", device.ip());
        info.addProperty("mac", device.mac());
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
        } else if (device instanceof CablePort port) {
            info.addProperty("face", port.accessFace().word());
            info.addProperty("target", BuiltInRegistries.BLOCK.getKey(level.getBlockState(port.targetPos()).getBlock()).toString());
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

    private static JsonArray contents(ItemPort port) {
        JsonArray list = new JsonArray();
        List<ItemStack> stacks = port.contents();
        for (int slot = 0; slot < stacks.size(); slot++) {
            ItemStack stack = stacks.get(slot);
            if (!stack.isEmpty()) {
                JsonObject entry = new JsonObject();
                entry.addProperty("slot", slot);
                entry.addProperty("item", itemId(stack.getItem()));
                entry.addProperty("count", stack.getCount());
                list.add(entry);
            }
        }
        return list;
    }
}
