// Feurcalc : petit pont JSON <-> @smogon/calc (le calculateur de Showdown / Showdex)
import { calculate, Generations, Pokemon, Move, Field, Side, toID } from '@smogon/calc';
import { getFinalSpeed, getQPBoostedStat, isGrounded } from '@smogon/calc/dist/mechanics/util';

const gen = Generations.get(9);
const STATS = ['hp', 'atk', 'def', 'spa', 'spd', 'spe'];

function nameOf(table, x) {
  if (!x) return undefined;
  const v = table.get(toID(x));
  return v ? v.name : undefined;
}

function cap(t) {
  if (!t) return undefined;
  const v = gen.types.get(toID(t));
  return v ? v.name : undefined;
}

function statTable(src, def) {
  const out = {};
  for (const s of STATS) {
    const v = src && src[s] != null ? Number(src[s]) : def;
    out[s] = isNaN(v) ? def : v;
  }
  return out;
}

function makeSide(s) {
  s = s || {};
  return new Side({
    isReflect: !!s.reflect,
    isLightScreen: !!s.lightScreen,
    isAuroraVeil: !!s.auroraVeil,
    isSR: !!s.sr,
    spikes: Number(s.spikes) || 0,
    isTailwind: !!s.tailwind,
    isHelpingHand: !!s.helpingHand,
    isFriendGuard: !!s.friendGuard,
    isProtected: false,
    isSeeded: !!s.seeded,
    isSaltCure: !!s.saltCure,
  });
}

// Les Méga-évolutions d'une espèce (ex. Dracaufeu → ["Charizard-Mega-X", "Charizard-Mega-Y"])
function megaNames(speciesId) {
  const base = gen.species.get(toID(speciesId));
  if (!base) return [];
  const root = base.baseSpecies ? gen.species.get(toID(base.baseSpecies)) || base : base;
  return (root.otherFormes || []).filter((n) => /-Mega/.test(n));
}

function makePokemon(p) {
  // Méga-évolution : on remplace l'espèce par sa forme Méga (stats, types, talent) et on lui donne sa méga-gemme
  if (p.mega > 0) {
    const megas = megaNames(p.species);
    if (megas.length) {
      const ms = gen.species.get(toID(megas[Math.min(p.mega, megas.length) - 1]));
      if (ms) {
        p = Object.assign({}, p, {
          species: ms.name,
          name: undefined,
          baseStats: undefined,
          types: undefined,
          weightkg: undefined,
          ability: ms.abilities && ms.abilities[0],
          item: ms.requiredItem || '', // un Méga tient sa méga-gemme (pas de Restes)
          tera: false,
        });
      }
    }
  }
  const known = gen.species.get(toID(p.species));
  const overrides = {};
  // Méga : les stats officielles de Showdown passent avant celles de l'addon du serveur (si l'option est cochée)
  if (p.baseStats && !(p.preferShowdown && known)) overrides.baseStats = statTable(p.baseStats, 50);
  if (p.weightkg) overrides.weightkg = Number(p.weightkg);
  if (!known) {
    overrides.name = p.name || p.species;
    overrides.abilities = { 0: nameOf(gen.abilities, p.ability) || 'No Ability' };
    if (!overrides.baseStats) overrides.baseStats = statTable({}, 80);
    if (!overrides.weightkg) overrides.weightkg = 50;
    overrides.types = (p.types || ['Normal']).map(cap).filter(Boolean);
    if (!overrides.types.length) overrides.types = ['Normal'];
  }
  const level = Math.max(1, Math.min(100, Number(p.level) || 50));
  const opts = {
    level,
    ability: nameOf(gen.abilities, p.ability),
    item: nameOf(gen.items, p.item),
    nature: nameOf(gen.natures, p.nature) || 'Hardy',
    ivs: statTable(p.ivs, 31),
    evs: statTable(p.evs, 0),
    boosts: statTable(p.boosts, 0),
    status: p.status || '',
    // Toxik : multiplicateur des prochains dégâts (1 = 1/16, 2 = 2/16...), compté par le mod pendant le combat
    toxicCounter: Math.max(0, Number(p.toxicCounter) || 0),
    // Alliés K.O. : Suprématie (Scalpereur) et Dernier Hommage
    alliesFainted: Math.max(0, Math.min(100, Number(p.alliesFainted) || 0)),
    overrides,
  };
  if (p.tera && p.teraType) opts.teraType = cap(p.teraType);
  // Paléosynthèse / Charge Quantique : sans ça, @smogon/calc n'applique jamais le boost (même au Soleil / sur
  // Champ Électrifié / avec l'Énergie Booster). 'auto' = la meilleure stat, comme dans le jeu.
  opts.boostedStat = 'auto';
  const mon = new Pokemon(gen, known ? known.name : (p.name || p.species), opts);
  // Paléosynthèse / Charge Quantique déjà activée en combat (Énergie Booster consommée, soleil ou champ parti...) :
  // une stat fixée force le boost, même sans objet. On prend celle annoncée par le combat, sinon la meilleure.
  if (p.paradox != null && p.paradox !== false) {
    const st = String(p.paradox);
    mon.boostedStat = ['atk', 'def', 'spa', 'spd', 'spe'].includes(st) ? st : getQPBoostedStat(mon, gen);
  }
  // Types donnés par Cobblemon (formes / datapacks) : remplacés après coup
  if (p.types && p.types.length) {
    const t = p.types.map(cap).filter(Boolean);
    if (t.length) {
      mon.species = Object.assign({}, mon.species, { types: t });
      mon.types = t;
    }
  }
  const max = mon.maxHP();
  // PV exacts (fraction envoyée par le combat) en priorité, sinon le % affiché. Jamais "PV pleins" par arrondi :
  // Multiécaille, Spectro-Bouclier, Fermeté et la Ceinture Force ne marchent qu'à PV vraiment pleins.
  const ratio = p.hpRatio != null && Number(p.hpRatio) >= 0 ? Number(p.hpRatio)
    : (p.hpPercent != null ? Number(p.hpPercent) / 100 : 1);
  if (!(ratio >= 1)) {
    mon.originalCurHP = Math.max(1, Math.min(max - 1, Math.round(max * ratio)));
  } else {
    mon.originalCurHP = max;
  }
  return mon;
}

function moveResults(attacker, defender, moves, field, crit, useZ) {
  const out = [];
  const maxHP = defender.maxHP();
  for (const id of (moves || [])) {
    const r = { id, known: false, min: 0, max: 0, minPct: 0, maxPct: 0, ko: '', koN: 0, koChance: 0, desc: '' };
    out.push(r);
    if (!id) continue;
    const data = gen.moves.get(toID(id));
    if (!data) continue;
    r.known = true;
    r.category = data.category;
    r.type = data.type;
    r.bp = data.basePower;
    r.priority = data.priority || 0;
    if (data.category === 'Status') continue;
    try {
      const opts = {
        isCrit: !!crit,
        useZ: !!useZ, // capacités Z (bouton Z du calculateur)
        species: attacker.name,
        ability: attacker.ability,
        item: attacker.item,
      };
      // Dernier Hommage : 50 de puissance + 50 par allié K.O. (@smogon/calc ne le compte pas tout seul)
      if (data.name === 'Last Respects' && attacker.alliesFainted) {
        opts.overrides = { basePower: 50 + 50 * attacker.alliesFainted };
      }
      const move = new Move(gen, data.name, opts);
      const res = calculate(gen, attacker, defender, move, field);
      if (useZ && move.name !== data.name) r.zName = move.name;
      const [min, max] = res.range();
      r.min = min;
      r.max = max;
      r.minPct = Math.round((min / maxHP) * 1000) / 10;
      r.maxPct = Math.round((max / maxHP) * 1000) / 10;
      r.bp = res.move.bp;
      r.type = res.move.type;
      if (max > 0) {
        try {
          const ko = res.kochance(false);
          r.ko = ko.text || '';
          // Ce qui compte en plus à la fin du tour (Restes, poison, Vampigraine, Piège de Roc...)
          const after = /after (.+)$/.exec(r.ko);
          if (after) r.koAfter = after[1];
          r.koN = ko.n || 0;
          r.koChance = ko.chance == null ? 0 : ko.chance;
        } catch (e) { /* pas de chance de KO calculable */ }
        try { r.desc = res.fullDesc('%', false); } catch (e) { r.desc = ''; }
      }
    } catch (e) {
      r.error = String(e && e.message || e);
    }
  }
  return out;
}

function summary(mon, field, side) {
  let speed = mon.stats.spe;
  try { speed = getFinalSpeed(gen, mon, field, side); } catch (e) { /* ignore */ }
  return {
    name: mon.name,
    stats: Object.assign({}, mon.rawStats),
    base: Object.assign({}, mon.species.baseStats),
    maxHP: mon.maxHP(),
    curHP: mon.curHP(),
    speed,
    ability: mon.ability || '',
    item: mon.item || '',
    nature: mon.nature || '',
    types: mon.types,
  };
}

// Terrain commun ; en Double, les attaques qui touchent plusieurs cibles font 25 % de dégâts en moins
function fields(f) {
  const base = {
    gameType: f.doubles ? 'Doubles' : 'Singles',
    weather: f.weather || undefined,
    terrain: f.terrain || undefined,
    isGravity: !!f.gravity,
  };
  return {
    ab: new Field(Object.assign({}, base, { attackerSide: makeSide(f.a), defenderSide: makeSide(f.b) })),
    ba: new Field(Object.assign({}, base, { attackerSide: makeSide(f.b), defenderSide: makeSide(f.a) })),
  };
}

function calc(json) {
  const q = typeof json === 'string' ? JSON.parse(json) : json;
  const f = q.field || {};
  const a = makePokemon(q.a);
  const b = makePokemon(q.b);
  const fl = fields(f);
  const fieldAB = fl.ab;
  const fieldBA = fl.ba;
  const out = {
    a: summary(a, fieldAB, fieldAB.attackerSide),
    b: summary(b, fieldBA, fieldBA.attackerSide),
  };
  out.a.megas = megaNames(q.a.species);
  out.b.megas = megaNames(q.b.species);
  out.a.moves = moveResults(a, b, q.a.moves, fieldAB, q.crit, q.a.z);
  out.b.moves = moveResults(b, a, q.b.moves, fieldBA, q.crit, q.b.z);
  return JSON.stringify(out);
}

// La meilleure attaque offensive d'une liste de résultats (la plus grosse fourchette haute)
function bestOf(list) {
  let best = null;
  for (const r of list) {
    if (!r.known || r.category === 'Status' || !(r.max > 0)) continue;
    if (!best || r.maxPct > best.maxPct) best = r;
  }
  if (best) delete best.desc;
  return best;
}

// L'attaque prioritaire offensive la plus forte (Coup Bas, Vitesse Extrême...) : sa priorité, ou 0
function bestPriority(list) {
  let p = 0;
  for (const r of list) {
    if (r.known && r.category !== 'Status' && r.max > 0 && r.priority > p) p = r.priority;
  }
  return p;
}

// Ce que les pièges au sol enlèvent (en % des PV max) à un Pokémon qui entre de ce côté
function entryHazardPct(mon, side, field) {
  if (mon.hasItem('Heavy-Duty Boots') || mon.hasAbility('Magic Guard')) return 0;
  let pct = 0;
  if (side.isSR) {
    const rock = gen.types.get(toID('Rock'));
    const types = mon.teraType && mon.teraType !== 'Stellar' ? [mon.teraType] : mon.types;
    let eff = 1;
    for (const t of types) {
      const e = rock && rock.effectiveness ? rock.effectiveness[t] : undefined;
      eff *= e == null ? 1 : e;
    }
    pct += 12.5 * eff;
  }
  if (side.spikes > 0 && isGrounded(mon, field)) {
    pct += [0, 100 / 8, 100 / 6, 100 / 4][Math.min(3, side.spikes)];
  }
  return Math.round(pct * 10) / 10;
}

// "Qui envoyer ?" : pour chaque Pokémon de ton équipe, sa meilleure attaque contre l'adversaire
// et la meilleure attaque de l'adversaire contre lui.
function matchups(json) {
  const q = typeof json === 'string' ? JSON.parse(json) : json;
  const f = q.field || {};
  const fl = fields(f);
  const foe = makePokemon(q.foe);
  const foeSummary = summary(foe, fl.ba, fl.ba.attackerSide);
  const rows = [];
  for (const t of (q.team || [])) {
    const row = {};
    try {
      const mon = makePokemon(t);
      const mineAll = moveResults(mon, foe, t.moves, fl.ab, false);
      const theirsAll = moveResults(foe, mon, q.foe.moves, fl.ba, false);
      row.mine = bestOf(mineAll);
      row.theirs = bestOf(theirsAll);
      row.myPriority = bestPriority(mineAll);
      row.foePriority = bestPriority(theirsAll);
      row.hazardPct = entryHazardPct(mon, fl.ab.attackerSide, fl.ab);
      const s = summary(mon, fl.ab, fl.ab.attackerSide);
      row.speed = s.speed;
      row.maxHP = s.maxHP;
      row.curHP = s.curHP;
    } catch (e) {
      row.error = String(e && e.message || e);
    }
    rows.push(row);
  }
  return JSON.stringify({ foeSpeed: foeSummary.speed, rows });
}

function lists() {
  const items = [];
  for (const it of gen.items) items.push(it.id);
  const abilities = [];
  for (const ab of gen.abilities) abilities.push(ab.id);
  const moves = [];
  for (const m of gen.moves) moves.push(m.id);
  return JSON.stringify({ items, abilities, moves });
}

// Export de l'équipe au format Showdown (ce que lit pokepast.es) : noms anglais de Showdown,
// ou ceux de Cobblemon pour les Pokémon / objets / attaques custom du serveur.
function paste(json) {
  const team = typeof json === 'string' ? JSON.parse(json) : json;
  const word = (id) => String(id || '').replace(/^.*:/, '').replace(/[_-]+/g, ' ').replace(/\b\w/g, (c) => c.toUpperCase());
  const labels = { hp: 'HP', atk: 'Atk', def: 'Def', spa: 'SpA', spd: 'SpD', spe: 'Spe' };
  const blocks = [];
  for (const p of team) {
    const sp = gen.species.get(toID(p.species));
    const species = sp ? sp.name : (p.enName || word(p.species));
    const item = p.item ? (nameOf(gen.items, p.item) || word(p.item)) : '';
    let head = p.nickname && p.nickname !== species ? `${p.nickname} (${species})` : species;
    if (p.gender === 'M' || p.gender === 'F') head += ` (${p.gender})`;
    if (item) head += ` @ ${item}`;
    const lines = [head];
    const ability = nameOf(gen.abilities, p.ability) || word(p.ability);
    if (ability) lines.push(`Ability: ${ability}`);
    if (p.level && p.level !== 100) lines.push(`Level: ${p.level}`);
    if (p.shiny) lines.push('Shiny: Yes');
    const tera = cap(p.teraType);
    if (tera) lines.push(`Tera Type: ${tera}`);
    const evs = STATS.filter((s) => (p.evs || {})[s] > 0).map((s) => `${p.evs[s]} ${labels[s]}`);
    if (evs.length) lines.push(`EVs: ${evs.join(' / ')}`);
    const nature = nameOf(gen.natures, p.nature);
    if (nature) lines.push(`${nature} Nature`);
    const ivs = STATS.filter((s) => (p.ivs || {})[s] != null && p.ivs[s] !== 31).map((s) => `${p.ivs[s]} ${labels[s]}`);
    if (ivs.length) lines.push(`IVs: ${ivs.join(' / ')}`);
    for (const m of (p.moves || [])) {
      if (m) lines.push(`- ${nameOf(gen.moves, m) || word(m)}`);
    }
    blocks.push(lines.join('\n'));
  }
  return JSON.stringify({ text: blocks.join('\n\n') + '\n' });
}

// /feurcalc megas : les Méga du serveur comparées à Showdown. Entrée [{name, id, stats}], sortie {diff, unknown, same}.
function megaCheck(json) {
  const list = typeof json === 'string' ? JSON.parse(json) : json;
  const out = { diff: [], unknown: [], same: 0 };
  for (const m of list) {
    const sp = gen.species.get(toID(m.id));
    if (!sp) { out.unknown.push(m.name); continue; }
    const official = sp.baseStats;
    if (STATS.some((s) => Number(m.stats[s]) !== official[s])) {
      out.diff.push({ name: m.name, showdown: sp.name, server: m.stats, official: Object.assign({}, official) });
    } else {
      out.same++;
    }
  }
  return JSON.stringify(out);
}

function safe(fn) {
  return function (arg) {
    try { return fn(arg); } catch (e) { return JSON.stringify({ error: String(e && e.stack || e) }); }
  };
}

globalThis.feurcalc = { calc: safe(calc), matchups: safe(matchups), lists: safe(lists), paste: safe(paste), megaCheck: safe(megaCheck), version: '0.12.0' };
