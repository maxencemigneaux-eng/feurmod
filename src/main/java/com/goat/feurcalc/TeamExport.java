package com.goat.feurcalc;

import com.cobblemon.mod.common.pokemon.Gender;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;

import java.util.List;

/** Copie ton équipe au format Showdown : à coller sur pokepast.es (ou dans le teambuilder de Showdown). */
public final class TeamExport {
    private TeamExport() {}

    public static void copyParty() {
        Minecraft mc = Minecraft.getInstance();
        List<Pokemon> party = CalcState.party();
        if (party.isEmpty()) {
            toast(mc, Names.tr("feurcalc.export.empty"), "");
            return;
        }
        JsonArray team = new JsonArray();
        for (Pokemon p : party) {
            JsonObject r = MonState.fromPokemon(p).toRequest();
            r.addProperty("level", p.getLevel()); // le vrai niveau, pas le réglage « niveau 100 »
            r.addProperty("shiny", p.getShiny());
            r.addProperty("gender", p.getGender() == Gender.MALE ? "M" : p.getGender() == Gender.FEMALE ? "F" : "");
            if (p.getNickname() != null) {
                r.addProperty("nickname", p.getNickname().getString());
            }
            String en = p.getSpecies().getName();
            if (!p.getForm().getName().equalsIgnoreCase(p.getSpecies().getStandardForm().getName())) {
                en += "-" + p.getForm().getName();
            }
            r.addProperty("enName", en); // Pokémon custom du serveur, inconnus de Showdown
            team.add(r);
        }
        CalcEngine.paste(team.toString()).thenAccept(res -> mc.execute(() -> {
            if (!res.has("text")) {
                toast(mc, Names.tr("feurcalc.export.error"), res.has("error") ? res.get("error").getAsString() : "");
                return;
            }
            mc.keyboardHandler.setClipboard(res.get("text").getAsString());
            toast(mc, Names.tr("feurcalc.export.done", party.size()), Names.tr("feurcalc.export.where"));
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.literal("§6[Feurcalc]§r " + Names.tr("feurcalc.export.done", party.size()) + " → ")
                        .append(Component.literal("§npokepast.es").withStyle(s -> s.withClickEvent(
                                new ClickEvent(ClickEvent.Action.OPEN_URL, "https://pokepast.es/")))), false);
            }
        }));
    }

    private static void toast(Minecraft mc, String title, String body) {
        SystemToast.add(mc.getToasts(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                Component.literal(title), body.isEmpty() ? null : Component.literal(body));
    }
}
