# Feurmod — les mods Cobblemon de Goat, en un seul

Pour **Cobblemon 1.8.x** (Fabric, Minecraft 1.21.1). Regroupe trois mods :

| Fonction | Comment l'utiliser |
|---|---|
| **Annuler** en combat (ex-Cobblemon Undo) | Boutons **Annuler** / **Envoyer** en bas à gauche de l'écran de combat |
| **Fiche Pokémon** (ex-Feurinfo) | `/feurinfo dracaufeu` |
| **Calculateur de dégâts** façon Showdex (ex-Feurcalc) | Touche **J**, bouton **Feurcalc** en combat, ou `/feurcalc [pokémon]` |

Tout marche **même si tu es le seul à avoir le mod** sur le serveur. Les touches sont réglables dans
Options › Commandes › **Feurmod**.

> Si tu avais les anciens mods (cobblemon-undo, cobblemon-feurinfo, cobblemon-feurcalc),
> **supprime-les** du dossier `mods` : Feurmod les remplace.

## Annuler en combat

**Tu es le seul à avoir le mod (serveur normal)** : quand tu valides ton attaque ou ton switch, ton choix
est **mis en attente** sur ton PC avec les boutons **Annuler** et **Envoyer**.
- **Envoyer** (ou **Entrée**) → le choix part au serveur.
- **Annuler** (ou **Retour arrière**) → le menu de choix revient.
- Rien ne part tant que tu n'as pas cliqué sur **Envoyer**.

**Le serveur a aussi le mod (ou solo / LAN)** : comme sur Showdown, **Annuler** reste utilisable tant que
l'adversaire n'a pas joué.

Par défaut, seulement **contre un joueur**. Réglages dans `config/cobblemon_undo.json` :
`"confirmerAvantEnvoi"` (mettre `false` pour l'envoi immédiat) et `"aussiContrePnjEtSauvages"`.

## Fiche Pokémon — `/feurinfo`

- `/feurinfo dracaufeu` (nom français, anglais ou numéro ; **Tab** pour l'autocomplétion).
- **Modèle 3D** (glisser pour tourner), formes **< >**, version shiny **☆ / ★**.
- **Types**, **talents** (le caché est marqué, survol = description) et **stats de base** avec le total.
- **Attaques** par onglet (Niveau, CT, Œufs, Tuteur, Évolution, Autres), barre de recherche dans toutes
  les catégories, survol = description.
- Onglet **Spawn** : où le Pokémon apparaît, forme par forme : rareté, niveaux, au sol / sous l'eau / pêche,
  **biomes**, conditions (jour/nuit, pluie, lune, hauteur, structures, Poké Snack...), groupes et alphas.
  Survol d'une carte = la liste des biomes concernés. En solo, les données viennent de ton monde
  (datapacks compris) ; sur un serveur, de tes mods.

## Calculateur de dégâts — touche J / `/feurcalc`

Le vrai calculateur de Pokémon Showdown (`@smogon/calc`, le même que Showdex), qui tourne dans le moteur
JavaScript déjà présent dans Cobblemon.

- **En combat** : bouton **Feurcalc** en haut de l'écran + résumé des % de dégâts et des chances de KO de
  tes attaques (et la meilleure attaque adverse ▼). Clic droit sur le bouton pour masquer le résumé.
- **Le calculateur** : toi à gauche, l'adversaire à droite. Tout est modifiable : Pokémon, niveau, PV,
  talent, objet, nature, Téra, statut, attaques, IV, EV, boosts. Météo, champ, gravité, critique,
  protections et pièges de chaque côté.
- En combat, tout se remplit seul (tes vraies stats, PV / boosts / statut de l'adversaire).
- Ligne **Set** (comme Showdex) : les **sets Smogon** et le **set le plus joué sur Showdown** (statistiques
  d'utilisation) de chaque format — NatDex, OU, UU, Monotype, VGC... L'adversaire reçoit **tout seul le set le
  plus joué**. Clic pour choisir un autre set (survol = détail avec les %), ◀ ▶ ou clic droit pour passer au suivant.
  Données [pkmn.github.io/smogon](https://pkmn.github.io/smogon), mises à jour à chaque compilation.
- Les spreads que tu règles pour un adversaire sont **retenus par espèce** (`config/feurcalc_sets.json`).

## Télécharger le .jar

Dossier **`telechargement/`** du dépôt → clique sur le .jar → bouton **Download raw file**.

Chaque compilation a son propre numéro de version (1.0.1, 1.0.2...) : le fichier s'appelle par exemple
`feurmod-1.0.3+cobblemon1.8.jar`. **Garde un seul Feurmod** dans le dossier `mods` (supprime l'ancien).

## Crédits

Calculs de dégâts : [@smogon/calc](https://github.com/smogon/damage-calc) (licence MIT).
Sets et statistiques d'utilisation : Smogon et Pokémon Showdown, via [pkmn/smogon](https://github.com/pkmn/smogon).
Icônes de types, traductions et données : Cobblemon.
