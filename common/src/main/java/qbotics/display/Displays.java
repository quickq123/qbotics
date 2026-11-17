package qbotics.display;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;
import qbotics.Qbotics;
import qbotics.block.DisplayBlock;
import qbotics.block.DisplayBlockEntity;
import qbotics.computer.ComputerManager;
import qbotics.net.DisplayPayloads;
import qbotics.network.CableNetwork;

/**
 * Text Monitors and Control Panels on the server (design doc §10): how blocks join into
 * screens, how programs draw on them, and how what they show reaches players nearby.
 *
 * <p><b>Drawing is fast.</b> A program first asks for a display on its network (a world call,
 * answered on the server thread); it gets a handle, and from then on its drawing calls are
 * carried out at once on its own thread ({@link #drawFast}), without waiting for a tick. The
 * server looks again once a second that each handle's display is still on its computer's
 * network, and drops the handle if not.
 */
public final class Displays {
    /** Players this close to a screen's top-left block are sent what it shows. */
    public static final int SEND_RANGE = 64;
    /** How close a player must be to use the close-up view. */
    public static final double CLOSE_UP_RANGE = 12;
    private static final int MAX_SCREEN_BLOCKS = 512;
    private static final int MAX_HANDLES_PER_COMPUTER = 64;

    private Displays() {
    }

    // ---- screens of several blocks --------------------------------------------------------

    /** A display block was placed (or changed): its screen, and any it joins, are laid out again. */
    public static void reform(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof DisplayBlock) {
            reformFrom(level, List.of(pos), state);
        }
    }

    /** A display block was removed: what is left of its screen is laid out again. */
    public static void removed(ServerLevel level, BlockPos pos, BlockState was) {
        if (!(was.getBlock() instanceof DisplayBlock)) {
            return;
        }
        Direction right = DisplayBlock.right(was);
        Direction down = DisplayBlock.down(was);
        reformFrom(level, List.of(pos.relative(right), pos.relative(right.getOpposite()), pos.relative(down), pos.relative(down.getOpposite())), was);
    }

    /** True if the block there is a display of the same kind, lying the same way. */
    private static boolean sameScreen(Level level, BlockPos pos, BlockState like) {
        BlockState state = level.getBlockState(pos);
        return state.getBlock() == like.getBlock() && DisplayBlock.sameWay(state, like) && level.getBlockEntity(pos) instanceof DisplayBlockEntity;
    }

    private static void reformFrom(ServerLevel level, List<BlockPos> seeds, BlockState like) {
        Set<BlockPos> done = new HashSet<>();
        for (BlockPos seed : seeds) {
            if (done.contains(seed) || !sameScreen(level, seed, like)) {
                continue;
            }
            List<BlockPos> group = flood(level, seed, like);
            done.addAll(group);
            layOut(level, group, like);
        }
    }

    /** The blocks of one kind and facing joined edge to edge in the screen's plane. */
    private static List<BlockPos> flood(ServerLevel level, BlockPos start, BlockState like) {
        Direction right = DisplayBlock.right(like);
        Direction down = DisplayBlock.down(like);
        Direction[] steps = {right, right.getOpposite(), down, down.getOpposite()};
        List<BlockPos> found = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start.immutable());
        seen.add(start.immutable());
        while (!queue.isEmpty() && found.size() < MAX_SCREEN_BLOCKS) {
            BlockPos pos = queue.poll();
            found.add(pos);
            for (Direction step : steps) {
                BlockPos next = pos.relative(step);
                if (!seen.contains(next) && level.isLoaded(next) && sameScreen(level, next, like)) {
                    seen.add(next);
                    queue.add(next);
                }
            }
        }
        return found;
    }

    /**
     * Splits a group of blocks into rectangular screens: from the top-left, each takes as many
     * blocks to the right as it can (up to 8), then as many full rows below as it can (up to 6).
     * Each screen keeps the identity and content of the biggest screen that was there before.
     */
    private static void layOut(ServerLevel level, List<BlockPos> group, BlockState like) {
        Direction right = DisplayBlock.right(like);
        Direction down = DisplayBlock.down(like);
        BlockPos anchor = group.getFirst();
        Map<Long, BlockPos> cells = new HashMap<>();
        for (BlockPos pos : group) {
            int dx = pos.getX() - anchor.getX();
            int dy = pos.getY() - anchor.getY();
            int dz = pos.getZ() - anchor.getZ();
            int u = dx * right.getStepX() + dy * right.getStepY() + dz * right.getStepZ();
            int v = dx * down.getStepX() + dy * down.getStepY() + dz * down.getStepZ();
            cells.put(key(u, v), pos);
        }
        List<Long> order = new ArrayList<>(cells.keySet());
        order.sort(Comparator.<Long>comparingInt(k -> (int) (k >> 32)).thenComparingInt(k -> (int) (long) k));
        Set<Long> taken = new HashSet<>();
        for (long start : order) {
            if (taken.contains(start)) {
                continue;
            }
            int v = (int) (start >> 32);
            int u = (int) start;
            int w = 1;
            while (w < DisplayBlockEntity.MAX_WIDTH && cells.containsKey(key(u + w, v)) && !taken.contains(key(u + w, v))) {
                w++;
            }
            int h = 1;
            grow:
            while (h < DisplayBlockEntity.MAX_HEIGHT) {
                for (int i = 0; i < w; i++) {
                    long k = key(u + i, v + h);
                    if (!cells.containsKey(k) || taken.contains(k)) {
                        break grow;
                    }
                }
                h++;
            }
            List<DisplayBlockEntity> members = new ArrayList<>();
            for (int j = 0; j < h; j++) {
                for (int i = 0; i < w; i++) {
                    taken.add(key(u + i, v + j));
                    if (level.getBlockEntity(cells.get(key(u + i, v + j))) instanceof DisplayBlockEntity member) {
                        members.add(member);
                    }
                }
            }
            if (members.isEmpty()) {
                continue;
            }
            DisplayBlockEntity origin = members.getFirst();
            // The biggest screen among those that were here keeps its identity and content.
            DisplayBlockEntity heir = null;
            for (DisplayBlockEntity member : members) {
                if (member.content() != null && (heir == null
                        || member.screenWidth() * member.screenHeight() > heir.screenWidth() * heir.screenHeight())) {
                    heir = member;
                }
            }
            for (DisplayBlockEntity member : members) {
                if (member != heir) {
                    member.dropContent();
                }
            }
            ScreenContent content;
            if (heir == null) {
                content = origin.newContent();
            } else {
                origin.adopt(heir);
                content = origin.content();
            }
            int index = 0;
            for (int j = 0; j < h; j++) {
                for (int i = 0; i < w; i++) {
                    DisplayBlockEntity member = members.get(index++);
                    member.layOut(i, j, w, h, i == 0 && j == 0 ? content : null);
                }
            }
        }
    }

    private static long key(int u, int v) {
        return ((long) v << 32) | (u & 0xFFFFFFFFL);
    }

    // ---- what players see -----------------------------------------------------------------

    /** What each player has been sent of a screen: which content, its shape and version. */
    private record Sent(ScreenContent content, long shape, long version, long pageAt) {
    }

    /** The screens loaded on the server (their top-left blocks), and what each player has of them. */
    private static final Map<DisplayBlockEntity, Map<UUID, Sent>> LIVE = new HashMap<>();

    public static void remember(DisplayBlockEntity display) {
        if (display.getLevel() != null && !display.getLevel().isClientSide()) {
            LIVE.computeIfAbsent(display, d -> new HashMap<>());
        }
    }

    public static void forget(DisplayBlockEntity display) {
        LIVE.remove(display);
    }

    /** Every other tick: sends players nearby what has changed on the screens they can see. */
    public static void tick(MinecraftServer server) {
        if (server.getTickCount() % 20 == 0) {
            checkHandles(server);
        }
        if (server.getTickCount() % 2 != 0) {
            return;
        }
        Tablets.get(server).tick(server);
        Iterator<Map.Entry<DisplayBlockEntity, Map<UUID, Sent>>> screens = LIVE.entrySet().iterator();
        while (screens.hasNext()) {
            Map.Entry<DisplayBlockEntity, Map<UUID, Sent>> entry = screens.next();
            DisplayBlockEntity display = entry.getKey();
            ScreenContent content = display.content();
            if (display.isRemoved() || content == null || !(display.getLevel() instanceof ServerLevel level)) {
                screens.remove();
                continue;
            }
            Map<UUID, Sent> sent = entry.getValue();
            BlockPos pos = display.getBlockPos();
            Set<UUID> near = new HashSet<>();
            // What is sent is made once for each version players are coming from, not once a player.
            Map<Long, net.minecraft.network.protocol.common.custom.CustomPacketPayload> made = null;
            for (ServerPlayer player : level.players()) {
                if (player.blockPosition().distSqr(pos) > SEND_RANGE * SEND_RANGE) {
                    continue;
                }
                near.add(player.getUUID());
                Sent had = sent.get(player.getUUID());
                long shape = content.shapeVersion();
                long version = content.version();
                if (had != null && had.content() == content && had.shape() == shape && had.version() == version) {
                    continue;
                }
                boolean full = had == null || had.content() != content || had.shape() != shape;
                long now = System.currentTimeMillis();
                long pageAt = had == null ? 0 : had.pageAt();
                if (full || (content instanceof WebScreen web && web.pageChangedSince(had.version()))) {
                    // A page goes out at most once a second to each player: a program rewriting a big
                    // page fast can't flood anyone, and one player's turn doesn't use up another's.
                    if (!full && now - pageAt < 1000) {
                        continue;
                    }
                    pageAt = now;
                }
                if (made == null) {
                    made = new HashMap<>();
                }
                Qbotics.platform().sendToPlayer(player, made.computeIfAbsent(full ? 0 : had.version(), since -> payload(pos, content, since)));
                sent.put(player.getUUID(), new Sent(content, shape, version, pageAt));
            }
            sent.keySet().retainAll(near); // coming back later, a player gets it all again
        }
    }

    static net.minecraft.network.protocol.common.custom.CustomPacketPayload payload(BlockPos pos, ScreenContent content, long since) {
        if (content instanceof TextScreen text) {
            synchronized (text) {
                return new DisplayPayloads.Text(pos, text.cols(), text.rows(), (float) text.scale(), since == 0, text.rowsSince(since));
            }
        }
        if (content instanceof WebScreen web) {
            synchronized (web) {
                boolean page = since == 0 || web.pageChangedSince(since);
                boolean pictures = since == 0 || web.imagesChangedSince(since);
                return new DisplayPayloads.Web(pos, web.pixelWidth(), web.pixelHeight(), page, page ? web.html() : "", web.stateJson(),
                        pictures ? web.images() : null);
            }
        }
        return new DisplayPayloads.Panel(pos, ((PanelScreen) content).state().toString());
    }

    /**
     * A player clicked an element of a Web Panel's page, as their game laid it out: programs
     * listening are told what it was ({@code data-event} or {@code id}, tag, name, value).
     */
    public static void onWebClick(ServerPlayer player, DisplayPayloads.WebClick click) {
        if (Tablets.isKey(click.origin())) {
            Tablets.get(player.level().getServer()).onWebClick(player, click);
            return;
        }
        if (!(player.level() instanceof ServerLevel level) || !level.isLoaded(click.origin())
                || !(level.getBlockEntity(click.origin()) instanceof DisplayBlockEntity display) || !(display.content() instanceof WebScreen web)) {
            return;
        }
        // The panel may be large: measure to its nearest block, not its corner.
        double far = Double.MAX_VALUE;
        Direction right = DisplayBlock.right(display.getBlockState());
        Direction down = DisplayBlock.down(display.getBlockState());
        for (int i = 0; i < display.screenWidth(); i++) {
            for (int j = 0; j < display.screenHeight(); j++) {
                BlockPos block = click.origin().relative(right, i).relative(down, j);
                far = Math.min(far, player.position().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(block)));
            }
        }
        if (far > CLOSE_UP_RANGE * CLOSE_UP_RANGE) {
            return;
        }
        webClicked(level, player, web, click.element(), display.mac(), display.deviceName(), display.getBlockPos(), null);
    }

    /** A click on a Web Panel's page (or a tablet's): the event for the programs listening. */
    static void webClicked(ServerLevel level, ServerPlayer player, WebScreen web, String clicked, String mac, String name, BlockPos soundAt,
            @Nullable JsonObject extra) {
        JsonObject element;
        try {
            element = JsonParser.parseString(clicked).getAsJsonObject();
        } catch (RuntimeException e) {
            return;
        }
        JsonObject event = new JsonObject();
        event.addProperty("type", "web");
        for (String field : new String[] {"tag", "id", "name", "value"}) {
            String text = element.has(field) && element.get(field).isJsonPrimitive() ? element.get(field).getAsString() : "";
            event.addProperty(field, text.length() > qbotics.web.WebInputs.MAX_AREA_LENGTH ? text.substring(0, qbotics.web.WebInputs.MAX_AREA_LENGTH) : text);
        }
        // What a player's game sends is not trusted to have the right shape.
        String eventName = element.has("event") && element.get("event").isJsonPrimitive() ? element.get("event").getAsString() : "";
        event.addProperty("event", eventName.isEmpty() ? event.get("id").getAsString() : eventName);
        event.addProperty("enter", element.has("enter") && element.get("enter").isJsonPrimitive()
                && element.get("enter").getAsBoolean()); // Enter pressed in a text field
        if (element.has("data") && element.get("data").isJsonPrimitive()) {
            // panel.send(name, data) from the page's script: the data, as JSON.
            try {
                event.add("data", JsonParser.parseString(element.get("data").getAsString()));
            } catch (RuntimeException e) {
                event.add("data", com.google.gson.JsonNull.INSTANCE);
            }
        }
        event.addProperty("mac", mac);
        event.addProperty("panel", name);
        event.addProperty("player", player.getName().getString());
        if (extra != null) {
            extra.entrySet().forEach(entry -> event.add(entry.getKey(), entry.getValue()));
        }
        level.playSound(null, soundAt, SoundEvents.STONE_BUTTON_CLICK_ON, SoundSource.BLOCKS, 0.3f, 1.4f);
        ComputerManager computers = ComputerManager.get(level.getServer());
        web.listeners.entrySet().removeIf(listener -> !computers.pushEvent(listener.getKey(), listener.getValue(), event));
    }

    // ---- programs: handles and fast drawing -----------------------------------------------

    /** A program's hold on a display. */
    private record Handle(UUID computer, ResourceKey<Level> dimension, BlockPos computerPos, @Nullable DisplayBlockEntity display, ScreenContent content) {
    }

    private static final Map<Integer, Handle> HANDLES = new ConcurrentHashMap<>();

    /** The server has stopped: its screens and the handles programs drew on them with are let go. */
    public static void clear() {
        LIVE.clear();
        HANDLES.clear();
    }
    private static final AtomicInteger NEXT_HANDLE = new AtomicInteger(1);

    /** Gives a computer a handle to draw on a display of its network with. Server thread. */
    public static int open(ServerLevel level, BlockPos computerPos, UUID computer, DisplayBlockEntity display) {
        ScreenContent content = display.content();
        if (content == null) {
            throw new IllegalStateException("not a screen's top-left block");
        }
        return open(level, computerPos, computer, display, content);
    }

    /** Gives a computer a handle to draw on a tablet's screen (not a block: checked by the tablet). */
    public static int openTablet(ServerLevel level, BlockPos computerPos, UUID computer, ScreenContent content) {
        return open(level, computerPos, computer, null, content);
    }

    private static int open(ServerLevel level, BlockPos computerPos, UUID computer, @Nullable DisplayBlockEntity display, ScreenContent content) {
        int count = 0;
        for (Map.Entry<Integer, Handle> entry : HANDLES.entrySet()) {
            Handle handle = entry.getValue();
            if (handle.computer().equals(computer)) {
                if (handle.content() == content) {
                    return entry.getKey();
                }
                count++;
            }
        }
        if (count >= MAX_HANDLES_PER_COMPUTER) {
            HANDLES.entrySet().removeIf(entry -> entry.getValue().computer().equals(computer)); // starts again
        }
        int id = NEXT_HANDLE.getAndIncrement();
        HANDLES.put(id, new Handle(computer, level.dimension(), computerPos.immutable(), display, content));
        return id;
    }

    /**
     * Why a program may not open this display, or null if it may: a Web Panel over its owner's
     * limit of panels in use (each costs every nearby player's game to draw). Counted among
     * loaded screens.
     */
    public static @Nullable String overLimit(ServerLevel level, DisplayBlockEntity display) {
        int limit = qbotics.QboticsConfig.webPanelsPerPlayer;
        UUID owner = display.owner();
        if (!(display.content() instanceof WebScreen) || owner == null || limit < 0 || qbotics.limits.Limits.exempt(level.getServer(), owner)) {
            return null;
        }
        int inUse = 0;
        for (DisplayBlockEntity other : LIVE.keySet()) {
            if (other != display && !other.isRemoved() && owner.equals(other.owner()) && other.content() instanceof WebScreen web && web.inUse()) {
                inUse++;
            }
        }
        if (inUse >= limit) {
            return "Its owner already has " + inUse + " Web Panels in use, the most this server allows (web_panels_per_player = "
                    + limit + "). Clear one (web.clear()) or break it.";
        }
        return null;
    }

    /** A program wants this display's touches and presses pushed to its event queue. */
    public static void listen(DisplayBlockEntity display, UUID computer, int session) {
        if (display.content() != null) {
            display.content().listeners.put(computer, session);
        }
    }

    /** The prefix of the calls {@link #drawFast} answers. Must match display.py in the ROM. */
    public static final String DRAW_PREFIX = "[\"draw\",";

    /**
     * Carries out a program's drawing call on the program's own thread: ["draw", handle,
     * method, args...]. Answers as a world call would; a handle that no longer works answers
     * with "gone", and the program asks for the display again.
     */
    public static String drawFast(UUID computer, String call) {
        JsonObject reply = new JsonObject();
        try {
            JsonArray args = JsonParser.parseString(call).getAsJsonArray();
            Handle handle = HANDLES.get(args.get(1).getAsInt());
            if (handle == null || !handle.computer().equals(computer) || handle.content().isGone()) {
                reply.addProperty("gone", true);
                reply.addProperty("e", "That display is no longer on this computer's network.");
                return reply.toString();
            }
            JsonArray rest = new JsonArray();
            for (int i = 3; i < args.size(); i++) {
                rest.add(args.get(i));
            }
            reply.add("r", handle.content().op(args.get(2).getAsString(), rest));
        } catch (IllegalArgumentException e) {
            reply = new JsonObject();
            reply.addProperty("e", e.getMessage());
        } catch (RuntimeException e) {
            reply = new JsonObject();
            reply.addProperty("e", "The drawing call's arguments are wrong (" + e.getClass().getSimpleName() + ").");
        }
        return reply.toString();
    }

    /** Once a second: drops handles whose computer has stopped or whose display has left its network. */
    private static void checkHandles(MinecraftServer server) {
        ComputerManager computers = ComputerManager.get(server);
        HANDLES.entrySet().removeIf(entry -> {
            Handle handle = entry.getValue();
            if (handle.content().isGone() || computers.computer(handle.computer()) == null) {
                return true;
            }
            ServerLevel level = server.getLevel(handle.dimension());
            if (handle.display() == null) {
                // A tablet's screen: gone when the tablet is unpaired or changes kind, and when its
                // computer (still standing where it was; a Qbot's tab is its own) is no longer on the
                // network the tab belongs to.
                UUID key = Tablets.get(server).keyOf(handle.content());
                if (key == null || level == null || !level.isLoaded(handle.computerPos())
                        || !(level.getBlockEntity(handle.computerPos()) instanceof qbotics.block.ComputerBlockEntity block)
                        || block instanceof qbotics.qbot.QbotBlockEntity || !block.deviceId().equals(handle.computer())) {
                    return false;
                }
                var gateway = CableNetwork.at(level, handle.computerPos()).gateway();
                if (gateway != null && gateway.deviceId().equals(key)) {
                    return false;
                }
                handle.content().listeners.remove(handle.computer());
                return true;
            }
            if (handle.display().isRemoved() || handle.display().content() != handle.content()) {
                return true;
            }
            boolean left = level == null || !level.isLoaded(handle.computerPos())
                    || !CableNetwork.at(level, handle.computerPos()).has(handle.display());
            if (left) {
                handle.content().listeners.remove(handle.computer()); // its touches too
            }
            return left;
        });
    }

    // ---- players touching screens -----------------------------------------------------------

    /**
     * A player touched a screen at a point given as fractions of its width and height: a
     * panel's widget is pressed, flipped or slid, and programs listening are told.
     */
    public static void touched(ServerLevel level, DisplayBlockEntity block, double fx, double fy, ServerPlayer player) {
        if (!(level.getBlockEntity(block.originPos()) instanceof DisplayBlockEntity display) || display.content() == null) {
            return;
        }
        touchedContent(level, display.content(), fx, fy, player, display.mac(), display.deviceName(), display.getBlockPos(), null);
    }

    /** A touch on a screen's content (a block's or a tablet's), told to the programs listening. */
    static void touchedContent(ServerLevel level, ScreenContent content, double fx, double fy, ServerPlayer player, String mac, String name,
            BlockPos soundAt, @Nullable JsonObject extra) {
        JsonObject event;
        if (content instanceof PanelScreen panel) {
            int cols;
            int rows;
            synchronized (panel) {
                cols = panel.cols();
                rows = panel.rows();
            }
            event = panel.click(fx * cols, fy * rows);
            if (event == null) {
                return;
            }
            event.addProperty("type", "panel");
            level.playSound(null, soundAt, SoundEvents.STONE_BUTTON_CLICK_ON, SoundSource.BLOCKS, 0.3f, 1.4f);
        } else if (content instanceof TextScreen text) {
            int[] cell = text.cellAt(fx, fy);
            event = new JsonObject();
            event.addProperty("type", "touch");
            event.addProperty("event", "touch");
            event.addProperty("x", cell[0]);
            event.addProperty("y", cell[1]);
        } else {
            return;
        }
        event.addProperty("mac", mac);
        event.addProperty("name", name);
        event.addProperty("player", player.getName().getString());
        if (extra != null) {
            extra.entrySet().forEach(entry -> event.add(entry.getKey(), entry.getValue()));
        }
        ComputerManager computers = ComputerManager.get(level.getServer());
        content.listeners.entrySet().removeIf(listener -> !computers.pushEvent(listener.getKey(), listener.getValue(), event));
    }

    /** Sneak and right-click: the player's game opens the screen close up. */
    public static void openCloseUp(ServerPlayer player, DisplayBlockEntity block) {
        BlockPos origin = block.originPos();
        if (player.level().getBlockEntity(origin) instanceof DisplayBlockEntity display && display.content() != null) {
            Map<UUID, Sent> sent = LIVE.get(display);
            if (sent != null) {
                sent.remove(player.getUUID()); // send it all again, to be sure the view starts complete
            }
            tickOne(display, player);
            Qbotics.platform().sendToPlayer(player, new DisplayPayloads.OpenCloseUp(origin));
        }
    }

    private static void tickOne(DisplayBlockEntity display, ServerPlayer player) {
        ScreenContent content = display.content();
        if (content != null) {
            Qbotics.platform().sendToPlayer(player, payload(display.getBlockPos(), content, 0));
            Map<UUID, Sent> sent = LIVE.computeIfAbsent(display, d -> new HashMap<>());
            sent.put(player.getUUID(), new Sent(content, content.shapeVersion(), content.version(), System.currentTimeMillis()));
        }
    }

    /** A touch from the close-up view: checked, then handled as a touch on the block. */
    public static void onCloseUpTouch(ServerPlayer player, DisplayPayloads.Touch touch) {
        if (Tablets.isKey(touch.origin())) {
            if (Float.isFinite(touch.x()) && Float.isFinite(touch.y())) {
                Tablets.get(player.level().getServer()).onTouch(player, touch.origin(), Math.clamp(touch.x(), 0, 0.9999), Math.clamp(touch.y(), 0, 0.9999));
            }
            return;
        }
        if (!(player.level() instanceof ServerLevel level) || !level.isLoaded(touch.origin())
                || !(level.getBlockEntity(touch.origin()) instanceof DisplayBlockEntity display) || display.content() == null) {
            return;
        }
        if (player.position().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(touch.origin())) > CLOSE_UP_RANGE * CLOSE_UP_RANGE
                || !Float.isFinite(touch.x()) || !Float.isFinite(touch.y())) {
            return;
        }
        touched(level, display, Math.clamp(touch.x(), 0, 0.9999), Math.clamp(touch.y(), 0, 0.9999), player);
    }

    /** The display a program means: the screen's top-left block, from any of its blocks. */
    public static @Nullable DisplayBlockEntity screenOf(Level level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof DisplayBlockEntity block
                && level.getBlockEntity(block.originPos()) instanceof DisplayBlockEntity display && display.content() != null) {
            return display;
        }
        return null;
    }
}
