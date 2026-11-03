package com.goat.cobblemonundo;

import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.battles.BattleRegistry;
import com.cobblemon.mod.common.battles.MoveActionResponse;
import com.cobblemon.mod.common.battles.PassActionResponse;
import com.cobblemon.mod.common.battles.ShiftActionResponse;
import com.cobblemon.mod.common.battles.ShowdownActionRequest;
import com.cobblemon.mod.common.battles.ShowdownActionResponse;
import com.cobblemon.mod.common.battles.SwitchActionResponse;
import com.cobblemon.mod.common.net.messages.client.battle.BattleMakeChoicePacket;
import com.cobblemon.mod.common.net.messages.client.battle.BattleQueueRequestPacket;
import com.goat.cobblemonundo.network.UndoRequestPayload;
import com.goat.cobblemonundo.network.UndoStatePayload;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Logique côté serveur.
 *
 * Comment Cobblemon gère un tour : quand un joueur valide ses actions, Cobblemon les garde
 * dans {@code actor.responses} et passe {@code actor.mustChoose} à false. Ce n'est que quand
 * TOUS les combattants ont choisi ({@code PokemonBattle.checkForInputDispatch}) que les choix
 * sont envoyés au moteur Showdown. Tant que l'adversaire n'a pas joué, on peut donc simplement
 * vider les réponses et redemander un choix au joueur, exactement comme Cobblemon le fait
 * lui-même quand un choix est invalide (BattleSelectActionsHandler).
 */
public final class UndoServer {
    /** Dernier état envoyé à chaque joueur, pour n'envoyer un paquet que quand ça change. */
    private static final Map<UUID, Boolean> LAST_SENT = new HashMap<>();

    private UndoServer() {
    }

    static void init() {
        ServerPlayNetworking.registerGlobalReceiver(UndoRequestPayload.TYPE,
                (payload, context) -> handleUndo(context.player()));

        ServerTickEvents.END_SERVER_TICK.register(UndoServer::tick);

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                LAST_SENT.remove(handler.getPlayer().getUUID()));
    }

    // ---------------------------------------------------------------------------------------

    private static void tick(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            sync(player);
        }
    }

    /** Envoie au client l'état "peut annuler" s'il a changé depuis le dernier envoi. */
    private static void sync(ServerPlayer player) {
        if (!ServerPlayNetworking.canSend(player, UndoStatePayload.TYPE)) {
            return; // le joueur n'a pas le mod côté client
        }
        boolean canUndo = canUndo(player);
        Boolean previous = LAST_SENT.put(player.getUUID(), canUndo);
        if (previous == null || previous != canUndo) {
            ServerPlayNetworking.send(player, new UndoStatePayload(canUndo));
        }
    }

    private static void handleUndo(ServerPlayer player) {
        PokemonBattle battle = BattleRegistry.getBattleByParticipatingPlayer(player);
        BattleActor actor = battle == null ? null : findActor(battle, player);

        if (battle == null || actor == null || !canUndo(battle, actor)) {
            player.displayClientMessage(
                    Component.translatable("cobblemon_undo.too_late").withStyle(ChatFormatting.RED), true);
            sync(player);
            return;
        }

        ShowdownActionRequest request = actor.getRequest();

        // 1) On oublie le choix (il n'a jamais été envoyé à Showdown)
        actor.getResponses().clear();
        // 2) Le joueur doit de nouveau choisir : le tour ne partira pas sans lui
        actor.setMustChoose(true);
        // 3) On renvoie la demande d'action au client, comme le fait Cobblemon après un choix invalide
        actor.sendUpdate(new BattleQueueRequestPacket(request));
        actor.sendUpdate(new BattleMakeChoicePacket());

        player.displayClientMessage(
                Component.translatable("cobblemon_undo.undone").withStyle(ChatFormatting.YELLOW), true);
        sync(player);
    }

    // ---------------------------------------------------------------------------------------

    static boolean canUndo(ServerPlayer player) {
        PokemonBattle battle = BattleRegistry.getBattleByParticipatingPlayer(player);
        if (battle == null) {
            return false;
        }
        BattleActor actor = findActor(battle, player);
        return actor != null && canUndo(battle, actor);
    }

    static boolean canUndo(PokemonBattle battle, BattleActor actor) {
        // Uniquement contre d'autres joueurs (contre un PNJ / sauvage, l'IA joue instantanément)
        if (battle.getEnded() || !battle.getStarted() || !battle.isPvP()) {
            return false;
        }
        // Une capture en cours bloque le tour : on ne touche à rien
        if (!battle.getCaptureActions().isEmpty()) {
            return false;
        }
        // Le joueur doit avoir déjà validé un choix qui n'est pas encore parti
        if (actor.getMustChoose() || actor.getRequest() == null || actor.getResponses().isEmpty()) {
            return false;
        }
        if (!actor.getExpectingPassActions().isEmpty()) {
            return false;
        }
        // Seuls les choix "gratuits" sont annulables. Un objet du sac est déjà consommé
        // au moment où on l'utilise : on n'autorise pas son annulation.
        for (ShowdownActionResponse response : actor.getResponses()) {
            if (!isUndoableResponse(response)) {
                return false;
            }
        }
        // Et il faut qu'au moins un autre combattant n'ait pas encore choisi
        for (BattleActor other : battle.getActors()) {
            if (other != actor && other.getMustChoose()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isUndoableResponse(ShowdownActionResponse response) {
        return response instanceof MoveActionResponse
                || response instanceof SwitchActionResponse
                || response instanceof PassActionResponse
                || response instanceof ShiftActionResponse;
    }

    private static BattleActor findActor(PokemonBattle battle, ServerPlayer player) {
        UUID uuid = player.getUUID();
        for (BattleActor actor : battle.getActors()) {
            for (UUID playerId : actor.getPlayerUUIDs()) {
                if (uuid.equals(playerId)) {
                    return actor;
                }
            }
        }
        return null;
    }
}
