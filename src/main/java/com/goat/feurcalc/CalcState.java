package com.goat.feurcalc;

import com.cobblemon.mod.common.client.CobblemonClient;
import com.cobblemon.mod.common.client.battle.ActiveClientBattlePokemon;
import com.cobblemon.mod.common.client.battle.ClientBattle;
import com.cobblemon.mod.common.client.battle.ClientBattleActor;
import com.cobblemon.mod.common.client.battle.ClientBattlePokemon;
import com.cobblemon.mod.common.client.battle.ClientBattleSide;
import com.cobblemon.mod.common.client.storage.ClientParty;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * L'état du calculateur, partagé entre l'écran et l'affichage en combat :
 * A = toi, B = l'adversaire, le terrain, et les derniers résultats.
 */
public final class CalcState {
    public static final CalcState INSTANCE = new CalcState();

    public final MonState a = new MonState();
    public final MonState b = new MonState();
    public final FieldState field = new FieldState();

    public boolean inBattle;

    // Combat double : quel Pokémon en jeu de chaque côté est utilisé dans le calcul
    private int mySlot;
    private int oppSlot;
    private int myActiveCount;
    private int oppActiveCount;
    /** Ton équipe pendant le combat (avec ses PV du moment), pour "Qui envoyer ?". */
    private List<Pokemon> battleTeam = List.of();
    private final List<UUID> myActiveUuids = new ArrayList<>();

    // Synchronisation avec le combat
    @Nullable private UUID lastBattleId;
    @Nullable private UUID lastMyUuid;
    @Nullable private UUID lastOppUuid;
    @Nullable private String lastMySig;
    @Nullable private String lastOppSig;

    // Calcul
    @Nullable private String lastRequest;
    private final AtomicInteger sequence = new AtomicInteger();
    @Nullable private volatile JsonObject result;
    @Nullable private volatile String resultFor;
    @Nullable private volatile String lastError;

    private CalcState() {
    }

    // =======================================================================================
    // Tick : synchronisation + calcul si quelque chose a changé

    public void tick() {
        syncFromBattle();
        if (a.isEmpty()) {
            fillFromParty();
        }
        applyPendingSets();
        applyReveals();
        requestIfChanged();
    }

    /** Ce que le combat vient de révéler (objet, talent, attaques, Téra) corrige le set de l'adversaire. */
    private void applyReveals() {
        for (MonState m : new MonState[]{a, b}) {
            if (m.isEmpty() || m.autoSet) {
                continue; // on attend d'abord son set
            }
            BattleLog.Revealed r = BattleLog.forMon(m);
            if (r == null || r.version == m.revealVersion) {
                continue;
            }
            // Set choisi automatiquement : on rechoisit celui qui colle le mieux (il remet aussi ce qui est révélé)
            if (!(m.setAuto && !m.setModified && SmogonSets.applyBest(m))) {
                BattleLog.applyTo(m, r);
            }
            m.revealVersion = r.version;
        }
    }

    /** Les Pokémon en attente de leur set "le plus joué" (les sets se chargent en arrière-plan). */
    private void applyPendingSets() {
        if (!a.autoSet && !b.autoSet) {
            return;
        }
        SmogonSets.load();
        if (SmogonSets.status() == SmogonSets.Status.LOADING || SmogonSets.status() == SmogonSets.Status.NOT_STARTED) {
            return;
        }
        for (MonState m : new MonState[]{a, b}) {
            if (m.autoSet) {
                m.autoSet = false;
                if (!m.isEmpty()) {
                    SmogonSets.applyBest(m);
                }
            }
        }
    }

    /** Si A est vide, on prend le premier Pokémon de l'équipe. */
    public void fillFromParty() {
        List<Pokemon> party = party();
        if (!party.isEmpty() && a.isEmpty()) {
            a.copyFrom(MonState.fromPokemon(party.get(0)));
        }
    }

    public static List<Pokemon> party() {
        List<Pokemon> list = new ArrayList<>();
        try {
            ClientParty party = CobblemonClient.INSTANCE.getStorage().getParty();
            for (Pokemon pokemon : party.getSlots()) {
                if (pokemon != null) {
                    list.add(pokemon);
                }
            }
        } catch (Throwable ignored) {
            // pas encore d'équipe (écran titre...)
        }
        return list;
    }

    /** Passe au Pokémon suivant / précédent de l'équipe pour le côté A. */
    public void cycleParty(int direction) {
        List<Pokemon> party = party();
        if (party.isEmpty()) {
            return;
        }
        int index = -1;
        for (int i = 0; i < party.size(); i++) {
            if (party.get(i).getUuid().equals(a.uuid)) {
                index = i;
            }
        }
        int next = Math.floorMod(index + direction, party.size());
        if (index < 0) {
            next = direction > 0 ? 0 : party.size() - 1;
        }
        a.copyFrom(MonState.fromPokemon(party.get(next)));
    }

    // =======================================================================================
    // Combat

    private void syncFromBattle() {
        ClientBattle battle;
        try {
            battle = CobblemonClient.INSTANCE.getBattle();
        } catch (Throwable t) {
            battle = null;
        }
        if (battle == null) {
            inBattle = false;
            return;
        }
        inBattle = true;
        if (!battle.getBattleId().equals(lastBattleId)) {
            lastBattleId = battle.getBattleId();
            lastMyUuid = null;
            lastOppUuid = null;
            lastMySig = null;
            lastOppSig = null;
            mySlot = 0;
            oppSlot = 0;
            try {
                field.doubles = battle.getBattleFormat().getBattleType().getPokemonPerSide() >= 2;
            } catch (Throwable ignored) {
                // format inconnu : on ne touche à rien
            }
        }

        UUID me = Minecraft.getInstance().player != null ? Minecraft.getInstance().player.getUUID() : null;
        ClientBattleSide mySide = null;
        for (ClientBattleSide side : battle.getSides()) {
            for (ClientBattleActor actor : side.getActors()) {
                if (actor.getUuid().equals(me)) {
                    mySide = side;
                }
            }
        }
        if (mySide == null) {
            mySide = battle.getSide1(); // spectateur
        }
        ClientBattleSide oppSide = mySide == battle.getSide1() ? battle.getSide2() : battle.getSide1();

        List<ClientBattlePokemon> myActives = actives(mySide, me);
        List<ClientBattlePokemon> oppActives = actives(oppSide, null);
        myActiveCount = myActives.size();
        oppActiveCount = oppActives.size();
        myActiveUuids.clear();
        for (ClientBattlePokemon p : myActives) {
            myActiveUuids.add(p.getUuid());
        }
        battleTeam = myTeam(mySide, me);

        ClientBattlePokemon mine = myActives.isEmpty() ? null : myActives.get(Math.floorMod(mySlot, myActives.size()));
        if (mine != null) {
            if (!mine.getUuid().equals(lastMyUuid)) {
                Pokemon own = findOwn(mine, mySide);
                MonState fresh;
                if (own != null) {
                    fresh = MonState.fromPokemon(own);
                } else {
                    fresh = MonState.fromBattle(mine);
                    if (!SavedSets.apply(fresh)) {
                        fresh.autoSet = true;
                    }
                }
                fresh.uuid = mine.getUuid();
                a.copyFrom(fresh);
                lastMyUuid = mine.getUuid();
                lastMySig = null;
            }
            String sig = MonState.battleSignature(mine);
            if (!sig.equals(lastMySig)) {
                a.updateFromBattle(mine);
                lastMySig = sig;
            }
        }

        ClientBattlePokemon opp = oppActives.isEmpty() ? null : oppActives.get(Math.floorMod(oppSlot, oppActives.size()));
        if (opp != null) {
            if (!opp.getUuid().equals(lastOppUuid)) {
                MonState fresh = MonState.fromBattle(opp);
                if (!SavedSets.apply(fresh)) {
                    fresh.autoSet = true; // le set le plus joué (Smogon / Showdown)
                }
                b.copyFrom(fresh);
                lastOppUuid = opp.getUuid();
                lastOppSig = null;
            }
            String sig = MonState.battleSignature(opp);
            if (!sig.equals(lastOppSig)) {
                b.updateFromBattle(opp);
                lastOppSig = sig;
            }
        }
    }

    /** Les Pokémon en jeu d'un côté (ceux de preferredActor d'abord : toi, en combat multi). */
    private static List<ClientBattlePokemon> actives(ClientBattleSide side, @Nullable UUID preferredActor) {
        List<ClientBattleActor> actors = new ArrayList<>(side.getActors());
        if (preferredActor != null) {
            actors.sort(java.util.Comparator.comparingInt(actor -> actor.getUuid().equals(preferredActor) ? 0 : 1));
        }
        List<ClientBattlePokemon> list = new ArrayList<>();
        for (ClientBattleActor actor : actors) {
            for (ActiveClientBattlePokemon active : actor.getActivePokemon()) {
                ClientBattlePokemon bp = active.getBattlePokemon();
                if (bp != null && !list.contains(bp)) {
                    list.add(bp);
                }
            }
        }
        return list;
    }

    /** Ton équipe dans le combat (version du combat : PV, niveaux ajustés), sinon ton équipe tout court. */
    private static List<Pokemon> myTeam(ClientBattleSide side, @Nullable UUID me) {
        for (ClientBattleActor actor : side.getActors()) {
            if (me != null && actor.getUuid().equals(me) && !actor.getPokemon().isEmpty()) {
                List<Pokemon> list = new ArrayList<>();
                for (Pokemon pokemon : actor.getPokemon()) {
                    if (pokemon != null) {
                        list.add(pokemon);
                    }
                }
                return list;
            }
        }
        return party();
    }

    // --- Combat double : choix du Pokémon en jeu ---

    public int myActiveCount() {
        return inBattle ? myActiveCount : 0;
    }

    public int oppActiveCount() {
        return inBattle ? oppActiveCount : 0;
    }

    public int mySlot() {
        return myActiveCount == 0 ? 0 : Math.floorMod(mySlot, myActiveCount);
    }

    public int oppSlot() {
        return oppActiveCount == 0 ? 0 : Math.floorMod(oppSlot, oppActiveCount);
    }

    /** Passe à l'autre Pokémon adverse en jeu (combat double). */
    public void cycleOppSlot() {
        oppSlot = oppSlot() + 1;
        lastOppUuid = null;
        syncFromBattle();
    }

    /** Passe à ton autre Pokémon en jeu (combat double). */
    public void cycleMySlot() {
        mySlot = mySlot() + 1;
        lastMyUuid = null;
        syncFromBattle();
    }

    /** Ton équipe pour "Qui envoyer ?" : celle du combat si tu es en combat, sinon celle de ton PC d'équipe. */
    public List<Pokemon> team() {
        return inBattle && !battleTeam.isEmpty() ? battleTeam : party();
    }

    /** Ce Pokémon est-il déjà en jeu (combat) ? */
    public boolean isActive(@Nullable UUID uuid) {
        return inBattle && uuid != null && myActiveUuids.contains(uuid);
    }

    /** Ton Pokémon avec toutes ses données : d'abord la version du combat (niveaux ajustés), sinon l'équipe. */
    @Nullable
    private static Pokemon findOwn(ClientBattlePokemon battlePokemon, ClientBattleSide side) {
        UUID uuid = battlePokemon.getUuid();
        for (ClientBattleActor actor : side.getActors()) {
            for (Pokemon pokemon : actor.getPokemon()) {
                if (pokemon != null && pokemon.getUuid().equals(uuid)) {
                    return pokemon;
                }
            }
        }
        try {
            Pokemon pokemon = CobblemonClient.INSTANCE.getStorage().getParty().findByUUID(uuid);
            if (pokemon != null) {
                return pokemon;
            }
        } catch (Throwable ignored) {
            // rien
        }
        // Dernier recours : même espèce dans la liste du combat
        for (ClientBattleActor actor : side.getActors()) {
            for (Pokemon pokemon : actor.getPokemon()) {
                if (pokemon != null && pokemon.getSpecies() == battlePokemon.getSpecies()) {
                    return pokemon;
                }
            }
        }
        return null;
    }

    // =======================================================================================
    // Modifications faites dans l'écran

    /** À appeler quand le spread (talent, objet, nature, IV, EV, attaques, Téra) est modifié à la main. */
    public void spreadEdited(MonState mon) {
        mon.setModified = true;
        mon.autoSet = false;
        if (mon.source != MonState.Source.PARTY && !mon.isEmpty()) {
            SavedSets.remember(mon);
        }
    }

    public void swapSides() {
        MonState tmp = a.copy();
        a.copyFrom(b);
        b.copyFrom(tmp);
        FieldState.Side side = field.a.copy();
        field.a.copyFrom(field.b);
        field.b.copyFrom(side);
    }

    // =======================================================================================
    // Calcul

    @Nullable
    private String buildRequest() {
        if (a.isEmpty() || b.isEmpty()) {
            return null;
        }
        JsonObject q = new JsonObject();
        q.add("a", a.toRequest());
        q.add("b", b.toRequest());
        q.add("field", field.toRequest());
        q.addProperty("crit", field.crit);
        return q.toString();
    }

    public void requestIfChanged() {
        String q = buildRequest();
        if (q == null || q.equals(lastRequest)) {
            return;
        }
        CalcEngine.warmUp();
        if (CalcEngine.status() != CalcEngine.Status.READY) {
            return; // on réessaiera au prochain tick
        }
        lastRequest = q;
        int id = sequence.incrementAndGet();
        CalcEngine.calculate(q).whenComplete((r, t) -> {
            if (id != sequence.get()) {
                return; // un calcul plus récent est parti
            }
            if (t != null) {
                lastError = t.toString();
                result = null;
            } else if (r.has("error")) {
                lastError = r.get("error").getAsString();
                result = null;
            } else {
                lastError = null;
                result = r;
            }
            resultFor = q;
        });
    }

    public boolean isCalculating() {
        return lastRequest != null && !Objects.equals(lastRequest, resultFor);
    }

    @Nullable
    public String lastError() {
        return lastError;
    }

    /** Résumé d'un côté : stats, PV, vitesse finale... */
    @Nullable
    public JsonObject side(boolean sideA) {
        JsonObject r = result;
        if (r == null) {
            return null;
        }
        JsonElement e = r.get(sideA ? "a" : "b");
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    /** Résultat d'une attaque (index 0..3) du côté donné contre l'autre côté. */
    @Nullable
    public JsonObject move(boolean sideA, int index) {
        JsonObject side = side(sideA);
        if (side == null || !side.has("moves")) {
            return null;
        }
        JsonArray moves = side.getAsJsonArray("moves");
        if (index >= moves.size() || !moves.get(index).isJsonObject()) {
            return null;
        }
        JsonObject move = moves.get(index).getAsJsonObject();
        String expected = (sideA ? a : b).moves[index];
        if (!expected.equals(move.has("id") ? move.get("id").getAsString() : "")) {
            return null; // résultat d'un ancien calcul
        }
        return move;
    }

    public static int getInt(@Nullable JsonObject o, String key, int fallback) {
        return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsInt() : fallback;
    }

    public static double getDouble(@Nullable JsonObject o, String key, double fallback) {
        return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsDouble() : fallback;
    }

    public static String getString(@Nullable JsonObject o, String key) {
        return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }
}
