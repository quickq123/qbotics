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
    /** What a block offers other mods' pipes and cables has changed (a tunnel opened or closed there). */
    default void capabilitiesChanged(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos) {
    }

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

    /**
     * The stand-in player for a plate's owner (design doc §11.0.3): one per plate, standing at
     * the plate's cable and facing the block it points at, with nothing in its hands.
     *
     * @param plate identifies the plate, so that each has a stand-in of its own
     */
    net.minecraft.server.level.ServerPlayer standIn(ServerLevel level, java.util.UUID owner, java.util.UUID plate, BlockPos at, Direction facing);

    /**
     * Readies a stand-in's swing as if this many ticks had passed since its last: a stand-in is
     * never ticked, so its attack cooldown would otherwise never recover.
     */
    void chargeAttack(net.minecraft.server.level.ServerPlayer standIn, int ticks);

    /**
     * An entity's items, as hoppers and pipes reach them: a minecart's or boat's chest, a horse's
     * or llama's packs; for other creatures (and armor stands) their hands and armour. Null for
     * players (the Player Interface is for those) and for entities with none.
     */
    qbotics.network.@org.jspecify.annotations.Nullable ItemPort entityItems(net.minecraft.world.entity.Entity entity);

    /** True if protection (a land claim, spawn protection) lets this player break the block at a position. */
    boolean mayBreak(ServerLevel level, net.minecraft.server.level.ServerPlayer player, BlockPos pos);

    /**
     * Places something by running {@code place}, then asks protection whether this player may
     * have placed it; if not, puts back what was there and returns false.
     *
     * @param face the face it was placed against
     */
    boolean placeChecked(ServerLevel level, net.minecraft.server.level.ServerPlayer player, BlockPos pos, Direction face, Runnable place);

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

    /** True if this "player" is a machine's stand-in (a fake player), not a person. */
    boolean isFakePlayer(net.minecraft.world.entity.player.Player player);

    /** The fluid an item holds (a bucket of water: water), or null if it holds none or isn't a fluid container. */
    net.minecraft.world.level.material.@Nullable Fluid fluidIn(net.minecraft.world.item.ItemStack stack);

    /**
     * The fluid container in one slot of a container (a bucket, another mod's tank) empties
     * into one tank of a Fluid Interface; if it gives nothing, it fills from the tank instead.
     * The slot ends up holding what the container became. Returns true if anything moved.
     */
    boolean exchangeFluid(net.minecraft.world.Container container, int slot, qbotics.block.FluidInterfaceBlockEntity store, int tank);

    /**
     * Charges the energy-holding item in one slot of a container from an Energy Interface
     * ({@code charge} true), or drains it into the store. Returns how much moved.
     */
    int exchangeEnergy(net.minecraft.world.Container container, int slot, qbotics.block.EnergyInterfaceBlockEntity store, boolean charge, int max);

    /**
     * Keeps a chunk loaded (and ticking), or stops keeping it, on behalf of a block: an
     * antenna with a Chunk Loader Upgrade. What is kept is saved with the world.
     */
    void forceChunk(ServerLevel level, BlockPos owner, int chunkX, int chunkZ, boolean add);
}
