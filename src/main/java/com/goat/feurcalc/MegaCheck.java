package com.goat.feurcalc;

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.api.pokemon.stats.Stat;
import com.cobblemon.mod.common.pokemon.FormData;
import com.cobblemon.mod.common.pokemon.Species;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** /feurcalc megas : les Méga du serveur dont les stats ne sont pas celles de Showdown → config/feurcalc-megas.txt. */
final class MegaCheck {
    private MegaCheck() {}

    static void run() {
        Minecraft mc = Minecraft.getInstance();
        JsonArray list = new JsonArray();
        for (Species sp : PokemonSpecies.getSpecies()) {
            for (FormData f : sp.getForms()) {
                if (!MonState.isMegaForm(f)) continue;
                JsonObject m = new JsonObject();
                m.addProperty("name", sp.getTranslatedName().getString() + " (" + f.getName() + ")");
                m.addProperty("id", f.showdownId());
                JsonObject stats = new JsonObject();
                Map<Stat, Integer> base = f.getBaseStats();
                for (int i = 0; i < 6; i++) {
                    stats.addProperty(Names.STAT_IDS[i], base.getOrDefault(Names.STATS[i], 0));
                }
                m.add("stats", stats);
                list.add(m);
            }
        }
        CalcEngine.megaCheck(list.toString()).thenAccept(res -> mc.execute(() -> report(mc, res)));
    }

    private static void report(Minecraft mc, JsonObject res) {
        if (mc.player == null) return;
        if (!res.has("diff")) {
            mc.player.displayClientMessage(Component.literal("§c[Feurcalc] Vérification impossible : "
                    + (res.has("error") ? res.get("error").getAsString() : "?")), false);
            return;
        }
        StringBuilder sb = new StringBuilder("Méga du serveur dont les stats diffèrent de Showdown (PV/Atq/Déf/AtqSpé/DéfSpé/Vit)\n\n");
        for (JsonElement e : res.getAsJsonArray("diff")) {
            JsonObject d = e.getAsJsonObject();
            sb.append(d.get("name").getAsString()).append(" = ").append(d.get("showdown").getAsString()).append('\n')
                    .append("  serveur  : ").append(line(d.getAsJsonObject("server"))).append('\n')
                    .append("  officiel : ").append(line(d.getAsJsonObject("official"))).append("\n\n");
        }
        sb.append("Inconnues de Showdown (Méga custom, stats du serveur gardées) :\n");
        for (JsonElement e : res.getAsJsonArray("unknown")) {
            sb.append("  ").append(e.getAsString()).append('\n');
        }
        int diff = res.getAsJsonArray("diff").size();
        try {
            Path file = mc.gameDirectory.toPath().resolve("config").resolve("feurcalc-megas.txt");
            Files.createDirectories(file.getParent());
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
            mc.player.displayClientMessage(Component.literal("§6[Feurcalc]§r " + diff + " Méga avec des stats différentes de Showdown, "
                    + res.get("same").getAsInt() + " identiques, " + res.getAsJsonArray("unknown").size() + " custom → ")
                    .append(Component.literal("§nfeurcalc-megas.txt").withStyle(s -> s.withClickEvent(
                            new ClickEvent(ClickEvent.Action.OPEN_FILE, file.toAbsolutePath().toString())))), false);
        } catch (Exception ex) {
            mc.player.displayClientMessage(Component.literal("§c[Feurcalc] Écriture impossible : " + ex.getMessage()), false);
        }
    }

    private static String line(JsonObject s) {
        StringBuilder b = new StringBuilder();
        for (String id : Names.STAT_IDS) {
            if (b.length() > 0) b.append('/');
            b.append(s.has(id) ? s.get(id).getAsInt() : 0);
        }
        return b.toString();
    }
}
