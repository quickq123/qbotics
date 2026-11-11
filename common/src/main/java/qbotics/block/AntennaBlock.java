package qbotics.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;
import qbotics.network.CableNetwork;
import qbotics.network.RadioNetwork;

/** An antenna: a base with a mast. One block class serves all three range tiers. */
public class AntennaBlock extends BaseEntityBlock {
    public static final MapCodec<AntennaBlock> SHORT_CODEC = simpleCodec(properties -> new AntennaBlock(RadioNetwork.Tier.SHORT, properties));
    public static final MapCodec<AntennaBlock> LONG_CODEC = simpleCodec(properties -> new AntennaBlock(RadioNetwork.Tier.LONG, properties));
    public static final MapCodec<AntennaBlock> INFINITE_CODEC = simpleCodec(properties -> new AntennaBlock(RadioNetwork.Tier.INFINITE, properties));

    private static final VoxelShape SHAPE = Shapes.or(Block.box(2, 0, 2, 14, 4, 14), Block.box(7, 4, 7, 9, 16, 9));

    private final RadioNetwork.Tier tier;

    public AntennaBlock(RadioNetwork.Tier tier, BlockBehaviour.Properties properties) {
        super(properties);
        this.tier = tier;
    }

    public RadioNetwork.Tier tier() {
        return tier;
    }

    @Override
    protected MapCodec<AntennaBlock> codec() {
        return switch (tier) {
            case SHORT -> SHORT_CODEC;
            case LONG -> LONG_CODEC;
            case INFINITE -> INFINITE_CODEC;
        };
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new AntennaBlockEntity(pos, state);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof AntennaBlockEntity antenna) {
            antenna.onPlaced(placer instanceof Player player ? player.getUUID() : null);
        }
    }

    /** Right-click: tells the player the antenna's addresses, frequency and reach. */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer serverPlayer && level instanceof ServerLevel serverLevel
                && level.getBlockEntity(pos) instanceof AntennaBlockEntity antenna) {
            CableNetwork.at(serverLevel, pos);
            String reach = tier == RadioNetwork.Tier.INFINITE ? "unlimited" : tier.radius + " blocks";
            serverPlayer.sendOverlayMessage(Component.translatable("message.qbotics.antenna",
                    CableBlock.describe(antenna), antenna.externalIp(), antenna.frequency(), reach));
        }
        return InteractionResult.SUCCESS;
    }
}
