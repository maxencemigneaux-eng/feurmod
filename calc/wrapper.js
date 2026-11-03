// Feurcalc : petit pont JSON <-> @smogon/calc (le calculateur de Showdown / Showdex)
import { calculate, Generations, Pokemon, Move, Field, Side, toID } from '@smogon/calc';
import { getFinalSpeed } from '@smogon/calc/dist/mechanics/util';

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
    isProtected: false,
  });
}

function makePokemon(p) {
  const known = gen.species.get(toID(p.species));
  const overrides = {};
  if (p.baseStats) overrides.baseStats = statTable(p.baseStats, 50);
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
    overrides,
  };
  if (p.tera && p.teraType) opts.teraType = cap(p.teraType);
  const mon = new Pokemon(gen, known ? known.name : (p.name || p.species), opts);
  // Types donnés par Cobblemon (formes / datapacks) : remplacés après coup
  if (p.types && p.types.length) {
    const t = p.types.map(cap).filter(Boolean);
    if (t.length) {
      mon.species = Object.assign({}, mon.species, { types: t });
      mon.types = t;
    }
  }
  if (p.hpPercent != null) {
    const max = mon.maxHP();
    mon.originalCurHP = Math.max(1, Math.min(max, Math.round(max * Number(p.hpPercent) / 100)));
  }
  return mon;
}

function moveResults(attacker, defender, moves, field, crit) {
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
    if (data.category === 'Status') continue;
    try {
      const move = new Move(gen, data.name, {
        isCrit: !!crit,
        species: attacker.name,
        ability: attacker.ability,
        item: attacker.item,
      });
      const res = calculate(gen, attacker, defender, move, field);
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
    maxHP: mon.maxHP(),
    curHP: mon.curHP(),
    speed,
    ability: mon.ability || '',
    item: mon.item || '',
    nature: mon.nature || '',
    types: mon.types,
  };
}

function calc(json) {
  const q = typeof json === 'string' ? JSON.parse(json) : json;
  const f = q.field || {};
  const base = {
    gameType: 'Singles',
    weather: f.weather || undefined,
    terrain: f.terrain || undefined,
    isGravity: !!f.gravity,
  };
  const a = makePokemon(q.a);
  const b = makePokemon(q.b);
  const fieldAB = new Field(Object.assign({}, base, { attackerSide: makeSide(f.a), defenderSide: makeSide(f.b) }));
  const fieldBA = new Field(Object.assign({}, base, { attackerSide: makeSide(f.b), defenderSide: makeSide(f.a) }));
  const out = {
    a: summary(a, fieldAB, fieldAB.attackerSide),
    b: summary(b, fieldBA, fieldBA.attackerSide),
  };
  out.a.moves = moveResults(a, b, q.a.moves, fieldAB, q.crit);
  out.b.moves = moveResults(b, a, q.b.moves, fieldBA, q.crit);
  return JSON.stringify(out);
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

function safe(fn) {
  return function (arg) {
    try { return fn(arg); } catch (e) { return JSON.stringify({ error: String(e && e.stack || e) }); }
  };
}

globalThis.feurcalc = { calc: safe(calc), lists: safe(lists), version: '0.12.0' };
