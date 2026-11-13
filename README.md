# Legacy World Fix

Mod Fabric pour **Minecraft Java 26.3** qui répare le message :

> *« This world must be opened in an older version (like 1.6.4) to be safely converted. »*
> (FR : « Ce monde doit être ouvert dans une version plus ancienne (comme la 1.6.4) pour être converti en toute sécurité. »)

Il restaure les conversions **pré-Anvil** que Mojang a retirées du jeu à partir de la
série 26.x, et remet ainsi les mondes de **Minecraft 1.0 (novembre 2011)** — et de toute la
zone McRegion (Beta 1.3 → 1.1.2) — sur les rails de mise à jour officielles, jusqu'à la 26.3.

**Nouveau dans la 1.1.0** : prise en charge des mondes **encore plus vieux**, antérieurs au
format McRegion — **Infdev/Alpha** (chunks individuels, mi-2010 → Beta 1.2) et
**Indev/Classic** (carte fixe unique, décembre 2009) — ainsi que des optimisations de
vitesse de conversion.

**Correctif 1.1.1 (important)** : la 1.1.0 convertissait les mondes Beta 1.2 réels en
mondes **vides** (« la map n'a plus ses chunks »). Cause : les vrais fichiers de chunks de
cette époque enveloppent toutes les données dans un composé `Level` (racine → Level →
xPos/Blocks/...) ; la 1.1.0 supposait les champs à la racine et écrivait des chunks d'air.
La 1.1.1 accepte les deux formes, convertit aussi le **Nether `DIM-1`** (existe depuis
Alpha 1.2) et ignore honnêtement les chunks sans tableau `Blocks` valide (vanilla les
régénère au lieu d'écrire du vide). Testé avec un monde Beta 1.2 reconstruit au format
d'époque exact — enveloppe `Level`, noms base36 signés (`1f/18/c.-d.18.dat`), `level.dat`
sans tag `version` : terrain, coffres (contenu inclus), cochons vivants, chunks négatifs
et Nether arrivent tous intacts en 26.3 (voir `docs/PREUVE-TESTS-1.1.1.txt`).

**Nouveau dans la 1.2.0** : conversion **et** remontée de données en une seule passe
parallèle. Le mod applique désormais lui-même la chaîne DataFixerUpper vanilla (le même
appel que l'« upgrade » du jeu, tag `__context` dimension/générateur compris) et écrit les
chunks directement à la version de données courante (5023) :

- l'upgrade vanilla qui suivait la conversion (« Starting upgrade… 0 % → 100 % ») devient
  un passage quasi instantané — sur le monde de test : **23 s → ~1 s**, et la préparation
  du spawn passe de ~11 s à quelques millisecondes ;
- les données d'époque sont fixes dans le même passage : un spawner Beta 1.2
  (`EntityId: "Pig"`) arrive en spawner moderne (`SpawnData.entity.id:
  "minecraft:pig"`, délais et portée recalculés) ;
- les fichiers `c.<x>.<z>.dat` convertis et leurs dossiers base36 vides sont supprimés,
  la sauvegarde ne garde plus d'arborescence pré-région fantôme ;
- si la remontée de données échouait sur un chunk (imprévu), il est écrit sans
  `DataVersion` et l'upgrade vanilla s'en charge — résultat identique, juste plus lent.

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
2. **Convertisseur Infdev/Alpha → Anvil** (1.1.0, corrigé en 1.1.1) : les mondes
   antérieurs à la Beta 1.3 n'ont pas de `region/` — chaque chunk est un fichier gzip
   `c.<x>.<z>.dat` (coordonnées en base 36, signées) dans deux niveaux de dossiers base 36.
   Le mod :
   - lit chaque chunk **avec son enveloppe `Level` d'époque** (les champs à la racine
     restent tolérés) — c'est le correctif du bug « monde Beta 1.2 vide » ;
   - convertit aussi le **Nether `DIM-1`** (présent depuis Alpha 1.2), pas seulement
     l'overworld ;
   - tolère les chunks très anciens sans tags de lumière/heightmap (recréés) et ignore
     les chunks dont le tableau `Blocks` est absent ou corrompu (vanilla régénère) ;
   - applique la même transformation McRegion → Anvil que ci-dessus,
   - écrit directement les `.mca` Anvil (une seule passe d'E/S, pas d'étape McRegion
     intermédiaire), puis met `level.dat` à `version: 19133`.
3. **Convertisseur Indev/Classic → Anvil** (nouveauté 1.1.0) : les tout premiers mondes
   (déc. 2009) sont une **carte fixe unique** dans `level.dat` (`Map/Blocks`, 256x256x64
   typiquement). Le mod :
   - découpe la carte en chunks 16x16 et applique la même transformation que ci-dessus ;
   - migre entités (cochons...) et tile entities (coffres...) dans leurs chunks ;
   - écrit un `level.dat` moderne (spawn, heure, nom) et **conserve l'original** sous
     `level.dat.indev.bak` ;
   - les ID de blocs Indev usuels (pierre, terre, minerais, eau, lave, bois, torches,
     coffres...) existent toujours ; quelques blocs exotiques (variantes de « cloth »,
     engrenage) n'existent plus et deviennent de l'air via les data fixers vanilla.
4. **Déclenchement automatique** :
   - client : dès que la liste des mondes lit le résumé du monde (greffe sur
     `LevelStorageSource#makeLevelSummary`), la conversion tourne, puis le monde s'affiche
     normalement avec le bouton « Mettre à jour et jouer » ;
   - serveur dédié : scan du dossier courant au démarrage (avant toute lecture du monde),
     en plus de la même greffe de sécurité (le serveur dédié 26.3 passe aussi par
     `makeLevelSummary` au chargement du monde).
5. **Optimisations** (1.1.0, étendues en 1.2.0) : conversion des fichiers de région
   **parallélisée** (jusqu'à 8 threads, tâche = 1 fichier de région), lecture du header de
   région en une seule fois au lieu de 2048 `seek`, compression zlib en `BEST_SPEED`, et
   depuis la 1.2.0 la **remontée DataFixerUpper est faite dans le même pool de threads**
   (le DFU vanilla est thread-safe : c'est exactement ce que fait le chargement de chunks
   normal). Le goulot d'étranglement restant (upgrade vanilla séquentiel) est éliminé :
   voir la section 1.2.0 ci-dessus.
6. Ensuite, **la 26.3 fait le reste toute seule** : file fixing, puis remontée des données
   (blocs renommés en `minecraft:*`, entités avec UUID, entités déplacées dans `entities/`,
   statuts de chunks, etc.).

Vérifié de bout en bout sur serveur 26.3 (voir `docs/PREUVE-TESTS.txt` pour la 1.1.0,
`docs/PREUVE-TESTS-1.1.1.txt` pour le correctif Beta 1.2 et
`docs/PREUVE-TESTS-1.2.0.txt` pour la 1.2.0) : mondes McRegion 1.0, Beta 1.2
réel (format d'époque exact) et Indev arrivent tous en `DataVersion 5023`
(`Version {Name: "26.3"}`), avec les blocs en palette moderne (`minecraft:grass_block`,
`minecraft:gold_block`, `minecraft:water`...), les entités de l'époque vivantes
(`Health: 10.0`, UUID ajouté, IA active — le cochon se déplace), les coffres intacts et
les spawners d'époque fonctionnels (`SpawnData.entity.id: "minecraft:pig"`).
Un monde hors de portée du scan serveur (monde imbriqué) est converti par la greffe
`makeLevelSummary` : le chemin client est donc couvert aussi.

Remarque : pour ces mondes sans `DataVersion`, la chaîne vanilla laisse le terrain à sa
hauteur d'origine (y 0..127) au lieu de le décaler de -64 — c'est le comportement vanilla
du chemin « schéma 99 », le monde est jouable tel quel.

## Installation

1. Installe Fabric Loader 0.20.1 ou plus récent pour Minecraft 26.3.
2. Dépose `legacyworldfix-1.2.0+mc26.3.jar` dans le dossier `mods/`.
3. Lance le jeu (client) ou le serveur. Aucune configuration.

### Utilisation (client)

Ouvre la liste des mondes : le monde 1.0 apparaît normalement (le mod le convertit en
McRegion → Anvil à ce moment, regarde les logs). Clique dessus : le jeu propose
« Mettre à jour et jouer » — c'est le flux officiel de mise à niveau, laisse-le finir.
Le monde est ensuite en 26.3.

### Utilisation (serveur dédié)

Démarre simplement le serveur : les mondes McRegion, Infdev/Alpha et Indev du dossier sont
convertis avant le chargement, puis la mise à niveau officielle s'exécute (messages
`Starting upgrade for world ...` / `Upgrade done`).

## Limites connues (honnêtes)

- **Minecraft Classic pré-Indev (c0.0.x)** : les niveaux dont `Blocks` est un tableau
  d'`int` sont gérés en mode best-effort, mais le launcher et la structure des sauvegardes
  de cette époque (fichiers `.mclevel` hors dossier `saves/`) n'ont jamais été testés.
- **Spawners de donjons des mondes pré-1.9** : au premier chargement d'un chunk, vanilla
  peut noter `Failed to fetch mob spawner entity ...` et réinitialiser le spawner. C'est un
  comportement vanilla de la remontée de données, indépendant de ce mod.
- **Éclairage des mondes Indev** : la lumière du ciel est approximée à la conversion puis
  recalculée par le moteur de lumière moderne au chargement.
- Les mondes Anvil sans `DataVersion` (1.2 → 1.8) n'ont même pas besoin du mod : la 26.3
  les gère déjà via son flux « Mettre à jour et jouer ».
- Comme pour toute conversion de version, **fais une copie du dossier du monde avant**.
  Le mod convertit sur place (le `.mcr`/`.dat` source reste pour Alpha — seuls les `.mcr`
  McRegion sont supprimés après écriture réussie ; pour Indev, `level.dat.indev.bak`
  conserve l'original) ; en cas d'échec, `level.dat` n'est pas modifié et le message
  d'origine reste affiché.

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
  des résumés de mondes côté client (liste, quick play) et côté serveur (`Main` →
  `fixAndGetSummaryFromTag`). Le tag reçu étant le composé `Data`, la greffe met à jour
  `version`/`LevelName` directement dedans.
- IO région réécrite en interne (lecture gzip/zlib/non compressé, écriture zlib), sans
  dépendre de la classe `RegionFile` de 26.x (qui gère désormais des fichiers de chunks
  externes). Un `AnvilRegionWriter` unique sert aux trois convertisseurs.
- Licence MIT.
