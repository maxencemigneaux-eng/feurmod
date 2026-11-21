package com.goat.feurmod.pcsearch;

import com.cobblemon.mod.common.api.pokemon.stats.Stats;
import com.cobblemon.mod.common.client.CobblemonClient;
import com.cobblemon.mod.common.client.storage.ClientBox;
import com.cobblemon.mod.common.client.storage.ClientPC;
import com.cobblemon.mod.common.client.storage.ClientParty;
import com.cobblemon.mod.common.client.storage.ClientStorageManager;
import com.cobblemon.mod.common.pokemon.Pokemon;
import net.minecraft.ChatFormatting;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Recherche de Pokémon dans l'équipe et le PC (côté client : le PC doit avoir été ouvert au moins une fois).
 *
 * Mots-clés, combinables (tous doivent correspondre) :
 *  - un nom (français, anglais ou surnom) : "dracau", "charizard"
 *  - "shiny"
 *  - "6iv" ou "parfait" : IV tous à 31
 *  - une nature ou un talent : "rigide", "adamant", "intimidation"
 *  - "niv50" (niveau exact) ou "niv>50" / "niv<20"
 */
public final class PcSearch {

    /** page = -1 pour l'équipe, sinon numéro de boîte (0 = première). slot = case dans la boîte / place dans l'équipe. */
    public record Hit(Pokemon pokemon, int page, int slot) {
        public boolean inParty() {
            return page < 0;
        }
    }

    private PcSearch() {}

    public static List<Hit> find(String query, ClientParty party, ClientPC pc) {
        List<Hit> hits = new ArrayList<>();
        String[] tokens = norm(query).split("\\s+");
        if (query.isBlank()) return hits;
        if (party != null) {
            List<Pokemon> slots = party.getSlots();
            for (int i = 0; i < slots.size(); i++) {
                Pokemon p = slots.get(i);
                if (p != null && matches(p, tokens)) hits.add(new Hit(p, -1, i));
            }
        }
        if (pc != null) {
            List<ClientBox> boxes = pc.getBoxes();
            for (int b = 0; b < boxes.size(); b++) {
                List<Pokemon> slots = boxes.get(b).getSlots();
                for (int i = 0; i < slots.size(); i++) {
                    Pokemon p = slots.get(i);
                    if (p != null && matches(p, tokens)) hits.add(new Hit(p, b, i));
                }
            }
        }
        return hits;
    }

    /** Pour la commande /feursearch : équipe + tous les PC connus du client. */
    public static List<Hit> findEverywhere(String query) {
        ClientStorageManager storage = CobblemonClient.INSTANCE.getStorage();
        List<Hit> hits = new ArrayList<>(find(query, storage.getParty(), null));
        for (ClientPC pc : storage.getPcStores().values()) {
            hits.addAll(find(query, null, pc));
        }
        return hits;
    }

    public static boolean knowsAnyPc() {
        return !CobblemonClient.INSTANCE.getStorage().getPcStores().isEmpty();
    }

    static boolean matches(Pokemon p, String[] tokens) {
        for (String t : tokens) {
            if (t.isEmpty()) continue;
            if (!matchesToken(p, t)) return false;
        }
        return true;
    }

    private static boolean matchesToken(Pokemon p, String t) {
        if (t.equals("shiny") || t.equals("chromatique")) return p.getShiny();
        if (t.equals("6iv") || t.equals("parfait") || t.equals("perfect")) return perfectIvs(p);
        if (t.startsWith("niv") || t.startsWith("lv")) {
            String rest = t.replaceFirst("^(niv|lv)\\.?", "");
            try {
                if (rest.startsWith(">")) return p.getLevel() > Integer.parseInt(rest.substring(1));
                if (rest.startsWith("<")) return p.getLevel() < Integer.parseInt(rest.substring(1));
                if (!rest.isEmpty()) return p.getLevel() == Integer.parseInt(rest);
            } catch (NumberFormatException ignored) {
                // pas un niveau : on continue comme un nom
            }
        }
        for (String name : names(p)) {
            if (name.contains(t)) return true;
        }
        return false;
    }

    /** Tous les textes qui peuvent correspondre : noms, surnom, nature, talent (FR et EN). */
    private static List<String> names(Pokemon p) {
        List<String> out = new ArrayList<>();
        out.add(norm(p.getSpecies().getTranslatedName().getString()));
        out.add(norm(p.getSpecies().getName()));
        if (p.getNickname() != null) out.add(norm(p.getNickname().getString()));
        String nature = p.getEffectiveNature().getName().getPath();
        out.add(norm(nature));
        out.add(norm(I18n.get("cobblemon.nature." + nature)));
        String ability = p.getAbility().getName();
        out.add(norm(ability));
        out.add(norm(I18n.get("cobblemon.ability." + ability)));
        return out;
    }

    public static boolean perfectIvs(Pokemon p) {
        for (Stats s : new Stats[]{Stats.HP, Stats.ATTACK, Stats.DEFENCE, Stats.SPECIAL_ATTACK, Stats.SPECIAL_DEFENCE, Stats.SPEED}) {
            if (p.getIvs().getOrDefault(s) < 31) return false;
        }
        return true;
    }

    public static String ivLine(Pokemon p) {
        Stats[] all = {Stats.HP, Stats.ATTACK, Stats.DEFENCE, Stats.SPECIAL_ATTACK, Stats.SPECIAL_DEFENCE, Stats.SPEED};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < all.length; i++) {
            if (i > 0) sb.append('/');
            sb.append(p.getIvs().getOrDefault(all[i]));
        }
        return sb.toString();
    }

    public static String where(Hit h) {
        return h.inParty() ? "Équipe · place " + (h.slot() + 1) : "Boîte " + (h.page() + 1) + " · case " + (h.slot() + 1);
    }

    public static MutableComponent describe(Hit h) {
        Pokemon p = h.pokemon();
        MutableComponent c = Component.literal(where(h) + " — ").withStyle(ChatFormatting.GRAY);
        c.append(p.getSpecies().getTranslatedName().copy().withStyle(ChatFormatting.WHITE));
        if (p.getShiny()) c.append(Component.literal(" ★").withStyle(ChatFormatting.GOLD));
        c.append(Component.literal(" · Niv. " + p.getLevel()
                + " · " + I18n.get("cobblemon.nature." + p.getEffectiveNature().getName().getPath())
                + " · IV " + ivLine(p)).withStyle(ChatFormatting.GRAY));
        return c;
    }

    public static String norm(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s.toLowerCase(Locale.ROOT).trim(), Normalizer.Form.NFD);
        return n.replaceAll("\\p{M}", "");
    }
}
