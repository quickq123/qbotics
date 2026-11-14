package qbotics.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import qbotics.block.GameInterfaceBlockEntity;
import qbotics.computer.ComputerManager;
import qbotics.network.NetworkCalls.CallError;
import qbotics.network.NetworkDevice.Kind;

/**
 * Programs' calls to the game interfaces: the Player, Chat and Command Interfaces.
 * <p>
 * A Player or Chat Interface works only for its owner's computers: it reaches its owner's
 * inventory, and speaks and runs commands as its owner, with the owner's own permissions.
 * A Command Interface runs commands as a command block does. Only an operator in creative
 * can place one, so one a player placed works only for that player's computers, and only
 * while they are still an operator; one placed by a command or a structure (no owner)
 * works for any computer on its network, as a command block works for any redstone.
 */
public final class GameInterfaceCalls {
    /** Chat's own limit on a message. */
    public static final int MAX_CHAT_CHARS = 256;
    /** A command as long as a command block takes. */
    public static final int MAX_COMMAND_CHARS = 32500;
    /** Messages one Chat Interface may write to chat in a second. */
    public static final int MESSAGES_PER_SECOND = 5;
    /** Lines of a command's output kept for the program. */
    private static final int MAX_OUTPUT_LINES = 100;

    private GameInterfaceCalls() {
    }

    static JsonElement call(String method, ServerLevel level, CableNetwork network, BlockPos computerPos, JsonArray args) {
        GameInterfaceBlockEntity device = device(network, args);
        MinecraftServer server = level.getServer();
        switch (method) {
            case "player_info": {
                need(device, Kind.PLAYER, "Only a player interface knows about its player");
                return playerInfo(device, server);
            }
            case "chat_say": {
                need(device, Kind.CHAT, "Only a chat interface writes to chat");
                ownersComputer(device, server);
                Component line = line(device, text(args, 2));
                written(device, level);
                server.getPlayerList().broadcastSystemMessage(line, false);
                return JsonNull.INSTANCE;
            }
            case "chat_tell": {
                need(device, Kind.CHAT, "Only a chat interface writes to chat");
                ownersComputer(device, server);
                String name = NetworkCalls.string(args, 2);
                ServerPlayer to = name == null ? null : server.getPlayerList().getPlayerByName(name);
                if (to == null) {
                    throw new CallError("No player called '" + name + "' is online.");
                }
                Component line = line(device, text(args, 3));
                written(device, level);
                to.sendSystemMessage(line);
                return JsonNull.INSTANCE;
            }
            case "chat_command": {
                need(device, Kind.CHAT, "Only a chat interface runs commands for its owner");
                ServerPlayer owner = ownersOnly(device, server);
                Capture capture = new Capture(owner);
                CommandSourceStack source = owner.createCommandSourceStack().withSource(capture);
                return run(server, source, command(args), capture);
            }
            case "chat_listen": {
                // ["chat_listen", mac, session]: this computer wants chat pushed to it as events.
                need(device, Kind.CHAT, "Only a chat interface hears chat");
                ownersComputer(device, server);
                device.listen(NetworkCalls.runningAt(level, computerPos).deviceId(), args.get(2).getAsInt());
                return JsonNull.INSTANCE;
            }
            case "command_run": {
                need(device, Kind.COMMAND, "Only a command interface runs commands as a command block");
                ServerLevel at = NetworkCalls.levelOf(device, level);
                commandsAllowed(device, server, at);
                String name = device.deviceName().isEmpty() ? "Command Interface" : device.deviceName();
                Capture capture = new Capture(null);
                CommandSourceStack source = new CommandSourceStack(capture, Vec3.atCenterOf(device.getBlockPos()), Vec2.ZERO, at,
                        LevelBasedPermissionSet.GAMEMASTER, name, Component.literal(name), server, null);
                return run(server, source, command(args), capture);
            }
            default:
                throw new CallError("Unknown call: " + method);
        }
    }

    // ---- the Player Interface ----------------------------------------------------------------

    /**
     * The inventory a Player Interface reaches, for moving, counting and watching. Only its
     * owner's computers may, and only while the owner is online.
     */
    static ItemPort playerItems(GameInterfaceBlockEntity device) {
        MinecraftServer server = device.getLevel() == null ? null : device.getLevel().getServer();
        return new PlayerItemPort(ownersOnly(device, server));
    }

    private static JsonObject playerInfo(GameInterfaceBlockEntity device, MinecraftServer server) {
        JsonObject info = new JsonObject();
        info.addProperty("player", device.ownerName(server));
        ServerPlayer player = device.onlineOwner(server);
        info.addProperty("online", player != null);
        if (player == null) {
            return info;
        }
        ownersComputer(device, server); // where they are and how they are is for their own computers
        info.addProperty("health", player.getHealth());
        info.addProperty("max_health", player.getMaxHealth());
        info.addProperty("food", player.getFoodData().getFoodLevel());
        info.addProperty("saturation", player.getFoodData().getSaturationLevel());
        info.addProperty("level", player.experienceLevel);
        info.addProperty("dimension", player.level().dimension().identifier().toString());
        info.addProperty("x", player.getX());
        info.addProperty("y", player.getY());
        info.addProperty("z", player.getZ());
        info.addProperty("selected", player.getInventory().getSelectedSlot());
        info.addProperty("game_mode", player.gameMode().getName());
        info.addProperty("held", BuiltInRegistries.ITEM.getKey(player.getMainHandItem().getItem()).toString());
        return info;
    }

    // ---- the Chat Interface ------------------------------------------------------------------

    /**
     * A player said something in chat: every program listening to a Chat Interface hears it.
     * Commands players type aren't chat and aren't heard.
     */
    public static void heard(MinecraftServer server, ServerPlayer player, String message) {
        ComputerManager computers = ComputerManager.get(server);
        for (GameInterfaceBlockEntity device : GameInterfaceBlockEntity.hearing()) {
            JsonObject event = new JsonObject();
            event.addProperty("type", "chat");
            event.addProperty("mac", device.mac());
            event.addProperty("name", device.deviceName());
            event.addProperty("player", player.getName().getString());
            event.addProperty("uuid", player.getUUID().toString());
            event.addProperty("message", message);
            device.listeners().entrySet().removeIf(listener -> !computers.pushEvent(listener.getKey(), listener.getValue(), event));
        }
    }

    /** "[Chat Interface] text": who is writing, then what. */
    private static Component line(GameInterfaceBlockEntity device, String text) {
        String name = device.deviceName().isEmpty() ? "Chat Interface" : device.deviceName();
        return Component.literal("[" + name + "] ").withStyle(ChatFormatting.AQUA).append(Component.literal(text).withStyle(ChatFormatting.RESET));
    }

    private static void written(GameInterfaceBlockEntity device, ServerLevel level) {
        if (!device.mayWrite(level.getGameTime(), MESSAGES_PER_SECOND)) {
            throw new CallError("A chat interface writes at most " + MESSAGES_PER_SECOND + " messages a second. Wait a moment and send it again.");
        }
    }

    private static String text(JsonArray args, int index) {
        String text = NetworkCalls.string(args, index);
        if (text == null || text.isBlank()) {
            throw new CallError("There is nothing to write. Give some text.");
        }
        if (text.length() > MAX_CHAT_CHARS) {
            throw new CallError("Chat takes at most " + MAX_CHAT_CHARS + " characters in a message; this has " + text.length() + ".");
        }
        return text.replace('\n', ' ');
    }

    // ---- commands ----------------------------------------------------------------------------

    private static String command(JsonArray args) {
        String command = NetworkCalls.string(args, 2);
        if (command == null || command.isBlank() || command.strip().equals("/")) {
            throw new CallError("There is no command to run. Give one, such as \"time set day\".");
        }
        if (command.length() > MAX_COMMAND_CHARS) {
            throw new CallError("That command is too long (" + command.length() + " characters; the most is " + MAX_COMMAND_CHARS + ").");
        }
        return command.strip();
    }

    /** Runs a command, as typed (a leading / is fine), and answers what it did and what it said. */
    private static JsonObject run(MinecraftServer server, CommandSourceStack source, String command, Capture capture) {
        int[] result = {0};
        int[] successes = {0};
        CommandSourceStack counted = source.withCallback((success, value) -> {
            if (success) {
                successes[0]++;
                result[0] += value;
            }
        });
        server.getCommands().performPrefixedCommand(counted, command);
        JsonObject answer = new JsonObject();
        answer.addProperty("ok", successes[0] > 0);
        answer.addProperty("result", result[0]);
        JsonArray output = new JsonArray();
        capture.lines.forEach(output::add);
        answer.add("output", output);
        return answer;
    }

    private static void commandsAllowed(GameInterfaceBlockEntity device, MinecraftServer server, ServerLevel level) {
        if (!level.isCommandBlockEnabled()) {
            throw new CallError("Command blocks are turned off in this world (the commandBlocksWork game rule), so the command interface does nothing.");
        }
        UUID owner = device.owner();
        if (owner == null) {
            return; // placed by a command or a structure: it works as a command block, for its network
        }
        if (!owner.equals(NetworkCalls.callerOwner())) {
            throw new CallError("This command interface belongs to " + device.ownerName(server) + "; only their computers can use it.");
        }
        if (!server.getProfilePermissions(new NameAndId(owner, device.ownerName(server))).hasPermission(Permissions.COMMANDS_GAMEMASTER)) {
            throw new CallError(device.ownerName(server) + " placed this command interface but is no longer an operator, so it runs nothing.");
        }
    }

    /** What a command says, kept for the program instead of being shown to anyone. */
    private static final class Capture implements CommandSource {
        private final List<String> lines = new ArrayList<>();
        /** The player a command runs for, whose server log and operators hear of it as usual; null for a command block. */
        private final ServerPlayer player;

        Capture(ServerPlayer player) {
            this.player = player;
        }

        @Override
        public void sendSystemMessage(Component message) {
            if (lines.size() < MAX_OUTPUT_LINES) {
                lines.add(message.getString());
            }
        }

        @Override
        public boolean acceptsSuccess() {
            return true;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            return player != null; // as a player's own commands do
        }
    }

    // ---- who may use it ----------------------------------------------------------------------

    /** Checks that the computer calling belongs to the interface's owner (who may be offline). */
    static void ownersComputer(GameInterfaceBlockEntity device, MinecraftServer server) {
        String what = device.kindWord().replace('_', ' ');
        if (device.owner() == null) {
            throw new CallError("This " + what + " wasn't placed by a player, so it has no one to work for. Break it and place it again.");
        }
        if (!device.owner().equals(NetworkCalls.callerOwner())) {
            throw new CallError("This " + what + " belongs to " + device.ownerName(server) + "; only their computers can use it.");
        }
    }

    private static GameInterfaceBlockEntity device(CableNetwork network, JsonArray args) {
        NetworkDevice device = NetworkCalls.byMac(network, args, 1);
        if (device instanceof GameInterfaceBlockEntity game) {
            return game;
        }
        throw new CallError("That needs a player, chat or command interface; this is " + NetworkCalls.article(device) + ".");
    }

    private static void need(GameInterfaceBlockEntity device, Kind kind, String what) {
        if (device.kind() != kind) {
            throw new CallError(what + "; this is " + NetworkCalls.article(device) + ".");
        }
    }

    /**
     * Checks that the computer calling belongs to the interface's owner, and that the owner is
     * online, and answers the owner.
     */
    static ServerPlayer ownersOnly(GameInterfaceBlockEntity device, MinecraftServer server) {
        ownersComputer(device, server);
        String what = device.kindWord().replace('_', ' ');
        ServerPlayer owner = device.onlineOwner(server);
        if (owner == null) {
            throw new CallError(device.ownerName(server) + " isn't online, so their " + what + " can't reach them.");
        }
        return owner;
    }
}
