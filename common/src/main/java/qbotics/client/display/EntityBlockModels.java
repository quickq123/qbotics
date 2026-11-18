package qbotics.client.display;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Transformation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.object.banner.BannerFlagModel;
import net.minecraft.client.model.object.banner.BannerModel;
import net.minecraft.client.model.object.bell.BellModel;
import net.minecraft.client.model.object.chest.ChestModel;
import net.minecraft.client.model.object.skull.SkullModelBase;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.blockentity.BedRenderer;
import net.minecraft.client.renderer.blockentity.BellRenderer;
import net.minecraft.client.renderer.blockentity.ChestRenderer;
import net.minecraft.client.renderer.blockentity.ConduitRenderer;
import net.minecraft.client.renderer.blockentity.DecoratedPotRenderer;
import net.minecraft.client.renderer.blockentity.HangingSignRenderer;
import net.minecraft.client.renderer.blockentity.ShulkerBoxRenderer;
import net.minecraft.client.renderer.blockentity.SkullBlockRenderer;
import net.minecraft.client.renderer.blockentity.StandingSignRenderer;
import net.minecraft.client.renderer.blockentity.state.ChestRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.sprite.SpriteId;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.AbstractBannerBlock;
import net.minecraft.world.level.block.AbstractSkullBlock;
import net.minecraft.world.level.block.BannerBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CeilingHangingSignBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ConduitBlock;
import net.minecraft.world.level.block.CopperChestBlock;
import net.minecraft.world.level.block.DecoratedPotBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.HangingSignBlock;
import net.minecraft.world.level.block.PlainSignBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.SkullBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.TrappedChestBlock;
import net.minecraft.world.level.block.WallBannerBlock;
import net.minecraft.world.level.block.WallHangingSignBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.WallSkullBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.WoodType;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;
import qbotics.Qbotics;

/**
 * The shapes of blocks the game draws with code rather than a block model (chests, signs, beds,
 * banners, heads, shulker boxes, pots, bells, conduits), for a Qbot's view: the game's own entity
 * models, posed as its renderers pose them in the world, and their textures. Text, patterns and
 * what a pot or shelf holds are left out; lids are shut. Any other block whose model draws nothing
 * (one from another mod, say) is a box of its outline in its particle texture, so it is still seen.
 * Kept per block state; safe on the web panels' worker.
 */
final class EntityBlockModels {
    private EntityBlockModels() {
    }

    /** A face, in the block's own space (0 to 1): its corners (x, y, z, u, v), texture, tint (or -1) and shade. */
    record Quad(double[][] corners, PictureColors.Image texture, int tint, float shade) {
    }

    private static final Map<Integer, List<Quad>> CACHE = new ConcurrentHashMap<>();

    /** The faces to draw for a block whose model has none of its own (empty if it isn't one of these). */
    static List<Quad> of(BlockState state, boolean modelIsEmpty) {
        int id = Block.getId(state);
        List<Quad> known = CACHE.get(id);
        if (known != null) {
            return known;
        }
        List<Quad> quads;
        try {
            quads = build(state, modelIsEmpty);
        } catch (RuntimeException e) {
            Qbotics.LOGGER.debug("Couldn't build a view shape for {}", state, e);
            quads = modelIsEmpty ? outline(state) : List.of();
        }
        CACHE.put(id, quads);
        return quads;
    }

    private static List<Quad> build(BlockState state, boolean modelIsEmpty) {
        EntityModelSet models = Minecraft.getInstance().getEntityModels();
        Block block = state.getBlock();
        Capture capture = new Capture();
        if (block instanceof ChestBlock || block instanceof EnderChestBlock) {
            ChestType type = state.hasProperty(ChestBlock.TYPE) ? state.getValue(ChestBlock.TYPE) : ChestType.SINGLE;
            ChestModel model = new ChestModel(models.bakeLayer(ChestRenderer.LAYERS.select(type)));
            model.setupAnim(0f);
            ChestRenderState.ChestMaterialType material;
            if (block instanceof CopperChestBlock copper) {
                material = switch (copper.getState()) {
                    case UNAFFECTED -> ChestRenderState.ChestMaterialType.COPPER_UNAFFECTED;
                    case EXPOSED -> ChestRenderState.ChestMaterialType.COPPER_EXPOSED;
                    case WEATHERED -> ChestRenderState.ChestMaterialType.COPPER_WEATHERED;
                    case OXIDIZED -> ChestRenderState.ChestMaterialType.COPPER_OXIDIZED;
                };
            } else if (block instanceof EnderChestBlock) {
                material = ChestRenderState.ChestMaterialType.ENDER_CHEST;
            } else if (block instanceof TrappedChestBlock) {
                material = ChestRenderState.ChestMaterialType.TRAPPED;
            } else {
                material = ChestRenderState.ChestMaterialType.REGULAR;
            }
            capture.model(model, ChestRenderer.modelTransformation(state.getValue(BlockStateProperties.HORIZONTAL_FACING)),
                    sprite(Sheets.chooseSprite(material, type)), -1);
        } else if (block instanceof SignBlock && !(block instanceof HangingSignBlock)) {
            WoodType wood = SignBlock.getWoodType(block);
            PlainSignBlock.Attachment attachment = PlainSignBlock.getAttachmentPoint(state);
            Transformation pose = block instanceof WallSignBlock
                    ? StandingSignRenderer.TRANSFORMATIONS.wallTransformation(state.getValue(WallSignBlock.FACING)).body()
                    : StandingSignRenderer.TRANSFORMATIONS.freeTransformations(state.getValue(StandingSignBlock.ROTATION)).body();
            capture.model(StandingSignRenderer.createSignModel(models, wood, attachment), pose, sprite(Sheets.getSignSprite(wood)), -1);
        } else if (block instanceof HangingSignBlock) {
            WoodType wood = SignBlock.getWoodType(block);
            HangingSignBlock.Attachment attachment = HangingSignBlock.getAttachmentPoint(state);
            Transformation pose = block instanceof WallHangingSignBlock
                    ? HangingSignRenderer.TRANSFORMATIONS.wallTransformation(state.getValue(WallHangingSignBlock.FACING)).body()
                    : HangingSignRenderer.TRANSFORMATIONS.freeTransformations(state.getValue(CeilingHangingSignBlock.ROTATION)).body();
            capture.model(HangingSignRenderer.createSignModel(models, wood, attachment), pose, sprite(Sheets.getHangingSignSprite(wood)), -1);
        } else if (block instanceof BedBlock bed) {
            boolean head = state.getValue(BedBlock.PART) == BedPart.HEAD;
            ModelPart root = models.bakeLayer(head ? ModelLayers.BED_HEAD : ModelLayers.BED_FOOT);
            capture.part(root, BedRenderer.modelTransform(state.getValue(BedBlock.FACING)), sprite(Sheets.getBedSprite(bed.getColor())), -1);
        } else if (block instanceof ShulkerBoxBlock box) {
            ModelPart root = models.bakeLayer(ModelLayers.SHULKER_BOX);
            root.getChild("lid").setPos(0f, 24f, 0f); // shut, as the renderer poses it
            DyeColor color = box.getColor();
            capture.part(root, ShulkerBoxRenderer.modelTransform(state.getValueOrElse(ShulkerBoxBlock.FACING, Direction.UP)),
                    sprite(color == null ? Sheets.DEFAULT_SHULKER_TEXTURE_LOCATION : Sheets.getShulkerBoxSprite(color)), -1);
        } else if (block instanceof AbstractBannerBlock banner) {
            boolean wall = block instanceof WallBannerBlock;
            Transformation pose = wall ? net.minecraft.client.renderer.blockentity.BannerRenderer.TRANSFORMATIONS.wallTransformation(state.getValue(WallBannerBlock.FACING))
                    : net.minecraft.client.renderer.blockentity.BannerRenderer.TRANSFORMATIONS.freeTransformations(state.getValue(BannerBlock.ROTATION));
            BannerModel pole = new BannerModel(models.bakeLayer(wall ? ModelLayers.WALL_BANNER : ModelLayers.STANDING_BANNER));
            BannerFlagModel flag = new BannerFlagModel(models.bakeLayer(wall ? ModelLayers.WALL_BANNER_FLAG : ModelLayers.STANDING_BANNER_FLAG));
            flag.setupAnim(0f);
            capture.model(pole, pose, sprite(Sheets.BANNER_BASE), -1);
            // The flag in its base colour (its patterns are left out).
            capture.model(flag, pose, sprite(Sheets.BANNER_PATTERN_BASE), banner.getColor().getTextureDiffuseColor() & 0xFFFFFF);
        } else if (block instanceof AbstractSkullBlock skull) {
            SkullModelBase model = SkullBlockRenderer.createModel(models, skull.getType());
            Identifier skin = SkullBlockRenderer.SKIN_BY_TYPE.get(skull.getType());
            if (model != null && skin != null) {
                model.setupAnim(new SkullModelBase.State());
                Transformation pose = block instanceof WallSkullBlock
                        ? SkullBlockRenderer.TRANSFORMATIONS.wallTransformation(state.getValue(WallSkullBlock.FACING))
                        : SkullBlockRenderer.TRANSFORMATIONS.freeTransformations(state.getValue(SkullBlock.ROTATION));
                capture.model(model, pose, PictureColors.image(skin), -1);
            }
        } else if (block instanceof DecoratedPotBlock) {
            Transformation pose = DecoratedPotRenderer.modelTransformation(state.getValue(DecoratedPotBlock.HORIZONTAL_FACING));
            ModelPart base = models.bakeLayer(ModelLayers.DECORATED_POT_BASE);
            ModelPart sides = models.bakeLayer(ModelLayers.DECORATED_POT_SIDES);
            PictureColors.Image baseTexture = sprite(Sheets.DECORATED_POT_BASE);
            PictureColors.Image sideTexture = sprite(Sheets.DECORATED_POT_SIDE);
            for (String name : new String[] {"neck", "top", "bottom"}) {
                capture.part(base.getChild(name), pose, baseTexture, -1);
            }
            for (String name : new String[] {"front", "back", "left", "right"}) {
                capture.part(sides.getChild(name), pose, sideTexture, -1);
            }
        } else if (block instanceof BellBlock) {
            capture.part(models.bakeLayer(ModelLayers.BELL), null, sprite(BellRenderer.BELL_TEXTURE), -1);
        } else if (block instanceof ConduitBlock) {
            capture.part(models.bakeLayer(ModelLayers.CONDUIT_SHELL), ConduitRenderer.DEFAULT_TRANSFORMATION, sprite(ConduitRenderer.SHELL_TEXTURE), -1);
        }
        if (!capture.quads.isEmpty()) {
            return List.copyOf(capture.quads);
        }
        return modelIsEmpty ? outline(state) : List.of();
    }

    private static PictureColors.@Nullable Image sprite(SpriteId id) {
        Identifier t = id.texture();
        return PictureColors.image(Identifier.fromNamespaceAndPath(t.getNamespace(), "textures/" + t.getPath() + ".png"));
    }

    /** A box of the block's outline (or a small one if it has none), in its particle texture. */
    private static List<Quad> outline(BlockState state) {
        PictureColors.Image texture = null;
        try {
            BlockStateModel model = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state);
            TextureAtlasSprite particle = model.particleMaterial().sprite();
            Identifier name = particle.contents().name();
            texture = PictureColors.image(Identifier.fromNamespaceAndPath(name.getNamespace(), "textures/" + name.getPath() + ".png"));
        } catch (RuntimeException e) {
            // no texture: its map colour instead
        }
        int tint = -1;
        if (texture == null) {
            texture = PictureColors.Image.SOLID;
            tint = state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).col & 0xFFFFFF;
        }
        List<AABB> boxes;
        try {
            boxes = state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).toAabbs();
        } catch (RuntimeException e) {
            boxes = List.of();
        }
        if (boxes.isEmpty()) {
            boxes = List.of(new AABB(0.25, 0, 0.25, 0.75, 0.5, 0.75));
        }
        List<Quad> quads = new ArrayList<>();
        for (AABB box : boxes) {
            for (Direction d : Direction.values()) {
                quads.add(new Quad(boxFace(box, d), texture, tint, shade(d.getStepX(), d.getStepY(), d.getStepZ())));
            }
        }
        return List.copyOf(quads);
    }

    /** A box's face, its texture laid on as a full block's would be. */
    private static double[][] boxFace(AABB a, Direction d) {
        double[][] p = switch (d) {
            case UP -> new double[][] {{a.minX, a.maxY, a.minZ}, {a.minX, a.maxY, a.maxZ}, {a.maxX, a.maxY, a.maxZ}, {a.maxX, a.maxY, a.minZ}};
            case DOWN -> new double[][] {{a.minX, a.minY, a.maxZ}, {a.minX, a.minY, a.minZ}, {a.maxX, a.minY, a.minZ}, {a.maxX, a.minY, a.maxZ}};
            case NORTH -> new double[][] {{a.maxX, a.maxY, a.minZ}, {a.maxX, a.minY, a.minZ}, {a.minX, a.minY, a.minZ}, {a.minX, a.maxY, a.minZ}};
            case SOUTH -> new double[][] {{a.minX, a.maxY, a.maxZ}, {a.minX, a.minY, a.maxZ}, {a.maxX, a.minY, a.maxZ}, {a.maxX, a.maxY, a.maxZ}};
            case WEST -> new double[][] {{a.minX, a.maxY, a.minZ}, {a.minX, a.minY, a.minZ}, {a.minX, a.minY, a.maxZ}, {a.minX, a.maxY, a.maxZ}};
            case EAST -> new double[][] {{a.maxX, a.maxY, a.maxZ}, {a.maxX, a.minY, a.maxZ}, {a.maxX, a.minY, a.minZ}, {a.maxX, a.maxY, a.minZ}};
        };
        double[][] corners = new double[4][];
        for (int i = 0; i < 4; i++) {
            double x = p[i][0];
            double y = p[i][1];
            double z = p[i][2];
            double u;
            double v;
            switch (d) {
                case UP, DOWN -> {
                    u = x;
                    v = z;
                }
                case NORTH -> {
                    u = 1 - x;
                    v = 1 - y;
                }
                case SOUTH -> {
                    u = x;
                    v = 1 - y;
                }
                case WEST -> {
                    u = z;
                    v = 1 - y;
                }
                default -> {
                    u = 1 - z;
                    v = 1 - y;
                }
            }
            corners[i] = new double[] {x, y, z, u, v};
        }
        return corners;
    }

    /** As the game shades a face by the way it faces (the light from above). */
    static float shade(double nx, double ny, double nz) {
        double ax = Math.abs(nx);
        double ay = Math.abs(ny);
        double az = Math.abs(nz);
        if (ay >= ax && ay >= az) {
            return ny > 0 ? 1f : 0.5f;
        }
        return az >= ax ? 0.8f : 0.6f;
    }

    /** Takes down a model's faces as the game would draw them, through a pose, with one texture. */
    private static final class Capture implements VertexConsumer {
        final List<Quad> quads = new ArrayList<>();
        private PictureColors.@Nullable Image texture;
        private int tint = -1;
        private final double[][] pending = new double[4][];
        private int count;
        private double nx;
        private double ny;
        private double nz;

        void model(Model<?> model, @Nullable Transformation pose, PictureColors.@Nullable Image texture, int tint) {
            part(model.root(), pose, texture, tint);
        }

        void part(ModelPart part, @Nullable Transformation pose, PictureColors.@Nullable Image texture, int tint) {
            if (texture == null) {
                return;
            }
            this.texture = texture;
            this.tint = tint;
            count = 0;
            PoseStack stack = new PoseStack();
            if (pose != null) {
                stack.mulPose(pose);
            }
            part.render(stack, this, 0, 0);
        }

        @Override
        public void addVertex(float x, float y, float z, int color, float u, float v, int overlayCoords, int lightCoords, float nx, float ny, float nz) {
            pending[count++] = new double[] {x, y, z, u, v};
            this.nx = nx;
            this.ny = ny;
            this.nz = nz;
            if (count == 4) {
                count = 0;
                quads.add(new Quad(new double[][] {pending[0], pending[1], pending[2], pending[3]}, texture, tint, shade(this.nx, this.ny, this.nz)));
            }
        }

        // Only the call above is used (by ModelPart); the rest take nothing down.
        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            return this;
        }

        @Override
        public VertexConsumer setColor(int color) {
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            return this;
        }
    }
}
