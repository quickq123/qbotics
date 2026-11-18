package qbotics.client.display;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;
import qbotics.Qbotics;
import qbotics.camera.ConeView;

/**
 * Creatures (and items on the ground, item frames, armour stands, paintings...) as the game draws
 * them, for a Qbot's view: one of each kind (young or grown, holding or showing the item, and for
 * a player, that player with their skin) is made in the player's game, standing still and facing
 * south, and its renderer's drawing is taken down (models, items and blocks it submits, painted
 * shapes, their textures and tints) instead of drawn. Kept per kind, so each is taken once, on the
 * render thread; the view turns and places it. Name tags, text and flames are left out. Its own
 * looks (a sheep's colour, a villager's job) are a newly made one's. Empty if the kind can't be
 * drawn so: the view then shows its box.
 */
final class CreatureModels {
    private CreatureModels() {
    }

    /** A face, from the creature's position (south facing): corners (x, y, z, u, v), texture, tint, shade, and which layer (later ones lie on top). */
    record Quad(double[][] corners, PictureColors.Image texture, int tint, float shade, int layer) {
    }

    private record Key(int type, boolean baby, int item, @Nullable UUID player, int hung, @Nullable String variant) {
    }

    private static final Map<Key, List<Quad>> CACHE = new ConcurrentHashMap<>();
    private static final Pattern TEXTURE = Pattern.compile("location=([a-z0-9_.\\-]+:[a-z0-9_./\\-]+)");

    /** The faces of a creature as the server described it, or empty to show its box. */
    static List<Quad> of(ConeView.Creature c) {
        if (c.type() < 0) {
            return List.of();
        }
        Key key = new Key(c.type(), c.baby(), c.item(), c.player(), c.hung(), c.variant());
        List<Quad> known = CACHE.get(key);
        if (known != null) {
            return known;
        }
        Minecraft minecraft = Minecraft.getInstance();
        List<Quad> quads;
        if (minecraft.isSameThread()) {
            quads = capture(key);
        } else {
            try {
                quads = minecraft.submit(() -> capture(key)).get(2, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                return List.of(); // the game is busy: a box this time, and try again next view
            } catch (Exception e) {
                quads = List.of();
            }
        }
        CACHE.put(key, quads);
        return quads;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static List<Quad> capture(Key key) {
        Minecraft minecraft = Minecraft.getInstance();
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.byId(key.type());
        if (minecraft.level == null || type == null) {
            return List.of();
        }
        try {
            Entity entity;
            Direction hung = key.hung() >= 0 ? Direction.from3DDataValue(key.hung()) : null;
            if (hung != null && type == EntityType.PAINTING && key.variant() != null) {
                // Made hanging as the real one hangs (the view doesn't turn it), showing the same picture.
                var variant = minecraft.level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.PAINTING_VARIANT)
                        .get(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.PAINTING_VARIANT,
                                Identifier.parse(key.variant())));
                if (variant.isEmpty()) {
                    return List.of();
                }
                entity = new net.minecraft.world.entity.decoration.painting.Painting(minecraft.level, net.minecraft.core.BlockPos.ZERO, hung,
                        variant.get());
            } else if (hung != null && type.create(minecraft.level, EntitySpawnReason.LOAD) instanceof ItemFrame) {
                entity = new ItemFrame((EntityType<? extends ItemFrame>) type, minecraft.level, net.minecraft.core.BlockPos.ZERO, hung);
            } else if (type == EntityType.PLAYER) {
                // A stand-in for the player: their skin is found by who they are, as for any other player in sight.
                entity = new RemotePlayer(minecraft.level, new GameProfile(key.player() != null ? key.player() : UUID.randomUUID(), "qbotview"));
            } else {
                entity = type.create(minecraft.level, EntitySpawnReason.LOAD);
            }
            if (entity == null) {
                return List.of();
            }
            if (hung == null) {
                entity.setPos(0, 0, 0);
                entity.setYRot(0);
                entity.setXRot(0);
                entity.yRotO = 0;
            }
            if (entity instanceof LivingEntity living) {
                living.yBodyRot = 0;
                living.yBodyRotO = 0;
                living.yHeadRot = 0;
                living.yHeadRotO = 0;
                if (key.baby()) {
                    if (living instanceof AgeableMob ageable) {
                        ageable.setAge(AgeableMob.BABY_START_AGE);
                    } else if (living instanceof Mob mob) {
                        mob.setBaby(true);
                    }
                }
            }
            if (key.item() >= 0) {
                ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.byId(key.item()));
                if (entity instanceof ItemEntity item) {
                    item.setItem(stack);
                } else if (entity instanceof ItemFrame frame) {
                    frame.setItem(stack, false);
                }
            }
            EntityRenderer renderer = minecraft.getEntityRenderDispatcher().getRenderer(entity);
            EntityRenderState state = renderer.createRenderState(entity, 0f);
            Recorder recorder = new Recorder();
            SubmitNodeCollector collector = (SubmitNodeCollector) Proxy.newProxyInstance(SubmitNodeCollector.class.getClassLoader(),
                    new Class<?>[] {SubmitNodeCollector.class}, recorder);
            recorder.self = collector;
            PoseStack pose = new PoseStack();
            // Drawn from where the entity is, as the game does (a hung one sits off its block's corner).
            var offset = renderer.getRenderOffset(state);
            pose.translate(offset.x, offset.y, offset.z);
            renderer.submit(state, pose, collector, new CameraRenderState());
            entity.discard();
            return List.copyOf(recorder.quads);
        } catch (RuntimeException | LinkageError e) {
            Qbotics.LOGGER.debug("Couldn't take down how a {} is drawn, for a view", type, e);
            return List.of();
        }
    }

    /** A texture by the name the game draws with: a file, a downloaded one (a skin), or null. Render thread. */
    private static PictureColors.@Nullable Image texture(Identifier location) {
        if (location.getPath().endsWith(".png")) {
            PictureColors.Image file = PictureColors.image(location);
            if (file != null) {
                return file;
            }
        }
        return PictureColors.loaded(location);
    }

    private static @Nullable Identifier location(RenderType renderType) {
        Matcher m = TEXTURE.matcher(renderType.toString());
        return m.find() ? Identifier.tryParse(m.group(1)) : null;
    }

    /** Stands in for the game's drawing: takes down what is submitted, through its pose, with its textures. */
    private static final class Recorder implements InvocationHandler {
        final List<Quad> quads = new ArrayList<>();
        Object self;
        private int layer;

        @Override
        public @Nullable Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            int n = args == null ? 0 : args.length;
            if (name.equals("order")) {
                return self;
            }
            try {
                if (name.equals("submitModel") && n == 10 && args[0] instanceof Model<?> model && args[2] instanceof PoseStack pose
                        && args[3] instanceof RenderType renderType) {
                    @SuppressWarnings("unchecked")
                    Model<Object> m = (Model<Object>) model;
                    m.setupAnim(args[1]); // as the game does before drawing it
                    part(m.root(), pose, renderType, (TextureAtlasSprite) args[7], (Integer) args[6]);
                    return null;
                }
                if (name.equals("submitModelPart") && n == 11 && args[0] instanceof ModelPart part && args[1] instanceof PoseStack pose
                        && args[2] instanceof RenderType renderType) {
                    part(part, pose, renderType, (TextureAtlasSprite) args[5], (Integer) args[8]);
                    return null;
                }
                if (name.equals("submitItem") && n == 8 && args[0] instanceof PoseStack pose && args[6] instanceof List<?> list) {
                    baked(pose, (List<BakedQuad>) list, (int[]) args[5]);
                    return null;
                }
                if (name.equals("submitBlockModel") && n == 7 && args[0] instanceof PoseStack pose && args[2] instanceof List<?> parts) {
                    List<BakedQuad> all = new ArrayList<>();
                    for (Object o : parts) {
                        BlockStateModelPart part = (BlockStateModelPart) o;
                        for (Direction d : Direction.values()) {
                            all.addAll(part.getQuads(d));
                        }
                        all.addAll(part.getQuads(null));
                    }
                    baked(pose, all, (int[]) args[3]);
                    return null;
                }
                if (name.equals("submitCustomGeometry") && n == 3 && args[0] instanceof PoseStack pose && args[1] instanceof RenderType renderType
                        && args[2] instanceof SubmitNodeCollector.CustomGeometryRenderer drawing) {
                    custom(pose, renderType, drawing);
                    return null;
                }
            } catch (RuntimeException e) {
                Qbotics.LOGGER.debug("Couldn't take down a {} for a view", name, e);
                return null;
            }
            if (method.isDefault()) {
                return InvocationHandler.invokeDefault(proxy, method, args);
            }
            if (name.equals("equals")) {
                return proxy == args[0];
            }
            if (name.equals("hashCode")) {
                return System.identityHashCode(proxy);
            }
            if (name.equals("toString")) {
                return "Qbot view recorder";
            }
            Class<?> r = method.getReturnType();
            return r == boolean.class ? false : r == int.class ? 0 : r == float.class ? 0f : r == double.class ? 0d : r == long.class ? 0L : null;
        }

        private void part(ModelPart part, PoseStack pose, RenderType renderType, @Nullable TextureAtlasSprite sprite, int tint) {
            if (tint != -1 && (tint >>> 24) == 0) {
                return; // drawn invisible
            }
            PictureColors.Image texture;
            if (sprite != null) {
                texture = PictureColors.sprite(sprite);
            } else {
                Identifier location = location(renderType);
                texture = location == null ? null : texture(location);
            }
            if (texture == null) {
                return;
            }
            Vertices vertices = new Vertices(texture, tint == -1 ? -1 : tint & 0xFFFFFF, layer++, null);
            part.render(pose, vertices, 0xF000F0, 0);
            vertices.finish();
        }

        /** Item and block models: baked faces, each with its own sprite. */
        private void baked(PoseStack pose, List<BakedQuad> list, int @Nullable [] tints) {
            Matrix4f matrix = pose.last().pose();
            int index = layer++;
            for (BakedQuad quad : list) {
                TextureAtlasSprite sprite = quad.materialInfo().sprite();
                PictureColors.Image texture = PictureColors.sprite(sprite);
                if (texture == null) {
                    continue;
                }
                double u0 = sprite.getU0();
                double du = sprite.getU1() - u0;
                double v0 = sprite.getV0();
                double dv = sprite.getV1() - v0;
                double[][] corners = new double[4][];
                for (int i = 0; i < 4; i++) {
                    Vector3f p = matrix.transformPosition(new Vector3f(quad.position(i)));
                    long uv = quad.packedUV(i);
                    corners[i] = new double[] {p.x, p.y, p.z, (UVPair.unpackU(uv) - u0) / du, (UVPair.unpackV(uv) - v0) / dv};
                }
                int tint = -1;
                if (quad.materialInfo().isTinted() && tints != null && quad.materialInfo().tintIndex() < tints.length) {
                    tint = tints[quad.materialInfo().tintIndex()] & 0xFFFFFF;
                }
                Vector3f facing = matrix.transformDirection(new Vector3f(quad.direction().getStepX(), quad.direction().getStepY(),
                        quad.direction().getStepZ()));
                quads.add(new Quad(corners, texture, tint, EntityBlockModels.shade(facing.x, facing.y, facing.z), index));
            }
        }

        /** Shapes a renderer paints itself (a painting): textured from a file, or from the atlas sheet a corner's place falls in. */
        private void custom(PoseStack pose, RenderType renderType, SubmitNodeCollector.CustomGeometryRenderer drawing) {
            Identifier location = location(renderType);
            if (location == null) {
                return;
            }
            // A sheet of sprites (paintings, blocks...), found among the game's sheets by its texture's name.
            TextureAtlas[] sheet = new TextureAtlas[1];
            Minecraft.getInstance().getAtlasManager().forEach((id, a) -> {
                if (a.location().equals(location)) {
                    sheet[0] = a;
                }
            });
            TextureAtlas atlas = sheet[0];
            PictureColors.Image whole = atlas == null ? texture(location) : null;
            if (atlas == null && whole == null) {
                return;
            }
            Vertices vertices = new Vertices(whole, -1, layer++, atlas);
            drawing.render(pose.last(), vertices);
            vertices.finish();
        }

        /** Takes down vertices four at a time, whether given whole (models) or step by step (painted shapes). */
        private final class Vertices implements VertexConsumer {
            private final PictureColors.@Nullable Image texture;
            private final int tint;
            private final int index;
            private final @Nullable TextureAtlas atlas;
            private final double[][] pending = new double[4][];
            private int count;
            private double @Nullable [] current;
            private float nx;
            private float ny;
            private float nz;
            private boolean normal;

            Vertices(PictureColors.@Nullable Image texture, int tint, int index, @Nullable TextureAtlas atlas) {
                this.texture = texture;
                this.tint = tint;
                this.index = index;
                this.atlas = atlas;
            }

            @Override
            public VertexConsumer addVertex(float x, float y, float z) {
                close();
                current = new double[] {x, y, z, 0, 0};
                return this;
            }

            @Override
            public void addVertex(float x, float y, float z, int color, float u, float v, int overlayCoords, int lightCoords, float nx, float ny, float nz) {
                addVertex(x, y, z);
                setUv(u, v);
                setNormal(nx, ny, nz);
            }

            @Override
            public VertexConsumer setUv(float u, float v) {
                if (current != null) {
                    current[3] = u;
                    current[4] = v;
                }
                return this;
            }

            /** The vertex being given is complete: four make a face. */
            private void close() {
                if (current == null) {
                    return;
                }
                pending[count++] = current;
                current = null;
                if (count == 4) {
                    count = 0;
                    face(new double[][] {pending[0], pending[1], pending[2], pending[3]});
                }
            }

            void finish() {
                close();
            }

            private void face(double[][] corners) {
                PictureColors.Image t = texture;
                if (atlas != null) {
                    // Which sprite of the sheet: the one the face's middle falls in, its corners made the sprite's own.
                    double u = (corners[0][3] + corners[2][3]) / 2;
                    double v = (corners[0][4] + corners[2][4]) / 2;
                    TextureAtlasSprite found = null;
                    for (TextureAtlasSprite sprite : atlas.getTextures().values()) {
                        if (u >= sprite.getU0() && u <= sprite.getU1() && v >= sprite.getV0() && v <= sprite.getV1()) {
                            found = sprite;
                            break;
                        }
                    }
                    if (found == null) {
                        return;
                    }
                    t = PictureColors.sprite(found);
                    for (double[] c : corners) {
                        c[3] = (c[3] - found.getU0()) / (found.getU1() - found.getU0());
                        c[4] = (c[4] - found.getV0()) / (found.getV1() - found.getV0());
                    }
                }
                if (t != null) {
                    quads.add(new Quad(corners, t, tint, normal ? EntityBlockModels.shade(nx, ny, nz) : shadeOf(corners), index));
                }
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
            public VertexConsumer setUv1(int u, int v) {
                return this;
            }

            @Override
            public VertexConsumer setUv2(int u, int v) {
                return this;
            }

            @Override
            public VertexConsumer setNormal(float x, float y, float z) {
                nx = x;
                ny = y;
                nz = z;
                normal = true;
                return this;
            }

            @Override
            public VertexConsumer setLineWidth(float width) {
                return this;
            }
        }
    }

    /** The shade of a face by the way it faces, from its corners (which side is out isn't known: a flat one is lit as a side). */
    private static float shadeOf(double[][] c) {
        double ax = c[1][0] - c[0][0];
        double ay = c[1][1] - c[0][1];
        double az = c[1][2] - c[0][2];
        double bx = c[2][0] - c[0][0];
        double by = c[2][1] - c[0][1];
        double bz = c[2][2] - c[0][2];
        double ny = Math.abs(az * bx - ax * bz);
        return EntityBlockModels.shade(ay * bz - az * by, 0.5 * ny, ax * by - ay * bx);
    }
}
