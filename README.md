# Legacy World Fix

Mod Fabric pour **Minecraft Java 26.3** qui répare le message :

> *« This world must be opened in an older version (like 1.6.4) to be safely converted. »*
> (FR : « Ce monde doit être ouvert dans une version plus ancienne (comme la 1.6.4) pour être converti en toute sécurité. »)

Il restaure les conversions **pré-Anvil** que Mojang a retirées du jeu à partir de la
série 26.x, et remet ainsi les mondes de **Minecraft 1.0 (novembre 2011)** — et de toute la
zone McRegion (Beta 1.3 → 1.1.2) — sur les rails de mise à jour officielles, jusqu'à la 26.3.

**Nouveau dans la 1.8.1 — les données joueur sont restaurées (inventaire, position, XP, coffre ender)** :

- **le vrai bug** : depuis la 1.21.5 le jeu lit les fichiers joueur dans `players/data/<uuid>.dat`,
  mais le mod écrivait le joueur extrait de `level.dat` dans `playerdata/` — un dossier que la
  26.3 n'ouvre jamais. Résultat : le monde se chargeait parfaitement, mais le jeu ne trouvait
  aucun fichier joueur → nouveau joueur, inventaire vide, retour au spawn. Le contenu était
  correct, il était juste au mauvais endroit ;
- le dossier joueur est désormais lu directement depuis la constante du jeu en cours
  (`LevelResource.PLAYER_DATA_DIR` → `players/data/`), donc il suit le jeu quelle que soit
  la version ;
- **fichiers joueurs par pseudo (`players/<pseudo>.dat`, ère Bêta 1.8 → 1.7.6)** : la 26.3 a
  perdu tout repli vers ces fichiers — ils sont migrés vers `players/data/<uuid-hors-ligne>.dat`
  (chaîne de datafixers PLAYER appliquée, ids d'items renommés) au lieu de rester ignorés à
  jamais ;
- **réparation automatique des mondes convertis par ≤ 1.8.0** : au prochain lancement, le
  fichier joueur coincé dans `playerdata/` est replacé dans `players/data/` (bref écran de
  conversion, simple déplacement de dossier), le joueur éventuellement encore coincé dans
  `level.dat` est extrait, et `singleplayer_uuid` est complété si absent — c'est la clé que
  `PlayerList#loadPlayerData` résout pour retrouver le joueur du solo ;
- **ordre vanilla respecté** : le joueur quitte `level.dat` AVANT la chaîne LEVEL (ordre du
  `LevelDatToSavedDataFileFix` vanilla) ; la convergence de la chaîne PLAYER est prouvée
  (sortie identique sur tag brut et tag déjà corrigé) — voir `docs/PLAYER-PROOF-1.8.1.txt` ;
- sens de migration des dossiers joueurs corrigé dans le même mouvement :
  `playerdata/` → `players/data/`, `advancements/` → `players/advancements/`,
  `stats/` → `players/stats/` (sens du `PlayerStorageFileFix` vanilla), et détection
  convergente (plus d'écran de conversion superflu après réparation).

**Nouveau dans la 1.8.0 — la conversion se fait quand tu lances le monde, plus au chargement de la liste** :

- avant : la conversion tournait pendant le chargement de la liste des mondes — l'écran
  *« Loading world list »* restait bloqué le temps de convertir (problème remonté en jeu) ;
- maintenant : la liste se charge **immédiatement** (le mod ne fait qu'une détection
  légère par monde) et la conversion démarre **au clic sur « Jouer »**, sur un écran
  dédié avec **barre de progression en temps réel** (phases : préparation → chunks
  x/y → écriture des regions, mêmes compteurs que les logs console) ;
- à la fin de la conversion, le flux vanilla reprend tout seul et le monde s'ouvre
  normalement. En cas d'échec, un écran d'erreur avec bouton *Retour* s'affiche et
  `level.dat` reste intact (rejouable) ;
- écran traduit (anglais + français), aucune action à faire ;
- sur **serveur dédié**, rien ne change : la conversion reste faite au démarrage
  avant le chargement du monde.

**Nouveau dans la 1.7.0 — le reste du pipeline DFU réécrit de zéro + support du nouveau layout de stockage 26.x** :

- **support du nouveau layout par-dimension** : les builds 26.x récents (même id
  « 26.3 », même data version 5023) lisent les chunks dans
  `dimensions/minecraft/<dimension>/region/` au lieu de `region/` à la racine du
  monde. Un monde estampillé DataVersion saute le pass vanilla qui migrait les
  dossiers — le mod détecte donc à l'exécution le layout que le jeu en cours
  lit et écrit les chunks au bon endroit (y compris le Nether `DIM-1` →
  `the_nether`, l'End `DIM1` → `the_end`, et `playerdata/` pour le fichier
  joueur) ;
- **migration automatique des mondes déjà convertis** : un monde converti dans
  l'ancien layout par une version antérieure (mod + jeu) est migré au premier
  lancement sur un build récent — exactement les déplacements de
  `DimensionStorageFileFix` (`region/` → `dimensions/minecraft/overworld/region`,
  `players/data` → `playerdata`), avec marqueur
  `legacyworldfix/migrated-to-dimension-layout.txt`. Idempotent ;
- **codec réécrit de zéro** (`RegionIo`) : moteurs Inflater/Deflater poolés par
  thread, inflate/deflate one-shot plein buffer (l'ancienne branche zlib McRegion
  traînait un `InflaterInputStream` à 512 octets), entête gzip RFC 1952 lu à la
  main avec repli sûr. Identité stricte des octets écrits sur disque prouvée 61/61
  chunks ;
- **reshape réécrit de zéro** (`transformChunkNbt`) : passe unique fusionnée — les
  sections tout-air n'allouent plus rien du tout (ni tableaux, ni tags, ni
  nibbles), la détection d'air est faite pendant la copie. Sorties identiques
  prouvées 61/61 ;
- **composition des règles DFU cachée en arrière-plan** : le premier chunk payait
  4,2-4,3 s de composition de règles (mesuré trois fois) avant de convertir quoi
  que ce soit. Le warm-up construit désormais les règles CHUNK + LEVEL + PLAYER en
  arrière-plan pendant l'écran titre / le scan de monde — visible dans le log :
  `Datafixer ready in N ms (built 0 ms, rules composed N ms, prebuilt in background)`.
  En jeu : le premier monde converti attend la composition (~4 s sur la machine de
  bench), les suivants partent immédiatement (154 ms et 393 ms mesurés) ;
- **coût propre du mod passé sous la milliseconde par chunk** : reshape 0,151 →
  0,071 ms, décodage gzip ×1,8, compression ×1,19. Le moteur DFU reste >97 % du
  temps (code Mojang, déjà parallélisé sur tous les coeurs). Chiffres et preuves :
  `docs/BENCH-PERF-1.7.0.txt`, `docs/PREUVE-TESTS-1.7.0.txt`.

**Nouveau dans la 1.6.0 — optimisation continue du DFU** :

- **les listes `Entities`/`TileEntities` vides n'entrent plus dans la chaîne DFU**
  (clé absente). Équivalence prouvée 12/12 chunks par candidat — sections
  strictement égales, champs égaux modulo absent-vs-liste-vide, la même classe
  d'équivalence que `UpgradeData Indices` en 1.4.0 ; vanilla lit une clé absente
  comme une liste vide et réécrit les listes à la première sauvegarde du chunk.
  Vérifié sur disque après E2E : chunks vides sans clé, chunks peuplés intacts
  (`docs/PREUVE-TESTS-1.6.0.txt`). Honnêteté : le gain mesuré est ~nul
  (mesure d'isolation : absentes 2,7 ms/chunk vs vides 2,7 ms/chunk) — c'est un
  changement de propreté/équivalence, pas de vitesse ;
- **candidats rejetés avec preuve** : `LastUpdate` (la chaîne le conserve en
  sortie), `HeightMap`, `TerrainPopulated` (il pilote `below_zero_retrogen`) ;
- **le coût réel des chunks peuplés est identifié et irréductible** : 2 mobs +
  3 tile entities ≈ +28-35 ms/chunk (machinerie DFU par élément, code Mojang,
  identique à l'upgrade vanilla) contre ~3-8 ms pour un chunk sans entités ;
- **log de progression live** : `Converting pre-region chunks: 600/1195
  (~N chunks/s)` toutes les ~10 % — la parallélisation devient visible et toute
  lenteur résiduelle est mesurable chez vous ;
- **pool de threads partagé** (plus de création par monde/phase) et **buffers
  gzip 64 Kio** (le défaut Java est 512 o) ;
- GC testé (G1 vs ParallelGC) : effet nul — inutile de changer vos flags.

**Nouveau dans la 1.5.0 — recodage du hot path DFU** (réponse à « recode les morceaux
du DFU mal opti ») :

- **les sections 100 % air ne traversent plus la chaîne de datafixers**. Le cylindre de
  ciel des mondes Alpha (jusqu'à la moitié d'un chunk) était transformé par le DFU en
  sections « gonflées » (palette de 9 entrées + ~2 Ko de data par section de ciel) que
  vanilla ne produit jamais à la sauvegarde — il omet ces sections. Retirées à la
  source, la sortie contient des sections minimales (ou rien), les `.mca` sont plus
  petits et le DFU marche sur moins de données. **Équivalence prouvée** : 72 chunks de
  terrains variés, 264 sections retirées — sections conservées identiques octet pour
  octet après la chaîne complète, sections retirées recréées par la chaîne elle-même en
  sections minimales `[air]` (voir `docs/BENCH-PERF-1.5.0.txt`) ;
- **le datafixer est pré-construit en arrière-plan** dès le lancement du jeu
  (`Datafixer ready in X ms (prebuilt in background)`) : ~0,8 s de construction du
  singleton + composition de règle passent derrière l'écran de titre au lieu de
  s'ajouter à la conversion ;
- **écriture des `.mca` et suppression des fichiers legacy parallélisées** (un task par
  region file ; sur NTFS, les ~1195 petits fichiers `c.<x>.<z>.dat` + dossiers base36
  coûtaient ~1 s en séquentiel).

Honnêteté sur les chiffres : le moteur DataFixerUpper lui-même (code Mojang, partagé
avec l'upgrade vanilla) reste le coût dominant (~35 ms/chunk à JIT chaud, ~fixe par
chunk) ; il est désormais réparti sur **tous les cœurs logiques**, et la ligne de log
`~X ms/chunk on N threads` dit exactement ce que fait la machine.

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

**Nouveau dans la 1.4.0** : la conversion passe encore plus vite, et les logs disent
maintenant *pourquoi* :

- **les tableaux de lumière ne traversent plus la chaîne de datafixers**. La chaîne
  vanilla elle-même les supprime en cours de route (`ChunkDeleteLightFix`,
  `ChunkLightRemoveFix`) puisque le moteur de light moderne les recalcule depuis les
  blocs au chargement : les retirer à la source économise ~4 Kio par section copiés dans
  chacune des ~500 règles, soit **~-20 % sur le temps DFU** et des `.mca` plus petits.
  Sortie vérifiée équivalente (chaîne complète exécutée sur les deux formes, sortie
  comparée champ par champ) ;
- **tous les cœurs logiques** sont utilisés (au lieu de `cœurs - 1`, plafond relevé à 32) :
  la conversion bloque le thread appelant de toute façon ;
- la **première tâche s'exécute seule** avant le fan-out : la règle DFU (composition des
  ~500 fixers pour la paire 99 → 5023) est composée une fois, single-thread, au lieu d'une
  stampede concurrente sur le cache de règles (qui n'est pas thread-safe en composition) ;
- le log de fin de conversion affiche désormais **`~X ms/chunk on N threads`** — la
  prochaine exécution dira exactement ce que fait la machine.

**Pourquoi pas Rust/JNI ?** Mesuré (benchmark sur le jar 26.3 réel) : la transformation
McRegion → Anvil + gzip + zlib coûte **~0,9 ms/chunk**, la chaîne DataFixerUpper
**~66 ms/chunk** — c'est-à-dire ~98 % du temps dans le moteur DFU de Mojang, du code Java
qu'il faut bien exécuter tel quel pour que le monde soit correctement remonté (profiling
JFR : temps réparti uniformément dans le moteur — `PointFree`, comparaisons de types,
caches — aucun fixer individuel ne domine). Réécrire l'E/S en Rust ne toucherait pas les
98 % ; réécrire le DFU signifierait réimplémenter dix ans de règles de format monde.
Le seul levier honnête : saturer les cœurs (fait) et alléger ce qui traverse la chaîne
(light, fait).

**Nouveau dans la 1.3.0** : la conversion devient **par chunk** et l'upgrade vanilla
**disparaît complètement** :

- le travail lourd (lecture gzip + transformation + chaîne DataFixerUpper + zlib) tourne
  **une tâche par chunk** dans le pool (un monde Alpha dense ne touche que quelques fichiers
  de région : l'ancienne granularité « par région » laissait les cœurs inactifs) ;
- `level.dat` est remonté **en place** par la chaîne LEVEL vanilla et reçoit le tag
  `DataVersion` courant (5023) + le tag `Version` — le passage « Starting upgrade for
  world » de vanilla **ne démarre plus du tout** (plus de copie avec hardlinks, plus de
  rescan des régions) ;
- le mod recrée ce que le « file fixing » vanilla faisait pendant ce passage :
  `data/minecraft/world_gen_settings.dat` (seed + générateurs standard des trois
  dimensions, surplat si le LevelType l'exige) et `players/data/<uuid>.dat` (joueur de
  level.dat remonté par la chaîne PLAYER — l'inventaire y reçoit ses ids `minecraft:*` —
  position conservée, UUID rétrogradé à l'UUID zéro vanilla si absent) ;
- en cas d'échec imprévu de la remontée de `level.dat`, rien n'est extrait et vanilla
  reprend la main avec son propre passage (résultat identique, plus lent).

**Nouveau dans la 1.2.0** : conversion **et** remontée de données en une seule passe
parallèle. Le mod applique désormais lui-même la chaîne DataFixerUpper vanilla (le même
appel que l'« upgrade » du jeu, tag `__context` dimension/générateur compris) et écrit les
chunks directement à la version de données courante (5023) :

- l'upgrade vanilla qui suivait la conversion (« Starting upgrade… 0 % → 100 % ») était
  réduit à un passage quasi instantané ; depuis la 1.3.0 il n'a simplement **plus lieu** ;
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
5. **Optimisations** (1.1.0 → 1.3.0) : lecture du header de région en une seule fois au
   lieu de 2048 `seek`, compression zlib en `BEST_SPEED`, et surtout une granularité
   **par chunk** : la remontée DataFixerUpper (coûteuse, thread-safe) tourne dans un pool
   d'un fil par cœur logique (max 16), une tâche par chunk — la densité d'un monde Alpha
   ne bride plus le parallélisme.
6. Ensuite, **la 26.3 fait le reste toute seule** — et depuis la 1.3.0, il ne lui reste
   presque rien à faire : les chunks comme `level.dat` sont déjà à la version de données
   courante, `world_gen_settings.dat` et `players/data/` existent, le jeu ouvre le monde
   directement (aucun « Starting upgrade for world » dans les logs).

Vérifié de bout en bout sur serveur 26.3 (voir `docs/PREUVE-TESTS.txt` pour la 1.1.0,
`docs/PREUVE-TESTS-1.1.1.txt` pour le correctif Beta 1.2, `docs/PREUVE-TESTS-1.2.0.txt`
pour la 1.2.0 et `docs/PREUVE-TESTS-1.3.0.txt` pour la 1.3.0) : mondes McRegion 1.0,
Beta 1.2 réel (format d'époque exact) et Indev arrivent tous en `DataVersion 5023`
(`Version {Name: "26.3"}`), avec les blocs en palette moderne (`minecraft:grass_block`,
`minecraft:gold_block`...), les entités de l'époque vivantes (`Health: 10.0`, UUID ajouté,
IA active), les coffres intacts et les spawners d'époque fonctionnels
(`SpawnData.entity.id: "minecraft:pig"`). Le serveur démarre sans aucun passage d'upgrade
vanilla et `world_gen_settings.dat`/`players/data/` sont créés avec le seed d'origine.
Un monde hors de portée du scan serveur (monde imbriqué) est converti par la greffe
`makeLevelSummary` : le chemin client est donc couvert aussi.

Remarque : pour ces mondes sans `DataVersion`, la chaîne vanilla laisse le terrain à sa
hauteur d'origine (y 0..127) au lieu de le décaler de -64 — c'est le comportement vanilla
du chemin « schéma 99 », le monde est jouable tel quel.

## Installation

1. Installe Fabric Loader 0.20.1 ou plus récent pour Minecraft 26.3.
2. Dépose `legacyworldfix-1.8.1+mc26.3.jar` dans le dossier `mods/`.
3. Lance le jeu (client) ou le serveur. Aucune configuration.

### Utilisation (client)

Ouvre la liste des mondes : elle s'affiche tout de suite, le monde legacy apparaît
normalement. Clique sur **Jouer** : un écran « Conversion du monde legacy » s'affiche avec
la progression (nombre de chunks, barre), puis le monde s'ouvre tout seul. Regarde les
logs pour le détail (taux chunks/s, temps total).

### Utilisation (serveur dédié)

Démarre simplement le serveur : les mondes McRegion, Infdev/Alpha et Indev du dossier sont
convertis avant le chargement, données comprises — aucun message `Starting upgrade for
world`, le monde est prêt dès le `Done`.

## Limites connues (honnêtes)

- **Minecraft Classic pré-Indev (c0.0.x)** : les niveaux dont `Blocks` est un tableau
  d'`int` sont gérés en mode best-effort, mais le launcher et la structure des sauvegardes
  de cette époque (fichiers `.mclevel` hors dossier `saves/`) n'ont jamais été testés.
- **Fichiers joueurs anciens (`players/<pseudo>.dat`, ère multi Bêta)** : migrés depuis la
  1.8.1 vers `players/data/<uuid-hors-ligne>.dat` (uuid déterministe `OfflinePlayer:<pseudo>`).
  Le joueur du solo retrouve toujours ses données via `singleplayer_uuid` ; un monde ayant
  servi en LAN retrouve chaque profil sur son uuid hors-ligne (un profil en mode en-ligne
  avec un autre uuid repartrait en spawn, ses données restant migrées sur disque).
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
- Deux mixins : `LevelStorageSource#makeLevelSummary` (HEAD) — détection légère des mondes
  legacy au chargement de la liste (patch du tag en mémoire, migration de layout des mondes
  déjà convertis, **plus aucune conversion lourde**) — et, côté client,
  `WorldOpenFlows#openWorld(String, Runnable)` (HEAD, cancellable) qui intercepte le clic
  sur « Jouer », affiche `LegacyConversionScreen` (progression en direct) et relance le flux
  vanilla une fois le monde converti. Le tag reçu par `makeLevelSummary` étant le composé
  `Data`, la greffe met à jour `version`/`LevelName` directement dedans.
- IO région réécrite en interne (lecture gzip/zlib/non compressé, écriture zlib), sans
  dépendre de la classe `RegionFile` de 26.x (qui gère désormais des fichiers de chunks
  externes). Un `AnvilRegionWriter` unique sert aux trois convertisseurs.
- Licence MIT.
