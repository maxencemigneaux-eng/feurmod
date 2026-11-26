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
            String text = res.get("text").getAsString();
            mc.keyboardHandler.setClipboard(text); // au cas où l'envoi sur pokepast.es échoue : le texte est déjà copié
            String author = mc.player != null ? mc.player.getGameProfile().getName() : "";
            upload(text, author).thenAccept(url -> mc.execute(() -> {
                if (url == null) {
                    toast(mc, Names.tr("feurcalc.export.done", party.size()), Names.tr("feurcalc.export.where"));
                    return;
                }
                mc.keyboardHandler.setClipboard(url);
                toast(mc, Names.tr("feurcalc.export.link"), url);
                if (mc.player != null) {
                    mc.player.displayClientMessage(Component.literal("§6[Feurcalc]§r " + Names.tr("feurcalc.export.link") + " : ")
                            .append(Component.literal("§n" + url).withStyle(s -> s.withClickEvent(
                                    new ClickEvent(ClickEvent.Action.OPEN_URL, url)))), false);
                }
            }));
        }));
    }

    /** Crée le paste sur pokepast.es (comme le bouton « Upload to PokePaste » de Showdown) : son lien, ou null. */
    private static java.util.concurrent.CompletableFuture<String> upload(String text, String author) {
        String body = form("title", Names.tr("feurcalc.export.title", author)) + "&" + form("paste", text)
                + "&" + form("author", author) + "&" + form("notes", "Feurmod");
        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(java.net.URI.create("https://pokepast.es/create"))
                .timeout(java.time.Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                .build();
        return java.net.http.HttpClient.newBuilder()
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                .connectTimeout(java.time.Duration.ofSeconds(10))
                .build()
                .sendAsync(req, java.net.http.HttpResponse.BodyHandlers.discarding())
                .thenApply(r -> {
                    String loc = r.headers().firstValue("location").orElse(null);
                    if (r.statusCode() / 100 != 3 || loc == null || loc.isBlank() || loc.equals("/")) {
                        return null;
                    }
                    return loc.startsWith("http") ? loc : "https://pokepast.es" + (loc.startsWith("/") ? "" : "/") + loc;
                })
                .exceptionally(e -> null);
    }

    private static String form(String key, String value) {
        return key + "=" + java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void toast(Minecraft mc, String title, String body) {
        SystemToast.add(mc.getToasts(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                Component.literal(title), body.isEmpty() ? null : Component.literal(body));
    }
}
