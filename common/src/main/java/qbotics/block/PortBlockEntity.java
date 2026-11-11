package qbotics.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import qbotics.QboticsContent;
import qbotics.network.AccessFace;

/**
 * An input, output or bus: a plate on a cable (see {@link CableBlock}). It holds nothing
 * itself, and acts on the container it points at.
 */
public class PortBlockEntity extends DeviceBlockEntity {
    /** The face used when a program doesn't name one. */
    private AccessFace accessFace = AccessFace.NEAR;

    public PortBlockEntity(BlockPos pos, BlockState state) {
        super(QboticsContent.PORT_BLOCK_ENTITY.get(), pos, state);
    }

    @Override
    public Kind kind() {
        Kind kind = getBlockState().getBlock() instanceof CableBlock ? getBlockState().getValue(CableBlock.PLATE).kind : null;
        return kind == null ? Kind.BUS : kind;
    }

    /** The direction from this block to its target. */
    public Direction pointing() {
        Direction side = CableBlock.plateSide(getBlockState());
        return side == null ? Direction.NORTH : side;
    }

    public BlockPos targetPos() {
        return getBlockPos().relative(pointing());
    }

    public AccessFace accessFace() {
        return accessFace;
    }

    public void setAccessFace(AccessFace face) {
        accessFace = face;
        setChanged();
    }

    @Override
    public boolean connectsOn(Direction side) {
        return side != pointing();
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putString("face", accessFace.word());
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        AccessFace saved = AccessFace.parse(input.getStringOr("face", ""));
        accessFace = saved == null ? AccessFace.NEAR : saved;
    }
}
