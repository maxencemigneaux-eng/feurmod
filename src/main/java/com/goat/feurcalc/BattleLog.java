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
                {"activate.protosynthesis", "protosynthesis"}, {"activate.mummy", "mummy"}, {"activate.disguise", "disguise"},
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

    /** Ce que le combat a révélé pour ce côté du calcul (seulement les Pokémon vus en combat). */
    @Nullable
    public static Revealed forMon(MonState m) {
        return m.source == MonState.Source.BATTLE ? get(m.uuid) : null;
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
            default -> handleOther(battle, k, args);
        }
    }

    private static void handleOther(ClientBattle battle, String k, Object[] args) {
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
                        }
                        continue;
                    }
                    if (actor.getDisplayName().getString().trim().equals(owner)) {
                        return pokemon;
                    }
                }
            }
        }
        return owner == null ? fallback : null;
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
