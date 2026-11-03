package com.goat.cobblemonundo.client;

import com.cobblemon.mod.common.api.battles.model.actor.ActorType;
import com.cobblemon.mod.common.battles.MoveActionResponse;
import com.cobblemon.mod.common.battles.PassActionResponse;
import com.cobblemon.mod.common.battles.ShiftActionResponse;
import com.cobblemon.mod.common.battles.ShowdownActionResponse;
import com.cobblemon.mod.common.battles.SwitchActionResponse;
import com.cobblemon.mod.common.client.CobblemonClient;
import com.cobblemon.mod.common.client.battle.ClientBattle;
import com.cobblemon.mod.common.client.battle.ClientBattleActor;
import com.cobblemon.mod.common.client.battle.ClientBattleSide;
import com.cobblemon.mod.common.client.battle.SingleActionRequest;
import com.goat.cobblemonundo.network.UndoRequestPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import java.util.UUID;

/**
 * Mode 100 % client (quand le serveur n'a PAS le mod).
 *
 * Sans le mod côté serveur, un choix envoyé ne peut plus être repris : le serveur Cobblemon refuse
 * tout nouveau choix tant que le tour n'est pas joué. On fait donc l'inverse : quand tu valides,
 * le choix est mis en attente sur ton PC avec deux boutons, Envoyer et Annuler. Rien ne part tant
 * que tu n'as pas cliqué sur Envoyer ; à ce moment le code de Cobblemon l'envoie normalement.
 */
public final class GraceHold {
    private static UUID heldBattleId;
    /** Vrai pendant qu'on rappelle nous-mêmes checkForFinishedChoosing() pour envoyer pour de vrai. */
    private static boolean sending;

    private GraceHold() {
    }

    /**
     * Appelé (via le mixin) au début de ClientBattle.checkForFinishedChoosing().
     * @return true si on met le choix en attente (Cobblemon ne l'envoie pas tout de suite)
     */
    public static boolean interceptSubmit(ClientBattle battle) {
        if (sending || battle.getSpectating()) {
            return false;
        }
        if (battle.getFirstUnansweredRequest() != null) {
            return false; // pas encore fini de choisir (combat double)
        }
        boolean pvp = isPvP(battle);
        if (pvp && ClientPlayNetworking.canSend(UndoRequestPayload.TYPE)) {
            return false; // le serveur a le mod : annulation façon Showdown, pas besoin d'attente
        }
        UndoConfig config = UndoConfig.get();
        if (!config.confirmerAvantEnvoi) {
            return false;
        }
        if (!pvp && !config.aussiContrePnjEtSauvages) {
            return false;
        }

        // On ne retient que les vrais choix (attaque / switch). Un objet du sac ou une capture
        // est déjà validé par le serveur (ForcePassActionResponse) : on laisse partir tout de suite.
        boolean hasRealChoice = false;
        for (SingleActionRequest request : battle.getPendingActionRequests()) {
            ShowdownActionResponse response = request.getResponse();
            if (response instanceof MoveActionResponse
                    || response instanceof SwitchActionResponse
                    || response instanceof ShiftActionResponse) {
                hasRealChoice = true;
            } else if (!(response instanceof PassActionResponse)) {
                return false;
            }
        }
        if (!hasRealChoice) {
            return false;
        }

        heldBattleId = battle.getBattleId();
        battle.setMustChoose(false); // masque le menu de choix pendant l'attente
        return true;
    }

    public static boolean isHolding() {
        return heldBattle() != null;
    }

    /** Bouton "Envoyer" (ou Entrée). */
    public static void sendNow() {
        ClientBattle battle = heldBattle();
        heldBattleId = null;
        if (battle == null) {
            return;
        }
        sending = true;
        try {
            battle.checkForFinishedChoosing(); // le code de Cobblemon envoie le paquet normalement
        } finally {
            sending = false;
        }
    }

    /** Bouton "Annuler" (ou Retour arrière) : on efface les choix et le menu de Cobblemon réapparaît. */
    public static void cancel() {
        ClientBattle battle = heldBattle();
        heldBattleId = null;
        if (battle == null) {
            return;
        }
        // cancelLastAnsweredRequest() est la fonction "retour" de Cobblemon 1.8 :
        // elle gère aussi la Téracristal / Méga / Dynamax choisie ce tour-ci.
        int guard = battle.getPendingActionRequests().size() + 1;
        while (battle.getLastAnsweredRequest() != null && guard-- > 0) {
            battle.cancelLastAnsweredRequest();
        }
        // Filet de sécurité
        for (SingleActionRequest request : battle.getPendingActionRequests()) {
            ShowdownActionResponse response = request.getResponse();
            if (response != null && !(response instanceof PassActionResponse)) {
                request.setResponse(null);
            }
        }
        battle.setMustChoose(true); // BattleGUI réaffiche Attaque / Sac / Pokémon / Fuite
    }

    static void reset() {
        heldBattleId = null;
        sending = false;
    }

    /** Le combat concerné par l'attente, ou null si l'attente n'a plus de sens. */
    private static ClientBattle heldBattle() {
        UUID id = heldBattleId;
        if (id == null) {
            return null;
        }
        ClientBattle battle = CobblemonClient.INSTANCE.getBattle();
        if (battle == null
                || !id.equals(battle.getBattleId())
                || battle.getMustChoose()
                || battle.getFirstUnansweredRequest() != null) {
            heldBattleId = null;
            return null;
        }
        return battle;
    }

    private static boolean isPvP(ClientBattle battle) {
        for (ClientBattleSide side : battle.getSides()) {
            for (ClientBattleActor actor : side.getActors()) {
                if (actor.getType() != ActorType.PLAYER) {
                    return false;
                }
            }
        }
        return true;
    }
}
