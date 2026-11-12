package qbotics.uplink;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import qbotics.QboticsContent;
import qbotics.block.DeviceBlockEntity;

/** The Uplink (design doc §9.4): a device on a cable network that lets its computers reach the real internet. */
public class UplinkBlockEntity extends DeviceBlockEntity {
    public UplinkBlockEntity(BlockPos pos, BlockState state) {
        super(QboticsContent.UPLINK_BLOCK_ENTITY.get(), pos, state);
    }

    @Override
    public Kind kind() {
        return Kind.UPLINK;
    }
}
