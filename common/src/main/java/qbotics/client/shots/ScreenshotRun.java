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
        then(20, minecraft -> onServer(minecraft, ScreenshotRun::showCamera));
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
        view("08c-server", new Vec3(18.5, 1.7, -2.0), new Vec3(18.5, 0.5, -5.0));
        view("08d-web-panel", new Vec3(22.5, 1.4, -1.6), new Vec3(22.5, 1.0, -5.0));
        then(10, minecraft -> minecraft.setScreen(new qbotics.client.display.WebCloseUpScreen(base.offset(21, 1, -5))));
        then(40, minecraft -> shot(minecraft, "08e-web-close-up"));
        then(5, minecraft -> minecraft.setScreen(null));
        view("08f-qbot-camera", new Vec3(26.0, 1.0, -2.4), new Vec3(26.0, 1.0, -5.0));
        view("08g-script-a", new Vec3(29.5, 1.0, -2.6), new Vec3(29.5, 1.0, -5.0));
        then(25, minecraft -> shot(minecraft, "08g-script-b"));

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
        // The Fluid Interface's screen: water and lava in two tanks, a third kept for water, an empty bucket in a slot.
        then(10, minecraft -> {
            minecraft.setScreen(null);
            onServer(minecraft, player -> {
                if (player.level().getBlockEntity(base.offset(12, 0, -2)) instanceof qbotics.block.FluidInterfaceBlockEntity tanks) {
                    tanks.insertInto(0, net.minecraft.world.level.material.Fluids.WATER, 3000, false);
                    tanks.insertInto(1, net.minecraft.world.level.material.Fluids.LAVA, 1500, false);
                    tanks.setFilter(2, net.minecraft.world.level.material.Fluids.WATER);
                    tanks.buckets().setItem(3, new ItemStack(Items.BUCKET));
                    player.openMenu(tanks);
                }
            });
        });
        then(5, minecraft -> shot(minecraft, "09i-fluid-interface-screen"));
        // The Energy Interface's screen, a little over half full.
        then(10, minecraft -> {
            minecraft.player.closeContainer();
            onServer(minecraft, player -> {
                if (player.level().getBlockEntity(base.offset(14, 0, -2)) instanceof qbotics.block.EnergyInterfaceBlockEntity cell) {
                    cell.insert(1_200_000, false);
                    player.openMenu(cell);
                }
            });
        });
        then(5, minecraft -> shot(minecraft, "09j-energy-interface-screen"));
        // A Drive Bay with two hard drives in it, and its screen.
        then(10, minecraft -> {
            minecraft.player.closeContainer();
            onServer(minecraft, player -> {
                BlockPos at = base.offset(9, 0, -6);
                player.level().setBlock(at, net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(Qbotics.id("drive_bay")).defaultBlockState(),
                        Block.UPDATE_ALL);
                if (player.level().getBlockEntity(at) instanceof qbotics.block.DriveBayBlockEntity bay) {
                    net.minecraft.world.item.Item drive = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(Qbotics.id("hard_drive"));
                    bay.drives().setItem(0, new ItemStack(drive));
                    bay.drives().setItem(1, new ItemStack(drive));
                    player.getInventory().add(new ItemStack(drive, 1));
                    player.openMenu(bay);
                }
            });
        });
        then(5, minecraft -> shot(minecraft, "09k-drive-bay-screen"));
        // Programmers, facing each way the player might see them, and one's screen.
        then(10, minecraft -> {
            minecraft.player.closeContainer();
            onServer(minecraft, player -> {
                net.minecraft.world.level.block.Block programmer = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(Qbotics.id("programmer"));
                Direction[] facings = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP};
                for (int i = 0; i < facings.length; i++) {
                    player.level().setBlock(base.offset(10 + i, 0, -6), programmer.defaultBlockState()
                            .setValue(qbotics.block.ProgrammerBlock.FACING, facings[i]), Block.UPDATE_ALL);
                }
                if (player.level().getBlockEntity(base.offset(10, 0, -6)) instanceof qbotics.block.ProgrammerBlockEntity one) {
                    one.driveSlot().setItem(0, new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(Qbotics.id("hard_drive"))));
                    player.getInventory().add(new ItemStack(programmer.asItem()));
                    player.openMenu(one);
                }
            });
        });
        then(5, minecraft -> shot(minecraft, "09l-programmer-screen"));
        then(5, minecraft -> {
            minecraft.player.closeContainer();
            onServer(minecraft, player -> {
                if (player.level().getBlockEntity(base.offset(10, 0, -6)) instanceof qbotics.block.ProgrammerBlockEntity one) {
                    qbotics.computer.ComputerManager.get(player.level().getServer()).openDriveEditor(player, one);
                }
            });
        });
        then(15, minecraft -> shot(minecraft, "09m-programmer-editor"));
        // An antenna's screen, with both upgrades fitted and chunk loading on.
        then(10, minecraft -> {
            minecraft.setScreen(null);
            onServer(minecraft, player -> {
                BlockPos at = base.offset(16, 0, -6);
                player.level().setBlock(at, QboticsContent.ANTENNA_SHORT_BLOCK.get().defaultBlockState(), Block.UPDATE_ALL);
                if (player.level().getBlockEntity(at) instanceof qbotics.block.AntennaBlockEntity antenna) {
                    antenna.claim(player.getUUID());
                    antenna.upgrades().setItem(0, new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(Qbotics.id("transport_upgrade"))));
                    antenna.upgrades().setItem(1, new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(Qbotics.id("chunk_loader_upgrade"))));
                }
            });
        });
        then(40, minecraft -> onServer(minecraft, player -> {
            if (player.level().getBlockEntity(base.offset(16, 0, -6)) instanceof qbotics.block.AntennaBlockEntity antenna) {
                player.openMenu(antenna);
            }
        }));
        then(10, minecraft -> shot(minecraft, "09n-antenna-screen"));
        then(5, minecraft -> minecraft.player.closeContainer());
        then(5, minecraft -> minecraft.setScreen(null));
        // The Server's hardware screen: a drive in, a Chunk Loader Upgrade fitted.
        then(10, minecraft -> onServer(minecraft, player -> {
            if (player.level().getBlockEntity(base.offset(18, 0, -5)) instanceof qbotics.block.ServerBlockEntity rack) {
                rack.claim(player.getUUID());
                rack.drives().setItem(0, new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(Qbotics.id("hard_drive"))));
                rack.upgrades().setItem(0, new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(Qbotics.id("chunk_loader_upgrade"))));
            }
        }));
        then(40, minecraft -> onServer(minecraft, player -> {
            if (player.level().getBlockEntity(base.offset(18, 0, -5)) instanceof qbotics.block.ServerBlockEntity rack) {
                player.openMenu(rack);
            }
        }));
        then(10, minecraft -> shot(minecraft, "09o-server-screen"));
        then(5, minecraft -> minecraft.player.closeContainer());
        then(5, minecraft -> minecraft.setScreen(null));
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

        // Displays: a 3x2 Text Monitor wall and a 2x2 Control Panel, filled in as a program would.
        then(20, minecraft -> onServer(minecraft, ScreenshotRun::buildDisplays));
        view("17-displays", new Vec3(22.5, 1.6, 5.2), new Vec3(22.5, 1.0, 0.5));
        view("17b-control-panel", new Vec3(25.0, 1.4, 2.6), new Vec3(25.0, 1.0, 0.5));
        then(10, minecraft -> {
            minecraft.options.hideGui = false;
            onServer(minecraft, player -> {
                if (player.level().getBlockEntity(base.offset(24, 1, 0)) instanceof qbotics.block.DisplayBlockEntity panel) {
                    qbotics.display.Displays.openCloseUp(player, panel);
                }
            });
        });
        then(10, minecraft -> shot(minecraft, "17c-panel-close-up"));
        then(5, minecraft -> {
            minecraft.setScreen(null);
            minecraft.options.hideGui = true;
        });
        // An Iron Qbot: standing, mid-step, mid-swing, and its screen.
        then(10, minecraft -> onServer(minecraft, player -> {
            BlockPos at = base.offset(24, 0, 3);
            player.level().setBlock(at, QboticsContent.IRON_QBOT_BLOCK.get().defaultBlockState()
                    .setValue(qbotics.block.ComputerBlock.FACING, Direction.SOUTH), Block.UPDATE_ALL);
            if (player.level().getBlockEntity(at) instanceof qbotics.qbot.QbotBlockEntity qbot) {
                qbot.onPlaced(player.getUUID());
            }
        }));
        view("18-iron-qbot", new Vec3(25.6, 1.5, 6.0), new Vec3(24.5, 0.5, 3.5));
        then(5, minecraft -> onServer(minecraft, player -> {
            if (player.level().getBlockEntity(base.offset(24, 0, 3)) instanceof qbotics.qbot.QbotBlockEntity qbot) {
                qbotics.qbot.Qbots.step(player.level(), qbot, Direction.SOUTH,
                        Qbotics.platform().standIn(player.level(), player.getUUID(), qbot.deviceId(), qbot.getBlockPos(), Direction.SOUTH));
            }
        }));
        then(0, minecraft -> shot(minecraft, "18b-qbot-stepping"));
        then(20, minecraft -> onServer(minecraft, player -> {
            if (player.level().getBlockEntity(base.offset(24, 0, 4)) instanceof qbotics.qbot.QbotBlockEntity qbot) {
                qbot.swingTowards(Direction.SOUTH);
            }
        }));
        then(1, minecraft -> shot(minecraft, "18c-qbot-swing"));
        then(20, minecraft -> {
            minecraft.options.hideGui = false;
            onServer(minecraft, player -> {
                if (player.level().getBlockEntity(base.offset(24, 0, 4)) instanceof qbotics.qbot.QbotBlockEntity qbot) {
                    qbot.items().setItem(0, new ItemStack(Items.COBBLESTONE, 40));
                    qbot.items().setItem(5, new ItemStack(Items.RAW_IRON, 12));
                    qbot.hand().setItem(0, new ItemStack(Items.DIAMOND_PICKAXE));
                    qbot.setEnergy(640_000);
                    qbot.fluidPort().insert(net.minecraft.world.level.material.Fluids.WATER, 3000, false);
                    qbot.fluidPort().insert(net.minecraft.world.level.material.Fluids.LAVA, 1500, false);
                    // A program that reports as it goes, for the strip and the log.
                    qbotics.computer.ServerComputer computer = qbotics.computer.ComputerManager.get(player.level().getServer()).getOrStart(qbot);
                    try {
                        computer.drive().call(qbotics.engine.HostFileSystem.WRITE, "/startup.py", String.join("\n",
                                "import time", "print('miner v2: starting')", "for n in range(1, 40):",
                                "    print('layer', n, '- dug 25, iron 3, coal 7')", "    time.sleep(0.02)",
                                "print('warning: inventory 80% full')", "time.sleep(60)").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    } catch (qbotics.engine.HostFileSystem.FsException e) {
                        throw new IllegalStateException(e);
                    }
                    computer.runProgram();
                    player.openMenu(qbot);
                }
            });
        });
        then(40, minecraft -> shot(minecraft, "18d-qbot-screen"));
        then(2, minecraft -> {
            if (minecraft.screen instanceof qbotics.client.QbotScreen screen) {
                screen.openLog();
                screen.selectLog(30, 33); // a few lines selected, as a drag would
            }
        });
        then(4, minecraft -> shot(minecraft, "18e-qbot-log"));
        // The laptop: in the hand, its two drive slots, and the laptop itself.
        then(5, minecraft -> {
            minecraft.player.closeContainer();
            onServer(minecraft, player -> {
                ItemStack laptop = new ItemStack(QboticsContent.LAPTOP_ITEM.get());
                ItemStack drive = new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(Qbotics.id("hard_drive")));
                drive.set(QboticsContent.MEDIA_ID.get(), java.util.UUID.randomUUID());
                laptop.set(net.minecraft.core.component.DataComponents.CONTAINER,
                        net.minecraft.world.item.component.ItemContainerContents.fromItems(java.util.List.of(drive, ItemStack.EMPTY)));
                player.getInventory().setItem(player.getInventory().getSelectedSlot(), laptop);
                player.openMenu(new net.minecraft.world.SimpleMenuProvider(
                        (id, inventory, who) -> new qbotics.laptop.LaptopMenu(id, inventory, net.minecraft.world.InteractionHand.MAIN_HAND),
                        net.minecraft.network.chat.Component.translatable("screen.qbotics.laptop.disks")));
            });
        });
        then(8, minecraft -> shot(minecraft, "19-laptop-drives"));
        then(5, minecraft -> {
            minecraft.player.closeContainer();
            onServer(minecraft, player -> qbotics.laptop.Laptops.open(player, player.getMainHandItem()));
        });
        then(30, minecraft -> shot(minecraft, "19b-laptop"));
        // A program running on the laptop, paused: the editor opens it at the line it stopped on.
        then(2, minecraft -> onServer(minecraft, player -> {
            qbotics.computer.ServerComputer laptop = qbotics.computer.ComputerManager.get(player.level().getServer()).getOrStartLaptop(player);
            try {
                laptop.drive().call(qbotics.engine.HostFileSystem.WRITE, "/patrol.py", String.join("\n",
                        "import time", "", "def report(n):", "    print('lap', n)", "", "n = 0", "while True:",
                        "    n += 1", "    report(n)", "    time.sleep(0.2)", "").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (qbotics.engine.HostFileSystem.FsException e) {
                throw new IllegalStateException(e);
            }
            laptop.submitRunFile("/patrol.py");
        }));
        then(40, minecraft -> onServer(minecraft, player ->
                qbotics.computer.ComputerManager.get(player.level().getServer()).getOrStartLaptop(player).togglePause()));
        then(30, minecraft -> shot(minecraft, "19c-laptop-paused"));
        then(5, minecraft -> {
            minecraft.setScreen(null);
            minecraft.options.hideGui = true;
        });
        then(10, minecraft -> onServer(minecraft, player -> command(player, "time set midnight")));
        view("17d-displays-at-night", new Vec3(22.5, 1.6, 5.2), new Vec3(22.5, 1.0, 0.5));
        then(10, minecraft -> onServer(minecraft, player -> command(player, "time set noon")));

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
        view("16b-test-stage-back-row", new Vec3(8.5, 3.4, 16.5), new Vec3(8.5, 0.4, 12.0));

        // The items, as the hotbar and the hand draw them.
        then(10, minecraft -> {
            minecraft.options.hideGui = false;
            onServer(minecraft, player -> {
                ItemStack[] items = {
                    new ItemStack(QboticsContent.PROBE_ITEM.get()),
                    new ItemStack(QboticsContent.COMPUTER_BLOCK.get()),
                    new ItemStack(QboticsContent.CABLE_BLOCK.get()),
                    new ItemStack(QboticsContent.INTERFACE_BLOCK.get()),
                    new ItemStack(QboticsContent.plateItem(Kind.BUS, qbotics.network.Medium.ITEM)),
                    new ItemStack(QboticsContent.plateItem(Kind.BUS, qbotics.network.Medium.FLUID)),
                    new ItemStack(QboticsContent.plateItem(Kind.BUS, qbotics.network.Medium.UNIVERSAL)),
                    new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(Qbotics.id("lcd_screen"))),
                    new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(Qbotics.id("integrated_circuit"))),
                    new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(Qbotics.id("ram"))),
                    new ItemStack(QboticsContent.SERVER_BLOCK.get()),
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

    /** Takes the camera's picture once the scene stands, and shows it on the camera panel. */
    private static void showCamera(net.minecraft.server.level.ServerPlayer player) {
        if (player.level().getBlockEntity(base.offset(25, 1, -5)) instanceof qbotics.block.DisplayBlockEntity panel
                && panel.content() instanceof qbotics.display.WebScreen web && player.level() instanceof net.minecraft.server.level.ServerLevel level) {
            com.google.gson.JsonObject picture = qbotics.camera.Camera.snapshot(level, base.offset(9, 2, 5), Direction.NORTH, 96, 72, -12);
            com.google.gson.JsonArray page = new com.google.gson.JsonArray();
            page.add("<html><body style='margin: 6px'><div style='color: #ffb347; font-size: 15px; margin-bottom: 4px'>Qbot camera</div>"
                    + "<img src='image:cam' style='width: 244px; height: 183px; border: 1px solid #3a5068'></body></html>");
            web.op("page", page);
            com.google.gson.JsonArray image = new com.google.gson.JsonArray();
            image.add("cam");
            image.add(picture.get("id"));
            web.op("image", image);
        }
    }

    // ---- the scene ----------------------------------------------------------------------

    /** A drawing call on a display, as a program makes it. */
    private static void draw(qbotics.display.ScreenContent content, String method, Object... args) {
        com.google.gson.JsonArray list = new com.google.gson.JsonArray();
        for (Object arg : args) {
            list.add(arg == null ? com.google.gson.JsonNull.INSTANCE : arg instanceof com.google.gson.JsonElement json ? json
                    : arg instanceof Number number ? new com.google.gson.JsonPrimitive(number) : new com.google.gson.JsonPrimitive(arg.toString()));
        }
        content.op(method, list);
    }

    private static com.google.gson.JsonObject widget(String type, String label, int x, int y, int w, int h, Object... more) {
        com.google.gson.JsonObject spec = new com.google.gson.JsonObject();
        spec.addProperty("type", type);
        spec.addProperty("label", label);
        spec.addProperty("x", x);
        spec.addProperty("y", y);
        spec.addProperty("w", w);
        spec.addProperty("h", h);
        for (int i = 0; i + 1 < more.length; i += 2) {
            if (more[i + 1] instanceof Number number) {
                spec.addProperty((String) more[i], number);
            } else {
                spec.addProperty((String) more[i], more[i + 1].toString());
            }
        }
        return spec;
    }

    private static void buildDisplays(ServerPlayer player) {
        var level = player.level();
        BlockState monitor = QboticsContent.TEXT_MONITOR_BLOCK.get().defaultBlockState().setValue(qbotics.block.DisplayBlock.FACING, Direction.SOUTH);
        BlockState panel = QboticsContent.CONTROL_PANEL_BLOCK.get().defaultBlockState().setValue(qbotics.block.DisplayBlock.FACING, Direction.SOUTH);
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 3; x++) {
                level.setBlock(base.offset(20 + x, y, 0), monitor, Block.UPDATE_ALL);
            }
            for (int x = 0; x < 2; x++) {
                level.setBlock(base.offset(24 + x, y, 0), panel, Block.UPDATE_ALL);
            }
        }
        // Facing south, a screen's right is east: the top-left blocks are the westmost of the top row.
        if (level.getBlockEntity(base.offset(20, 1, 0)) instanceof qbotics.block.DisplayBlockEntity wall && wall.content() != null) {
            var text = wall.content();
            draw(text, "clear", "black");
            draw(text, "fill", 0, 0, 45, 1, "blue");
            draw(text, "write_at", 1, 0, "STORAGE REPORT", "white", "blue");
            draw(text, "write_at", 33, 0, "day 12 06:40", "light_blue", "blue");
            String[][] rows = {{"Iron ingot", "1,984", "lime"}, {"Copper ingot", "612", "lime"}, {"Coal", "48", "yellow"},
                {"Redstone", "3,201", "lime"}, {"Diamond", "7", "orange"}, {"Raw gold", "0", "red"}};
            for (int i = 0; i < rows.length; i++) {
                draw(text, "write_at", 1, 2 + i, rows[i][0], "light_gray", null);
                draw(text, "write_at", 16, 2 + i, rows[i][1], rows[i][2], null);
                int bar = Math.min(24, rows[i][1].replace(",", "").length() * 5);
                draw(text, "fill", 22, 2 + i, bar, 1, rows[i][2], " ");
            }
            draw(text, "write_at", 1, 9, "> furnace 2: smelting raw iron", "white", null);
            draw(text, "write_at", 1, 10, "> crafter: out of sticks", "orange", null);
            draw(text, "write_at", 1, 18, "Touch an item for details", "gray", null);
        }
        if (level.getBlockEntity(base.offset(24, 1, 0)) instanceof qbotics.block.DisplayBlockEntity controls && controls.content() != null) {
            var c = controls.content();
            draw(c, "clear");
            draw(c, "add", widget("label", "Reactor 1", 0, 0, 8, 1, "color", "yellow"));
            draw(c, "add", widget("toggle", "Pump", 0, 1, 3, 1, "value", 1));
            draw(c, "add", widget("lamp", "Overheat", 3, 1, 3, 1, "value", 1, "color", "red"));
            draw(c, "add", widget("button", "SCRAM", 6, 1, 2, 1, "color", "red"));
            draw(c, "add", widget("slider", "Speed", 0, 2, 5, 1, "min", 0, "max", 100, "step", 1, "value", 60, "unit", "%"));
            draw(c, "add", widget("readout", "Temp", 5, 2, 3, 2, "value", 412, "unit", "C"));
            draw(c, "add", widget("gauge", "Power", 0, 3, 5, 1, "min", 0, "max", 1000, "value", 640, "unit", "FE"));
            draw(c, "add", widget("graph", "Load", 0, 4, 8, 4, "min", 0, "max", 100));
            for (int i = 0; i < 60; i++) {
                com.google.gson.JsonObject point = new com.google.gson.JsonObject();
                point.addProperty("value", 50 + 30 * Math.sin(i / 6.0) + (i % 7) * 2);
                draw(c, "set", 8, point);
            }
        }
    }

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
        // The four Serial Buses (item, fluid, energy, universal) facing the camera, and seen from the side.
        qbotics.network.Medium[] buses = {qbotics.network.Medium.ITEM, qbotics.network.Medium.FLUID,
            qbotics.network.Medium.ENERGY, qbotics.network.Medium.UNIVERSAL};
        for (int i = 0; i < buses.length; i++) {
            BlockPos front = base.offset(i * 2, 1, 0);
            level.setBlock(front, cable.defaultBlockState(), Block.UPDATE_ALL);
            CableBlock.attach(level, front, Kind.BUS, buses[i], Direction.SOUTH);
            BlockPos beside = base.offset(i * 2 + 1, 1, -1);
            level.setBlock(beside, cable.defaultBlockState(), Block.UPDATE_ALL);
            CableBlock.attach(level, beside, Kind.BUS, buses[i], Direction.EAST);
        }
        // Tunnel lights: the front row joined by cable, its item bus tunnelled to its USB (both
        // green), and its energy bus tunnelled to an end far away in an unloaded area (amber).
        // The buses seen from the side are in no tunnel (off).
        for (int x = 1; x <= 5; x += 2) {
            level.setBlock(base.offset(x, 1, 0), cable.defaultBlockState(), Block.UPDATE_ALL);
        }
        java.util.function.Function<Integer, qbotics.block.CablePort> frontBus = i ->
                level.getBlockEntity(base.offset(i * 2, 1, 0)) instanceof qbotics.block.PortBlockEntity host ? host.port(Direction.SOUTH) : null;
        qbotics.network.Tunnels.link(level, frontBus.apply(0), level, frontBus.apply(3));
        frontBus.apply(2).setTunnel(new qbotics.network.Tunnels.Link(java.util.UUID.randomUUID(), level.dimension(),
                base.offset(20_000, 1, 0), Direction.NORTH));
        // The world plates: a Logic Pin with redstone dust joined to it, a Placer and a Breaker.
        plated(level, base.offset(12, 0, 0), Kind.PIN, Direction.SOUTH);
        level.setBlock(base.offset(12, 0, 1), Blocks.REDSTONE_WIRE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(base.offset(12, 0, 2), Blocks.REDSTONE_WIRE.defaultBlockState(), Block.UPDATE_ALL);
        plated(level, base.offset(14, 1, 0), Kind.PLACER, Direction.SOUTH);
        plated(level, base.offset(16, 1, 0), Kind.BREAKER, Direction.SOUTH);
        // One cable carrying a plate on every side.
        qbotics.network.Medium[] around = {qbotics.network.Medium.ITEM, qbotics.network.Medium.FLUID, qbotics.network.Medium.ENERGY,
            qbotics.network.Medium.UNIVERSAL, qbotics.network.Medium.ITEM, qbotics.network.Medium.UNIVERSAL};
        level.setBlock(base.offset(2, 1, -4), cable.defaultBlockState(), Block.UPDATE_ALL);
        for (Direction side : Direction.values()) {
            CableBlock.attach(level, base.offset(2, 1, -4), Kind.BUS, around[side.get3DDataValue()], side);
        }

        // A small working network: computer and antenna, cables, a bus under a chest, an interface, an input at a barrel.
        level.setBlock(base.offset(6, 0, -4), QboticsContent.COMPUTER_BLOCK.get().defaultBlockState(), Block.UPDATE_ALL);
        // A bricked computer beside it (over its owner's limit): its screen is dark.
        // Both face the camera, the working one at its side for comparison.
        level.setBlock(base.offset(4, 0, -3), QboticsContent.COMPUTER_BLOCK.get().defaultBlockState()
                .setValue(qbotics.block.ComputerBlock.FACING, Direction.SOUTH), Block.UPDATE_ALL);
        level.setBlock(base.offset(5, 0, -3), QboticsContent.COMPUTER_BLOCK.get().defaultBlockState()
                .setValue(qbotics.block.ComputerBlock.FACING, Direction.SOUTH)
                .setValue(qbotics.block.ComputerBlock.ACTIVE, false), Block.UPDATE_ALL);
        level.setBlock(base.offset(6, 1, -4), QboticsContent.ANTENNA_SHORT_BLOCK.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(base.offset(7, 0, -4), cable.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(base.offset(8, 1, -4), Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        plated(level, base.offset(8, 0, -4), Kind.BUS, Direction.UP);
        level.setBlock(base.offset(9, 0, -4), QboticsContent.INTERFACE_BLOCK.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(base.offset(11, 0, -4), Blocks.BARREL.defaultBlockState(), Block.UPDATE_ALL);
        plated(level, base.offset(10, 0, -4), Kind.BUS, Direction.EAST);
        level.setBlock(base.offset(13, 0, -4), QboticsContent.ANTENNA_LONG_BLOCK.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(base.offset(14, 0, -4), QboticsContent.ANTENNA_INFINITE_BLOCK.get().defaultBlockState(), Block.UPDATE_ALL);
        // The fluid and energy plates (their collars and rings are blue and yellow), and the two stores.
        qbotics.network.Medium[] media = {qbotics.network.Medium.FLUID, qbotics.network.Medium.ENERGY};
        for (int m = 0; m < media.length; m++) {
            BlockPos at = base.offset(m * 6 + 4, 1, -2);
            level.setBlock(at, cable.defaultBlockState(), Block.UPDATE_ALL);
            CableBlock.attach(level, at, Kind.BUS, media[m], Direction.SOUTH);
            CableBlock.attach(level, at, Kind.BUS, media[m], Direction.UP);
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
        // The Server beside a Computer, and a bricked Server, all facing the camera.
        level.setBlock(base.offset(17, 0, -5), QboticsContent.COMPUTER_BLOCK.get().defaultBlockState()
                .setValue(qbotics.block.ComputerBlock.FACING, Direction.SOUTH), Block.UPDATE_ALL);
        // Its lights: two services running, one failed, one slot free.
        level.setBlock(base.offset(18, 0, -5), QboticsContent.SERVER_BLOCK.get().defaultBlockState()
                .setValue(qbotics.block.ComputerBlock.FACING, Direction.SOUTH)
                .setValue(qbotics.block.ServerBlock.LIGHTS[0], 1).setValue(qbotics.block.ServerBlock.LIGHTS[1], 2)
                .setValue(qbotics.block.ServerBlock.LIGHTS[2], 1), Block.UPDATE_ALL);
        level.setBlock(base.offset(19, 0, -5), QboticsContent.SERVER_BLOCK.get().defaultBlockState()
                .setValue(qbotics.block.ComputerBlock.FACING, Direction.SOUTH).setValue(qbotics.block.ComputerBlock.ACTIVE, false), Block.UPDATE_ALL);
        // A 3x2 Web Panel showing a page, as a program would set it.
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 2; j++) {
                BlockPos at = base.offset(21 + i, j, -5);
                level.setBlock(at, QboticsContent.WEB_PANEL_BLOCK.get().defaultBlockState()
                        .setValue(qbotics.block.DisplayBlock.FACING, Direction.SOUTH), Block.UPDATE_ALL);
                if (level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
                    qbotics.display.Displays.reform(serverLevel, at);
                }
            }
        }
        if (level.getBlockEntity(base.offset(21, 1, -5)) instanceof qbotics.block.DisplayBlockEntity webPanel
                && webPanel.content() instanceof qbotics.display.WebScreen web) {
            com.google.gson.JsonArray page = new com.google.gson.JsonArray();
            page.add(String.join("\n",
                    "<html><head><style>",
                    "body { margin: 8px; } h1 { font-size: 18px; margin: 0 0 6px 0; color: #ffb347; }",
                    ".top { display: flex; justify-content: space-between; align-items: center; margin-bottom: 6px; }",
                    ".list { height: 150px; overflow: auto; border: 1px solid #2a3a4a; border-radius: 4px; }",
                    ".row { display: flex; align-items: center; height: 24px; padding: 0 6px; border-bottom: 1px solid #1c2630; }",
                    ".row img { width: 18px; height: 18px; margin-right: 8px; } .name { flex: 1; } .count { color: #ffd27a; font-weight: bold; }",
                    "</style></head><body>",
                    "<div class='top'><h1>Storage</h1><input name='search' placeholder='Search items...'></div>",
                    "<div class='list'>{{#items}}<div class='row'><img src='item:{{id}}'><span class='name'>{{name}}</span>",
                    "<span class='count'>{{count}}</span></div>{{/items}}</div>",
                    "{{^items}}<p>Nothing matches.</p>{{/items}}",
                    "</body></html>"));
            web.op("page", page);
            com.google.gson.JsonArray state = new com.google.gson.JsonArray();
            state.add(com.google.gson.JsonParser.parseString("{\"items\": ["
                    + "{\"id\": \"minecraft:iron_ingot\", \"name\": \"Iron Ingot\", \"count\": 1284},"
                    + "{\"id\": \"minecraft:gold_ingot\", \"name\": \"Gold Ingot\", \"count\": 312},"
                    + "{\"id\": \"minecraft:coal\", \"name\": \"Coal\", \"count\": 4096},"
                    + "{\"id\": \"minecraft:diamond\", \"name\": \"Diamond\", \"count\": 27},"
                    + "{\"id\": \"minecraft:oak_log\", \"name\": \"Oak Log\", \"count\": 640},"
                    + "{\"id\": \"minecraft:redstone\", \"name\": \"Redstone Dust\", \"count\": 2210},"
                    + "{\"id\": \"minecraft:cobblestone\", \"name\": \"Cobblestone\", \"count\": 18000}]}"));
            web.op("state", state);
        }
        // A 2x2 Web Panel showing what a camera at the front of the scene sees, looking back at the setup.
        for (int i = 0; i < 2; i++) {
            for (int j = 0; j < 2; j++) {
                BlockPos at = base.offset(25 + i, j, -5);
                level.setBlock(at, QboticsContent.WEB_PANEL_BLOCK.get().defaultBlockState()
                        .setValue(qbotics.block.DisplayBlock.FACING, Direction.SOUTH), Block.UPDATE_ALL);
                if (level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
                    qbotics.display.Displays.reform(serverLevel, at);
                }
            }
        }
        // A 2x2 Web Panel whose page has a script: a clock, a counter and an animated canvas.
        for (int i = 0; i < 2; i++) {
            for (int j = 0; j < 2; j++) {
                BlockPos at = base.offset(28 + i, j, -5);
                level.setBlock(at, QboticsContent.WEB_PANEL_BLOCK.get().defaultBlockState()
                        .setValue(qbotics.block.DisplayBlock.FACING, Direction.SOUTH), Block.UPDATE_ALL);
                if (level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
                    qbotics.display.Displays.reform(serverLevel, at);
                }
            }
        }
        if (level.getBlockEntity(base.offset(28, 1, -5)) instanceof qbotics.block.DisplayBlockEntity scripted
                && scripted.content() instanceof qbotics.display.WebScreen web) {
            com.google.gson.JsonArray page = new com.google.gson.JsonArray();
            page.add(String.join("\n",
                    "<html><head><style>body { margin: 8px; } h2 { margin: 0 0 6px 0; color: #9cf; font-size: 16px; }",
                    "#count { font-size: 22px; font-weight: bold; color: #ffd27a; } .row { display: flex; justify-content: space-between; }</style></head>",
                    "<body><h2>Reactor</h2><div class='row'><span>Ticks</span><span id='count'>0</span></div>",
                    "<canvas id='c' width='240' height='150'></canvas>",
                    "<script>",
                    "let n = 0; const c = document.getElementById('c'); const g = c.getContext('2d');",
                    "setInterval(() => { n++; document.getElementById('count').textContent = n; }, 250);",
                    "const history = [];",
                    "function frame(t) {",
                    "  history.push(60 + 40 * Math.sin(t / 300) + 10 * Math.sin(t / 77)); if (history.length > 60) history.shift();",
                    "  g.fillStyle = '#0b1a26'; g.fillRect(0, 0, 240, 150);",
                    "  g.strokeStyle = '#1f3a50'; g.lineWidth = 1; for (let y = 0; y < 150; y += 25) { g.beginPath(); g.moveTo(0, y); g.lineTo(240, y); g.stroke(); }",
                    "  g.strokeStyle = '#ffb347'; g.lineWidth = 2; g.beginPath(); history.forEach((v, i) => i ? g.lineTo(i * 4, 150 - v) : g.moveTo(0, 150 - v)); g.stroke();",
                    "  g.save(); g.translate(205, 35); g.rotate(t / 400); g.fillStyle = '#7cf'; g.fillRect(-12, -3, 24, 6); g.restore();",
                    "  g.fillStyle = '#e6e8ea'; g.font = '11px sans-serif'; g.fillText('Output', 6, 14);",
                    "  requestAnimationFrame(frame);",
                    "}",
                    "requestAnimationFrame(frame);",
                    "</script></body></html>"));
            web.op("page", page);
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
