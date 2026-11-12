package qbotics.client.ide;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import qbotics.Qbotics;
import qbotics.net.IdePayloads;
import qbotics.net.TerminalInputPayload;
import qbotics.net.TerminalStatePayload;

/** Where the server's messages for the editor screen arrive, and the real {@link IdeBackend}. */
public final class ClientIde {
    private static final Map<UUID, NetworkBackend> BACKENDS = new HashMap<>();

    private ClientIde() {
    }

    /** The server sent a computer's terminal screen; with open set, show the editor. */
    public static void onTerminalState(TerminalStatePayload state) {
        IdeSession session = IdeSession.of(state.deviceId());
        net.minecraft.client.player.LocalPlayer me = Minecraft.getInstance().player;
        if (me != null && qbotics.laptop.Laptops.isLaptopOf(state.deviceId(), me.getUUID())) {
            session.laptop = true;
            session.title = "Laptop";
        }
        session.applyTerminal(state.state());
        Minecraft minecraft = Minecraft.getInstance();
        boolean showing = minecraft.screen instanceof IdeScreen screen && screen.session() == session;
        if (state.open() && !showing) {
            minecraft.setScreen(new IdeScreen(session, backend(state.deviceId())));
        }
    }

    /** The lines of a computer's terminal as last sent, for a Qbot's screen (no editor is open for it). */
    public static java.util.List<String> terminalLines(UUID deviceId) {
        return IdeSession.of(deviceId).terminalLines;
    }

    /**
     * Everything a computer's terminal has shown, as last sent: the lines scrolled off the top
     * (as many as are kept), then the screen, without the empty rows at its bottom.
     */
    public static java.util.List<String> terminalHistory(UUID deviceId) {
        IdeSession session = IdeSession.of(deviceId);
        java.util.List<String> all = new java.util.ArrayList<>(session.terminalScrollback);
        all.addAll(session.terminalLines);
        while (!all.isEmpty() && all.getLast().isBlank()) {
            all.removeLast();
        }
        return all;
    }

    /** Where the program of an open computer is: its editor marks that line. */
    public static void onTrace(qbotics.net.TracePayload trace) {
        IdeSession session = IdeSession.of(trace.deviceId());
        boolean newlyPaused = trace.paused() && (!session.tracePaused || session.traceLine != trace.line());
        session.traceFile = trace.file();
        session.traceLine = trace.line();
        session.tracePaused = trace.paused();
        if (newlyPaused && Minecraft.getInstance().screen instanceof IdeScreen screen && screen.session() == session) {
            screen.showLine(trace.file(), trace.line()); // bring the line it stopped on into view
        }
    }

    /** The server opened a drive (a Programmer's) in the editor: files only, no terminal. */
    public static void onOpenEditor(IdePayloads.OpenEditor open) {
        IdeSession session = IdeSession.of(open.deviceId());
        session.editOnly = true;
        session.title = open.title();
        Minecraft.getInstance().setScreen(new IdeScreen(session, backend(open.deviceId())));
    }

    public static void onFileResponse(IdePayloads.FileResponse response) {
        IdeBackend.FileCallback callback = backend(response.deviceId()).files.remove(response.requestId());
        if (callback != null) {
            callback.done(response.errno(), response.data());
        }
    }

    public static void onScratchResult(IdePayloads.ScratchResult result) {
        IdeBackend.ScratchCallback callback = backend(result.deviceId()).scratches.remove(result.runId());
        if (callback != null) {
            callback.done(result.ok(), result.output());
        }
    }

    /** The "edit" shell command: open a file in the editor that is showing this computer. */
    public static void onOpenFile(IdePayloads.OpenFile open) {
        if (Minecraft.getInstance().screen instanceof IdeScreen screen && screen.session().deviceId.equals(open.deviceId())) {
            screen.openFile(open.path());
        }
    }

    private static NetworkBackend backend(UUID deviceId) {
        return BACKENDS.computeIfAbsent(deviceId, NetworkBackend::new);
    }

    /** Sends the editor's requests to the server and remembers who is waiting for each answer. */
    private static final class NetworkBackend implements IdeBackend {
        private final UUID deviceId;
        private final Map<Integer, FileCallback> files = new HashMap<>();
        private final Map<Integer, ScratchCallback> scratches = new HashMap<>();
        private int nextId = 1;

        NetworkBackend(UUID deviceId) {
            this.deviceId = deviceId;
        }

        @Override
        public void file(byte op, String path, byte[] data, FileCallback callback) {
            int id = nextId++;
            files.put(id, callback);
            Qbotics.platform().sendToServer(new IdePayloads.FileRequest(deviceId, id, op, path, data));
        }

        @Override
        public void terminalInput(byte action, String line) {
            Qbotics.platform().sendToServer(new TerminalInputPayload(deviceId, action, line));
        }

        @Override
        public void runScratch(String code, ScratchCallback callback) {
            int id = nextId++;
            scratches.put(id, callback);
            Qbotics.platform().sendToServer(new IdePayloads.ScratchRun(deviceId, id, code));
        }
    }
}
