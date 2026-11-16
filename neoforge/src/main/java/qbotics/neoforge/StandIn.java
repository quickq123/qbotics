package qbotics.neoforge;

import com.mojang.authlib.GameProfile;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import org.jspecify.annotations.Nullable;
import qbotics.network.ScreenSessions;

/**
 * The stand-in for a plate's owner (design doc §11.0.3): an invisible player on the server
 * that carries the owner's ID and name. It is NeoForge's own kind of stand-in player, so
 * other mods that know about those recognise it, and a claim mod set to judge stand-ins by
 * whose ID they carry treats it as its owner.
 *
 * <p>Its one addition: when a block it right-clicks tries to open a screen, the screen is
 * kept here for the Bus to work, where an ordinary stand-in would drop it.
 */
final class StandIn extends FakePlayer {
    private static final Map<ServerLevel, Map<UUID, StandIn>> BY_OWNER = new WeakHashMap<>();

    private @Nullable MenuProvider offered;

    /** Its swing, readied as if this many ticks had passed since the last (see QboticsPlatform.chargeAttack). */
    void charge(int ticks) {
        attackStrengthTicker = ticks;
        itemSwapTicker = ticks;
    }

    private StandIn(ServerLevel level, GameProfile profile) {
        super(level, profile);
    }

    /** One stand-in per Bus, carrying that Bus's owner's ID and name; made when first needed. */
    private static StandIn of(ServerLevel level, UUID owner, UUID bus) {
        return BY_OWNER.computeIfAbsent(level, l -> new HashMap<>()).computeIfAbsent(bus, key -> {
            String known = qbotics.network.RadioNetwork.playerName(level.getServer(), owner);
            String name = known == null ? "[Qbotics]" : known;
            return new StandIn(level, new GameProfile(owner, name));
        });
    }

    /**
     * The stand-in for a plate (a Placer or a Breaker), standing at the plate's cable and
     * facing the block it points at, with empty hands.
     */
    static StandIn at(ServerLevel level, UUID owner, UUID plate, BlockPos cable, Direction facing) {
        StandIn player = of(level, owner, plate);
        if (!player.getGameProfile().id().equals(owner)) {
            BY_OWNER.get(level).remove(plate);
            player = of(level, owner, plate);
        }
        player.containerMenu = player.inventoryMenu;
        Vec3 centre = Vec3.atCenterOf(cable);
        player.setPos(centre.x, centre.y - player.getEyeHeight(), centre.z);
        // Looking straight at the target, so that blocks placed by it face as a player's would.
        float yaw = facing.getAxis().isHorizontal() ? facing.toYRot() : player.getYRot();
        float pitch = facing == Direction.UP ? -90.0f : facing == Direction.DOWN ? 90.0f : 0.0f;
        player.setYRot(yaw);
        player.setYHeadRot(yaw);
        player.setXRot(pitch);
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        return player;
    }

    /** A stand-in never waits between swings (it doesn't tick): a Qbot's attack lands at full strength. */
    @Override
    public float getAttackStrengthScale(float partialTick) {
        return 1.0f;
    }

    @Override
    public OptionalInt openMenu(@Nullable MenuProvider provider, @Nullable Consumer<RegistryFriendlyByteBuf> extraData) {
        offered = provider;
        return OptionalInt.empty();
    }

    /**
     * Right-clicks a block as the owner would, through the game's ordinary path (so that
     * protection mods are asked), and returns the screen the block opened.
     *
     * @param face the face of the block that is clicked: the one towards the Bus
     * @return the stand-in and the screen, or null if the block opened none
     */
    static ScreenSessions.@Nullable Opened open(ServerLevel level, BlockPos pos, Direction face, UUID owner, UUID bus) {
        StandIn player = of(level, owner, bus);
        if (!player.getGameProfile().id().equals(owner)) {
            // The Bus has changed hands since its stand-in was made.
            BY_OWNER.get(level).remove(bus);
            player = of(level, owner, bus);
        }
        player.containerMenu = player.inventoryMenu;
        Vec3 beside = Vec3.atCenterOf(pos.relative(face));
        player.setPos(beside.x, beside.y - player.getEyeHeight(), beside.z);
        player.offered = null;
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos).relative(face, 0.5), face, pos, false);
        player.gameMode.useItemOn(player, level, ItemStack.EMPTY, InteractionHand.MAIN_HAND, hit);
        MenuProvider provider = player.offered;
        player.offered = null;
        if (provider == null) {
            return null;
        }
        AbstractContainerMenu menu = provider.createMenu(1, player.getInventory(), player);
        if (menu == null) {
            return null;
        }
        player.containerMenu = menu;
        return new ScreenSessions.Opened(player, menu);
    }
}
