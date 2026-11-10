package qbotics;

import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import qbotics.block.AntennaBlock;
import qbotics.block.AntennaBlockEntity;
import qbotics.block.CableBlock;
import qbotics.block.ComputerBlock;
import qbotics.block.ComputerBlockEntity;
import qbotics.block.InputBlock;
import qbotics.block.InputBlockEntity;
import qbotics.block.PortBlock;
import qbotics.block.PortBlockEntity;

/**
 * Registered game objects. Each loader registers them its own way and fills these in
 * before any of them is used.
 */
public final class QboticsContent {
    public static Supplier<ComputerBlock> COMPUTER_BLOCK;
    public static Supplier<BlockEntityType<ComputerBlockEntity>> COMPUTER_BLOCK_ENTITY;
    public static Supplier<CableBlock> CABLE_BLOCK;
    public static Supplier<InputBlock> INPUT_BLOCK;
    public static Supplier<PortBlock> OUTPUT_BLOCK;
    public static Supplier<PortBlock> BUS_BLOCK;
    public static Supplier<BlockEntityType<InputBlockEntity>> INPUT_BLOCK_ENTITY;
    /** Shared by the bus and the output. */
    public static Supplier<BlockEntityType<PortBlockEntity>> PORT_BLOCK_ENTITY;
    public static Supplier<AntennaBlock> ANTENNA_SHORT_BLOCK;
    public static Supplier<AntennaBlock> ANTENNA_LONG_BLOCK;
    public static Supplier<AntennaBlock> ANTENNA_INFINITE_BLOCK;
    /** Shared by the three antenna tiers. */
    public static Supplier<BlockEntityType<AntennaBlockEntity>> ANTENNA_BLOCK_ENTITY;
    /** On a computer item: which drive (folder of files) it carries. */
    public static Supplier<DataComponentType<UUID>> DRIVE_ID;

    private QboticsContent() {
    }
}
