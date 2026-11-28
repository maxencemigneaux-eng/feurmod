package com.goat.feurmod;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** À la connexion (une fois par lancement) : s'il y a une version plus récente sur GitHub, lien de téléchargement dans le chat. */
public final class UpdateCheck {
    private static final String REPO = "https://github.com/maxzit0/feurmod";
    private static final String STATUS = "https://raw.githubusercontent.com/maxzit0/feurmod/main/telechargement/statut.txt";
    private static final Pattern VERSION = Pattern.compile("Feurmod (\\d+(?:\\.\\d+)+)");
    private static boolean done;
    /** Lien du dernier .jar trouvé : ouvert par /feurmod maj, jamais écrit dans le jeu (il contient le nom du compte GitHub). */
    private static String downloadUrl;

    private UpdateCheck() {}

    public static void onJoin() {
        if (done || !FeurmodConfig.get().checkUpdates) {
            return;
        }
        done = true;
        String installed = installedVersion();
        HttpRequest req = HttpRequest.newBuilder(URI.create(STATUS)).timeout(Duration.ofSeconds(10)).GET().build();
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL) // marche encore si le compte GitHub change de nom
                .build()
                .sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenAccept(r -> {
                    if (r.statusCode() != 200) return;
                    String[] lines = r.body().split("\\R");
                    Matcher m = VERSION.matcher(lines[0]);
                    if (!m.find() || lines.length < 2 || !lines[1].trim().endsWith(".jar")) return;
                    String latest = m.group(1);
                    if (compare(latest, installed) <= 0) return;
                    downloadUrl = REPO + "/raw/main/telechargement/" + URLEncoder.encode(lines[1].trim(), StandardCharsets.UTF_8);
                    Minecraft mc = Minecraft.getInstance();
                    mc.execute(() -> {
                        if (mc.player == null) return;
                        // Clic → /feurmod maj, qui ouvre le navigateur directement : l'adresse n'apparaît ni au survol
                        // ni dans l'écran de confirmation de Minecraft
                        mc.player.displayClientMessage(Component.literal("§6[Feurmod]§r Nouvelle version §a" + latest
                                + "§r (tu as " + installed + ") : ").append(Component.literal("§ntélécharger").withStyle(s -> s
                                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/feurmod maj"))
                                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                        Component.literal("Ouvre le téléchargement de la " + latest + " dans ton navigateur"))))), false);
                    });
                })
                .exceptionally(e -> null); // pas d'internet / GitHub en panne : on ne dit rien
    }

    /** /feurmod maj : ouvre le téléchargement de la nouvelle version, s'il y en a une. */
    public static boolean openDownload() {
        Minecraft mc = Minecraft.getInstance();
        if (downloadUrl == null) {
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.literal("§6[Feurmod]§r Pas de nouvelle version (tu as " + installedVersion() + ")"), false);
            }
            return false;
        }
        net.minecraft.Util.getPlatform().openUri(downloadUrl);
        return true;
    }

    static String installedVersion() {
        return FabricLoader.getInstance().getModContainer("feurmod")
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("0").split("\\+")[0];
    }

    /** Compare "1.0.56" et "1.0.9" chiffre par chiffre (1.0.56 est plus récent). */
    static int compare(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? parse(x[i]) : 0, q = i < y.length ? parse(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s.replaceAll("\\D.*", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
