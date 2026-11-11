package qbotics.qbot;

import com.mojang.serialization.MapCodec;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;

/**
 * Never placed: its states carry the models of a Qbot's parts (its body, an arm), which the
 * Qbot's renderer draws, moving each as the Qbot steps, turns and swings.
 */
public class QbotPartBlock extends Block {
    public static final MapCodec<QbotPartBlock> CODEC = simpleCodec(QbotPartBlock::new);

    public enum Part implements StringRepresentable {
        IRON_BODY("iron_body"), IRON_ARM("iron_arm");

        private final String word;

        Part(String word) {
            this.word = word;
        }

        @Override
        public String getSerializedName() {
            return word;
        }
    }

    public static final EnumProperty<Part> PART = EnumProperty.create("part", Part.class);

    public QbotPartBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(PART, Part.IRON_BODY));
    }

    @Override
    protected MapCodec<QbotPartBlock> codec() {
        return CODEC;
    }

    public BlockState part(Part part) {
        return defaultBlockState().setValue(PART, part);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(PART);
    }
}
