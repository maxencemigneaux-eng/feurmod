package com.goat.cobblemonundo.network;

import com.goat.cobblemonundo.CobblemonUndo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client -> serveur : "j'annule mon choix pour ce tour". Aucun contenu. */
public record UndoRequestPayload() implements CustomPacketPayload {
    public static final UndoRequestPayload INSTANCE = new UndoRequestPayload();

    public static final Type<UndoRequestPayload> TYPE = new Type<>(CobblemonUndo.id("undo_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, UndoRequestPayload> CODEC = StreamCodec.unit(INSTANCE);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
