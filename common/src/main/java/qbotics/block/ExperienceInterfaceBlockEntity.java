package qbotics.block;

import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import qbotics.Qbotics;
import qbotics.QboticsContent;
import qbotics.net.XpPayloads;
import qbotics.network.Medium;
import qbotics.network.RadioNetwork;

/**
 * The Experience Interface: a store of experience on the network (design doc §11.0.4). It
 * holds real experience only, counted in points so that what is put in is exactly what comes
 * out, and it has no upper limit worth the name. Players store and take levels by hand; a
 * lock says who may. It never accepts liquid experience: that would let a mod's fluid, at
 * whatever rate that mod chose, be turned into experience a player can take.
 */
public class ExperienceInterfaceBlockEntity extends DeviceBlockEntity {
    /** As much as fits with room to spare for arithmetic. */
    public static final long MAX_POINTS = Long.MAX_VALUE / 4;
    private static final double MAX_USE_DISTANCE = 8.0;

    private long points;
    /** Who may store and take. The owner always may, and only the owner changes this. */
    private RadioNetwork.Access lock = RadioNetwork.Access.PRIVATE;

    public ExperienceInterfaceBlockEntity(BlockPos pos, BlockState state) {
        super(QboticsContent.EXPERIENCE_INTERFACE_BLOCK_ENTITY.get(), pos, state);
    }

    @Override
    public Kind kind() {
        return Kind.INTERFACE;
    }

    @Override
    public Medium medium() {
        return Medium.EXPERIENCE;
    }

    public long points() {
        return points;
    }

    public RadioNetwork.Access lock() {
        return lock;
    }

    public void setLock(RadioNetwork.Access wanted) {
        lock = wanted;
        setChanged();
    }

    /** Adds up to this many points; returns how many went in. */
    public long add(long amount) {
        long added = Math.clamp(amount, 0, MAX_POINTS - points);
        if (added > 0) {
            points += added;
            setChanged();
        }
        return added;
    }

    /** Takes out up to this many points; returns how many came out. */
    public long take(long amount) {
        long taken = Math.clamp(amount, 0, points);
        if (taken > 0) {
            points -= taken;
            setChanged();
        }
        return taken;
    }

    // ---- players, by hand --------------------------------------------------------------------

    /** True if this player may store and take here. A block nobody owns is open to all. */
    public boolean mayUse(MinecraftServer server, UUID player) {
        return owner() == null || RadioNetwork.allowed(server, lock, owner(), player);
    }

    public boolean mayChangeLock(UUID player) {
        return owner() == null || Objects.equals(owner(), player);
    }

    /**
     * Moves a number of levels from the player into the block. The levels are the player's
     * top ones, at what those cost; asking for as many as they have (or more) stores
     * everything, including progress towards the next level.
     */
    public long store(ServerPlayer player, int levels) {
        long has = Experience.pointsOf(player);
        long wanted = levels >= player.experienceLevel ? has
                : Experience.pointsAtLevel(player.experienceLevel) - Experience.pointsAtLevel(player.experienceLevel - Math.max(0, levels));
        long stored = add(Math.min(wanted, has));
        Experience.setPoints(player, has - stored);
        return stored;
    }

    /**
     * Gives the player enough to climb a number of levels from where they are, or as much of
     * that as the block holds.
     */
    public long give(ServerPlayer player, int levels) {
        long has = Experience.pointsOf(player);
        long target = Math.min((long) player.experienceLevel + Math.max(0, levels), Experience.MAX_LEVEL);
        long given = take(Math.max(0, Experience.pointsAtLevel(target) - Experience.pointsAtLevel(player.experienceLevel)));
        Experience.setPoints(player, has + given);
        return given;
    }

    public XpPayloads.Info info(ServerPlayer player) {
        return new XpPayloads.Info(getBlockPos(), points, lock.ordinal(), mayUse(player.level().getServer(), player.getUUID()),
                mayChangeLock(player.getUUID()));
    }

    /** A player pressed something on the block's screen. */
    public static void onAction(ServerPlayer player, XpPayloads.Action action) {
        if (!player.level().isLoaded(action.pos()) || player.distanceToSqr(Vec3.atCenterOf(action.pos())) > MAX_USE_DISTANCE * MAX_USE_DISTANCE
                || !(player.level().getBlockEntity(action.pos()) instanceof ExperienceInterfaceBlockEntity store)) {
            return;
        }
        boolean mayUse = store.mayUse(player.level().getServer(), player.getUUID());
        switch (action.what()) {
            case XpPayloads.Action.STORE -> {
                if (mayUse) {
                    store.store(player, action.amount());
                }
            }
            case XpPayloads.Action.TAKE -> {
                if (mayUse) {
                    store.give(player, action.amount());
                }
            }
            case XpPayloads.Action.LOCK -> {
                RadioNetwork.Access[] all = RadioNetwork.Access.values();
                if (store.mayChangeLock(player.getUUID()) && action.amount() >= 0 && action.amount() < all.length) {
                    store.setLock(all[action.amount()]);
                }
            }
            default -> {
            }
        }
        Qbotics.platform().sendToPlayer(player, store.info(player));
    }

    // ---- saving; the experience goes with the block when it is picked up -------------------------

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putLong("points", points);
        output.putString("lock", lock.word);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        points = Math.clamp(input.getLongOr("points", 0), 0, MAX_POINTS);
        RadioNetwork.Access saved = RadioNetwork.Access.parse(input.getStringOr("lock", ""));
        lock = saved == null ? RadioNetwork.Access.PRIVATE : saved;
    }

    @Override
    protected void applyImplicitComponents(DataComponentGetter components) {
        super.applyImplicitComponents(components);
        Long carried = components.get(QboticsContent.XP_POINTS.get());
        points = carried == null ? 0 : Math.clamp(carried, 0, MAX_POINTS);
    }

    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        if (points > 0) {
            components.set(QboticsContent.XP_POINTS.get(), points);
        }
    }

    @Override
    public void removeComponentsFromTag(ValueOutput output) {
        super.removeComponentsFromTag(output);
        output.discard("points");
    }

    /** For tests: the block as if this player had placed it. */
    public void setOwnerForTest(@Nullable UUID owner) {
        onPlaced(owner);
    }
}
