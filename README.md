# Legacy World Fix

Mod Fabric pour **Minecraft Java 26.3** qui répare le message :

> *« This world must be opened in an older version (like 1.6.4) to be safely converted. »*
> (FR : « Ce monde doit être ouvert dans une version plus ancienne (comme la 1.6.4) pour être converti en toute sécurité. »)

Il restaure la conversion **McRegion → Anvil** que Mojang a retirée du jeu à partir de la
série 26.x, et remet ainsi les mondes de **Minecraft 1.0 (novembre 2011)** — et de toute la
zone McRegion (Beta 1.3 → 1.1.2) — sur les rails de mise à jour officielles, jusqu'à la 26.3.

## Pourquoi ce message apparaît

Le monde 1.0 stocke ses chunks dans des fichiers `region/*.mcr` (« McRegion », `level.dat`
marqué `version: 19132`). Jusqu'à la 1.21.x, le jeu contenait une classe `McRegionUpgrader`
qui convertissait ces mondes vers le format Anvil (`version: 19133`). Cette classe a été
supprimée du jeu : la 26.3 n'arrive donc plus à ouvrir ces mondes et affiche le message
ci-dessus en proposant de passer par une vieille version.

Ce que la 26.3 sait toujours très bien faire, c'est **mettre à jour les données d'un monde
Anvil ancien** : son « file fixer » plus sa chaîne DataFixerUpper (schémas à partir de la
version de données 99, c'est-à-dire l'ère 1.8/1.9) remontent un monde ancien jusqu'à la
version de données courante (5023 en 26.3). Il ne manquait *que* le premier maillon
McRegion → Anvil. C'est exactement ce que ce mod recrée.

## Ce que fait le mod

1. **Convertisseur McRegion → Anvil réécrit** (ce que faisait l'ancien `McRegionUpgrader`) :
   - chaque `r.X.Z.mcr` (overworld, nether `DIM-1`, end `DIM1`) est réécrit en `r.X.Z.mca`
     (le conteneur est identique, ce sont les chunks qui changent) ;
   - l'ancien stockage plat 16x16x128 (ordre x,z,y) est découpé en 16 sections de 16x16x16
     (ordre y,z,x), blocs et lumières compris ;
   - les points de vie des entités et du joueur écrits avant la 1.6 (tag `Health` en `short`)
     sont normalisés en `float` (ce que faisait l'ouverture en 1.6.4 à l'époque) ;
   - `level.dat` (et `level.dat_old`) passe à `version: 19133`.
   - Le tout est idempotent : un monde déjà converti ou déjà Anvil n'est jamais retouché.
2. **Déclenchement automatique** :
   - client : dès que la liste des mondes lit le résumé du monde (greffe sur
     `LevelStorageSource#makeLevelSummary`), la conversion tourne hors du thread graphique,
     puis le monde s'affiche normalement avec le bouton « Mettre à jour et jouer » ;
   - serveur dédié : scan du dossier courant au démarrage (avant toute lecture du monde),
     en plus de la même greffe de sécurité.
3. Ensuite, **la 26.3 fait le reste toute seule** : file fixing, puis remontée des données
   (blocs renommés en `minecraft:*`, entités avec UUID, entités déplacées dans `entities/`,
   extension de hauteur -64..320, statuts de chunks, etc.).

Vérifié de bout en bout sur serveur 26.3 : un monde McRegion 1.0 (fabriqué avec le format
exact de 2011) et un monde généré par le **vrai serveur officiel 1.2.5** arrivent tous les
deux en `DataVersion 5023` (`Version {Name: "26.3"}`), avec les blocs en palette moderne
(`minecraft:grass_block`, `minecraft:chest`...), le cochon de 2011 vivant (`Health: 10.0`,
UUID ajouté) et les coffres intacts.

## Installation

1. Installe Fabric Loader 0.20.1 ou plus récent pour Minecraft 26.3.
2. Dépose `legacyworldfix-1.0.0+mc26.3.jar` dans le dossier `mods/`.
3. Lance le jeu (client) ou le serveur. Aucune configuration.

### Utilisation (client)

Ouvre la liste des mondes : le monde 1.0 apparaît normalement (le mod le convertit en
McRegion → Anvil à ce moment, regarde les logs). Clique dessus : le jeu propose
« Mettre à jour et jouer » — c'est le flux officiel de mise à niveau, laisse-le finir.
Le monde est ensuite en 26.3.

### Utilisation (serveur dédié)

Démarre simplement le serveur : les mondes McRegion du dossier sont convertis avant le
chargement, puis la mise à niveau officielle s'exécute (messages `Starting upgrade for
world ...` / `Upgrade done`).

## Limites connues (honnêtes)

- **Beta 1.2 et avant (Alpha, Indev, Infdev)** : non géré. Ces mondes ne sont même pas au
  format McRegion ; le jeu n'a plus aucun lecteur pour eux.
- **Spawners de donjons des mondes pré-1.9** : au premier chargement d'un chunk, vanilla
  peut noter `Failed to fetch mob spawner entity ...` et réinitialiser le spawner. C'est un
  comportement vanilla de la remontée de données, indépendant de ce mod.
- Les mondes Anvil sans `DataVersion` (1.2 → 1.8) n'ont même pas besoin du mod : la 26.3
  les gère déjà via son flux « Mettre à jour et jouer ».
- Comme pour toute conversion de version, **fais une copie du dossier du monde avant**.
  Le mod convertit sur place (le `.mcr` est supprimé après écriture réussie du `.mca`) ;
  en cas d'échec, `level.dat` n'est pas modifié et le message d'origine reste affiché.

## Compilation

```bash
./gradlew build
```

Java 25 est requis (c'est la version imposée par Minecraft 26.3). Le jar est produit dans
`build/libs/`.

## Détails techniques

- Cible : Fabric Loader 0.20.1, Minecraft 26.3 (classes du jeu dé-obfusquées : le mod n'a
  besoin d'aucun mappings), Java 25.
- Un seul mixin : `LevelStorageSource#makeLevelSummary` (HEAD), le point de passage unique
  des résumés de mondes côté client (liste, quick play) et côté serveur (`Main`).
- IO région réécrite en interne (lecture gzip/zlib/non compressé, écriture zlib), sans
  dépendre de la classe `RegionFile` de 26.x (qui gère désormais des fichiers de chunks
  externes).
- Licence MIT.
