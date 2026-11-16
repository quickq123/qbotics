package qbotics.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import qbotics.QboticsConfig;
import qbotics.computer.ComputerManager;
import qbotics.computer.ServerComputer;
import qbotics.engine.HostFileSystem;
import qbotics.gateway.SshGateway;
import qbotics.ide.Paths;
import qbotics.net.AgentPayloads;
import qbotics.network.NetworkCalls;

/**
 * What the in-game AI agent can do in the world (design doc §17.8). The agent runs in the
 * player's game; each tool call is done here, as that player: only their own computers (and
 * their laptop), as over the SSH gateway. Writing a file and running a command are asked of
 * the player first, in their game, unless they turned auto mode on.
 *
 * <p>Answers are text for the model. Mistakes are answered too ("No computer called x"), so
 * the model can correct itself, rather than failing the call.
 */
public final class AgentTools {
    private AgentTools() {
    }

    /** A command the agent runs is stopped after this long (a test, not a program left running). */
    public static final int RUN_SECONDS = 30;
    private static final Executor WORKER = Executors.newVirtualThreadPerTaskExecutor();

    /** Runs a tool for a player. Called on the server thread; the answer may come later. */
    public static CompletableFuture<String> call(ServerPlayer player, String tool, String argsJson) {
        if (!QboticsConfig.aiEnabled) {
            return CompletableFuture.completedFuture("The server's admin has turned the AI agent's tools off (ai.enabled).");
        }
        JsonObject args;
        try {
            args = argsJson.isBlank() ? new JsonObject() : JsonParser.parseString(argsJson).getAsJsonObject();
        } catch (RuntimeException e) {
            return CompletableFuture.completedFuture("The arguments weren't a JSON object: " + argsJson);
        }
        try {
            return switch (tool) {
                case "computers" -> CompletableFuture.completedFuture(computers(player));
                case "look" -> CompletableFuture.completedFuture(look(player));
                case "devices" -> CompletableFuture.completedFuture(devices(player, args));
                case "list_files", "read_file", "write_file" -> files(player, tool, args);
                case "run" -> run(player, args);
                case "python" -> python(player, args);
                case "stop_program" -> computer(player, args).computer().stopForAgent();
                case "start_program" -> CompletableFuture.completedFuture(computer(player, args).computer().startForAgent(text(args, "command")));
                case "find_items", "item_info", "recipes" -> knowledge(player, tool, args);
                case "plan" -> plan(player, args);
                default -> CompletableFuture.completedFuture("There is no tool called " + tool + ".");
            };
        } catch (Problem problem) {
            return CompletableFuture.completedFuture(problem.getMessage());
        }
    }

    /** A mistake in a call, told to the model. */
    private static final class Problem extends RuntimeException {
        Problem(String message) {
            super(message, null, false, false);
        }
    }

    private static String text(JsonObject args, String name) {
        return args.has(name) && args.get(name).isJsonPrimitive() ? args.get(name).getAsString() : "";
    }

    // ---- which computer --------------------------------------------------------------

    /**
     * The computer a call is about: by name ("laptop", a computer's name or hardware address),
     * or, with none given, the one the player opened last, else their only one.
     */
    private static SshGateway.Target computer(ServerPlayer player, JsonObject args) {
        MinecraftServer mc = player.level().getServer();
        List<SshGateway.Target> all = SshGateway.computersOf(mc, player.getUUID());
        String name = text(args, "computer").strip();
        if (name.isEmpty()) {
            UUID last = ComputerManager.get(mc).lastOpened(player.getUUID());
            for (SshGateway.Target target : all) {
                if (target.deviceId().equals(last) && target.computer() != null) {
                    return target;
                }
            }
            List<SshGateway.Target> running = all.stream().filter(t -> t.computer() != null).toList();
            if (running.size() == 1) {
                return running.getFirst();
            }
            throw new Problem("Say which computer: " + names(all) + ".");
        }
        for (SshGateway.Target target : all) {
            if (target.label().equalsIgnoreCase(name) || target.deviceId().toString().equals(name)) {
                if (target.computer() == null) {
                    throw new Problem(target.laptop() ? "The laptop is off: the player opens it once in game, then it runs while they carry it."
                            : name + " isn't running (its area must be loaded).");
                }
                return target;
            }
        }
        throw new Problem("No computer of the player's is called " + name + ". There are: " + names(all) + ".");
    }

    private static String names(List<SshGateway.Target> all) {
        return String.join(", ", all.stream().map(t -> t.label() + (t.computer() == null ? " (off)" : "")).toList());
    }

    private static String computers(ServerPlayer player) {
        MinecraftServer mc = player.level().getServer();
        UUID last = ComputerManager.get(mc).lastOpened(player.getUUID());
        JsonArray list = new JsonArray();
        for (SshGateway.Target target : SshGateway.computersOf(mc, player.getUUID())) {
            JsonObject one = new JsonObject();
            one.addProperty("name", target.label());
            one.addProperty("kind", target.laptop() ? "laptop" : "computer");
            one.addProperty("running", target.computer() != null);
            one.addProperty("opened_last", target.deviceId().equals(last));
            // What it is running now: a busy computer can't run the helper's tests until that stops.
            String busyWith = target.computer() == null ? null : target.computer().running();
            if (busyWith != null) {
                one.addProperty("running_program", busyWith);
            }
            list.add(one);
        }
        return list.toString();
    }

    // ---- the world -------------------------------------------------------------------

    /** What the player is looking at: the block, a device on it, what a plate points at, what a container holds. */
    private static String look(ServerPlayer player) {
        HitResult hit = player.pick(8.0, 0, false);
        if (!(hit instanceof BlockHitResult block) || hit.getType() != HitResult.Type.BLOCK) {
            return "The player isn't looking at a block (nothing within 8 blocks).";
        }
        ServerLevel level = (ServerLevel) player.level();
        BlockPos pos = block.getBlockPos();
        JsonObject info = new JsonObject();
        info.addProperty("block", BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString());
        info.addProperty("position", pos.getX() + " " + pos.getY() + " " + pos.getZ());
        info.addProperty("face_looked_at", block.getDirection().getName());
        var device = qbotics.block.ProbeItem.inspect(level, pos, hit.getLocation());
        if (device != null) {
            JsonObject d = new JsonObject();
            d.addProperty("kind", device.kind());
            d.addProperty("name", device.name());
            d.addProperty("ip", device.ip());
            d.addProperty("mac", device.mac());
            if (!device.external().isEmpty()) {
                d.addProperty("external", device.external());
            }
            if (device.side() >= 0) {
                // A plate on a cable: the block it works on.
                BlockPos target = pos.relative(Direction.from3DDataValue(device.side()));
                BlockState state = level.getBlockState(target);
                d.addProperty("points_at", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
                d.addProperty("points_at_side", device.side() >= 0 ? Direction.from3DDataValue(device.side()).getOpposite().getName() : "");
                contents(level, target, d);
            }
            info.add("device", d);
        }
        contents(level, pos, info);
        return info.toString();
    }

    private static void contents(ServerLevel level, BlockPos pos, JsonObject into) {
        if (level.getBlockEntity(pos) instanceof Container container) {
            JsonArray slots = new JsonArray();
            for (int i = 0; i < container.getContainerSize() && slots.size() < 54; i++) {
                ItemStack stack = container.getItem(i);
                if (!stack.isEmpty()) {
                    slots.add(i + ": " + stack.getCount() + " " + BuiltInRegistries.ITEM.getKey(stack.getItem()));
                }
            }
            into.add("contents", slots);
        }
    }

    private static String devices(ServerPlayer player, JsonObject args) {
        SshGateway.Target target = computer(player, args);
        MinecraftServer mc = player.level().getServer();
        for (ComputerManager.Running running : ComputerManager.get(mc).running()) {
            if (running.deviceId().equals(target.deviceId())) {
                ServerLevel level = mc.getLevel(running.dimension());
                if (level != null) {
                    return NetworkCalls.describeAll(level, running.pos()).toString();
                }
            }
        }
        if (target.laptop()) {
            return "A laptop has no cable network of its own: it reaches other networks through its antenna (network module).";
        }
        throw new Problem(target.label() + " isn't loaded.");
    }

    // ---- files and commands ---------------------------------------------------------

    private static CompletableFuture<String> files(ServerPlayer player, String tool, JsonObject args) {
        SshGateway.Target target = computer(player, args);
        HostFileSystem drive = target.computer().drive();
        String path = Paths.resolve("/", text(args, tool.equals("list_files") ? "folder" : "path").isEmpty() && tool.equals("list_files")
                ? "/" : text(args, tool.equals("list_files") ? "folder" : "path"));
        String content = text(args, "content");
        return CompletableFuture.supplyAsync(() -> {
            try {
                switch (tool) {
                    case "list_files": {
                        String listing = new String(drive.call(HostFileSystem.LIST, path, new byte[0]), StandardCharsets.UTF_8);
                        StringBuilder out = new StringBuilder();
                        for (String line : listing.split("\n")) {
                            String[] parts = line.split("\t");
                            if (parts.length == 3) {
                                out.append(parts[0].equals("d") ? parts[2] + "/" : parts[2] + " (" + parts[1] + " bytes)").append('\n');
                            }
                        }
                        return out.isEmpty() ? path + " is empty." : out.toString();
                    }
                    case "read_file": {
                        byte[] data = drive.call(HostFileSystem.READ, path, new byte[0]);
                        String text = new String(data, StandardCharsets.UTF_8);
                        return text.length() > AgentPayloads.MAX_TEXT - 200 ? text.substring(0, AgentPayloads.MAX_TEXT - 200) + "\n...(cut short)" : text;
                    }
                    default: {
                        drive.call(HostFileSystem.WRITE, path, content.getBytes(StandardCharsets.UTF_8));
                        return "Wrote " + path + " (" + content.lines().count() + " lines) on " + target.label() + ".";
                    }
                }
            } catch (HostFileSystem.FsException e) {
                return switch (e.errno) {
                    case HostFileSystem.ENOENT -> path + " doesn't exist.";
                    case HostFileSystem.ENOSPC, HostFileSystem.EFBIG -> "The drive is full.";
                    case HostFileSystem.EISDIR -> path + " is a folder.";
                    case HostFileSystem.ENOTDIR -> path + " is a file, not a folder.";
                    case HostFileSystem.EINVAL -> "That name isn't allowed: letters, digits and . _ - only.";
                    default -> "The drive said no (error " + e.errno + ").";
                };
            }
        }, WORKER);
    }

    /** Items and recipes, as this world has them (GameKnowledge); read off the server's thread. */
    private static CompletableFuture<String> knowledge(ServerPlayer player, String tool, JsonObject args) {
        MinecraftServer server = player.level().getServer();
        return CompletableFuture.supplyAsync(() -> {
            try {
                return switch (tool) {
                    case "find_items" -> GameKnowledge.findItems(text(args, "query"), 40).toString();
                    case "item_info" -> GameKnowledge.itemInfo(server, text(args, "item")).toString();
                    default -> "Recipes as this game has them. Items are mod:name; \"#...\" is a tag (any item carrying it); "
                            + "type says the table or machine.\n"
                            + GameKnowledge.recipes(server, text(args, "item"), "true".equalsIgnoreCase(text(args, "uses")), 8);
                };
            } catch (IllegalArgumentException e) {
                return e.getMessage();
            }
        }, WORKER);
    }

    /** How to make an item from what the player carries (the same planner as game.plan). */
    private static CompletableFuture<String> plan(ServerPlayer player, JsonObject args) {
        MinecraftServer server = player.level().getServer();
        java.util.Map<String, Long> carried = new java.util.HashMap<>();
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
            if (!stack.isEmpty()) {
                carried.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), (long) stack.getCount(), Long::sum);
            }
        }
        long count;
        try {
            count = Math.max(1, Long.parseLong(text(args, "count").isEmpty() ? "1" : text(args, "count")));
        } catch (NumberFormatException e) {
            count = 1;
        }
        long wanted = count;
        return CompletableFuture.supplyAsync(() -> {
            try {
                JsonObject plan = CraftPlanner.plan(server, text(args, "item"), wanted, carried, java.util.Set.of());
                // The steps without their full recipes: enough for the model, much shorter.
                for (com.google.gson.JsonElement step : plan.getAsJsonArray("steps")) {
                    step.getAsJsonObject().remove("craft");
                }
                return "From what the player carries:\n" + plan;
            } catch (IllegalArgumentException e) {
                return e.getMessage();
            }
        }, WORKER);
    }

    /**
     * The helper's scratchpad: a Python snippet run on a computer without touching its files.
     * Look-only unless "change" is "true" (which the player is asked to allow first, in their game):
     * then it only reads, so the helper can find out how things stand by trying.
     */
    private static CompletableFuture<String> python(ServerPlayer player, JsonObject args) {
        SshGateway.Target target = computer(player, args);
        String code = text(args, "code");
        if (code.isBlank()) {
            throw new Problem("Give the Python to run, e.g. \"from logistics import network\\nprint(network().devices())\".");
        }
        boolean change = text(args, "change").equalsIgnoreCase("true");
        String busyWith = target.computer().running();
        if (busyWith != null) {
            return CompletableFuture.completedFuture("Busy: it is running \"" + busyWith + "\". Ask the player to let you stop it "
                    + "(stop_program), try your code, then start it again (start_program); or use another of their computers.");
        }
        return target.computer().scratchForAgent(code, !change, RUN_SECONDS).thenApply(result ->
                (result.status() == 0 ? "ran" : "failed") + (change ? "" : " (look-only)") + "\n" + result.output())
                .completeOnTimeout("The code didn't finish in time.", RUN_SECONDS + 15, TimeUnit.SECONDS);
    }

    private static CompletableFuture<String> run(ServerPlayer player, JsonObject args) {
        SshGateway.Target target = computer(player, args);
        String command = text(args, "command").strip();
        if (command.isEmpty()) {
            throw new Problem("Give the command to run, e.g. \"run test.py\".");
        }
        return target.computer().exec(command, RUN_SECONDS).thenApply(result ->
                "status " + result.status() + (result.status() == 0 ? " (worked)" : " (failed)") + "\n" + result.output())
                .completeOnTimeout("The command didn't finish in time.", RUN_SECONDS + 15, TimeUnit.SECONDS);
    }
}
