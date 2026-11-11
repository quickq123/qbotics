package qbotics.client.shots;

import java.util.ArrayDeque;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import qbotics.Qbotics;
import qbotics.QboticsContent;
import qbotics.block.CableBlock;
import qbotics.block.ComputerBlockEntity;
import qbotics.block.InterfaceBlockEntity;
import qbotics.block.ProbeItem;
import qbotics.computer.ComputerManager;
import qbotics.net.ProbePayloads;
import qbotics.network.CableNetwork;
import qbotics.network.NetworkDevice.Kind;

/**
 * A development aid, not part of play: when the game is started with
 * {@code -Dqbotics.screenshots=true} (the {@code runClientShots} Gradle task), it makes a
 * new flat world, builds a fixed scene of the mod's blocks, takes pictures of the scene and
 * of each screen, and quits. The pictures land in the run folder's {@code screenshots}
 * folder, so the mod's look can be checked without anyone playing.
 *
 * <p>It runs in single-player, where the game's server is in the same program, so the scene
 * is built by asking that server directly.
 */
public final class ScreenshotRun {
    public static final boolean ENABLED = Boolean.getBoolean("qbotics.screenshots");
    private static final String WORLD = "qbotics-shots";
    /** Give up after this long, so a run that gets stuck doesn't leave the game open. */
    private static final int MAX_TICKS = 20 * 180;

    private record Step(int waitTicks, Consumer<Minecraft> action) {
    }

    private static final ArrayDeque<Step> STEPS = new ArrayDeque<>();
    private static int ticks;
    private static int waiting;
    private static boolean worldAsked;
    private static boolean planned;
    private static BlockPos base;

    private ScreenshotRun() {
    }

    /** Called once every client tick while the run is switched on. */
    public static void tick(Minecraft minecraft) {
        if (++ticks > MAX_TICKS) {
            Qbotics.LOGGER.error("Screenshot run: timed out; the screen showing was {}", minecraft.screen);
            shot(minecraft, "timeout");
            ticks = Integer.MIN_VALUE;
            STEPS.clear();
            STEPS.add(new Step(20, Minecraft::stop));
            planned = true;
            return;
        }
        if (!planned) {
            if (minecraft.level == null) {
                // Still starting up. Once the game has finished loading and shows a screen, make the world.
                if (!worldAsked && minecraft.getOverlay() == null && minecraft.screen != null && ticks > 40) {
                    worldAsked = true;
                    minecraft.options.pauseOnLostFocus = false;
                    minecraft.options.onboardAccessibility = false;
                    createWorld(minecraft);
                }
                return;
            }
            if (minecraft.player == null || minecraft.getSingleplayerServer() == null) {
                return;
            }
            planned = true;
            plan();
            waiting = 60; // let the world around the player load
            return;
        }
        if (waiting > 0) {
            waiting--;
            return;
        }
        Step step = STEPS.poll();
        if (step == null) {
            return;
        }
        try {
            step.action().accept(minecraft);
        } catch (RuntimeException e) {
            Qbotics.LOGGER.error("Screenshot run: a step failed", e);
        }
        waiting = step.waitTicks();
    }

    private static void createWorld(Minecraft minecraft) {
        LevelSettings settings = new LevelSettings(WORLD, GameType.CREATIVE,
                new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT);
        minecraft.createWorldOpenFlows().createFreshLevel(WORLD, settings, new WorldOptions(1L, false, false),
                WorldPresets::createFlatWorldDimensions, new TitleScreen());
    }

    // ---- the script ---------------------------------------------------------------------

    private static void then(int waitTicks, Consumer<Minecraft> action) {
        STEPS.add(new Step(waitTicks, action));
    }

    /** Runs something on the game's server, which is where blocks may be changed. */
    private static void onServer(Minecraft minecraft, Consumer<ServerPlayer> action) {
        MinecraftServer server = minecraft.getSingleplayerServer();
        server.execute(() -> {
            try {
                action.accept(server.getPlayerList().getPlayers().getFirst());
            } catch (RuntimeException e) {
                Qbotics.LOGGER.error("Screenshot run: a server step failed", e);
            }
        });
    }

    private static void shot(Minecraft minecraft, String name) {
        Screenshot.grab(minecraft.gameDirectory, name + ".png", minecraft.getMainRenderTarget(), 1,
                message -> Qbotics.LOGGER.info("Screenshot run: {}", message.getString()));
    }

    /** Moves the player so their eyes are at one point, looking at another, then takes a picture. */
    private static void view(String name, Vec3 eye, Vec3 target) {
        then(30, minecraft -> onServer(minecraft, player -> {
            Vec3 from = eye.add(base.getX(), base.getY(), base.getZ());
            Vec3 to = target.add(base.getX(), base.getY(), base.getZ());
            Vec3 look = to.subtract(from);
            float yaw = (float) Math.toDegrees(Math.atan2(-look.x, look.z));
            float pitch = (float) -Math.toDegrees(Math.atan2(look.y, Math.sqrt(look.x * look.x + look.z * look.z)));
            player.getAbilities().mayfly = true;
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
            player.teleportTo(player.level(), from.x, from.y - player.getEyeHeight(), from.z, Set.of(), yaw, pitch, false);
        }));
        then(6, minecraft -> shot(minecraft, name));
    }

    private static void plan() {
        then(10, minecraft -> {
            minecraft.options.hideGui = true;
            onServer(minecraft, ScreenshotRun::buildScene);
        });
        // Everything, then each part closer up.
        view("01-overview", new Vec3(6.5, 6.5, 8.5), new Vec3(6.5, 0.5, -2.0));
        view("02-plates-front", new Vec3(2.5, 2.4, 3.0), new Vec3(2.5, 1.5, 0.5));
        view("02b-fluid-and-energy-plates", new Vec3(5.5, 3.4, 1.4), new Vec3(5.5, 1.4, -2.0));
        view("02c-stores", new Vec3(13.5, 2.4, 1.0), new Vec3(13.5, 0.6, -2.0));
        view("03-plates-side", new Vec3(8.5, 2.4, 3.2), new Vec3(8.5, 1.5, 0.5));
        view("03b-world-plates", new Vec3(14.5, 2.6, 3.6), new Vec3(14.5, 0.8, 0.5));
        view("04-plates-above", new Vec3(5.5, 5.0, 2.5), new Vec3(5.5, 1.5, 0.5));
        view("05-six-plates", new Vec3(4.3, 3.0, -1.7), new Vec3(2.5, 1.5, -3.5));
        view("06-six-plates-other-side", new Vec3(0.6, 2.6, -5.4), new Vec3(2.5, 1.5, -3.5));
        view("07-working-setup", new Vec3(9.0, 3.6, 0.2), new Vec3(9.0, 0.7, -3.5));
        view("08-antennas", new Vec3(12.5, 2.8, -0.6), new Vec3(12.5, 1.2, -3.5));
        view("08b-cable-shapes", new Vec3(3.5, 3.4, -4.6), new Vec3(3.5, 0.5, -7.0));

        // The screens.
        then(15, minecraft -> onServer(minecraft, player -> {
            BlockPos at = base.offset(9, 0, -4);
            if (player.level().getBlockEntity(at) instanceof InterfaceBlockEntity store) {
                CableNetwork.at(player.level(), at);
                player.openMenu(store);
            }
        }));
        then(5, minecraft -> shot(minecraft, "09-interface-screen"));
        then(10, minecraft -> minecraft.player.closeContainer());
        // The probe's Slots mode: a furnace, then the Interface, with their slots numbered.
        then(15, minecraft -> onServer(minecraft, player -> {
            ItemStack probe = new ItemStack(QboticsContent.PROBE_ITEM.get());
            probe.set(QboticsContent.PROBE_MODE.get(), ProbeItem.SLOTS);
            player.getInventory().setItem(0, probe);
            BlockPos at = base.offset(9, 0, -6);
            player.level().setBlock(at, Blocks.FURNACE.defaultBlockState(), Block.UPDATE_ALL);
            if (player.level().getBlockEntity(at) instanceof net.minecraft.world.level.block.entity.FurnaceBlockEntity furnace) {
                furnace.setItem(0, new ItemStack(Items.RAW_IRON, 3));
                furnace.setItem(2, new ItemStack(Items.IRON_INGOT, 5));
                player.openMenu(furnace);
            }
        }));
        then(5, minecraft -> shot(minecraft, "09b-furnace-slots"));
        then(10, minecraft -> minecraft.player.closeContainer());
        then(15, minecraft -> onServer(minecraft, player -> {
            if (player.level().getBlockEntity(base.offset(9, 0, -4)) instanceof InterfaceBlockEntity store) {
                player.openMenu(store);
            }
        }));
        then(5, minecraft -> shot(minecraft, "09c-interface-slots"));
        // A crafting table: several inventories on one screen, so the screen's own numbers (product 0, grid 1 to 9).
        then(10, minecraft -> {
            minecraft.player.closeContainer();
            onServer(minecraft, player -> {
                BlockPos at = base.offset(9, 0, -6);
                player.level().setBlock(at, Blocks.CRAFTING_TABLE.defaultBlockState(), Block.UPDATE_ALL);
                player.openMenu(player.level().getBlockState(at).getMenuProvider(player.level(), at));
            });
        });
        then(5, minecraft -> shot(minecraft, "09f-crafting-slots"));
        // A stonecutter's button clicked with the probe in Slots mode: the number pops up, and goes to chat.
        then(10, minecraft -> {
            minecraft.player.closeContainer();
            onServer(minecraft, player -> {
                BlockPos at = base.offset(9, 0, -6);
                player.level().setBlock(at, Blocks.STONECUTTER.defaultBlockState(), Block.UPDATE_ALL);
                player.openMenu(player.level().getBlockState(at).getMenuProvider(player.level(), at));
                player.containerMenu.getSlot(0).set(new ItemStack(Blocks.STONE, 4));
                player.containerMenu.broadcastChanges();
            });
        });
        then(10, minecraft -> {
            // What the stonecutter's screen does when its third choice is clicked.
            minecraft.player.containerMenu.clickMenuButton(minecraft.player, 2);
            minecraft.gameMode.handleInventoryButtonClick(minecraft.player.containerMenu.containerId, 2);
        });
        then(5, minecraft -> shot(minecraft, "09g-button-number"));
        then(5, minecraft -> {
            minecraft.player.closeContainer();
            minecraft.setScreen(new net.minecraft.client.gui.screens.ChatScreen("", false));
        });
        then(5, minecraft -> shot(minecraft, "09h-button-in-chat"));
        then(5, minecraft -> minecraft.setScreen(null));
        // The Experience Interface's screen.
        then(10, minecraft -> {
            minecraft.player.closeContainer();
            onServer(minecraft, player -> {
                if (player.level().getBlockEntity(base.offset(13, 0, -2)) instanceof qbotics.block.ExperienceInterfaceBlockEntity bank) {
                    Qbotics.platform().sendToPlayer(player, bank.info(player));
                }
            });
        });
        then(5, minecraft -> shot(minecraft, "09e-experience-screen"));
        then(10, minecraft -> {
            minecraft.setScreen(null);
            onServer(minecraft, player -> {
                if (player.level().getBlockEntity(base.offset(9, 0, -4)) instanceof InterfaceBlockEntity store) {
                    player.openMenu(store);
                }
            });
        });
        // With any probe in hand, an item's tooltip shows the name programs use. The mouse is
        // put over the Interface's first stored stack (this needs the game's window to be in front).
        then(10, minecraft -> {
            var window = minecraft.getWindow();
            double scale = window.getGuiScale();
            double x = ((window.getGuiScaledWidth() - 176) / 2.0 + 8 + 8) * scale;
            double y = ((window.getGuiScaledHeight() - 150) / 2.0 + 36 + 8) * scale;
            org.lwjgl.glfw.GLFW.glfwSetCursorPos(window.handle(), x, y);
        });
        then(5, minecraft -> shot(minecraft, "09d-item-name-tooltip"));
        then(10, minecraft -> {
            minecraft.player.closeContainer();
            onServer(minecraft, player -> player.getInventory().setItem(0, ItemStack.EMPTY));
        });
        then(15, minecraft -> onServer(minecraft, player -> {
            BlockPos cable = base.offset(8, 0, -4);
            ProbeItem.rename(player.level(), null, new ProbePayloads.Rename(cable, Direction.UP.get3DDataValue(), "smelter"));
            ProbePayloads.Info info = ProbeItem.inspect(player.level(), cable, Vec3.atLowerCornerOf(cable).add(0.5, 0.95, 0.5));
            if (info != null) {
                Qbotics.platform().sendToPlayer(player, info);
            }
        }));
        then(5, minecraft -> shot(minecraft, "10-probe-screen"));
        then(10, minecraft -> minecraft.setScreen(null));
        then(80, minecraft -> onServer(minecraft, player -> {
            if (player.level().getBlockEntity(base.offset(6, 0, -4)) instanceof ComputerBlockEntity computer) {
                ComputerManager.get(player.level().getServer()).openTerminal(player, computer);
            }
        }));
        then(5, minecraft -> shot(minecraft, "11-computer-screen"));
        // The Settings page, opened as the button at the top left does, then an ssh session to a second computer.
        then(25, minecraft -> onServer(minecraft, player -> typeAt(player, "settings")));
        then(5, minecraft -> shot(minecraft, "11b-settings-page"));
        then(60, minecraft -> onServer(minecraft, player -> typeAt(player, "ssh workshop")));
        then(30, minecraft -> onServer(minecraft, player -> typeAt(player, "mkdir made_over_ssh")));
        then(30, minecraft -> onServer(minecraft, player -> typeAt(player, "ls")));
        then(5, minecraft -> shot(minecraft, "11c-ssh-session"));
        then(30, minecraft -> onServer(minecraft, player -> typeAt(player, "exit")));
        then(5, minecraft -> shot(minecraft, "11d-after-ssh"));
        then(10, minecraft -> minecraft.setScreen(null));

        // The same cables at night, to see how much the red core glows by itself.
        then(40, minecraft -> onServer(minecraft, player -> command(player, "time set midnight")));
        view("13-cables-at-night", new Vec3(3.5, 3.4, -4.6), new Vec3(3.5, 0.5, -7.0));
        view("14-setup-at-night", new Vec3(9.0, 3.6, 0.2), new Vec3(9.0, 0.7, -3.5));
        then(40, minecraft -> onServer(minecraft, player -> command(player, "time set noon")));

        // The test stage that /qbotics stage builds, set down beyond the scene.
        then(20, minecraft -> onServer(minecraft, player ->
                qbotics.stage.TestStage.build(player.level(), base.offset(0, 0, 8), player.getUUID())));
        view("15-test-stage", new Vec3(7.0, 7.5, 21.5), new Vec3(7.0, 0.5, 11.5));
        view("16-test-stage-main-row", new Vec3(7.0, 3.2, 13.6), new Vec3(7.0, 0.6, 9.6));

        // The items, as the hotbar and the hand draw them.
        then(10, minecraft -> {
            minecraft.options.hideGui = false;
            onServer(minecraft, player -> {
                ItemStack[] items = {
                    new ItemStack(QboticsContent.PROBE_ITEM.get()),
                    new ItemStack(QboticsContent.COMPUTER_BLOCK.get()),
                    new ItemStack(QboticsContent.CABLE_BLOCK.get()),
                    new ItemStack(QboticsContent.INTERFACE_BLOCK.get()),
                    new ItemStack(QboticsContent.plateItem(Kind.INPUT, qbotics.network.Medium.ITEM)),
                    new ItemStack(QboticsContent.plateItem(Kind.OUTPUT, qbotics.network.Medium.FLUID)),
                    new ItemStack(QboticsContent.plateItem(Kind.BUS, qbotics.network.Medium.ENERGY)),
                    new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(Qbotics.id("screen"))),
                    new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(Qbotics.id("motherboard"))),
                };
                for (int slot = 0; slot < items.length; slot++) {
                    player.getInventory().setItem(slot, items[slot]);
                }
            });
        });
        view("12-items-in-hotbar", new Vec3(6.5, 3.0, 5.0), new Vec3(6.5, 1.0, -2.0));
        then(20, minecraft -> Qbotics.LOGGER.info("Screenshot run: finished"));
        then(0, Minecraft::stop);
    }

    // ---- the scene ----------------------------------------------------------------------

    /** Types a line at the scene's first computer, as a player at its terminal would. */
    private static void typeAt(ServerPlayer player, String line) {
        if (player.level().getBlockEntity(base.offset(6, 0, -4)) instanceof ComputerBlockEntity computer) {
            ComputerManager.get(player.level().getServer()).getOrStart(computer).submitLine(line);
        }
    }

    private static void command(ServerPlayer player, String command) {
        MinecraftServer server = player.level().getServer();
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command);
    }

    /** A cable with one plate on it. */
    private static void plated(ServerLevel level, BlockPos pos, Kind kind, Direction side) {
        level.setBlock(pos, QboticsContent.CABLE_BLOCK.get().defaultBlockState(), Block.UPDATE_ALL);
        CableBlock.attach(level, pos, kind, side);
    }

    private static void buildScene(ServerPlayer player) {
        ServerLevel level = player.level();
        MinecraftServer server = level.getServer();
        for (String command : new String[] {"time set noon", "weather clear", "gamerule advance_time false", "gamerule advance_weather false"}) {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command);
        }
        base = player.blockPosition().offset(2, 0, -6);
        // A dark floor, so the whitish blocks stand out.
        for (int x = -2; x <= 16; x++) {
            for (int z = -8; z <= 4; z++) {
                level.setBlock(base.offset(x, -1, z), Blocks.GRAY_CONCRETE.defaultBlockState(), Block.UPDATE_ALL);
                for (int y = 0; y < 4; y++) {
                    level.setBlock(base.offset(x, y, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        CableBlock cable = QboticsContent.CABLE_BLOCK.get();
        Kind[] kinds = {Kind.INPUT, Kind.OUTPUT, Kind.BUS};
        // One of each plate facing the camera, and one of each seen from the side.
        for (int i = 0; i < kinds.length; i++) {
            plated(level, base.offset(i * 2, 1, 0), kinds[i], Direction.SOUTH);
            plated(level, base.offset(6 + i * 2, 1, 0), kinds[i], Direction.EAST);
        }
        // The world plates: a Logic Pin with redstone dust joined to it, a Placer and a Breaker.
        plated(level, base.offset(12, 0, 0), Kind.PIN, Direction.SOUTH);
        level.setBlock(base.offset(12, 0, 1), Blocks.REDSTONE_WIRE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(base.offset(12, 0, 2), Blocks.REDSTONE_WIRE.defaultBlockState(), Block.UPDATE_ALL);
        plated(level, base.offset(14, 1, 0), Kind.PLACER, Direction.SOUTH);
        plated(level, base.offset(16, 1, 0), Kind.BREAKER, Direction.SOUTH);
        // One cable carrying a plate on every side.
        Kind[] around = {Kind.OUTPUT, Kind.INPUT, Kind.INPUT, Kind.OUTPUT, Kind.BUS, Kind.BUS};
        level.setBlock(base.offset(2, 1, -4), cable.defaultBlockState(), Block.UPDATE_ALL);
        for (Direction side : Direction.values()) {
            CableBlock.attach(level, base.offset(2, 1, -4), around[side.get3DDataValue()], side);
        }

        // A small working network: computer and antenna, cables, a bus under a chest, an interface, an input at a barrel.
        level.setBlock(base.offset(6, 0, -4), QboticsContent.COMPUTER_BLOCK.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(base.offset(6, 1, -4), QboticsContent.ANTENNA_SHORT_BLOCK.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(base.offset(7, 0, -4), cable.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(base.offset(8, 1, -4), Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        plated(level, base.offset(8, 0, -4), Kind.BUS, Direction.UP);
        level.setBlock(base.offset(9, 0, -4), QboticsContent.INTERFACE_BLOCK.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(base.offset(11, 0, -4), Blocks.BARREL.defaultBlockState(), Block.UPDATE_ALL);
        plated(level, base.offset(10, 0, -4), Kind.INPUT, Direction.EAST);
        level.setBlock(base.offset(13, 0, -4), QboticsContent.ANTENNA_LONG_BLOCK.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(base.offset(14, 0, -4), QboticsContent.ANTENNA_INFINITE_BLOCK.get().defaultBlockState(), Block.UPDATE_ALL);
        // The fluid and energy plates (their collars and rings are blue and yellow), and the two stores.
        qbotics.network.Medium[] media = {qbotics.network.Medium.FLUID, qbotics.network.Medium.ENERGY};
        for (int m = 0; m < media.length; m++) {
            for (int i = 0; i < kinds.length; i++) {
                BlockPos at = base.offset(m * 6 + i * 2, 1, -2);
                level.setBlock(at, cable.defaultBlockState(), Block.UPDATE_ALL);
                CableBlock.attach(level, at, kinds[i], media[m], Direction.SOUTH);
                CableBlock.attach(level, at, kinds[i], media[m], Direction.UP);
            }
        }
        level.setBlock(base.offset(12, 0, -2), QboticsContent.FLUID_INTERFACE_BLOCK.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(base.offset(14, 0, -2), QboticsContent.ENERGY_INTERFACE_BLOCK.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(base.offset(13, 0, -2), QboticsContent.EXPERIENCE_INTERFACE_BLOCK.get().defaultBlockState(), Block.UPDATE_ALL);
        if (level.getBlockEntity(base.offset(13, 0, -2)) instanceof qbotics.block.ExperienceInterfaceBlockEntity bank) {
            bank.add(2000);
        }
        // Cable on its own: a straight run, a corner, a T, a lone piece, and one going up.
        int[][] run = {{0, 0, -7}, {1, 0, -7}, {2, 0, -7}, {3, 0, -7}, {3, 0, -6}, {4, 0, -7}, {4, 1, -7}, {6, 0, -7}};
        for (int[] at : run) {
            level.setBlock(base.offset(at[0], at[1], at[2]), cable.defaultBlockState(), Block.UPDATE_ALL);
        }
        // A second computer on the same cables, to log in to.
        level.setBlock(base.offset(7, 0, -5), QboticsContent.COMPUTER_BLOCK.get().defaultBlockState(), Block.UPDATE_ALL);
        if (level.getBlockEntity(base.offset(7, 0, -5)) instanceof ComputerBlockEntity workshop) {
            workshop.setDeviceName("workshop");
        }
        // Blocks set like this skip the placement logic, so give every cable its arms now.
        for (int x = -2; x <= 16; x++) {
            for (int z = -8; z <= 4; z++) {
                for (int y = 0; y < 3; y++) {
                    BlockPos pos = base.offset(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (state.getBlock() instanceof CableBlock) {
                        level.setBlock(pos, CableBlock.withConnections(level, pos, state), Block.UPDATE_ALL);
                    }
                }
            }
        }
        if (level.getBlockEntity(base.offset(9, 0, -4)) instanceof InterfaceBlockEntity store) {
            store.setFilter(0, Items.COAL);
            store.setFilter(1, Items.RAW_IRON);
            store.setFilter(4, Items.IRON_INGOT);
            store.setItem(0, new ItemStack(Items.COAL, 24));
            store.setItem(4, new ItemStack(Items.IRON_INGOT, 7));
        }
    }
}
