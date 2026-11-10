package qbotics;

import java.util.function.Supplier;
import net.minecraft.world.level.block.entity.BlockEntityType;
import qbotics.block.ComputerBlock;
import qbotics.block.ComputerBlockEntity;

/**
 * Registered game objects. Each loader registers them its own way and fills these in
 * before any of them is used.
 */
public final class QboticsContent {
    public static Supplier<ComputerBlock> COMPUTER_BLOCK;
    public static Supplier<BlockEntityType<ComputerBlockEntity>> COMPUTER_BLOCK_ENTITY;

    private QboticsContent() {
    }
}
