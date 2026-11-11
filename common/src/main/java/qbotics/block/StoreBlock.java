package qbotics.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;
import qbotics.Qbotics;
import qbotics.network.CableNetwork;
import qbotics.network.FluidPort;
import qbotics.network.Medium;

/**
 * The Fluid, Energy and Experience Interfaces: the network's stores for those, as the
 * Interface is for items (design doc §11.0). Right-click to open a store's screen; a bucket
 * used on a Fluid Interface fills or empties it directly.
 */
public class StoreBlock extends BaseEntityBlock {
    public static final MapCodec<StoreBlock> FLUID_CODEC = simpleCodec(StoreBlock::fluid);
    public static final MapCodec<StoreBlock> ENERGY_CODEC = simpleCodec(StoreBlock::energy);
    public static final MapCodec<StoreBlock> EXPERIENCE_CODEC = simpleCodec(StoreBlock::experience);

    private final Medium medium;

    private StoreBlock(Medium medium, BlockBehaviour.Properties properties) {
        super(properties);
        this.medium = medium;
    }

    public static StoreBlock fluid(BlockBehaviour.Properties properties) {
        return new StoreBlock(Medium.FLUID, properties);
    }

    public static StoreBlock energy(BlockBehaviour.Properties properties) {
        return new StoreBlock(Medium.ENERGY, properties);
    }

    public static StoreBlock experience(BlockBehaviour.Properties properties) {
        return new StoreBlock(Medium.EXPERIENCE, properties);
    }

    @Override
    protected MapCodec<StoreBlock> codec() {
        return medium == Medium.FLUID ? FLUID_CODEC : medium == Medium.ENERGY ? ENERGY_CODEC : EXPERIENCE_CODEC;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return switch (medium) {
            case FLUID -> new FluidInterfaceBlockEntity(pos, state);
            case EXPERIENCE -> new ExperienceInterfaceBlockEntity(pos, state);
            default -> new EnergyInterfaceBlockEntity(pos, state);
        };
    }

    /** The Energy Interface works its battery slots every tick. */
    @Override
    public <T extends BlockEntity> net.minecraft.world.level.block.entity.@Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state,
            net.minecraft.world.level.block.entity.BlockEntityType<T> type) {
        if (level.isClientSide() || medium != Medium.ENERGY) {
            return null;
        }
        return (tickLevel, tickPos, tickState, entity) -> {
            if (entity instanceof EnergyInterfaceBlockEntity store) {
                store.serverTick();
            }
        };
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof DeviceBlockEntity device) {
            device.onPlaced(placer instanceof Player player ? player.getUUID() : null);
        }
    }

    /** A bucket (or any fluid container another mod adds) fills or empties a Fluid Interface. */
    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
            InteractionHand hand, BlockHitResult hit) {
        if (medium == Medium.FLUID && !ProbeItem.heldBy(player) && level instanceof ServerLevel
                && Qbotics.platform().useFluidContainer(player, hand, level, pos, hit.getDirection())) {
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.TRY_WITH_EMPTY_HAND;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (ProbeItem.heldBy(player)) {
            return InteractionResult.PASS; // the probe reads this device instead
        }
        if (player instanceof ServerPlayer serverPlayer && level instanceof ServerLevel serverLevel) {
            CableNetwork.at(serverLevel, pos); // makes sure it has an address to show
            if (level.getBlockEntity(pos) instanceof ExperienceInterfaceBlockEntity store) {
                // Experience is stored and taken by hand, on a screen of its own.
                Qbotics.platform().sendToPlayer(serverPlayer, store.info(serverPlayer));
            } else if (level.getBlockEntity(pos) instanceof net.minecraft.world.MenuProvider screen) {
                // The fluid and energy stores' screens: tanks with filters and bucket slots, or a gauge with battery slots.
                serverPlayer.openMenu(screen);
            } else {
                serverPlayer.sendOverlayMessage(Component.literal(describe(level.getBlockEntity(pos))));
            }
        }
        return InteractionResult.SUCCESS;
    }

    /** "192.168.1.4  |  2000 mB water  |  empty  |  empty  |  empty" */
    static String describe(@Nullable BlockEntity entity) {
        StringBuilder text = new StringBuilder();
        if (entity instanceof DeviceBlockEntity device) {
            text.append(device.deviceName().isEmpty() ? device.ip() : device.deviceName() + "  " + device.ip());
        }
        if (entity instanceof FluidInterfaceBlockEntity store) {
            for (FluidPort.Tank tank : store.tanks()) {
                text.append("  |  ").append(tank.amount() == 0 ? "empty"
                        : tank.amount() + " mB " + BuiltInRegistries.FLUID.getKey(tank.fluid()).getPath());
            }
        } else if (entity instanceof EnergyInterfaceBlockEntity store) {
            text.append("  |  ").append(String.format("%,d / %,d FE", store.stored(), store.capacity()));
        }
        return text.toString();
    }
}
