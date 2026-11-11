package qbotics.neoforge;

import com.mojang.math.Quadrant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.block.dispatch.ModelState;
import net.minecraft.client.renderer.block.dispatch.Variant;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.DelegateBlockStateModel;
import net.neoforged.neoforge.client.model.standalone.SimpleUnbakedStandaloneModel;
import net.neoforged.neoforge.client.model.standalone.StandaloneModelKey;
import qbotics.Qbotics;
import qbotics.block.PortBlockEntity;
import qbotics.network.NetworkDevice.Kind;

/**
 * The cable's model, built in code. The block state only says which arms a cable has; its
 * plates are kept by its {@link PortBlockEntity}. So the cable's ordinary model (core and
 * arms, from its block state file) is wrapped, and the wrapper adds a part for each plate the
 * block entity lists, read at the moment the cable is drawn.
 *
 * <p>Each plate's look is an ordinary model file, {@code block/plate_<kind>_<side>}, drawn
 * pointing north and turned here to face its side. A new kind of plate needs only its model
 * files; nothing about the cable's states changes.
 */
final class CableModels {
    /** Each plate model, by kind and by the side it is on. */
    private static final Map<Kind, Map<Direction, StandaloneModelKey<BlockStateModelPart>>> KEYS = new EnumMap<>(Kind.class);

    private CableModels() {
    }

    /** How a plate drawn pointing north is turned to point each way (as the block state file used to). */
    private static ModelState turn(Direction side) {
        Quadrant x = Quadrant.R0;
        Quadrant y = Quadrant.R0;
        switch (side) {
            case EAST -> y = Quadrant.R90;
            case SOUTH -> y = Quadrant.R180;
            case WEST -> y = Quadrant.R270;
            case UP -> x = Quadrant.R270;
            case DOWN -> x = Quadrant.R90;
            default -> {
            }
        }
        return new Variant.SimpleModelState(x, y, Quadrant.R0, false).asModelState();
    }

    /** Asks for every plate model to be loaded and baked alongside the game's own. */
    static void register(ModelEvent.RegisterStandalone event) {
        for (Kind kind : Kind.values()) {
            if (!kind.isPlate()) {
                continue;
            }
            Map<Direction, StandaloneModelKey<BlockStateModelPart>> sides = new EnumMap<>(Direction.class);
            for (Direction side : Direction.values()) {
                String name = "block/plate_" + kind.word + "_" + side.getSerializedName();
                StandaloneModelKey<BlockStateModelPart> key = new StandaloneModelKey<>(() -> Qbotics.MOD_ID + ":" + name);
                event.register(key, SimpleUnbakedStandaloneModel.simpleModelWrapper(Qbotics.id(name), turn(side)));
                sides.put(side, key);
            }
            KEYS.put(kind, sides);
        }
    }

    /** Wraps the model of every cable state, once all models are baked. */
    static void wrap(ModelEvent.ModifyBakingResult event) {
        // By kind's number, then side's number, for a quick look-up while drawing.
        BlockStateModelPart[][] plates = new BlockStateModelPart[Kind.values().length][6];
        KEYS.forEach((kind, sides) -> sides.forEach((side, key) ->
                plates[kind.ordinal()][side.get3DDataValue()] = event.getBakingResult().standaloneModels().get(key)));
        Map<BlockState, BlockStateModel> models = event.getBakingResult().blockStateModels();
        for (BlockState state : QboticsNeoForge.cableBlock().getStateDefinition().getPossibleStates()) {
            BlockStateModel base = models.get(state);
            if (base != null) {
                models.put(state, new WithPlates(base, plates));
            }
        }
    }

    /** A cable's core and arms, plus the plates its block entity lists. */
    private static final class WithPlates extends DelegateBlockStateModel {
        private final BlockStateModelPart[][] plates;

        WithPlates(BlockStateModel base, BlockStateModelPart[][] plates) {
            super(base);
            this.plates = plates;
        }

        @Override
        public void collectParts(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random, List<BlockStateModelPart> parts) {
            delegate.collectParts(level, pos, state, random, parts);
            if (level.getBlockEntity(pos) instanceof PortBlockEntity host) {
                Kind[] look = host.look();
                for (int side = 0; side < look.length; side++) {
                    Kind kind = look[side];
                    BlockStateModelPart plate = kind == null ? null : plates[kind.ordinal()][side];
                    if (plate != null) {
                        parts.add(plate);
                    }
                }
            }
        }
    }
}
