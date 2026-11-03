package com.goat.feurcalc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fait tourner le calculateur de dégâts de Pokémon Showdown (@smogon/calc, le même que Showdex)
 * dans le moteur JavaScript GraalJS que Cobblemon embarque déjà.
 *
 * Tout se passe sur un seul fil d'exécution en arrière-plan : le jeu ne se fige jamais.
 */
public final class CalcEngine {
    private static final Logger LOG = LoggerFactory.getLogger("Feurcalc");

    public enum Status { NOT_STARTED, LOADING, READY, FAILED }

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Feurcalc-JS");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    private static volatile Status status = Status.NOT_STARTED;
    private static volatile String error = "";
    private static volatile Set<String> knownItems = Collections.emptySet();
    private static volatile Set<String> knownMoves = Collections.emptySet();
    private static volatile Set<String> knownAbilities = Collections.emptySet();

    // Utilisés uniquement depuis le fil "Feurcalc-JS"
    private static Context context;
    private static Value calcFunction;

    private CalcEngine() {
    }

    public static Status status() {
        return status;
    }

    public static String error() {
        return error;
    }

    public static Set<String> knownItems() {
        return knownItems;
    }

    public static Set<String> knownMoves() {
        return knownMoves;
    }

    public static Set<String> knownAbilities() {
        return knownAbilities;
    }

    /** Lance le chargement (quelques secondes, en arrière-plan) si ce n'est pas déjà fait. */
    public static synchronized void warmUp() {
        if (status == Status.NOT_STARTED) {
            status = Status.LOADING;
            EXEC.execute(CalcEngine::load);
        }
    }

    private static void load() {
        long start = System.currentTimeMillis();
        try {
            Thread.currentThread().setContextClassLoader(CalcEngine.class.getClassLoader());
            String source;
            try (InputStream in = CalcEngine.class.getResourceAsStream("/assets/feurcalc/js/feurcalc.js")) {
                if (in == null) {
                    throw new IllegalStateException("feurcalc.js introuvable dans le .jar");
                }
                source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            context = Context.newBuilder("js")
                    .allowAllAccess(false)
                    .option("engine.WarnInterpreterOnly", "false")
                    .build();
            context.eval(Source.create("js", source));
            Value api = context.getBindings("js").getMember("feurcalc");
            calcFunction = api.getMember("calc");

            JsonObject lists = JsonParser.parseString(api.getMember("lists").execute().asString()).getAsJsonObject();
            knownItems = toSet(lists.getAsJsonArray("items"));
            knownMoves = toSet(lists.getAsJsonArray("moves"));
            knownAbilities = toSet(lists.getAsJsonArray("abilities"));

            status = Status.READY;
            LOG.info("Calculateur de dégâts prêt en {} ms", System.currentTimeMillis() - start);
        } catch (Throwable t) {
            status = Status.FAILED;
            error = t.getClass().getSimpleName() + ": " + t.getMessage();
            LOG.error("Impossible de démarrer le calculateur de dégâts", t);
        }
    }

    private static Set<String> toSet(JsonArray array) {
        Set<String> set = new HashSet<>();
        if (array != null) {
            for (JsonElement e : array) {
                set.add(e.getAsString());
            }
        }
        return Collections.unmodifiableSet(set);
    }

    /** Calcule en arrière-plan. Le résultat est le JSON renvoyé par feurcalc.calc (ou {"error": ...}). */
    public static CompletableFuture<JsonObject> calculate(String requestJson) {
        warmUp();
        return CompletableFuture.supplyAsync(() -> {
            if (status != Status.READY || calcFunction == null) {
                JsonObject err = new JsonObject();
                err.addProperty("error", status == Status.FAILED ? error : "not ready");
                return err;
            }
            String out = calcFunction.execute(requestJson).asString();
            return JsonParser.parseString(out).getAsJsonObject();
        }, EXEC);
    }
}
