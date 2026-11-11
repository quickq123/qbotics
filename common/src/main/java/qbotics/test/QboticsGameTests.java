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
    public static final Map<String, Consumer<GameTestHelper>> ALL = Map.ofEntries(
            Map.entry("cable_recipes", QboticsGameTests::cableRecipes),
            Map.entry("cable_connects", QboticsGameTests::cableConnects),
            Map.entry("computer_repl", QboticsGameTests::computerRepl),
            Map.entry("computer_interrupt", QboticsGameTests::computerInterrupt),
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
        helper.succeedWhen(() -> assertScreen(helper, computer, "program stopped by the computer", "still alive\n>>>"));
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
        // The parts first: four screens from lines of glowstone dust, dye and glass pane; a motherboard from copper, glass and green dye.
        ItemStack g = new ItemStack(Items.GLOWSTONE_DUST);
        ItemStack p = new ItemStack(Blocks.GLASS_PANE);
        ItemStack screens = craft(helper,
                g, new ItemStack(Items.RED_DYE), p,
                g, new ItemStack(Items.GREEN_DYE), p,
                g, new ItemStack(Items.BLUE_DYE), p);
        ItemStack copper = new ItemStack(Items.COPPER_INGOT);
        ItemStack glass = new ItemStack(Blocks.GLASS);
        ItemStack motherboard = craft(helper,
                copper, copper, copper,
                glass, new ItemStack(Items.GREEN_DYE), glass,
                copper, copper, copper);
        helper.assertTrue(screens.getCount() == 4 && screens.getItem().toString().equals("qbotics:screen"), "the screen recipe gave " + screens);
        helper.assertTrue(motherboard.getCount() == 1 && motherboard.getItem().toString().equals("qbotics:motherboard"),
                "the motherboard recipe gave " + motherboard);

        ItemStack c = new ItemStack(QboticsContent.CABLE_BLOCK.get());
        ItemStack result = craft(helper,
                c, c, c,
                c, motherboard, c,
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
                "frobnicate")) {
            computer.submitLine(line);
        }
        helper.succeedWhen(() -> assertScreen(helper, computer,
                "/ $ run hi.py\nhi from file 42\n", "/lib $ ls\nhi.py", "no such file or folder",
                "Unknown command: frobnicate", "\n/lib $"));
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
        return CableBlock.side(cable, side) == CableBlock.Side.CABLE;
    }

    private static BlockState port(Kind kind, Direction pointing) {
        return QboticsContent.CABLE_BLOCK.get().withPlate(kind, pointing);
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
        helper.setBlock(new BlockPos(2, 1, 0), port(Kind.BUS, Direction.SOUTH));
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
        helper.setBlock(new BlockPos(2, 1, 0), port(Kind.BUS, Direction.SOUTH));
        helper.setBlock(barrelPos, Blocks.BARREL);
        helper.setBlock(new BlockPos(1, 1, 1), port(Kind.OUTPUT, Direction.UP));

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
                "print('out', move(inbox, net.output(), 'raw_iron', 100), inbox.count('raw_iron'), inbox.count('iron_ingot'))",
                "print(len(net.devices()), net.me.kind, f.target, f.ip.startswith('192.168.1.'))",
                "net.bus('nope')",
                "move(inbox, f, 'minecraft:not_a_thing')",
                "move(net.output(), inbox)",
                "import network",
                "print('host', network.hostname('base'), network.ifconfig()['ip'] == net.me.ip)")) {
            computer.submitLine(line);
        }
        helper.succeedWhen(() -> {
            assertScreen(helper, computer, "before 3 None\n", "moved 5 2 3\n", "out 11 0 3\n", "4 computer minecraft:furnace True\n",
                    "NetworkError: No bus on this network has the name or address 'nope'.",
                    "There is no item called 'minecraft:not_a_thing'", "An output can't be a source", "host base True\n");
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

        // A player holding an Input clicks the chest's north face: the plate goes on the cable in front of it.
        net.minecraft.world.entity.player.Player player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        net.minecraft.world.InteractionHand hand = net.minecraft.world.InteractionHand.MAIN_HAND;
        ItemStack inputs = new ItemStack(QboticsContent.plateItem(Kind.INPUT, qbotics.network.Medium.ITEM), 2);
        inputs.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("ore in"));
        player.setItemInHand(hand, inputs);
        java.util.function.BiFunction<BlockPos, Direction, net.minecraft.world.InteractionResult> click = (pos, face) -> {
            BlockPos at = helper.absolutePos(pos);
            return player.getItemInHand(hand).useOn(new net.minecraft.world.item.context.UseOnContext(player, hand,
                    new net.minecraft.world.phys.BlockHitResult(at.getCenter(), face, at, false)));
        };
        click.apply(chestPos, Direction.NORTH);
        BlockState withPlate = helper.getBlockState(cablePos);
        helper.assertTrue(CableBlock.plateKind(withPlate, Direction.SOUTH) == Kind.INPUT && arm(withPlate, Direction.WEST)
                && !arm(withPlate, Direction.SOUTH) && player.getItemInHand(hand).getCount() == 1,
                "the cable should carry an input pointing south at the chest, and one item be used: " + withPlate);
        PortBlockEntity host = helper.getBlockEntity(cablePos, PortBlockEntity.class);
        CablePort plate = host.port(Direction.SOUTH);
        helper.assertTrue(plate.kind() == Kind.INPUT && plate.targetPos().equals(helper.absolutePos(chestPos)) && plate.deviceName().equals("ore_in"),
                "the plate should be an input named ore_in pointing at the chest, but is " + plate.kind() + " " + plate.deviceName());
        click.apply(chestPos, Direction.NORTH);
        helper.assertTrue(player.getItemInHand(hand).getCount() == 1, "a side carries only one plate");

        // A second plate goes on another side of the same cable: here, pointing up at a barrel.
        helper.setBlock(cablePos.above(), Blocks.BARREL);
        click.apply(cablePos.above(), Direction.DOWN);
        BlockState withTwo = helper.getBlockState(cablePos);
        CablePort second = host.port(Direction.UP);
        helper.assertTrue(CableBlock.plateKind(withTwo, Direction.UP) == Kind.INPUT && CableBlock.plateKind(withTwo, Direction.SOUTH) == Kind.INPUT
                && second != null && host.ports().size() == 2 && !second.mac().equals(plate.mac())
                && second.targetPos().equals(helper.absolutePos(cablePos.above())) && player.getItemInHand(hand).isEmpty(),
                "the cable should carry two inputs, each its own device: " + withTwo);

        // With no cable in front of the clicked face, one is taken from the player's inventory.
        player.setItemInHand(hand, new ItemStack(QboticsContent.plateItem(Kind.BUS, qbotics.network.Medium.ITEM)));
        click.apply(chestPos, Direction.UP);
        helper.assertTrue(helper.getBlockState(chestPos.above()).isAir() && player.getItemInHand(hand).getCount() == 1, "no cable, no plate");
        player.getInventory().add(new ItemStack(QboticsContent.CABLE_BLOCK.get(), 3));
        click.apply(chestPos, Direction.UP);
        helper.assertTrue(CableBlock.plateKind(helper.getBlockState(chestPos.above()), Direction.DOWN) == Kind.BUS && player.getItemInHand(hand).isEmpty()
                && player.getInventory().countItem(QboticsContent.CABLE_BLOCK.get().asItem()) == 2,
                "the bus should have been placed with one of the player's cables");

        // The plates are devices on the network: the computer, the interface and the two inputs.
        BlockPos computerAt = helper.absolutePos(computerPos);
        CableNetwork network = networkAt(helper, computerPos);
        helper.assertTrue(network.devices().size() == 4 && network.devices().contains(plate) && network.devices().contains(second)
                && ips(network).stream().distinct().count() == 4, "four devices with four addresses, but had " + ips(network));
        java.util.function.Function<String, String> call = request ->
                qbotics.network.NetworkCalls.handle(level.getServer(), level.dimension(), computerAt, request);
        String mac = plate.mac();
        helper.assertTrue(call.apply("[\"contents\",\"" + mac + "\",null]").contains("\"count\":12"), "an input reads its target");
        String storeMac = store.mac();
        helper.assertTrue(call.apply("[\"move\",[{\"mac\":\"" + mac + "\"}],[{\"mac\":\"" + storeMac + "\"}],\"minecraft:coal\",5,null]")
                .equals("{\"r\":{\"moved\":5}}")
                && store.getItem(0).getCount() == 5 && chest.getItem(5).getCount() == 7, "an input should move 5 coal to the interface");
        helper.assertTrue(call.apply("[\"move\",[{\"mac\":\"" + storeMac + "\"}],[{\"mac\":\"" + mac + "\"}],\"minecraft:coal\",1,null]")
                .contains("An input can't be a destination"),
                "an input should refuse to put items in");
        helper.assertTrue(call.apply("[\"find\",\"" + second.mac() + "\",null]").contains("minecraft:barrel"), "the second input points at the barrel");

        // Breaking the cable drops every plate; taking plates off one at a time ends with a plain cable.
        List<ItemStack> drops = net.minecraft.world.level.block.Block.getDrops(withTwo, level, helper.absolutePos(cablePos), host);
        helper.assertTrue(drops.size() == 3 && drops.stream().anyMatch(stack -> stack.getItem() instanceof qbotics.block.PlateItem
                && stack.getHoverName().getString().equals("ore_in")), "the drops should be the cable and both plates: " + drops);
        ItemStack taken = CableBlock.detach(level, helper.absolutePos(cablePos), Direction.SOUTH);
        helper.assertTrue(taken.getItem() instanceof qbotics.block.PlateItem && plate.isRemoved() && !second.isRemoved()
                && CableBlock.plateKind(helper.getBlockState(cablePos), Direction.UP) == Kind.INPUT
                && networkAt(helper, computerPos).devices().size() == 3, "taking one plate off should leave the other working");
        // Breaking while aiming at a plate takes off only that plate. Here a player stands west of a cable and looks east at it.
        BlockPos alonePos = new BlockPos(2, 2, 0); // inside the test's walls, with clear air to its west
        helper.setBlock(alonePos, QboticsContent.CABLE_BLOCK.get().withPlate(Kind.OUTPUT, Direction.WEST));
        BlockPos aloneAt = helper.absolutePos(alonePos);
        player.snapTo(aloneAt.getX() - 1.5, aloneAt.getY() + 0.5 - player.getEyeHeight(), aloneAt.getZ() + 0.5, -90.0f, 0.0f);
        player.setYHeadRot(-90.0f);
        helper.assertTrue(CableBlock.plateAimedAt(helper.getBlockState(alonePos), aloneAt, player) == Direction.WEST,
                "the player should be aiming at the west plate");
        helper.assertTrue(CableBlock.breakPlateInstead(helper.getBlockState(alonePos), level, aloneAt, player)
                && helper.getBlockState(alonePos).getBlock() instanceof CableBlock && !CableBlock.hasPlates(helper.getBlockState(alonePos)),
                "breaking at the plate should leave the cable without it");
        helper.assertTrue(!CableBlock.breakPlateInstead(helper.getBlockState(alonePos), level, aloneAt, player),
                "with no plate aimed at, the cable itself breaks as usual");

        CableBlock.detach(level, helper.absolutePos(cablePos), Direction.UP);
        helper.assertTrue(!CableBlock.hasPlates(helper.getBlockState(cablePos)) && level.getBlockEntity(helper.absolutePos(cablePos)) == null
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

        // Recipes: a bus is an input and an output; a bucket makes the fluid version, a redstone block the energy version.
        ItemStack none = ItemStack.EMPTY;
        ItemStack input = new ItemStack(QboticsContent.plateItem(Kind.INPUT, qbotics.network.Medium.ITEM));
        ItemStack output = new ItemStack(QboticsContent.plateItem(Kind.OUTPUT, qbotics.network.Medium.ITEM));
        ItemStack bus = craft(helper, input, output, none, none, none, none, none, none, none);
        ItemStack fluidInput = craft(helper, input, new ItemStack(Items.BUCKET), none, none, none, none, none, none, none);
        ItemStack energyStore = craft(helper, new ItemStack(QboticsContent.INTERFACE_BLOCK.get()), new ItemStack(Blocks.REDSTONE_BLOCK),
                none, none, none, none, none, none, none);
        ItemStack energyBusItem = craft(helper, bus, new ItemStack(Blocks.REDSTONE_BLOCK), none, none, none, none, none, none, none);
        helper.assertTrue(bus.is(QboticsContent.plateItem(Kind.BUS, qbotics.network.Medium.ITEM)) && fluidInput.is(QboticsContent.plateItem(Kind.INPUT, fluid))
                && energyStore.is(QboticsContent.ENERGY_INTERFACE_BLOCK.get().asItem()) && energyBusItem.is(QboticsContent.plateItem(Kind.BUS, energy)),
                "recipes gave " + bus + ", " + fluidInput + ", " + energyStore + ", " + energyBusItem);

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
     * Aiming by slot and by face instead of by name: what a program does when it doesn't know
     * (or care) which item is there, as with a furnace's product.
     */
    private static void slotsAndFaces(GameTestHelper helper) {
        BlockPos chestPos = new BlockPos(2, 1, 1);
        ServerComputer computer = startAt(helper, new BlockPos(0, 1, 0));
        helper.setBlock(new BlockPos(1, 1, 0), QboticsContent.INTERFACE_BLOCK.get());
        helper.setBlock(new BlockPos(1, 1, 1), port(Kind.BUS, Direction.EAST));
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
        helper.setBlock(new BlockPos(1, 1, 1), port(Kind.BUS, Direction.EAST));
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
                    "NetworkError: There is no item called 'irn_ingot'. Did you mean", "left 5 5\n", "8 8\n",
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
        helper.assertTrue(names.equals(List.of("battery", "big_tank", "chest", "crafter", "furnace", "inbox", "machine", "main", "main_antenna", "outbox",
                "store", "tanks", "xp")),"the main network should hold exactly the stage's devices, but had " + names);

        ServerComputer computer = ComputerManager.get(level.getServer()).getOrStart(main);
        for (String demo : List.of("1_items", "2_filters", "3_fluid", "4_energy", "5_radio", "6_devices", "7_sources", "8_crafting")) {
            computer.submitLine("run demos/" + demo + ".py");
        }
        computer.submitLine("pwd");
        helper.succeedWhen(() -> {
            Terminal.State state = computer.terminal().full();
            String text = String.join("\n", state.scrolled()) + "\n" + String.join("\n", state.lines());
            helper.assertTrue(text.contains("$ pwd"), "the demos are still running; so far:\n" + text);
            helper.assertTrue(!text.contains("Traceback") && !text.contains("Error"), "a demo failed:\n" + text);
            for (String expected : List.of("149 items; 32 are coal", "chest to furnace: 8", "moved to the store: 24", "5000 mB, 3000 of it water",
                    "battery now holds 250000 of 2000000", "Sent: True", "{'echo': {'hello': 'from main'}}", "experience_interface",
                    "into the furnace: 4", "all but gold: 20", "As expected: Only 6 of the 50 needed", "Exactly 6 gold: 6",
                    "A log into the grid: 1", "Planks taken out: 4", "runs for 8 sticks: 2", "Made: {'minecraft:stick': 8}", "holds 8 sticks")) {
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
        helper.setBlock(new BlockPos(1, 1, 1), port(Kind.BUS, Direction.EAST));
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
        helper.setBlock(new BlockPos(1, 1, 1), port(Kind.BUS, Direction.EAST));
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

        String program = String.join("\n",
                "from logistics import network, Slot",
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
                    "final 1 1 1 3 1 1\n");
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
        helper.setBlock(cablePos, port(Kind.BUS, Direction.SOUTH));
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
