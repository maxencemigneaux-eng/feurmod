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

    /** Adversaires croisés en combat depuis le lancement du jeu (le plus récent en dernier), avec ce qu'ils ont révélé. */
    public static final java.util.LinkedHashMap<UUID, MonState> SEEN_OPPONENTS = new java.util.LinkedHashMap<>();
    private static final int MAX_SEEN = 24;

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
    private int lastFieldVersion = -1;

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
        applyAbilityField();
        requestIfChanged();
    }

    // =======================================================================================
    // Talents qui posent une météo / un champ en entrant (Sécheresse → Soleil, Créa-Élec → Champ Électrifié...)

    private static final java.util.Map<String, String> WEATHER_ABILITIES = java.util.Map.of(
            "drought", "Sun", "orichalcumpulse", "Sun", "desolateland", "Harsh Sunshine",
            "drizzle", "Rain", "primordialsea", "Heavy Rain", "deltastream", "Strong Winds",
            "sandstream", "Sand", "snowwarning", "Snow");
    private static final java.util.Set<String> PRIMAL_WEATHERS = java.util.Set.of("Harsh Sunshine", "Heavy Rain", "Strong Winds");

    /** Météo primale ? (Set.of(...).contains(null) plante : un talent sans météo donne null.) */
    private static boolean isPrimal(@Nullable String weather) {
        return weather != null && PRIMAL_WEATHERS.contains(weather);
    }
    private static final java.util.Map<String, String> TERRAIN_ABILITIES = java.util.Map.of(
            "electricsurge", "Electric", "hadronengine", "Electric", "grassysurge", "Grassy",
            "psychicsurge", "Psychic", "mistysurge", "Misty");

    private final String[] lastAbility = new String[2];
    private final String[] lastSpecies = new String[2];
    @Nullable private String weatherBefore;
    @Nullable private String weatherSet;
    @Nullable private String terrainBefore;
    @Nullable private String terrainSet;

    public static boolean setsField(String ability) {
        String id = Names.normalize(ability == null ? "" : ability);
        return WEATHER_ABILITIES.containsKey(id) || TERRAIN_ABILITIES.containsKey(id);
    }

    /**
     * Quand le talent d'un côté change (nouveau Pokémon, set, Méga...), on pose sa météo / son champ ;
     * et on remet ceux d'avant si c'est un talent qui les avait posés et qu'il n'est plus là.
     */
    private void applyAbilityField() {
        MonState[] mons = {a, b};
        for (int i = 0; i < 2; i++) {
            MonState m = mons[i];
            String ability = m.isEmpty() ? "" : Names.normalize(m.ability);
            String species = m.isEmpty() || m.form == null ? "" : m.calcForm().showdownId();
            if (ability.equals(lastAbility[i]) && species.equals(lastSpecies[i])) {
                continue;
            }
            boolean first = lastAbility[i] == null;
            lastAbility[i] = ability;
            lastSpecies[i] = species;
            String weather = WEATHER_ABILITIES.get(ability);
            String terrain = TERRAIN_ABILITIES.get(ability);
            String otherAbility = Names.normalize(mons[1 - i].isEmpty() ? "" : mons[1 - i].ability);
            if (inBattle) {
                // En combat, ce sont les messages du combat qui font foi : une météo normale (Sable de Tyranitar...) ou un
                // champ dure 5 tours même après la sortie de celui qui l'a posé, et le talent du set n'est qu'une supposition.
                // Seules les météos primales (sans message) restent gérées ici : elles s'arrêtent quand leur Pokémon sort.
                if (weather != null && !isPrimal(weather)) weather = null;
                terrain = null;
                if (terrainSet != null) terrainSet = null;
                if (weatherSet != null && !isPrimal(weatherSet)) weatherSet = null;
                otherAbility = isPrimal(WEATHER_ABILITIES.get(otherAbility)) ? otherAbility : "";
            }
            // Le talent qui avait posé la météo / le champ n'est plus là (des deux côtés) : on remet comme avant
            if (!first && weather == null && weatherSet != null && weatherSet.equals(field.weather)
                    && !weatherSet.equals(WEATHER_ABILITIES.get(otherAbility))) {
                field.weather = weatherBefore == null ? "" : weatherBefore;
                weatherSet = null;
            }
            if (!first && terrain == null && terrainSet != null && terrainSet.equals(field.terrain)
                    && !terrainSet.equals(TERRAIN_ABILITIES.get(otherAbility))) {
                field.terrain = terrainBefore == null ? "" : terrainBefore;
                terrainSet = null;
            }
            // Une météo primale (Terre Finale, Mer Primaire, Souffle Delta) ne peut pas être remplacée par une météo normale
            boolean primalBlocks = weather != null && isPrimal(field.weather) && !isPrimal(weather)
                    && field.weather.equals(WEATHER_ABILITIES.get(otherAbility));
            if (weather != null && !weather.equals(field.weather) && !primalBlocks) {
                if (weatherSet == null) weatherBefore = field.weather;
                field.weather = weather;
                weatherSet = weather;
            }
            if (terrain != null && !terrain.equals(field.terrain)) {
                if (terrainSet == null) terrainBefore = field.terrain;
                field.terrain = terrain;
                terrainSet = terrain;
            }
        }
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
            lastSeeds = null;
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
        // Ceux qui ne sont plus sur le terrain perdent leurs boosts (combat double compris, Relais géré)
        if (!myActives.isEmpty() && !oppActives.isEmpty()) {
            java.util.Set<UUID> opp = new java.util.HashSet<>();
            for (ClientBattlePokemon p : oppActives) {
                opp.add(p.getUuid());
            }
            BattleLog.updateField(new java.util.HashSet<>(myActiveUuids), opp);
        }
        // Météo, champ, murs, pièges... lus dans les messages : recopiés seulement quand ils changent
        if (BattleLog.fieldVersion() != lastFieldVersion) {
            lastFieldVersion = BattleLog.fieldVersion();
            BattleLog.applyField(field);
        }
        // Pokémon K.O. de chaque côté (vus à leurs PV) : Suprématie, Dernier Hommage
        for (ClientBattlePokemon p : oppActives) {
            if (p.getHpValue() <= 0) BattleLog.markFainted(p.getUuid(), false);
        }
        for (ClientBattlePokemon p : myActives) {
            if (p.getHpValue() <= 0) BattleLog.markFainted(p.getUuid(), true);
        }
        for (Pokemon p : battleTeam) {
            if (p.getCurrentHealth() <= 0) BattleLog.markFainted(p.getUuid(), true);
        }

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
            battleCounters(a, mine.getUuid(), true);
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
            battleCounters(b, opp.getUuid(), false);
            rememberOpponent(opp.getUuid(), b);
        }

        // Vampigraine / Salaison lus dans les messages : recopiés quand ils changent (le Pokémon en jeu compris),
        // pour que les boutons restent réglables à la main entre deux changements
        String seeds = BattleLog.seeded(a.uuid) + "/" + BattleLog.saltCured(a.uuid) + "/"
                + BattleLog.seeded(b.uuid) + "/" + BattleLog.saltCured(b.uuid);
        if (!seeds.equals(lastSeeds)) {
            lastSeeds = seeds;
            field.a.seeded = BattleLog.seeded(a.uuid);
            field.a.saltCure = BattleLog.saltCured(a.uuid);
            field.b.seeded = BattleLog.seeded(b.uuid);
            field.b.saltCure = BattleLog.saltCured(b.uuid);
        }
    }

    @Nullable private String lastSeeds;

    /** Ce qui ne dure que tant que le Pokémon est en jeu : Paléosynthèse / Charge Quantique, Toxik, alliés K.O. */
    private static void battleCounters(MonState m, UUID uuid, boolean mine) {
        m.paradoxStat = BattleLog.paradox(uuid);
        m.toxicCounter = BattleLog.toxicCounter(uuid);
        m.alliesFainted = BattleLog.faintedAllies(mine, uuid);
    }

    /** Garde une copie de l'adversaire (et de ce qu'il a révélé) pour la liste « Adversaires déjà vus ». */
    private static void rememberOpponent(UUID uuid, MonState m) {
        if (m.isEmpty()) {
            return;
        }
        seenOpponents(); // charge d'abord ceux des parties précédentes
        SEEN_OPPONENTS.remove(uuid);
        MonState copy = m.copy();
        // Ce qui ne vaut que pour ce moment du combat ne suit pas dans la liste
        copy.paradoxStat = null;
        copy.toxicCounter = 0;
        copy.alliesFainted = 0;
        copy.hpRatio = -1;
        SEEN_OPPONENTS.put(uuid, copy);
        while (SEEN_OPPONENTS.size() > MAX_SEEN) {
            SEEN_OPPONENTS.remove(SEEN_OPPONENTS.keySet().iterator().next());
        }
        seenDirty = true;
    }

    // ----- Sauvegarde des adversaires déjà vus (config/feurcalc_seen.json) -----

    private static final java.nio.file.Path SEEN_FILE =
            net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("feurcalc_seen.json");
    private static boolean seenDirty;
    /** Le serveur dont la liste est chargée (chaque serveur a sa propre liste). */
    @Nullable private static String seenServer;

    private static String serverKey() {
        net.minecraft.client.multiplayer.ServerData d = net.minecraft.client.Minecraft.getInstance().getCurrentServer();
        return d == null ? "solo" : d.ip.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** La liste du serveur actuel, chargée depuis le fichier au premier accès (une fois les espèces de Cobblemon connues). */
    public static java.util.LinkedHashMap<UUID, MonState> seenOpponents() {
        String key = serverKey();
        if (!key.equals(seenServer) && !com.cobblemon.mod.common.api.pokemon.PokemonSpecies.getSpecies().isEmpty()) {
            if (seenServer != null) {
                saveSeenIfNeeded(); // garde la liste de l'ancien serveur
                SEEN_OPPONENTS.clear();
            }
            seenServer = key;
            loadSeen();
        }
        return SEEN_OPPONENTS;
    }

    /** Tout le fichier : { "servers": { "adresse": { uuid: {...} } } }. L'ancien format (sans serveurs) est rendu tel quel. */
    private static JsonObject readSeenFile() {
        try {
            if (java.nio.file.Files.exists(SEEN_FILE)) {
                return com.google.gson.JsonParser.parseString(
                        java.nio.file.Files.readString(SEEN_FILE, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            }
        } catch (Exception ignored) {
            // fichier illisible : on repart de zéro
        }
        return new JsonObject();
    }

    private static void loadSeen() {
        java.util.LinkedHashMap<UUID, MonState> loaded = new java.util.LinkedHashMap<>();
        try {
            JsonObject file = readSeenFile();
            // Ancien fichier sans serveurs : la liste va au premier serveur ouvert
            JsonObject root = !file.has("servers") ? file
                    : file.getAsJsonObject("servers").has(seenServer) ? file.getAsJsonObject("servers").getAsJsonObject(seenServer)
                    : new JsonObject();
            for (java.util.Map.Entry<String, JsonElement> e : root.entrySet()) {
                try {
                    JsonObject o = e.getValue().getAsJsonObject();
                    com.cobblemon.mod.common.pokemon.Species species = com.cobblemon.mod.common.api.pokemon.PokemonSpecies
                            .getByIdentifier(net.minecraft.resources.ResourceLocation.parse(o.get("species").getAsString()));
                    if (species == null) {
                        continue; // espèce absente de ce serveur
                    }
                    com.cobblemon.mod.common.pokemon.FormData form = species.getStandardForm();
                    String formName = o.has("form") ? o.get("form").getAsString() : "";
                    for (com.cobblemon.mod.common.pokemon.FormData f : species.getForms()) {
                        if (f.getName().equals(formName)) form = f;
                    }
                    MonState m = MonState.fromForm(species, form, o.get("level").getAsInt());
                    m.applySpread(o.getAsJsonObject("spread"));
                    loaded.put(UUID.fromString(e.getKey()), m);
                } catch (Exception bad) {
                    // une entrée abîmée ne doit pas faire perdre les autres
                }
            }
        } catch (Exception ex) {
            return; // fichier illisible : on garde la liste vide, il sera réécrit à la prochaine sauvegarde
        }
        loaded.putAll(SEEN_OPPONENTS); // ceux de cette partie restent les plus récents
        SEEN_OPPONENTS.clear();
        SEEN_OPPONENTS.putAll(loaded);
        while (SEEN_OPPONENTS.size() > MAX_SEEN) {
            SEEN_OPPONENTS.remove(SEEN_OPPONENTS.keySet().iterator().next());
        }
    }

    /** Écrit la liste si elle a changé (à la fermeture du calculateur et à la déconnexion). */
    public static void saveSeenIfNeeded() {
        if (!seenDirty) {
            return;
        }
        seenDirty = false;
        String server = seenServer != null ? seenServer : serverKey();
        JsonObject file = readSeenFile();
        JsonObject servers = file.has("servers") ? file.getAsJsonObject("servers") : new JsonObject();
        JsonObject root = new JsonObject();
        for (java.util.Map.Entry<UUID, MonState> e : SEEN_OPPONENTS.entrySet()) {
            MonState m = e.getValue();
            if (m.species == null || m.form == null) {
                continue;
            }
            JsonObject o = new JsonObject();
            o.addProperty("species", m.species.getResourceIdentifier().toString());
            o.addProperty("form", m.form.getName());
            o.addProperty("level", m.level);
            o.add("spread", m.spreadToJson());
            root.add(e.getKey().toString(), o);
        }
        try {
            java.nio.file.Files.createDirectories(SEEN_FILE.getParent());
            servers.add(server, root);
            JsonObject out = new JsonObject();
            out.add("servers", servers);
            java.nio.file.Files.writeString(SEEN_FILE, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(out),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            seenDirty = true; // on réessaiera à la prochaine sauvegarde
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
