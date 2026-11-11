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
    private static final class CallError extends RuntimeException {
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
                if (device instanceof CablePort port && port.kind() != Kind.OUTPUT) {
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
                } else if (device instanceof CablePort port && port.medium() == Medium.ITEM && port.kind() != Kind.OUTPUT) {
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
                InterfaceBlockEntity input = input(byMac(network, args, 1));
                JsonArray list = new JsonArray();
                for (int slot = 0; slot < InterfaceBlockEntity.SLOTS; slot++) {
                    String filter = input.filterText(slot);
                    list.add(filter == null ? JsonNull.INSTANCE : new JsonPrimitive(filter));
                }
                return list;
            }
            case "set_filter": {
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
            case "insert":
            case "extract":
                return new JsonPrimitive(move(level, network, method.equals("insert"), args));
            default:
                throw new CallError("This computer doesn't know the call '" + method + "'.");
        }
    }

    /**
     * insert: from interfaces, through an output or bus, into its target.
     * extract: out of an input's or bus's target, into interfaces.
     * Arguments: device MAC, item (or null for whatever is there), count (or null: 1 of a named
     * item, otherwise all there is), the other end's MAC (or null for any interface), face (or
     * null), slot of the target (or null), slot of the interface (or null).
     */
    private static int move(ServerLevel level, CableNetwork network, boolean insert, JsonArray args) {
        CablePort port = port(byMac(network, args, 1));
        String stuff = switch (port.medium()) {
            case ITEM -> "items";
            case FLUID -> "fluid";
            case ENERGY -> "energy";
        };
        if (!insert && port.kind() == Kind.OUTPUT) {
            throw new CallError("An output can only put " + stuff + " in. Use an input or a bus to take " + stuff + " out.");
        }
        if (insert && port.kind() == Kind.INPUT) {
            throw new CallError("An input can only take " + stuff + " out. Use an output or a bus to put " + stuff + " in.");
        }
        if (port.medium() == Medium.FLUID) {
            return moveFluid(level, network, port, insert, args);
        }
        if (port.medium() == Medium.ENERGY) {
            return moveEnergy(level, network, port, insert, args);
        }
        // No item named means "whatever is there", which is how a furnace's product is taken
        // without knowing what was smelted.
        NameMatch<Item> item = string(args, 2) == null ? null : item(string(args, 2));
        // One named item moves 1 unless a count is given; a filter, like no name at all, moves all there is.
        int count = amount(args, item == null || item.single() == null ? MAX_ITEMS_PER_CALL : 1, MAX_ITEMS_PER_CALL, "items");
        int slot = slot(args, 6);
        int otherSlot = slot(args, 7);
        List<InterfaceBlockEntity> inputs = new ArrayList<>();
        String other = string(args, 4);
        if (other != null) {
            inputs.add(input(byMac(network, args, 4)));
        } else {
            for (NetworkDevice device : network.devices()) {
                if (device instanceof InterfaceBlockEntity input) {
                    inputs.add(input);
                }
            }
            if (inputs.isEmpty()) {
                throw new CallError("There is no interface on this network to " + (insert ? "take items from." : "put items into."));
            }
        }
        // A slot number with no face named counts slots as contents() lists them: through the whole container.
        ItemPort target = slot >= 0 && string(args, 5) == null ? wholeTarget(level, port) : target(level, port, string(args, 5));
        checkSlot(slot, target.contents().size(), "The target", "slot");
        checkSlot(otherSlot, InterfaceBlockEntity.SLOTS, "An interface", "slot");
        int moved = 0;
        for (InterfaceBlockEntity input : inputs) {
            if (moved >= count) {
                break;
            }
            moved += insert ? ItemPort.move(input, otherSlot, target, slot, item, count - moved)
                    : ItemPort.move(target, slot, input, otherSlot, item, count - moved);
        }
        return moved;
    }

    // ---- fluid and energy ----------------------------------------------------------------

    /** The most one call may move: sixteen buckets of fluid, or a whole energy interface's worth. */
    private static final int MAX_FLUID_PER_CALL = 16 * FluidPort.BUCKET;
    private static final int MAX_ENERGY_PER_CALL = EnergyInterfaceBlockEntity.CAPACITY;

    /** The fluid for an ID such as "minecraft:water"; "water" alone means the same. */
    private static NameMatch<Fluid> fluid(String id) {
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

    private static FluidPort fluidTarget(ServerLevel level, CablePort port, String faceWord, boolean whole) {
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
    private static FluidPort fluidSource(ServerLevel level, NetworkDevice device, String faceWord) {
        if (device instanceof FluidInterfaceBlockEntity store) {
            return store;
        }
        if (device instanceof CablePort port && port.kind() != Kind.OUTPUT) {
            return fluidTarget(level, port, faceWord, true);
        }
        throw new CallError("Only a fluid interface, a fluid input or a fluid bus can list contents; this is " + article(device) + ".");
    }

    private static EnergyPort energySource(ServerLevel level, NetworkDevice device, String faceWord) {
        if (device instanceof EnergyInterfaceBlockEntity store) {
            return store;
        }
        if (device instanceof CablePort port && port.medium() == Medium.ENERGY && port.kind() != Kind.OUTPUT) {
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

    /** The count argument of insert and extract, checked and capped; with none given, a default. */
    private static int amount(JsonArray args, int byDefault, int most, String unit) {
        if (args.size() <= 3 || args.get(3).isJsonNull()) {
            return byDefault;
        }
        int count = args.get(3).getAsInt();
        if (count < 1) {
            throw new CallError("The amount must be 1 or more " + unit + ", not " + count + ".");
        }
        return Math.min(count, most);
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

    /** Arguments: device MAC, fluid, millibuckets, the other end's MAC (or null for any fluid interface), face (or null). */
    private static int moveFluid(ServerLevel level, CableNetwork network, CablePort port, boolean insert, JsonArray args) {
        NameMatch<Fluid> fluid = string(args, 2) == null ? null : fluid(string(args, 2));
        int count = amount(args, fluid == null || fluid.single() == null ? MAX_FLUID_PER_CALL : FluidPort.BUCKET, MAX_FLUID_PER_CALL, "millibuckets");
        int tank = slot(args, 6);
        int otherTank = slot(args, 7);
        List<FluidInterfaceBlockEntity> stores = new ArrayList<>();
        if (string(args, 4) != null) {
            if (!(byMac(network, args, 4) instanceof FluidInterfaceBlockEntity store)) {
                throw new CallError("That needs a fluid interface; this is " + article(byMac(network, args, 4)) + ".");
            }
            stores.add(store);
        } else {
            for (NetworkDevice device : network.devices()) {
                if (device instanceof FluidInterfaceBlockEntity store) {
                    stores.add(store);
                }
            }
            if (stores.isEmpty()) {
                throw new CallError("There is no fluid interface on this network to " + (insert ? "take fluid from." : "put fluid into."));
            }
        }
        FluidPort target = fluidTarget(level, port, string(args, 5), tank >= 0);
        checkSlot(tank, target.tanks().size(), "The target", "tank");
        checkSlot(otherTank, FluidInterfaceBlockEntity.TANKS, "A fluid interface", "tank");
        int moved = 0;
        for (FluidInterfaceBlockEntity store : stores) {
            if (moved >= count) {
                break;
            }
            moved += insert ? FluidPort.move(store, otherTank, target, tank, fluid, count - moved)
                    : FluidPort.move(target, tank, store, otherTank, fluid, count - moved);
        }
        return moved;
    }

    /** Arguments: device MAC, null, amount of energy, the other end's MAC (or null for any energy interface), face (or null). */
    private static int moveEnergy(ServerLevel level, CableNetwork network, CablePort port, boolean insert, JsonArray args) {
        int count = amount(args, MAX_ENERGY_PER_CALL, MAX_ENERGY_PER_CALL, "units of energy");
        List<EnergyInterfaceBlockEntity> stores = new ArrayList<>();
        if (string(args, 4) != null) {
            if (!(byMac(network, args, 4) instanceof EnergyInterfaceBlockEntity store)) {
                throw new CallError("That needs an energy interface; this is " + article(byMac(network, args, 4)) + ".");
            }
            stores.add(store);
        } else {
            for (NetworkDevice device : network.devices()) {
                if (device instanceof EnergyInterfaceBlockEntity store) {
                    stores.add(store);
                }
            }
            if (stores.isEmpty()) {
                throw new CallError("There is no energy interface on this network to " + (insert ? "take energy from." : "put energy into."));
            }
        }
        EnergyPort target = energyTarget(level, port, string(args, 5), false);
        int moved = 0;
        for (EnergyInterfaceBlockEntity store : stores) {
            if (moved >= count) {
                break;
            }
            moved += insert ? EnergyPort.move(store, target, count - moved) : EnergyPort.move(target, store, count - moved);
        }
        return moved;
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

    private static String string(JsonArray args, int index) {
        return index >= args.size() || args.get(index).isJsonNull() ? null : args.get(index).getAsString();
    }

    private static NetworkDevice byMac(CableNetwork network, JsonArray args, int index) {
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

    private static String article(NetworkDevice device) {
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
    private static NameMatch<Item> item(String id) {
        return match(BuiltInRegistries.ITEM, id, "item", "minecraft:iron_ingot");
    }

    private static String itemId(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    /** Everything in the container a bus points at, whichever face it would go in or out through. */
    private static ItemPort wholeTarget(ServerLevel level, CablePort port) {
        BlockPos pos = port.targetPos();
        ItemPort whole = level.isLoaded(pos) ? Qbotics.platform().itemPort(level, pos, null) : null;
        return whole != null ? whole : target(level, port, null);
    }

    /** The container a bus or output points at, seen through a face. */
    private static ItemPort target(ServerLevel level, CablePort port, String faceWord) {
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
