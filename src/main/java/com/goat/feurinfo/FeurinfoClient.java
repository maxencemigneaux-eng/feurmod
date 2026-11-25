package com.goat.feurinfo;

import com.cobblemon.mod.common.pokemon.Species;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.concurrent.CompletableFuture;

/**
 * /feurwiki                          → ouvre l'interface avec la recherche
 * /feurwiki dracaufeu                 → ouvre directement la fiche (noms FR/EN ou numéro, avec autocomplétion)
 * /feurwiki export vol                → config/feurwiki-vol.csv : tous les Pokémon d'un type (Excel)
 *
 * Commande 100 % côté client : elle marche sur n'importe quel serveur, même sans le mod.
 */
public final class FeurinfoClient implements ClientModInitializer {
    public static final String MOD_ID = "feurinfo";

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> register(dispatcher));
        // Lit les données d'apparition (onglet Spawn) en arrière-plan dès qu'on entre dans un monde
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> SpawnIndex.ensureLoaded());
    }

    private static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        register(dispatcher, "feurwiki");
    }

    private static void register(CommandDispatcher<FabricClientCommandSource> dispatcher, String name) {
        dispatcher.register(ClientCommandManager.literal(name)
                .executes(context -> {
                    open(context.getSource().getClient(), null);
                    return 1;
                })
                .then(ClientCommandManager.literal("export")
                        .then(ClientCommandManager.argument("type", StringArgumentType.word())
                                .executes(context -> {
                                    String type = StringArgumentType.getString(context, "type");
                                    try {
                                        java.nio.file.Path file = Exporter.export(type);
                                        if (file == null) {
                                            context.getSource().sendError(Component.literal("Aucun Pokémon de type « " + type + " »"));
                                            return 0;
                                        }
                                        context.getSource().sendFeedback(Component.literal("§6[FeurWiki]§r Fiche écrite : ")
                                                .append(Component.literal("§n" + file.getFileName()).withStyle(s -> s.withClickEvent(
                                                        new net.minecraft.network.chat.ClickEvent(net.minecraft.network.chat.ClickEvent.Action.OPEN_FILE,
                                                                file.toAbsolutePath().toString()))))
                                                .append(Component.literal(" (dossier config, clique pour l'ouvrir)")));
                                        return 1;
                                    } catch (Exception e) {
                                        context.getSource().sendError(Component.literal("Export impossible : " + e.getMessage()));
                                        return 0;
                                    }
                                })))
                .then(ClientCommandManager.argument("pokemon", StringArgumentType.greedyString())
                        .suggests(FeurinfoClient::suggestSpecies)
                        .executes(context -> {
                            String query = StringArgumentType.getString(context, "pokemon");
                            Species species = SpeciesLookup.find(query);
                            if (species == null) {
                                context.getSource().sendError(Component.translatable("feurinfo.not_found", query));
                                return 0;
                            }
                            open(context.getSource().getClient(), species);
                            return 1;
                        })));
    }

    private static CompletableFuture<Suggestions> suggestSpecies(CommandContext<FabricClientCommandSource> context,
                                                                 SuggestionsBuilder builder) {
        String typed = SpeciesLookup.normalize(builder.getRemaining());
        for (Species species : SpeciesLookup.all()) {
            String name = SpeciesLookup.displayName(species);
            if (SpeciesLookup.normalize(name).startsWith(typed)) {
                builder.suggest(name);
            }
        }
        return builder.buildFuture();
    }

    /** On attend la fin de la commande (le chat se ferme) avant d'ouvrir l'écran. */
    private static void open(Minecraft client, Species species) {
        client.tell(() -> client.setScreen(new FeurinfoScreen(species)));
    }
}
