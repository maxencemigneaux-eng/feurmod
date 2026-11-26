# Changelog Feurmod

Depuis la fusion des mods (Cobblemon Undo, FeurWiki, Feurcalc, Feur-Leg / FeurHUD) dans Feurmod. Le plus récent en haut.

## 1.0.57
- **Avertissement de mise à jour** : à la connexion, un message dans le chat avec le lien s'il y a une nouvelle version (on peut le couper dans `/feurmod`).
- **FeurWiki › Types** : bouton **Export Excel**, qui fait la fiche des Pokémon d'un ou deux types (types, talents, stats, attaques, Méga et fusions) et l'ouvre directement.
- **Tests automatiques** à chaque compilation (messages de combat, lecture de `/leg`, calculateur, export PokePaste) : un test raté bloque la version.
- Correction : les fichiers de réglages des timers n'étaient plus lus trop tôt au lancement.

## 1.0.55
- **FeurHUD** : le moment de la journée revient après l'heure (`[23:29 minuit]`, `[08:35 matin]`...), avec une option dans `/feurmod` pour l'enlever.

## 1.0.54
- **Export PokePaste** : le bouton crée directement le paste sur pokepast.es et copie son lien, à coller dans Showdown › Import/Export. Si le site ne répond pas, c'est le texte de l'équipe qui est copié.

## 1.0.53
- Reprise de la version 1.0.52.
- **Feurcalc** : bouton **Export**, qui copie ton équipe au format Showdown (surnom, objet, talent, niveau, shiny, Téra, EV, IV, nature, attaques).
- `/feurwiki export <type>` remis (il manquait dans la 1.0.52).

## 1.0.46 à 1.0.52
- **FeurHUD** (nouveau nom du panneau des timers) :
  - format `Hoenn » 00h 01m 39s [18:16]`, du plus proche au plus lointain ; le monde où tu es est en gras ;
  - noms au choix : Johto / Hoenn / Unys ou Construction / Ressources 01-02-03 ;
  - couleurs vert, jaune, orange, rouge ;
  - décompte calé sur les ticks du serveur (reste juste quand le serveur rame) ;
  - coin de l'écran, taille et fond réglables.
- **Feurcalc fidèle au combat** :
  - vrai niveau et PV exacts en combat ;
  - Téracristal ;
  - objets utilisés ou perdus (Ballon, baies, Énergie Booster, objet volé...) ;
  - compteur de Toxik ;
  - alliés K.O. (Suprématie, Dernier Hommage) ;
  - Change Côté ;
  - le sable reste 5 tours.
- **Distorsion** : bouton dans la barre du haut, rempli tout seul en combat ; la flèche de vitesse dit qui agit en premier.
- **Qui envoyer ?** tient compte des pièges, du coup reçu pendant le switch, de la priorité, de Distorsion et des chances de K.O.

## 1.0.45
- `/feurwiki export vol` : fiche `.csv` de tous les Pokémon d'un type, à ouvrir dans Excel.

## 1.0.44
- **Feurcalc** :
  - le bonus de météo s'affiche sur la stat (Déf ×1,5 des types Glace sous la Neige, Déf.Spé ×1,5 des types Roche dans le Sable) ;
  - une météo primale (Terre Finale, Mer Primaire, Souffle Delta) n'est plus remplacée par Alerte Neige, Sécheresse...

## 1.0.43
- **Feur-Leg** ne lit plus que le menu `/leg` : plus de faux timers venant d'autres menus.
- **Feurcalc** : chaque serveur a sa propre liste d'adversaires déjà vus.

## 1.0.42
- **Feurcalc** : le terrain est suivi tout seul en combat. Il couvre :
  - météo et champ ;
  - Gravité ;
  - Protection, Mur Lumière, Voile Aurore ;
  - Piège de Roc, Picots (jusqu'à 3), Vent Arrière.
- **Relais** : les boosts passent au remplaçant.

## 1.0.40
- **Feurcalc** : bouton **Z** pour calculer les capacités Z, comme Showdex.

## 1.0.39
- **Feurcalc** : les boosts sont suivis depuis les messages du combat (Danse-Lames, Intimidation, Buée Noire, échanges de stats...). Avant, un boost n'était plus compté au tour suivant.

## 1.0.37
- Réglages : bouton **Réinitialiser** (avec confirmation).
- **Feurcalc** : les adversaires déjà vus sont sauvegardés d'une partie à l'autre.

## 1.0.35 et 1.0.36
- Menus retravaillés :
  - survol teinté par la couleur d'accent ;
  - bandeau d'en-tête des tableaux ;
  - colonnes alignées ;
  - même en-tête sur tous les écrans.
- Nettoyage du code.

## 1.0.30 à 1.0.34
- **Feur-Leg** : timers triés du plus proche au plus lointain.
- **FeurWiki** :
  - recherche **par attaque** (« qui apprend Distorsion ? ») ;
  - recherche **par talent** (« qui a Intimidation ? ») ;
  - ces deux recherches ont un filtre par type.
- Son « Damn N » retiré.

## 1.0.26 à 1.0.29
- **Feur-Leg** : son d'alerte au choix (Cloche ou « Damn les gens », amplifié) et volume jusqu'à 300 %.
- **FeurWiki** : son spécial sur les fiches de Roucarnage, Scalproie et Scalpereur.

## 1.0.20 à 1.0.25
- **FeurWiki** :
  - onglet **UltraShiny** ;
  - icônes de catégorie (Physique / Spéciale / Statut) ;
  - liste déroulante de recherche ;
  - nouvel écran **Pokémon par type**, qui remplace l'onglet Faiblesses.
- Le panneau des timers devient **Feur-Leg**. `/feurinfo` est supprimé, il ne reste que `/feurwiki`.
- **Feurcalc** :
  - climats primaires et Souffle Delta ;
  - ton équipe en haut de la liste « Choisir un Pokémon ».

## 1.0.16 à 1.0.18
- **Feurcalc** :
  - liste d'attaques façon Showdex (set actuel, les plus jouées avec leur %, puis toutes) ;
  - la météo et le champ sont posés par les talents (Sécheresse, Crachin, Créa-Élec...).
- **FeurWiki** (ex-Feurinfo) : nouvelle interface façon FeurDex :
  - une seule liste d'attaques avec des filtres ;
  - le set courant ;
  - les faiblesses en bref ;
  - Normal / Shiny.

## 1.0.12 à 1.0.14
- **Feurcalc** :
  - bouton **Méga** pour toutes les Méga de Cobblemon, celles du serveur comprises ; la météo ou le champ de la Méga se posent tout seuls (Méga-Dracaufeu Y → Soleil) ;
  - Paléosynthèse et Charge Quantique sont appliquées ;
  - tous les Pokémon sont au niveau 100 par défaut (réglable).

## 1.0.6 à 1.0.10 : la fusion
- **Feur-Leg intégré** : les timers des légendaires de `/leg` restent à l'écran, avec l'heure Minecraft du spawn et une alerte à 1 minute.
- **Recherche dans le PC** : une barre dans le PC, et la commande `/feursearch`.
- **FeurWiki** : onglet Évos (chaîne cliquable), tri des colonnes, filtres par type et catégorie.
- **Feurcalc** :
  - Méga-évolution ;
  - Vampigraine et Salaison ;
  - détail de fin de tour dans les K.O. ;
  - adversaires déjà vus.
- **Menu Réglages** en jeu (`/feurmod`) : thème, Annuler / Envoyer, timers.
