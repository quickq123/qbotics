package qbotics.display;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jspecify.annotations.Nullable;
import qbotics.Qbotics;
import qbotics.QboticsContent;
import qbotics.block.AntennaBlockEntity;
import qbotics.net.DisplayPayloads;
import qbotics.net.TabletPayloads;
import qbotics.network.Addresses;

/**
 * Tablets (design doc §10.6): screens carried in the hand. A tablet is paired with networks by
 * sneak-right-clicking their antennas; each network it is paired with gets a screen of its own
 * on it (an "app", shown as a tab), which that network's programs draw on as on a display
 * block, in whichever kind they choose: text, widgets or a web page. The player picks which
 * tab shows, so programs never fight over one screen.
 *
 * <p>The screens live here on the server, kept with the world, under the tablet's ID (on the
 * item). What the selected tab shows is sent to the player holding the tablet. A player's game
 * knows a tablet's screens by made-up block positions at {@link #KEY_Y}, above any world's
 * height, so the display code that sends, draws and touches screens works for them unchanged.
 */
public final class Tablets extends SavedData {
    /** The height of the made-up positions tablet screens are known by in players' games. No block is ever here. */
    public static final int KEY_Y = 2047;
    /** A tablet's screen is as big as a display 3 blocks wide and 2 high. */
    public static final int WIDTH = 3;
    public static final int HEIGHT = 2;
    /** Networks one tablet may be paired with. */
    public static final int MAX_APPS = 8;
    /** A tablet nobody has carried for this long (real time) is forgotten: its screens and pairings. */
    private static final long FORGET_AFTER_MS = 30L * 24 * 3600 * 1000;
    public static final String[] KINDS = {"monitor", "panel", "web_panel"};

    private static final Codec<Tablets> CODEC = Codec.STRING.xmap(Tablets::fromJson, Tablets::toJson);
    // No data fixer: this data is the mod's own.
    static final SavedDataType<Tablets> TYPE = new SavedDataType<>(Qbotics.id("tablets"), Tablets::new, CODEC, null);

    /** One network's screen on a tablet. */
    public static final class App {
        final Tablet tablet;
        /** The antenna (its device ID) that is the paired network's router. */
        final UUID antenna;
        /** What the tab says when no program has named it: the antenna's name or address. */
        String network;
        /** The name a program gave the tab ("" for none). */
        String title = "";
        String kind = "monitor";
        ScreenContent content;
        /** Its made-up position's x in players' games; not saved. */
        int key;

        App(Tablet tablet, UUID antenna, String network) {
            this.tablet = tablet;
            this.antenna = antenna;
            this.network = network;
        }

        public String label() {
            return title.isEmpty() ? network : title;
        }

        public ScreenContent content() {
            return content;
        }
    }

    /** One tablet: its screens, which is selected, and who carried it last. */
    public static final class Tablet {
        final UUID id;
        final List<App> apps = new ArrayList<>();
        int selected;
        long lastSeen = System.currentTimeMillis();
        /** Its name as an item (renamed on an anvil), or "". */
        String name = "";
        @Nullable UUID holder;
        String holderName = "";

        Tablet(UUID id) {
            this.id = id;
        }

        public String mac() {
            return Addresses.mac(id);
        }

        public @Nullable App selectedApp() {
            return apps.isEmpty() ? null : apps.get(Math.clamp(selected, 0, apps.size() - 1));
        }

        public List<App> apps() {
            return apps;
        }
    }

    private final Map<UUID, Tablet> tablets = new HashMap<>();
    // Not saved: the made-up keys, and what each player has been sent.
    private final Map<Integer, App> byKey = new HashMap<>();
    private int nextKey = 1;
    private record Sent(ScreenContent content, long shape, long version) {
    }
    private final Map<UUID, Map<Integer, Sent>> sentContent = new HashMap<>();
    private final Map<UUID, Map<UUID, String>> sentState = new HashMap<>();

    public Tablets() {
    }

    public static Tablets get(MinecraftServer server) {
        return server.getDataStorage().computeIfAbsent(TYPE);
    }

    public static boolean isKey(BlockPos pos) {
        return pos.getY() == KEY_Y;
    }

    public static BlockPos keyPos(int key) {
        return new BlockPos(key, KEY_Y, 0);
    }

    // ---- the item -----------------------------------------------------------------------------

    public static boolean isTablet(ItemStack stack) {
        return QboticsContent.TABLET_ITEM != null && stack.is(QboticsContent.TABLET_ITEM.get());
    }

    /** The tablet an item is, given an ID the first time it is used. Server only. */
    public Tablet of(ItemStack stack) {
        UUID id = stack.get(QboticsContent.TABLET_ID.get());
        if (id == null) {
            id = UUID.randomUUID();
            stack.set(QboticsContent.TABLET_ID.get(), id);
        }
        Tablet tablet = tablets.get(id);
        if (tablet == null) {
            tablet = new Tablet(id);
            tablets.put(id, tablet);
            setDirty();
        }
        return tablet;
    }

    private void keyed(App app) {
        if (app.key == 0) {
            app.key = nextKey++;
            byKey.put(app.key, app);
        }
    }

    private static ScreenContent blank(String kind) {
        ScreenContent content = switch (kind) {
            case "panel" -> new PanelScreen();
            case "web_panel" -> new WebScreen();
            default -> new TextScreen();
        };
        content.resize(WIDTH, HEIGHT);
        return content;
    }

    private static String networkLabel(AntennaBlockEntity antenna) {
        return antenna.deviceName().isEmpty() ? "Network " + antenna.externalIp() : antenna.deviceName();
    }

    // ---- pairing ------------------------------------------------------------------------------

    /** Sneak and right-click an antenna: pairs the tablet with its network, or unpairs it if it was. */
    public void pair(ServerPlayer player, ItemStack stack, AntennaBlockEntity antenna) {
        if (unpair(player, stack, antenna.deviceId())) {
            return;
        }
        MinecraftServer server = player.level().getServer();
        String refused = qbotics.network.RadioNetwork.accessProblem(server, antenna, player.getUUID());
        if (refused != null) {
            player.sendOverlayMessage(Component.translatable("message.qbotics.tablet.refused", refused));
            return;
        }
        if (antenna.getLevel() instanceof ServerLevel level
                && qbotics.network.CableNetwork.at(level, antenna.getBlockPos()).gateway() != antenna) {
            player.sendOverlayMessage(Component.translatable("message.qbotics.tablet.not_router"));
            return;
        }
        add(player, stack, antenna.deviceId(), networkLabel(antenna), "tab = network().tablet()");
    }

    /**
     * Sneak and right-click a Qbot: pairs the tablet with it, or unpairs it if it was. The tab is
     * the Qbot's own (its radio reaches the tablet anywhere): its program draws there with
     * network().tablet(). Only the Qbot's owner may pair.
     */
    public void pair(ServerPlayer player, ItemStack stack, qbotics.qbot.QbotBlockEntity qbot) {
        if (unpair(player, stack, qbot.deviceId())) {
            return;
        }
        if (!java.util.Objects.equals(qbot.owner(), player.getUUID())) {
            player.sendOverlayMessage(Component.translatable("message.qbotics.tablet.refused", "only this Qbot's owner may pair with it"));
            return;
        }
        String label = qbot.deviceName().isEmpty() ? "Qbot " + qbot.radioIp(player.level().getServer()) : qbot.deviceName();
        add(player, stack, qbot.deviceId(), label, "tab = network().tablet()   (in the Qbot's program)");
    }

    /** Unpairs the tablet from this network (or Qbot) if it was paired. True if it was. */
    private boolean unpair(ServerPlayer player, ItemStack stack, UUID key) {
        Tablet tablet = of(stack);
        for (Iterator<App> it = tablet.apps.iterator(); it.hasNext(); ) {
            App app = it.next();
            if (app.antenna.equals(key)) {
                it.remove();
                app.content.markGone();
                byKey.remove(app.key);
                tablet.selected = Math.clamp(tablet.selected, 0, Math.max(0, tablet.apps.size() - 1));
                setDirty();
                player.sendOverlayMessage(Component.translatable("message.qbotics.tablet.unpaired", app.label()));
                return true;
            }
        }
        return false;
    }

    /** A new tab for a network (by its antenna's ID) or a Qbot (by its ID). */
    private void add(ServerPlayer player, ItemStack stack, UUID key, String label, String hint) {
        Tablet tablet = of(stack);
        if (tablet.apps.size() >= MAX_APPS) {
            player.sendOverlayMessage(Component.translatable("message.qbotics.tablet.full", MAX_APPS));
            return;
        }
        App app = new App(tablet, key, label);
        app.content = blank("monitor");
        JsonArray line = new JsonArray();
        line.add("Paired with " + app.network + ".\n\nWaiting for a program on that network:\n\n  " + hint);
        app.content.op("print", line);
        tablet.apps.add(app);
        tablet.selected = tablet.apps.size() - 1;
        keyed(app);
        setDirty();
        player.sendOverlayMessage(Component.translatable("message.qbotics.tablet.paired", app.network));
    }

    // ---- programs -----------------------------------------------------------------------------

    /** The tablets paired with the network whose router is this antenna. */
    public List<Tablet> pairedWith(UUID antenna) {
        List<Tablet> found = new ArrayList<>();
        for (Tablet tablet : tablets.values()) {
            for (App app : tablet.apps) {
                if (app.antenna.equals(antenna)) {
                    found.add(tablet);
                }
            }
        }
        found.sort(java.util.Comparator.comparing(Tablet::mac));
        return found;
    }

    /** A tablet's screen for the network whose router is this antenna, by the tablet's hardware address; null if none. */
    public @Nullable App app(UUID antenna, String mac) {
        for (Tablet tablet : tablets.values()) {
            if (tablet.mac().equalsIgnoreCase(mac)) {
                for (App app : tablet.apps) {
                    if (app.antenna.equals(antenna)) {
                        keyed(app);
                        return app;
                    }
                }
            }
        }
        return null;
    }

    /**
     * A tablet paired with a network, by its hardware address, its name, or the name of the
     * player carrying it; with no key, the network's only tablet. Throws with a message for
     * the program if there is none, or more than one.
     */
    public Tablet find(UUID antenna, @Nullable String key) {
        List<Tablet> paired = pairedWith(antenna);
        List<Tablet> matches = new ArrayList<>();
        for (Tablet tablet : paired) {
            if (key == null || tablet.mac().equalsIgnoreCase(key) || tablet.name.equalsIgnoreCase(key) || tablet.holderName.equalsIgnoreCase(key)) {
                matches.add(tablet);
            }
        }
        if (matches.size() == 1) {
            return matches.getFirst();
        }
        if (paired.isEmpty()) {
            throw new IllegalArgumentException("No tablet is paired with this network. Sneak and right-click its antenna with a tablet (on a Qbot: the Qbot itself).");
        }
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("No tablet paired with this network is called " + key + " or carried by a player of that name.");
        }
        throw new IllegalArgumentException(matches.size() + " tablets are paired with this network: name one (by its player, its name or its address).");
    }

    /** What a program is told about a tablet. */
    public JsonObject describe(Tablet tablet, UUID antenna) {
        JsonObject info = new JsonObject();
        info.addProperty("kind", "tablet");
        info.addProperty("mac", tablet.mac());
        info.addProperty("ip", "");
        info.addProperty("name", tablet.name);
        info.addProperty("holder", tablet.holderName);
        App app = app(antenna, tablet.mac());
        info.addProperty("title", app == null ? "" : app.title);
        info.addProperty("screen", app == null ? "" : app.kind);
        return info;
    }

    /** A program wants the tablet's screen for its network as a kind of display: a blank one of that kind if it was another. */
    public ScreenContent use(App app, @Nullable String kind) {
        if (kind != null && !kind.equals(app.kind)) {
            if (!List.of(KINDS).contains(kind)) {
                throw new IllegalArgumentException("A tablet's screen is a monitor, a panel or a web_panel, not " + kind + ".");
            }
            ScreenContent fresh = blank(kind);
            fresh.listeners.putAll(app.content.listeners);
            app.content.markGone();
            app.content = fresh;
            app.kind = kind;
            setDirty();
        }
        return app.content;
    }

    public void setTitle(App app, String title) {
        String clean = ScreenContent.printable(title).replace('\n', ' ').strip();
        app.title = clean.length() > 32 ? clean.substring(0, 32) : clean;
        setDirty();
    }

    // ---- players --------------------------------------------------------------------------------

    /** The tablet a player holds with this screen on it, or null. */
    private @Nullable App held(ServerPlayer player, BlockPos key) {
        App app = byKey.get(key.getX());
        if (app == null) {
            return null;
        }
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (isTablet(stack) && app.tablet.id.equals(stack.get(QboticsContent.TABLET_ID.get())) && app.tablet.selectedApp() == app) {
                return app;
            }
        }
        return null;
    }

    private static JsonObject extra(App app) {
        JsonObject extra = new JsonObject();
        extra.addProperty("tablet", true);
        extra.addProperty("title", app.label());
        return extra;
    }

    public void onTouch(ServerPlayer player, BlockPos key, double fx, double fy) {
        App app = held(player, key);
        if (app != null && player.level() instanceof ServerLevel level) {
            Displays.touchedContent(level, app.content, fx, fy, player, app.tablet.mac(), app.tablet.name, player.blockPosition(), extra(app));
        }
    }

    public void onWebClick(ServerPlayer player, DisplayPayloads.WebClick click) {
        App app = held(player, click.origin());
        if (app != null && app.content instanceof WebScreen web && player.level() instanceof ServerLevel level) {
            Displays.webClicked(level, player, web, click.element(), app.tablet.mac(), app.tablet.name, player.blockPosition(), extra(app));
        }
    }

    /** The player picked a tab on a tablet they hold. */
    public void select(ServerPlayer player, UUID id, int index) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (isTablet(stack) && id.equals(stack.get(QboticsContent.TABLET_ID.get()))) {
                Tablet tablet = of(stack);
                if (index >= 0 && index < tablet.apps.size() && tablet.selected != index) {
                    tablet.selected = index;
                    setDirty();
                }
                return;
            }
        }
    }

    /** Right-click: the player's game gets the tablet's screen now, then opens it. */
    public void open(ServerPlayer player, ItemStack stack) {
        Tablet tablet = of(stack);
        Map<UUID, String> states = sentState.get(player.getUUID());
        if (states != null) {
            states.remove(tablet.id);
        }
        Map<Integer, Sent> contents = sentContent.get(player.getUUID());
        App app = tablet.selectedApp();
        if (contents != null && app != null) {
            contents.remove(app.key);
        }
        send(player, tablet, true);
        Qbotics.platform().sendToPlayer(player, new TabletPayloads.Open(tablet.id));
    }

    /** Every other tick: who carries which tablet, and what the held ones show, to their holders. */
    void tick(MinecraftServer server) {
        boolean census = server.getTickCount() % 20 == 0;
        long now = System.currentTimeMillis();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (census) {
                for (ItemStack stack : player.getInventory()) {
                    if (isTablet(stack) && stack.get(QboticsContent.TABLET_ID.get()) != null) {
                        Tablet tablet = of(stack);
                        tablet.lastSeen = now;
                        tablet.holder = player.getUUID();
                        tablet.holderName = player.getName().getString();
                        tablet.name = stack.has(net.minecraft.core.component.DataComponents.CUSTOM_NAME) ? stack.getHoverName().getString() : "";
                    }
                }
            }
            for (InteractionHand hand : InteractionHand.values()) {
                ItemStack stack = player.getItemInHand(hand);
                if (isTablet(stack)) {
                    send(player, of(stack), false);
                }
            }
        }
        if (census && !tablets.isEmpty()) {
            setDirty(); // programs draw from their own threads; what they drew is saved with the world
        }
        if (census && server.getTickCount() % 1200 == 0) {
            sentContent.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
            sentState.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
            tablets.values().removeIf(tablet -> {
                if (now - tablet.lastSeen < FORGET_AFTER_MS) {
                    return false;
                }
                tablet.apps.forEach(app -> {
                    app.content.markGone();
                    byKey.remove(app.key);
                });
                setDirty();
                return true;
            });
        }
    }

    /** Sends a holder the tablet's tabs if they changed, and its selected screen if that changed. */
    private void send(ServerPlayer player, Tablet tablet, boolean force) {
        App app = tablet.selectedApp();
        if (app != null) {
            keyed(app);
        }
        List<String> tabs = new ArrayList<>();
        for (App each : tablet.apps) {
            tabs.add(each.label());
        }
        int key = app == null ? 0 : app.key;
        String state = String.join("\u0000", tabs) + "|" + tablet.selected + "|" + key;
        Map<UUID, String> states = sentState.computeIfAbsent(player.getUUID(), id -> new HashMap<>());
        if (force || !state.equals(states.get(tablet.id))) {
            states.put(tablet.id, state);
            Qbotics.platform().sendToPlayer(player, new TabletPayloads.State(tablet.id, tabs, app == null ? -1 : tablet.selected, keyPos(key)));
        }
        if (app == null) {
            return;
        }
        ScreenContent content = app.content;
        Map<Integer, Sent> sent = sentContent.computeIfAbsent(player.getUUID(), id -> new HashMap<>());
        Sent had = sent.get(app.key);
        long shape = content.shapeVersion();
        long version = content.version();
        if (had != null && had.content() == content && had.shape() == shape && had.version() == version) {
            return;
        }
        boolean full = had == null || had.content() != content || had.shape() != shape;
        if (!full && content instanceof WebScreen web && web.pageChangedSince(had.version())) {
            long ms = System.currentTimeMillis();
            if (ms - web.lastPageSent < 1000) {
                return; // a page goes out at most once a second
            }
            web.lastPageSent = ms;
        }
        Qbotics.platform().sendToPlayer(player, Displays.payload(keyPos(app.key), content, full ? 0 : had.version()));
        sent.put(app.key, new Sent(content, shape, version));
    }

    /** The made-up position a tablet's selected screen is known by in players' games (for tests). */
    public BlockPos screenKey(ItemStack stack) {
        App app = of(stack).selectedApp();
        if (app == null) {
            return keyPos(0);
        }
        keyed(app);
        return keyPos(app.key);
    }

    /** For the tooltip: the tabs of the tablet with this ID, as last kept. */
    public List<String> tabs(@Nullable UUID id) {
        Tablet tablet = id == null ? null : tablets.get(id);
        List<String> tabs = new ArrayList<>();
        if (tablet != null) {
            tablet.apps.forEach(app -> tabs.add(app.label()));
        }
        return tabs;
    }

    // ---- saving ---------------------------------------------------------------------------------

    private static Tablets fromJson(String text) {
        Tablets loaded = new Tablets();
        try {
            for (JsonElement element : JsonParser.parseString(text).getAsJsonObject().getAsJsonArray("tablets")) {
                JsonObject saved = element.getAsJsonObject();
                Tablet tablet = new Tablet(UUID.fromString(saved.get("id").getAsString()));
                tablet.selected = saved.get("selected").getAsInt();
                tablet.lastSeen = saved.get("seen").getAsLong();
                tablet.name = saved.get("name").getAsString();
                tablet.holderName = saved.get("holder").getAsString();
                for (JsonElement a : saved.getAsJsonArray("apps")) {
                    JsonObject s = a.getAsJsonObject();
                    App app = new App(tablet, UUID.fromString(s.get("antenna").getAsString()), s.get("network").getAsString());
                    app.title = s.get("title").getAsString();
                    app.kind = s.get("kind").getAsString().toLowerCase(Locale.ROOT);
                    app.content = blank(app.kind);
                    try {
                        app.content.load(s.getAsJsonObject("screen"));
                        app.content.resize(WIDTH, HEIGHT);
                    } catch (RuntimeException e) {
                        app.content = blank(app.kind);
                    }
                    tablet.apps.add(app);
                }
                loaded.tablets.put(tablet.id, tablet);
            }
        } catch (RuntimeException e) {
            Qbotics.LOGGER.warn("The tablets' saved screens couldn't be read; tablets start unpaired", e);
        }
        return loaded;
    }

    private String toJson() {
        JsonArray list = new JsonArray();
        for (Tablet tablet : tablets.values()) {
            JsonObject saved = new JsonObject();
            saved.addProperty("id", tablet.id.toString());
            saved.addProperty("selected", tablet.selected);
            saved.addProperty("seen", tablet.lastSeen);
            saved.addProperty("name", tablet.name);
            saved.addProperty("holder", tablet.holderName);
            JsonArray apps = new JsonArray();
            for (App app : tablet.apps) {
                JsonObject s = new JsonObject();
                s.addProperty("antenna", app.antenna.toString());
                s.addProperty("network", app.network);
                s.addProperty("title", app.title);
                s.addProperty("kind", app.kind);
                s.add("screen", app.content.save());
                apps.add(s);
            }
            saved.add("apps", apps);
            list.add(saved);
        }
        JsonObject all = new JsonObject();
        all.add("tablets", list);
        return all.toString();
    }
}
