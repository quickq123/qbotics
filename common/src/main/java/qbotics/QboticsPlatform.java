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

    /**
     * Right-clicks a block as a stand-in for a player (the owner of a Bus), the way that player
     * would, and returns the screen the block opened with the stand-in that holds it. Null if
     * the block opened no screen, or a protection mod refused the stand-in.
     *
     * @param face the face of the block that is clicked
     * @param bus  identifies the Bus, so that each Bus has a stand-in of its own
     */
    qbotics.network.ScreenSessions.@Nullable Opened openScreenAs(ServerLevel level, BlockPos pos, Direction face, java.util.UUID owner, java.util.UUID bus);

    /** The name players know a mod by ("Applied Energistics 2"), given its ID; the ID itself if it isn't known. */
    String modName(String modId);

    /** The same for the fluid in a block: its tanks, as reached through one of its sides. */
    qbotics.network.@Nullable FluidPort fluidPort(ServerLevel level, BlockPos pos, @Nullable Direction side);

    /** The same for the energy in a block. */
    qbotics.network.@Nullable EnergyPort energyPort(ServerLevel level, BlockPos pos, @Nullable Direction side);

    /**
     * A player used the item in their hand on a block that holds fluid: fills the block from a
     * full bucket, or fills an empty bucket from the block. Returns true if anything moved.
     */
    boolean useFluidContainer(net.minecraft.world.entity.player.Player player, net.minecraft.world.InteractionHand hand,
            net.minecraft.world.level.Level level, BlockPos pos, Direction side);
}
