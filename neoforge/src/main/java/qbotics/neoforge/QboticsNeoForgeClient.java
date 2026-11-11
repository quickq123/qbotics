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
        // The probe's Slots mode: numbers on container screens, and sneak + scroll to change mode.
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ContainerScreenEvent.Render.Foreground event) ->
                qbotics.client.SlotLabels.draw(event.getContainerScreen(), event.getGuiGraphics(),
                        event.getContainerScreen().getImageWidth(), event.getContainerScreen().getTopPos()));
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.InputEvent.MouseScrollingEvent event) -> {
            if (qbotics.client.SlotLabels.scrolled(event.getScrollDeltaY())) {
                event.setCanceled(true);
            }
        });
        // With a probe in hand, item tooltips show the name programs use; Ctrl+C copies it (see ItemNames).
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.entity.player.ItemTooltipEvent event) ->
                qbotics.client.ItemNames.tooltip(event.getItemStack(), event.getToolTip()));
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ScreenEvent.KeyPressed.Pre event) ->
                qbotics.client.ItemNames.keyPressed(event.getKeyCode()));
        // The cable's model built in code: its plates come from its block entity (see CableModels).
        modBus.addListener(CableModels::register);
        modBus.addListener(CableModels::wrap);
        // A plate's ring is coloured by what it carries (see PlateTints).
        modBus.addListener((net.neoforged.neoforge.client.event.RegisterColorHandlersEvent.BlockTintSources event) ->
                event.register(qbotics.client.PlateTints.sources(), QboticsNeoForge.cableBlock()));
        // A development aid: see ScreenshotRun. Off unless the game was started for it.
        if (qbotics.client.shots.ScreenshotRun.ENABLED) {
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ClientTickEvent.Post event) ->
                    qbotics.client.shots.ScreenshotRun.tick(net.minecraft.client.Minecraft.getInstance()));
        }
        // A development aid: see PlaytestStart. Off unless the game was started for it.
        if (qbotics.client.shots.PlaytestStart.ENABLED) {
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ClientTickEvent.Post event) ->
                    qbotics.client.shots.PlaytestStart.tick(net.minecraft.client.Minecraft.getInstance()));
        }
        modBus.addListener((net.neoforged.neoforge.client.event.RegisterMenuScreensEvent event) -> {
            event.register(QboticsNeoForge.INTERFACE_MENU.get(), qbotics.client.InterfaceScreen::new);
            event.register(QboticsNeoForge.FLUID_INTERFACE_MENU.get(), qbotics.client.FluidInterfaceScreen::new);
            event.register(QboticsNeoForge.ENERGY_INTERFACE_MENU.get(), qbotics.client.EnergyInterfaceScreen::new);
            event.register(QboticsNeoForge.DRIVE_BAY_MENU.get(), qbotics.client.DriveBayScreen::new);
        });
        modBus.addListener((RegisterClientPayloadHandlersEvent event) -> {
            event.register(TerminalStatePayload.TYPE, (payload, context) -> ClientIde.onTerminalState(payload));
            event.register(IdePayloads.FileResponse.TYPE, (payload, context) -> ClientIde.onFileResponse(payload));
            event.register(IdePayloads.ScratchResult.TYPE, (payload, context) -> ClientIde.onScratchResult(payload));
            event.register(IdePayloads.OpenFile.TYPE, (payload, context) -> ClientIde.onOpenFile(payload));
            event.register(qbotics.net.ProbePayloads.Info.TYPE, (payload, context) -> qbotics.client.ProbeScreen.show(payload));
            event.register(qbotics.net.XpPayloads.Info.TYPE, (payload, context) -> qbotics.client.XpScreen.show(payload));
        });
    }
}
