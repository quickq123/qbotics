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
import qbotics.QboticsContent;
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
    public static final Map<String, Consumer<GameTestHelper>> ALL = Map.of(
            "computer_repl", QboticsGameTests::computerRepl,
            "computer_interrupt", QboticsGameTests::computerInterrupt,
            "computer_recipe", QboticsGameTests::computerRecipe,
            "computer_break", QboticsGameTests::computerBreak,
            "payload_codecs", QboticsGameTests::payloadCodecs);

    private QboticsGameTests() {
    }

    private static ServerComputer placeAndStart(GameTestHelper helper) {
        helper.setBlock(POS, QboticsContent.COMPUTER_BLOCK.get());
        ComputerBlockEntity block = helper.getBlockEntity(POS, ComputerBlockEntity.class);
        return ComputerManager.get(helper.getLevel().getServer()).getOrStart(block.deviceId());
    }

    private static String screen(ServerComputer computer) {
        return String.join("\n", computer.terminal().snapshot().lines());
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
        for (String line : List.of("x = 6", "x * 7", "for i in range(3):", "    print('row', i * 100)", "", "1 / 0", "print('end')")) {
            computer.submitLine(line);
        }
        helper.succeedWhen(() -> assertScreen(helper, computer,
                ">>> x * 7\n42\n", "...     print('row', i * 100)\n...\nrow 0\nrow 100\nrow 200\n",
                "ZeroDivisionError: divide by zero", "\nend\n>>>"));
    }

    /** Ctrl+C stops an endless loop and the prompt comes back. */
    private static void computerInterrupt(GameTestHelper helper) {
        ServerComputer computer = placeAndStart(helper);
        for (String line : List.of("while True:", "    pass", "")) {
            computer.submitLine(line);
        }
        helper.runAfterDelay(10, computer::interrupt);
        helper.runAfterDelay(12, () -> computer.submitLine("print('still', 'alive')"));
        helper.succeedWhen(() -> assertScreen(helper, computer, "program stopped by the computer", "still alive\n>>>"));
    }

    /** Stone in the shape of leggings, redstone in the middle, glass at the bottom middle. */
    private static void computerRecipe(GameTestHelper helper) {
        ItemStack s = new ItemStack(Blocks.STONE);
        List<ItemStack> grid = List.of(
                s, s, s,
                s, new ItemStack(Items.REDSTONE), s,
                s, new ItemStack(Blocks.GLASS), s);
        ServerLevel level = helper.getLevel();
        CraftingInput input = CraftingInput.of(3, 3, grid);
        ItemStack result = level.getServer().getRecipeManager()
                .getRecipeFor(RecipeType.CRAFTING, input, level)
                .map(recipe -> recipe.value().assemble(input))
                .orElse(ItemStack.EMPTY);
        helper.assertTrue(result.is(QboticsContent.COMPUTER_BLOCK.get().asItem()) && result.getCount() == 1,
                "recipe should give one computer but gave " + result);
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

    /** Both packets survive being written to and read from a buffer. */
    private static void payloadCodecs(GameTestHelper helper) {
        Terminal terminal = new Terminal();
        terminal.write(">>> print('hi')\nhi\n>>> ");
        TerminalStatePayload state = TerminalStatePayload.of(UUID.randomUUID(), true, terminal.snapshot());
        ByteBuf buffer = Unpooled.buffer();
        TerminalStatePayload.STREAM_CODEC.encode(buffer, state);
        TerminalStatePayload stateBack = TerminalStatePayload.STREAM_CODEC.decode(buffer);
        helper.assertTrue(state.deviceId().equals(stateBack.deviceId()) && stateBack.open()
                && new ArrayList<>(state.lines()).equals(new ArrayList<>(stateBack.lines()))
                && stateBack.cursorX() == 4 && stateBack.cursorY() == 2, "terminal state changed in transit: " + stateBack);

        TerminalInputPayload input = new TerminalInputPayload(UUID.randomUUID(), TerminalInputPayload.LINE, "print('héllo')");
        buffer.clear();
        TerminalInputPayload.STREAM_CODEC.encode(buffer, input);
        helper.assertTrue(input.equals(TerminalInputPayload.STREAM_CODEC.decode(buffer)), "terminal input changed in transit");
        helper.succeed();
    }
}
