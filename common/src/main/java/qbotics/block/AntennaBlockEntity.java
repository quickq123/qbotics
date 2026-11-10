package qbotics.block;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import qbotics.QboticsContent;
import qbotics.network.Addresses;
import qbotics.network.RadioNetwork;

/**
 * An antenna: its cable network's router (design doc §9.3). It has an external address on
 * the server-wide radio network and is tuned to a frequency; only antennas on the same
 * frequency and in range can exchange messages.
 */
public class AntennaBlockEntity extends DeviceBlockEntity {
    /** Its number on the radio network; 0 until the server has given it one. */
    private int externalNumber;
    private String frequency = RadioNetwork.DEFAULT_FREQUENCY;

    public AntennaBlockEntity(BlockPos pos, BlockState state) {
        super(QboticsContent.ANTENNA_BLOCK_ENTITY.get(), pos, state);
    }

    @Override
    public Kind kind() {
        return Kind.ANTENNA;
    }

    public RadioNetwork.Tier tier() {
        return getBlockState().getBlock() instanceof AntennaBlock antenna ? antenna.tier() : RadioNetwork.Tier.SHORT;
    }

    public String externalIp() {
        return RadioNetwork.externalIp(externalNumber);
    }

    public String frequency() {
        return frequency;
    }

    /** Frequencies follow the same rules as names; an unusable one leaves the antenna as it was. */
    public boolean tune(String wanted) {
        String cleaned = Addresses.cleanName(wanted);
        if (cleaned.isEmpty()) {
            return false;
        }
        frequency = cleaned;
        setChanged();
        return true;
    }

    // An antenna is on the radio network for exactly as long as its block is loaded.

    @Override
    public void setLevel(Level level) {
        super.setLevel(level);
        if (level instanceof ServerLevel serverLevel) {
            int number = RadioNetwork.get(serverLevel.getServer()).register(this, externalNumber);
            if (number != externalNumber) {
                externalNumber = number;
                setChanged();
            }
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level instanceof ServerLevel serverLevel) {
            RadioNetwork.get(serverLevel.getServer()).unregister(this, externalNumber);
        }
    }

    @Override
    public void clearRemoved() {
        super.clearRemoved();
        if (level instanceof ServerLevel serverLevel) {
            externalNumber = RadioNetwork.get(serverLevel.getServer()).register(this, externalNumber);
        }
    }

    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        // Broken, not merely unloaded: the address goes back to the pool.
        if (level instanceof ServerLevel serverLevel) {
            RadioNetwork.get(serverLevel.getServer()).release(this, externalNumber);
            externalNumber = 0;
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putInt("external", externalNumber);
        output.putString("frequency", frequency);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        externalNumber = input.getIntOr("external", 0);
        String saved = Addresses.cleanName(input.getStringOr("frequency", ""));
        frequency = saved.isEmpty() ? RadioNetwork.DEFAULT_FREQUENCY : saved;
    }
}
