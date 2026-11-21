package com.goat.feurmod;

import com.goat.feurmod.pcsearch.PcSearch;
import com.goat.feurmod.pcsearch.PcSearchBar;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.util.List;

/** Fonctions communes de Feurmod : recherche dans le PC (+ /feursearch). */
public final class FeurmodClient implements ClientModInitializer {

    private static final int MAX_LINES = 15;

    @Override
    public void onInitializeClient() {
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> PcSearchBar.attach(screen));

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(ClientCommandManager.literal("feursearch")
                .then(ClientCommandManager.argument("recherche", StringArgumentType.greedyString())
                    .executes(ctx -> {
                        run(ctx.getSource(), StringArgumentType.getString(ctx, "recherche"));
                        return 1;
                    }))));
    }

    private static void run(FabricClientCommandSource source, String query) {
        List<PcSearch.Hit> hits = PcSearch.findEverywhere(query);
        source.sendFeedback(Component.literal("Recherche « " + query + " » : " + hits.size() + " résultat(s)")
            .withStyle(ChatFormatting.GOLD));
        for (int i = 0; i < Math.min(hits.size(), MAX_LINES); i++) {
            source.sendFeedback(PcSearch.describe(hits.get(i)));
        }
        if (hits.size() > MAX_LINES) {
            source.sendFeedback(Component.literal("… et " + (hits.size() - MAX_LINES) + " autre(s). Précise la recherche.")
                .withStyle(ChatFormatting.GRAY));
        }
        if (!PcSearch.knowsAnyPc()) {
            source.sendFeedback(Component.literal("Ton PC n'est pas encore connu : ouvre-le une fois pour qu'il soit inclus.")
                .withStyle(ChatFormatting.GRAY));
        }
    }
}
