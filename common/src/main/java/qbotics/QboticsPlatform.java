package qbotics;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;
import qbotics.network.ItemPort;

/** The few things common code needs from the mod loader. Each loader provides one. */
public interface QboticsPlatform {
    void sendToPlayer(ServerPlayer player, CustomPacketPayload payload);

    /** Client only. */
    void sendToServer(CustomPacketPayload payload);

    /**
     * The items in the block at a position, as reached through one of its sides, using the
     * loader's standard item API so that other mods' containers work. A null side means the whole
     * container, for reading. Returns null if the block holds no items, or none that this
     * side gives access to.
     */
    @Nullable ItemPort itemPort(ServerLevel level, BlockPos pos, @Nullable Direction side);
}
