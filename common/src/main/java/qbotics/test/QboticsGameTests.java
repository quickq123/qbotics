package qbotics.test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import qbotics.QboticsContent;
import qbotics.block.AntennaBlockEntity;
import qbotics.block.CableBlock;
import qbotics.block.CablePort;
import qbotics.block.ComputerBlockEntity;
import qbotics.block.InterfaceBlockEntity;
import qbotics.network.NetworkDevice.Kind;
import qbotics.block.PortBlockEntity;
import qbotics.network.AccessFace;
import qbotics.network.CableNetwork;
import qbotics.network.NetworkDevice;
import qbotics.network.RadioNetwork;
import qbotics.computer.ComputerManager;
import qbotics.computer.ServerComputer;
import qbotics.computer.Terminal;
import qbotics.net.TerminalInputPayload;
import qbotics.net.TerminalStatePayload;

/**
 * In-game tests, run by each loader's game test server (NeoForge: gradlew :neoforge:runGameTestServer).
 * They use only Minecraft's own test API, so every loader can register the same functions.
 */
public final class QboticsGameTests {
    private static final BlockPos POS = new BlockPos(1, 1, 1);

    /** Test name -> test body. Each runs in the "qbotics:empty" structure. */
    /**
     * Tests whose programs wait in real time (a pin waiting for a change, a breaker working at
     * a block) get more ticks than the usual 1,200: the test server runs ticks far faster than
     * 20 a second (10,000 a second when little is happening), so 1,200 can pass in a fraction of a
     * second. A test still ends as soon as it passes.
     */
    public static final Map<String, Integer> TICK_LIMITS = Map.ofEntries(Map.entry("world_plates", 200_000), Map.entry("test_stage", 200_000), Map.entry("watching", 200_000), Map.entry("events_module", 200_000), Map.entry("drive_bays", 200_000), Map.entry("programmer_block", 200_000), Map.entry("displays", 200_000), Map.entry("antenna_upgrades", 200_000), Map.entry("iron_qbot", 200_000), Map.entry("ssh_gateway", 200_000), Map.entry("uplink", 200_000), Map.entry("git_offline", 200_000), Map.entry("server_block", 200_000), Map.entry("server_services", 200_000), Map.entry("server_chunk_loading", 200_000), Map.entry("web_panel", 200_000), Map.entry("web_storage", 200_000), Map.entry("qbot_camera", 200_000), Map.entry("helpme", 200_000), Map.entry("helpme_storage", 200_000), Map.entry("laptop_connect", 200_000), Map.entry("step_through", 200_000), Map.entry("step_through_laptop", 200_000), Map.entry("qbot_program_controls", 200_000), Map.entry("computer_interrupt_ignored", 200_000), Map.entry("computer_interrupt_sleep", 200_000));

    public static final Map<String, Consumer<GameTestHelper>> ALL = Map.ofEntries(
            Map.entry("cable_recipes", QboticsGameTests::cableRecipes),
            Map.entry("cable_connects", QboticsGameTests::cableConnects),
            Map.entry("computer_repl", QboticsGameTests::computerRepl),
            Map.entry("computer_interrupt", QboticsGameTests::computerInterrupt),
            Map.entry("universal_serial_bus", QboticsGameTests::universalSerialBus),
            Map.entry("bus_tunnels", QboticsGameTests::busTunnels),
            Map.entry("laptop", QboticsGameTests::laptop),
            Map.entry("laptop_connect", QboticsGameTests::laptopConnect),
            Map.entry("step_through", QboticsGameTests::stepThrough),
            Map.entry("ssh_gateway", QboticsGameTests::sshGateway),
            Map.entry("uplink", QboticsGameTests::uplink),
            Map.entry("git_offline", QboticsGameTests::gitOffline),
            Map.entry("server_block", QboticsGameTests::serverBlock),
            Map.entry("server_services", QboticsGameTests::serverServices),
            Map.entry("server_chunk_loading", QboticsGameTests::serverChunkLoading),
            Map.entry("web_page", QboticsGameTests::webPage),
            Map.entry("web_panel", QboticsGameTests::webPanel),
            Map.entry("web_storage", QboticsGameTests::webStorage),
            Map.entry("qbot_camera", QboticsGameTests::qbotCamera),
            Map.entry("js_engine", QboticsGameTests::jsEngine),
            Map.entry("web_script", QboticsGameTests::webScript),
            Map.entry("helpme", QboticsGameTests::helpme),
            Map.entry("helpme_storage", QboticsGameTests::helpmeStorage),
            Map.entry("step_through_laptop", QboticsGameTests::stepThroughLaptop),
            Map.entry("qbot_program_controls", QboticsGameTests::qbotProgramControls),
            Map.entry("computer_interrupt_sleep", QboticsGameTests::computerInterruptSleep),
            Map.entry("computer_interrupt_ignored", QboticsGameTests::computerInterruptIgnored),
            Map.entry("computer_forgets_calls", QboticsGameTests::computerForgetsCalls),
            Map.entry("computer_recipe", QboticsGameTests::computerRecipe),
            Map.entry("computer_break", QboticsGameTests::computerBreak),
            Map.entry("computer_files", QboticsGameTests::computerFiles),
            Map.entry("computer_shell", QboticsGameTests::computerShell),
            Map.entry("computer_scratchpad", QboticsGameTests::computerScratchpad),
            Map.entry("computer_input", QboticsGameTests::computerInput),
            Map.entry("network_addresses", QboticsGameTests::networkAddresses),
            Map.entry("access_faces", QboticsGameTests::accessFaces),
            Map.entry("interface_filters", QboticsGameTests::interfaceFilters),
            Map.entry("logistics_transfer", QboticsGameTests::logisticsTransfer),
            Map.entry("cable_plates", QboticsGameTests::cablePlates),
            Map.entry("network_probe", QboticsGameTests::networkProbe),
            Map.entry("fluid_and_energy", QboticsGameTests::fluidAndEnergy),
            Map.entry("slots_and_faces", QboticsGameTests::slotsAndFaces),
            Map.entry("name_filters", QboticsGameTests::nameFilters),
            Map.entry("computer_settings", QboticsGameTests::computerSettings),
            Map.entry("remote_login", QboticsGameTests::remoteLogin),
            Map.entry("experience_store", QboticsGameTests::experienceStore),
            Map.entry("test_stage", QboticsGameTests::testStage),
            Map.entry("screen_access", QboticsGameTests::screenAccess),
            Map.entry("vanilla_screens", QboticsGameTests::vanillaScreens),
            Map.entry("screen_experience", QboticsGameTests::screenExperience),
            Map.entry("world_plates", QboticsGameTests::worldPlates),
            Map.entry("store_screens", QboticsGameTests::storeScreens),
            Map.entry("watching", QboticsGameTests::watching),
            Map.entry("events_module", QboticsGameTests::eventsModule),
            Map.entry("limits_and_boot", QboticsGameTests::limitsAndBoot),
            Map.entry("drive_bays", QboticsGameTests::driveBays),
            Map.entry("pip_from_remote", QboticsGameTests::pipFromRemote),
            Map.entry("programmer_block", QboticsGameTests::programmerBlock),
            Map.entry("displays", QboticsGameTests::displays),
            Map.entry("antenna_upgrades", QboticsGameTests::antennaUpgrades),
            Map.entry("iron_qbot", QboticsGameTests::ironQbot),
            Map.entry("antenna_addresses", QboticsGameTests::antennaAddresses),
            Map.entry("radio_messages", QboticsGameTests::radioMessages),
            Map.entry("payload_codecs", QboticsGameTests::payloadCodecs));

    private QboticsGameTests() {
    }

    private static ServerComputer placeAndStart(GameTestHelper helper) {
        helper.setBlock(POS, QboticsContent.COMPUTER_BLOCK.get());
        ComputerBlockEntity block = helper.getBlockEntity(POS, ComputerBlockEntity.class);
        return ComputerManager.get(helper.getLevel().getServer()).getOrStart(block);
    }

    /** Everything on the terminal, including what has scrolled off the top. */
    private static String screen(ServerComputer computer) {
        Terminal.State state = computer.terminal().full();
        List<String> all = new ArrayList<>(state.scrolled());
        all.addAll(state.lines());
        return String.join("\n", all);
    }

    private static void assertScreen(GameTestHelper helper, ServerComputer computer, String... expected) {
        String screen = screen(computer);
        for (String text : expected) {
            helper.assertTrue(screen.contains(text), "screen should contain '" + text + "' but was:\n" + screen);
        }
    }

    /** Typing at the prompt: assignment, expression echo, a multi-line loop and an error. */
    private static void computerRepl(GameTestHelper helper) {
        ServerComputer computer = placeAndStart(helper);
        for (String line : List.of("python", "x = 6", "x * 7", "for i in range(3):", "    print('row', i * 100)", "", "1 / 0", "print('end')")) {
            computer.submitLine(line);
        }
        helper.succeedWhen(() -> assertScreen(helper, computer,
                "42\n>>> for i in range(3):\n", "...     print('row', i * 100)\n...\nrow 0\nrow 100\nrow 200\n",
                "ZeroDivisionError: divide by zero", "\nend\n>>>"));
    }

    /** Ctrl+C stops an endless loop and the prompt comes back. */
    private static void computerInterrupt(GameTestHelper helper) {
        ServerComputer computer = placeAndStart(helper);
        for (String line : List.of("python", "while True:", "    pass", "")) {
            computer.submitLine(line);
        }
        // Well after the loop has started, even with other tests competing for the two CPU permits.
        helper.runAfterDelay(60, computer::interrupt);
        helper.runAfterDelay(65, () -> computer.submitLine("print('still', 'alive')"));
        helper.succeedWhen(() -> assertScreen(helper, computer, "KeyboardInterrupt", "still alive\n>>>"));
    }

    /**
     * Ctrl+C stops a loop that sleeps and catches Exception at once (it arrives as
     * KeyboardInterrupt, during the sleep), and calls afterwards still work.
     */
    private static void computerInterruptSleep(GameTestHelper helper) {
        ServerComputer computer = placeAndStart(helper);
        for (String line : List.of("python", "import time", "from logistics import network",
                "while True:", "    try:", "        network().devices()", "        time.sleep(10)", "    except Exception:", "        pass", "")) {
            computer.submitLine(line);
        }
        helper.runAfterDelay(60, computer::interrupt);
        helper.runAfterDelay(65, () -> computer.submitLine("print('devices', len(network().devices()) > 0)"));
        helper.succeedWhen(() -> assertScreen(helper, computer, "KeyboardInterrupt", "devices True\n>>>"));
    }

    /** A program that swallows even KeyboardInterrupt is stopped by a second Ctrl+C, and Python restarts. */
    private static void computerInterruptIgnored(GameTestHelper helper) {
        ServerComputer computer = placeAndStart(helper);
        for (String line : List.of("python", "import time", "while True:", "    try:", "        time.sleep(10)", "    except:", "        print('ignored')", "")) {
            computer.submitLine(line);
        }
        // Each step waits for the last one to show (the test server ticks faster than real time).
        int[] step = {0};
        helper.succeedWhen(() -> {
            String now = screen(computer);
            if (step[0] == 0 && now.contains("while True:") && now.contains("...")) {
                computer.interrupt(); // the first Ctrl+C: the program catches it
                step[0] = 1;
            } else if (step[0] == 1 && now.contains("ignored")) {
                computer.interrupt(); // the second: stopped for certain
                step[0] = 2;
            } else if (step[0] == 2 && now.contains("Python was restarted")) {
                computer.submitLine("python");
                computer.submitLine("print('fresh', 'start')");
                step[0] = 3;
            }
            helper.assertTrue(step[0] == 3, "waiting at step " + step[0]);
            assertScreen(helper, computer, "ignored", "Python was restarted", "fresh start\n>>>");
        });
    }

    /** Calls a program left unanswered are forgotten when it ends, so the next program isn't refused. */
    private static void computerForgetsCalls(GameTestHelper helper) {
        ServerComputer computer = placeAndStart(helper);
        for (String line : List.of("python", "import _host",
                "t = [_host.submit('[\"devices\"]') for i in range(40)]; print('full', _host.wait(_host.submit('[\"devices\"]'))[:9])",
                "print('next', _host.wait(_host.submit('[\"devices\"]'))[:1])")) {
            computer.submitLine(line);
        }
        helper.succeedWhen(() -> assertScreen(helper, computer, "full error:too", "next {\n>>>"));
    }

    /** What the 3x3 crafting grid gives for these nine stacks, row by row. */
    private static ItemStack craft(GameTestHelper helper, ItemStack... grid) {
        ServerLevel level = helper.getLevel();
        CraftingInput input = CraftingInput.of(3, 3, List.of(grid));
        return level.getServer().getRecipeManager()
                .getRecipeFor(RecipeType.CRAFTING, input, level)
                .map(recipe -> recipe.value().assemble(input))
                .orElse(ItemStack.EMPTY);
    }

    /** Cable in the shape of leggings, redstone in the middle, glass at the bottom middle. */
    private static void computerRecipe(GameTestHelper helper) {
        // The parts first. LCD: three Liquid Crystal Panels from glass panes, dyes and redstone; four LCD
        // Screens from torches, panels and panes. LED: three diodes from glowstone and dyes; four LED
        // Screens from gold, diodes and glass. An integrated circuit from copper, glass and green dye.
        ItemStack g = new ItemStack(Items.GLOWSTONE_DUST);
        ItemStack p = new ItemStack(Blocks.GLASS_PANE);
        ItemStack red = new ItemStack(Items.RED_DYE);
        ItemStack green = new ItemStack(Items.GREEN_DYE);
        ItemStack blue = new ItemStack(Items.BLUE_DYE);
        ItemStack panels = craft(helper,
                p, red, p,
                new ItemStack(Items.REDSTONE), green, p,
                p, blue, p);
        ItemStack t = new ItemStack(Blocks.REDSTONE_TORCH);
        ItemStack panel = panels.copyWithCount(1);
        ItemStack screens = craft(helper, t, panel, p, t, panel, p, t, panel, p);
        ItemStack diodes = craft(helper, g, g, g, red, green, blue, g, g, g);
        ItemStack gold = new ItemStack(Items.GOLD_INGOT);
        ItemStack diode = diodes.copyWithCount(1);
        ItemStack glassBlock = new ItemStack(Blocks.GLASS);
        ItemStack ledScreens = craft(helper, gold, diode, glassBlock, gold, diode, glassBlock, gold, diode, glassBlock);
        helper.assertTrue(panels.getCount() == 3 && panels.getItem().toString().equals("qbotics:liquid_crystal_panel")
                && diodes.getCount() == 3 && diodes.getItem().toString().equals("qbotics:light_emitting_diode")
                && ledScreens.getCount() == 4 && ledScreens.getItem().toString().equals("qbotics:led_screen"),
                "the screen parts gave " + panels + ", " + diodes + ", " + ledScreens);
        ItemStack copper = new ItemStack(Items.COPPER_INGOT);
        ItemStack glass = new ItemStack(Blocks.GLASS);
        ItemStack circuit = craft(helper,
                copper, copper, copper,
                glass, new ItemStack(Items.GREEN_DYE), glass,
                copper, copper, copper);
        helper.assertTrue(screens.getCount() == 4 && screens.getItem().toString().equals("qbotics:lcd_screen"), "the LCD screen recipe gave " + screens);
        helper.assertTrue(circuit.getCount() == 1 && circuit.getItem().toString().equals("qbotics:integrated_circuit"),
                "the integrated circuit recipe gave " + circuit);

        ItemStack c = new ItemStack(QboticsContent.CABLE_BLOCK.get());
        ItemStack result = craft(helper,
                c, c, c,
                c, circuit, c,
                c, screens.copyWithCount(1), c);
        helper.assertTrue(result.is(QboticsContent.COMPUTER_BLOCK.get().asItem()) && result.getCount() == 1,
                "recipe should give one computer but gave " + result);
        helper.succeed();
    }

    /** Six iron and three redstone give 16 cables, as vertical or as horizontal lines. */
    private static void cableRecipes(GameTestHelper helper) {
        ItemStack i = new ItemStack(Items.IRON_INGOT);
        ItemStack r = new ItemStack(Items.REDSTONE);
        ItemStack vertical = craft(helper, i, r, i, i, r, i, i, r, i);
        ItemStack horizontal = craft(helper, i, i, i, r, r, r, i, i, i);
        for (ItemStack result : List.of(vertical, horizontal)) {
            helper.assertTrue(result.is(QboticsContent.CABLE_BLOCK.get().asItem()) && result.getCount() == 16,
                    "recipe should give 16 cables but gave " + result);
        }
        helper.succeed();
    }

    /** A cable reaches out to cables and computers beside it, and to nothing else. */
    private static void cableConnects(GameTestHelper helper) {
        CableBlock cable = QboticsContent.CABLE_BLOCK.get();
        BlockPos middle = new BlockPos(1, 1, 1);
        helper.setBlock(middle, cable);
        helper.setBlock(middle.east(), cable);
        helper.setBlock(middle.above(), QboticsContent.COMPUTER_BLOCK.get());
        helper.setBlock(middle.north(), Blocks.STONE);
        // Blocks set by a test don't run placement logic, so ask for the connected state.
        BlockState state = CableBlock.withConnections(helper.getLevel(), helper.absolutePos(middle), cable.defaultBlockState());
        helper.assertTrue(arm(state, Direction.EAST) && arm(state, Direction.UP)
                && !arm(state, Direction.NORTH) && !arm(state, Direction.WEST)
                && !arm(state, Direction.SOUTH) && !arm(state, Direction.DOWN),
                "cable should connect east (cable) and up (computer) only, but was " + state);
        helper.succeed();
    }

    /** Breaking the block drops it and shuts its computer down. */
    private static void computerBreak(GameTestHelper helper) {
        ServerComputer computer = placeAndStart(helper);
        UUID deviceId = computer.deviceId();
        ComputerManager manager = ComputerManager.get(helper.getLevel().getServer());
        helper.assertTrue(manager.isRunning(deviceId), "computer should be running after start");
        helper.getLevel().destroyBlock(helper.absolutePos(POS), true);
        helper.assertTrue(!manager.isRunning(deviceId), "computer should stop when its block is broken");
        helper.succeedWhen(() -> helper.assertItemEntityPresent(QboticsContent.COMPUTER_BLOCK.get().asItem()));
    }

    /**
     * Files written in Python are real files in the world folder, programs can be imported
     * and run from them, and the dropped item keeps the drive so the files aren't lost.
     */
    private static void computerFiles(GameTestHelper helper) {
        ServerComputer computer = placeAndStart(helper);
        ComputerManager manager = ComputerManager.get(helper.getLevel().getServer());
        java.nio.file.Path folder = manager.driveFolder(computer.driveId());
        for (String line : List.of(
                "python",
                "f = open('greet.py', 'w')",
                "f.write('def hello(name):\\n    return \"hi \" + name\\n')",
                "f.close()",
                "import os, greet",
                "print(os.listdir(), greet.hello('qbot'))",
                "open('boom.py', 'w').write('x = 1\\ny = x / 0\\n')",
                "execfile('boom.py')")) {
            computer.submitLine(line);
        }
        helper.succeedWhen(() -> {
            assertScreen(helper, computer, "['greet.py'] hi qbot", "File \"boom.py\", line 2", "ZeroDivisionError");
            helper.assertTrue(java.nio.file.Files.isRegularFile(folder.resolve("greet.py")),
                    "greet.py should be a real file in " + folder);
            // Break the block: the item that drops must carry the drive.
            UUID driveId = computer.driveId();
            helper.getLevel().destroyBlock(helper.absolutePos(POS), true);
            boolean carried = helper.getLevel()
                    .getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                            new net.minecraft.world.phys.AABB(helper.absolutePos(POS)).inflate(3))
                    .stream().anyMatch(item -> driveId.equals(item.getItem().get(QboticsContent.DRIVE_ID.get())));
            helper.assertTrue(carried, "the dropped computer should carry drive " + driveId);
        });
    }

    /** The shell: running a program, copying, listing, and plain-worded mistakes. */
    private static void computerShell(GameTestHelper helper) {
        ServerComputer computer = placeAndStart(helper);
        for (String line : List.of(
                "python",
                "open('hi.py', 'w').write('print(\"hi from file\", 6 * 7)\\n')",
                "exit()",
                "run hi.py",
                "mkdir lib",
                "cp hi.py lib",
                "cd lib",
                "ls",
                "cat nope.py",
                "frobnicate", "runhi.py", "hi.py")) {
            computer.submitLine(line);
        }
        helper.succeedWhen(() -> assertScreen(helper, computer,
                "/ $ run hi.py\nhi from file 42\n", "/lib $ ls\nhi.py", "no such file or folder",
                "Unknown command: frobnicate. Type help", "Unknown command: runhi.py. Did you mean: run hi.py ?",
                "Unknown command: hi.py. To run a program, type: run hi.py", "\n/lib $"));
    }

    /** A scratchpad run happens on the computer, shares its Python variables, and leaves the terminal alone. */
    private static void computerScratchpad(GameTestHelper helper) {
        ServerComputer computer = placeAndStart(helper);
        computer.submitScratch(UUID.randomUUID(), 1, "shared = 41 + 1\nprint('this goes to the scratchpad, not the terminal')");
        computer.submitLine("python");
        computer.submitLine("shared");
        helper.succeedWhen(() -> {
            assertScreen(helper, computer, ">>> shared\n42\n");
            helper.assertTrue(!screen(computer).contains("scratchpad, not"), "scratchpad output leaked into the terminal:\n" + screen(computer));
        });
    }

    /** input() takes the next line typed at the terminal; Ctrl+C gets a waiting program out of it. */
    private static void computerInput(GameTestHelper helper) {
        ServerComputer computer = placeAndStart(helper);
        for (String line : List.of("python", "name = input('Name? ')", "Ada", "print('hi', name)", "input('again? ')")) {
            computer.submitLine(line);
        }
        // Each step waits for the screen to show the one before it: the test server's ticks
        // run much faster than the computer's own thread, so fixed delays can't be relied on.
        int[] step = {0};
        helper.succeedWhen(() -> {
            String shown = screen(computer);
            if (step[0] == 0 && shown.contains("again? ")) {
                computer.interrupt();
                step[0] = 1;
            }
            if (step[0] == 1 && shown.contains("KeyboardInterrupt")) {
                computer.submitLine("print('after', len(name))");
                step[0] = 2;
            }
            if (step[0] == 2 && shown.contains("after 3")) {
                // A scratchpad run has no terminal: input() there is an error, not a hang.
                computer.submitScratch(UUID.randomUUID(), 1, "input('never asked')");
                computer.submitLine("print('still here')");
                step[0] = 3;
            }
            assertScreen(helper, computer,
                    "Name? Ada\n>>> print('hi', name)\nhi Ada\n", "again?\n", "KeyboardInterrupt", "after 3\n", "still here\n>>>");
        });
    }

    /** Both packets survive being written to and read from a buffer. */
    private static void payloadCodecs(GameTestHelper helper) {
        Terminal terminal = new Terminal();
        for (int i = 0; i < 40; i++) {
            terminal.write("line " + i + "\n");
        }
        terminal.write(">>> ");
        // 40 lines and a prompt on a 14-row screen: 27 lines have scrolled off.
        Terminal.State first = terminal.takeUpdate();
        terminal.write("one more\n");
        Terminal.State second = terminal.takeUpdate();
        helper.assertTrue(first.scrolled().size() == 27 && first.scrolledStart() == 0 && first.scrolled().get(26).equals("line 26")
                && second.scrolled().equals(List.of("line 27")) && second.scrolledStart() == 27
                && terminal.full().scrolled().size() == 28, "scroll-back should be handed out once, in order: " + second);

        TerminalStatePayload state = new TerminalStatePayload(UUID.randomUUID(), true, terminal.full());
        ByteBuf buffer = Unpooled.buffer();
        TerminalStatePayload.STREAM_CODEC.encode(buffer, state);
        TerminalStatePayload stateBack = TerminalStatePayload.STREAM_CODEC.decode(buffer);
        helper.assertTrue(state.deviceId().equals(stateBack.deviceId()) && stateBack.open()
                && state.state().equals(stateBack.state()), "terminal state changed in transit: " + stateBack);

        TerminalInputPayload input = new TerminalInputPayload(UUID.randomUUID(), TerminalInputPayload.LINE, "print('héllo')");
        buffer.clear();
        TerminalInputPayload.STREAM_CODEC.encode(buffer, input);
        helper.assertTrue(input.equals(TerminalInputPayload.STREAM_CODEC.decode(buffer)), "terminal input changed in transit");
        helper.succeed();
    }

    // ---- cable networks and the item blocks (design doc §9.3, §11.0) -----------------

    private static ServerComputer startAt(GameTestHelper helper, BlockPos pos) {
        helper.setBlock(pos, QboticsContent.COMPUTER_BLOCK.get());
        return ComputerManager.get(helper.getLevel().getServer()).getOrStart(helper.getBlockEntity(pos, ComputerBlockEntity.class));
    }

    /** True if a cable reaches out to its neighbour on this side. */
    private static boolean arm(BlockState cable, Direction side) {
        return CableBlock.arm(cable, side);
    }

    /** A cable at a position with one plate on it, pointing one way. */
    private static void setPort(GameTestHelper helper, BlockPos pos, Kind kind, Direction pointing) {
        helper.setBlock(pos, QboticsContent.CABLE_BLOCK.get());
        CableBlock.attach(helper.getLevel(), helper.absolutePos(pos), kind, pointing);
    }

    private static CableNetwork networkAt(GameTestHelper helper, BlockPos pos) {
        return CableNetwork.at(helper.getLevel(), helper.absolutePos(pos));
    }

    private static List<String> ips(CableNetwork network) {
        return network.devices().stream().map(NetworkDevice::ip).toList();
    }

    /**
     * Devices joined by cable (or touching) form one network, each with its own internal IP
     * and a hardware address. Joining two networks keeps addresses where it can, and cutting
     * the cable parts them again.
     */
    private static void networkAddresses(GameTestHelper helper) {
        BlockPos computerPos = new BlockPos(0, 1, 0);
        BlockPos farInput = new BlockPos(0, 1, 2);
        BlockPos link = new BlockPos(0, 1, 1);
        ServerComputer computer = startAt(helper, computerPos);
        helper.setBlock(new BlockPos(1, 1, 0), QboticsContent.CABLE_BLOCK.get());
        setPort(helper, new BlockPos(2, 1, 0), Kind.BUS, Direction.SOUTH);
        helper.setBlock(new BlockPos(1, 1, 1), QboticsContent.INTERFACE_BLOCK.get());
        helper.setBlock(farInput, QboticsContent.INTERFACE_BLOCK.get());

        CableNetwork network = networkAt(helper, computerPos);
        helper.assertTrue(ips(network).equals(List.of("192.168.1.2", "192.168.1.3", "192.168.1.4")),
                "three devices should have the first three addresses, but had " + ips(network));
        for (NetworkDevice device : network.devices()) {
            helper.assertTrue(device.mac().matches("02(:[0-9A-F]{2}){5}"), "odd hardware address " + device.mac());
        }
        helper.assertTrue(network.devices().stream().map(NetworkDevice::mac).distinct().count() == 3, "hardware addresses should differ");
        helper.assertTrue(ips(networkAt(helper, farInput)).equals(List.of("192.168.1.2")),
                "an input on its own is a network of one, but was " + ips(networkAt(helper, farInput)));

        // The bus doesn't join the network through the side that points at its target.
        CablePort bus = helper.getBlockEntity(new BlockPos(2, 1, 0), PortBlockEntity.class).port(Direction.SOUTH);
        helper.assertTrue(bus != null && bus.kind() == Kind.BUS, "the cable should carry a bus on its south side");
        BlockState cable = CableBlock.withConnections(helper.getLevel(), helper.absolutePos(new BlockPos(2, 1, 1)),
                QboticsContent.CABLE_BLOCK.get().defaultBlockState());
        helper.assertTrue(!arm(cable, Direction.NORTH), "a cable in front of a bus should not reach out to it");

        // Join the far input on: it held .2 as well, so one of the two is given a new address.
        Map<String, String> before = new java.util.HashMap<>();
        network.devices().forEach(device -> before.put(device.mac(), device.ip()));
        helper.setBlock(link, QboticsContent.CABLE_BLOCK.get());
        CableNetwork joined = networkAt(helper, computerPos);
        helper.assertTrue(joined.devices().size() == 4 && ips(joined).stream().distinct().count() == 4,
                "four devices should have four different addresses, but had " + ips(joined));
        long kept = joined.devices().stream().filter(device -> device.ip().equals(before.get(device.mac()))).count();
        helper.assertTrue(kept >= 2, "joining should leave most addresses alone, but only " + kept + " of 3 stayed");

        helper.setBlock(link, Blocks.AIR);
        helper.assertTrue(networkAt(helper, computerPos).devices().size() == 3, "cutting the cable should part the networks");

        // Names: cleaned up, and usable to find the device.
        bus.setDeviceName("smelter bus!");
        helper.assertTrue(bus.deviceName().equals("smelter_bus") && networkAt(helper, computerPos).find("smelter_bus").size() == 1,
                "name should be cleaned to smelter_bus but was " + bus.deviceName());

        computer.submitLine("devices");
        helper.succeedWhen(() -> assertScreen(helper, computer, "IP address", "bus       smelter_bus", "(this computer)"));
    }

    /** Which real side each face means, for a bus looking sideways and one looking down. */
    private static void accessFaces(GameTestHelper helper) {
        Direction south = Direction.SOUTH;
        helper.assertTrue(AccessFace.NEAR.resolve(south) == Direction.NORTH && AccessFace.FAR.resolve(south) == Direction.SOUTH
                && AccessFace.TOP.resolve(south) == Direction.UP && AccessFace.BOTTOM.resolve(south) == Direction.DOWN
                && AccessFace.LEFT.resolve(south) == Direction.EAST && AccessFace.RIGHT.resolve(south) == Direction.WEST,
                "faces for a bus looking south are wrong");
        Direction down = Direction.DOWN;
        helper.assertTrue(AccessFace.NEAR.resolve(down) == Direction.UP && AccessFace.FAR.resolve(down) == Direction.DOWN
                && AccessFace.TOP.resolve(down) == Direction.NORTH && AccessFace.LEFT.resolve(down) == Direction.WEST,
                "faces for a bus looking down are wrong");
        helper.assertTrue(AccessFace.parse("top") == AccessFace.TOP && AccessFace.parse("sideways") == null, "face words");
        helper.succeed();
    }

    /** An input slot takes only its filter's item, one stack of it; a hopper obeys the same rule. */
    private static void interfaceFilters(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, QboticsContent.INTERFACE_BLOCK.get());
        InterfaceBlockEntity input = helper.getBlockEntity(pos, InterfaceBlockEntity.class);
        ItemStack coal = new ItemStack(Items.COAL);
        helper.assertTrue(!input.canPlaceItem(0, coal) && input.insert(coal, 10, false) == 0, "every slot should start closed");
        input.setFilter(0, Items.COAL);
        input.setFilter(4, Items.COAL);
        helper.assertTrue(input.canPlaceItem(0, coal) && !input.canPlaceItem(1, coal) && !input.canPlaceItem(0, new ItemStack(Items.STONE)),
                "only slots 0 and 4 should take coal");
        helper.assertTrue(input.insert(coal, 100, true) == 100 && input.isEmpty(), "a trial run should change nothing");
        helper.assertTrue(input.insert(coal, 200, false) == 128 && input.getItem(0).getCount() == 64 && input.getItem(4).getCount() == 64,
                "two open slots hold two stacks");
        helper.assertTrue(input.extract(coal, 70, false) == 70 && input.countItem(Items.COAL) == 58, "70 of 128 taken leaves 58");

        // By hand: clicking the screen's top row with an item sets the filter of the slot below it.
        BlockPos handPos = new BlockPos(3, 1, 3);
        helper.setBlock(handPos, QboticsContent.INTERFACE_BLOCK.get());
        InterfaceBlockEntity byHand = helper.getBlockEntity(handPos, InterfaceBlockEntity.class);
        net.minecraft.world.entity.player.Player player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        net.minecraft.world.inventory.AbstractContainerMenu menu = byHand.createMenu(1, player.getInventory(), player);
        net.minecraft.world.inventory.ContainerInput click = net.minecraft.world.inventory.ContainerInput.PICKUP;
        menu.setCarried(new ItemStack(Items.COAL, 20));
        menu.clicked(9 + 2, 0, click, player);
        helper.assertTrue(byHand.isEmpty() && menu.getCarried().getCount() == 20, "a closed slot should refuse the coal");
        menu.clicked(2, 0, click, player);
        helper.assertTrue(byHand.filter(2) == Items.COAL && menu.getCarried().getCount() == 20 && menu.getSlot(2).getItem().is(Items.COAL),
                "clicking the top row should set the filter, show it, and leave the coal in hand");
        menu.clicked(9 + 2, 0, click, player);
        helper.assertTrue(byHand.getItem(2).getCount() == 20 && menu.getCarried().isEmpty(), "the open slot should now take the coal");
        menu.clicked(2, 0, click, player);
        helper.assertTrue(byHand.filter(2) == null && menu.getSlot(2).getItem().isEmpty() && byHand.getItem(2).getCount() == 20,
                "an empty hand on the top row should close the slot and leave its items");
        menu.quickMoveStack(player, 2);
        helper.assertTrue(byHand.getItem(2).getCount() == 20, "shift-clicking a filter should move nothing");
        menu.removed(player);

        // A hopper above feeds it coal but can't push in the iron.
        helper.setBlock(pos.above(), Blocks.HOPPER);
        net.minecraft.world.level.block.entity.HopperBlockEntity hopper =
                helper.getBlockEntity(pos.above(), net.minecraft.world.level.block.entity.HopperBlockEntity.class);
        hopper.setItem(0, new ItemStack(Items.IRON_INGOT, 3));
        hopper.setItem(1, new ItemStack(Items.COAL, 5));
        helper.succeedWhen(() -> helper.assertTrue(input.countItem(Items.COAL) == 63 && hopper.countItem(Items.IRON_INGOT) == 3,
                "the hopper should have delivered 5 coal and kept its iron; input has " + input.countItem(Items.COAL) + " coal"));
    }

    /**
     * A program moves items: from an input through a bus into a furnace's ingredient and fuel
     * slots, the furnace's product back out, and the rest through an output into a barrel.
     */
    private static void logisticsTransfer(GameTestHelper helper) {
        BlockPos inputPos = new BlockPos(1, 1, 0);
        BlockPos furnacePos = new BlockPos(2, 1, 1);
        BlockPos barrelPos = new BlockPos(1, 2, 1);
        ServerComputer computer = startAt(helper, new BlockPos(0, 1, 0));
        helper.setBlock(inputPos, QboticsContent.INTERFACE_BLOCK.get());
        helper.setBlock(furnacePos, Blocks.FURNACE);
        setPort(helper, new BlockPos(2, 1, 0), Kind.BUS, Direction.SOUTH);
        helper.setBlock(barrelPos, Blocks.BARREL);
        helper.setBlock(new BlockPos(1, 1, 1), QboticsContent.CABLE_BLOCK.get());
        CableBlock.attach(helper.getLevel(), helper.absolutePos(new BlockPos(1, 1, 1)), Kind.BUS, qbotics.network.Medium.UNIVERSAL, Direction.UP);

        InterfaceBlockEntity input = helper.getBlockEntity(inputPos, InterfaceBlockEntity.class);
        input.setFilter(0, Items.RAW_IRON);
        input.setItem(0, new ItemStack(Items.RAW_IRON, 16));
        input.setFilter(1, Items.COAL);
        input.setItem(1, new ItemStack(Items.COAL, 8));
        net.minecraft.world.Container furnace = helper.getBlockEntity(furnacePos, net.minecraft.world.level.block.entity.FurnaceBlockEntity.class);
        furnace.setItem(2, new ItemStack(Items.IRON_INGOT, 3));
        net.minecraft.world.Container barrel = helper.getBlockEntity(barrelPos, net.minecraft.world.level.block.entity.BarrelBlockEntity.class);

        for (String line : List.of(
                "python",
                "from logistics import network, move, Face",
                "net = network()",
                "f = net.bus()",
                "inbox = net.interface()",
                "print('before', f.count('iron_ingot'), inbox.filters()[2])",
                "inbox.set_slot_filter(2, 'minecraft:iron_ingot')",
                "a = move(inbox, f.destination(face=Face.TOP), 'minecraft:raw_iron', 5)",
                "b = move(inbox, f.destination(face=Face.LEFT), 'coal', 2)",
                "print('moved', a, b, move(f.source(face=Face.BOTTOM), inbox, 'iron_ingot', 64))",
                "print('out', move(inbox, net.usb(), 'raw_iron', 100), inbox.count('raw_iron'), inbox.count('iron_ingot'))",
                "print(len(net.devices()), net.me.kind, f.target, f.ip.startswith('192.168.1.'))",
                "net.bus('nope')",
                "move(inbox, f, 'minecraft:not_a_thing')",
                "print('plain bus first', net.bus().target, net.usb().kind)",
                "import network",
                "print('host', network.hostname('base'), network.ifconfig()['ip'] == net.me.ip)")) {
            computer.submitLine(line);
        }
        helper.succeedWhen(() -> {
            assertScreen(helper, computer, "before 3 None\n", "moved 5 2 3\n", "out 11 0 3\n", "4 computer minecraft:furnace True\n",
                    "NetworkError: No bus on this network has the name or address 'nope'.",
                    "There is no item called 'minecraft:not_a_thing'", "plain bus first minecraft:furnace usb\n", "host base True\n");
            // The furnace is lit, so by now it may have smelted some of the five: count both.
            helper.assertTrue(furnace.getItem(0).getCount() + furnace.getItem(2).getCount() == 5,
                    "the furnace should hold the five raw iron (smelted or not) and none of the old ingots: "
                    + furnace.getItem(0) + " / " + furnace.getItem(2));
            helper.assertTrue(barrel.countItem(Items.RAW_IRON) == 11, "the barrel should hold 11 raw iron but holds " + barrel.countItem(Items.RAW_IRON));
            helper.assertTrue(input.getItem(1).getCount() == 6 && input.getItem(2).is(Items.IRON_INGOT),
                    "the input should have 6 coal left and the ingots in slot 2");
        });
    }

    /**
     * Plates share a cable's block: one is attached by clicking the block it should work on,
     * points at that block, is a device on the network, and comes off again as an item.
     */
    private static void cablePlates(GameTestHelper helper) {
        BlockPos cablePos = new BlockPos(1, 1, 1);
        BlockPos chestPos = new BlockPos(1, 1, 2);
        BlockPos computerPos = new BlockPos(0, 1, 1);
        BlockPos storePos = new BlockPos(1, 1, 0);
        ServerLevel level = helper.getLevel();
        helper.setBlock(cablePos, QboticsContent.CABLE_BLOCK.get());
        helper.setBlock(chestPos, Blocks.CHEST);
        helper.setBlock(computerPos, QboticsContent.COMPUTER_BLOCK.get());
        helper.setBlock(storePos, QboticsContent.INTERFACE_BLOCK.get());
        net.minecraft.world.Container chest = helper.getBlockEntity(chestPos, net.minecraft.world.level.block.entity.ChestBlockEntity.class);
        chest.setItem(5, new ItemStack(Items.COAL, 12));
        InterfaceBlockEntity store = helper.getBlockEntity(storePos, InterfaceBlockEntity.class);
        store.setFilter(0, Items.COAL);

        // A player holding an Item Serial Bus clicks the chest's north face: the plate goes on the cable in front of it.
        net.minecraft.world.entity.player.Player player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        net.minecraft.world.InteractionHand hand = net.minecraft.world.InteractionHand.MAIN_HAND;
        ItemStack inputs = new ItemStack(QboticsContent.plateItem(Kind.BUS, qbotics.network.Medium.ITEM), 2);
        inputs.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("ore in"));
        player.setItemInHand(hand, inputs);
        java.util.function.BiFunction<BlockPos, Direction, net.minecraft.world.InteractionResult> click = (pos, face) -> {
            BlockPos at = helper.absolutePos(pos);
            return player.getItemInHand(hand).useOn(new net.minecraft.world.item.context.UseOnContext(player, hand,
                    new net.minecraft.world.phys.BlockHitResult(at.getCenter(), face, at, false)));
        };
        click.apply(chestPos, Direction.NORTH);
        BlockState withPlate = helper.getBlockState(cablePos);
        BlockPos cableAt = helper.absolutePos(cablePos);
        helper.assertTrue(CableBlock.plateKind(level, cableAt, Direction.SOUTH) == Kind.BUS && arm(withPlate, Direction.WEST)
                && !arm(withPlate, Direction.SOUTH) && player.getItemInHand(hand).getCount() == 1,
                "the cable should carry a bus pointing south at the chest, and one item be used: " + withPlate);
        PortBlockEntity host = helper.getBlockEntity(cablePos, PortBlockEntity.class);
        CablePort plate = host.port(Direction.SOUTH);
        helper.assertTrue(plate.kind() == Kind.BUS && plate.targetPos().equals(helper.absolutePos(chestPos)) && plate.deviceName().equals("ore_in"),
                "the plate should be a bus named ore_in pointing at the chest, but is " + plate.kind() + " " + plate.deviceName());
        click.apply(chestPos, Direction.NORTH);
        helper.assertTrue(player.getItemInHand(hand).getCount() == 1, "a side carries only one plate");

        // A second plate goes on another side of the same cable: here, pointing up at a barrel.
        helper.setBlock(cablePos.above(), Blocks.BARREL);
        click.apply(cablePos.above(), Direction.DOWN);
        BlockState withTwo = helper.getBlockState(cablePos);
        CablePort second = host.port(Direction.UP);
        helper.assertTrue(CableBlock.plateKind(level, cableAt, Direction.UP) == Kind.BUS && CableBlock.plateKind(level, cableAt, Direction.SOUTH) == Kind.BUS
                && second != null && host.ports().size() == 2 && !second.mac().equals(plate.mac())
                && second.targetPos().equals(helper.absolutePos(cablePos.above())) && player.getItemInHand(hand).isEmpty(),
                "the cable should carry two buses, each its own device: " + withTwo);

        // With no cable in front of the clicked face, one is taken from the player's inventory.
        player.setItemInHand(hand, new ItemStack(QboticsContent.plateItem(Kind.BUS, qbotics.network.Medium.ITEM)));
        click.apply(chestPos, Direction.UP);
        helper.assertTrue(helper.getBlockState(chestPos.above()).isAir() && player.getItemInHand(hand).getCount() == 1, "no cable, no plate");
        player.getInventory().add(new ItemStack(QboticsContent.CABLE_BLOCK.get(), 3));
        click.apply(chestPos, Direction.UP);
        helper.assertTrue(CableBlock.plateKind(level, helper.absolutePos(chestPos.above()), Direction.DOWN) == Kind.BUS && player.getItemInHand(hand).isEmpty()
                && player.getInventory().countItem(QboticsContent.CABLE_BLOCK.get().asItem()) == 2,
                "the bus should have been placed with one of the player's cables");

        // The plates are devices on the network: the computer, the interface and the two buses.
        BlockPos computerAt = helper.absolutePos(computerPos);
        CableNetwork network = networkAt(helper, computerPos);
        helper.assertTrue(network.devices().size() == 4 && network.devices().contains(plate) && network.devices().contains(second)
                && ips(network).stream().distinct().count() == 4, "four devices with four addresses, but had " + ips(network));
        java.util.function.Function<String, String> call = request ->
                qbotics.network.NetworkCalls.handle(level.getServer(), level.dimension(), computerAt, request);
        String mac = plate.mac();
        helper.assertTrue(call.apply("[\"contents\",\"" + mac + "\",null]").contains("\"count\":12"), "a bus reads its target");
        String storeMac = store.mac();
        helper.assertTrue(call.apply("[\"move\",[{\"mac\":\"" + mac + "\"}],[{\"mac\":\"" + storeMac + "\"}],\"minecraft:coal\",5,null]")
                .equals("{\"r\":{\"moved\":5}}")
                && store.getItem(0).getCount() == 5 && chest.getItem(5).getCount() == 7, "a bus should move 5 coal to the interface");
        helper.assertTrue(call.apply("[\"move\",[{\"mac\":\"" + storeMac + "\"}],[{\"mac\":\"" + mac + "\"}],\"minecraft:coal\",1,null]")
                .equals("{\"r\":{\"moved\":1}}") && chest.countItem(Items.COAL) == 8,
                "a bus should put items back in too");
        helper.assertTrue(call.apply("[\"find\",\"" + second.mac() + "\",null]").contains("minecraft:barrel"), "the second bus points at the barrel");

        // Breaking the cable drops every plate; taking plates off one at a time ends with a plain cable.
        List<ItemStack> drops = net.minecraft.world.level.block.Block.getDrops(withTwo, level, helper.absolutePos(cablePos), host);
        helper.assertTrue(drops.size() == 3 && drops.stream().anyMatch(stack -> stack.getItem() instanceof qbotics.block.PlateItem
                && stack.getHoverName().getString().equals("ore_in")), "the drops should be the cable and both plates: " + drops);
        ItemStack taken = CableBlock.detach(level, helper.absolutePos(cablePos), Direction.SOUTH);
        helper.assertTrue(taken.getItem() instanceof qbotics.block.PlateItem && plate.isRemoved() && !second.isRemoved()
                && CableBlock.plateKind(level, cableAt, Direction.UP) == Kind.BUS
                && networkAt(helper, computerPos).devices().size() == 3, "taking one plate off should leave the other working");
        // Breaking while aiming at a plate takes off only that plate. Here a player stands west of a cable and looks east at it.
        BlockPos alonePos = new BlockPos(2, 2, 0); // inside the test's walls, with clear air to its west
        setPort(helper, alonePos, Kind.BUS, Direction.WEST);
        BlockPos aloneAt = helper.absolutePos(alonePos);
        player.snapTo(aloneAt.getX() - 1.5, aloneAt.getY() + 0.5 - player.getEyeHeight(), aloneAt.getZ() + 0.5, -90.0f, 0.0f);
        player.setYHeadRot(-90.0f);
        helper.assertTrue(CableBlock.plateAimedAt(level, aloneAt, player) == Direction.WEST,
                "the player should be aiming at the west plate");
        helper.assertTrue(CableBlock.breakPlateInstead(helper.getBlockState(alonePos), level, aloneAt, player)
                && helper.getBlockState(alonePos).getBlock() instanceof CableBlock && !CableBlock.hasPlates(level, aloneAt),
                "breaking at the plate should leave the cable without it");
        helper.assertTrue(!CableBlock.breakPlateInstead(helper.getBlockState(alonePos), level, aloneAt, player),
                "with no plate aimed at, the cable itself breaks as usual");

        CableBlock.detach(level, helper.absolutePos(cablePos), Direction.UP);
        helper.assertTrue(!CableBlock.hasPlates(level, cableAt) && arm(helper.getBlockState(cablePos), Direction.UP) == false
                && networkAt(helper, computerPos).devices().size() == 2, "taking the last plate off should leave a bare cable and two devices");
        helper.succeed();
    }

    /**
     * Fluid and energy: a store for each, plates that carry each, and programs moving both.
     * The plates' targets here are a second store of each kind, reached the way another
     * mod's tank or machine would be.
     */
    private static void fluidAndEnergy(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos computerPos = new BlockPos(0, 1, 0);
        BlockPos fluidCable = new BlockPos(2, 1, 0);
        BlockPos energyCable = new BlockPos(0, 1, 2);
        ServerComputer computer = startAt(helper, computerPos);
        helper.setBlock(new BlockPos(1, 1, 0), QboticsContent.FLUID_INTERFACE_BLOCK.get());
        helper.setBlock(new BlockPos(0, 1, 1), QboticsContent.ENERGY_INTERFACE_BLOCK.get());
        helper.setBlock(new BlockPos(2, 1, 1), QboticsContent.FLUID_INTERFACE_BLOCK.get());
        helper.setBlock(new BlockPos(1, 1, 2), QboticsContent.ENERGY_INTERFACE_BLOCK.get());
        helper.setBlock(fluidCable, QboticsContent.CABLE_BLOCK.get());
        helper.setBlock(energyCable, QboticsContent.CABLE_BLOCK.get());
        qbotics.network.Medium fluid = qbotics.network.Medium.FLUID;
        qbotics.network.Medium energy = qbotics.network.Medium.ENERGY;
        CablePort fluidBus = CableBlock.attach(level, helper.absolutePos(fluidCable), Kind.BUS, fluid, Direction.SOUTH);
        CablePort energyBus = CableBlock.attach(level, helper.absolutePos(energyCable), Kind.BUS, energy, Direction.EAST);
        qbotics.block.FluidInterfaceBlockEntity store = helper.getBlockEntity(new BlockPos(1, 1, 0), qbotics.block.FluidInterfaceBlockEntity.class);
        qbotics.block.FluidInterfaceBlockEntity tank = helper.getBlockEntity(new BlockPos(2, 1, 1), qbotics.block.FluidInterfaceBlockEntity.class);
        qbotics.block.EnergyInterfaceBlockEntity cell = helper.getBlockEntity(new BlockPos(0, 1, 1), qbotics.block.EnergyInterfaceBlockEntity.class);
        qbotics.block.EnergyInterfaceBlockEntity machine = helper.getBlockEntity(new BlockPos(1, 1, 2), qbotics.block.EnergyInterfaceBlockEntity.class);
        net.minecraft.world.level.material.Fluid water = net.minecraft.world.level.material.Fluids.WATER;

        // The stores themselves: four tanks of four buckets, and two million units of energy.
        helper.assertTrue(tank.insert(water, 99_000, false) == 16_000 && tank.extract(water, 13_000, false) == 13_000
                && tank.insert(net.minecraft.world.level.material.Fluids.LAVA, 500, false) == 500 && tank.tanks().size() == 4,
                "a fluid interface holds 16 buckets in four tanks");
        helper.assertTrue(machine.insert(3_000_000, false) == 2_000_000 && machine.extract(1_500_000, false) == 1_500_000 && machine.stored() == 500_000,
                "an energy interface holds two million");

        // On the network: the computer, the two stores and the two plates. The plates' targets are not on it.
        CableNetwork network = networkAt(helper, computerPos);
        List<String> kinds = network.devices().stream().map(NetworkDevice::kindWord).sorted().toList();
        helper.assertTrue(kinds.equals(List.of("computer", "energy_bus", "energy_interface", "fluid_bus", "fluid_interface")),
                "the network should hold the computer, two stores and two plates, but had " + kinds);
        BlockPos computerAt = helper.absolutePos(computerPos);
        java.util.function.Function<String, String> call = request ->
                qbotics.network.NetworkCalls.handle(level.getServer(), level.dimension(), computerAt, request);
        String f = fluidBus.mac();
        String e = energyBus.mac();
        helper.assertTrue(call.apply("[\"contents\",\"" + f + "\",null]").contains("\"fluid\":\"minecraft:water\",\"amount\":3000"),
                "the fluid bus should read its target's tanks: " + call.apply("[\"contents\",\"" + f + "\",null]"));
        java.util.function.BiFunction<String, String, String> ends = (from, to) -> "[{\"mac\":\"" + from + "\"}],[{\"mac\":\"" + to + "\"}]";
        helper.assertTrue(call.apply("[\"move\"," + ends.apply(f, store.mac()) + ",\"minecraft:water\",2500,null]").equals("{\"r\":{\"moved\":2500}}")
                && store.tanks().getFirst().amount() == 2500, "2500 mB of water should move into the fluid interface");
        helper.assertTrue(call.apply("[\"move\"," + ends.apply(store.mac(), f) + ",\"minecraft:water\",500,null]").equals("{\"r\":{\"moved\":500}}")
                && store.tanks().getFirst().amount() == 2000 && tank.extract(water, 99_000, true) == 1000, "500 mB should go back");
        helper.assertTrue(call.apply("[\"move\"," + ends.apply(f, store.mac()) + ",\"minecraft:milk_tea\",1,null]").contains("There is no fluid called"),
                "an unknown fluid should be explained");
        helper.assertTrue(call.apply("[\"energy\",\"" + e + "\",null]").equals("{\"r\":{\"stored\":500000,\"capacity\":2000000}}"),
                "the energy bus should read its target: " + call.apply("[\"energy\",\"" + e + "\",null]"));
        helper.assertTrue(call.apply("[\"move\"," + ends.apply(e, cell.mac()) + ",null,200000,null]").equals("{\"r\":{\"moved\":200000}}")
                && cell.stored() == 200_000 && machine.stored() == 300_000, "200,000 should move into the energy interface");
        helper.assertTrue(call.apply("[\"move\"," + ends.apply(cell.mac(), e) + ",null,50000,null]").equals("{\"r\":{\"moved\":50000}}")
                && cell.stored() == 150_000 && machine.stored() == 350_000, "50,000 should go back");
        helper.assertTrue(call.apply("[\"move\"," + ends.apply(f, cell.mac()) + ",\"minecraft:water\",1,null]").contains("A move carries one kind of thing"),
                "fluid can't be sent to an energy interface");
        // "need": all or nothing. More than there is moves nothing and says how much could; the exact amount moves.
        helper.assertTrue(call.apply("[\"move\"," + ends.apply(e, cell.mac()) + ",null,null,900000]").equals("{\"r\":{\"moved\":0,\"short\":true,\"could\":350000}}")
                && machine.stored() == 350_000, "asking for more than there is should move nothing");
        helper.assertTrue(call.apply("[\"move\"," + ends.apply(e, cell.mac()) + ",null,null,50000]").equals("{\"r\":{\"moved\":50000}}")
                && call.apply("[\"move\"," + ends.apply(cell.mac(), e) + ",null,null,50000]").equals("{\"r\":{\"moved\":50000}}"),
                "an exact amount that fits should move, there and back");

        // Breaking the cable gives back the fluid plate, not an item one.
        List<ItemStack> drops = net.minecraft.world.level.block.Block.getDrops(helper.getBlockState(fluidCable), level,
                helper.absolutePos(fluidCable), level.getBlockEntity(helper.absolutePos(fluidCable)));
        helper.assertTrue(drops.stream().anyMatch(stack -> stack.is(QboticsContent.plateItem(Kind.BUS, fluid))), "the drops should hold a fluid bus: " + drops);

        // Recipes: seven cables with a hopper on the left of the middle row and, on the right, a dropper (items),
        // a bucket (fluid) or a block of redstone (energy). The three together make a USB.
        ItemStack none = ItemStack.EMPTY;
        java.util.function.Function<ItemStack, ItemStack> h = right -> {
            ItemStack c = new ItemStack(QboticsContent.CABLE_BLOCK.get());
            return craft(helper, c, c, c, new ItemStack(Blocks.HOPPER), c, right, c, c, c);
        };
        ItemStack bus = h.apply(new ItemStack(Blocks.DROPPER));
        ItemStack fluidBusItem = h.apply(new ItemStack(Items.BUCKET));
        ItemStack energyBusItem = h.apply(new ItemStack(Blocks.REDSTONE_BLOCK));
        ItemStack usb = craft(helper, bus, fluidBusItem, energyBusItem, none, none, none, none, none, none);
        ItemStack energyStore = craft(helper, new ItemStack(QboticsContent.INTERFACE_BLOCK.get()), new ItemStack(Blocks.REDSTONE_BLOCK),
                none, none, none, none, none, none, none);
        helper.assertTrue(bus.is(QboticsContent.plateItem(Kind.BUS, qbotics.network.Medium.ITEM)) && fluidBusItem.is(QboticsContent.plateItem(Kind.BUS, fluid))
                && energyBusItem.is(QboticsContent.plateItem(Kind.BUS, energy)) && usb.is(QboticsContent.plateItem(Kind.BUS, qbotics.network.Medium.UNIVERSAL))
                && energyStore.is(QboticsContent.ENERGY_INTERFACE_BLOCK.get().asItem())
                && usb.getHoverName().getString().equals("Universal Serial Bus (USB)") && bus.getHoverName().getString().equals("Item Serial Bus"),
                "recipes gave " + bus + ", " + fluidBusItem + ", " + energyBusItem + ", " + usb + ", " + energyStore);

        // And from Python.
        for (String line : List.of(
                "python",
                "from logistics import network, move, Slot",
                "net = network()",
                "print(net.fluid_bus().amount('water'), net.fluid_interface().amount(), net.fluid_interface().contents()[0]['fluid'])",
                "print(net.energy_interface().stored, net.energy_bus().stored(), move(net.energy_interface(), net.energy_bus(), count=1000))",
                "print(move(net.fluid_bus(), net.fluid_interface(), 'water', 250), net.fluid_bus().kind)",
                // By tank number, without naming the fluid: the target's first tank holds the lava.
                "print(net.fluid_bus().amount(Slot(0)), move(net.fluid_bus().source(slot=0), net.fluid_interface()), net.fluid_interface().amount('lava'))",
                "move(net.energy_bus(), net.energy_interface(), count=0)")) {
            computer.submitLine(line);
        }
        helper.succeedWhen(() -> assertScreen(helper, computer, "1000 2000 minecraft:water\n", "150000 350000 1000\n", "250 fluid_bus\n", "500 500 500\n",
                "NetworkError: The count must be 1 or more, not 0."));
    }

    /**
     * A tunnel between two buses: a hopper pushing into one end fills the chest behind the
     * other, with no program moving anything; energy offered at a USB's face reaches the
     * energy store behind its partner; and closing the tunnel takes the faces away again.
     */
    private static void busTunnels(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerComputer computer = startAt(helper, new BlockPos(0, 1, 0));
        BlockPos cableA = new BlockPos(1, 1, 0);
        BlockPos cableB = new BlockPos(2, 1, 0);
        helper.setBlock(cableA, QboticsContent.CABLE_BLOCK.get());
        helper.setBlock(cableB, QboticsContent.CABLE_BLOCK.get());
        BlockPos at = helper.absolutePos(cableA);
        BlockPos bt = helper.absolutePos(cableB);
        // End a: under a hopper full of cobblestone. End b: at a chest.
        helper.setBlock(new BlockPos(1, 2, 0), Blocks.HOPPER);
        helper.setBlock(new BlockPos(2, 1, 1), Blocks.CHEST);
        CableBlock.attach(level, at, Kind.BUS, qbotics.network.Medium.ITEM, Direction.UP).setDeviceName("a");
        CableBlock.attach(level, bt, Kind.BUS, qbotics.network.Medium.ITEM, Direction.SOUTH).setDeviceName("b");
        // A USB at an energy store, and an Energy Serial Bus at another.
        helper.setBlock(new BlockPos(1, 1, 1), QboticsContent.ENERGY_INTERFACE_BLOCK.get());
        helper.setBlock(new BlockPos(2, 2, 0), QboticsContent.ENERGY_INTERFACE_BLOCK.get());
        CableBlock.attach(level, at, Kind.BUS, qbotics.network.Medium.UNIVERSAL, Direction.SOUTH).setDeviceName("u");
        CableBlock.attach(level, bt, Kind.BUS, qbotics.network.Medium.ENERGY, Direction.UP).setDeviceName("e");
        net.minecraft.world.Container hopper = helper.getBlockEntity(new BlockPos(1, 2, 0), net.minecraft.world.level.block.entity.HopperBlockEntity.class);
        net.minecraft.world.Container chest = helper.getBlockEntity(new BlockPos(2, 1, 1), net.minecraft.world.level.block.entity.ChestBlockEntity.class);
        qbotics.block.EnergyInterfaceBlockEntity far = helper.getBlockEntity(new BlockPos(2, 2, 0), qbotics.block.EnergyInterfaceBlockEntity.class);
        for (String line : List.of(
                "python",
                "from logistics import network",
                "import time",
                "net = network()",
                "a, b = net.bus('a'), net.bus('b')",
                "print('before', a.tunneled, a.tunnel_end)",
                "a.tunnel(b)",
                "print('tunnel', a.tunneled, a.tunnel_end.name, b.tunnel_end.name)",
                "net.usb('u').tunnel('e')",
                "print('energy', net.usb('u').tunnel_end.kind)",
                // An item bus and an energy bus carry nothing in common.
                "exec(\"try: a.tunnel('e')\\nexcept Exception as e: print('refused', 'nothing in common' in str(e))\")")) {
            computer.submitLine(line);
        }
        helper.runAfterDelay(20, () -> hopper.setItem(0, new ItemStack(Items.COBBLESTONE, 5)));
        boolean[] closed = {false};
        helper.succeedWhen(() -> {
            assertScreen(helper, computer, "before False None\n", "tunnel True b a\n", "energy energy_bus\n", "refused True\n");
            helper.assertTrue(chest.countItem(Items.COBBLESTONE) == 5 && hopper.isEmpty(),
                    "the hopper should have pushed all 5 through the tunnel into the chest; the chest has " + chest.countItem(Items.COBBLESTONE));
            // Energy offered at the USB's face goes into the store behind the energy bus; fluid isn't carried.
            qbotics.network.EnergyPort energyFace = qbotics.Qbotics.platform().energyPort(level, at, Direction.SOUTH);
            helper.assertTrue(energyFace != null && energyFace.insert(1000, false) == 1000 && far.stored() >= 1000,
                    "energy should cross the tunnel: " + far.stored());
            qbotics.network.FluidPort fluidFace = qbotics.Qbotics.platform().fluidPort(level, at, Direction.SOUTH);
            helper.assertTrue(fluidFace == null || fluidFace.insert(net.minecraft.world.level.material.Fluids.WATER, 1000, true) == 0,
                    "a USB tunnelled to an energy bus carries no fluid");
            // Both ends' lights say connected.
            CablePort a = helper.getBlockEntity(cableA, PortBlockEntity.class).port(Direction.UP);
            CablePort b = helper.getBlockEntity(cableB, PortBlockEntity.class).port(Direction.SOUTH);
            helper.assertTrue(closed[0] || (a.tunnelLive() && b.tunnelLive()), "both ends of a working tunnel should show connected");
            // Closing it, at both ends: the lights go off.
            if (!closed[0]) {
                closed[0] = true;
                qbotics.network.Tunnels.unlink(level, a);
            }
            helper.assertTrue(b.tunnel() == null && !a.tunnelLive() && !b.tunnelLive()
                    && qbotics.Qbotics.platform().itemPort(level, at, Direction.UP) == null,
                    "after closing, neither end should be in a tunnel or lit, and the face should offer nothing");
        });
    }

    /**
     * A Universal Serial Bus is an item, a fluid and an energy bus in one plate: found as
     * any of them, it moves whatever the other end of a move holds, and comes off as itself.
     */
    private static void universalSerialBus(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerComputer computer = startAt(helper, new BlockPos(0, 1, 0));
        // On the network: an interface, a fluid interface and an energy interface beside the computer.
        helper.setBlock(new BlockPos(0, 1, 1), QboticsContent.INTERFACE_BLOCK.get());
        helper.setBlock(new BlockPos(0, 1, 2), QboticsContent.FLUID_INTERFACE_BLOCK.get());
        helper.setBlock(new BlockPos(0, 2, 0), QboticsContent.ENERGY_INTERFACE_BLOCK.get());
        // One cable carrying three USBs: at a chest, at a tank and at an energy store (none of them on the network).
        BlockPos cable = new BlockPos(1, 1, 0);
        helper.setBlock(cable, QboticsContent.CABLE_BLOCK.get());
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.CHEST);
        helper.setBlock(new BlockPos(2, 1, 0), QboticsContent.FLUID_INTERFACE_BLOCK.get());
        helper.setBlock(new BlockPos(1, 0, 0), QboticsContent.ENERGY_INTERFACE_BLOCK.get());
        qbotics.network.Medium universal = qbotics.network.Medium.UNIVERSAL;
        BlockPos at = helper.absolutePos(cable);
        CableBlock.attach(level, at, Kind.BUS, universal, Direction.SOUTH).setDeviceName("u_chest");
        CableBlock.attach(level, at, Kind.BUS, universal, Direction.EAST).setDeviceName("u_tank");
        CableBlock.attach(level, at, Kind.BUS, universal, Direction.DOWN).setDeviceName("u_power");
        helper.getBlockEntity(new BlockPos(1, 1, 1), net.minecraft.world.level.block.entity.ChestBlockEntity.class).setItem(0, new ItemStack(Items.COAL, 12));
        helper.getBlockEntity(new BlockPos(0, 1, 1), InterfaceBlockEntity.class).setFilter(0, Items.COAL);
        helper.getBlockEntity(new BlockPos(2, 1, 0), qbotics.block.FluidInterfaceBlockEntity.class)
                .insert(net.minecraft.world.level.material.Fluids.WATER, 3000, false);
        helper.getBlockEntity(new BlockPos(1, 0, 0), qbotics.block.EnergyInterfaceBlockEntity.class).insert(500_000, false);
        for (String line : List.of(
                "python",
                "from logistics import network, move",
                "net = network()",
                "store, tanks, cell = net.interface(), net.fluid_interface(), net.energy_interface()",
                "c, t, p = net.usb('u_chest'), net.usb('u_tank'), net.usb('u_power')",
                "print('kinds', c.kind, t.fluid.kind, p.energy.kind, net.bus('u_chest').kind, net.fluid_bus('u_tank').kind)",
                "print('items', c.count('coal'), move(c, store, 'coal', 5), store.count('coal'))",
                "print('fluid', t.fluid.amount('water'), move(t, tanks, 'water', 1000), tanks.amount('water'))",
                "print('energy', p.energy.stored(), move(p, cell, count=1000), cell.stored)",
                "print('back', move(store, c, 'coal', 2), c.count('coal'), net.fluid_bus('u_tank').refresh().kind)",
                "print('listed', len(net.usbs()), len(net.buses()), sorted(d.kind for d in net.devices())[-1])",
                // USB to USB: a fluid's name makes it a fluid move (the chest holds no fluid, so it is refused as one).
                "exec(\"try: move(t, c, 'water', 100)\\nexcept Exception as e: print('usb2usb', 'holds no fluid' in str(e))\")")) {
            computer.submitLine(line);
        }
        helper.succeedWhen(() -> {
            assertScreen(helper, computer, "kinds usb fluid_bus energy_bus bus fluid_bus\n", "items 12 5 5\n", "fluid 3000 1000 1000\n",
                    "energy 500000 1000 1000\n", "back 2 9 fluid_bus\n", "listed 3 0 usb\n", "usb2usb True\n");
            // Taken off, it is a USB again.
            ItemStack taken = CableBlock.detach(level, at, Direction.DOWN);
            helper.assertTrue(taken.is(QboticsContent.plateItem(Kind.BUS, universal)), "the plate should come off as a USB, not " + taken);
        });
    }

    /**
     * Aiming by slot and by face instead of by name: what a program does when it doesn't know
     * (or care) which item is there, as with a furnace's product.
     */
    private static void slotsAndFaces(GameTestHelper helper) {
        BlockPos chestPos = new BlockPos(2, 1, 1);
        ServerComputer computer = startAt(helper, new BlockPos(0, 1, 0));
        helper.setBlock(new BlockPos(1, 1, 0), QboticsContent.INTERFACE_BLOCK.get());
        setPort(helper, new BlockPos(1, 1, 1), Kind.BUS, Direction.EAST);
        helper.setBlock(chestPos, Blocks.CHEST);
        InterfaceBlockEntity store = helper.getBlockEntity(new BlockPos(1, 1, 0), InterfaceBlockEntity.class);
        store.setFilter(0, Items.COAL);
        store.setFilter(1, Items.IRON_INGOT);
        store.setFilter(2, Items.COAL);
        net.minecraft.world.Container chest = helper.getBlockEntity(chestPos, net.minecraft.world.level.block.entity.ChestBlockEntity.class);
        chest.setItem(0, new ItemStack(Items.COAL, 10));
        chest.setItem(3, new ItemStack(Items.IRON_INGOT, 5));
        chest.setItem(5, new ItemStack(Items.COAL, 7));

        for (String line : List.of(
                "python",
                "from logistics import network, move, Face, Slot",
                "net = network()",
                "bus = net.bus()",
                "store = net.interface()",
                "print(bus.count(Slot(0)), bus.count('coal'), bus.count(slot=3), bus.count())",
                // Whatever is in a slot, without naming it.
                "print(move(bus.source(slot=3), store), store.count(Slot(1)))",
                // A named item, from one slot of the chest into one slot of the interface.
                "print(move(bus.source(slot=5), store.destination(slot=2), 'coal', 4), store.count(slot=2), bus.count(Slot(5)))",
                "print(move(bus.source(slot=Slot(0)), store), store.count('coal'))",
                // The other way: from one slot of the interface into one slot of the chest.
                "print(move(store.source(slot=1), bus.destination(slot=8)), bus.count(Slot(8)))",
                "print(move(store.source(slot=2), bus.destination(slot=0), count=3), bus.count(Slot(0)))",
                "move(bus.source(slot=40), store)",
                // Whatever can be reached through a face: here, everything left in the chest.
                "print(move(bus.source(face=Face.TOP), store), bus.count())")) {
            computer.submitLine(line);
        }
        helper.succeedWhen(() -> {
            assertScreen(helper, computer, "10 17 5 22\n", "5 5\n", "4 4 3\n", "10 14\n", "5 5\n", "3 3\n",
                    "has slots 0 to 26 here; there is no slot 40.", "11 0\n");
            // Coal tops up the slot that already holds coal (slot 2) before starting an empty one.
            helper.assertTrue(chest.isEmpty() && store.getItem(0).isEmpty() && store.getItem(1).getCount() == 5
                    && store.getItem(2).getCount() == 17, "everything should have ended up in the interface: " + store.getItem(0)
                    + ", " + store.getItem(1) + ", " + store.getItem(2));
        });
    }

    /**
     * Filters in place of names, in the words of JEI's search box: a tag, a mod, wildcards,
     * several words, "not", and alternatives. They work for counting, moving and slot filters.
     */
    private static void nameFilters(GameTestHelper helper) {
        BlockPos chestPos = new BlockPos(2, 1, 1);
        ServerComputer computer = startAt(helper, new BlockPos(0, 1, 0));
        helper.setBlock(new BlockPos(1, 1, 0), QboticsContent.INTERFACE_BLOCK.get());
        setPort(helper, new BlockPos(1, 1, 1), Kind.BUS, Direction.EAST);
        helper.setBlock(chestPos, Blocks.CHEST);
        InterfaceBlockEntity store = helper.getBlockEntity(new BlockPos(1, 1, 0), InterfaceBlockEntity.class);
        net.minecraft.world.Container chest = helper.getBlockEntity(chestPos, net.minecraft.world.level.block.entity.ChestBlockEntity.class);
        chest.setItem(0, new ItemStack(Blocks.OAK_LOG, 8));
        chest.setItem(1, new ItemStack(Blocks.BIRCH_LOG, 4));
        chest.setItem(2, new ItemStack(Items.COAL, 6));
        chest.setItem(3, new ItemStack(Blocks.IRON_ORE, 3));
        chest.setItem(4, new ItemStack(Blocks.OAK_PLANKS, 5));

        for (String line : List.of(
                "python",
                "from logistics import network, move",
                "net = network()",
                "bus = net.bus()",
                "store = net.interface()",
                "store.set_slot_filter(0, '#minecraft:logs')",
                "store.set_slot_filter(1, '#logs')",
                "store.set_slot_filter(2, '*_ore')",
                "store.set_slot_filter(3, 'coal | charcoal')",
                "print(store.filters()[:4])",
                "print(bus.count('#minecraft:logs'), bus.count('*_log'), bus.count('@minecraft'), bus.count('@minecraft -*_log -coal'), bus.count('oak_*'))",
                "print(move(bus, store, '#logs'), store.count('*_log'), move(bus, store, '*_ore | coal'), store.count())",
                "store.set_slot_filter(4, '@nosuchmod')",
                "move(bus, store, 'irn_ingot')",
                // A block tag (as F3 shows) matches no item: the error says so.
                "exec(\"try: bus.count('#minecraft:mineable/pickaxe')\\nexcept Exception as e: print('hint', 'is a block tag' in str(e), 'Probe' in str(e))\")",
                "exec(\"try: bus.count('#nosuchmod:things')\\nexcept Exception as e: print('hint2', 'carries the tag' in str(e))\")",
                "print('left', bus.count('@Minecraft'), bus.count('$minecraft:planks'))",
                // A source with its own lists: only logs, but not birch. Then all-or-nothing amounts.
                "print(move(store.source(only=['#minecraft:logs'], without='birch_*'), bus), bus.count('oak_log'))",
                "move(store, bus, 'coal', need=50)",
                "print(move(store, bus, 'coal', need=6), move(store, bus.destination(only='*_ore'), need=3, wait=5), store.count())",
                // A pool of sources, drawn on in order; the destination refuses what its own list leaves out.
                "pool = [store.source(), bus.source(only='coal')]",
                "print(move(pool, net.interface().destination(without='coal'), 'coal', 4), move(pool, store, 'coal', 4), store.count('coal'))")) {
            computer.submitLine(line);
        }
        helper.succeedWhen(() -> {
            assertScreen(helper, computer, "['#minecraft:logs', '#logs', '*_ore', 'coal | charcoal']\n", "12 12 26 8 13\n", "12 12 9 21\n",
                    "NetworkError: No item passes the filter '@nosuchmod'.", // (The whole message is longer than the terminal is wide, so it wraps; the start is enough here.)
                    "NetworkError: There is no item called 'irn_ingot'. Did you mean", "left 5 5\n", "hint True True\n", "hint2 True\n", "8 8\n",
                    "NetworkError: Only 6 of the 50 needed can be moved right now. Nothing was moved.", "6 3 4\n", "0 4 4\n");
            var items = net.minecraft.core.registries.BuiltInRegistries.ITEM;
            helper.assertTrue(qbotics.network.NameMatch.closest(items, "iron_ore_raw").contains("minecraft:raw_iron")
                    && qbotics.network.NameMatch.closest(items, "oak_plank").getFirst().equals("minecraft:oak_planks")
                    && qbotics.network.NameMatch.closest(items, "zzzzqqqq").isEmpty(),
                    "suggestions were " + qbotics.network.NameMatch.closest(items, "iron_ore_raw") + " and "
                    + qbotics.network.NameMatch.closest(items, "oak_plank"));
            helper.assertTrue(store.getItem(0).isEmpty() && store.getItem(1).is(Blocks.BIRCH_LOG.asItem()) && store.getItem(2).isEmpty()
                    && store.getItem(3).getCount() == 4 && chest.countItem(Blocks.OAK_PLANKS.asItem()) == 5 && chest.countItem(Items.COAL) == 2,
                    "at the end the interface should hold the birch logs and 4 coal: " + store.getItem(0) + ", " + store.getItem(1) + ", "
                    + store.getItem(2) + ", " + store.getItem(3));
            // A slot open for a tag takes any item with that tag, and nothing else.
            helper.assertTrue(store.canPlaceItem(0, new ItemStack(Blocks.SPRUCE_LOG)) && !store.canPlaceItem(0, new ItemStack(Items.COAL))
                    && store.canPlaceItem(3, new ItemStack(Items.CHARCOAL)), "slot filters should follow the tag and the alternatives");
        });
    }

    /**
     * ssh: one computer's terminal drives another's. The other computer decides who gets in:
     * its owner's computers, or anyone with the password, or nobody.
     */
    private static void remoteLogin(GameTestHelper helper) {
        ServerComputer a = startAt(helper, new BlockPos(0, 1, 0));
        BlockPos otherPos = new BlockPos(1, 1, 0);
        helper.setBlock(otherPos, QboticsContent.COMPUTER_BLOCK.get());
        ComputerBlockEntity other = helper.getBlockEntity(otherPos, ComputerBlockEntity.class);
        other.setDeviceName("beta");
        // ssh switches the other computer on; this fetches the same running computer to watch its screen.
        ServerComputer b = ComputerManager.get(helper.getLevel().getServer()).getOrStart(other);
        java.util.function.BiFunction<String, String, Integer> times = (text, part) -> text.split(java.util.regex.Pattern.quote(part), -1).length - 1;

        a.submitLine("ssh beta");
        // Each step waits for the screens to show the one before: the two computers run on their own threads.
        int[] step = {0};
        helper.succeedWhen(() -> {
            String local = screen(a);
            String remote = screen(b);
            if (step[0] == 0 && remote.contains("[ssh] login from 192.168.1.")) {
                a.submitLine("mkdir made_from_afar");
                a.submitLine("pwd");
                step[0] = 1;
            }
            if (step[0] == 1 && remote.contains("$ pwd\n/\n")) {
                a.submitLine("exit");
                step[0] = 2;
            }
            if (step[0] == 2 && local.contains("Connection to beta closed.")) {
                // Nobody: remote login switched off.
                other.setRemoteLogin(ComputerBlockEntity.RemoteLogin.OFF);
                a.submitLine("ssh beta");
                step[0] = 3;
            }
            if (step[0] == 3 && local.contains("doesn't allow remote login")) {
                // Another player's computer, owner-only: refused.
                other.setOwner(UUID.randomUUID());
                other.setRemoteLogin(ComputerBlockEntity.RemoteLogin.OWNER);
                a.submitLine("ssh beta");
                step[0] = 4;
            }
            if (step[0] == 4 && local.contains("only lets its owner's computers log in")) {
                // With a password set, the wrong one is refused and the right one gets in.
                other.setPassword("open sesame");
                other.setRemoteLogin(ComputerBlockEntity.RemoteLogin.PASSWORD);
                a.submitLine("ssh beta");
                a.submitLine("guess");
                step[0] = 5;
            }
            if (step[0] == 5 && local.contains("Wrong password.")) {
                a.submitLine("ssh beta");
                a.submitLine("open sesame");
                step[0] = 6;
            }
            if (step[0] == 6 && times.apply(remote, "[ssh] login from") == 2) {
                a.submitLine("exit");
                step[0] = 7;
            }
            helper.assertTrue(step[0] == 7 && times.apply(screen(a), "Connection to beta closed.") == 2,
                    "stopped at step " + step[0] + "; this computer's screen:\n" + local + "\nthe other's:\n" + remote);
            helper.assertTrue(!local.contains("guess") && !local.contains("open sesame"), "a typed password must not be written out:\n" + local);
            try {
                b.drive().call(qbotics.engine.HostFileSystem.STAT, "/made_from_afar", new byte[0]);
            } catch (qbotics.engine.HostFileSystem.FsException e) {
                helper.fail("the folder should have been made on the other computer's drive");
            }
        });
    }

    /**
     * The Experience Interface: levels go in and come out as exactly the points they cost,
     * a lock says who may use it, and the experience travels with the block when it is picked up.
     */
    private static void experienceStore(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 1);
        ServerLevel level = helper.getLevel();
        helper.setBlock(pos, QboticsContent.EXPERIENCE_INTERFACE_BLOCK.get());
        qbotics.block.ExperienceInterfaceBlockEntity store = helper.getBlockEntity(pos, qbotics.block.ExperienceInterfaceBlockEntity.class);
        // The game's own numbers: level 30 costs 1,395 points; level 31, 1,507.
        helper.assertTrue(qbotics.block.Experience.pointsAtLevel(16) == 352 && qbotics.block.Experience.pointsAtLevel(30) == 1395
                && qbotics.block.Experience.pointsAtLevel(31) == 1507 && qbotics.block.Experience.levelAt(1506) == 30
                && qbotics.block.Experience.levelAt(1507) == 31 && qbotics.block.Experience.levelAt(0) == 0, "the level arithmetic is wrong");

        net.minecraft.server.level.ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setExperienceLevels(30);
        player.setExperiencePoints(40);
        long before = qbotics.block.Experience.pointsOf(player);
        // Storing 5 levels takes the top five (25 to 30), and leaves the progress alone.
        long stored = store.store(player, 5);
        helper.assertTrue(stored == 1395 - 910 && store.points() == stored && qbotics.block.Experience.pointsOf(player) == before - stored
                && player.experienceLevel == 25, "storing 5 levels from 30 should move 485 points, but moved " + stored + " leaving level "
                + player.experienceLevel);
        // Taking them back is exact: nothing gained, nothing lost.
        store.give(player, 1000);
        helper.assertTrue(store.points() == 0 && qbotics.block.Experience.pointsOf(player) == before && player.experienceLevel == 30,
                "taking everything back should restore the player exactly: level " + player.experienceLevel);
        store.store(player, Integer.MAX_VALUE);
        helper.assertTrue(store.points() == before && player.experienceLevel == 0 && qbotics.block.Experience.pointsOf(player) == 0,
                "Store All should empty the player");
        // It holds far more than a player ever could.
        helper.assertTrue(store.add(Long.MAX_VALUE) > 1_000_000_000_000_000L && store.take(Long.MAX_VALUE) > 0 && store.points() == 0,
                "the store should hold a practically unlimited amount");
        store.add(before);

        // The lock: a block nobody owns is open; once owned, strangers are kept out until it is made public.
        var server = level.getServer();
        UUID owner = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        helper.assertTrue(store.mayUse(server, stranger) && store.mayChangeLock(stranger), "a block nobody owns is open to all");
        store.setOwnerForTest(owner);
        helper.assertTrue(store.mayUse(server, owner) && !store.mayUse(server, stranger) && !store.mayChangeLock(stranger)
                && store.mayChangeLock(owner), "a private store is its owner's alone");
        store.setLock(RadioNetwork.Access.PUBLIC);
        helper.assertTrue(store.mayUse(server, stranger) && !store.mayChangeLock(stranger), "a public store lets anyone use it, not relock it");

        // Picking the block up keeps the experience in the item.
        List<ItemStack> drops = net.minecraft.world.level.block.Block.getDrops(helper.getBlockState(pos), level, helper.absolutePos(pos), store);
        helper.assertTrue(drops.size() == 1 && Long.valueOf(before).equals(drops.getFirst().get(QboticsContent.XP_POINTS.get())),
                "the dropped item should carry the experience: " + drops);
        // It is a device on a network like the other stores.
        helper.assertTrue(networkAt(helper, pos).devices().getFirst().kindWord().equals("experience_interface"), "it should be a network device");
        helper.succeed();
    }

    /**
     * The test stage (/qbotics stage): it builds, and every demo program it puts on its main
     * computer runs through without an error and prints what the stage's contents say it should.
     */
    private static void testStage(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // High above the test's own space, so the stage's 15 by 9 blocks disturb nothing.
        BlockPos corner = helper.absolutePos(new BlockPos(0, 30, 0));
        // Owned by someone, as the command makes it: a bus with no owner won't open screens.
        String built = qbotics.stage.TestStage.build(level, corner, UUID.randomUUID());
        helper.assertTrue(built.contains("Stage built"), built);
        ComputerBlockEntity main = (ComputerBlockEntity) level.getBlockEntity(corner.offset(1, 0, 2));
        CableNetwork network = CableNetwork.at(level, main.getBlockPos());
        List<String> names = network.devices().stream().map(NetworkDevice::deviceName).sorted().toList();
        helper.assertTrue(names.equals(List.of("anvil", "battery", "big_tank", "breaker", "chest", "crafter", "enchanter", "furnace", "inbox", "lamp", "machine",
                "main", "main_antenna", "outbox", "placer", "shared", "store", "tanks", "xp")),"the main network should hold exactly the stage's devices, but had " + names);

        ServerComputer computer = ComputerManager.get(level.getServer()).getOrStart(main);
        for (String demo : List.of("1_items", "2_filters", "3_fluid", "4_energy", "5_radio", "6_devices", "7_sources", "8_crafting", "9_world", "10_levels", "13_drives")) {
            computer.submitLine("run demos/" + demo + ".py");
        }
        computer.submitLine("pip install -r requirements.txt");
        computer.submitLine("pwd");
        helper.succeedWhen(() -> {
            Terminal.State state = computer.terminal().full();
            String text = String.join("\n", state.scrolled()) + "\n" + String.join("\n", state.lines());
            helper.assertTrue(text.contains("$ pwd"), "the demos are still running; so far:\n" + text);
            helper.assertTrue(!text.contains("Traceback") && !text.contains("Error"), "a demo failed:\n" + text);
            for (String expected : List.of("163 items; 32 are coal", "chest to furnace: 8", "moved to the store: 24", "5000 mB, 3000 of it water",
                    "battery now holds 250000 of 2000000", "Sent: True", "{'echo': {'hello': 'from main'}}", "experience_interface",
                    "into the furnace: 4", "all but gold: 20", "As expected: Only 6 of the 50 needed", "Exactly 6 gold: 6",
                    "A log into the grid: 1", "Planks taken out: 4", "runs for 8 sticks: 2", "Made: {'minecraft:stick': 8}", "holds 8 sticks",
                    "The lamp blinked three times.", "Placed: minecraft:cobblestone", "In front of the breaker: minecraft:cobblestone",
                    "Broke it and got: {'minecraft:cobblestone': 1}",
                    "Repaired: 1\n", "Enchanted: 1\n", "Drives in the bay: ['drive2', 'lib']", "hello, main, from the shared drive",
                    "installed stock (2 files)\ninstalled greetings (1 file)")) {
                helper.assertTrue(text.contains(expected), "the demos should have printed '" + expected + "' but printed:\n" + text);
            }
        });
    }

    /**
     * Screen access: a bus opens a crafting table's screen as its owner, a program works the
     * slots, and what is left in them comes back when the screen closes. Then a Recipe.
     */
    private static void screenAccess(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerComputer computer = startAt(helper, new BlockPos(0, 1, 0));
        helper.setBlock(new BlockPos(1, 1, 0), QboticsContent.INTERFACE_BLOCK.get());
        setPort(helper, new BlockPos(1, 1, 1), Kind.BUS, Direction.EAST);
        helper.setBlock(new BlockPos(2, 1, 1), Blocks.CRAFTING_TABLE);
        CablePort bus = helper.getBlockEntity(new BlockPos(1, 1, 1), PortBlockEntity.class).port(Direction.EAST);
        InterfaceBlockEntity store = helper.getBlockEntity(new BlockPos(1, 1, 0), InterfaceBlockEntity.class);
        for (int slot = 0; slot < InterfaceBlockEntity.SLOTS; slot++) {
            store.setFilter(slot, qbotics.network.NameMatch.parse(net.minecraft.core.registries.BuiltInRegistries.ITEM, "*"));
        }
        store.setItem(0, new ItemStack(Blocks.OAK_PLANKS, 8));
        store.setItem(1, new ItemStack(Items.COAL, 20));

        // A bus nobody owns has nobody to act for.
        BlockPos computerAt = helper.absolutePos(new BlockPos(0, 1, 0));
        String refused = qbotics.network.NetworkCalls.handle(level.getServer(), level.dimension(), computerAt, "[\"screen_open\",\"" + bus.mac() + "\"]");
        helper.assertTrue(refused.contains("no owner to act for"), "a bus with no owner should not open screens: " + refused);
        bus.onPlaced(UUID.randomUUID());
        String mac = bus.mac();

        for (String line : List.of(
                "python",
                "from logistics import network, move, Slot, Recipe",
                "net = network()",
                "table = net.bus()",
                "store = net.interface()",
                // By hand: two planks, one above the other, make four sticks.
                "screen = table.open_screen()",
                "print(len(screen.slots()), screen.put(Slot(1), 'oak_planks', sources=store), screen.put(Slot(4), 'oak_planks', sources=store))",
                "print(sorted((e['slot'], e['item'], e['count']) for e in screen.contents()))",
                "print(screen.take(Slot(0), store), store.count('stick'), store.count('oak_planks'))",
                // Left in the grid when the screen closes: it comes back.
                "print(screen.put(Slot(5), 'oak_planks', 3, sources=store), store.count('oak_planks'), screen.close(), store.count('oak_planks'))",
                "screen.contents()",
                // A recipe: nine coal make a block of coal.
                "blocks = Recipe(sources=store, destination=store)",
                "for n in range(1, 10): blocks.ingredient('coal', Slot(n))",
                "",
                "blocks.result(Slot(0), 'coal_block', 1)",
                "print(blocks.max_runs(), blocks.can_craft(2), blocks.can_craft(3), blocks.runs_for(2), len(blocks.ingredients), blocks.results[0]['amount'])",
                "with table.open_screen() as screen: print(blocks.craft(2, screen))",
                "",
                "print(store.count('coal'), store.count('coal_block'))",
                // Not enough for another run: an error, and what was laid out comes back when the screen closes.
                "with table.open_screen() as screen: blocks.craft(1, screen)",
                "",
                "print('after', store.count('coal'))")) {
            computer.submitLine(line);
        }
        helper.succeedWhen(() -> assertScreen(helper, computer, "10 1 1\n",
                "[(0, 'minecraft:stick', 4), (1, 'minecraft:oak_planks', 1), (4, 'minecraft:oak_p",
                "4 4 6\n", "3 3 0 6\n", "This bus has no screen open", "2 True False 2 9 1\n", "{'minecraft:coal_block': 2}\n", "2 2\n",
                "Only 0 of the 1 needed can be moved right now", "after 2\n"));
    }

    /**
     * Screen access beyond the crafting table: a smithing table, a grindstone, a stonecutter
     * and a loom, each through its own bus. The last two choose with a button press.
     */
    private static void vanillaScreens(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerComputer computer = startAt(helper, new BlockPos(0, 1, 0));
        helper.setBlock(new BlockPos(1, 1, 0), QboticsContent.INTERFACE_BLOCK.get());
        helper.setBlock(new BlockPos(2, 1, 0), QboticsContent.INTERFACE_BLOCK.get());
        helper.setBlock(new BlockPos(2, 1, 1), Blocks.SMITHING_TABLE);
        helper.setBlock(new BlockPos(0, 1, 1), Blocks.GRINDSTONE);
        helper.setBlock(new BlockPos(1, 1, 2), Blocks.STONECUTTER);
        helper.setBlock(new BlockPos(1, 2, 1), Blocks.LOOM);
        setPort(helper, new BlockPos(1, 1, 1), Kind.BUS, Direction.EAST);
        BlockPos cableAt = helper.absolutePos(new BlockPos(1, 1, 1));
        CableBlock.attach(level, cableAt, Kind.BUS, Direction.WEST);
        CableBlock.attach(level, cableAt, Kind.BUS, Direction.SOUTH);
        CableBlock.attach(level, cableAt, Kind.BUS, Direction.UP);
        PortBlockEntity plates = helper.getBlockEntity(new BlockPos(1, 1, 1), PortBlockEntity.class);
        UUID owner = UUID.randomUUID();
        Map<Direction, String> names = Map.of(Direction.EAST, "smith", Direction.WEST, "grind", Direction.SOUTH, "cutter", Direction.UP, "loom");
        names.forEach((side, name) -> {
            CablePort plate = plates.port(side);
            plate.onPlaced(owner);
            plate.setDeviceName(name);
        });
        String[] stores = {"a", "b"};
        for (int i = 0; i < 2; i++) {
            InterfaceBlockEntity store = helper.getBlockEntity(new BlockPos(1 + i, 1, 0), InterfaceBlockEntity.class);
            store.setDeviceName(stores[i]);
            for (int slot = 0; slot < InterfaceBlockEntity.SLOTS; slot++) {
                store.setFilter(slot, qbotics.network.NameMatch.parse(net.minecraft.core.registries.BuiltInRegistries.ITEM, "*"));
            }
        }
        InterfaceBlockEntity a = helper.getBlockEntity(new BlockPos(1, 1, 0), InterfaceBlockEntity.class);
        a.setItem(0, new ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE));
        a.setItem(1, new ItemStack(Items.DIAMOND_SWORD));
        a.setItem(2, new ItemStack(Items.NETHERITE_INGOT));
        a.setItem(3, new ItemStack(Items.IRON_SWORD));
        a.setItem(4, new ItemStack(Items.IRON_SWORD));
        a.setItem(5, new ItemStack(Blocks.STONE, 4));
        a.setItem(6, new ItemStack(Items.WHITE_BANNER));
        a.setItem(7, new ItemStack(Items.RED_DYE, 2));
        a.setItem(8, new ItemStack(Items.WHITE_BANNER));

        String program = String.join("\n",
                "from logistics import network, Slot, Recipe",
                "net = network()",
                "stock = [net.interface('a'), net.interface('b')]",
                "def count(name):",
                "    return sum(store.count(name) for store in stock)",
                "",
                "# Smithing table: template 0, base 1, addition 2, product 3.",
                "with net.bus('smith').open_screen() as s:",
                "    print('smith slots', s.slots())",
                "    s.put(Slot(0), 'netherite_upgrade_smithing_template', sources=stock)",
                "    s.put(Slot(1), 'diamond_sword', sources=stock)",
                "    s.put(Slot(2), 'netherite_ingot', sources=stock)",
                "    print('smith made', s.take(Slot(3), stock))",
                "",
                "# Grindstone: two inputs, 0 and 1, product 2. Two swords make one.",
                "with net.bus('grind').open_screen() as s:",
                "    print('grind slots', s.slots())",
                "    s.put(Slot(0), 'iron_sword', sources=stock)",
                "    s.put(Slot(1), 'iron_sword', sources=stock)",
                "    print('grind made', s.take(Slot(2), stock))",
                "",
                "# Stonecutter: input 0, product 1; the choice is a button, found by trying.",
                "with net.bus('cutter').open_screen() as s:",
                "    print('cutter slots', s.slots())",
                "    s.put(Slot(0), 'stone', 1, sources=stock)",
                "    chosen = None",
                "    for n in range(40):",
                "        s.press(n)",
                "        if [e for e in s.contents() if e['slot'] == 1 and e['item'] == 'minecraft:stone_bricks']:",
                "            chosen = n",
                "            break",
                "    print('cutter chose', chosen is not None, 'made', s.take(Slot(1), stock))",
                "",
                "# Loom: banner 0, dye 1, pattern item 2, product 3; patterns are buttons.",
                "with net.bus('loom').open_screen() as s:",
                "    print('loom slots', s.slots())",
                "    s.put(Slot(0), 'white_banner', sources=stock)",
                "    s.put(Slot(1), 'red_dye', sources=stock)",
                "    print('loom pressed', s.press(1))",
                "    print('loom made', s.take(Slot(3), stock))",
                "",
                "# The same choices as recipes, with their buttons.",
                "bricks = Recipe(sources=stock, destination=stock)",
                "bricks.ingredient('stone', Slot(0)).button(chosen).result(Slot(1), 'stone_bricks', 1)",
                "banner = Recipe(sources=stock, destination=stock)",
                "banner.ingredient('white_banner', Slot(0)).ingredient('red_dye', Slot(1)).button(1, times=3).result(Slot(3), 'white_banner', 1)",
                "print('buttons', bricks.buttons[0]['button'] == chosen, banner.buttons[0]['button'], banner.buttons[0]['times'])",
                "with net.bus('cutter').open_screen() as s:",
                "    print('bricks made', bricks.craft(2, s))",
                "with net.bus('loom').open_screen() as s:",
                "    print('banner made', banner.craft(1, s))",
                "",
                "print('final', count('netherite_sword'), count('iron_sword'), count('stone_bricks'), count('stone'), count('white_banner'), count('red_dye'))",
                "");
        try {
            computer.drive().call(qbotics.engine.HostFileSystem.WRITE, "/screens.py", program.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (qbotics.engine.HostFileSystem.FsException e) {
            throw new IllegalStateException("could not write the test program", e);
        }
        computer.submitLine("run /screens.py");
        helper.succeedWhen(() -> {
            String screen = screen(computer);
            helper.assertTrue(!screen.contains("Traceback"), "the program failed:\n" + screen);
            assertScreen(helper, computer, "smith slots [0, 1, 2, 3]", "smith made 1\n", "grind slots [0, 1, 2]", "grind made 1\n",
                    "cutter slots [0, 1]", "cutter chose True made 1\n", "loom slots [0, 1, 2, 3]", "loom pressed True", "loom made 1\n",
                    "buttons True 1 3\n", "bricks made {'minecraft:stone_bricks': 2}", "banner made {'minecraft:white_banner': 1}",
                    "final 1 1 3 1 2 0\n");
        });
    }

    /**
     * Paying levels on screens (design doc §11.0.4): the anvil charges when its product is
     * taken, the enchanting table when an offer is pressed. Each is paid from a named
     * Experience Interface, and only what the block really charges is taken.
     */
    private static void screenExperience(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerComputer computer = startAt(helper, new BlockPos(0, 1, 0));
        helper.setBlock(new BlockPos(1, 1, 0), QboticsContent.INTERFACE_BLOCK.get());
        helper.setBlock(new BlockPos(2, 1, 0), QboticsContent.EXPERIENCE_INTERFACE_BLOCK.get());
        helper.setBlock(new BlockPos(0, 1, 1), QboticsContent.EXPERIENCE_INTERFACE_BLOCK.get());
        helper.setBlock(new BlockPos(2, 1, 1), Blocks.ENCHANTING_TABLE);
        setPort(helper, new BlockPos(1, 1, 1), Kind.BUS, Direction.EAST);
        BlockPos cableAt = helper.absolutePos(new BlockPos(1, 1, 1));
        CableBlock.attach(level, cableAt, Kind.BUS, Direction.UP);
        // Resting on the cable, so it doesn't fall.
        helper.setBlock(new BlockPos(1, 2, 1), Blocks.ANVIL);
        PortBlockEntity plates = helper.getBlockEntity(new BlockPos(1, 1, 1), PortBlockEntity.class);
        UUID owner = UUID.randomUUID();
        Map.of(Direction.EAST, "table", Direction.UP, "anvil").forEach((side, name) -> {
            CablePort plate = plates.port(side);
            plate.onPlaced(owner);
            plate.setDeviceName(name);
        });
        InterfaceBlockEntity store = helper.getBlockEntity(new BlockPos(1, 1, 0), InterfaceBlockEntity.class);
        store.setDeviceName("store");
        for (int slot = 0; slot < InterfaceBlockEntity.SLOTS; slot++) {
            store.setFilter(slot, qbotics.network.NameMatch.parse(net.minecraft.core.registries.BuiltInRegistries.ITEM, "*"));
        }
        for (int i = 0; i < 2; i++) {
            ItemStack sword = new ItemStack(Items.IRON_SWORD);
            sword.setDamageValue(200);
            store.setItem(i, sword);
        }
        store.setItem(2, new ItemStack(Items.BOOK));
        store.setItem(3, new ItemStack(Items.LAPIS_LAZULI, 3));
        qbotics.block.ExperienceInterfaceBlockEntity poor = helper.getBlockEntity(new BlockPos(2, 1, 0), qbotics.block.ExperienceInterfaceBlockEntity.class);
        poor.setDeviceName("poor");
        poor.add(1);
        qbotics.block.ExperienceInterfaceBlockEntity bank = helper.getBlockEntity(new BlockPos(0, 1, 1), qbotics.block.ExperienceInterfaceBlockEntity.class);
        bank.setDeviceName("bank");
        bank.add(1000);

        String program = String.join("\n",
                "from logistics import network, Slot, NetworkError",
                "net = network()",
                "store = net.interface('store')",
                "bank = net.experience_interface('bank')",
                "poor = net.experience_interface('poor')",
                "def points(levels):",
                "    return levels * levels + 6 * levels    # the game's rule up to level 16",
                "",
                "with net.bus('anvil').open_screen() as s:",
                "    s.put(Slot(0), 'iron_sword', sources=store)",
                "    s.put(Slot(1), 'iron_sword', sources=store)",
                "    cost = s.cost()",
                "    print('anvil cost', cost > 0)",
                "    try:",
                "        s.take(Slot(2), store)",
                "    except NetworkError as e:",
                "        print('no xp:', e)",
                "    s.xp = poor",
                "    try:",
                "        s.take(Slot(2), store)",
                "    except NetworkError as e:",
                "        print('too poor:', e)",
                "    s.xp = [poor, bank]",
                "    before = bank.points",
                "    print('anvil took', s.take(Slot(2), store), before - bank.points == points(cost), poor.points)",
                "",
                "with net.bus('table').open_screen(xp=bank) as s:",
                "    s.put(Slot(0), 'book', 1, sources=store)",
                "    s.put(Slot(1), 'lapis_lazuli', 3, sources=store)",
                "    costs = s.cost()",
                "    print('offers', len(costs), costs[0] > 0)",
                "    before = bank.points",
                "    print('pressed', s.press(0))",
                "    print('charged one level', before - bank.points == points(costs[0]) - points(costs[0] - 1))",
                "    print('enchanted', [e['item'] for e in s.contents() if e['slot'] == 0])",
                "    s.take(Slot(0), store)",
                "",
                "print('left', store.count('lapis_lazuli'), store.count('enchanted_book'), store.count('iron_sword'))",
                "");
        try {
            computer.drive().call(qbotics.engine.HostFileSystem.WRITE, "/xp.py", program.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (qbotics.engine.HostFileSystem.FsException e) {
            throw new IllegalStateException("could not write the test program", e);
        }
        computer.submitLine("run /xp.py");
        helper.succeedWhen(() -> {
            String screen = screen(computer);
            helper.assertTrue(!screen.contains("Traceback"), "the program failed:\n" + screen);
            assertScreen(helper, computer, "anvil cost True", "no xp: The anvil asks", "too poor: The anvil asks 2 levels: having them takes 16 points, but poor holds",
                    "anvil took 1 True 1\n", "offers 3 True", "pressed True", "charged one level True",
                    "enchanted ['minecraft:enchanted_book']", "left 2 1 1\n");
        });
    }

    /**
     * The world plates (design doc §11.0.1): a Logic Pin powering a lamp and another reading
     * a redstone block, a Placer placing and pouring, and a Breaker facing the same space
     * breaking what was placed and taking up what was poured.
     */
    private static void worldPlates(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerComputer computer = startAt(helper, new BlockPos(0, 1, 0));
        helper.setBlock(new BlockPos(1, 1, 0), QboticsContent.INTERFACE_BLOCK.get());
        helper.setBlock(new BlockPos(0, 1, 1), QboticsContent.FLUID_INTERFACE_BLOCK.get());
        helper.setBlock(new BlockPos(1, 2, 1), Blocks.REDSTONE_LAMP);
        helper.setBlock(new BlockPos(0, 1, 2), Blocks.REDSTONE_BLOCK);
        helper.setBlock(new BlockPos(2, 1, 0), Blocks.STONE); // keeps poured water from running off
        for (BlockPos cable : List.of(new BlockPos(1, 1, 1), new BlockPos(1, 1, 2), new BlockPos(2, 1, 2))) {
            helper.setBlock(cable, QboticsContent.CABLE_BLOCK.get());
        }
        UUID owner = UUID.randomUUID();
        record Plate(BlockPos at, Kind kind, Direction side, String name) {
        }
        for (Plate plate : List.of(new Plate(new BlockPos(1, 1, 1), Kind.PIN, Direction.UP, "lamp"),
                new Plate(new BlockPos(1, 1, 1), Kind.PLACER, Direction.EAST, "placer"),
                new Plate(new BlockPos(1, 1, 2), Kind.PIN, Direction.WEST, "sensor"),
                new Plate(new BlockPos(2, 1, 2), Kind.BREAKER, Direction.NORTH, "breaker"))) {
            CablePort port = CableBlock.attach(level, helper.absolutePos(plate.at()), plate.kind(), plate.side());
            port.onPlaced(owner);
            port.setDeviceName(plate.name());
        }
        InterfaceBlockEntity store = helper.getBlockEntity(new BlockPos(1, 1, 0), InterfaceBlockEntity.class);
        store.setDeviceName("store");
        for (int slot = 0; slot < InterfaceBlockEntity.SLOTS; slot++) {
            store.setFilter(slot, qbotics.network.NameMatch.parse(net.minecraft.core.registries.BuiltInRegistries.ITEM, "*"));
        }
        store.setItem(0, new ItemStack(Blocks.COBBLESTONE, 4));
        qbotics.block.FluidInterfaceBlockEntity tanks = helper.getBlockEntity(new BlockPos(0, 1, 1), qbotics.block.FluidInterfaceBlockEntity.class);
        tanks.setDeviceName("tanks");
        tanks.insert(net.minecraft.world.level.material.Fluids.WATER, 2000, false);

        String program = String.join("\n",
                "import time",
                "from logistics import network, NetworkError",
                "net = network()",
                "store = net.interface('store')",
                "tanks = net.fluid_interface('tanks')",
                "lamp = net.pin('lamp')",
                "sensor = net.pin('sensor')",
                "placer = net.placer()",
                "breaker = net.breaker()",
                "def lit():",
                "    return lamp.block()['state']['lit']",
                "",
                "print('sensor', sensor.mode, sensor.read())",
                "lamp.write(15)",
                "print('lamp on', lit(), lamp.mode, lamp.read())",
                "lamp.write(0)",
                "while lit() == 'true':    # a lamp goes out a few ticks after its power (states are text)",
                "    time.sleep(0.05)",
                "print('lamp off', lit())",
                "lamp.pulse(200)    # long enough that it can't end before the wait starts",
                "print('pulse', lit())",
                "print('pulse over', lamp.wait_for('block', 60), lit())",
                "",
                "print(placer.place('cobblestone', sources=store), placer.target)",
                "try:",
                "    placer.place('cobblestone', sources=store)",
                "except NetworkError as e:",
                "    print('taken:', e)",
                "print('broke', breaker.break_block(store))",
                "print('poured', placer.pour('water', sources=tanks), placer.block()['fluid'])",
                "print('took', breaker.break_block(tanks), tanks.amount('water'))",
                "try:",
                "    breaker.break_block(store)",
                "except NetworkError as e:",
                "    print('nothing:', e)",
                "print('stone', store.count('cobblestone'))",
                "");
        try {
            computer.drive().call(qbotics.engine.HostFileSystem.WRITE, "/plates.py", program.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (qbotics.engine.HostFileSystem.FsException e) {
            throw new IllegalStateException("could not write the test program", e);
        }
        computer.submitLine("run /plates.py");
        helper.succeedWhen(() -> {
            String screen = screen(computer);
            helper.assertTrue(!screen.contains("Traceback"), "the program failed:\n" + screen);
            assertScreen(helper, computer, "sensor input 15\n", "lamp on true output 15\n", "lamp off false\n", "pulse true\n",
                    "pulse over True false\n", "minecraft:cobblestone minecraft:cobblestone\n",
                    "taken: The space in front of the placer is taken by minecraft:cobblestone", "broke {'minecraft:cobblestone': 1}\n",
                    "poured minecraft:water minecraft:water\n", "took {'minecraft:water': 1000} 2000\n",
                    "nothing: There is nothing in front of the breaker to break.", "stone 4\n");
        });
    }

    /**
     * The fluid and energy stores' screens: tank filters (from a program and from a socket
     * clicked with a bucket), bucket slots that empty and fill, and the energy gauge's number.
     */
    private static void storeScreens(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerComputer computer = startAt(helper, new BlockPos(0, 1, 0));
        helper.setBlock(new BlockPos(1, 1, 0), QboticsContent.FLUID_INTERFACE_BLOCK.get());
        helper.setBlock(new BlockPos(2, 1, 0), QboticsContent.ENERGY_INTERFACE_BLOCK.get());
        qbotics.block.FluidInterfaceBlockEntity tanks = helper.getBlockEntity(new BlockPos(1, 1, 0), qbotics.block.FluidInterfaceBlockEntity.class);
        qbotics.block.EnergyInterfaceBlockEntity cell = helper.getBlockEntity(new BlockPos(2, 1, 0), qbotics.block.EnergyInterfaceBlockEntity.class);
        net.minecraft.world.level.material.Fluid water = net.minecraft.world.level.material.Fluids.WATER;
        net.minecraft.world.level.material.Fluid lava = net.minecraft.world.level.material.Fluids.LAVA;

        // A full bucket in tank 1's slot empties into tank 1; an empty one put back fills from it.
        tanks.buckets().setItem(1, new ItemStack(Items.WATER_BUCKET));
        helper.assertTrue(tanks.tanks().get(1).amount() == 1000 && tanks.buckets().getItem(1).is(Items.BUCKET),
                "a water bucket in slot 1 should empty into tank 1, leaving an empty bucket: " + tanks.tanks().get(1) + " " + tanks.buckets().getItem(1));
        tanks.buckets().setItem(1, ItemStack.EMPTY);
        tanks.buckets().setItem(1, new ItemStack(Items.BUCKET));
        helper.assertTrue(tanks.tanks().get(1).amount() == 0 && tanks.buckets().getItem(1).is(Items.WATER_BUCKET),
                "an empty bucket in slot 1 should fill from tank 1: " + tanks.tanks().get(1) + " " + tanks.buckets().getItem(1));

        // A socket clicked with a bucket of lava keeps that tank for lava; with an empty hand, for anything again.
        net.minecraft.world.entity.player.Player player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        qbotics.block.FluidInterfaceMenu menu = new qbotics.block.FluidInterfaceMenu(1, player.getInventory(), tanks);
        menu.setCarried(new ItemStack(Items.LAVA_BUCKET));
        menu.clicked(3, 0, net.minecraft.world.inventory.ContainerInput.PICKUP, player);
        helper.assertTrue(tanks.filter(3) == lava && menu.getCarried().is(Items.LAVA_BUCKET),
                "clicking socket 3 with lava should keep tank 3 for lava and leave the bucket in hand");
        menu.setCarried(ItemStack.EMPTY);
        menu.clicked(3, 0, net.minecraft.world.inventory.ContainerInput.PICKUP, player);
        helper.assertTrue(tanks.filter(3) == net.minecraft.world.level.material.Fluids.EMPTY, "an empty hand should clear the filter");

        // A filtered tank refuses other fluids, from pipes and buckets alike.
        tanks.setFilter(0, lava);
        helper.assertTrue(tanks.insertInto(0, water, 1000, false) == 0, "tank 0 is kept for lava");
        tanks.buckets().setItem(0, new ItemStack(Items.WATER_BUCKET));
        helper.assertTrue(tanks.tanks().get(0).amount() == 0 && tanks.buckets().getItem(0).is(Items.WATER_BUCKET),
                "a water bucket in tank 0's slot should stay full");
        tanks.buckets().setItem(0, new ItemStack(Items.LAVA_BUCKET));
        helper.assertTrue(tanks.tanks().get(0).amount() == 1000 && tanks.tanks().get(0).fluid() == lava, "lava goes in");

        // The energy gauge's number survives being sent in two halves.
        cell.insert(1_500_000, false);
        qbotics.block.EnergyInterfaceMenu gauge = new qbotics.block.EnergyInterfaceMenu(2, player.getInventory(), cell);
        helper.assertTrue(gauge.stored() == 1_500_000, "the gauge should read 1,500,000 but read " + gauge.stored());
        // Something that holds no energy in a battery slot is left alone.
        cell.batteries().setItem(qbotics.block.EnergyInterfaceBlockEntity.CHARGE_SLOT, new ItemStack(Blocks.STONE));

        for (String line : List.of("python", "from logistics import network", "tanks = network().fluid_interface()",
                "tanks.set_tank_filter(2, 'water')", "print(tanks.filters())", "tanks.set_tank_filter(2, None)",
                "tanks.set_tank_filter(1, '#minecraft:water')", "print(tanks.filters())")) {
            computer.submitLine(line);
        }
        helper.succeedWhen(() -> {
            assertScreen(helper, computer, "['minecraft:lava', None, 'minecraft:water', None]",
                    "A tank is kept for one fluid;", "['minecraft:lava', None, None, None]");
            helper.assertTrue(cell.stored() == 1_500_000 && cell.batteries().getItem(0).is(Blocks.STONE.asItem()),
                    "stone in the charge slot should take nothing");
        });
    }

    /** Watching for changes: items in a chest (all, or one kind), fluid in a tank, energy in a store. */
    private static void watching(GameTestHelper helper) {
        ServerComputer computer = startAt(helper, new BlockPos(0, 1, 0));
        helper.setBlock(new BlockPos(1, 1, 0), QboticsContent.INTERFACE_BLOCK.get());
        setPort(helper, new BlockPos(1, 1, 1), Kind.BUS, Direction.EAST);
        helper.setBlock(new BlockPos(2, 1, 1), Blocks.CHEST);
        helper.setBlock(new BlockPos(0, 1, 1), QboticsContent.FLUID_INTERFACE_BLOCK.get());
        helper.setBlock(new BlockPos(0, 2, 1), QboticsContent.FLUID_INTERFACE_BLOCK.get());
        helper.setBlock(new BlockPos(2, 1, 0), QboticsContent.ENERGY_INTERFACE_BLOCK.get());
        helper.setBlock(new BlockPos(2, 2, 0), QboticsContent.ENERGY_INTERFACE_BLOCK.get());
        InterfaceBlockEntity store = helper.getBlockEntity(new BlockPos(1, 1, 0), InterfaceBlockEntity.class);
        store.setDeviceName("store");
        for (int slot = 0; slot < InterfaceBlockEntity.SLOTS; slot++) {
            store.setFilter(slot, qbotics.network.NameMatch.parse(net.minecraft.core.registries.BuiltInRegistries.ITEM, "*"));
        }
        store.setItem(0, new ItemStack(Items.COAL, 10));
        store.setItem(1, new ItemStack(Items.IRON_INGOT, 5));
        qbotics.block.FluidInterfaceBlockEntity tankA = helper.getBlockEntity(new BlockPos(0, 1, 1), qbotics.block.FluidInterfaceBlockEntity.class);
        tankA.setDeviceName("tank_a");
        tankA.insert(net.minecraft.world.level.material.Fluids.WATER, 2000, false);
        helper.getBlockEntity(new BlockPos(0, 2, 1), qbotics.block.FluidInterfaceBlockEntity.class).setDeviceName("tank_b");
        qbotics.block.EnergyInterfaceBlockEntity cellA = helper.getBlockEntity(new BlockPos(2, 1, 0), qbotics.block.EnergyInterfaceBlockEntity.class);
        cellA.setDeviceName("cell_a");
        cellA.insert(10_000, false);
        helper.getBlockEntity(new BlockPos(2, 2, 0), qbotics.block.EnergyInterfaceBlockEntity.class).setDeviceName("cell_b");

        for (String line : List.of(
                "python",
                "from logistics import network, move",
                "net = network()",
                "store = net.interface('store')",
                "chest = net.bus()",
                "w = chest.watch()",
                "print('none yet', w.changes())",
                "move(store, chest, 'coal', 5)",
                "c = w.changes()",
                "print('coal in', len(c), c[0]['slot'], c[0]['item'], c[0]['was'], c[0]['now'])",
                "iron = chest.watch('iron_ingot')",
                "move(store, chest, 'coal', 2)",
                "print('iron only', iron.changes(), len(w.changes()))",
                "move(store, chest, 'iron_ingot', 3)",
                "for change in iron: print('loop', change['item'], change['was'], change['now']); break",
                "",
                "print('w saw', len(w.changes()), 'then quiet', w.wait(0.2))",
                "ft = net.fluid_interface('tank_b').watch()",
                "move(net.fluid_interface('tank_a'), net.fluid_interface('tank_b'), 'water', 1000)",
                "c = ft.changes()[0]",
                "print('fluid', c['tank'], c['fluid'], c['was'], c['now'])",
                "et = net.energy_interface('cell_b').watch()",
                "move(net.energy_interface('cell_a'), net.energy_interface('cell_b'), count=500)",
                "c = et.changes()[0]",
                "print('energy', c['was'], c['now'])",
                "w.close()",
                "w.changes()")) {
            computer.submitLine(line);
        }
        helper.succeedWhen(() -> assertScreen(helper, computer, "none yet []\n", "coal in 1 0 minecraft:coal 0 5\n",
                "iron only [] 1\n", "loop minecraft:iron_ingot 0 3\n", "w saw 1 then quiet []\n",
                "fluid 0 minecraft:water 0 1000\n", "energy 0 500\n", "This watch has ended"));
    }

    /**
     * The events module: a timer drives the test step by step, while handlers see a pin's
     * signal rise and fall, a chest change and a message arrive; then events.stop() ends run().
     */
    private static void eventsModule(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerComputer computer = startAt(helper, new BlockPos(0, 1, 0));
        helper.setBlock(new BlockPos(1, 1, 0), QboticsContent.INTERFACE_BLOCK.get());
        helper.setBlock(new BlockPos(2, 1, 1), Blocks.REDSTONE_LAMP);
        helper.setBlock(new BlockPos(0, 1, 2), Blocks.CHEST);
        for (BlockPos cable : List.of(new BlockPos(1, 1, 1), new BlockPos(1, 1, 2), new BlockPos(2, 1, 2))) {
            helper.setBlock(cable, QboticsContent.CABLE_BLOCK.get());
        }
        // The sensor reads the lamp; the driver, on another cable, powers it.
        CableBlock.attach(level, helper.absolutePos(new BlockPos(1, 1, 1)), Kind.PIN, Direction.EAST).setDeviceName("sensor");
        CableBlock.attach(level, helper.absolutePos(new BlockPos(2, 1, 2)), Kind.PIN, Direction.NORTH).setDeviceName("driver");
        CableBlock.attach(level, helper.absolutePos(new BlockPos(1, 1, 2)), Kind.BUS, Direction.WEST).setDeviceName("chest");
        InterfaceBlockEntity store = helper.getBlockEntity(new BlockPos(1, 1, 0), InterfaceBlockEntity.class);
        store.setDeviceName("store");
        store.setFilter(0, Items.COAL);
        store.setItem(0, new ItemStack(Items.COAL, 10));

        String program = String.join("\n",
                "import events, network",
                "from logistics import network as cables, move",
                "net = cables()",
                "sensor = net.pin('sensor')",
                "driver = net.pin('driver')",
                "store = net.interface('store')",
                "chest = net.bus('chest')",
                "me = network.ifconfig()['ip']",
                "",
                "events.on_pin(sensor, 'rising', lambda e: print('pin', e.name, e.event, e.level))",
                "events.on_pin(sensor, 'falling', lambda e: print('pin', e.name, e.event, e.level))",
                "events.on_change(chest, lambda c: print('chest', c['item'], c['was'], c['now']))",
                "events.on_message(lambda m: print('message', m.data['hi']))",
                "steps = []",
                "",
                "@events.every(0.05)",
                "def step():",
                "    steps.append(1)",
                "    n = len(steps)",
                "    if n == 1:",
                "        driver.write(15)",
                "    elif n == 2:",
                "        driver.write(0)",
                "    elif n == 3:",
                "        move(store, chest, 'coal', 3)",
                "    elif n == 4:",
                "        network.send(me, {'hi': 42})",
                "    elif n == 7:",
                "        events.stop()",
                "",
                "events.after(30, events.stop)    # in case something never comes",
                "events.run()",
                "print('ran', len(steps), 'steps')",
                "events.clear()",
                "events.run()",
                "");
        try {
            computer.drive().call(qbotics.engine.HostFileSystem.WRITE, "/ev.py", program.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (qbotics.engine.HostFileSystem.FsException e) {
            throw new IllegalStateException("could not write the test program", e);
        }
        computer.submitLine("run /ev.py");
        helper.succeedWhen(() -> assertScreen(helper, computer, "pin sensor rising 15\n", "pin sensor falling 0\n",
                "chest minecraft:coal 0 3\n", "message 42\n", "ran 7 steps\n", "There is nothing to wait for"));
    }

    /**
     * Limits, the bricked state and the boot queue (design doc §5, §6.3). The player already
     * has ten active computers (placeholders in the registry), the default limit.
     */
    private static void limitsAndBoot(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        net.minecraft.server.level.ServerPlayer player = helper.makeMockServerPlayerInLevel();
        UUID me = player.getUUID();
        qbotics.limits.DeviceRegistry registry = qbotics.limits.DeviceRegistry.get(level.getServer());
        List<UUID> placeholders = new java.util.ArrayList<>();
        for (int i = 0; i < qbotics.QboticsConfig.computersPerPlayer; i++) {
            UUID id = UUID.randomUUID();
            placeholders.add(id);
            registry.put(new qbotics.limits.DeviceRegistry.Entry(id, me, qbotics.limits.Limits.COMPUTER, "minecraft:overworld", BlockPos.ZERO));
        }
        ComputerManager manager = ComputerManager.get(level.getServer());

        // An eleventh computer is placed bricked: dark, and not on its network.
        BlockPos atA = new BlockPos(0, 1, 0);
        helper.setBlock(atA, QboticsContent.COMPUTER_BLOCK.get());
        helper.setBlock(new BlockPos(1, 1, 0), QboticsContent.INTERFACE_BLOCK.get());
        ComputerBlockEntity a = helper.getBlockEntity(atA, ComputerBlockEntity.class);
        a.onPlaced(me);
        qbotics.limits.Limits.placed(level, a, player);
        helper.assertTrue(a.bricked() && !helper.getBlockState(atA).getValue(qbotics.block.ComputerBlock.ACTIVE),
                "the eleventh computer should be bricked, with its screen dark");
        helper.assertTrue(networkAt(helper, new BlockPos(1, 1, 0)).devices().stream().noneMatch(d -> d == a),
                "a bricked computer should not be on its network");
        String refused = qbotics.limits.Limits.tryActivate(level, a, player).getString();
        helper.assertTrue(refused.contains("You already have 10 of 10 active computers"), "right-clicking it should say why: " + refused);

        // A computer a machine placed is bricked too, and has no owner.
        BlockPos atB = new BlockPos(2, 1, 0);
        helper.setBlock(atB, QboticsContent.COMPUTER_BLOCK.get());
        ComputerBlockEntity b = helper.getBlockEntity(atB, ComputerBlockEntity.class);
        b.onPlaced(null);
        qbotics.limits.Limits.placed(level, b, null);
        helper.assertTrue(b.bricked() && b.owner() == null, "a machine-placed computer should be bricked");

        // A computer that was on comes back on through the boot queue.
        BlockPos atC = new BlockPos(2, 1, 2);
        helper.setBlock(atC, QboticsContent.COMPUTER_BLOCK.get());
        ComputerBlockEntity c = helper.getBlockEntity(atC, ComputerBlockEntity.class);
        c.setOn(true);
        manager.queueBoot(level, c);
        helper.assertTrue(manager.computer(c.deviceId()) == null, "it waits in the queue for the next tick");

        // One of the player's computers goes: the bricked one may now come on (after the once-a-second check).
        registry.remove(placeholders.removeFirst());
        helper.runAfterDelay(25, () -> {
            String allowed = qbotics.limits.Limits.tryActivate(level, a, player).getString();
            helper.assertTrue(allowed.equals("Switched on.") && !a.bricked() && registry.has(a.deviceId())
                    && helper.getBlockState(atA).getValue(qbotics.block.ComputerBlock.ACTIVE), "the computer should switch on: " + allowed);
            helper.assertTrue(networkAt(helper, new BlockPos(1, 1, 0)).devices().contains(a), "and be on its network again");
            // Breaking it frees its place.
            helper.setBlock(atA, Blocks.AIR);
            helper.assertTrue(!registry.has(a.deviceId()), "a broken computer should no longer count");
            ServerComputer booted = manager.computer(c.deviceId());
            helper.assertTrue(booted != null, "the queued computer should have come on");
            booted.submitLine("shutdown");
            helper.succeedWhen(() -> {
                helper.assertTrue(manager.computer(c.deviceId()) == null && !c.wasOn(), "shutdown should switch it off and keep it off");
                placeholders.forEach(registry::remove);
            });
        });
    }

    /**
     * Hard drives in a Drive Bay (design doc §17.6): the computer on its network sees them
     * under /net/<bay>/<drive>/, writes and runs programs there, and moves files between
     * drives; the files are kept under the drive's own ID, and go when it is taken out.
     */
    private static void driveBays(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerComputer computer = startAt(helper, new BlockPos(0, 1, 0));
        helper.setBlock(new BlockPos(1, 1, 0), net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(qbotics.Qbotics.id("drive_bay")));
        qbotics.block.DriveBayBlockEntity bay = helper.getBlockEntity(new BlockPos(1, 1, 0), qbotics.block.DriveBayBlockEntity.class);
        bay.setDeviceName("shared");
        net.minecraft.world.item.Item hardDrive = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(qbotics.Qbotics.id("hard_drive"));
        ItemStack backup = new ItemStack(hardDrive);
        backup.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("backup"));
        bay.drives().setItem(0, backup);
        bay.drives().setItem(2, new ItemStack(hardDrive));
        UUID backupId = bay.drives().getItem(0).get(QboticsContent.MEDIA_ID.get());
        helper.assertTrue(backupId != null, "a drive should be given its files' ID when it goes in");

        String program = String.join("\n",
                "import os, time",
                "from logistics import network",
                "bay = network().drive_bay()",
                "while 'net' not in os.listdir('/'):    # the drives appear within half a second",
                "    time.sleep(0.05)",
                "print('bay', bay.path, sorted(bay.drives()))",
                "print('net', os.listdir('/net'), sorted(os.listdir(bay.path)))",
                "with open(bay.path + '/backup/hello.py', 'w') as f:",
                "    f.write(\"print('hello from the drive')\\n\")",
                "with open('/note.txt', 'w') as f:",
                "    f.write('moved')",
                "os.rename('/note.txt', bay.path + '/drive3/note.txt')",
                "print('moved', 'note.txt' in os.listdir('/'), os.listdir(bay.path + '/drive3'))",
                "");
        try {
            computer.drive().call(qbotics.engine.HostFileSystem.WRITE, "/t.py", program.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (qbotics.engine.HostFileSystem.FsException e) {
            throw new IllegalStateException("could not write the test program", e);
        }
        for (String line : List.of("run /t.py", "run /net/shared/backup/hello.py", "cp /net/shared/backup/hello.py /copy.py", "run /copy.py")) {
            computer.submitLine(line);
        }
        helper.succeedWhen(() -> {
            assertScreen(helper, computer, "bay /net/shared ['backup', 'drive3']\n", "net ['shared'] ['backup', 'drive3']\n",
                    "moved False ['note.txt']\n", "/ $ run /net/shared/backup/hello.py\nhello from the drive\n", "/ $ run /copy.py\nhello from the drive\n");
            java.nio.file.Path kept = ComputerManager.get(level.getServer()).mediaFolder(backupId).resolve("hello.py");
            helper.assertTrue(java.nio.file.Files.exists(kept), "the drive's files should be kept under its own ID");
            // Taken out, a drive's folder goes from /net on the next look.
            bay.drives().setItem(2, ItemStack.EMPTY);
        });
    }

    /**
     * pip from a remote repository: a Drive Bay on another network, reached by radio, holds a
     * drive of packages; a requirements.txt here installs them (and what they need) into /lib.
     */
    private static void pipFromRemote(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos here = new BlockPos(0, 1, 0);
        ServerComputer computer = startAt(helper, here);
        helper.setBlock(here.above(), QboticsContent.ANTENNA_SHORT_BLOCK.get());
        // The repository: a bay and its antenna, a network of their own.
        BlockPos bayPos = new BlockPos(2, 1, 2);
        helper.setBlock(bayPos, net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(qbotics.Qbotics.id("drive_bay")));
        helper.setBlock(bayPos.above(), QboticsContent.ANTENNA_SHORT_BLOCK.get());
        qbotics.block.DriveBayBlockEntity bay = helper.getBlockEntity(bayPos, qbotics.block.DriveBayBlockEntity.class);
        bay.setDeviceName("repo");
        ItemStack lib = new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(qbotics.Qbotics.id("hard_drive")));
        lib.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("lib"));
        bay.drives().setItem(0, lib);
        qbotics.engine.HostFileSystem drive = ComputerManager.get(level.getServer()).mediaFiles(bay.drives().getItem(0).get(QboticsContent.MEDIA_ID.get()));
        networkAt(helper, here);
        networkAt(helper, bayPos);
        String repoIp = helper.getBlockEntity(bayPos.above(), AntennaBlockEntity.class).externalIp();
        try {
            drive.call(qbotics.engine.HostFileSystem.WRITE, "/greetings.py",
                    "def hello(name):\n    return 'hello, ' + name + ', from the repo'\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            drive.call(qbotics.engine.HostFileSystem.MKDIR, "/tools", new byte[0]);
            drive.call(qbotics.engine.HostFileSystem.WRITE, "/tools/__init__.py",
                    "from greetings import hello\nVERSION = 3\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            drive.call(qbotics.engine.HostFileSystem.WRITE, "/tools/requirements.txt", "greetings\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            computer.drive().call(qbotics.engine.HostFileSystem.WRITE, "/requirements.txt",
                    ("# what this computer needs\n--source " + repoIp + "/repo/lib\ntools\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (qbotics.engine.HostFileSystem.FsException e) {
            throw new IllegalStateException("could not set up the repository", e);
        }

        for (String line : List.of(
                "pip available --source " + repoIp + "/repo/lib",
                "pip install -r requirements.txt",
                "pip list",
                "pip install nothere --source " + repoIp + "/repo/lib",
                "pip install greetings --source repo/lib",
                "python",
                "import tools",
                "print(tools.VERSION, tools.hello('main'))",
                "exit()",
                "pip uninstall greetings",
                "pip list")) {
            computer.submitLine(line);
        }
        helper.succeedWhen(() -> assertScreen(helper, computer, "/repo/lib\ngreetings\ntools\n", "installed tools (2 files)\ninstalled greetings (1 file)\n",
                "$ pip list\ngreetings\ntools\n", "There is no package called nothere", "There is no drive bay called repo on this network",
                "3 hello, main, from the repo\n", "removed greetings\n", "$ pip list\ntools\n"));
    }

    /**
     * The Programmer (design doc §5.4): a computer a machine placed in front of it is programmed
     * as it arrives (and becomes the programmer's owner's); a rising redstone signal installs
     * that level's program by programs.txt; a program on its network can ask by folder name. Two
     * programmers can't both program one computer in a tick, and nobody's programmer programs a
     * stranger's computer.
     */
    private static void programmerBlock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ComputerManager manager = ComputerManager.get(level.getServer());
        UUID me = UUID.randomUUID();
        net.minecraft.world.level.block.Block programmerBlock = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(qbotics.Qbotics.id("programmer"));
        java.util.function.BiFunction<BlockPos, Direction, qbotics.block.ProgrammerBlockEntity> place = (pos, facing) -> {
            helper.setBlock(pos, programmerBlock.defaultBlockState().setValue(qbotics.block.ProgrammerBlock.FACING, facing));
            qbotics.block.ProgrammerBlockEntity programmer = helper.getBlockEntity(pos, qbotics.block.ProgrammerBlockEntity.class);
            programmer.onPlaced(me);
            programmer.driveSlot().setItem(0, new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(qbotics.Qbotics.id("hard_drive"))));
            return programmer;
        };
        // The first faces east, onto where the computer will go; a computer of ours is on its network.
        qbotics.block.ProgrammerBlockEntity first = place.apply(new BlockPos(1, 1, 1), Direction.EAST);
        first.setDeviceName("prog");
        ServerComputer ours = startAt(helper, new BlockPos(1, 1, 0));
        // The second faces the same place from the other side.
        qbotics.block.ProgrammerBlockEntity second = place.apply(new BlockPos(3, 1, 1), Direction.WEST);
        // The third faces a stranger's computer.
        BlockPos theirsAt = new BlockPos(0, 1, 3);
        helper.setBlock(theirsAt, QboticsContent.COMPUTER_BLOCK.get());
        helper.getBlockEntity(theirsAt, ComputerBlockEntity.class).onPlaced(UUID.randomUUID());
        qbotics.block.ProgrammerBlockEntity third = place.apply(new BlockPos(1, 1, 3), Direction.WEST);

        qbotics.engine.HostFileSystem drive = manager.mediaFiles(first.mediaId());
        try {
            drive.call(qbotics.engine.HostFileSystem.WRITE, "/programs.txt", "# level: folder\n15: miner\n*: basic\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            drive.call(qbotics.engine.HostFileSystem.MKDIR, "/basic", new byte[0]);
            drive.call(qbotics.engine.HostFileSystem.WRITE, "/basic/startup.py", "print('basic installed')\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            drive.call(qbotics.engine.HostFileSystem.MKDIR, "/miner", new byte[0]);
            drive.call(qbotics.engine.HostFileSystem.MKDIR, "/miner/lib", new byte[0]);
            drive.call(qbotics.engine.HostFileSystem.WRITE, "/miner/startup.py", "print('miner installed')\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            drive.call(qbotics.engine.HostFileSystem.WRITE, "/miner/lib/dig.py", "DEPTH = 9\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            manager.mediaFiles(third.mediaId()).call(qbotics.engine.HostFileSystem.WRITE, "/startup.py", "print('hi')\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (qbotics.engine.HostFileSystem.FsException e) {
            throw new IllegalStateException("could not fill the programmer's drive", e);
        }

        // A machine places a computer in front of the first: it's programmed with the * program as it arrives.
        BlockPos targetAt = new BlockPos(2, 1, 1);
        helper.setBlock(targetAt, QboticsContent.COMPUTER_BLOCK.get());
        ComputerBlockEntity target = helper.getBlockEntity(targetAt, ComputerBlockEntity.class);
        target.onPlaced(null);
        qbotics.limits.Limits.placed(level, target, null);
        qbotics.block.Programmers.arrived(level, target);
        helper.assertTrue(first.status().startsWith("Installed 'basic' (1 file)"), "the arriving computer should get the * program: " + first.status());
        helper.assertTrue(me.equals(target.owner()) && !target.bricked(), "it should be switched on as the programmer's owner's");
        // The second programmer can't program it again this tick.
        String again = qbotics.block.Programmers.program(level, second, "*").message();
        helper.assertTrue(again.contains("this tick"), "two programmers shouldn't both program it in one tick: " + again);
        String stranger = qbotics.block.Programmers.program(level, third, "*").message();
        helper.assertTrue(stranger.contains("belongs to someone else"), "a stranger's computer should be refused: " + stranger);

        helper.runAfterDelay(2, () -> {
            // A redstone block below: a rise to 15 installs the miner, folders and all.
            helper.setBlock(new BlockPos(1, 0, 1), Blocks.REDSTONE_BLOCK);
            helper.assertTrue(first.status().startsWith("Installed 'miner' (2 files)"), "level 15 should install the miner: " + first.status());
            helper.assertTrue(java.nio.file.Files.exists(manager.driveFolder(target.driveId()).resolve("lib").resolve("dig.py")),
                    "the program's folders should be copied too");
            // A steady signal doesn't program again.
            helper.setBlock(new BlockPos(1, 0, 2), Blocks.REDSTONE_BLOCK);
            helper.assertTrue(first.status().startsWith("Installed 'miner'"), "a steady signal shouldn't program again");
            for (String line : List.of(
                    "python",
                    "from logistics import network",
                    "p = network().programmer()",
                    "print(p.status)",
                    "import time",
                    "time.sleep(0.2)    # once a tick: let the redstone's tick pass",
                    "print(p.program('basic'))",
                    "try:",
                    "    p.program('nothere')",
                    "except Exception as e:",
                    "    print('refused:', e)",
                    "",
                    "exit()")) {
                ours.submitLine(line);
            }
            helper.succeedWhen(() -> {
                assertScreen(helper, ours, "Installed 'miner' (2 files)", "Installed 'basic' (1 file)", "refused: The drive has no folder called nothere");
                ServerComputer programmed = manager.computer(target.deviceId());
                helper.assertTrue(programmed != null, "the programmed computer should be running");
                assertScreen(helper, programmed, "basic installed");
            });
        });
    }

    /**
     * Text Monitors and Control Panels (design doc §10): four monitors make one 2x2 screen (one
     * device on the network); a program writes on it and changes its text scale; a panel's
     * widgets are made and set, and a player's touches reach the program's handlers through
     * events.run(). Breaking the screen's top-left block hands its identity and text on.
     */
    private static void displays(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        net.minecraft.world.level.block.state.BlockState monitor = QboticsContent.TEXT_MONITOR_BLOCK.get().defaultBlockState()
                .setValue(qbotics.block.DisplayBlock.FACING, Direction.NORTH);
        net.minecraft.world.level.block.state.BlockState panel = QboticsContent.CONTROL_PANEL_BLOCK.get().defaultBlockState()
                .setValue(qbotics.block.DisplayBlock.FACING, Direction.SOUTH);
        for (BlockPos pos : List.of(new BlockPos(0, 1, 0), new BlockPos(1, 1, 0), new BlockPos(0, 2, 0), new BlockPos(1, 2, 0))) {
            helper.setBlock(pos, monitor);
        }
        ServerComputer computer = startAt(helper, new BlockPos(0, 1, 1));
        helper.setBlock(new BlockPos(0, 1, 2), panel);
        helper.setBlock(new BlockPos(1, 1, 2), panel);
        // Facing north, the screen's right is west: its top-left block is the top one furthest east.
        qbotics.block.DisplayBlockEntity screen = helper.getBlockEntity(new BlockPos(1, 2, 0), qbotics.block.DisplayBlockEntity.class);
        helper.assertTrue(screen.isOrigin() && screen.screenWidth() == 2 && screen.screenHeight() == 2,
                "four monitors should make one 2x2 screen, at " + screen.col() + "," + screen.row() + " of " + screen.screenWidth() + "x" + screen.screenHeight());
        qbotics.block.DisplayBlockEntity panelScreen = helper.getBlockEntity(new BlockPos(0, 1, 2), qbotics.block.DisplayBlockEntity.class);
        helper.assertTrue(panelScreen.isOrigin() && panelScreen.screenWidth() == 2, "the two panels should make one 2x1 panel");
        long monitors = networkAt(helper, new BlockPos(0, 1, 1)).devices().stream().filter(d -> d.kind() == Kind.MONITOR).count();
        helper.assertTrue(monitors == 1, "the screen should be one device on the network, not " + monitors);
        String mac = screen.mac();

        String program = String.join("\n",
                "import events",
                "from logistics import network",
                "net = network()",
                "mon = net.monitor()",
                "mon.clear()",
                "print('size', mon.size())",
                "mon.print('hello monitor')",
                "mon.write_at(0, 5, 'status: ok', 'lime')",
                "mon.scale = 2",
                "print('scaled', mon.size(), mon.scale)",
                "p = net.panel()",
                "p.clear()",
                "print('grid', p.size())",
                "t = p.toggle('Pump', at=(0, 0), span=(2, 1))",
                "b = p.button('Go', at=(4, 0), span=(2, 1))",
                "g = p.gauge('Power', 0, 1000, unit='FE', at=(0, 1), span=(8, 1))",
                "g.value = 640",
                "print('gauge', g.value)",
                "@t.on_change",
                "def pump(e):",
                "    print('pump', e.value)",
                "@b.on_press",
                "def go(e):",
                "    print('pressed by', e.player)",
                "    events.stop()",
                "events.run()",
                "print('done', t.on)",
                "");
        try {
            computer.drive().call(qbotics.engine.HostFileSystem.WRITE, "/t.py", program.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (qbotics.engine.HostFileSystem.FsException e) {
            throw new IllegalStateException("could not write the test program", e);
        }
        computer.submitLine("run /t.py");
        net.minecraft.server.level.ServerPlayer player = helper.makeMockServerPlayerInLevel();
        boolean[] touched = {false};
        helper.succeedWhen(() -> {
            // Once the program is listening, a player flips the toggle, then presses the button.
            qbotics.display.ScreenContent controls = panelScreen.content();
            helper.assertTrue(controls != null && !controls.listeners().isEmpty(), "the program should be listening");
            if (!touched[0]) {
                touched[0] = true;
                qbotics.display.Displays.touched(level, panelScreen, 0.1, 0.1, player);   // the toggle: cells 0-1 of 8
                qbotics.display.Displays.touched(level, panelScreen, 0.6, 0.1, player);   // the button: cells 4-5
            }
            assertScreen(helper, computer, "size (30, 20)\n", "scaled (15, 10) 2.0\n", "grid (8, 4)\n", "gauge 640.0\n", "pump 1.0\n",
                    "pressed by ", "done True\n");
            qbotics.display.TextScreen text = (qbotics.display.TextScreen) screen.content();
            List<qbotics.display.TextScreen.Row> rows = text.rowsSince(0);
            helper.assertTrue(rows.get(0).text().startsWith("hello monitor") && rows.get(5).text().startsWith("status: ok"),
                    "the monitor should show what was written: " + rows.get(0).text() + " / " + rows.get(5).text());
            // Breaking the top-left block: the screen's identity and text go to the block beside it.
            helper.setBlock(new BlockPos(1, 2, 0), Blocks.AIR);
            qbotics.block.DisplayBlockEntity heir = helper.getBlockEntity(new BlockPos(0, 2, 0), qbotics.block.DisplayBlockEntity.class);
            helper.assertTrue(heir.isOrigin() && heir.mac().equals(mac) && heir.content() == text && heir.screenHeight() == 2,
                    "the screen should keep its address and text in the block beside it");
            helper.assertTrue(text.rowsSince(0).get(0).text().startsWith("hello"), "and still show what it did");
        });
    }

    /**
     * Antenna upgrades: a program reaches the interface behind another antenna with
     * net.remote(), but moving from it is refused until both antennas have a Transport Upgrade;
     * then it moves, held to the per-second rate. A Chunk Loader Upgrade holds its network's
     * chunks, and a program switches it off.
     */
    private static void antennaUpgrades(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerComputer computer = startAt(helper, new BlockPos(0, 1, 0));
        BlockPos home = new BlockPos(0, 2, 0);
        BlockPos farPos = new BlockPos(2, 2, 2);
        helper.setBlock(home, QboticsContent.ANTENNA_SHORT_BLOCK.get());
        helper.setBlock(new BlockPos(1, 1, 0), QboticsContent.INTERFACE_BLOCK.get());
        helper.setBlock(new BlockPos(2, 1, 2), QboticsContent.INTERFACE_BLOCK.get());
        helper.setBlock(farPos, QboticsContent.ANTENNA_SHORT_BLOCK.get());
        InterfaceBlockEntity mine = helper.getBlockEntity(new BlockPos(2, 1, 2), InterfaceBlockEntity.class);
        mine.setItem(0, new ItemStack(Items.COAL, 64));
        InterfaceBlockEntity store = helper.getBlockEntity(new BlockPos(1, 1, 0), InterfaceBlockEntity.class);
        store.setFilter(0, Items.COAL);
        mine.setFilter(0, Items.COAL);
        AntennaBlockEntity near = helper.getBlockEntity(home, AntennaBlockEntity.class);
        AntennaBlockEntity far = helper.getBlockEntity(farPos, AntennaBlockEntity.class);
        networkAt(helper, new BlockPos(0, 1, 0));
        networkAt(helper, new BlockPos(2, 1, 2));
        String ip = far.externalIp();
        net.minecraft.world.item.Item transport = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(qbotics.Qbotics.id("transport_upgrade"));
        net.minecraft.world.item.Item loader = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(qbotics.Qbotics.id("chunk_loader_upgrade"));
        helper.assertTrue(!near.upgrades().canPlaceItem(0, new ItemStack(Items.COAL)), "only upgrades go in an antenna's slots");

        String first = String.join("\n",
                "from logistics import network, move",
                "net = network()",
                "mine = net.remote('" + ip + "').interface()",
                "print('remote', mine.mac.startswith('" + ip + "/'))",
                "try:",
                "    move(mine, net.interface(), 'coal', 10)",
                "except Exception as e:",
                "    print('refused:', e)",
                "");
        String second = String.join("\n",
                "import time",
                "import network as nw",
                "from logistics import network, move",
                "net = network()",
                "mine = net.remote('" + ip + "').interface()",
                "print('moved', move(mine, net.interface(), 'coal', 64))",
                "r = nw.Radio()",
                "print('upgrades', sorted(r.upgrades()))",
                "while r.chunks()['status'] != 'loading':",
                "    time.sleep(0.05)",
                "print('holding', r.chunks()['chunks'] >= 1)",
                "r.chunk_loading = False",
                "while r.chunks()['status'] != 'off':",
                "    time.sleep(0.05)",
                "print('done')",
                "");
        try {
            computer.drive().call(qbotics.engine.HostFileSystem.WRITE, "/a.py", first.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            computer.drive().call(qbotics.engine.HostFileSystem.WRITE, "/b.py", second.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (qbotics.engine.HostFileSystem.FsException e) {
            throw new IllegalStateException("could not write the test programs", e);
        }
        computer.submitLine("run /a.py");
        int before = qbotics.QboticsConfig.transportItemsPerSecond;
        int[] phase = {0};
        helper.succeedWhen(() -> {
            if (phase[0] == 0) {
                assertScreen(helper, computer, "remote True\n","refused: Reaching devices behind another antenna needs a Transport Upgrade");
                // Fit the upgrades, hold the rate to 20 items a second, and give the home antenna an owner for its loader.
                near.upgrades().setItem(0, new ItemStack(transport));
                far.upgrades().setItem(0, new ItemStack(transport));
                near.upgrades().setItem(1, new ItemStack(loader));
                helper.assertTrue(!near.upgrades().canPlaceItem(1, new ItemStack(transport)), "one upgrade of a kind per antenna");
                near.claim(UUID.randomUUID());
                qbotics.QboticsConfig.transportItemsPerSecond = 20;
                computer.submitLine("run /b.py");
                phase[0] = 1;
            }
            assertScreen(helper, computer, "moved 20\n", "upgrades ['chunk_loader', 'transport']\n", "holding True\n", "done\n");
            helper.assertTrue(store.countItem(Items.COAL) == 20 && mine.countItem(Items.COAL) == 44, "20 coal should have crossed");
            helper.assertTrue(qbotics.upgrade.ChunkLoaders.heldBy(level, near.getBlockPos()).isEmpty(), "switched off, it should hold nothing");
            qbotics.QboticsConfig.transportItemsPerSecond = before;
        });
    }

    /**
     * An Iron Qbot (design doc §8): its program keeps running as it steps and turns; it digs
     * stone (into its inventory) but not obsidian; places a block; fills its two tanks with
     * water and lava separately; charges its cell; and pours.
     */
    /** The highest "tick N" a program has printed so far. */
    private static int lastTick(ServerComputer computer) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("tick (\\d+)").matcher(screen(computer));
        int last = -1;
        while (m.find()) {
            last = Integer.parseInt(m.group(1));
        }
        return last;
    }

    /**
     * A Qbot's program is worked by its Run, Pause and Stop buttons: it doesn't start on its
     * own; Pause holds it (a sleeping loop prints nothing more) and Run resumes it; Stop ends
     * it; and a program that fails says so.
     */
    private static void qbotProgramControls(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos at = new BlockPos(1, 1, 1);
        helper.setBlock(at, QboticsContent.IRON_QBOT_BLOCK.get().defaultBlockState().setValue(qbotics.block.ComputerBlock.FACING, Direction.NORTH));
        qbotics.qbot.QbotBlockEntity qbot = helper.getBlockEntity(at, qbotics.qbot.QbotBlockEntity.class);
        qbot.onPlaced(UUID.randomUUID());
        ServerComputer computer = ComputerManager.get(level.getServer()).getOrStart(qbot);
        java.util.function.Consumer<String> install = program -> {
            try {
                computer.drive().call(qbotics.engine.HostFileSystem.WRITE, "/startup.py", program.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (qbotics.engine.HostFileSystem.FsException e) {
                throw new IllegalStateException(e);
            }
        };
        install.accept("import time\nn = 0\nwhile True:\n    n += 1\n    print('tick', n)\n    time.sleep(0.1)\n");
        var STOPPED = ServerComputer.ProgramState.STOPPED;
        var RUNNING = ServerComputer.ProgramState.RUNNING;
        var PAUSED = ServerComputer.ProgramState.PAUSED;
        // Each step waits for what it needs (other tests share the CPU, so timings vary); a step that
        // checks something hasn't happened waits a fixed while.
        int[] step = {0};
        int[] seen = new int[1];
        long[] since = new long[1];
        helper.runAfterDelay(20, () -> {
            helper.assertTrue(computer.programState() == STOPPED && lastTick(computer) < 0, "a Qbot's program should wait for Run");
            computer.runProgram();
            step[0] = 1;
        });
        helper.succeedWhen(() -> {
            long now = level.getGameTime();
            switch (step[0]) {
                case 1 -> {
                    if (computer.programState() == RUNNING && lastTick(computer) >= 2) {
                        computer.pauseProgram();
                        step[0] = 2;
                    }
                }
                case 2 -> {
                    helper.assertTrue(computer.programState() == PAUSED, "Pause should hold it");
                    seen[0] = lastTick(computer);
                    since[0] = now;
                    step[0] = 3;
                }
                case 3 -> {
                    if (now - since[0] >= 40) {
                        helper.assertTrue(lastTick(computer) <= seen[0] + 1 && qbot.programRunning(),
                                "while paused it should print nothing more: " + seen[0] + " then " + lastTick(computer));
                        seen[0] = lastTick(computer);
                        computer.runProgram(); // Resume
                        step[0] = 4;
                    }
                }
                case 4 -> {
                    if (lastTick(computer) > seen[0]) {
                        helper.assertTrue(computer.programState() == RUNNING, "Resume should carry on from where it was");
                        computer.stopProgram();
                        step[0] = 5;
                    }
                }
                case 5 -> {
                    if (computer.programState() == STOPPED && !qbot.programRunning()) {
                        helper.assertTrue(computer.programStatus().equals("Stopped"), "Stop should end it: " + computer.programStatus());
                        install.accept("print('starting')\nraise ValueError('boom')\n");
                        computer.runProgram();
                        step[0] = 6;
                    }
                }
                case 6 -> {
                    if (computer.programState() == STOPPED && computer.programStatus().startsWith("Error")) {
                        helper.assertTrue(computer.programStatus().contains("boom"), "a failing program should say why: " + computer.programStatus());
                        step[0] = 7;
                    }
                }
                default -> {
                }
            }
            helper.assertTrue(step[0] == 7, "waiting at step " + step[0]);
        });
    }

    /**
     * A laptop: one computer per player, whatever laptop they open; its drives at
     * /net/disks; held while its player carries no laptop; and ssh to a computer behind an
     * antenna, as its player, with no cable or antenna of its own.
     */
    private static void laptop(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ComputerManager manager = ComputerManager.get(level.getServer());
        net.minecraft.server.level.ServerPlayer player = helper.makeMockServerPlayerInLevel();
        ItemStack laptop = new ItemStack(QboticsContent.LAPTOP_ITEM.get());
        ItemStack drive = new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(qbotics.Qbotics.id("hard_drive")));
        drive.set(QboticsContent.MEDIA_ID.get(), UUID.randomUUID());
        laptop.set(net.minecraft.core.component.DataComponents.CONTAINER,
                net.minecraft.world.item.component.ItemContainerContents.fromItems(List.of(drive, ItemStack.EMPTY)));
        player.getInventory().setItem(3, laptop);
        ServerComputer computer = manager.getOrStartLaptop(player);
        helper.assertTrue(manager.getOrStartLaptop(player) == computer, "a player has one laptop computer");
        qbotics.laptop.Laptops.refresh(player);
        helper.assertTrue(!computer.held(), "carrying a laptop, it runs");
        try {
            String disks = new String(computer.drive().call(qbotics.engine.HostFileSystem.LIST, "/net/disks", new byte[0]),
                    java.nio.charset.StandardCharsets.UTF_8);
            helper.assertTrue(disks.contains("disk1") && !disks.contains("disk2"), "the drive in its first slot is at /net/disks/disk1: " + disks);
        } catch (qbotics.engine.HostFileSystem.FsException e) {
            helper.fail("/net/disks should list the laptop's drive (error " + e.errno + ")");
        }
        // Without a laptop on them it is held; with one again it carries on.
        player.getInventory().setItem(3, ItemStack.EMPTY);
        qbotics.laptop.Laptops.refresh(player);
        helper.assertTrue(computer.held(), "with no laptop on its player, it is held");
        player.getInventory().setItem(5, new ItemStack(QboticsContent.LAPTOP_ITEM.get()));
        qbotics.laptop.Laptops.refresh(player);
        helper.assertTrue(!computer.held(), "any laptop of theirs lets it carry on");
        // A computer behind an Infinite antenna, both the player's.
        BlockPos farComputer = new BlockPos(2, 1, 1);
        helper.setBlock(farComputer, QboticsContent.COMPUTER_BLOCK.get());
        helper.setBlock(new BlockPos(2, 2, 1), QboticsContent.ANTENNA_INFINITE_BLOCK.get());
        qbotics.block.ComputerBlockEntity remote = helper.getBlockEntity(farComputer, qbotics.block.ComputerBlockEntity.class);
        AntennaBlockEntity antenna = helper.getBlockEntity(new BlockPos(2, 2, 1), AntennaBlockEntity.class);
        remote.onPlaced(player.getUUID());
        antenna.onPlaced(player.getUUID());
        networkAt(helper, farComputer);
        String address = antenna.externalIp() + ":" + (qbotics.network.RadioNetwork.PORT_BASE + remote.hostNumber());
        String opened = qbotics.network.NetworkCalls.handleLaptop(player, "[\"ssh_open\",\"" + address + "\",null]");
        helper.assertTrue(opened.contains("\"id\":\"" + remote.deviceId()) && opened.contains("the laptop of"),
                "the laptop should ssh in to its player's computer: " + opened);
        String cables = qbotics.network.NetworkCalls.handleLaptop(player, "[\"devices\"]");
        helper.assertTrue(cables.contains("external address"), "a laptop has no cable network of its own: " + cables);
        String bus = qbotics.network.NetworkCalls.handleLaptop(player, "[\"find\",null,\"bus\"]");
        helper.assertTrue(bus.contains("ssh"), "cable calls point to ssh: " + bus);
        // It runs Python like any computer.
        computer.submitLine("python");
        computer.submitLine("print('lap', 6 * 7)");
        helper.succeedWhen(() -> assertScreen(helper, computer, "lap 42\n"));
    }

    /**
     * A laptop connects straight to its player's own Qbot (its editor and terminal), not to
     * someone else's computer; and Ctrl+Z pauses a running program where it is, then lets it on.
     */
    private static void laptopConnect(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ComputerManager manager = ComputerManager.get(level.getServer());
        net.minecraft.server.level.ServerPlayer player = helper.makeMockServerPlayerInLevel();
        BlockPos qbotPos = new BlockPos(1, 1, 1);
        helper.setBlock(qbotPos, QboticsContent.IRON_QBOT_BLOCK.get().defaultBlockState().setValue(qbotics.block.ComputerBlock.FACING, Direction.NORTH));
        qbotics.qbot.QbotBlockEntity qbot = helper.getBlockEntity(qbotPos, qbotics.qbot.QbotBlockEntity.class);
        qbot.onPlaced(player.getUUID());
        helper.setBlock(new BlockPos(0, 1, 0), QboticsContent.COMPUTER_BLOCK.get());
        qbotics.block.ComputerBlockEntity theirs = helper.getBlockEntity(new BlockPos(0, 1, 0), qbotics.block.ComputerBlockEntity.class);
        theirs.onPlaced(UUID.randomUUID());
        helper.assertTrue(qbotics.laptop.LaptopItem.connect(player, theirs) == net.minecraft.world.InteractionResult.FAIL
                && manager.viewedDevice(player.getUUID()) == null, "someone else's computer isn't the laptop's to open");
        try {
            qbotics.laptop.LaptopItem.connect(player, qbot);
        } catch (RuntimeException noClient) {
            // A test player has no game to send the screen to; the connection is made before that.
        }
        helper.assertTrue(qbot.deviceId().equals(manager.viewedDevice(player.getUUID())), "the laptop should open its player's own Qbot");
        ServerComputer computer = manager.getOrStart(qbot);
        for (String line : List.of("python", "import time", "n = 0", "while True:", "    n += 1", "    print('tick', n)", "    time.sleep(0.1)", "")) {
            computer.submitLine(line);
        }
        int[] step = {0};
        int[] seen = new int[1];
        long[] since = new long[1];
        helper.succeedWhen(() -> {
            long now = level.getGameTime();
            switch (step[0]) {
                case 0 -> {
                    if (lastTick(computer) >= 2) {
                        computer.togglePause(); // Ctrl+Z
                        step[0] = 1;
                    }
                }
                case 1 -> {
                    if (screen(computer).contains("[paused")) {
                        seen[0] = lastTick(computer);
                        since[0] = now;
                        step[0] = 2;
                    }
                }
                case 2 -> {
                    if (now - since[0] >= 40) {
                        helper.assertTrue(lastTick(computer) <= seen[0] + 1, "paused, it should print nothing more: " + seen[0] + " then " + lastTick(computer));
                        seen[0] = lastTick(computer);
                        computer.togglePause(); // Ctrl+Z again
                        step[0] = 3;
                    }
                }
                case 3 -> {
                    if (lastTick(computer) > seen[0] && screen(computer).contains("[carrying on]")) {
                        computer.interrupt();
                        step[0] = 4;
                    }
                }
                default -> {
                }
            }
            helper.assertTrue(step[0] == 4, "waiting at step " + step[0]);
        });
    }

    /**
     * Watching a program run: Ctrl+Z pauses it and says which line of the player's file it is
     * on; F8 steps it one line at a time (here, between the two lines of a loop); Ctrl+Z
     * carries on.
     */
    private static void stepThrough(GameTestHelper helper) {
        stepThrough(helper, false);
    }

    /** The same on a laptop, carried by its player. */
    private static void stepThroughLaptop(GameTestHelper helper) {
        stepThrough(helper, true);
    }

    private static void stepThrough(GameTestHelper helper, boolean onLaptop) {
        ServerLevel level = helper.getLevel();
        ServerComputer computer;
        if (onLaptop) {
            net.minecraft.server.level.ServerPlayer player = helper.makeMockServerPlayerInLevel();
            player.getInventory().setItem(0, new ItemStack(QboticsContent.LAPTOP_ITEM.get()));
            computer = ComputerManager.get(level.getServer()).getOrStartLaptop(player);
            qbotics.laptop.Laptops.refresh(player);
        } else {
            computer = placeAndStart(helper);
        }
        try {
            computer.drive().call(qbotics.engine.HostFileSystem.WRITE, "/prog.py", String.join("\n",
                    "import time", "n = 0", "while True:", "    n += 1", "    time.sleep(0.05)", "").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (qbotics.engine.HostFileSystem.FsException e) {
            throw new IllegalStateException(e);
        }
        // As when someone has its editor open (the server turns watching off for computers nobody has open).
        ServerComputer watchedOne = computer;
        helper.onEachTick(() -> watchedOne.setWatched(true));
        computer.submitRunFile("/prog.py");
        int[] step = {0};
        int[] lines = new int[3];
        helper.succeedWhen(() -> {
            ServerComputer.Trace now = computer.trace();
            switch (step[0]) {
                case 0 -> {
                    if (!now.paused() && (now.line() == 4 || now.line() == 5) && now.file().equals("/prog.py")) {
                        computer.togglePause(); // Ctrl+Z
                        step[0] = 1;
                    }
                }
                case 1 -> {
                    if (now.paused() && screen(computer).contains("[paused at line " + now.line() + " of /prog.py")) {
                        lines[0] = now.line();
                        computer.step(); // F8
                        step[0] = 2;
                    }
                }
                case 2, 3 -> {
                    int last = lines[step[0] - 2];
                    if (now.paused() && now.line() != last && now.line() > 0) {
                        lines[step[0] - 1] = now.line();
                        if (step[0] == 3) {
                            computer.togglePause(); // Ctrl+Z: carry on as normal
                        } else {
                            computer.step();
                        }
                        step[0]++;
                    }
                }
                case 4 -> {
                    if (!now.paused() && screen(computer).contains("[carrying on]")) {
                        computer.interrupt();
                        step[0] = 5;
                    }
                }
                default -> {
                }
            }
            helper.assertTrue(step[0] == 5, "waiting at step " + step[0] + ", at " + now);
            helper.assertTrue(lines[1] != lines[2] && (lines[1] == 4 || lines[1] == 5) && (lines[2] == 4 || lines[2] == 5),
                    "each step should stop at the loop's other line: " + lines[0] + " then " + lines[1] + " then " + lines[2]);
        });
    }

    /**
     * The SSH gateway: a player's key logs in as one of their computers by its name; SFTP puts
     * a file on its drive, the shell is its terminal; another key, or a name that isn't theirs,
     * is refused.
     */
    private static void sshGateway(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        net.minecraft.server.MinecraftServer server = level.getServer();
        UUID owner = UUID.randomUUID();
        helper.setBlock(POS, QboticsContent.COMPUTER_BLOCK.get());
        ComputerBlockEntity block = helper.getBlockEntity(POS, ComputerBlockEntity.class);
        block.onPlaced(owner);
        String name = "miner" + Integer.toHexString(owner.hashCode() & 0xfff);
        block.setDeviceName(name);
        ServerComputer computer = ComputerManager.get(server).getOrStart(block);
        java.security.KeyPair mine;
        java.security.KeyPair stranger;
        try {
            java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("EC");
            generator.initialize(256);
            mine = generator.generateKeyPair();
            stranger = generator.generateKeyPair();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
        String added = qbotics.gateway.SshGateway.keys(server).add(owner,
                org.apache.sshd.common.config.keys.PublicKeyEntry.toString(mine.getPublic()) + " test@pc");
        helper.assertTrue(added == null, "the key should be added: " + added);
        int port = qbotics.gateway.SshGateway.start(server, "127.0.0.1", 0);
        helper.assertTrue(port > 0, "the gateway should start");

        // The client runs on its own thread: the gateway asks the server thread which computer a login means.
        java.util.Map<String, String> results = new java.util.concurrent.ConcurrentHashMap<>();
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            org.apache.sshd.client.SshClient client = org.apache.sshd.client.SshClient.setUpDefaultClient();
            client.setServerKeyVerifier(org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier.INSTANCE);
            // Nothing from this PC's own ~/.ssh: no config, no keys of its own.
            client.setHostConfigEntryResolver(org.apache.sshd.client.config.hosts.HostConfigEntryResolver.EMPTY);
            client.setKeyIdentityProvider(org.apache.sshd.common.keyprovider.KeyIdentityProvider.EMPTY_KEYS_PROVIDER);
            client.start();
            try {
                // Logged in as their computer: SFTP writes a file onto its drive.
                try (org.apache.sshd.client.session.ClientSession session = client.connect(name, "127.0.0.1", port).verify(10_000).getSession()) {
                    session.addPublicKeyIdentity(mine);
                    session.auth().verify(10_000);
                    try (org.apache.sshd.sftp.client.SftpClient sftp = org.apache.sshd.sftp.client.SftpClientFactory.instance().createSftpClient(session)) {
                        try (java.io.OutputStream file = sftp.write("/from_vscode.py")) {
                            file.write("print('hi from the pc')\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        }
                        StringBuilder names = new StringBuilder();
                        for (org.apache.sshd.sftp.client.SftpClient.DirEntry entry : sftp.readDir("/")) {
                            names.append(entry.getFilename()).append(' ');
                        }
                        results.put("listed", names.toString());
                    }
                    // The shell is its terminal: typing "help" shows the computer's own help.
                    try (org.apache.sshd.client.channel.ChannelShell shell = session.createShellChannel()) {
                        java.io.ByteArrayOutputStream shown = new java.io.ByteArrayOutputStream();
                        shell.setOut(shown);
                        shell.setErr(shown);
                        shell.open().verify(10_000);
                        Thread.sleep(500);
                        shell.getInvertedIn().write("help\r".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        shell.getInvertedIn().flush();
                        for (int i = 0; i < 50 && !shown.toString(java.nio.charset.StandardCharsets.UTF_8).contains("Files:"); i++) {
                            Thread.sleep(100);
                        }
                        results.put("shell", shown.toString(java.nio.charset.StandardCharsets.UTF_8));
                    }
                }
                // A name that isn't theirs: logged in, but nothing there.
                try (org.apache.sshd.client.session.ClientSession session = client.connect("nosuchthing", "127.0.0.1", port).verify(10_000).getSession()) {
                    session.addPublicKeyIdentity(mine);
                    session.auth().verify(10_000);
                    try (org.apache.sshd.sftp.client.SftpClient sftp = org.apache.sshd.sftp.client.SftpClientFactory.instance().createSftpClient(session)) {
                        results.put("wrong_name", "opened");
                    } catch (Exception refused) {
                        results.put("wrong_name", "refused");
                    }
                }
                // A key nobody added: not let in.
                try (org.apache.sshd.client.session.ClientSession session = client.connect(name, "127.0.0.1", port).verify(10_000).getSession()) {
                    session.addPublicKeyIdentity(stranger);
                    session.auth().verify(10_000);
                    results.put("stranger", "let in");
                } catch (Exception refused) {
                    results.put("stranger", "refused");
                }
            } catch (Exception e) {
                results.put("error", e.toString());
            } finally {
                client.stop();
                results.put("done", "yes");
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(results.containsKey("done"), "waiting for the SSH client");
            qbotics.gateway.SshGateway.stop();
            helper.assertTrue(!results.containsKey("error"), "the client failed: " + results.get("error"));
            String file;
            try {
                file = new String(computer.drive().call(qbotics.engine.HostFileSystem.READ, "/from_vscode.py", new byte[0]),
                        java.nio.charset.StandardCharsets.UTF_8);
            } catch (qbotics.engine.HostFileSystem.FsException e) {
                file = "(missing: error " + e.errno + ")";
            }
            helper.assertTrue(file.contains("hi from the pc") && results.getOrDefault("listed", "").contains("from_vscode.py"),
                    "SFTP should have put the file on the computer's drive: " + file + " / " + results.get("listed"));
            helper.assertTrue(results.getOrDefault("shell", "").contains("Connected to " + name) && results.get("shell").contains("Files:"),
                    "the shell should be the computer's terminal: " + results.get("shell"));
            helper.assertTrue("refused".equals(results.get("wrong_name")), "a computer that isn't theirs should be refused: " + results.get("wrong_name"));
            helper.assertTrue("refused".equals(results.get("stranger")), "a key nobody added should be refused: " + results.get("stranger"));
        });
    }

    /**
     * The uplink, without the internet: a computer needs an Uplink on its network; only HTTPS;
     * private and local addresses, and blocked domains, are refused; and the address checks are
     * right for the awkward cases (cloud metadata, IPv6 local, IPv4 inside IPv6).
     */
    private static void uplink(GameTestHelper helper) {
        java.util.function.Predicate<String> forbidden = ip -> {
            try {
                return qbotics.uplink.UplinkTestAccess.forbidden(java.net.InetAddress.getByName(ip));
            } catch (java.net.UnknownHostException e) {
                throw new IllegalStateException(e);
            }
        };
        for (String ip : List.of("127.0.0.1", "10.0.0.5", "192.168.1.1", "172.16.4.4", "169.254.169.254", "100.64.0.12", "0.0.0.0",
                "::1", "fd00::1", "fe80::1", "::ffff:10.0.0.1", "224.0.0.1", "255.255.255.255")) {
            helper.assertTrue(forbidden.test(ip), ip + " should be refused");
        }
        for (String ip : List.of("8.8.8.8", "93.184.216.34", "2606:4700:4700::1111")) {
            helper.assertTrue(!forbidden.test(ip), ip + " should be allowed");
        }
        ServerComputer computer = startAt(helper, new BlockPos(0, 1, 0));
        helper.getBlockEntity(new BlockPos(0, 1, 0), ComputerBlockEntity.class).claim(UUID.randomUUID());
        java.util.List<String> denied = qbotics.QboticsConfig.uplinkDenyDomains;
        qbotics.QboticsConfig.uplinkDenyDomains = List.of("blocked.example");
        for (String line : List.of("python", "import requests",
                "def t(url):\n    try:\n        requests.get(url)\n        print('fetched', url)\n    except requests.RequestException as e:\n        print('refused', e)\n",
                "t('https://example.com/')")) {
            computer.submitLine(line);
        }
        int[] step = {0};
        helper.succeedWhen(() -> {
            String now = screen(computer);
            if (step[0] == 0 && now.contains("needs an Uplink")) {
                helper.setBlock(new BlockPos(1, 1, 0), QboticsContent.UPLINK_BLOCK.get());
                for (String url : List.of("http://example.com/", "https://127.0.0.1/", "https://localhost/", "https://www.blocked.example/")) {
                    computer.submitLine("t('" + url + "')");
                }
                step[0] = 1;
            }
            helper.assertTrue(step[0] == 1, "waiting for the first refusal");
            assertScreen(helper, computer, "needs an Uplink", "HTTPS only", "127.0.0.1 leads to a private", "localhost leads to a private",
                    "www.blocked.example is blocked");
            qbotics.QboticsConfig.uplinkDenyDomains = denied;
        });
    }

    /** git without reaching GitHub: status finds the changes, pull won't overwrite them, push is off, only GitHub clones. */
    private static void gitOffline(GameTestHelper helper) {
        helper.setBlock(new BlockPos(1, 1, 0), QboticsContent.UPLINK_BLOCK.get());
        ServerComputer computer = startAt(helper, new BlockPos(0, 1, 0));
        helper.getBlockEntity(new BlockPos(0, 1, 0), ComputerBlockEntity.class).claim(UUID.randomUUID());
        qbotics.engine.HostFileSystem drive = computer.drive();
        try {
            drive.call(qbotics.engine.HostFileSystem.MKDIR, "/proj", new byte[0]);
            drive.call(qbotics.engine.HostFileSystem.WRITE, "/proj/a.py", "print('a')\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            java.security.MessageDigest sha = java.security.MessageDigest.getInstance("SHA-1");
            String a = java.util.HexFormat.of().formatHex(sha.digest("print('old a')\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            String b = java.util.HexFormat.of().formatHex(sha.digest("b\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            drive.call(qbotics.engine.HostFileSystem.WRITE, "/proj/c.py", "c = 1\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String meta = "{\"owner\":\"me\",\"repo\":\"proj\",\"branch\":\"main\",\"commit\":\"abc1234def\","
                    + "\"files\":{\"a.py\":\"" + a + "\",\"b.py\":\"" + b + "\"}}";
            drive.call(qbotics.engine.HostFileSystem.WRITE, "/proj/.gitqbot", meta.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        for (String line : List.of("cd /proj", "git status", "git pull", "git push -m test", "git clone https://gitlab.com/x/y", "git status /nothing")) {
            computer.submitLine(line);
        }
        helper.succeedWhen(() -> assertScreen(helper, computer, "On me/proj, branch main, at abc1234", "M a.py", "A c.py", "D b.py",
                "changes that pulling would overwrite", "Pushing is switched off", "git clones GitHub repositories",
                "/nothing isn't a cloned repository"));
    }

    /**
     * The Server: memory a computer hasn't got, an uplink of its own (no Uplink block on its
     * network), its own hard drives at /net/disks, and the RAM and Server recipes.
     */
    private static void serverBlock(GameTestHelper helper) {
        java.util.function.Function<String, ItemStack> item = id -> new ItemStack(
                net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(qbotics.Qbotics.id(id)));
        ItemStack gold = new ItemStack(Items.GOLD_INGOT);
        ItemStack ram = craft(helper, gold, gold, gold, gold, item.apply("integrated_circuit"), gold, gold, gold, gold);
        helper.assertTrue(ram.getItem().toString().equals("qbotics:ram"), "the RAM recipe gave " + ram);
        ItemStack debris = new ItemStack(Items.ANCIENT_DEBRIS);
        ItemStack made = craft(helper, debris, item.apply("led_screen"), debris, ram, item.apply("laptop"), ram,
                debris, item.apply("drive_bay"), debris);
        helper.assertTrue(made.getItem().toString().equals("qbotics:server"), "the Server recipe gave " + made);

        BlockPos at = new BlockPos(0, 1, 0);
        helper.setBlock(at, QboticsContent.SERVER_BLOCK.get());
        qbotics.block.ServerBlockEntity block = helper.getBlockEntity(at, qbotics.block.ServerBlockEntity.class);
        block.claim(UUID.randomUUID());
        block.drives().setItem(0, item.apply("hard_drive"));
        ServerComputer computer = ComputerManager.get(helper.getLevel().getServer()).getOrStart(block);
        helper.assertTrue(computer.heapBytes() == qbotics.block.ServerBlockEntity.HEAP_BYTES, "a server's memory");
        // The largest web answer scales with memory: 24 KB for a computer, 96 KB for a laptop, 192 KB for a Server.
        helper.assertTrue(qbotics.uplink.Uplink.answerLimit(ServerComputer.BASIC_HEAP_BYTES) == 24 * 1024
                && qbotics.uplink.Uplink.answerLimit(qbotics.laptop.Laptops.HEAP_BYTES) == 96 * 1024
                && qbotics.uplink.Uplink.answerLimit(qbotics.block.ServerBlockEntity.HEAP_BYTES) == 192 * 1024, "answer sizes by tier");
        for (String line : List.of("python", "b = bytearray(1500000)", "print('big', len(b))", "del b", "import requests",
                "try:\n    requests.get('https://127.0.0.1/')\nexcept requests.RequestException as e:\n    print('refused', e)\n")) {
            computer.submitLine(line);
        }
        int[] ticks = {0};
        helper.succeedWhen(() -> {
            assertScreen(helper, computer, "big 1500000", "127.0.0.1 leads to a private");
            // The drives are mounted at the next refresh, a few ticks on: look until they are there.
            if (ticks[0]++ % 20 == 0 && !screen(computer).contains("disks ['disk1']")) {
                computer.submitLine("import os");
                computer.submitLine("print('disks', os.listdir('/net/disks') if 'disks' in os.listdir('/net') else [])");
            }
            assertScreen(helper, computer, "disks ['disk1']");
            helper.assertTrue(!screen(computer).contains("needs an Uplink"), "a server has its own uplink");
        });
    }

    /**
     * A Server's services: one that keeps running, one that fails, one that waits for a
     * message sent to the Server. Lights show them; they come back when the Server restarts;
     * service stop ends one for good.
     */
    private static void serverServices(GameTestHelper helper) {
        BlockPos at = new BlockPos(0, 1, 0);
        helper.setBlock(at, QboticsContent.SERVER_BLOCK.get());
        qbotics.block.ServerBlockEntity block = helper.getBlockEntity(at, qbotics.block.ServerBlockEntity.class);
        block.claim(UUID.randomUUID());
        ComputerManager manager = ComputerManager.get(helper.getLevel().getServer());
        ServerComputer[] server = {manager.getOrStart(block)};
        try {
            java.nio.charset.Charset utf8 = java.nio.charset.StandardCharsets.UTF_8;
            server[0].drive().call(qbotics.engine.HostFileSystem.WRITE, "/tick.py",
                    "import time\nn = 0\nwhile True:\n    n += 1\n    print('tick', n)\n    time.sleep(0.2)\n".getBytes(utf8));
            server[0].drive().call(qbotics.engine.HostFileSystem.WRITE, "/bad.py", "raise ValueError('broken on purpose')\n".getBytes(utf8));
            server[0].drive().call(qbotics.engine.HostFileSystem.WRITE, "/listen.py",
                    "import network\nprint('got', network.receive())\n".getBytes(utf8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        for (String line : List.of("service start /tick.py", "service start /bad.py", "service start /listen.py inbox")) {
            server[0].submitLine(line);
        }
        int[] step = {0};
        UUID id = block.deviceId();
        helper.succeedWhen(() -> {
            qbotics.computer.Services services = server[0].services();
            helper.assertTrue(services != null, "a server has services");
            switch (step[0]) {
                case 0 -> {
                    ServerComputer tick = services.get("tick");
                    ServerComputer bad = services.get("bad");
                    ServerComputer inbox = services.get("inbox");
                    helper.assertTrue(tick != null && bad != null && inbox != null, "three services started");
                    helper.assertTrue(screen(tick).contains("tick 2"), "tick is ticking");
                    helper.assertTrue(bad.serviceState() == ServerComputer.ServiceState.FAILED, "bad failed: " + bad.serviceState());
                    helper.assertTrue(inbox.listensForMessages(), "inbox waits for a message");
                    BlockState state = helper.getBlockState(at);
                    helper.assertTrue(state.getValue(qbotics.block.ServerBlock.LIGHTS[0]) == 1
                            && state.getValue(qbotics.block.ServerBlock.LIGHTS[1]) == 2
                            && state.getValue(qbotics.block.ServerBlock.LIGHTS[2]) == 1, "the lights: " + state);
                    // A message to the Server: its terminal isn't listening, so the waiting service gets it.
                    helper.assertTrue(manager.deliver(id, "100.64.0.9", "\"hello\""), "the message was taken");
                    server[0].submitLine("service log bad");
                    server[0].submitLine("service list");
                    step[0] = 1;
                    helper.fail("next");
                }
                case 1 -> {
                    assertScreen(helper, server[0], "broken on purpose", "tick", "running", "failed");
                    helper.assertTrue(screen(services.get("inbox")).contains("got"), "the service got the message");
                    // The Server goes off and comes back: its services start again.
                    manager.remove(id);
                    server[0] = manager.getOrStart(block);
                    step[0] = 2;
                    helper.fail("next");
                }
                case 2 -> {
                    ServerComputer tick = services.get("tick");
                    helper.assertTrue(tick != null && screen(tick).contains("tick 2"), "tick came back");
                    server[0].submitLine("service stop tick");
                    step[0] = 3;
                    helper.fail("next");
                }
                default -> {
                    helper.assertTrue(services.get("tick") == null, "tick was stopped");
                    assertScreen(helper, server[0], "Stopped tick");
                    helper.assertTrue(helper.getBlockState(at).getValue(qbotics.block.ServerBlock.LIGHTS[0]) == 0, "tick's light went out");
                }
            }
        });
    }

    /** A Server takes a Chunk Loader Upgrade, as an antenna does: it holds its network's chunks while switched on, under the same settings. */
    private static void serverChunkLoading(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos at = new BlockPos(0, 1, 0);
        helper.setBlock(at, QboticsContent.SERVER_BLOCK.get());
        qbotics.block.ServerBlockEntity rack = helper.getBlockEntity(at, qbotics.block.ServerBlockEntity.class);
        rack.claim(UUID.randomUUID());
        ItemStack loader = new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(qbotics.Qbotics.id("chunk_loader_upgrade")));
        ItemStack transport = new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(qbotics.Qbotics.id("transport_upgrade")));
        helper.assertTrue(!rack.upgrades().canPlaceItem(0, transport), "a Server takes only a Chunk Loader Upgrade");
        helper.assertTrue(rack.upgrades().canPlaceItem(0, loader), "a Server takes a Chunk Loader Upgrade");
        BlockPos abs = helper.absolutePos(at);
        boolean wasOffline = qbotics.QboticsConfig.chunkLoadingWhileOffline;
        qbotics.QboticsConfig.chunkLoadingWhileOffline = true; // its owner here is no one online
        helper.assertTrue(qbotics.upgrade.ChunkLoaders.Status.of(rack.loaderStatus()) == qbotics.upgrade.ChunkLoaders.Status.NO_UPGRADE,
                "no upgrade yet");
        rack.upgrades().setItem(0, loader);
        int[] phase = {0};
        helper.succeedWhen(() -> {
            if (phase[0] == 0) {
                helper.assertTrue(qbotics.upgrade.ChunkLoaders.Status.of(rack.loaderStatus()) == qbotics.upgrade.ChunkLoaders.Status.LOADING,
                        "loading: " + qbotics.upgrade.ChunkLoaders.Status.of(rack.loaderStatus()));
                helper.assertTrue(qbotics.upgrade.ChunkLoaders.heldBy(level, abs).contains(net.minecraft.world.level.ChunkPos.pack(abs)),
                        "it holds its own chunk");
                rack.setChunkLoading(false);
                phase[0] = 1;
                helper.fail("next");
            }
            helper.assertTrue(qbotics.upgrade.ChunkLoaders.Status.of(rack.loaderStatus()) == qbotics.upgrade.ChunkLoaders.Status.OFF, "switched off");
            helper.assertTrue(qbotics.upgrade.ChunkLoaders.heldBy(level, abs).isEmpty(), "switched off, it holds nothing");
            qbotics.QboticsConfig.chunkLoadingWhileOffline = wasOffline;
        });
    }

    /** The web panel engine: litehtml lays out a page with flexbox, Java 2D paints it, and a click finds its button. */
    private static void webPage(GameTestHelper helper) {
        String html = String.join("\n",
                "<!doctype html><html><head><style>",
                "body { margin: 0; font-family: sans-serif; background: #102030; color: white; }",
                "h1 { font-size: 22px; margin: 8px; }",
                ".row { display: flex; justify-content: space-between; align-items: center; padding: 8px; gap: 6px; }",
                ".card { flex: 1; padding: 6px; background: #345; border-radius: 6px; border: 1px solid #89a; }",
                ".big { flex: 2; background: linear-gradient(90deg, #345, #563); }",
                "button { background: linear-gradient(#4a4, #262); color: white; border: none; border-radius: 4px; padding: 4px 10px; }",
                "table { margin: 8px; border-collapse: collapse; } td { border: 1px solid #567; padding: 2px 6px; }",
                ".bar { height: 8px; margin: 8px; background: #234; border-radius: 4px; } .fill { width: 70%; height: 8px; background: #e94; border-radius: 4px; }",
                "</style></head><body>",
                "<h1>Factory <small style='color:#9bd'>online</small></h1>",
                "<div class='row'><div class='card' id='iron'>Iron <b>128</b></div><div class='card big' id='gold'>Gold <i>64</i></div>",
                "<button data-event='smelt' id='go'>Smelt</button></div>",
                "<div class='bar'><div class='fill'></div></div>",
                "<table><tr><td>Coal</td><td>512</td></tr><tr><td>Copper</td><td>96</td></tr></table>",
                "<ul><li>Furnaces: 4</li><li>Crushers: 2</li></ul>",
                "</body></html>");
        long start = System.nanoTime();
        qbotics.web.WebPage page = new qbotics.web.WebPage(url -> null);
        long loaded = System.nanoTime();
        page.load(html, "");
        float height = page.layout(384, 256);
        java.awt.image.BufferedImage image = qbotics.web.WebPainter.paint(page, 0, 2, url -> null);
        long painted = System.nanoTime();
        try {
            javax.imageio.ImageIO.write(image, "png", java.nio.file.Path.of("web-page-test.png").toFile());
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        qbotics.Qbotics.LOGGER.info("Web page: engine {} ms, load+layout+paint {} ms, content height {}",
                (loaded - start) / 1_000_000, (painted - loaded) / 1_000_000, height);
        helper.assertTrue(height > 100, "the page has content: " + height);
        // Lay out and paint again: how long a page update takes once the engine is warm.
        long again = System.nanoTime();
        page.load(html.replace("128", "129"), "");
        page.layout(384, 256);
        qbotics.web.WebPainter.paint(page, 0, 2, url -> null);
        qbotics.Qbotics.LOGGER.info("Web page: an update {} ms", (System.nanoTime() - again) / 1_000_000);
        // The button is at the right of the row: find it by scanning the row for a hit.
        String found = null;
        for (int x = 380; x > 200 && found == null; x -= 4) {
            String hit = page.hit(x, 60);
            if (hit != null && hit.contains("smelt")) {
                found = hit;
            }
        }
        helper.assertTrue(found != null && found.contains("\"event\":\"smelt\"") && found.contains("\"tag\":\"button\""),
                "a click on the button finds it: " + found);
        String card = page.hit(30, 60);
        helper.assertTrue(card != null && card.contains("\"id\":\"iron\""), "a click on the iron card: " + card);
        helper.succeed();
    }

    /**
     * A Web Panel: a program shows a page from its drive (its stylesheet put in), sets state the
     * page shows as {{name}}, and hears a click on a button, found where the page is laid out.
     */
    private static void webPanel(GameTestHelper helper) {
        net.minecraft.world.level.block.state.BlockState web = QboticsContent.WEB_PANEL_BLOCK.get().defaultBlockState()
                .setValue(qbotics.block.DisplayBlock.FACING, Direction.SOUTH);
        ServerComputer computer = startAt(helper, new BlockPos(0, 1, 1));
        helper.setBlock(new BlockPos(0, 1, 2), web);
        helper.setBlock(new BlockPos(1, 1, 2), web);
        qbotics.block.DisplayBlockEntity panel = helper.getBlockEntity(new BlockPos(0, 1, 2), qbotics.block.DisplayBlockEntity.class);
        helper.assertTrue(panel.isOrigin() && panel.screenWidth() == 2 && panel.content() instanceof qbotics.display.WebScreen,
                "two web panels make one 2x1 web panel");
        String page = String.join("\n",
                "<html><head><link rel=\"stylesheet\" href=\"style.css\"></head><body>",
                "<h2>Smeltery</h2><div class=\"row\"><span>Iron: {{iron}}</span> <span>Gold: {{gold}}</span>",
                "<button data-event=\"smelt\">Smelt</button></div><p>First: {{items.0}}, then {{items.1}}. {{missing}}</p>",
                "<script src=\"app.js\"></script></body></html>");
        String style = ".row { display: flex; gap: 8px; align-items: center; justify-content: space-between; } h2 { color: #ffb347; margin: 0 0 6px 0; }";
        String program = String.join("\n",
                "import events",
                "from logistics import network",
                "net = network()",
                "web = net.web_panel()",
                "print('size', web.size())",
                "web.show('/app/index.html')",
                "web.state['iron'] = 128",
                "web.state.update(gold=64, items=['coal', 'copper <b>'])",
                "print('state', web.state['iron'], web.state.get('gold'))",
                "@web.on('smelt')",
                "def smelt(e):",
                "    print('smelt by', e.player, e.tag)",
                "    events.stop()",
                "events.run()",
                "print('done')",
                "");
        try {
            java.nio.charset.Charset utf8 = java.nio.charset.StandardCharsets.UTF_8;
            computer.drive().call(qbotics.engine.HostFileSystem.MKDIR, "/app", new byte[0]);
            computer.drive().call(qbotics.engine.HostFileSystem.WRITE, "/app/index.html", page.getBytes(utf8));
            computer.drive().call(qbotics.engine.HostFileSystem.WRITE, "/app/style.css", style.getBytes(utf8));
            computer.drive().call(qbotics.engine.HostFileSystem.WRITE, "/app/app.js", "console.log('app loaded');".getBytes(utf8));
            computer.drive().call(qbotics.engine.HostFileSystem.WRITE, "/web.py", program.getBytes(utf8));
        } catch (qbotics.engine.HostFileSystem.FsException e) {
            throw new IllegalStateException(e);
        }
        computer.submitLine("run /web.py");
        boolean[] clicked = {false};
        helper.succeedWhen(() -> {
            assertScreen(helper, computer, "size (256, 128)", "state 128 64");
            qbotics.display.WebScreen screen = (qbotics.display.WebScreen) panel.content();
            String html;
            String state;
            synchronized (screen) {
                html = screen.html();
                state = screen.stateJson();
            }
            helper.assertTrue(html.contains("<style>") && html.contains("#ffb347") && !html.contains("<link"), "the stylesheet is put in the page");
            helper.assertTrue(html.contains("console.log('app loaded')") && !html.contains("src=\"app.js\""), "the script is put in the page");
            helper.assertTrue(state.contains("\"iron\":128") && state.contains("copper <b>"), "the state: " + state);
            String filled = qbotics.web.WebTemplate.fill(html, state);
            helper.assertTrue(filled.contains("Iron: 128") && filled.contains("copper &lt;b&gt;") && filled.contains("then copper") && !filled.contains("{{"),
                    "the state fills the page");
            if (!clicked[0]) {
                helper.assertTrue(!screen.listeners().isEmpty(), "the program listens for clicks");
                // Lay the page out as a player's game would, keep a picture of it, and click its button.
                qbotics.web.WebPage laid = new qbotics.web.WebPage(url -> null);
                laid.load(filled, "html { background: #0e1216; color: #e6e8ea; } body { margin: 8px; font-family: sans-serif; font-size: 14px; }");
                laid.layout(256, 128);
                try {
                    javax.imageio.ImageIO.write(qbotics.web.WebPainter.paint(laid, 0, 2, url -> null), "png",
                            java.nio.file.Path.of("web-panel-test.png").toFile());
                } catch (java.io.IOException e) {
                    throw new IllegalStateException(e);
                }
                String hit = null;
                for (int x = 250; x > 100 && hit == null; x -= 3) {
                    for (int y = 20; y < 70 && hit == null; y += 3) {
                        String h = laid.hit(x, y);
                        hit = h != null && h.contains("smelt") ? h : null;
                    }
                }
                helper.assertTrue(hit != null, "the button is on the page");
                net.minecraft.server.level.ServerPlayer player = helper.makeMockServerPlayerInLevel();
                BlockPos at = helper.absolutePos(new BlockPos(0, 1, 3));
                player.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
                qbotics.display.Displays.onWebClick(player, new qbotics.net.DisplayPayloads.WebClick(panel.getBlockPos(), hit));
                clicked[0] = true;
            }
            assertScreen(helper, computer, "smelt by", "button", "done");
        });
    }

    /**
     * What a storage screen needs: a row per item ({{#items}}), item icons, a search field the
     * player types in, a list that scrolls, and the typed search reaching the program.
     */
    private static void webStorage(GameTestHelper helper) {
        String page = String.join("\n",
                "<html><head><style>",
                ".list { height: 60px; overflow: auto; } .row { height: 20px; } img { width: 16px; height: 16px; }",
                "</style></head><body>",
                "<input name=\"search\" placeholder=\"Search...\">",
                "<div class=\"list\">{{#items}}<div class=\"row\"><img src=\"item:{{id}}\"> {{name}} x{{count}}</div>{{/items}}</div>",
                "{{^items}}<p>Nothing found</p>{{/items}}",
                "</body></html>");
        String state = "{\"items\":[{\"id\":\"minecraft:iron_ingot\",\"name\":\"Iron\",\"count\":512},{\"id\":\"minecraft:gold_ingot\",\"name\":\"Gold\",\"count\":64},"
                + "{\"id\":\"minecraft:coal\",\"name\":\"Coal\",\"count\":900},{\"id\":\"minecraft:diamond\",\"name\":\"Diamond\",\"count\":3},"
                + "{\"id\":\"minecraft:redstone\",\"name\":\"Redstone\",\"count\":77}]}";
        String filled = qbotics.web.WebTemplate.fill(page, state);
        helper.assertTrue(filled.contains("Iron x512") && filled.contains("Redstone x77") && filled.contains("item:minecraft:diamond")
                && !filled.contains("Nothing found"), "a row per item");
        helper.assertTrue(qbotics.web.WebTemplate.fill(page, "{\"items\":[]}").contains("Nothing found"), "an empty list says so");
        String typed = qbotics.web.WebInputs.rewrite(filled, java.util.Map.of("search", "ir"), "search");
        helper.assertTrue(typed.contains("data-input=\"search\"") && typed.contains(">ir<") && typed.contains("qb-focus") && !typed.contains("<input"),
                "the field shows what was typed");
        helper.assertTrue(qbotics.web.WebInputs.rewrite(filled, java.util.Map.of(), null).contains("Search..."), "an empty field shows its placeholder");

        qbotics.web.WebPage laid = new qbotics.web.WebPage(url -> null);
        laid.load(typed, qbotics.web.WebInputs.CSS + " body { margin: 8px; font-family: sans-serif; font-size: 14px; color: #e6e8ea; background: #0e1216; }");
        laid.layout(256, 128);
        java.util.List<qbotics.web.WebPainter.Overlay> overlays = new java.util.ArrayList<>();
        java.awt.image.BufferedImage picture = qbotics.web.WebPainter.paint(laid, 0, 2, url -> null, overlays);
        try {
            javax.imageio.ImageIO.write(picture, "png", java.nio.file.Path.of("web-storage-test.png").toFile());
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        // Three 20-pixel rows show in the 60-pixel list; the icons of the rest are hidden by it.
        helper.assertTrue(overlays.size() == 3 && overlays.get(0).url().equals("minecraft:iron_ingot") && overlays.get(0).width() == 16,
                "item icons to draw: " + overlays);
        String field = laid.hit(20, 18);
        helper.assertTrue(field != null && field.contains("\"input\":\"search\""), "a click on the field finds it: " + field);
        float moved = laid.scroll(40, 30, 60);
        helper.assertTrue(moved > 0, "the list scrolls: " + moved);
        overlays.clear();
        qbotics.web.WebPainter.paint(laid, 0, 2, url -> null, overlays);
        helper.assertTrue(!overlays.isEmpty() && !overlays.get(0).url().equals("minecraft:iron_ingot"), "scrolled, other items show: " + overlays);

        // The search, as the player's game sends it, reaches the program.
        net.minecraft.world.level.block.state.BlockState web = QboticsContent.WEB_PANEL_BLOCK.get().defaultBlockState()
                .setValue(qbotics.block.DisplayBlock.FACING, Direction.SOUTH);
        ServerComputer computer = startAt(helper, new BlockPos(0, 1, 1));
        helper.setBlock(new BlockPos(0, 1, 2), web);
        qbotics.block.DisplayBlockEntity panel = helper.getBlockEntity(new BlockPos(0, 1, 2), qbotics.block.DisplayBlockEntity.class);
        for (String line : List.of("python", "import events", "from logistics import network", "web = network().web_panel()",
                "web.on('search', lambda e: print('search', e.value, e.enter, e.tag))",
                "web.on('picked', lambda e: print('picked', e.data['item'], e.data['count'], e.tag))", "events.run()")) {
            computer.submitLine(line);
        }
        boolean[] sent = {false};
        helper.succeedWhen(() -> {
            if (!sent[0]) {
                helper.assertTrue(screen(computer).contains("events.run()") && panel.content() != null
                        && !panel.content().listeners().isEmpty(), "the program listens");
                net.minecraft.server.level.ServerPlayer player = helper.makeMockServerPlayerInLevel();
                BlockPos at = helper.absolutePos(new BlockPos(0, 1, 3));
                player.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
                qbotics.display.Displays.onWebClick(player, new qbotics.net.DisplayPayloads.WebClick(panel.getBlockPos(),
                        "{\"tag\":\"input\",\"id\":\"\",\"name\":\"search\",\"event\":\"search\",\"value\":\"iron\",\"enter\":true}"));
                // panel.send('picked', {...}) from a page's script, as the player's game sends it.
                qbotics.display.Displays.onWebClick(player, new qbotics.net.DisplayPayloads.WebClick(panel.getBlockPos(),
                        "{\"tag\":\"script\",\"id\":\"\",\"name\":\"\",\"event\":\"picked\",\"value\":\"\",\"data\":\"{\\\"item\\\":\\\"coal\\\",\\\"count\\\":12}\"}"));
                sent[0] = true;
            }
            assertScreen(helper, computer, "search iron True input", "picked coal 12 script");
        });
    }

    /**
     * A Qbot's camera: a picture of a stone wall with a pig before it, what its pixels show,
     * one picture a second, and the picture put on a web panel beside it.
     */
    private static void qbotCamera(GameTestHelper helper) {
        BlockPos start = new BlockPos(1, 1, 5);
        helper.setBlock(start, QboticsContent.IRON_QBOT_BLOCK.get().defaultBlockState().setValue(qbotics.block.ComputerBlock.FACING, Direction.NORTH));
        qbotics.qbot.QbotBlockEntity qbot = helper.getBlockEntity(start, qbotics.qbot.QbotBlockEntity.class);
        qbot.onPlaced(UUID.randomUUID());
        for (int x = -2; x <= 4; x++) {
            for (int y = 0; y <= 4; y++) {
                helper.setBlock(new BlockPos(x, y, 1), Blocks.STONE);
            }
        }
        helper.spawnWithNoFreeWill(net.minecraft.world.entity.EntityType.PIG, new BlockPos(1, 1, 3));
        helper.setBlock(new BlockPos(2, 1, 5), QboticsContent.WEB_PANEL_BLOCK.get().defaultBlockState()
                .setValue(qbotics.block.DisplayBlock.FACING, Direction.SOUTH));
        String program = String.join("\n",
                "import qbot",
                "from logistics import network",
                "pic = qbot.snapshot(32, 24)",
                "print('pic', pic.width, pic.height)",
                "print('middle', pic.block_at(16, 12))",
                "print('top', pic.block_at(16, 0))",
                "seen = pic.counts()",
                "print('stone', seen.get('minecraft:stone', 0) > 100, 'pig', seen.get('animal', 0) > 0)",
                "try:",
                "    qbot.snapshot()",
                "except qbot.QbotError as e:",
                "    print('again:', e)",
                "web = network().web_panel()",
                "web.html('<img src=\"image:cam\">')",
                "web.image('cam', pic)",
                "print('shown')",
                "");
        ServerComputer computer = ComputerManager.get(helper.getLevel().getServer()).getOrStart(qbot);
        try {
            computer.drive().call(qbotics.engine.HostFileSystem.WRITE, "/cam.py", program.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (qbotics.engine.HostFileSystem.FsException e) {
            throw new IllegalStateException(e);
        }
        computer.submitLine("run /cam.py");
        helper.succeedWhen(() -> {
            assertScreen(helper, computer, "pic 32 24", "middle animal", "stone True pig True", "again: A Qbot takes one picture a second", "shown");
            qbotics.block.DisplayBlockEntity panel = helper.getBlockEntity(new BlockPos(2, 1, 5), qbotics.block.DisplayBlockEntity.class);
            qbotics.display.WebScreen web = (qbotics.display.WebScreen) panel.content();
            qbotics.camera.Picture picture = web.images().get("cam");
            helper.assertTrue(picture != null && picture.width() == 32 && picture.data().length == 32 * 24 * qbotics.camera.Picture.BYTES_PER_PIXEL,
                    "the picture is on the panel");
        });
    }

    /** The web panels' JavaScript engine: it runs code, calls the game, keeps to its time budget, and how fast it is. */
    private static void jsEngine(GameTestHelper helper) {
        long start = System.nanoTime();
        java.util.List<String> calls = new java.util.ArrayList<>();
        qbotics.web.JsRuntime js = new qbotics.web.JsRuntime(16 << 20, (op, arg) -> {
            calls.add(op + ":" + arg);
            return op.equals("echo") ? "<" + arg + ">" : null;
        });
        long made = System.nanoTime();
        qbotics.web.JsRuntime.Result sum = js.eval("let s = 0; for (let i = 0; i < 10; i++) s += i; s", "sum.js", 1000);
        helper.assertTrue(sum.ok() && sum.text().equals("45"), "a sum: " + sum);
        qbotics.web.JsRuntime.Result echo = js.eval("__host('echo', 'hi') + [1,2,3].map(x => x * 2).join(',')", "echo.js", 1000);
        helper.assertTrue(echo.ok() && echo.text().equals("<hi>2,4,6"), "the game answers: " + echo);
        qbotics.web.JsRuntime.Result error = js.eval("function f() { null.x; }\nf();", "error.js", 1000);
        helper.assertTrue(!error.ok() && error.text().contains("TypeError") && error.text().contains("error.js"), "an error and where: " + error);
        long loop = System.nanoTime();
        qbotics.web.JsRuntime.Result forever = js.eval("while (true) {}", "loop.js", 50);
        long stopped = (System.nanoTime() - loop) / 1_000_000;
        helper.assertTrue(!forever.ok() && forever.text().contains("interrupted") && stopped < 1000, "a loop forever is stopped: " + forever + " in " + stopped);
        long bench = System.nanoTime();
        qbotics.web.JsRuntime.Result fib = js.eval("function fib(n) { return n < 2 ? n : fib(n - 1) + fib(n - 2); } fib(24)", "fib.js", 0);
        long fibMs = (System.nanoTime() - bench) / 1_000_000;
        long bench2 = System.nanoTime();
        js.eval("let a = []; for (let i = 0; i < 20000; i++) a.push({x: i, s: 'item' + i}); a.filter(o => o.x % 3 == 0).map(o => o.s).join('').length",
                "objects.js", 0);
        long objectsMs = (System.nanoTime() - bench2) / 1_000_000;
        for (int round = 0; round < 4; round++) {
            long warm = System.nanoTime();
            js.eval("fib(24)", "fib.js", 0);
            qbotics.Qbotics.LOGGER.info("JS engine: fib(24) again in {} ms", (System.nanoTime() - warm) / 1_000_000);
        }
        qbotics.Qbotics.LOGGER.info("JS engine: made in {} ms, fib(24) = {} in {} ms, 20000 objects in {} ms, memory {} KB",
                (made - start) / 1_000_000, fib.text(), fibMs, objectsMs, js.memoryUsed() / 1024);
        helper.succeed();
    }

    /**
     * A page with scripts: they build part of the page, draw on a canvas, answer clicks (and
     * can stop a button's default), hear the program's state, and send to it.
     */
    private static void webScript(GameTestHelper helper) {
        java.util.List<String> sent = new java.util.ArrayList<>();
        java.util.List<String> errors = new java.util.ArrayList<>();
        java.util.List<String> logs = new java.util.ArrayList<>();
        qbotics.web.WebScript script = new qbotics.web.WebScript(new qbotics.web.WebScript.Hooks() {
            @Override
            public void send(String name, String dataJson) {
                sent.add(name + " " + dataJson);
            }

            @Override
            public void log(String line) {
                logs.add(line);
            }

            @Override
            public void error(String message) {
                errors.add(message);
            }

            @Override
            public String value(String field) {
                return field.equals("q") ? "iron" : null;
            }

            @Override
            public void setValue(String field, String value) {
            }
        });
        String page = String.join("\n",
                "<html><head><style>body { margin: 6px; font-family: sans-serif; background: #0e1216; color: #e6e8ea; }",
                ".on { color: #7f7; } li { margin: 2px 0; }</style></head><body>",
                "<h3 id='title'>Loading</h3><ul id='list'></ul>",
                "<canvas id='chart' width='200' height='60'></canvas>",
                "<button id='go' data-event='go'>Go</button> <button id='quiet' data-event='quiet'>Quiet</button>",
                "<input name='q'>",
                "<script>",
                "const list = document.getElementById('list');",
                "for (const ore of ['Iron', 'Gold', 'Coal']) { const li = document.createElement('li'); li.textContent = ore; list.appendChild(li); }",
                "document.querySelector('#title').textContent = 'Ores: ' + document.querySelectorAll('#list > li').length;",
                "const ctx = document.getElementById('chart').getContext('2d');",
                "ctx.fillStyle = '#123'; ctx.fillRect(0, 0, 200, 60);",
                "ctx.fillStyle = 'orange'; [10, 30, 22, 50, 40].forEach((v, i) => ctx.fillRect(10 + i * 38, 60 - v, 28, v));",
                "ctx.strokeStyle = '#7cf'; ctx.lineWidth = 2; ctx.beginPath(); ctx.arc(180, 15, 10, 0, Math.PI * 2); ctx.stroke();",
                "ctx.font = 'bold 12px sans-serif'; ctx.fillStyle = 'white'; ctx.fillText('Output', 6, 14);",
                "let clicks = 0;",
                "document.getElementById('go').addEventListener('click', e => { clicks++; document.getElementById('title').classList.add('on'); panel.send('clicked', {clicks, q: document.querySelector('input').value}); });",
                "document.getElementById('quiet').onclick = null; document.getElementById('quiet').addEventListener('click', e => e.preventDefault());",
                "panel.onState(s => { document.getElementById('title').textContent = 'Iron: ' + s.iron; });",
                "setTimeout(() => console.log('timer ran'), 0);",
                "</script></body></html>");
        script.load(page, 256, 192, "{\"iron\": 5}");
        helper.assertTrue(errors.isEmpty(), "no errors: " + errors);
        String html = script.html();
        helper.assertTrue(html.contains("Ores: 3") && html.contains(">Gold</li>") && !html.contains("<script") && html.contains("src=\"canvas:"),
                "the scripts built the page: " + html);
        helper.assertTrue(logs.contains("timer ran"), "a timer ran: " + logs);
        // Lay the page out and paint it, with the canvas.
        qbotics.web.WebPage laid = new qbotics.web.WebPage(url -> url.startsWith("canvas:") ? new float[] {200, 60} : null);
        laid.load(html, "");
        laid.layout(256, 192);
        java.awt.image.BufferedImage picture = qbotics.web.WebPainter.paint(laid, 0, 2,
                url -> url.startsWith("canvas:") ? script.canvas(Integer.parseInt(url.substring(7))) : null);
        try {
            javax.imageio.ImageIO.write(picture, "png", java.nio.file.Path.of("web-script-test.png").toFile());
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        java.awt.image.BufferedImage chart = script.canvas(Integer.parseInt(html.replaceAll("(?s).*src=\"canvas:(\\d+)\".*", "$1")));
        helper.assertTrue(chart != null && (chart.getRGB(10 + 3 * 38 + 5, 55) & 0xFFFFFF) == 0xFFA500, "the canvas has its bars");
        // Click the Go button: the script hears it, and its default (data-event) goes on.
        String hit = null;
        for (int x = 4; x < 252 && hit == null; x += 3) {
            for (int y = 60; y < 190 && hit == null; y += 3) {
                String h = laid.hit(x, y);
                hit = h != null && h.contains("\"id\":\"go\"") ? h : null;
            }
        }
        helper.assertTrue(hit != null, "the Go button is on the page");
        int number = com.google.gson.JsonParser.parseString(hit).getAsJsonObject().get("qbn").getAsInt();
        helper.assertTrue(script.click(number, 10, 10), "the button's default goes on");
        helper.assertTrue(sent.size() == 1 && sent.get(0).contains("\"clicks\":1") && sent.get(0).contains("\"q\":\"iron\""), "panel.send: " + sent);
        helper.assertTrue(script.takeHtmlChanged() && script.html().contains("class=\"on\""), "the click changed the page");
        // A button whose script calls preventDefault: its default doesn't go on.
        int quiet = Integer.parseInt(script.html().replaceAll("(?s).*id=\"quiet\"[^>]*data-qbn=\"(\\d+)\".*", "$1"));
        helper.assertTrue(!script.click(quiet, 1, 1), "preventDefault stops the default");
        script.setState("{\"iron\": 42}");
        helper.assertTrue(script.html().contains("Iron: 42"), "panel.onState updated the page");
        // How long an animation frame takes, part by part (the reactor page of the screenshots).
        qbotics.web.WebScript anim = new qbotics.web.WebScript(new qbotics.web.WebScript.Hooks() {
            public void send(String n, String d) { }
            public void log(String l) { }
            public void error(String m) { errors.add(m); }
            public String value(String f) { return null; }
            public void setValue(String f, String v) { }
        });
        anim.load(String.join("\n",
                "<html><body><h2>Reactor</h2><div><span>Ticks</span><span id='count'>0</span></div><canvas id='c' width='240' height='150'></canvas><script>",
                "let n = 0; const c = document.getElementById('c'); const g = c.getContext('2d'); const history = [];",
                "function frame(t) { n++; document.getElementById('count').textContent = n;",
                "  history.push(60 + 40 * Math.sin(t / 300)); if (history.length > 60) history.shift();",
                "  g.fillStyle = '#0b1a26'; g.fillRect(0, 0, 240, 150);",
                "  g.strokeStyle = '#ffb347'; g.lineWidth = 2; g.beginPath(); history.forEach((v, i) => i ? g.lineTo(i * 4, 150 - v) : g.moveTo(0, 150 - v)); g.stroke();",
                "  requestAnimationFrame(frame); }",
                "requestAnimationFrame(frame);</script></body></html>"), 256, 256, "{}");
        qbotics.web.WebPage animPage = new qbotics.web.WebPage(url -> url.startsWith("canvas:") ? new float[] {240, 150} : null);
        long jsMs = 0;
        long layoutMs = 0;
        long paintMs = 0;
        for (int i = 0; i < 20; i++) {
            long t0 = System.nanoTime();
            anim.tick();
            long t1 = System.nanoTime();
            if (anim.takeHtmlChanged()) {
                animPage.load(anim.html(), "");
                animPage.layout(256, 256);
            }
            long t2 = System.nanoTime();
            qbotics.web.WebPainter.paint(animPage, 0, 2, url -> url.startsWith("canvas:") ? anim.canvas(Integer.parseInt(url.substring(7))) : null);
            long t3 = System.nanoTime();
            if (i >= 10) {
                jsMs += t1 - t0;
                layoutMs += t2 - t1;
                paintMs += t3 - t2;
            }
        }
        qbotics.Qbotics.LOGGER.info("Web frame (average of 10): scripts {} ms, layout {} ms, paint {} ms",
                jsMs / 10_000_000.0, layoutMs / 10_000_000.0, paintMs / 10_000_000.0);
        // A script that never ends is stopped.
        script.load("<script>while (true) {}</script>", 100, 100, "{}");
        helper.assertTrue(errors.stream().anyMatch(e -> e.contains("ran too long")), "a runaway script is stopped: " + errors);
        helper.succeed();
    }

    /**
     * The examples that come with the mod: git clone helpme without an Uplink, a README, status
     * after an edit, pull refusing and then putting the originals back, no push; an example runs;
     * the dashboard page's script runs without errors.
     */
    private static void helpme(GameTestHelper helper) {
        ServerComputer computer = startAt(helper, new BlockPos(0, 1, 0));
        computer.submitLine("git clone helpme");
        computer.submitLine("cat /helpme/displays/README.md");
        int[] step = {0};
        helper.succeedWhen(() -> {
            switch (step[0]) {
                case 0 -> {
                    assertScreen(helper, computer, "files from qbotics/helpme, branch bundled", "web_storage");
                    try {
                        computer.drive().call(qbotics.engine.HostFileSystem.WRITE, "/helpme/basics/hello.py",
                                "print('changed')\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    } catch (qbotics.engine.HostFileSystem.FsException e) {
                        throw new IllegalStateException(e);
                    }
                    for (String line : List.of("cd /helpme", "git status", "git pull", "git push -m mine", "git pull --force", "git pull")) {
                        computer.submitLine(line);
                    }
                    step[0] = 1;
                    helper.fail("next");
                }
                case 1 -> {
                    assertScreen(helper, computer, "M basics/hello.py", "changes that pulling would overwrite", "can't be pushed",
                            "files.", "Already up to date");
                    computer.submitLine("run /helpme/basics/hello.py");
                    computer.submitLine("Builder");
                    step[0] = 2;
                    helper.fail("next");
                }
                default -> {
                    assertScreen(helper, computer, "Nice to meet you, Builder", "Lift off!", "4096 blocks");
                    // The dashboard's page and script, as show() would put them together, run in a page's script runtime.
                    try {
                        String page = new String(QboticsGameTests.class.getResourceAsStream("/qbotics/helpme/displays/web_dashboard/index.html")
                                .readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                        String js = new String(QboticsGameTests.class.getResourceAsStream("/qbotics/helpme/displays/web_dashboard/dashboard.js")
                                .readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                        page = page.replace("<script src=\"dashboard.js\"></script>", "<script>\n" + js + "\n</script>");
                        java.util.List<String> errors = new java.util.ArrayList<>();
                        qbotics.web.WebScript script = new qbotics.web.WebScript(new qbotics.web.WebScript.Hooks() {
                            public void send(String n, String d) { }
                            public void log(String l) { }
                            public void error(String m) { errors.add(m); }
                            public String value(String f) { return null; }
                            public void setValue(String f, String v) { }
                        });
                        script.load(page, 384, 256, "{}");
                        script.setState("{\"heat\": 120, \"note\": \"Too hot!\"}");
                        helper.assertTrue(errors.isEmpty(), "the dashboard's script runs: " + errors);
                        helper.assertTrue(script.html().contains("120") && script.html().contains("hot") && script.html().contains("Too hot!"),
                                "the dashboard shows the state");
                    } catch (java.io.IOException e) {
                        throw new IllegalStateException(e);
                    }
                }
            }
        });
    }

    /** The helpme storage examples, run as a player would, against a real bus, chest and Web Panel. */
    private static void helpmeStorage(GameTestHelper helper) {
        ServerComputer computer = startAt(helper, new BlockPos(0, 1, 0));
        setPort(helper, new BlockPos(1, 1, 0), Kind.BUS, Direction.EAST);
        helper.setBlock(new BlockPos(2, 1, 0), Blocks.CHEST);
        var chest = helper.getBlockEntity(new BlockPos(2, 1, 0), net.minecraft.world.level.block.entity.ChestBlockEntity.class);
        chest.setItem(0, new ItemStack(Items.IRON_INGOT, 40));
        chest.setItem(1, new ItemStack(Items.COAL, 12));
        chest.setItem(2, new ItemStack(Items.IRON_INGOT, 5));
        helper.setBlock(new BlockPos(0, 2, 0), QboticsContent.WEB_PANEL_BLOCK.get().defaultBlockState()
                .setValue(qbotics.block.DisplayBlock.FACING, Direction.SOUTH));
        for (String line : List.of("git clone helpme", "cd /helpme/storage", "run count.py", "cd /helpme/displays/web_storage", "run storage.py")) {
            computer.submitLine(line);
        }
        helper.succeedWhen(() -> {
            assertScreen(helper, computer, "The bus points at minecraft:chest", "45  minecraft:iron_ingot", "In all: 57 items",
                    "No bus called out", "The storage page is up.");
            qbotics.block.DisplayBlockEntity panel = helper.getBlockEntity(new BlockPos(0, 2, 0), qbotics.block.DisplayBlockEntity.class);
            qbotics.display.WebScreen web = (qbotics.display.WebScreen) panel.content();
            String state = web.stateJson();
            helper.assertTrue(state.contains("\"name\":\"Iron Ingot\"") && state.contains("\"count\":45") && state.contains("\"total\":57")
                    && state.contains("\"can_take\":false"), "the storage page's state: " + state);
            String page = qbotics.web.WebTemplate.fill(web.html(), state);
            helper.assertTrue(page.contains("item:minecraft:coal") && page.contains("overflow: auto") && !page.contains("Take 16"),
                    "the page shows the items, with its stylesheet");
        });
    }

    private static void ironQbot(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ComputerManager manager = ComputerManager.get(level.getServer());
        BlockPos start = new BlockPos(1, 1, 1);
        helper.setBlock(start, QboticsContent.IRON_QBOT_BLOCK.get().defaultBlockState().setValue(qbotics.block.ComputerBlock.FACING, Direction.NORTH));
        qbotics.qbot.QbotBlockEntity qbot = helper.getBlockEntity(start, qbotics.qbot.QbotBlockEntity.class);
        qbot.onPlaced(UUID.randomUUID());
        UUID id = qbot.deviceId();
        UUID drive = qbot.driveId();
        helper.setBlock(new BlockPos(1, 1, 0), Blocks.STONE);
        helper.setBlock(new BlockPos(1, 1, -1), Blocks.OBSIDIAN);
        helper.setBlock(new BlockPos(0, 1, 0), Blocks.LAVA);
        helper.setBlock(new BlockPos(1, 0, 1), QboticsContent.FLUID_INTERFACE_BLOCK.get());
        helper.getBlockEntity(new BlockPos(1, 0, 1), qbotics.block.FluidInterfaceBlockEntity.class)
                .insertInto(0, net.minecraft.world.level.material.Fluids.WATER, 2000, false);
        helper.setBlock(new BlockPos(1, 0, 0), QboticsContent.ENERGY_INTERFACE_BLOCK.get());
        helper.getBlockEntity(new BlockPos(1, 0, 0), qbotics.block.EnergyInterfaceBlockEntity.class).insert(5000, false);

        String program = String.join("\n",
                "import qbot",
                "from qbot import inspect, inventory, tanks, energy, Side, QbotError",
                "print('front', inspect.front().id)",
                "print('water', tanks.take(Side.BOTTOM))",
                "print('dug', qbot.dig())",
                "qbot.forward()",
                "print('energy', energy.take(Side.BOTTOM))",
                "try:",
                "    qbot.dig()",
                "except QbotError as e:",
                "    print('refused:', e)",
                "print('facing', qbot.turn_right())",
                "print('placed', qbot.place())",
                "print('items', inventory.list())",
                "print('pumped', qbot.pump(Side.BACK))",
                "qbot.back()",
                "print('poured', qbot.pour(Side.FRONT, 'water'))",
                "print('done')",
                "");
        ServerComputer computer = manager.getOrStart(qbot);
        try {
            computer.drive().call(qbotics.engine.HostFileSystem.WRITE, "/q.py", program.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (qbotics.engine.HostFileSystem.FsException e) {
            throw new IllegalStateException("could not write the test program", e);
        }
        computer.submitLine("run /q.py");
        helper.succeedWhen(() -> {
            assertScreen(helper, computer, "front minecraft:stone\n", "water 2000\n", "dug {'minecraft:cobblestone': 1}\n", "energy 5000\n",
                    "refused: An iron Qbot can't dig minecraft:obsidian", "facing east\n", "placed minecraft:cobblestone\n", "items []\n",
                    "pumped minecraft:lava\n", "poured minecraft:water\n", "done\n");
            // It ended a block west of where it dug, still the same computer, with its drive.
            qbotics.qbot.QbotBlockEntity moved = helper.getBlockEntity(new BlockPos(0, 1, 0), qbotics.qbot.QbotBlockEntity.class);
            helper.assertTrue(moved.deviceId().equals(id) && moved.driveId().equals(drive) && manager.computer(id) == computer,
                    "the Qbot should be the same computer, still running, at its new place");
            helper.assertTrue(moved.energy() == 5000, "its cell should hold 5000 FE, not " + moved.energy());
            helper.assertTrue(moved.tankFluid(0) == net.minecraft.world.level.material.Fluids.WATER && moved.tankAmount(0) == 1000
                    && moved.tankFluid(1) == net.minecraft.world.level.material.Fluids.LAVA && moved.tankAmount(1) == 1000,
                    "water and lava should be in separate tanks: " + moved.tankFluid(0) + " " + moved.tankAmount(0) + ", "
                            + moved.tankFluid(1) + " " + moved.tankAmount(1));
            helper.assertBlockPresent(Blocks.COBBLESTONE, new BlockPos(2, 1, 0));
            helper.assertBlockPresent(Blocks.OBSIDIAN, new BlockPos(1, 1, -1));
            helper.assertTrue(!(helper.getLevel().getBlockState(helper.absolutePos(start)).getBlock() instanceof qbotics.qbot.QbotBlock),
                    "it should have left where it started");
        });
    }

    /** The settings page: what it shows, what saving changes, and what it refuses. */
    private static void computerSettings(GameTestHelper helper) {
        BlockPos computerPos = new BlockPos(0, 1, 0);
        ServerLevel level = helper.getLevel();
        helper.setBlock(computerPos, QboticsContent.COMPUTER_BLOCK.get());
        ComputerBlockEntity computer = helper.getBlockEntity(computerPos, ComputerBlockEntity.class);
        String page = qbotics.computer.ComputerSettings.read(level, computer);
        helper.assertTrue(page.contains("\"allow\": \"owner\"") && page.contains("\"password\": \"\"") && page.contains("_antenna")
                && page.contains("192.168.1."), "a new computer's settings page was:\n" + page);

        // With an antenna the page shows it too; saving changes the computer and the antenna.
        helper.setBlock(computerPos.above(), QboticsContent.ANTENNA_SHORT_BLOCK.get());
        AntennaBlockEntity antenna = helper.getBlockEntity(computerPos.above(), AntennaBlockEntity.class);
        String edited = qbotics.computer.ComputerSettings.read(level, computer)
                .replace("\"name\": \"\"", "\"name\": \"base camp\"")
                .replace("\"allow\": \"owner\"", "\"allow\": \"password\"")
                .replace("\"password\": \"\"", "\"password\": \"open sesame\"")
                .replace("\"access\": \"private\"", "\"access\": \"public\"")
                .replace("\"frequency\": \"1\"", "\"frequency\": \"mine ops\"");
        helper.assertTrue(qbotics.computer.ComputerSettings.write(level, computer, edited) == null, "the edited page should save");
        helper.assertTrue(computer.deviceName().equals("base_camp") && computer.remoteLogin() == ComputerBlockEntity.RemoteLogin.PASSWORD
                && computer.checkPassword("open sesame") && !computer.checkPassword("open sesame!")
                && antenna.access() == RadioNetwork.Access.PUBLIC && antenna.frequency().equals("mine_ops"), "saving should apply every setting");
        String saved = qbotics.computer.ComputerSettings.read(level, computer);
        helper.assertTrue(saved.contains("\"password\": \"********\"") && !saved.contains("sesame"), "the password must never be shown:\n" + saved);
        // Saving the page untouched keeps the password; emptying it while passwords are required is refused.
        helper.assertTrue(qbotics.computer.ComputerSettings.write(level, computer, saved) == null && computer.checkPassword("open sesame"),
                "saving the page as shown should keep the password");
        String problem = qbotics.computer.ComputerSettings.write(level, computer, saved.replace("\"password\": \"********\"", "\"password\": \"\""));
        helper.assertTrue(problem != null && problem.contains("set a password") && computer.hasPassword(), "refused, but said: " + problem);
        helper.assertTrue(qbotics.computer.ComputerSettings.write(level, computer, saved.replace("\"public\"", "\"everyone\"")).contains("private")
                && antenna.access() == RadioNetwork.Access.PUBLIC, "a wrong access word should be explained and change nothing");
        helper.assertTrue(qbotics.computer.ComputerSettings.write(level, computer, "{ not json").contains("valid JSON"), "broken JSON should be refused");
        helper.succeed();
    }

    /** The Network Probe reads any device's name and addresses, picks out one plate on a cable, and renames. */
    private static void networkProbe(GameTestHelper helper) {
        BlockPos computerPos = new BlockPos(0, 1, 0);
        BlockPos storePos = new BlockPos(1, 1, 0);
        BlockPos cablePos = new BlockPos(0, 1, 1);
        ServerLevel level = helper.getLevel();
        helper.setBlock(computerPos, QboticsContent.COMPUTER_BLOCK.get());
        helper.setBlock(storePos, QboticsContent.INTERFACE_BLOCK.get());
        setPort(helper, cablePos, Kind.BUS, Direction.SOUTH);
        BlockPos storeAt = helper.absolutePos(storePos);
        BlockPos cableAt = helper.absolutePos(cablePos);

        qbotics.net.ProbePayloads.Info store = qbotics.block.ProbeItem.inspect(level, storeAt, storeAt.getCenter());
        helper.assertTrue(store != null && store.kind().equals("interface") && store.side() == -1 && store.ip().startsWith("192.168.1.")
                && store.mac().startsWith("02:") && store.external().isEmpty() && store.python().equals("net.interface(\"" + store.ip() + "\")"),
                "the probe should read the interface, but gave " + store);

        // Naming it changes what a program would write.
        qbotics.net.ProbePayloads.Info named = qbotics.block.ProbeItem.rename(level, null,
                new qbotics.net.ProbePayloads.Rename(storeAt, -1, "ore store!"));
        helper.assertTrue(named != null && named.name().equals("ore_store") && named.python().equals("net.interface(\"ore_store\")")
                && helper.getBlockEntity(storePos, InterfaceBlockEntity.class).deviceName().equals("ore_store"),
                "renaming from the probe should clean and keep the name, but gave " + named);

        // On a cable the click picks out a plate; the cable itself has no address.
        net.minecraft.world.phys.Vec3 corner = new net.minecraft.world.phys.Vec3(cableAt.getX(), cableAt.getY(), cableAt.getZ());
        qbotics.net.ProbePayloads.Info bus = qbotics.block.ProbeItem.inspect(level, cableAt, corner.add(0.5, 0.5, 0.95));
        helper.assertTrue(bus != null && bus.kind().equals("bus") && bus.side() == Direction.SOUTH.get3DDataValue()
                && !bus.ip().equals(store.ip()), "the probe should read the bus on the cable's south side, but gave " + bus);
        helper.assertTrue(qbotics.block.ProbeItem.inspect(level, cableAt, corner.add(0.5, 0.5, 0.5)) == null, "the cable's core is not a device");
        qbotics.block.ProbeItem.rename(level, null, new qbotics.net.ProbePayloads.Rename(cableAt, bus.side(), "smelter"));
        helper.assertTrue(helper.getBlockEntity(cablePos, PortBlockEntity.class).port(Direction.SOUTH).deviceName().equals("smelter"),
                "a plate can be named from the probe");

        // With an antenna on the network, the address other networks use is shown too.
        helper.setBlock(computerPos.above(), QboticsContent.ANTENNA_SHORT_BLOCK.get());
        qbotics.net.ProbePayloads.Info outside = qbotics.block.ProbeItem.inspect(level, storeAt, storeAt.getCenter());
        String port = String.valueOf(1000 + Integer.parseInt(outside.ip().substring(outside.ip().lastIndexOf('.') + 1)));
        helper.assertTrue(outside.external().startsWith("100.64.") && outside.external().endsWith(":" + port),
                "the external address should end in port " + port + " but was " + outside.external());

        // Modes: Labels reads devices; in Slots mode devices open as usual so their slot numbers can be seen.
        net.minecraft.world.entity.player.Player player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        ItemStack probe = new ItemStack(QboticsContent.PROBE_ITEM.get());
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, probe);
        helper.assertTrue(qbotics.block.ProbeItem.mode(probe) == qbotics.block.ProbeItem.LABELS && qbotics.block.ProbeItem.heldBy(player)
                && !qbotics.block.ProbeItem.showsSlots(player), "a new probe is in Labels mode");
        probe.set(QboticsContent.PROBE_MODE.get(), qbotics.block.ProbeItem.SLOTS);
        helper.assertTrue(!qbotics.block.ProbeItem.heldBy(player) && qbotics.block.ProbeItem.showsSlots(player)
                && probe.getHoverName().getString().contains("Slots"), "in Slots mode the probe shows slots and says so: " + probe.getHoverName().getString());

        ByteBuf buffer = Unpooled.buffer();
        qbotics.net.ProbePayloads.Info.STREAM_CODEC.encode(buffer, outside);
        helper.assertTrue(outside.equals(qbotics.net.ProbePayloads.Info.STREAM_CODEC.decode(buffer)), "probe info changed in transit");
        helper.succeed();
    }

    // ---- antennas and messages (design doc §9.1, §9.3) --------------------------------

    /** An antenna is its network's router, has an external address, and obeys the range rule. */
    private static void antennaAddresses(GameTestHelper helper) {
        BlockPos computerPos = new BlockPos(0, 1, 0);
        BlockPos otherPos = new BlockPos(2, 1, 2);
        helper.setBlock(computerPos, QboticsContent.COMPUTER_BLOCK.get());
        helper.assertTrue(ips(networkAt(helper, computerPos)).equals(List.of("192.168.1.2")), "the computer starts at .2");
        helper.setBlock(computerPos.above(), QboticsContent.ANTENNA_SHORT_BLOCK.get());
        helper.setBlock(otherPos, QboticsContent.ANTENNA_LONG_BLOCK.get());
        AntennaBlockEntity antenna = helper.getBlockEntity(computerPos.above(), AntennaBlockEntity.class);
        AntennaBlockEntity other = helper.getBlockEntity(otherPos, AntennaBlockEntity.class);

        CableNetwork network = networkAt(helper, computerPos);
        helper.assertTrue(network.gateway() == antenna && ips(network).equals(List.of("192.168.1.1", "192.168.1.2")),
                "the antenna should be the router at .1 and the computer stay at .2, but had " + ips(network));
        helper.assertTrue(antenna.externalIp().startsWith("100.64.") && other.externalIp().startsWith("100.64.")
                && !antenna.externalIp().equals(other.externalIp()),
                "each antenna needs its own external address: " + antenna.externalIp() + " and " + other.externalIp());
        RadioNetwork radio = RadioNetwork.get(helper.getLevel().getServer());
        helper.assertTrue(radio.byExternalIp(antenna.externalIp()) == antenna, "an antenna should be found by its external address");
        helper.assertTrue(antenna.tier() == RadioNetwork.Tier.SHORT && other.tier() == RadioNetwork.Tier.LONG, "tiers come from the block");

        // Frequencies: only antennas on the same one can talk.
        helper.assertTrue(RadioNetwork.linkProblem(antenna, other) == null, "two new antennas share the default frequency");
        helper.assertTrue(antenna.tune("mine ops") && antenna.frequency().equals("mine_ops") && !antenna.tune("!!"),
                "frequency should be cleaned to mine_ops but was " + antenna.frequency());
        helper.assertTrue(RadioNetwork.linkProblem(antenna, other) != null, "different frequencies should not link");
        other.tune("mine_ops");
        helper.assertTrue(RadioNetwork.linkProblem(antenna, other) == null, "the same frequency should link again");

        // Access: private keeps other players' devices out, public lets anyone in; a null owner is "nobody".
        java.util.UUID stranger = java.util.UUID.randomUUID();
        var server = helper.getLevel().getServer();
        helper.assertTrue(other.access() == RadioNetwork.Access.PRIVATE && RadioNetwork.accessProblem(server, other, null) == null
                && RadioNetwork.accessProblem(server, other, stranger) != null, "a new antenna should be private");
        other.setAccess(RadioNetwork.Access.TEAM);
        helper.assertTrue(RadioNetwork.accessProblem(server, other, stranger) != null, "a stranger is not on the owner's team");
        other.setAccess(RadioNetwork.Access.PUBLIC);
        helper.assertTrue(RadioNetwork.accessProblem(server, other, stranger) == null, "a public antenna lets anyone in");

        // A broken antenna can't be reached any more.
        String gone = other.externalIp();
        helper.getLevel().destroyBlock(helper.absolutePos(otherPos), false);
        helper.assertTrue(radio.byExternalIp(gone) == null, "a broken antenna should leave the radio network");

        // Range: the larger of the two reaches counts; height doesn't; dimensions need Infinite at both ends.
        var overworld = net.minecraft.world.level.Level.OVERWORLD;
        var nether = net.minecraft.world.level.Level.NETHER;
        BlockPos here = BlockPos.ZERO;
        RadioNetwork.Tier s = RadioNetwork.Tier.SHORT;
        RadioNetwork.Tier l = RadioNetwork.Tier.LONG;
        RadioNetwork.Tier i = RadioNetwork.Tier.INFINITE;
        helper.assertTrue(RadioNetwork.rangeProblem(s, overworld, here, s, overworld, new BlockPos(24, 200, 0)) == null
                && RadioNetwork.rangeProblem(s, overworld, here, s, overworld, new BlockPos(25, 0, 0)) != null
                && RadioNetwork.rangeProblem(s, overworld, here, l, overworld, new BlockPos(0, 0, 100)) == null
                && RadioNetwork.rangeProblem(l, overworld, here, s, overworld, new BlockPos(80, 0, 80)) != null
                && RadioNetwork.rangeProblem(s, overworld, here, i, overworld, new BlockPos(90000, 0, 0)) == null
                && RadioNetwork.rangeProblem(l, overworld, here, i, nether, here) != null
                && RadioNetwork.rangeProblem(i, overworld, here, i, nether, new BlockPos(90000, 0, 0)) == null,
                "the range rule is wrong");

        // Placeholder recipes: a column, each tier from the one before.
        ItemStack none = ItemStack.EMPTY;
        ItemStack cable = new ItemStack(QboticsContent.CABLE_BLOCK.get());
        ItemStack shortOne = craft(helper, none, new ItemStack(Items.REDSTONE_TORCH), none, none, new ItemStack(Items.IRON_INGOT), none, none, cable, none);
        ItemStack longOne = craft(helper, none, new ItemStack(Items.DIAMOND), none, none, shortOne, none, none, cable, none);
        ItemStack infinite = craft(helper, none, new ItemStack(Items.ENDER_EYE), none, none, longOne, none, none, cable, none);
        helper.assertTrue(shortOne.is(QboticsContent.ANTENNA_SHORT_BLOCK.get().asItem()) && longOne.is(QboticsContent.ANTENNA_LONG_BLOCK.get().asItem())
                && infinite.is(QboticsContent.ANTENNA_INFINITE_BLOCK.get().asItem()),
                "antenna recipes gave " + shortOne + ", " + longOne + ", " + infinite);
        helper.succeed();
    }

    /**
     * Two computers, each with its own antenna, exchange messages by external address and
     * port; a computer can message its own network without one; mistakes are explained.
     */
    private static void radioMessages(GameTestHelper helper) {
        BlockPos aPos = new BlockPos(0, 1, 0);
        BlockPos bPos = new BlockPos(2, 1, 2);
        ServerComputer a = startAt(helper, aPos);
        ServerComputer b = startAt(helper, bPos);
        helper.setBlock(aPos.above(), QboticsContent.ANTENNA_SHORT_BLOCK.get());
        helper.setBlock(bPos.above(), QboticsContent.ANTENNA_SHORT_BLOCK.get());
        networkAt(helper, aPos);
        networkAt(helper, bPos);
        String aAddress = helper.getBlockEntity(aPos.above(), AntennaBlockEntity.class).externalIp() + ":1002";
        String bAddress = helper.getBlockEntity(bPos.above(), AntennaBlockEntity.class).externalIp() + ":1002";

        for (String line : List.of(
                "python",
                "import network",
                "m = network.receive(8)",
                "print('got', m.data['ore'], m.sender)",
                "network.send(m.sender, ['thanks', 2])")) {
            b.submitLine(line);
        }
        for (String line : List.of(
                "python",
                "import network",
                "radio = network.Radio()",
                "print(network.ifconfig()['port'], radio.tier, radio.range, radio.frequency)",
                "network.send('192.168.1.2', 'to myself')",
                "print(network.receive(1).data)",
                "print(network.receive(0))",
                "network.send('" + bAddress + "', {'ore': 128})",
                "r = network.receive(8)",
                "print('reply', r.data, r.sender)",
                "print([(d['address'], d['kind']) for d in network.scan('" + bAddress.replace(":1002", "") + "')])",
                // A new antenna is private. Both of these have no owner, which counts as the same owner.
                "print(radio.access)",
                "radio.tune('other')",
                "network.send('" + bAddress + "', 1)",
                "network.send('100.64.200.1:1002', 1)",
                "network.send('192.168.1.1', 1)",
                "print('end')")) {
            a.submitLine(line);
        }
        helper.succeedWhen(() -> {
            assertScreen(helper, a, "end\n");
            assertScreen(helper, b, "got 128 " + aAddress + "\n");
            assertScreen(helper, a, "1002 short 24 1\n", "private\n", "to myself\n", "None\n", "reply ['thanks', 2] " + bAddress + "\n",
                    "[('" + bAddress.replace(":1002", ":1001") + "', 'antenna'), ('" + bAddress + "', 'computer')]\n",
                    "that antenna is tuned to a different", "No antenna with the address 100.64.200.1 can be reached",
                    "Only computers receive messages; 192.168.1.1 is an antenna.", "end\n");
        });
    }
}
