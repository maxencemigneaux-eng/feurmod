package com.goat.cobblemonundo;

import com.goat.cobblemonundo.network.UndoRequestPayload;
import com.goat.cobblemonundo.network.UndoStatePayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CobblemonUndo implements ModInitializer {
    public static final String MOD_ID = "cobblemon_undo";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    @Override
    public void onInitialize() {
        // Les paquets doivent être déclarés des deux côtés (client et serveur)
        PayloadTypeRegistry.playC2S().register(UndoRequestPayload.TYPE, UndoRequestPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(UndoStatePayload.TYPE, UndoStatePayload.CODEC);

        UndoServer.init();
        LOGGER.info("Cobblemon Undo chargé : bouton Annuler disponible en combat PvP.");
    }
}
