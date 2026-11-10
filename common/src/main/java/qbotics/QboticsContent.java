package qbotics;

import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.core.component.DataComponentType;
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
    /** On a computer item: which drive (folder of files) it carries. */
    public static Supplier<DataComponentType<UUID>> DRIVE_ID;

    private QboticsContent() {
    }
}
