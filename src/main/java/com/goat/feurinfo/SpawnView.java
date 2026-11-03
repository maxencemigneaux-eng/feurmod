package com.goat.feurinfo;

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.pokemon.FormData;
import com.cobblemon.mod.common.pokemon.Species;
import com.goat.feurinfo.SpawnIndex.Entry;
import com.goat.feurinfo.SpawnIndex.Role;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/** Transforme les données d'apparition brutes en texte lisible (en français dans un jeu en français). */
final class SpawnView {
    static final int WHITE = 0xFFFFFF;
    static final int LABEL = 0x8A949E;
    static final int TEXT = 0xD8DEE4;
    static final int EXCEPT = 0xE07070;
    static final int MORE = 0x6FD3C6;
    static final int LESS = 0xC9A26B;
    static final int SNACK = 0xFFB347;
    static final int ALPHA = 0xFFC83D;
    static final int TAG_TITLE = 0xFFD866;
    static final int DIM = 0x6A7480;

    private static final List<String> BUCKETS = List.of("common", "uncommon", "rare", "ultra-rare", "boss");
    private static final int MAX_TAG_MEMBERS = 24;

    /** Une façon d'apparaître, prête à afficher. */
    record Card(int color, List<Component> lines, List<Component> tooltip) {
    }

    private SpawnView() {
    }

    // =======================================================================================
    // Sélection des apparitions de la forme affichée

    static List<Card> cards(Species species, List<FormData> forms, int formIndex) {
        List<Entry> all = SpawnIndex.get(species.getResourceIdentifier().getPath());
        if (all.isEmpty()) {
            all = SpawnIndex.get(species.getName());
        }
        List<Entry> mine = new ArrayList<>();
        for (Entry e : all) {
            if (bestForms(e, forms).contains(formIndex)) {
                mine.add(e);
            }
        }
        mine.sort(Comparator.<Entry>comparingInt(e -> bucketOrder(e.bucket()))
                .thenComparingInt(e -> e.role().ordinal())
                .thenComparing(e -> e.alpha())
                .thenComparing(e -> e.id()));

        // Même apparition au sol et sous l'eau (par ex.) → une seule carte "Au sol / Sous l'eau"
        Map<String, List<Entry>> groups = new LinkedHashMap<>();
        for (Entry e : mine) {
            String key = e.aspects() + "|" + e.alpha() + "|" + e.role() + "|" + e.leader() + "|" + e.leaderAlpha() + "|"
                    + e.herdSize() + "|" + e.bucket() + "|" + e.level() + "|" + e.condition() + "|" + e.anticondition()
                    + "|" + e.presets() + "|" + e.multipliers() + "|" + e.snackOnly();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(e);
        }
        List<Card> cards = new ArrayList<>();
        for (List<Entry> group : groups.values()) {
            cards.add(card(species, group));
        }
        return cards;
    }

    /**
     * À quelle(s) forme(s) correspond une apparition : "vulpix alolan" → forme d'Alola, "tauros paldean bull_breed=aqua"
     * → Tauros de Paldea (Aqua). Sans correspondance → forme normale (index 0).
     */
    private static Set<Integer> bestForms(Entry e, List<FormData> forms) {
        Set<String> candidates = new HashSet<>();
        for (String a : e.aspects()) {
            candidates.add(a.toLowerCase(Locale.ROOT));
        }
        int best = 0;
        Set<Integer> winners = new HashSet<>();
        for (int i = 1; i < forms.size(); i++) {
            List<String> aspects = forms.get(i).getAspects();
            if (aspects.isEmpty()) {
                continue;
            }
            int common = 0;
            for (String a : aspects) {
                if (candidates.contains(a.toLowerCase(Locale.ROOT))) {
                    common++;
                }
            }
            if (common == 0) {
                continue;
            }
            int score = common == aspects.size() ? 1000 + common : common;
            if (score > best) {
                best = score;
                winners.clear();
                winners.add(i);
            } else if (score == best) {
                winners.add(i);
            }
        }
        if (winners.isEmpty()) {
            winners.add(0);
        }
        return winners;
    }

    private static int bucketOrder(String bucket) {
        int i = BUCKETS.indexOf(bucket);
        return i < 0 ? BUCKETS.size() : i;
    }

    // =======================================================================================
    // Une carte

    private static Card card(Species species, List<Entry> group) {
        Entry e = group.get(0);
        int color = bucketColor(e.bucket());
        List<Component> lines = new ArrayList<>();

        // Rareté · niveaux · où (au sol, sous l'eau...) · alpha
        MutableComponent title = Component.literal(bucketName(e.bucket())).withStyle(s -> s.withColor(color).withBold(true));
        if (!e.level().isEmpty()) {
            title.append(sep()).append(tr("level", e.level()).withStyle(s -> s.withColor(WHITE)));
        }
        Set<String> positions = new LinkedHashSet<>();
        for (Entry x : group) {
            positions.add(positionName(x.position()));
        }
        title.append(sep()).append(Component.literal(String.join(" / ", positions)).withStyle(s -> s.withColor(WHITE)));
        if (e.alpha()) {
            title.append(sep()).append(tr("alpha").withStyle(s -> s.withColor(ALPHA).withBold(true)));
        }
        lines.add(title);

        MutableComponent herd = herdText(species, e);
        if (herd != null) {
            lines.add(herd.withStyle(s -> s.withColor(TEXT)));
        }

        // Biomes
        List<String> biomes = SpawnIndex.strings(e.condition(), "biomes");
        MutableComponent biomeLine = label("label.biomes");
        if (biomes.isEmpty()) {
            biomeLine.append(tr("biomes.any").withStyle(s -> s.withColor(WHITE)));
        } else {
            biomeLine.append(Component.literal(joinNames(biomes, SpawnView::biomeName)).withStyle(s -> s.withColor(WHITE)));
        }
        lines.add(biomeLine);

        // Conditions
        List<MutableComponent> parts = new ArrayList<>();
        if (e.snackOnly()) {
            parts.add(tr("cond.snack_only").withStyle(s -> s.withColor(SNACK).withBold(true)));
        }
        parts.addAll(describe(e.condition(), Mode.CONDITION));
        for (String preset : e.presets()) {
            if (!preset.equals("natural") && !preset.equals("wild") && !preset.equals("water") && !preset.equals("lava")) {
                parts.add(Component.literal(lookup(preset, "feurinfo.spawn.preset." + preset)));
            }
        }
        if (!parts.isEmpty()) {
            lines.add(label("label.conditions").append(join(parts, ", ", TEXT)));
        }

        // Plus / moins fréquent (multiplicateurs, hors Poké Snack)
        List<MutableComponent> more = new ArrayList<>();
        List<MutableComponent> less = new ArrayList<>();
        for (JsonObject m : e.multipliers()) {
            JsonObject cond = SpawnIndex.object(m, "condition");
            List<MutableComponent> mParts = describe(cond, Mode.BONUS);
            if (mParts.isEmpty() || !m.has("multiplier") || !m.get("multiplier").isJsonPrimitive()) {
                continue;
            }
            double mult;
            try {
                mult = m.get("multiplier").getAsDouble();
            } catch (Exception ex) {
                continue;
            }
            if (mult == 1.0) {
                continue;
            }
            MutableComponent text = join(mParts, " + ", 0).append(Component.literal(" (×" + number(mult) + ")"));
            (mult > 1 ? more : less).add(text);
        }
        if (!more.isEmpty()) {
            lines.add(label("label.more").append(join(more, ", ", MORE)));
        }
        if (!less.isEmpty()) {
            lines.add(label("label.less").append(join(less, ", ", LESS)));
        }

        // Sauf...
        List<MutableComponent> anti = describe(e.anticondition(), Mode.ANTI);
        if (!anti.isEmpty()) {
            lines.add(tr("label.except").withStyle(s -> s.withColor(EXCEPT)).append(join(anti, " + ", EXCEPT)));
        }

        return new Card(color | 0xFF000000, lines, tooltip(e, group));
    }

    @Nullable
    private static MutableComponent herdText(Species species, Entry e) {
        if (e.role() == Role.SOLO) {
            return null;
        }
        if (e.role() == Role.LEADER) {
            return e.herdSize() > 1 ? tr("herd.leader_size", e.herdSize()) : tr("herd.leader");
        }
        if (!e.leader().isEmpty()) {
            String leaderName = speciesName(e.leader());
            if (e.leaderAlpha()) {
                leaderName = I18n.get("feurinfo.spawn.herd.alpha_name", leaderName);
            }
            return tr("herd.follower_of", leaderName);
        }
        return e.herdSize() > 1 ? tr("herd.group_size", e.herdSize()) : tr("herd.group");
    }

    private static List<Component> tooltip(Entry e, List<Entry> group) {
        List<Component> lines = new ArrayList<>();
        for (String biome : SpawnIndex.strings(e.condition(), "biomes")) {
            addTagMembers(lines, biome, false);
        }
        for (String biome : SpawnIndex.strings(e.anticondition(), "biomes")) {
            addTagMembers(lines, biome, true);
        }
        Set<String> ids = new LinkedHashSet<>();
        double weight = 0;
        for (Entry x : group) {
            if (!x.id().isEmpty()) {
                ids.add(x.id());
            }
            weight = Math.max(weight, x.weight());
        }
        lines.add(tr("tooltip.footer", number(weight), String.join(", ", ids)).withStyle(s -> s.withColor(DIM)));
        return lines;
    }

    private static void addTagMembers(List<Component> lines, String biome, boolean except) {
        if (!biome.startsWith("#")) {
            return;
        }
        List<String> members = tagMembers(biome);
        if (members == null || members.isEmpty()) {
            return;
        }
        String name = biomeName(biome);
        MutableComponent header = except
                ? tr("tooltip.except_tag", name).withStyle(s -> s.withColor(EXCEPT).withBold(true))
                : Component.literal(name + " :").withStyle(s -> s.withColor(TAG_TITLE).withBold(true));
        lines.add(header);
        String text;
        if (members.size() > MAX_TAG_MEMBERS) {
            text = String.join(", ", members.subList(0, MAX_TAG_MEMBERS)) + " "
                    + I18n.get("feurinfo.spawn.tooltip.more", members.size() - MAX_TAG_MEMBERS);
        } else {
            text = String.join(", ", members);
        }
        lines.add(Component.literal(text).withStyle(ChatFormatting.GRAY));
    }

    // =======================================================================================
    // Conditions → texte

    private enum Mode { CONDITION, ANTI, BONUS }

    private static List<MutableComponent> describe(JsonObject c, Mode mode) {
        List<MutableComponent> out = new ArrayList<>();
        if (c == null || c.size() == 0) {
            return out;
        }
        if (mode != Mode.CONDITION) {
            List<String> biomes = SpawnIndex.strings(c, "biomes");
            if (!biomes.isEmpty()) {
                String names = joinNames(biomes, SpawnView::biomeName);
                out.add(mode == Mode.ANTI ? Component.literal(names) : tr("cond.biome", names));
            }
        }
        List<String> dims = SpawnIndex.strings(c, "dimensions");
        if (!dims.isEmpty()) {
            out.add(tr("cond.dimension", joinNames(dims, SpawnView::dimensionName)));
        }
        String time = SpawnIndex.string(c, "timeRange");
        if (!time.isEmpty()) {
            out.add(Component.literal(lookup(time, "feurinfo.spawn.time." + time)));
        }
        Boolean sky = bool(c, "canSeeSky");
        if (sky != null) {
            out.add(tr(sky ? "cond.sky_yes" : "cond.sky_no"));
        }
        Integer maxSky = integer(c, "maxSkyLight");
        if (maxSky != null && maxSky <= 7 && sky == null) {
            out.add(tr(maxSky == 0 ? "cond.deep" : "cond.underground"));
        }
        range(out, c, "minLight", "maxLight", "light");
        range(out, c, "minY", "maxY", "y");
        range(out, c, "minX", "maxX", "x");
        range(out, c, "minZ", "maxZ", "z");
        range(out, c, "minDepth", "maxDepth", "depth");
        Boolean rain = bool(c, "isRaining");
        if (rain != null) {
            out.add(tr(rain ? "cond.rain_yes" : "cond.rain_no"));
        }
        Boolean thunder = bool(c, "isThundering");
        if (thunder != null) {
            out.add(tr(thunder ? "cond.thunder_yes" : "cond.thunder_no"));
        }
        String moon = SpawnIndex.string(c, "moonPhase");
        if (!moon.isEmpty()) {
            out.add(moonPhase(moon));
        }
        Boolean slime = bool(c, "isSlimeChunk");
        if (slime != null) {
            out.add(tr(slime ? "cond.slime_yes" : "cond.slime_no"));
        }
        List<String> structures = SpawnIndex.strings(c, "structures");
        if (!structures.isEmpty()) {
            out.add(tr("cond.structure", joinNames(structures, SpawnView::structureName)));
        }
        List<String> nearby = SpawnIndex.strings(c, "neededNearbyBlocks");
        if (!nearby.isEmpty()) {
            out.add(tr("cond.near", joinNames(nearby, SpawnView::blockName)));
        }
        List<String> base = SpawnIndex.strings(c, "neededBaseBlocks");
        if (!base.isEmpty()) {
            out.add(tr("cond.on", joinNames(base, SpawnView::blockName)));
        }
        range(out, c, "minLureLevel", "maxLureLevel", "lure");
        String rod = SpawnIndex.string(c, "rodType");
        if (!rod.isEmpty()) {
            out.add(tr("cond.rod", itemName(rod)));
        }
        String bait = SpawnIndex.string(c, "bait");
        if (!bait.isEmpty()) {
            out.add(tr("cond.bait", itemName(bait)));
        }
        if (mode != Mode.CONDITION) {
            Boolean snack = bool(c, "isPokeSnack");
            if (snack != null && snack && mode == Mode.ANTI) {
                out.add(tr("cond.snack"));
            }
        }
        return out;
    }

    /** "Y ≥ 62", "Y ≤ 13", "Y entre -60 et 13". */
    private static void range(List<MutableComponent> out, JsonObject c, String minKey, String maxKey, String name) {
        Integer min = integer(c, minKey);
        Integer max = integer(c, maxKey);
        if (min != null && max != null) {
            out.add(tr("cond." + name + "_range", min, max));
        } else if (min != null) {
            out.add(tr("cond." + name + "_min", min));
        } else if (max != null) {
            out.add(tr("cond." + name + "_max", max));
        }
    }

    /** Minecraft : phase 0 = pleine lune, 4 = nouvelle lune. */
    private static MutableComponent moonPhase(String text) {
        Set<Integer> phases = new TreeSet<>();
        try {
            for (String part : text.split(",")) {
                part = part.trim();
                int dash = part.indexOf('-', 1);
                if (dash > 0) {
                    int a = Integer.parseInt(part.substring(0, dash).trim());
                    int b = Integer.parseInt(part.substring(dash + 1).trim());
                    for (int i = Math.min(a, b); i <= Math.max(a, b); i++) {
                        phases.add(i);
                    }
                } else if (!part.isEmpty()) {
                    phases.add(Integer.parseInt(part));
                }
            }
        } catch (NumberFormatException ex) {
            return tr("moon.other", text);
        }
        String key = switch (phases.toString()) {
            case "[0]" -> "moon.full";
            case "[4]" -> "moon.new";
            case "[0, 4]" -> "moon.full_or_new";
            case "[1, 2, 3]" -> "moon.waning";
            case "[5, 6, 7]" -> "moon.waxing";
            default -> null;
        };
        return key != null ? tr(key) : tr("moon.other", text);
    }

    // =======================================================================================
    // Noms

    static String bucketName(String bucket) {
        return lookup(bucket, "feurinfo.spawn.bucket." + bucket);
    }

    static int bucketColor(String bucket) {
        return switch (bucket) {
            case "common" -> 0xB8C4CC;
            case "uncommon" -> 0x6CCB6C;
            case "rare" -> 0x5AA8FF;
            case "ultra-rare" -> 0xC07CFF;
            case "boss" -> 0xFFB13B;
            default -> 0x9AA4AE;
        };
    }

    static String positionName(String position) {
        return lookup(position, "feurinfo.spawn.position." + position);
    }

    static String speciesName(String id) {
        Species s = PokemonSpecies.getByName(id);
        return s != null ? s.getTranslatedName().getString() : pretty(id);
    }

    /** "#cobblemon:is_forest" → "Forêts" ; "minecraft:plains" → "Plaines". */
    static String biomeName(String raw) {
        boolean tag = raw.startsWith("#");
        ResourceLocation rl = ResourceLocation.tryParse(tag ? raw.substring(1) : raw);
        if (rl == null) {
            return raw;
        }
        String dotted = rl.getNamespace() + "." + rl.getPath().replace('/', '.');
        if (tag) {
            return firstKey(rl.getPath(), "feurinfo.biometag." + dotted, "tag.worldgen.biome." + dotted);
        }
        return firstKey(rl.getPath(), "biome." + dotted);
    }

    static String structureName(String raw) {
        boolean tag = raw.startsWith("#");
        ResourceLocation rl = ResourceLocation.tryParse(tag ? raw.substring(1) : raw);
        if (rl == null) {
            return raw;
        }
        String dotted = rl.getNamespace() + "." + rl.getPath().replace('/', '.');
        return firstKey(rl.getPath(), "feurinfo.structure." + dotted);
    }

    static String blockName(String raw) {
        boolean tag = raw.startsWith("#");
        ResourceLocation rl = ResourceLocation.tryParse(tag ? raw.substring(1) : raw);
        if (rl == null) {
            return raw;
        }
        String dotted = rl.getNamespace() + "." + rl.getPath().replace('/', '.');
        if (tag) {
            return firstKey(rl.getPath(), "feurinfo.blocktag." + dotted, "tag.block." + dotted, "tag.item." + dotted);
        }
        return firstKey(rl.getPath(), "block." + dotted, "item." + dotted);
    }

    static String itemName(String raw) {
        ResourceLocation rl = ResourceLocation.tryParse(raw);
        if (rl == null) {
            return raw;
        }
        String dotted = rl.getNamespace() + "." + rl.getPath().replace('/', '.');
        return firstKey(rl.getPath(), "item." + dotted, "block." + dotted);
    }

    static String dimensionName(String raw) {
        ResourceLocation rl = ResourceLocation.tryParse(raw);
        if (rl == null) {
            return raw;
        }
        return firstKey(rl.getPath(), "feurinfo.dimension." + rl.getNamespace() + "." + rl.getPath().replace('/', '.'));
    }

    private static String firstKey(String path, String... keys) {
        for (String key : keys) {
            if (I18n.exists(key)) {
                return I18n.get(key);
            }
        }
        return pretty(path);
    }

    private static String lookup(String raw, String key) {
        return I18n.exists(key) ? I18n.get(key) : pretty(raw);
    }

    /** "cobblemon:ruins/luna_henge_ruins" → "Luna henge ruins" ; "is_snowy_forest" → "Snowy forest". */
    static String pretty(String path) {
        String last = path.substring(path.lastIndexOf('/') + 1);
        last = last.substring(last.indexOf(':') + 1);
        if (last.startsWith("is_")) {
            last = last.substring(3);
        } else if (last.startsWith("has_")) {
            last = last.substring(4);
        }
        String text = last.replace('_', ' ').replace('-', ' ').trim();
        return text.isEmpty() ? path : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    // =======================================================================================
    // Biomes contenus dans un tag (#cobblemon:is_forest → Forêt, Forêt de bouleaux...)

    private static WeakReference<ClientLevel> cacheLevel = new WeakReference<>(null);
    private static final Map<String, List<String>> MEMBERS = new HashMap<>();

    @Nullable
    static List<String> tagMembers(String raw) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            return null;
        }
        if (cacheLevel.get() != level) {
            MEMBERS.clear();
            cacheLevel = new WeakReference<>(level);
        }
        if (MEMBERS.containsKey(raw)) {
            return MEMBERS.get(raw);
        }
        List<String> result = null;
        try {
            ResourceLocation rl = ResourceLocation.tryParse(raw.substring(1));
            if (rl != null) {
                Registry<Biome> registry = level.registryAccess().registryOrThrow(Registries.BIOME);
                Optional<HolderSet.Named<Biome>> set = registry.getTag(TagKey.create(Registries.BIOME, rl));
                if (set.isPresent()) {
                    Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
                    for (Holder<Biome> holder : set.get()) {
                        holder.unwrapKey().ifPresent(key -> names.add(biomeName(key.location().toString())));
                    }
                    result = new ArrayList<>(names);
                }
            }
        } catch (Exception ex) {
            result = null;
        }
        MEMBERS.put(raw, result);
        return result;
    }

    // =======================================================================================
    // Outils

    private static MutableComponent tr(String key, Object... args) {
        return Component.translatable("feurinfo.spawn." + key, args);
    }

    private static MutableComponent label(String key) {
        return tr(key).withStyle(s -> s.withColor(LABEL));
    }

    private static MutableComponent sep() {
        return Component.literal(" · ").withStyle(s -> s.withColor(DIM));
    }

    /** Assemble des morceaux ; color = 0 → garde les couleurs des morceaux. */
    private static MutableComponent join(List<MutableComponent> parts, String separator, int color) {
        MutableComponent out = Component.empty();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                out.append(Component.literal(separator).withStyle(s -> s.withColor(DIM)));
            }
            MutableComponent part = parts.get(i);
            if (color != 0 && part.getStyle().getColor() == null) {
                part.withStyle(s -> s.withColor(color));
            }
            out.append(part);
        }
        return out;
    }

    private interface Namer {
        String name(String raw);
    }

    private static String joinNames(List<String> raws, Namer namer) {
        Set<String> names = new LinkedHashSet<>();
        for (String raw : raws) {
            names.add(namer.name(raw));
        }
        return String.join(", ", names);
    }

    private static String number(double value) {
        String text;
        if (value == Math.rint(value)) {
            text = String.valueOf((long) value);
        } else {
            text = String.format(Locale.ROOT, "%.2f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return text.replace(".", I18n.get("feurinfo.spawn.decimal"));
    }

    @Nullable
    private static Boolean bool(JsonObject c, String key) {
        if (!c.has(key) || !c.get(key).isJsonPrimitive()) {
            return null;
        }
        JsonElement e = c.get(key);
        try {
            return e.getAsJsonPrimitive().isBoolean() ? e.getAsBoolean() : Boolean.parseBoolean(e.getAsString());
        } catch (Exception ex) {
            return null;
        }
    }

    @Nullable
    private static Integer integer(JsonObject c, String key) {
        if (!c.has(key) || !c.get(key).isJsonPrimitive()) {
            return null;
        }
        try {
            return (int) Math.round(c.get(key).getAsDouble());
        } catch (Exception ex) {
            return null;
        }
    }
}
