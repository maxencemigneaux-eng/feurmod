package com.goat.cobblemonundo.mixin;

import com.cobblemon.mod.common.client.battle.ClientBattle;
import com.goat.cobblemonundo.client.GraceHold;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * checkForFinishedChoosing() est la fonction de Cobblemon qui envoie tes choix au serveur
 * quand tu as fini de choisir. On s'y glisse pour retenir l'envoi jusqu'à ce que tu cliques sur Envoyer.
 * (remap = false : c'est une classe de Cobblemon, pas de Minecraft)
 */
@Mixin(value = ClientBattle.class, remap = false)
public abstract class ClientBattleMixin {
    @Inject(method = "checkForFinishedChoosing", at = @At("HEAD"), cancellable = true)
    private void cobblemonUndo$holdChoice(CallbackInfo ci) {
        if (GraceHold.interceptSubmit((ClientBattle) (Object) this)) {
            ci.cancel();
        }
    }
}
