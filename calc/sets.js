// Feurcalc : télécharge les sets Smogon et les statistiques d'utilisation de Pokémon Showdown
// (les mêmes données que Showdex, via https://pkmn.github.io/smogon/data) et en fait un petit
// fichier JSON rangé dans le .jar : src/main/resources/assets/feurcalc/sets/sets.json
//
// Lancé par la compilation (GitHub Actions) : node sets.js

const fs = require('fs');
const path = require('path');

const BASE = 'https://pkmn.github.io/smogon/data/';
const OUT = path.join(__dirname, '../src/main/resources/assets/feurcalc/sets/sets.json');

const toID = (s) => String(s == null ? '' : s).toLowerCase().replace(/[^a-z0-9]+/g, '');
const first = (x) => (Array.isArray(x) ? x[0] : x);
const STATS = ['hp', 'atk', 'def', 'spa', 'spd', 'spe'];

// Formats, dans l'ordre d'affichage (NatDex d'abord : c'est ce qui ressemble le plus à Cobblemon).
// Les formats "fantaisie" (AAA, STABmons, Hackmons...) sont laissés de côté : leurs sets ne sont pas légaux.
const TIERS = [
  ['nationaldex', 'NatDex'],
  ['nationaldexubers', 'NatDex Ubers'],
  ['nationaldexuu', 'NatDex UU'],
  ['nationaldexru', 'NatDex RU'],
  ['nationaldexmonotype', 'NatDex Monotype'],
  ['nationaldexdoubles', 'NatDex Doubles'],
  ['ou', 'OU'],
  ['ubers', 'Ubers'],
  ['uu', 'UU'],
  ['ru', 'RU'],
  ['nu', 'NU'],
  ['pu', 'PU'],
  ['zu', 'ZU'],
  ['lc', 'LC'],
  ['nfe', 'NFE'],
  ['ubersuu', 'Ubers UU'],
  ['monotype', 'Monotype'],
  ['1v1', '1v1'],
  ['battlestadiumsingles', 'BSS'],
  ['doublesou', 'Doubles OU'],
  ['vgc2027', 'VGC 2027'],
  ['vgc2026', 'VGC 2026'],
  ['vgc2025', 'VGC 2025'],
  ['vgc2024', 'VGC 2024'],
];

// Anciennes générations : seulement pour les Pokémon qui n'ont rien en gen 9
const OLD_GENS = [8, 7, 6];

async function getJson(url) {
  for (let attempt = 1; attempt <= 3; attempt++) {
    try {
      const res = await fetch(url);
      if (res.status === 404) return null;
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const text = await res.text();
      return { json: JSON.parse(text), date: res.headers.get('last-modified') };
    } catch (e) {
      if (attempt === 3) {
        console.warn(`! ${url} : ${e.message}`);
        return null;
      }
      await new Promise((r) => setTimeout(r, 2000 * attempt));
    }
  }
  return null;
}

function isoDate(header) {
  const d = header ? new Date(header) : new Date();
  return isNaN(d.getTime()) ? new Date().toISOString().slice(0, 10) : d.toISOString().slice(0, 10);
}

function topEntries(obj, skip) {
  return Object.entries(obj || {})
    .filter(([k]) => k && !skip.includes(k))
    .sort((a, b) => b[1] - a[1]);
}

const pct = (v) => Math.round(v * 100);

function evsFrom(src) {
  return STATS.map((s) => Math.max(0, Math.min(252, Number((src || {})[s]) || 0)));
}

function ivsFrom(src) {
  if (!src) return undefined;
  const ivs = STATS.map((s) => (src[s] == null ? 31 : Math.max(0, Math.min(31, Number(src[s]) || 0))));
  return ivs.every((v) => v === 31) ? undefined : ivs;
}

async function main() {
  const formats = [];
  const formatIndex = {};
  const pokemon = {};
  const add = (species, set) => {
    const key = toID(species);
    if (!key) return;
    (pokemon[key] = pokemon[key] || []).push(set);
  };
  const format = (id, label, date, battles) => {
    if (formatIndex[id] == null) {
      formatIndex[id] = formats.length;
      formats.push({ id, label, date, battles: battles || 0 });
    }
    return formatIndex[id];
  };

  // VGC : on ne garde que l'année la plus récente disponible
  let vgcKept = null;
  const isVgc = (tier) => tier.startsWith('vgc');

  // 1) Statistiques d'utilisation (le set "Usage Showdown" de chaque Pokémon)
  const stats = {};
  for (const [tier] of TIERS) {
    if (isVgc(tier) && vgcKept) continue;
    const r = await getJson(`${BASE}stats/gen9${tier}.json`);
    if (!r || !r.json || !r.json.pokemon) continue;
    stats[tier] = r;
    if (isVgc(tier)) vgcKept = tier;
    console.log(`stats gen9${tier} : ${Object.keys(r.json.pokemon).length} Pokémon, ${r.json.battles} combats`);
  }

  // 2) Sets Smogon (analyses) de la gen 9
  const sets9 = await getJson(`${BASE}sets/gen9.json`);
  if (!sets9) throw new Error('Impossible de télécharger les sets Smogon gen 9');
  const setsDate = isoDate(sets9.date);
  if (!vgcKept) {
    for (const [tier] of TIERS) {
      if (!isVgc(tier)) continue;
      if (Object.values(sets9.json).some((tiers) => tiers[tier])) {
        vgcKept = tier;
        break;
      }
    }
  }

  for (const [tier, label] of TIERS) {
    if (isVgc(tier) && tier !== vgcKept) continue;
    const st = stats[tier];
    // Sets d'usage
    if (st) {
      const fi = format(`gen9${tier}`, label, isoDate(st.date), st.json.battles);
      for (const [species, p] of Object.entries(st.json.pokemon)) {
        const count = Number(p.count) || 0;
        if (count < 30) continue;
        const ability = topEntries(p.abilities, ['No Ability', 'Nothing'])[0];
        const items = topEntries(p.items, ['Other']).slice(0, 3);
        const spread = topEntries(p.spreads || p.stats, ['Other'])[0];
        const moves = topEntries(p.moves, ['Nothing', 'Other']).slice(0, 4);
        const tera = topEntries(p.teraTypes, ['Nothing', 'Other'])[0];
        const set = { f: fi, n: '' };
        if (ability) {
          set.a = toID(ability[0]);
          set.ap = pct(ability[1]);
        }
        if (items.length) {
          set.i = items.map(([k]) => (k === 'Nothing' ? '' : toID(k)));
          set.ip = items.map(([, v]) => pct(v));
        }
        if (spread) {
          const [nature, evs] = spread[0].split(':');
          set.na = toID(nature);
          set.ev = (evs || '').split('/').map((v) => Math.max(0, Math.min(252, Number(v) || 0)));
          if (set.ev.length !== 6) set.ev = [0, 0, 0, 0, 0, 0];
          set.sp = pct(spread[1]);
        }
        set.m = moves.map(([k]) => toID(k));
        set.mp = moves.map(([, v]) => pct(v));
        if (tera) set.t = toID(tera[0]);
        const usage = p.usage && typeof p.usage === 'object' ? p.usage.weighted || p.usage.real || p.usage.raw : p.usage;
        set.u = Math.round((Number(usage) || 0) * 10000) / 10000;
        set.c = count;
        add(species, set);
      }
    }
    // Sets nommés de Smogon
    for (const [species, tiers] of Object.entries(sets9.json)) {
      const named = tiers[tier];
      if (!named) continue;
      const fi = format(`gen9${tier}`, label, st ? isoDate(st.date) : setsDate, st ? st.json.battles : 0);
      for (const [name, s] of Object.entries(named)) {
        const set = { f: fi, n: name };
        if (s.ability) set.a = toID(first(s.ability));
        if (s.item) set.i = [toID(first(s.item))];
        if (s.nature) set.na = toID(first(s.nature));
        set.ev = evsFrom(first(s.evs));
        const ivs = ivsFrom(first(s.ivs));
        if (ivs) set.iv = ivs;
        set.m = (s.moves || []).map((m) => toID(first(m))).filter(Boolean).slice(0, 4);
        if (s.teratypes) set.t = toID(first(s.teratypes));
        add(species, set);
      }
    }
  }

  // 3) Anciennes générations, pour les Pokémon qui n'ont rien en gen 9
  for (const gen of OLD_GENS) {
    const old = await getJson(`${BASE}sets/gen${gen}.json`);
    if (!old) continue;
    let added = 0;
    for (const [species, tiers] of Object.entries(old.json)) {
      if (pokemon[toID(species)] && pokemon[toID(species)].some((s) => formats[s.f].id.startsWith('gen9'))) continue;
      for (const [tier, label] of TIERS) {
        const named = tiers[tier];
        if (!named || isVgc(tier)) continue;
        const fi = format(`gen${gen}${tier}`, `Gen ${gen} ${label}`, isoDate(old.date), 0);
        for (const [name, s] of Object.entries(named)) {
          const set = { f: fi, n: name };
          if (s.ability) set.a = toID(first(s.ability));
          if (s.item) set.i = [toID(first(s.item))];
          if (s.nature) set.na = toID(first(s.nature));
          set.ev = evsFrom(first(s.evs));
          const ivs = ivsFrom(first(s.ivs));
          if (ivs) set.iv = ivs;
          set.m = (s.moves || []).map((m) => toID(first(m))).filter(Boolean).slice(0, 4);
          add(species, set);
          added++;
        }
      }
    }
    console.log(`gen ${gen} : ${added} sets ajoutés pour les Pokémon absents de la gen 9`);
  }

  // Tri : ordre des formats, puis le set d'usage en premier
  for (const key of Object.keys(pokemon)) {
    pokemon[key] = pokemon[key]
      .map((s, i) => [s, i])
      .sort((x, y) => x[0].f - y[0].f || (x[0].n === '' ? 0 : 1) - (y[0].n === '' ? 0 : 1) || x[1] - y[1])
      .map(([s]) => s);
  }

  const out = { date: setsDate, formats, pokemon };
  fs.mkdirSync(path.dirname(OUT), { recursive: true });
  fs.writeFileSync(OUT, JSON.stringify(out));
  const total = Object.values(pokemon).reduce((n, l) => n + l.length, 0);
  console.log(`sets.json : ${Object.keys(pokemon).length} Pokémon, ${total} sets, ${formats.length} formats, ${fs.statSync(OUT).size} octets`);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
