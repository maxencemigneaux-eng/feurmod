// Tests du calculateur (après « npm run build ») : node test.js
// Vérifie le pont JSON <-> @smogon/calc sur des cas déjà vus en jeu.
const assert = require('node:assert/strict');
require(process.argv[2] || '../src/main/resources/assets/feurcalc/js/feurcalc.js');
const api = globalThis.feurcalc;
let ok = 0;
function test(name, fn) {
  try { fn(); ok++; console.log('ok   ' + name); } catch (e) { console.log('ÉCHEC ' + name + '\n' + e.message); process.exitCode = 1; }
}
const call = (fn, x) => {
  const out = JSON.parse(fn(JSON.stringify(x)));
  assert.equal(out.error, undefined, out.error);
  return out;
};

const hoOh = { species: 'hooh', level: 100, ability: 'pressure', item: 'leftovers', nature: 'adamant',
  evs: { hp: 252, atk: 252, spe: 4 }, moves: ['sacredfire', 'bravebird'] };
const ninetales = { species: 'ninetalesalola', level: 100, ability: 'snowwarning', item: 'lightclay', nature: 'timid',
  evs: { def: 228, spd: 28, spe: 252 }, moves: ['freezedry'] };
const damage = (weather) => call(api.calc, { a: hoOh, b: ninetales, field: { weather, a: {}, b: {} } }).a.moves[0].maxPct;

test('la Neige donne +50 % de Déf aux types Glace', () => {
  assert.ok(damage('Snow') < damage('') * 0.75, `${damage('Snow')} vs ${damage('')}`);
});

test('le Soleil Intense renforce le Feu', () => {
  assert.ok(damage('Harsh Sunshine') > damage('') * 1.4);
});

test('une attaque inconnue ne casse pas le calcul', () => {
  const out = call(api.calc, { a: Object.assign({}, hoOh, { moves: ['attaquequinexistepas', 'sacredfire'] }), b: ninetales,
    field: { a: {}, b: {} } });
  assert.equal(out.a.moves[0].known, false);
  assert.ok(out.a.moves[1].maxPct > 0);
});

test('export PokePaste au format Showdown', () => {
  const text = call(api.paste, [
    Object.assign({}, hoOh, { nickname: 'Phénix', shiny: true, gender: '', teraType: 'ground',
      ivs: { hp: 31, atk: 31, def: 31, spa: 0, spd: 31, spe: 31 }, moves: ['sacredfire', 'bravebird', '', ''] }),
    Object.assign({}, ninetales, { level: 87, gender: 'F' }),
    { species: 'hodon', enName: 'Hodon', level: 100, ability: 'terre_finale', item: 'cobblemon:mega_stone', nature: 'calm', moves: ['custom_move'] },
  ]).text;
  assert.equal(text, [
    'Phénix (Ho-Oh) @ Leftovers', 'Ability: Pressure', 'Shiny: Yes', 'Tera Type: Ground',
    'EVs: 252 HP / 252 Atk / 4 Spe', 'Adamant Nature', 'IVs: 0 SpA', '- Sacred Fire', '- Brave Bird', '',
    'Ninetales-Alola (F) @ Light Clay', 'Ability: Snow Warning', 'Level: 87', 'EVs: 228 Def / 28 SpD / 252 Spe',
    'Timid Nature', '- Freeze-Dry', '',
    'Hodon @ Mega Stone', 'Ability: Terre Finale', 'Calm Nature', '- Custom Move', ''].join('\n'));
});

test('Méga : stats officielles si demandé, sinon celles du serveur', () => {
  const serveur = { hp: 60, atk: 90, def: 70, spa: 140, spd: 80, spe: 145 };
  const raichu = { species: 'raichumegay', level: 100, nature: 'timid', ability: 'noability', evs: { spa: 252, spe: 252 },
    baseStats: serveur, moves: ['thunderbolt'] };
  const off = call(api.calc, { a: Object.assign({}, raichu, { preferShowdown: true }), b: ninetales, field: { a: {}, b: {} } }).a;
  assert.equal(off.base.spa, 160);
  assert.equal(off.base.spe, 130);
  const srv = call(api.calc, { a: raichu, b: ninetales, field: { a: {}, b: {} } }).a;
  assert.equal(srv.base.spa, 140);
});

test('/feurcalc megas repère les Méga aux stats différentes', () => {
  const out = call(api.megaCheck, [
    { name: 'Raichu (Mega-Y)', id: 'raichumegay', stats: { hp: 60, atk: 90, def: 70, spa: 140, spd: 80, spe: 145 } },
    { name: 'Raichu (Mega-X)', id: 'raichumegax', stats: { hp: 60, atk: 135, def: 95, spa: 90, spd: 95, spe: 110 } },
    { name: 'Hodon (Mega)', id: 'hodonmega', stats: { hp: 1, atk: 1, def: 1, spa: 1, spd: 1, spe: 1 } },
  ]);
  assert.deepEqual(out.diff.map((d) => d.showdown), ['Raichu-Mega-Y']);
  assert.equal(out.same, 1);
  assert.deepEqual(out.unknown, ['Hodon (Mega)']);
});

console.log(`${ok} test(s) réussi(s)`);
