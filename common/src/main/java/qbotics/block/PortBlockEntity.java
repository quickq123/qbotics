package qbotics.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import qbotics.QboticsContent;
import qbotics.network.AccessFace;

/** A bus or an output: it holds nothing itself, and acts on the container it points at. */
public class PortBlockEntity extends DeviceBlockEntity {
    /** The face used when a program doesn't name one. */
    private AccessFace accessFace = AccessFace.NEAR;

    public PortBlockEntity(BlockPos pos, BlockState state) {
        super(QboticsContent.PORT_BLOCK_ENTITY.get(), pos, state);
    }

    @Override
    public Kind kind() {
        return getBlockState().getBlock() instanceof PortBlock port ? port.kind() : Kind.BUS;
    }

    /** The direction from this block to its target. */
    public Direction pointing() {
        BlockState state = getBlockState();
        return state.hasProperty(PortBlock.FACING) ? state.getValue(PortBlock.FACING) : Direction.NORTH;
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
