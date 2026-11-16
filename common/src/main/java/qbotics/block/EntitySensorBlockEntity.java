package qbotics.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import qbotics.QboticsContent;

/**
 * An Entity Sensor (docs/reference.md §6.12): sees the creatures, players and dropped things
 * in the box around it (see {@link AreaBlockEntity}), reads them, and acts on them as its
 * owner: using an item on them, attacking, trading with villagers, picking things up.
 */
public class EntitySensorBlockEntity extends AreaBlockEntity {
    /** When its stand-in last swung (game time), for the attack cooldown. Not saved. */
    private long lastSwing = Long.MIN_VALUE / 2;

    public EntitySensorBlockEntity(BlockPos pos, BlockState state) {
        super(QboticsContent.ENTITY_SENSOR_BLOCK_ENTITY.get(), pos, state);
    }

    @Override
    public Kind kind() {
        return Kind.ENTITY_SENSOR;
    }

    public long lastSwing() {
        return lastSwing;
    }

    public void swung(long gameTime) {
        lastSwing = gameTime;
    }
}
