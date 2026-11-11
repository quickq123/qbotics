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
import qbotics.Qbotics;
import qbotics.computer.ComputerManager;
import qbotics.block.AntennaBlockEntity;
import qbotics.block.InterfaceBlockEntity;
import qbotics.block.CablePort;
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
                    Far far = far(level, network, external.group(1));
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
                if (device instanceof InterfaceBlockEntity input) {
                    return contents(input);
                }
                if (device instanceof CablePort port && port.kind() != Kind.OUTPUT) {
                    // With no face named, an input or bus reads the whole container, not only what one face shows.
                    String faceWord = string(args, 2);
                    return contents(faceWord == null ? wholeTarget(level, port) : target(level, port, faceWord));
                }
                throw new CallError("Only an interface, an input or a bus can list contents; this is " + article(device.kind()) + ".");
            }
            case "filters": {
                InterfaceBlockEntity input = input(byMac(network, args, 1));
                JsonArray list = new JsonArray();
                for (int slot = 0; slot < InterfaceBlockEntity.SLOTS; slot++) {
                    Item filter = input.filter(slot);
                    list.add(filter == null ? JsonNull.INSTANCE : new JsonPrimitive(itemId(filter)));
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
     * Arguments: device MAC, item, count, the other end's MAC (or null for any interface), face (or null).
     */
    private static int move(ServerLevel level, CableNetwork network, boolean insert, JsonArray args) {
        CablePort port = port(byMac(network, args, 1));
        if (!insert && port.kind() == Kind.OUTPUT) {
            throw new CallError("An output can only put items in. Use an input or a bus to take items out.");
        }
        if (insert && port.kind() == Kind.INPUT) {
            throw new CallError("An input can only take items out. Use an output or a bus to put items in.");
        }
        Item item = item(args.get(2).getAsString());
        int count = args.get(3).getAsInt();
        if (count < 1) {
            throw new CallError("The count must be 1 or more, not " + count + ".");
        }
        count = Math.min(count, MAX_ITEMS_PER_CALL);
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
        ItemPort target = target(level, port, string(args, 5));
        int moved = 0;
        for (InterfaceBlockEntity input : inputs) {
            if (moved >= count) {
                break;
            }
            moved += insert ? ItemPort.move(input, target, item, count - moved) : ItemPort.move(target, input, item, count - moved);
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

    private static Far far(ServerLevel level, CableNetwork network, String ip) {
        AntennaBlockEntity near = network.gateway();
        if (near == null) {
            throw new CallError("Reaching an external address needs an antenna cabled to this computer.");
        }
        AntennaBlockEntity far = RadioNetwork.get(level.getServer()).byExternalIp(ip);
        if (far == null || !(far.getLevel() instanceof ServerLevel farLevel)) {
            throw new CallError("No antenna with the address " + ip + " can be reached. It may not exist, or its area isn't loaded.");
        }
        String problem = far == near ? null : RadioNetwork.linkProblem(near, far);
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
            Far far = far(level, network, ip);
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
            throw new CallError("Only computers receive messages; " + address + " is " + article(target.kind()) + ".");
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
            if (kindWord == null || device.kind().word.equals(kindWord)) {
                matches.add(device);
            }
        }
        String what = kindWord == null ? "device" : kindWord;
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
                    : "'" + key + "' is " + article(otherKinds.getFirst().kind()) + ", not " + article(what) + ".");
        }
        throw new CallError(matches.size() + " devices are named '" + key + "'. Use an address, or rename one.");
    }

    private static String article(Kind kind) {
        return article(kind.word);
    }

    private static String article(String word) {
        return ("aeiou".indexOf(word.charAt(0)) >= 0 ? "an " : "a ") + word;
    }

    private static InterfaceBlockEntity input(NetworkDevice device) {
        if (device instanceof InterfaceBlockEntity input) {
            return input;
        }
        throw new CallError("That needs an interface; this is " + article(device.kind()) + ".");
    }

    private static CablePort port(NetworkDevice device) {
        if (device instanceof CablePort port) {
            return port;
        }
        throw new CallError("That needs an input, an output or a bus; this is " + article(device.kind()) + ".");
    }

    private static AccessFace face(String word) {
        AccessFace face = AccessFace.parse(word);
        if (face == null) {
            throw new CallError("'" + word + "' isn't a face. Use near, far, top, bottom, left or right.");
        }
        return face;
    }

    /** The item for an ID such as "minecraft:coal"; "coal" alone means the same. */
    private static Item item(String id) {
        Identifier key = Identifier.tryParse(id);
        if (key == null || !BuiltInRegistries.ITEM.containsKey(key) || BuiltInRegistries.ITEM.getValue(key) == Items.AIR) {
            throw new CallError("There is no item called '" + id + "'. Item names look like minecraft:iron_ingot.");
        }
        return BuiltInRegistries.ITEM.getValue(key);
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
            throw new CallError("The " + port.kind().word + " at " + port.ip() + " points at " + block
                    + ", which holds no items" + (face == AccessFace.NEAR ? "." : " that can be reached through its " + face.word() + " face."));
        }
        return target;
    }

    // ---- describing things -----------------------------------------------------------

    private static JsonObject describe(ServerLevel level, CableNetwork network, NetworkDevice device) {
        JsonObject info = new JsonObject();
        info.addProperty("ip", device.ip());
        info.addProperty("mac", device.mac());
        info.addProperty("kind", device.kind().word);
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
            info.addProperty("tier", antenna.tier().word);
            info.addProperty("range", antenna.tier() == RadioNetwork.Tier.INFINITE ? -1 : antenna.tier().radius);
            info.addProperty("router", antenna == gateway);
        } else if (device instanceof CablePort port) {
            info.addProperty("face", port.accessFace().word());
            info.addProperty("target", BuiltInRegistries.BLOCK.getKey(level.getBlockState(port.targetPos()).getBlock()).toString());
        } else if (device instanceof InterfaceBlockEntity) {
            info.addProperty("slots", InterfaceBlockEntity.SLOTS);
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
