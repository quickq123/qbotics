package qbotics.neoforge;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import qbotics.Qbotics;
import qbotics.client.TerminalScreen;
import qbotics.net.TerminalStatePayload;

/** Client-only setup. Never loaded on a dedicated server. */
@Mod(value = Qbotics.MOD_ID, dist = Dist.CLIENT)
public final class QboticsNeoForgeClient {
    public QboticsNeoForgeClient(IEventBus modBus) {
        modBus.addListener((RegisterClientPayloadHandlersEvent event) ->
                event.register(TerminalStatePayload.TYPE, (payload, context) -> TerminalScreen.onState(payload)));
    }
}
