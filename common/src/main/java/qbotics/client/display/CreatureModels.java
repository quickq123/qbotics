package qbotics.client.display;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.jspecify.annotations.Nullable;
import qbotics.Qbotics;

/**
 * Creatures as the game draws them, for a Qbot's view: a creature of each kind (young or grown) is
 * made in the player's game, standing still and facing south, and its renderer's drawing is taken
 * down (the models it submits, their textures and tints) instead of drawn. Kept per kind, so each
 * is taken once, on the render thread; the view turns and places it. What renderers draw other
 * than models (held items, blocks, name tags, flames) is left out. Its own looks (a sheep's
 * colour, a villager's job) are a newly made one's. Empty if the kind can't be drawn so: the view
 * then shows its box.
 */
final class CreatureModels {
    private CreatureModels() {
    }

    /** A face, from the creature's feet (south facing): corners (x, y, z, u, v), texture, tint, shade, and which layer (later ones lie on top). */
    record Quad(double[][] corners, PictureColors.Image texture, int tint, float shade, int layer) {
    }

    private static final Map<Long, List<Quad>> CACHE = new ConcurrentHashMap<>();
    private static final Pattern TEXTURE = Pattern.compile("location=([a-z0-9_.\\-]+:[a-z0-9_./\\-]+\\.png)");

    /** The faces of a creature of an entity type (by its ID), or empty to show its box. */
    static List<Quad> of(int type, boolean baby) {
        if (type < 0) {
            return List.of();
        }
        long key = (long) type << 1 | (baby ? 1 : 0);
        List<Quad> known = CACHE.get(key);
        if (known != null) {
            return known;
        }
        Minecraft minecraft = Minecraft.getInstance();
        List<Quad> quads;
        if (minecraft.isSameThread()) {
            quads = capture(type, baby);
        } else {
            try {
                quads = minecraft.submit(() -> capture(type, baby)).get(2, TimeUnit.SECONDS);
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
    private static List<Quad> capture(int typeId, boolean baby) {
        Minecraft minecraft = Minecraft.getInstance();
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.byId(typeId);
        if (minecraft.level == null || type == null) {
            return List.of();
        }
        try {
            Entity entity = type.create(minecraft.level, EntitySpawnReason.LOAD);
            if (entity == null) {
                return List.of();
            }
            entity.setPos(0, 0, 0);
            entity.setYRot(0);
            entity.setXRot(0);
            entity.yRotO = 0;
            if (entity instanceof LivingEntity living) {
                living.yBodyRot = 0;
                living.yBodyRotO = 0;
                living.yHeadRot = 0;
                living.yHeadRotO = 0;
                if (baby) {
                    if (living instanceof AgeableMob ageable) {
                        ageable.setAge(AgeableMob.BABY_START_AGE);
                    } else if (living instanceof Mob mob) {
                        mob.setBaby(true);
                    }
                }
            }
            EntityRenderer renderer = minecraft.getEntityRenderDispatcher().getRenderer(entity);
            EntityRenderState state = renderer.createRenderState(entity, 0f);
            Recorder recorder = new Recorder();
            SubmitNodeCollector collector = (SubmitNodeCollector) Proxy.newProxyInstance(SubmitNodeCollector.class.getClassLoader(),
                    new Class<?>[] {SubmitNodeCollector.class}, recorder);
            recorder.self = collector;
            renderer.submit(state, new PoseStack(), collector, new CameraRenderState());
            entity.discard();
            return List.copyOf(recorder.quads);
        } catch (RuntimeException | LinkageError e) {
            Qbotics.LOGGER.debug("Couldn't take down how a {} is drawn, for a view", type, e);
            return List.of();
        }
    }

    /** Stands in for the game's drawing: takes down each model submitted, through its pose, with its texture. */
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
            if (name.equals("submitModel") && n == 10 && args[0] instanceof Model<?> model && args[2] instanceof PoseStack pose
                    && args[3] instanceof RenderType renderType) {
                @SuppressWarnings("unchecked")
                Model<Object> m = (Model<Object>) model;
                m.setupAnim(args[1]); // as the game does before drawing it
                take(m.root(), pose, renderType, (TextureAtlasSprite) args[7], (Integer) args[6]);
                return null;
            }
            if (name.equals("submitModelPart") && n == 11 && args[0] instanceof ModelPart part && args[1] instanceof PoseStack pose
                    && args[2] instanceof RenderType renderType) {
                take(part, pose, renderType, (TextureAtlasSprite) args[5], (Integer) args[8]);
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

        private void take(ModelPart part, PoseStack pose, RenderType renderType, @Nullable TextureAtlasSprite sprite, int tint) {
            PictureColors.Image texture = null;
            if (sprite != null) {
                Identifier s = sprite.contents().name();
                texture = PictureColors.image(Identifier.fromNamespaceAndPath(s.getNamespace(), "textures/" + s.getPath() + ".png"));
            } else {
                Matcher m = TEXTURE.matcher(renderType.toString());
                if (m.find()) {
                    texture = PictureColors.image(Identifier.parse(m.group(1)));
                }
            }
            if (texture == null) {
                return;
            }
            PictureColors.Image t = texture;
            int tintRgb = tint == -1 ? -1 : tint & 0xFFFFFF;
            if ((tint >>> 24) == 0 && tint != -1) {
                return; // drawn invisible
            }
            int index = layer++;
            part.render(pose, new VertexConsumer() {
                private final double[][] pending = new double[4][];
                private int count;

                @Override
                public void addVertex(float x, float y, float z, int color, float u, float v, int overlayCoords, int lightCoords, float nx, float ny,
                        float nz) {
                    pending[count++] = new double[] {x, y, z, u, v};
                    if (count == 4) {
                        count = 0;
                        quads.add(new Quad(new double[][] {pending[0], pending[1], pending[2], pending[3]}, t, tintRgb,
                                EntityBlockModels.shade(nx, ny, nz), index));
                    }
                }

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
            }, 0xF000F0, 0);
        }
    }
}
