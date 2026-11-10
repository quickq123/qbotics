package qbotics.test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import qbotics.QboticsContent;
import qbotics.block.CableBlock;
import qbotics.block.ComputerBlockEntity;
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
        ItemStack c = new ItemStack(QboticsContent.CABLE_BLOCK.get());
        ItemStack result = craft(helper,
                c, c, c,
                c, new ItemStack(Items.REDSTONE), c,
                c, new ItemStack(Blocks.GLASS), c);
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
        helper.assertTrue(state.getValue(CableBlock.EAST) && state.getValue(CableBlock.UP)
                && !state.getValue(CableBlock.NORTH) && !state.getValue(CableBlock.WEST)
                && !state.getValue(CableBlock.SOUTH) && !state.getValue(CableBlock.DOWN),
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
        helper.runAfterDelay(40, computer::interrupt);
        helper.runAfterDelay(45, () -> computer.submitLine("print('after', len(name))"));
        // A scratchpad run has no terminal: input() there is an error, not a hang.
        helper.runAfterDelay(50, () -> computer.submitScratch(UUID.randomUUID(), 1, "input('never asked')"));
        helper.runAfterDelay(55, () -> computer.submitLine("print('still here')"));
        helper.succeedWhen(() -> assertScreen(helper, computer,
                "Name? Ada\n>>> print('hi', name)\nhi Ada\n", "again?\n", "KeyboardInterrupt", "after 3\n", "still here\n>>>"));
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
}
