package qbotics.qbot;

import java.util.List;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A Qbot's tier (design doc §8.4): what it can dig with no tool in its hand. An Iron Qbot digs
 * as an unenchanted iron tool would; a block that needs better (obsidian, ancient debris) needs a
 * better tool in its hand.
 */
public enum QbotTier {
    IRON("iron", List.of(Items.IRON_PICKAXE, Items.IRON_AXE, Items.IRON_SHOVEL, Items.IRON_HOE, Items.IRON_SWORD));

    /** The word programs see. */
    public final String word;
    private final List<Item> tools;

    QbotTier(String word, List<Item> tools) {
        this.word = word;
        this.tools = tools;
    }

    /** What a Qbot with a Netherite Upgrade digs with when its hand is empty (silk touch is added when it digs). */
    public static final List<Item> NETHERITE_TOOLS = List.of(Items.NETHERITE_PICKAXE, Items.NETHERITE_AXE, Items.NETHERITE_SHOVEL,
            Items.NETHERITE_HOE, Items.NETHERITE_SWORD);

    /** The built-in tool it digs a block with: the right one for the block if any is, else the fastest (or none). */
    public ItemStack toolFor(BlockState state) {
        return toolFor(tools, state);
    }

    public static ItemStack toolFor(List<Item> tools, BlockState state) {
        ItemStack fastest = ItemStack.EMPTY;
        float best = 1.0f;
        for (Item item : tools) {
            ItemStack tool = new ItemStack(item);
            if (tool.isCorrectToolForDrops(state)) {
                return tool;
            }
            float speed = tool.getDestroySpeed(state);
            if (speed > best) {
                best = speed;
                fastest = tool;
            }
        }
        return fastest;
    }
}
