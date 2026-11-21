// Feurinfo : génère la table des évolutions (qui évolue en qui, et comment) à partir de @pkmn/dex
// (données de Pokémon Showdown). Résultat : src/main/resources/assets/feurinfo/data/evos.json
//
// Pas lancé à chaque compilation (les évolutions ne changent presque jamais). Pour regénérer :
//   cd calc && npm i --no-save @pkmn/dex && node evos.js
//
// Format : { "<id>": { "b": id de l'espèce de base, "f": forme ("Alola"...), "p": id de la pré-évolution,
//   "e": [ids des évolutions], "m": méthode, "l": niveau, "i": objet, "mv": attaque, "c": condition } }

const fs = require('fs');
const path = require('path');
const { Dex } = require('@pkmn/dex');

const OUT = path.join(__dirname, '../src/main/resources/assets/feurinfo/data/evos.json');
const toID = (s) => String(s == null ? '' : s).toLowerCase().replace(/[^a-z0-9]+/g, '');

const out = {};
for (const s of Dex.species.all()) {
  if (!s.exists || s.isCosmeticForme) continue;
  if (s.isNonstandard && !['Past', 'Unobtainable'].includes(s.isNonstandard)) continue;
  if (!s.prevo && (!s.evos || s.evos.length === 0)) continue;
  const e = { b: toID(s.baseSpecies) };
  if (s.forme) e.f = s.forme;
  if (s.prevo) e.p = toID(s.prevo);
  if (s.evos && s.evos.length) e.e = s.evos.map(toID);
  if (s.prevo) {
    e.m = s.evoType || 'level';
    if (s.evoLevel) e.l = s.evoLevel;
    if (s.evoItem) e.i = toID(s.evoItem);
    if (s.evoMove) e.mv = toID(s.evoMove);
    if (s.evoCondition) e.c = s.evoCondition;
  }
  out[toID(s.name)] = e;
}
fs.mkdirSync(path.dirname(OUT), { recursive: true });
fs.writeFileSync(OUT, JSON.stringify(out));
console.log(`${Object.keys(out).length} espèces -> ${OUT}`);
