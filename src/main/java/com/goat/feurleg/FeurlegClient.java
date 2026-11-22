package com.goat.feurleg;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class FeurlegClient implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("feurleg");

    private static KeyMapping toggleKey;
    private static KeyMapping dumpKey;

    @Override
    public void onInitializeClient() {
        FeurlegConfig.load();

        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.feurleg.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, "key.categories.feurmod"));
        dumpKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.feurleg.dump", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F8, "key.categories.feurmod"));

        // Touche de debug utilisable pendant qu'un menu est ouvert.
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) ->
            ScreenKeyboardEvents.afterKeyPress(screen).register((s, key, scancode, modifiers) -> {
                if (dumpKey.matches(key, scancode)) TextCapture.requestDump();
            }));

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            ServerData info = client.getCurrentServer();
            String key = info != null ? info.ip.toLowerCase() : "solo";
            client.execute(() -> {
                TimerStore.get().onJoin(key);
                ZoneClock.onJoin(key);
                ZonePeriods.onJoin(key);
            });
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
            client.execute(() -> {
                TimerStore.get().onLeave();
                ZoneClock.onLeave();
                ZonePeriods.onLeave();
            }));

        ClientTickEvents.END_CLIENT_TICK.register(FeurlegClient::tick);
        HudRenderCallback.EVENT.register(TimerHud::render);
    }

    private static void tick(Minecraft client) {
        FeurlegConfig cfg = FeurlegConfig.get();
        while (toggleKey.consumeClick()) {
            cfg.enabled = !cfg.enabled;
            FeurlegConfig.save();
            if (client.player != null) {
                client.player.displayClientMessage(Component.literal(cfg.enabled
                    ? "§6[Feurleg]§r Timers affichés" : "§6[Feurleg]§r Timers cachés"), true);
            }
        }
        if (client.player == null) return;
        ZoneClock.tick(client);
        ZonePeriods.tickSave();

        for (TimerStore.Timer t : TimerStore.get().list()) {
            long left = t.remainingMillis();
            if (cfg.warnSeconds > 0 && !t.warned && left <= cfg.warnSeconds * 1000L && left > 0) {
                t.warned = true;
                alert(client, "§e" + t.name, "§fLégendaire dans " + TimerHud.format(left) + " !", 1.2f);
            }
            if (!t.alerted && left <= 0) {
                t.alerted = true;
                t.warned = true;
                alert(client, "§a" + t.name, "§fTentative de spawn légendaire !", 1.5f);
            }
        }
    }

    /** Gros message au milieu de l'écran (titre + sous-titre). */
    private static void alert(Minecraft client, String title, String subtitle, float pitch) {
        FeurlegConfig cfg = FeurlegConfig.get();
        if (cfg.alertMessage) {
            client.gui.setTimes(5, 50, 15);
            client.gui.setSubtitle(Component.literal(subtitle));
            client.gui.setTitle(Component.literal(title));
        }
        if (cfg.alertSound) {
            playAlertSound(client, pitch);
        }
    }

    private static final net.minecraft.sounds.SoundEvent DAMN_LES_GENS = net.minecraft.sounds.SoundEvent.createVariableRangeEvent(
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("feurleg", "alert.damn_les_gens"));

    /**
     * Le son choisi dans les réglages, au volume réglé (0 à 300 %). Minecraft bloque chaque son à 100 % :
     * au-dessus, on joue le même son 2 ou 3 fois en même temps pour qu'il soit plus fort.
     */
    public static void playAlertSound(Minecraft client, float pitch) {
        FeurlegConfig cfg = FeurlegConfig.get();
        net.minecraft.sounds.SoundEvent sound;
        float p = 1.0f;
        if ("damn".equals(cfg.alertSoundChoice)) {
            sound = DAMN_LES_GENS;
        } else {
            sound = SoundEvents.NOTE_BLOCK_BELL.value();
            p = pitch;
        }
        float volume = Math.max(0, Math.min(300, cfg.alertVolume)) / 100f;
        if (volume <= 0) {
            return;
        }
        int copies = (int) Math.ceil(volume);
        float each = volume / copies;
        for (int i = 0; i < copies; i++) {
            client.getSoundManager().play(SimpleSoundInstance.forUI(sound, p, each));
        }
    }
}
