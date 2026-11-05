package com.goat.feurcalc;

import com.cobblemon.mod.common.pokemon.Pokemon;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * "Qui envoyer ?" : pour chaque Pokémon de ton équipe, sa meilleure attaque contre l'adversaire (côté B)
 * et la meilleure attaque de l'adversaire contre lui. Calculé seulement quand c'est affiché
 * (menu des switchs en combat, ou panneau "Équipe" du calculateur).
 */
public final class TeamMatchups {
    public record Row(Pokemon pokemon, MonState mon, boolean active, boolean fainted,
                      @Nullable JsonObject mine, @Nullable JsonObject theirs, int speed) {
        /** Note "à envoyer" : ce que tu fais moins ce que tu prends (+ bonus si tu es plus rapide). */
        public double score(int foeSpeed) {
            double dealt = Math.min(100, CalcState.getDouble(mine, "maxPct", 0));
            double taken = Math.min(100, CalcState.getDouble(theirs, "maxPct", 0));
            return dealt - taken + (speed > foeSpeed ? 15 : 0);
        }
    }

    private static int wantedUntil = -1;
    private static int now;
    @Nullable private static String lastRequest;
    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static volatile List<Row> rows = List.of();
    private static volatile int foeSpeed;
    private static volatile String foeName = "";
    private static volatile boolean calculating;

    private TeamMatchups() {
    }

    /** L'affichage en a besoin : on (re)calcule pendant la seconde qui vient. */
    public static void want() {
        wantedUntil = now + 20;
    }

    static void tick(int tick) {
        now = tick;
        if (tick <= wantedUntil && tick % 4 == 0) {
            update();
        }
    }

    public static List<Row> rows() {
        return rows;
    }

    public static int foeSpeed() {
        return foeSpeed;
    }

    public static String foeName() {
        return foeName;
    }

    public static boolean calculating() {
        return calculating;
    }

    /** Le meilleur choix (pas déjà en jeu, pas K.O.), ou -1. */
    public static int bestIndex(List<Row> list) {
        int best = -1;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < list.size(); i++) {
            Row row = list.get(i);
            if (row.active() || row.fainted() || row.mine() == null) {
                continue;
            }
            double score = row.score(foeSpeed);
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    private static void update() {
        CalcState state = CalcState.INSTANCE;
        List<Pokemon> team = state.team();
        if (state.b.isEmpty() || team.isEmpty()) {
            rows = List.of();
            lastRequest = null;
            return;
        }
        List<MonState> mons = new ArrayList<>();
        List<Boolean> active = new ArrayList<>();
        JsonArray list = new JsonArray();
        for (Pokemon pokemon : team) {
            MonState m = MonState.fromPokemon(pokemon);
            mons.add(m);
            active.add(state.isActive(m.uuid));
            list.add(m.toRequest());
        }
        JsonObject q = new JsonObject();
        q.add("team", list);
        q.add("foe", state.b.toRequest());
        q.add("field", state.field.toRequest());
        String json = q.toString();
        if (json.equals(lastRequest)) {
            return;
        }
        CalcEngine.warmUp();
        if (CalcEngine.status() != CalcEngine.Status.READY) {
            return;
        }
        lastRequest = json;
        calculating = true;
        int id = SEQUENCE.incrementAndGet();
        List<Pokemon> members = List.copyOf(team);
        String name = state.b.displayName.isEmpty() ? state.b.species.getTranslatedName().getString() : state.b.displayName;
        CalcEngine.matchups(json).whenComplete((r, t) -> {
            if (id != SEQUENCE.get()) {
                return; // un calcul plus récent est parti
            }
            calculating = false;
            if (t != null || r == null || r.has("error") || !r.has("rows")) {
                rows = List.of();
                return;
            }
            JsonArray out = r.getAsJsonArray("rows");
            List<Row> result = new ArrayList<>();
            for (int i = 0; i < mons.size(); i++) {
                JsonObject row = i < out.size() && out.get(i).isJsonObject() ? out.get(i).getAsJsonObject() : new JsonObject();
                MonState m = mons.get(i);
                result.add(new Row(members.get(i), m, active.get(i), m.hpPercent <= 0, object(row, "mine"),
                        object(row, "theirs"), CalcState.getInt(row, "speed", 0)));
            }
            foeSpeed = CalcState.getInt(r, "foeSpeed", 0);
            foeName = name;
            rows = List.copyOf(result);
        });
    }

    @Nullable
    private static JsonObject object(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }
}
