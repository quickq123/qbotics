package qbotics.neoforge;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import qbotics.Qbotics;
import qbotics.client.ide.ClientIde;
import qbotics.net.IdePayloads;
import qbotics.net.TerminalStatePayload;

/** Client-only setup. Never loaded on a dedicated server. */
@Mod(value = Qbotics.MOD_ID, dist = Dist.CLIENT)
public final class QboticsNeoForgeClient {
    public QboticsNeoForgeClient(IEventBus modBus) {
        modBus.addListener((net.neoforged.neoforge.client.event.RegisterMenuScreensEvent event) ->
                event.register(QboticsNeoForge.INPUT_MENU.get(), qbotics.client.InputScreen::new));
        modBus.addListener((RegisterClientPayloadHandlersEvent event) -> {
            event.register(TerminalStatePayload.TYPE, (payload, context) -> ClientIde.onTerminalState(payload));
            event.register(IdePayloads.FileResponse.TYPE, (payload, context) -> ClientIde.onFileResponse(payload));
            event.register(IdePayloads.ScratchResult.TYPE, (payload, context) -> ClientIde.onScratchResult(payload));
            event.register(IdePayloads.OpenFile.TYPE, (payload, context) -> ClientIde.onOpenFile(payload));
        });
    }
}
