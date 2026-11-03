package com.goat.cobblemonundo.network;

import com.goat.cobblemonundo.CobblemonUndo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Serveur -> client : indique si le joueur peut annuler son choix en ce moment
 * (il a déjà choisi, mais l'adversaire n'a pas encore joué).
 */
public record UndoStatePayload(boolean canUndo) implements CustomPacketPayload {
    public static final Type<UndoStatePayload> TYPE = new Type<>(CobblemonUndo.id("undo_state"));

    public static final StreamCodec<RegistryFriendlyByteBuf, UndoStatePayload> CODEC =
            StreamCodec.composite(ByteBufCodecs.BOOL, UndoStatePayload::canUndo, UndoStatePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
