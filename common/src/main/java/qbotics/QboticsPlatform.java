package qbotics;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

/** The few things common code needs from the mod loader. Each loader provides one. */
public interface QboticsPlatform {
    void sendToPlayer(ServerPlayer player, CustomPacketPayload payload);

    /** Client only. */
    void sendToServer(CustomPacketPayload payload);
}
