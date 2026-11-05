package com.goat.cobblemonundo.mixin;

import com.cobblemon.mod.common.net.messages.client.battle.BattleMessagePacket;
import com.goat.feurcalc.BattleLog;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Feurcalc : chaque paquet de messages de combat passe par ce constructeur (à la réception sur un serveur,
 * à la création en solo). On garde une copie des messages pour savoir ce que l'adversaire a révélé
 * (objet, talent, attaques, Téra). Le descripteur ne contient que java.util.List : rien à traduire (remap = false).
 */
@Mixin(value = BattleMessagePacket.class, remap = false)
public abstract class BattleMessagePacketMixin {
    @Inject(method = "<init>(Ljava/util/List;)V", at = @At("RETURN"), require = 0)
    private void feurcalc$capture(List<?> messages, CallbackInfo ci) {
        try {
            BattleLog.capture(((BattleMessagePacket) (Object) this).getMessages());
        } catch (Throwable ignored) {
            // jamais bloquer le combat
        }
    }
}
