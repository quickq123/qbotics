package qbotics.block;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;
import qbotics.QboticsContent;
import qbotics.computer.ComputerManager;

/**
 * The placed computer's identity. The running program does not live here: it belongs to
 * the server-wide {@link ComputerManager}, keyed by device ID (design doc §6.2).
 */
public class ComputerBlockEntity extends BlockEntity {
    /** Identifies this placed machine. A new one is assigned on every placement (design doc §17.1). */
    private @Nullable UUID deviceId;
    /** The player who placed it. Stored as a UUID because names can change (design doc §5.1). */
    private @Nullable UUID owner;

    public ComputerBlockEntity(BlockPos pos, BlockState state) {
        super(QboticsContent.COMPUTER_BLOCK_ENTITY.get(), pos, state);
    }

    public void onPlaced(@Nullable UUID placedBy) {
        deviceId = UUID.randomUUID();
        owner = placedBy;
        setChanged();
    }

    public UUID deviceId() {
        if (deviceId == null) {
            // Placed by something other than a player (a command, a structure).
            deviceId = UUID.randomUUID();
            setChanged();
        }
        return deviceId;
    }

    public @Nullable UUID owner() {
        return owner;
    }

    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        // The block was broken or replaced (not merely unloaded): the computer is gone.
        if (deviceId != null && level instanceof ServerLevel serverLevel) {
            ComputerManager.get(serverLevel.getServer()).remove(deviceId);
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.storeNullable("device_id", UUIDUtil.CODEC, deviceId);
        output.storeNullable("owner", UUIDUtil.CODEC, owner);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        deviceId = input.read("device_id", UUIDUtil.CODEC).orElse(null);
        owner = input.read("owner", UUIDUtil.CODEC).orElse(null);
    }
}
