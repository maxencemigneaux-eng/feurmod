package com.goat.feurcalc;

import com.cobblemon.mod.common.client.battle.ActiveClientBattlePokemon;
import com.cobblemon.mod.common.client.battle.ClientBattle;
import com.cobblemon.mod.common.client.battle.ClientBattleActor;
import com.cobblemon.mod.common.client.battle.ClientBattlePokemon;
import com.cobblemon.mod.common.client.battle.ClientBattleSide;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.Item;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Lit les messages du combat ("Léviator a récupéré un peu de PV grâce à ses Restes !", "Lance-Flammes"...)
 * pour retenir ce que chaque Pokémon a révélé : son objet, son talent, ses attaques et son type Téra.
 * Le calculateur s'en sert pour corriger le set de l'adversaire tout seul, comme Showdex.
 *
 * Les messages arrivent par le mixin de BattleMessagePacket (n'importe quel fil) et sont lus sur le fil du jeu.
 */
public final class BattleLog {
    private static final String PREFIX = "cobblemon.battle.";
    private static final int MAX_PENDING = 2000;

    private static final ConcurrentLinkedQueue<Component> PENDING = new ConcurrentLinkedQueue<>();
    private static final Map<UUID, Revealed> REVEALED = new HashMap<>();
    /** Boosts suivis depuis les messages (le client Cobblemon ne les garde pas) : index 1..5 = Atq..Vit. */
    private static final Map<UUID, int[]> BOOSTS = new HashMap<>();
    /** Ceux qui viennent d'utiliser Relais : leurs boosts iront au remplaçant. */
    private static final Set<UUID> PASSING = new java.util.HashSet<>();
    /** Paléosynthèse / Charge Quantique activée (reste active même après l'Énergie Booster) : stat boostée, "" = inconnue. */
    private static final Map<UUID, String> PARADOX = new HashMap<>();
    /** Toxik : nombre de fois où le poison a déjà fait des dégâts depuis l'entrée en jeu. */
    private static final Map<UUID, Integer> TOXIC_TICKS = new HashMap<>();
    /** Sous Vampigraine / Salaison (disparaît quand le Pokémon sort). */
    private static final Set<UUID> SEEDED = new java.util.HashSet<>();
    private static final Set<UUID> SALT_CURED = new java.util.HashSet<>();
    /** Pokémon K.O. de chaque côté (Suprématie, Dernier Hommage). */
    private static final Set<UUID> FAINTED_MINE = new java.util.HashSet<>();
    private static final Set<UUID> FAINTED_OPP = new java.util.HashSet<>();
    private static Set<UUID> prevMine = Set.of();
    private static Set<UUID> prevOpp = Set.of();

    /** Le terrain lu dans les messages : a = ton côté ("ally"), b = l'adversaire ("opponent"). */
    private static FieldState field = new FieldState();
    private static int fieldVersion;
    private static boolean weatherSeen;
    private static boolean terrainSeen;
    @Nullable private static UUID battleId;

    // Talent connu rien qu'à la clé du message (le nom du Pokémon est le 1er argument)
    private static final Map<String, String> FIXED_ABILITIES = new HashMap<>();
    // Objet connu rien qu'à la clé : objet + position du Pokémon qui le tient dans les arguments
    private static final Map<String, String> FIXED_ITEMS = new HashMap<>();
    private static final Map<String, Integer> FIXED_ITEM_HOLDER = new HashMap<>();
    // Messages "<Pokémon> ... son <objet>" (Pokémon = argument 1, objet = argument 2)
    private static final Set<String> ITEM_KEYS = Set.of(
            "heal.leftovers", "heal.item", "damage.item", "item.airballoon", "item.frisk", "item.harvest",
            "item.recycle", "item.trick", "item.bestow", "item.thief");

    static {
        String[][] abilities = {
                {"ability.sturdy", "sturdy"}, {"ability.unnerve", "unnerve"}, {"ability.anticipation", "anticipation"},
                {"ability.magicbounce", "magicbounce"}, {"ability.airlock", "airlock"}, {"ability.cloudnine", "cloudnine"},
                {"activate.protosynthesis", "protosynthesis"}, {"activate.quarkdrive", "quarkdrive"}, {"activate.mummy", "mummy"}, {"activate.disguise", "disguise"},
                {"damage.pokemonmimikyubusted", "disguise"}, {"activate.synchronize", "synchronize"},
                {"activate.forewarn", "forewarn"},
                {"heal.dryskin", "dryskin"}, {"damage.dryskin", "dryskin"}, {"heal.icybody", "icybody"},
                {"heal.raindish", "raindish"}, {"heal.poisonheal", "poisonheal"}, {"heal.voltabsorb", "voltabsorb"},
                {"heal.waterabsorb", "waterabsorb"}, {"heal.eartheater", "eartheater"}, {"heal.cheekpouch", "cheekpouch"},
        };
        for (String[] a : abilities) {
            FIXED_ABILITIES.put(a[0], a[1]);
        }
        fixedItem("damage.lifeorb", "lifeorb", 0);
        fixedItem("damage.rockyhelmet", "rockyhelmet", 1); // "X a été blessé par le Casque Brut de Y"
        fixedItem("activate.quickclaw", "quickclaw", 0);
        fixedItem("activate.focusband", "focusband", 0);
    }

    private static void fixedItem(String key, String item, int holder) {
        FIXED_ITEMS.put(key, item);
        FIXED_ITEM_HOLDER.put(key, holder);
    }

    /** Ce qu'un Pokémon a montré pendant le combat. */
    public static final class Revealed {
        /** null = inconnu ; "" = il n'a plus d'objet (utilisé, perdu, volé). */
        @Nullable public String item;
        /** L'objet qu'il avait avant de l'utiliser ou de le perdre. */
        @Nullable public String lostItem;
        @Nullable public String ability;
        @Nullable public String tera;
        public final Set<String> moves = new LinkedHashSet<>();
        /** Augmente à chaque nouvelle info. */
        public int version;
    }

    private BattleLog() {
    }

    // =======================================================================================
    // Entrée (mixin)

    /** Appelé quand un paquet de messages de combat est créé ou reçu (n'importe quel fil). */
    public static void capture(@Nullable List<? extends Component> messages) {
        if (messages == null) {
            return;
        }
        for (Component message : messages) {
            if (message != null && PENDING.size() < MAX_PENDING) {
                PENDING.add(message);
            }
        }
    }

    /** À chaque tick, sur le fil du jeu. */
    public static void process(@Nullable ClientBattle battle) {
        if (battle == null) {
            PENDING.clear();
            return;
        }
        if (!battle.getBattleId().equals(battleId)) {
            battleId = battle.getBattleId();
            REVEALED.clear();
            BOOSTS.clear();
            MEGA.clear();
            PASSING.clear();
            PARADOX.clear();
            TOXIC_TICKS.clear();
            SEEDED.clear();
            SALT_CURED.clear();
            FAINTED_MINE.clear();
            FAINTED_OPP.clear();
            prevMine = Set.of();
            prevOpp = Set.of();
            field = new FieldState(); // nouveau combat : terrain vide
            weatherSeen = false;
            terrainSeen = false;
            fieldVersion++;
        }
        Component message;
        while ((message = PENDING.poll()) != null) {
            try {
                handle(battle, message);
            } catch (Throwable ignored) {
                // message inattendu : on l'ignore
            }
        }
    }

    @Nullable
    public static Revealed get(@Nullable UUID uuid) {
        return uuid == null ? null : REVEALED.get(uuid);
    }

    /**
     * Ce que le combat a révélé pour ce côté du calcul : l'adversaire, et aussi ton propre Pokémon pendant un combat
     * (Téracristal, objet utilisé ou perdu : Ballon éclaté, baie mangée, Énergie Booster, objet volé ou échangé...).
     */
    @Nullable
    public static Revealed forMon(MonState m) {
        if (m.source == MonState.Source.BATTLE) {
            return get(m.uuid);
        }
        if (m.source == MonState.Source.PARTY && MonState.inBattleNow()) {
            return get(m.uuid);
        }
        return null;
    }

    // =======================================================================================
    // États qui durent tant que le Pokémon reste en jeu

    /** Paléosynthèse / Charge Quantique active : null = non, "" = stat inconnue, sinon "atk", "def", "spa", "spd", "spe". */
    @Nullable
    public static String paradox(@Nullable UUID uuid) {
        return uuid == null ? null : PARADOX.get(uuid);
    }

    /** Toxik : multiplicateur des prochains dégâts (1 au premier tour, puis 2, 3...). */
    public static int toxicCounter(@Nullable UUID uuid) {
        return uuid == null ? 1 : 1 + TOXIC_TICKS.getOrDefault(uuid, 0);
    }

    public static boolean seeded(@Nullable UUID uuid) {
        return uuid != null && SEEDED.contains(uuid);
    }

    public static boolean saltCured(@Nullable UUID uuid) {
        return uuid != null && SALT_CURED.contains(uuid);
    }

    /** Signale un Pokémon K.O. (vu à ses PV), pour compter les alliés tombés de chaque côté. */
    public static void markFainted(@Nullable UUID uuid, boolean mine) {
        if (uuid != null) {
            (mine ? FAINTED_MINE : FAINTED_OPP).add(uuid);
        }
    }

    /** Nombre de Pokémon K.O. de ce côté, sans compter celui-ci. */
    public static int faintedAllies(boolean mine, @Nullable UUID except) {
        Set<UUID> set = mine ? FAINTED_MINE : FAINTED_OPP;
        return set.size() - (except != null && set.contains(except) ? 1 : 0);
    }

    // =======================================================================================
    // Application au calculateur

    /** Remet par-dessus le set ce que le combat a révélé (objet, talent, Téra, attaques). */
    public static void overlay(MonState m) {
        Revealed r = forMon(m);
        if (r != null) {
            applyTo(m, r);
        }
    }

    public static void applyTo(MonState m, Revealed r) {
        if (r.item != null) {
            m.item = r.item;
            m.revealedItem = r.item;
        }
        m.lostItem = r.lostItem == null ? "" : r.lostItem;
        if (r.ability != null) {
            m.ability = r.ability;
            m.revealedAbility = r.ability;
        }
        if (r.tera != null) {
            m.teraType = r.tera;
            m.tera = true;
            m.revealedTera = r.tera;
        }
        for (String move : r.moves) {
            m.revealedMoves.add(move);
            if (contains(m.moves, move)) {
                continue;
            }
            // Une case vide, sinon la dernière attaque qui n'a pas encore été vue
            int slot = -1;
            for (int i = 0; i < 4 && slot < 0; i++) {
                if (m.moves[i].isEmpty()) {
                    slot = i;
                }
            }
            for (int i = 3; i >= 0 && slot < 0; i--) {
                if (!r.moves.contains(m.moves[i])) {
                    slot = i;
                }
            }
            if (slot >= 0) {
                m.moves[slot] = move;
            }
        }
    }

    private static boolean contains(String[] values, String value) {
        for (String v : values) {
            if (v.equals(value)) {
                return true;
            }
        }
        return false;
    }

    // =======================================================================================
    // Lecture d'un message

    private static void handle(ClientBattle battle, Component message) {
        if (!(message.getContents() instanceof TranslatableContents contents)) {
            return;
        }
        String key = contents.getKey();
        if (key.toLowerCase(java.util.Locale.ROOT).contains("mega")) {
            megaMessage(battle, contents.getArgs());
        }
        if (!key.startsWith(PREFIX)) {
            return;
        }
        String k = key.substring(PREFIX.length());
        Object[] args = contents.getArgs();

        switch (k) {
            case "used_move", "used_move_on" -> move(battle, arg(args, 0), moveId(arg(args, 1)));
            case "terastallize" -> tera(battle, arg(args, 0), teraId(arg(args, 1)));
            case "ability.generic", "ability.replace" -> ability(battle, arg(args, 0), abilityId(arg(args, 1)));
            case "ability.trace" -> {
                String traced = abilityId(arg(args, 2));
                ability(battle, arg(args, 0), traced);
                ability(battle, arg(args, 1), traced);
            }
            default -> {
                if (!handleBoost(battle, k, args) && !handleField(k)) {
                    handleOther(battle, k, args);
                }
            }
        }
    }

    // =======================================================================================
    // Boosts (Danse-Lames, Intimidation, Buée Noire...)

    /** Les boosts suivis pour ce Pokémon (0 partout si rien vu). */
    public static int[] boosts(@Nullable UUID uuid) {
        int[] b = uuid == null ? null : BOOSTS.get(uuid);
        return b == null ? new int[6] : b.clone();
    }

    private static void clearBoosts(@Nullable UUID uuid) {
        if (uuid != null) {
            BOOSTS.remove(uuid);
        }
    }

    /**
     * Qui est sur le terrain de chaque côté. Ceux qui sont sortis perdent leurs boosts,
     * sauf après Relais : le remplaçant les reçoit.
     */
    public static void updateField(Set<UUID> mine, Set<UUID> opp) {
        passBoosts(prevMine, mine);
        passBoosts(prevOpp, opp);
        prevMine = Set.copyOf(mine);
        prevOpp = Set.copyOf(opp);
        Set<UUID> onField = new java.util.HashSet<>(mine);
        onField.addAll(opp);
        BOOSTS.keySet().retainAll(onField);
        PASSING.retainAll(onField);
        // Sortir du terrain met fin à Paléosynthèse / Charge Quantique, remet le compteur de Toxik à zéro
        // et enlève Vampigraine / Salaison
        PARADOX.keySet().retainAll(onField);
        TOXIC_TICKS.keySet().retainAll(onField);
        SEEDED.retainAll(onField);
        SALT_CURED.retainAll(onField);
    }

    private static void passBoosts(Set<UUID> before, Set<UUID> now) {
        for (UUID left : before) {
            if (now.contains(left) || !PASSING.remove(left)) {
                continue;
            }
            for (UUID arrived : now) {
                if (!before.contains(arrived)) {
                    int[] b = BOOSTS.get(left);
                    if (b != null) BOOSTS.put(arrived, b.clone());
                    break;
                }
            }
        }
    }

    // =======================================================================================
    // Terrain : météo, champ, Gravité, Protection / Mur Lumière / Voile Aurore, Piège de Roc, Picots, Vent Arrière

    /** Change à chaque fois que le terrain du combat bouge (pour ne recopier qu'à ce moment-là). */
    public static int fieldVersion() {
        return fieldVersion;
    }

    /** Recopie le terrain du combat dans le calculateur (a = toi, b = l'adversaire). */
    public static void applyField(FieldState target) {
        // Pas de message pour les météos primales : on ne touche à la météo / au champ qu'une fois qu'un message en a parlé
        if (weatherSeen) target.weather = field.weather;
        if (terrainSeen) target.terrain = field.terrain;
        target.gravity = field.gravity;
        target.trickRoom = field.trickRoom;
        copySide(field.a, target.a);
        copySide(field.b, target.b);
    }

    private static void copySide(FieldState.Side from, FieldState.Side to) {
        to.reflect = from.reflect;
        to.lightScreen = from.lightScreen;
        to.auroraVeil = from.auroraVeil;
        to.stealthRock = from.stealthRock;
        to.tailwind = from.tailwind;
        to.spikes = from.spikes;
    }

    private static boolean handleField(String k) {
        int r = FieldMessages.apply(field, k);
        if ((r & FieldMessages.WEATHER) != 0) weatherSeen = true;
        if ((r & FieldMessages.TERRAIN) != 0) terrainSeen = true;
        if ((r & FieldMessages.CHANGED) != 0) fieldVersion++;
        return (r & FieldMessages.HANDLED) != 0;
    }

    /** Change Côté : les murs, pièges et Vent Arrière changent de camp. */
    private static void swapSides() {
        FieldState.Side tmp = field.a.copy();
        copySide(field.b, field.a);
        copySide(tmp, field.b);
        fieldVersion++;
    }

    private static int[] boostsOf(ClientBattlePokemon p) {
        return BOOSTS.computeIfAbsent(p.getUuid(), u -> new int[6]);
    }

    private static boolean handleBoost(ClientBattle battle, String k, Object[] args) {
        String base = k.endsWith(".zeffect") ? k.substring(0, k.length() - ".zeffect".length()) : k;
        int amount = switch (base) {
            case "boost.slight" -> 1;
            case "boost.sharp" -> 2;
            case "boost.severe" -> 3;
            case "unboost.slight" -> -1;
            case "unboost.sharp" -> -2;
            case "unboost.severe" -> -3;
            default -> 0;
        };
        if (amount != 0) {
            ClientBattlePokemon p = resolve(battle, arg(args, 0));
            int stat = statIndex(arg(args, 1));
            if (p != null && stat > 0) {
                int[] b = boostsOf(p);
                b[stat] = Math.max(-6, Math.min(6, b[stat] + amount));
            }
            return true;
        }
        ClientBattlePokemon p1 = resolve(battle, arg(args, 0));
        switch (base) {
            case "clearallboost" -> BOOSTS.clear();
            case "clearboost" -> clearBoosts(p1 == null ? null : p1.getUuid());
            case "clearallnegativeboost" -> {
                if (p1 != null) {
                    int[] b = boostsOf(p1);
                    for (int i = 1; i < 6; i++) b[i] = Math.max(0, b[i]);
                }
            }
            case "invertboost" -> {
                if (p1 != null) {
                    int[] b = boostsOf(p1);
                    for (int i = 1; i < 6; i++) b[i] = -b[i];
                }
            }
            case "setboost.bellydrum", "setboost.angerpoint" -> {
                if (p1 != null) boostsOf(p1)[1] = 6;
            }
            case "swapboost.generic", "swapboost.heartswap", "swapboost.guardswap", "swapboost.powerswap" -> {
                // Message : "<lanceur> ... <cible>" ; sans cible nommée, l'adversaire en face
                ClientBattlePokemon p2 = resolve(battle, arg(args, 1));
                if (p1 != null && p2 != null) {
                    int[] stats = base.equals("swapboost.guardswap") ? new int[]{2, 4}
                            : base.equals("swapboost.powerswap") ? new int[]{1, 3} : new int[]{1, 2, 3, 4, 5};
                    int[] a = boostsOf(p1), b = boostsOf(p2);
                    for (int i : stats) {
                        int t = a[i];
                        a[i] = b[i];
                        b[i] = t;
                    }
                }
            }
            case "copyboost.generic" -> {
                ClientBattlePokemon p2 = resolve(battle, arg(args, 1));
                if (p1 != null && p2 != null) System.arraycopy(boostsOf(p2), 0, boostsOf(p1), 0, 6);
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    /** "cobblemon.stat.attack.name" (ou son texte traduit) → 1..5 ; -1 pour PV / précision / esquive. */
    private static int statIndex(@Nullable Object statArg) {
        String[] ids = {"", "attack", "defence", "special_attack", "special_defence", "speed"};
        if (statArg instanceof Component c && c.getContents() instanceof TranslatableContents t) {
            for (int i = 1; i < 6; i++) {
                if (t.getKey().equals("cobblemon.stat." + ids[i] + ".name")) return i;
            }
        }
        String shown = text(statArg);
        for (int i = 1; i < 6; i++) {
            if (shown.equalsIgnoreCase(net.minecraft.client.resources.language.I18n.get("cobblemon.stat." + ids[i] + ".name"))) {
                return i;
            }
        }
        return -1;
    }

    /** Pokémon qui ont Méga-évolué (lu dans les messages) → le texte du message, pour savoir si c'est X ou Y. */
    private static final Map<UUID, String> MEGA = new HashMap<>();

    @Nullable
    public static String megaHint(@Nullable UUID uuid) {
        return uuid == null ? null : MEGA.get(uuid);
    }

    private static void megaMessage(ClientBattle battle, Object[] args) {
        StringBuilder all = new StringBuilder();
        ClientBattlePokemon who = null;
        for (Object o : args) {
            all.append(' ').append(text(o));
            if (who == null) who = resolve(battle, o);
        }
        if (who != null) MEGA.put(who.getUuid(), all.toString());
    }

    private static void handleOther(ClientBattle battle, String k, Object[] args) {
        // Paléosynthèse / Charge Quantique : "activate.protosynthesis", "start.quarkdrivespe", "end.protosynthesis"...
        if (k.contains("protosynthesis") || k.contains("quarkdrive")) {
            ClientBattlePokemon p = resolve(battle, arg(args, 0));
            if (p != null) {
                ability(battle, arg(args, 0), k.contains("protosynthesis") ? "protosynthesis" : "quarkdrive");
                if (k.startsWith("end")) {
                    PARADOX.remove(p.getUuid());
                } else {
                    String stat = paradoxStat(k);
                    if (stat.isEmpty()) {
                        for (int i = 1; i < args.length && stat.isEmpty(); i++) {
                            stat = paradoxStat(Names.toId(text(args[i])));
                        }
                    }
                    String before = PARADOX.get(p.getUuid());
                    PARADOX.put(p.getUuid(), stat.isEmpty() && before != null ? before : stat);
                }
            }
            return;
        }
        // Dégâts du poison : fait monter le compteur de Toxik ("damage.psn", "damage.tox"...)
        if (k.equals("damage.psn") || k.equals("damage.tox") || k.equals("damage.poison")) {
            ClientBattlePokemon p = resolve(battle, arg(args, 0));
            if (p != null) {
                TOXIC_TICKS.merge(p.getUuid(), 1, Integer::sum);
            }
            return;
        }
        // Vampigraine / Salaison : "start.leechseed", "damage.leechseed", "end.leechseed"...
        if (k.contains("leechseed") || k.contains("saltcure")) {
            Set<UUID> set = k.contains("leechseed") ? SEEDED : SALT_CURED;
            // Message de soin de Vampigraine : le 1er argument peut être celui qui récupère les PV, pas la cible
            if (k.startsWith("heal") || k.startsWith("drain")) {
                return;
            }
            ClientBattlePokemon p = resolve(battle, arg(args, 0));
            if (p != null) {
                boolean changed = k.startsWith("end") ? set.remove(p.getUuid()) : set.add(p.getUuid());
                if (changed) fieldVersion++;
            }
            return;
        }
        if (k.contains("courtchange") || k.contains("swapsideconditions")) {
            swapSides();
            return;
        }
        String fixedAbility = FIXED_ABILITIES.get(k);
        if (fixedAbility != null) {
            ability(battle, arg(args, 0), fixedAbility);
            return;
        }
        String fixedItem = FIXED_ITEMS.get(k);
        if (fixedItem != null) {
            item(battle, arg(args, FIXED_ITEM_HOLDER.getOrDefault(k, 0)), fixedItem);
            return;
        }
        if (ITEM_KEYS.contains(k)) {
            item(battle, arg(args, 0), itemId(arg(args, 1)));
            if (k.equals("item.thief") || k.equals("item.bestow")) {
                itemGone(battle, arg(args, 2), itemId(arg(args, 1))); // l'autre Pokémon n'a plus cet objet
            }
            return;
        }
        if (k.startsWith("enditem.") || k.equals("item.eat")) {
            String used = itemId(arg(args, 1));
            if (used.isEmpty() && k.startsWith("enditem.")) {
                used = knownItem(k.substring("enditem.".length())); // "enditem.occaberry" → Baie Chocco
            }
            itemGone(battle, arg(args, 0), used);
        }
    }

    /** "start.protosynthesisatk" → "atk" ; "" si la stat n'est pas dans le texte. */
    private static String paradoxStat(String s) {
        String t = s.toLowerCase(java.util.Locale.ROOT);
        for (String stat : new String[]{"atk", "def", "spa", "spd", "spe"}) {
            if (t.endsWith(stat)) {
                return stat;
            }
        }
        return "";
    }

    // --- Mise à jour des infos d'un Pokémon ---

    @Nullable
    private static Revealed revealed(ClientBattle battle, @Nullable Object nameArg) {
        ClientBattlePokemon pokemon = resolve(battle, nameArg);
        return pokemon == null ? null : REVEALED.computeIfAbsent(pokemon.getUuid(), u -> new Revealed());
    }

    private static void move(ClientBattle battle, @Nullable Object who, String move) {
        if (move.isEmpty() || move.equals("struggle")) {
            return;
        }
        if (move.equals("batonpass")) {
            ClientBattlePokemon passer = resolve(battle, who);
            if (passer != null) PASSING.add(passer.getUuid());
        }
        Revealed r = revealed(battle, who);
        if (r != null && r.moves.add(move)) {
            r.version++;
        }
    }

    private static void ability(ClientBattle battle, @Nullable Object who, String ability) {
        if (ability.isEmpty()) {
            return;
        }
        Revealed r = revealed(battle, who);
        if (r != null && !ability.equals(r.ability)) {
            r.ability = ability;
            r.version++;
        }
    }

    private static void item(ClientBattle battle, @Nullable Object who, String item) {
        if (item.isEmpty()) {
            return;
        }
        Revealed r = revealed(battle, who);
        if (r != null && !item.equals(r.item)) {
            r.item = item;
            r.version++;
        }
    }

    private static void itemGone(ClientBattle battle, @Nullable Object who, String usedItem) {
        Revealed r = revealed(battle, who);
        if (r == null) {
            return;
        }
        if (!usedItem.isEmpty()) {
            r.lostItem = usedItem;
        } else if (r.item != null && !r.item.isEmpty()) {
            r.lostItem = r.item;
        }
        r.item = "";
        r.version++;
    }

    private static void tera(ClientBattle battle, @Nullable Object who, String type) {
        if (type.isEmpty()) {
            return;
        }
        Revealed r = revealed(battle, who);
        if (r != null && !type.equals(r.tera)) {
            r.tera = type;
            r.version++;
        }
    }

    // --- Qui est ce Pokémon ? ---

    /**
     * Retrouve le Pokémon en jeu à partir de son nom dans le message : soit "Dracaufeu",
     * soit "Dresseur's Dracaufeu" (clé cobblemon.battle.owned_pokemon : dresseur + Pokémon).
     */
    @Nullable
    private static ClientBattlePokemon resolve(ClientBattle battle, @Nullable Object nameArg) {
        if (nameArg == null) {
            return null;
        }
        String owner = null;
        String name;
        if (nameArg instanceof Component component && component.getContents() instanceof TranslatableContents t
                && t.getKey().equals(PREFIX + "owned_pokemon") && t.getArgs().length >= 2) {
            owner = text(t.getArgs()[0]);
            name = text(t.getArgs()[1]);
        } else {
            name = text(nameArg);
        }
        ClientBattlePokemon fallback = null;
        boolean ambiguous = false;
        for (ClientBattleSide side : battle.getSides()) {
            for (ClientBattleActor actor : side.getActors()) {
                for (ActiveClientBattlePokemon active : actor.getActivePokemon()) {
                    ClientBattlePokemon pokemon = active.getBattlePokemon();
                    if (pokemon == null || !pokemon.getDisplayName().getString().trim().equals(name)) {
                        continue;
                    }
                    if (owner == null) {
                        if (fallback == null) {
                            fallback = pokemon;
                        } else if (fallback != pokemon) {
                            ambiguous = true; // même nom des deux côtés (miroir) : impossible de savoir lequel
                        }
                        continue;
                    }
                    if (actor.getDisplayName().getString().trim().equals(owner)) {
                        return pokemon;
                    }
                }
            }
        }
        return owner == null && !ambiguous ? fallback : null;
    }

    private static String text(@Nullable Object o) {
        if (o == null) {
            return "";
        }
        return (o instanceof Component c ? c.getString() : String.valueOf(o)).trim();
    }

    @Nullable
    private static Object arg(Object[] args, int index) {
        return index >= 0 && index < args.length ? args[index] : null;
    }

    // --- Noms → identifiants Showdown ---

    private static String moveId(@Nullable Object arg) {
        if (arg instanceof Component c && c.getContents() instanceof TranslatableContents t
                && t.getKey().startsWith("cobblemon.move.")) {
            String id = t.getKey().substring("cobblemon.move.".length());
            if (Names.move(id) != null) {
                return id;
            }
        }
        String id = Names.toId(text(arg));
        return Names.move(id) != null ? id : "";
    }

    private static String abilityId(@Nullable Object arg) {
        if (arg instanceof Component c && c.getContents() instanceof TranslatableContents t
                && t.getKey().startsWith("cobblemon.ability.")) {
            String id = t.getKey().substring("cobblemon.ability.".length());
            if (Names.ability(id) != null) {
                return id;
            }
        }
        String id = Names.toId(text(arg)); // nom anglais de Showdown : "Water Absorb" → waterabsorb
        return Names.ability(id) != null ? id : "";
    }

    private static String teraId(@Nullable Object arg) {
        String raw = "";
        if (arg instanceof Component c && c.getContents() instanceof TranslatableContents t) {
            raw = t.getKey().substring(t.getKey().lastIndexOf('.') + 1);
        }
        if (raw.isEmpty()) {
            raw = text(arg);
        }
        String id = Names.toId(raw);
        return id.equals("stellar") || Names.type(id) != null ? id : "";
    }

    private static Map<String, String> itemsByKey;
    private static Map<String, String> itemsByName;

    private static String itemId(@Nullable Object arg) {
        if (arg == null) {
            return "";
        }
        buildItemMaps();
        if (arg instanceof Component c && c.getContents() instanceof TranslatableContents t) {
            String id = itemsByKey.get(t.getKey());
            if (id != null) {
                return id;
            }
        }
        String shown = text(arg);
        String byName = itemsByName.get(Names.normalize(shown));
        if (byName != null) {
            return byName;
        }
        return knownItem(Names.toId(shown)); // nom anglais de Showdown : "Leftovers" → leftovers
    }

    private static String knownItem(String id) {
        if (id.isEmpty()) {
            return "";
        }
        return CalcEngine.knownItems().contains(id) || Names.itemsById().containsKey(id) ? id : "";
    }

    private static void buildItemMaps() {
        Map<String, Item> byId = Names.itemsById();
        if (itemsByKey != null && itemsByKey.size() == byId.size()) {
            return;
        }
        Map<String, String> keys = new HashMap<>();
        Map<String, String> names = new HashMap<>();
        for (Map.Entry<String, Item> e : byId.entrySet()) {
            keys.put(e.getValue().getDescriptionId(), e.getKey());
            names.put(Names.normalize(Names.itemName(e.getKey())), e.getKey());
        }
        itemsByKey = keys;
        itemsByName = names;
    }
}
