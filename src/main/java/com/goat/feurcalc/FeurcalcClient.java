package com.goat.feurcalc;

import com.cobblemon.mod.common.client.CobblemonClient;
import com.cobblemon.mod.common.client.battle.ClientBattle;
import com.cobblemon.mod.common.client.gui.battle.BattleGUI;
import com.cobblemon.mod.common.pokemon.FormData;
import com.cobblemon.mod.common.pokemon.Species;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.concurrent.CompletableFuture;

/**
 * Feurcalc : calculateur de dégâts façon Showdex pour Cobblemon.
 *
 * - En combat : bouton "Feurcalc" en haut de l'écran + résumé des dégâts ; touche J pour ouvrir le calculateur.
 * - Partout : /feurcalc [pokémon] ou la touche J.
 *
 * 100 % côté client : marche sur n'importe quel serveur, même si personne d'autre n'a le mod.
 */
public final class FeurcalcClient implements ClientModInitializer {
    public static final String MOD_ID = "feurcalc";

    @Nullable public static KeyMapping openKey;
    private static int tickCounter;

    @Override
    public void onInitializeClient() {
        openKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.feurcalc.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, "key.categories.feurmod"));

        // Le calculateur met quelques secondes à se charger : on le prépare dès le lancement, en arrière-plan
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            CalcEngine.warmUp();
            SmogonSets.load();
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openKey.consumeClick()) {
                if (client.screen == null && client.player != null) {
                    openScreen(client, null);
                }
            }
            ClientBattle current;
            try {
                current = CobblemonClient.INSTANCE.getBattle();
            } catch (Throwable t) {
                current = null;
            }
            BattleLog.process(current); // ce que l'adversaire a révélé (objet, talent, attaques...)
            boolean battle = current != null;
            if (battle || client.screen instanceof FeurcalcScreen) {
                CalcState.INSTANCE.tick();
            }
            TeamMatchups.tick(tickCounter);
            if (++tickCounter % 200 == 0) {
                SavedSets.saveIfNeeded();
            }
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> registerCommand(dispatcher));

        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (screen instanceof BattleGUI) {
                BattleHud.attach(screen);
            }
        });
    }

    public static void openScreen(Minecraft client, @Nullable Screen parent) {
        client.setScreen(new FeurcalcScreen(parent));
    }

    /**
     * Ouvre le calculateur avec ce Pokémon en face (côté adversaire), avec ton spread retenu pour lui
     * ou, à défaut, son set le plus joué. Utilisé par /feurcalc <pokémon> et le bouton de /feurinfo.
     */
    public static void openWith(Minecraft client, Species species, FormData form, @Nullable Screen parent) {
        CalcState state = CalcState.INSTANCE;
        state.fillFromParty();
        int level = state.a.isEmpty() ? 50 : state.a.level;
        MonState opponent = MonState.fromForm(species, form, level);
        if (!SavedSets.apply(opponent)) {
            opponent.autoSet = true; // le set le plus joué, dès que les sets sont chargés
        }
        state.b.copyFrom(opponent);
        openScreen(client, parent);
    }

    private static void registerCommand(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(ClientCommandManager.literal("feurcalc")
                .executes(context -> {
                    Minecraft client = context.getSource().getClient();
                    client.tell(() -> openScreen(client, null));
                    return 1;
                })
                .then(ClientCommandManager.argument("pokemon", StringArgumentType.greedyString())
                        .suggests(FeurcalcClient::suggestSpecies)
                        .executes(context -> {
                            String query = StringArgumentType.getString(context, "pokemon");
                            Species species = SpeciesLookup.find(query);
                            if (species == null) {
                                context.getSource().sendError(Component.translatable("feurcalc.not_found", query));
                                return 0;
                            }
                            Minecraft client = context.getSource().getClient();
                            client.tell(() -> openWith(client, species, species.getStandardForm(), null));
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
}
