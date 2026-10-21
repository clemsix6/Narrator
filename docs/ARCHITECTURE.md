# The Narrator 2 — Architecture

> Un mod Minecraft qui branche Claude sur un serveur pour jouer le **Narrateur** : un dieu sarcastique, drôle et sévère, qui observe les joueurs, leur parle, freine leur progression avec des défis, des châtiments et des rebondissements, et réécrit les règles du monde quand ça lui chante.

## 0. En bref

- **Mod Fabric côté serveur uniquement**, en Kotlin, pour Minecraft 26.3. Les joueurs n'ont rien à installer pour le Narrateur.
- **Claude via LLMGW** (API Anthropic Messages, clé projet). Le Directeur tourne sur **Claude Opus 5.5**.
- **Réactif par construction** : le Directeur est réveillé par des événements, ne bloque jamais sur une tâche longue (elle part chez un assistant en arrière-plan) et reçoit les nouveaux messages du chat entre deux appels d'outils.
- **Journal total** : chaque interaction est enregistrée et datée dans **DuckDB** (base analytique embarquée, SQL proche de PostgreSQL). Le Narrateur l'interroge en SQL, en lecture seule.
- **Oracle** : des outils instantanés pour savoir où est un joueur, s'il est dans une grotte, dans quel biome, quels minerais et quelles structures sont proches…
- **Mémoire en couches** avec une **fiche joueur** structurée et des **rêves** de consolidation : voir [MEMOIRE.md](MEMOIRE.md).
- **Mécaniques de jeu de première classe** : défis, châtiments, faveurs, pièges et récits sont suivis, minutés et résolus par le moteur, pas seulement racontés.
- **La Forge** : le Narrateur crée de nouvelles mécaniques (poules en orbite, mini-jeux arbitrés…) en Java compilé à chaud contre son propre SDK, une API bornée qui passe par ses outils et ses garde-fous, dans un bac à sable ; chaque module est testé dans un monde simulé avant d'être déployé, et se décharge proprement.
- **Ton et renouvellement garantis par des mécanismes** (historique des interventions, tics détectés, registres comiques, arcs à fin programmée, cooldowns), pas seulement par le prompt.

## 1. Ce qu'on retient de la v1

Les idées de la v1 étaient bonnes ; l'exécution était du bricolage. L'analyse du code et des specs de la v1 montre que la plupart des problèmes venaient de **fils non branchés** et d'une **charte qui poussait au sombre**, plus que du modèle lui-même. On repart de zéro en gardant les idées et en traitant chaque cause par un mécanisme.

| Problème de la v1 | Causes trouvées | Réponse de la v2 |
|---|---|---|
| Récits qui tournent en rond | Briefing construit avec des entrées vides : fiches joueurs, chat et état du monde n'arrivaient jamais au modèle, seuls ses propres arcs passés y étaient. Consolidation aveugle (elle ne voyait pas ce que le Narrateur disait) : les punchlines « à ne pas répéter » étaient inventées. Anciens événements rejoués comme neufs. Intentions et assistants inopérants présentés comme actifs. Arcs impossibles à clore. Cadence imposée (« au moins une action », relances quand il se taisait). | Briefing couvert par un test qui vérifie que fiches, chat et état y sont. Consolidation sur la transcription complète. Tics calculés mécaniquement à partir de ce qui a vraiment été dit. Curseur d'événements par conversation et horodatage relatif. Le modèle ne voit que des outils réellement branchés (doc générée depuis le registre d'outils). Arcs avec identifiant, fin programmée et jachère. Aucun quota : ne rien faire est une décision valable. |
| Ton qui devient glauque | Charte de surveillant : quota de 70 % de sabotage, destruction d'une partie « régulière », jamais de prévenance, tribut exigé et colère s'il est ignoré, exemples gothiques. Autoportrait et réflexions rédigés dans la voix du personnage puis réinjectés à chaque réveil : le Narrateur se mythifiait lui-même. | Voix comique d'abord, avec exemples et contre-exemples. Registres comiques qui tournent. « Méchant de dessin animé ». La persona est figée dans le prompt : la mémoire est **factuelle, à la troisième personne**, et ne réécrit jamais la voix. Carotte et bâton, lisibilité (« le joueur peut raconter en une phrase ce qui lui est arrivé »). |
| Commandes de datapack invalides | Le modèle écrivait de mémoire une syntaxe qui avait changé, et la documentation censée l'aider était fausse : filtrée au mauvais seuil de version, tirée de la dernière snapshot au lieu de 26.1.2, tronquée par la fin. | Plus de datapacks. Actions **typées** validées côté serveur. Commandes brutes analysées par le vrai parseur du serveur *avant* exécution, avec l'usage exact de la commande en cas d'erreur. Toute connaissance de version vient du serveur en cours d'exécution (arbre des commandes, registres), jamais d'Internet. |
| Plugins JS mal maîtrisés | API maison, erreurs découvertes seulement à l'exécution, crochets manquants (mort, dégâts, connexion…), assistants lancés sans aucun outil qui annonçaient « plugin écrit » sans rien faire. | Règles déclaratives validées (§10.1) et modules Java compilés contre un SDK documenté (§10.2), dans un bac à sable, avec erreurs immédiates, tests dans un monde simulé avant sauvegarde et un bus d'événements riche. Assistants avec de vrais outils. |
| Lenteur | 25 à 180 s par tour ; prompt de ~40k tokens sans cache (une horloge placée en tête du prompt l'invalidait à chaque appel) ; attentes bloquantes dans la boucle. | Préfixe stable mis en cache, informations variables à la fin ; boucle événementielle sans attente ; effort `medium` ; tâches longues déléguées. |
| Outils mal typés | Le convertisseur de schémas déclarait tableaux et booléens comme des chaînes. | SDK officiel d'Anthropic, schémas JSON stricts. |
| Mémoire en fichiers libres | Fiches en markdown réécrites par le modèle, jamais relues dans le prompt. | Fiches structurées (stats calculées par le code, personnalité avec preuves, historique noté), canon, souvenirs, chronique hiérarchique. |

Idées reprises telles quelles : jar serveur uniquement, validation des commandes par Brigadier avant exécution, canal privé `/narrateur`, canal opérateur, audit de chaque appel d'outil, intentions armées (§9.4), offrandes vérifiables, récompenses calibrées sur la progression réelle, « ne pas s'acharner sur un joueur déjà à terre ».

## 2. Vue d'ensemble

```
┌──────────────────── Serveur Minecraft 26.3 · Fabric · une seule JVM ─────────────────────┐
│                                                                                            │
│  OBSERVATEUR ──► bus d'événements ──► JOURNAL (DuckDB, écritures par lots)                 │
│  hooks Fabric          │                                                                   │
│  + mixins              ▼                                                                   │
│               filtre de saillance ──► boîte de réception du DIRECTEUR                      │
│                                                   │                                        │
│   ORACLE (lire le monde)          ◄───┐           ▼                                        │
│   ACTIONS (typées, validées)      ◄───┤      DIRECTEUR  (Opus 5.5, boucle événementielle)  │
│   MOTEUR DE JEU (défis, pièges,   ◄───┼─ outils ─┤   délègue │  ▲ résultats                │
│     châtiments, récits)               │          │           ▼  │                          │
│   FORGE (modules à chaud)         ◄───┤      ASSISTANTS (en arrière-plan)                  │
│   MÉMOIRE (fiches, canon, …)      ◄───┘      Analyste · Éclaireur · Forgeron ·             │
│                                              Chroniqueur · Scénariste · Rêveur             │
└──────────────────────────────────────────────┬─────────────────────────────────────────────┘
                                               │ HTTPS · API Anthropic Messages · clé projet
                                               ▼
                                   LLMGW (passerelle) ──► Claude
```

Tout vit dans le processus du serveur : un seul `.jar` à déposer dans `mods/`, une configuration, et c'est tout. Les données (Journal, mémoire, modules) vivent dans le dossier du monde, `world/narrator2/`.

**Trois durées de vie** (détail : [design M1/M2](design/M1_M2.md) §2) :
- **le mod** : les hooks Fabric, les commandes et les types de pièces jointes sont enregistrés une seule fois ;
- **le serveur** (`NarratorRuntime`) : bus, Journal, Observateur, garde-fous, Directeur et assistants vivent de `SERVER_STARTED` à `SERVER_STOPPED` et ne sont jamais reconstruits ;
- **les réglages** (config, prompts, textes affichés, clients Claude du Directeur et des réflexes) : remplacés d'un bloc par `/narrator reload`.

L'arrêt se fait en deux temps : à `SERVER_STOPPING`, plus aucun travail nouveau et les appels à Claude sont annulés sans rien ajouter aux conversations ; à `SERVER_STOPPED`, une fois les joueurs déconnectés et leurs départs journalisés, le Journal est vidé (avec un délai maximal) puis la base fermée.

**Ce qui doit rester cohérent avec le monde vit dans le monde** : l'index des blocs posés, les lieux visités, l'état des garde-fous (modificateurs d'attributs, confiscations, mises et offrandes en séquestre, mort protégée, grâce) et l'échéance des créatures du Narrateur sont des pièces jointes Fabric persistantes, sauvées avec le tronçon ou le joueur ; les blocs que les pièges doivent retirer sont dans leur tronçon ; les défis et duels en cours, les règles actives et les rappels du Directeur sont dans une pièce jointe **globale** (`server.globalAttachments()`), sauvée avec le monde ([design M4](design/M4.md) §3). DuckDB garde l'historique ; il n'est jamais la source d'un état de jeu.

## 3. Stack technique

| Brique | Choix | Pourquoi |
|---|---|---|
| Plateforme | Fabric (Loader + Fabric API), **côté serveur uniquement** | Léger, à jour rapidement, idéal pour un mod serveur. Aucun ajout requis chez les joueurs. |
| Langage du mod | Kotlin (via Fabric Language Kotlin), mixins en Java | Coroutines pour l'orchestration asynchrone ; DSL agréable pour le SDK de la Forge |
| Noms du jeu | Noms officiels de Mojang (le jeu n'est plus obfusqué depuis 26.1) | Le mod se compile et s'exécute avec les noms du jeu, sans remapping |
| Claude | SDK Java officiel d'Anthropic, pointé sur **LLMGW** | Protocole Anthropic Messages standard ; LLMGW gère clés projet, budgets et comptes |
| Journal et mémoire | **DuckDB** embarqué (JDBC) | Base analytique en colonnes : agrégations rapides sur des millions de lignes, SQL riche (fenêtres, `QUALIFY`, JSON, listes), aucun serveur à gérer |
| Recherche plein texte | BM25 calculé en SQL dans DuckDB, analyseur français en Kotlin | Rien de plus dans le jar ; l’extension `fts` de DuckDB n’est pas embarquable et Lucene ajouterait 6,5 Mo et un second stockage (mesures dans `design/M3.md`) |
| Code à chaud | **Java 25** compilé en mémoire par **ECJ**, embarqué (3,2 Mo), sur JDK comme sur JRE | ~7 ms par compilation une fois chaud dans le serveur (mesuré), un seul compilateur, et c'est en Java que Claude écrit le mieux (voir §10.2) |

Versions vérifiées (fixées dans `TheMod/gradle.properties`) :

| Composant | Version |
|---|---|
| Minecraft | 26.3 (sortie le 15/09/2026), Java 25 |
| Fabric Loader / Fabric API | 0.19.5 / 0.161.0+26.3 |
| Loom / Gradle | 1.18 (plugin `net.fabricmc.fabric-loom`, sans remapping) / 9.7.1 |
| Fabric Language Kotlin | 1.14.1 (Kotlin 2.4.20, coroutines 1.11) |
| SDK Anthropic Java | 2.65.0 |

Depuis 26.1, Minecraft n'est plus obfusqué : plus d'Intermediary ni de Yarn, les mods sont compilés et s'exécutent directement avec les noms de Mojang. Les modules de la Forge, eux, ne voient pas les classes du jeu : seulement le SDK du Narrateur (§10.2).

## 4. L'Observateur et le Journal

### 4.1 Ce qui est enregistré
L'objectif est « absolument chaque interaction ». Trois sources se complètent :

1. **Les événements Fabric API** : connexion/déconnexion, chat, blocs cassés, dégâts, morts, kills, réapparition, changement de dimension, sommeil, interactions avec blocs, objets et entités.
2. **Un hook générique sur les statistiques du jeu** : Minecraft comptabilise déjà presque tout (objets fabriqués, utilisés, ramassés, jetés, cassés ; mobs tués ; échanges avec les villageois ; animaux reproduits ; poissons pêchés ; enchantements ; coffres ouverts ; nuits dormies ; dégâts infligés et reçus…). En interceptant l'attribution des statistiques, on capture tout ça d'un coup, sans un mixin par action. Les statistiques de distance et de temps (très bruyantes) sont exclues : les positions sont échantillonnées à part.
3. **Des mixins ciblés** quand il faut plus de contexte : position d'un bloc posé, détail d'un échange (ce qui a été acheté, à quel villageois), aliment consommé, advancement obtenu, commande exécutée.

Plus des **événements dérivés**, calculés par l'échantillonneur de positions : entrée dans un nouveau biome, dans une structure (village, forteresse, chambre d'épreuve…), passage sous terre, vie critique, inactivité.

Les actions du Narrateur lui-même sont journalisées aussi : c'est la base de l'anti-répétition.

### 4.2 Stockage
Base : `world/narrator2/narrator.duckdb`. Écritures par lots (tampon mémoire vidé toutes les secondes via l'API *appender* de DuckDB, sur un thread dédié) : aucun coût sur le thread du serveur. Le schéma complet (version 1) est dans le [design M1/M2](design/M1_M2.md) §5.3 :

```sql
events       (id, ts, game_time, type, player_uuid, player, session_id, dim, x, y, z,
              subject,  -- bloc, objet, entité, advancement, statistique…
              amount,   -- quantité, dégâts, XP…
              other,    -- outil, arme, tueur, poste…
              detail JSON)
positions    (ts, player, dim, x, y, z, biome, structure, underground, lumière, vie, faim, véhicule, raison)
chat         (id, ts, channel, player, recipients, text, turn_id, operator)
             -- channel : public | pray | whisper | narrator | operator
players, player_names, sessions, stat_totals, chat_signals
conversations, turns, tasks, llm_calls, tool_calls, engine_decisions   -- traces (§12)
```

Les grandes tables en ajout seul n'ont ni clé primaire ni index (les identifiants sont attribués par le code). Les actions du Narrateur ne sont plus une table à part : c'est la vue `narrator_actions` sur `tool_calls`.

Plus des **vues** lisibles pour le modèle (`timeline`, `chat_log`, `narrator_actions`, `mining`, `building`, `deaths`, `kills`, `damage`, `trades`, `meals`, `crafts`, `advancements`, `visits`, `griefs`, `playtime`, `player_stats`) et des **macros SQL** pour les questions fréquentes :

```sql
SELECT * FROM hotspots('Bartok', 'block_break', 32);   -- où mine-t-il le plus ?
SELECT * FROM sleep_spots('Mimosa');                   -- où dort-elle ?
SELECT * FROM kill_stats('clemsix6', now() - INTERVAL 7 DAY);  -- que tue-t-il ?
SELECT * FROM columns('events');                       -- colonnes et commentaires d'une table
```

Volume attendu : 2 000 à 8 000 lignes par heure et par joueur actif, soit 40 à 160 Mo pour 500 heures-joueur. **Rien n'est supprimé** ; un `CHECKPOINT` suit chaque sauvegarde du monde, pour que les sauvegardes copient une base à jour. DuckDB agrège des dizaines de millions de lignes en moins d'une seconde.

### 4.3 Accès pour le Narrateur
L'outil `sql` exécute une requête en **lecture seule** : une seule instruction `SELECT`, vérifiée avant exécution par `json_serialize_sql` (qui refuse tout le reste) ; `nextval` et `setval` refusées ; accès aux fichiers et au réseau coupé au niveau de DuckDB (`enable_external_access = false`) et configuration verrouillée ; délai maximal (`setQueryTimeout`), nombre de lignes plafonné, résultat compact (colonnes séparées par `|`, nombre total de lignes). La description de l'outil **est** le schéma, généré au démarrage depuis la base et ses commentaires, avec des exemples ; la macro `columns()` détaille une table à la demande. Les opérateurs ont `/narrator sql`.

## 5. L'Oracle : lire le monde

Des outils rapides (quelques millisecondes), exécutés sur le thread du serveur avec un délai maximal :

| Outil | Ce qu'il répond |
|---|---|
| `list_players` | Une ligne par joueur en ligne : dimension, biome, position, vie, activité en cours |
| `inspect_player` | Tout sur un joueur : position, dimension, biome, profondeur sous la surface, lumière, **grotte ou pas**, structure où il se trouve, entités proches, joueurs proches et distances, ce qu'il regarde, équipement, inventaire, effets, vie, faim, XP, activité récente |
| `scan_area` | Blocs d'intérêt autour d'un joueur ou d'un point (minerais, coffres, spawners, lave…), avec nombre et positions les plus proches |
| `locate` | Structure ou biome le plus proche (village, forteresse, ancient city…) |
| `player_stats` | Statistiques vanilla du joueur |
| `area_info` | Résumé d'une zone : biome, blocs dominants, entités, lumière |

Heuristiques utiles, calculées par le code : **dans une grotte** = pas de ciel visible et nettement sous la surface ; **près d'un village** = structure dans le rayon ; **en danger** = vie basse, lave proche ou hostiles nombreux.

Calculer la distance entre deux joueurs ne demande pas d'assistant : c'est un outil instantané. Les assistants servent aux analyses en plusieurs étapes (§7.3).

## 6. Les Actions : agir sur le monde

### 6.1 Des outils typés plutôt que des commandes
Le Narrateur agit surtout par des outils typés, validés et journalisés — ils ne dépendent d'aucune syntaxe de commande :

- **Parole et mise en scène** : `say` (tout le monde ou certains joueurs), `show_title`, `actionbar`, `play_sound`, `give_book` (une prophétie, un contrat, une amende…). Les barres de boss (M4) n'existent qu'attachées à un défi ou à un duel : une par participant, tenue par le moteur.
- **Effets sur les joueurs** : `apply_effect` / `remove_effect`, `set_attribute` / `reset_attribute` (temporaire), `teleport` (atterrissage sûr, même dimension en M2), `launch`, `give_item` / `take_item` (objets décrits en JSON : nom, lore, enchantements, incassable… convertis en composants 26.x par le code ; une confiscation est toujours temporaire).
- **Effets sur le monde** : `spawn` (mobs équipés, nommés, à durée de vie), `lightning`, `explosion`, `fireworks`, `particles`, `set_weather`, `set_time`.
- **Mécaniques** (M4, §9) : `start_challenge`, `end_challenge`, `propose_duel`, `create_rule`, `remove_rule`, `punish` (dont deux pièges de blocs, posés dans l'air pour quelques secondes et retirés tout seuls par le `BlockRestorer`), `favor` (qui prend aussi une offrande en échange d'une faveur) ; `teleport` sait aussi un point au hasard dans un rayon. Ces outils n'existent que quand le moteur des mécaniques est disponible.
- **Commandes brutes** : `run_command`, en dernier recours. La commande est d'abord **analysée par le parseur Brigadier du serveur sans être exécutée** : si elle est invalide, le Narrateur reçoit l'erreur, sa position, et l'usage exact de la commande en 26.3. L'outil `command_help` renvoie l'arbre de syntaxe d'une commande. C'est ce qui manquait à la v1.

La liste exacte des 32 outils de M2, avec leurs paramètres, garde-fous et retours, est dans le [design M1/M2](design/M1_M2.md) §9. Les schémas JSON sont générés depuis les classes d'arguments Kotlin et validés côté serveur ; on n'utilise pas `strict: true` (trop limité en nombre d'outils et en contraintes, et non vérifié à travers LLMGW).

### 6.2 L'arsenal des attributs
Les attributs de 26.x offrent des châtiments et récompenses spectaculaires, visibles par tous les clients, sans une ligne de code : `scale` (joueur minuscule ou géant), `gravity` (gravité lunaire), `jump_strength`, `movement_speed`, `block_break_speed`, `safe_fall_distance`, `step_height`, `block_interaction_range`, `entity_interaction_range`, `max_health`, `attack_speed`, `knockback_resistance`, `oxygen_bonus`, `burning_time`… Chaque modification porte un identifiant et une durée : elle est retirée automatiquement, même après un redémarrage.

### 6.3 Garde-fous
Commandes interdites (`stop`, `op`, `deop`, `ban`, `whitelist`, `save-off`… et toutes celles qui modifient des blocs, des objets, la progression ou des entités, y compris derrière `execute … run`) ; seules les commandes relues pour le Narrateur passent, celles des autres mods sont refusées tant qu'un opérateur ne les a pas autorisées. Puissance d'explosion plafonnée, nombre d'entités invoquées plafonné, joueurs protégés configurables (ses créatures ne les blessent pas plus que les joueurs morts depuis peu), aucune destruction de base. Les plafonds dépendent du niveau de chaos ; le composant qui applique chaque règle du §17 est décrit dans le [design M1/M2](design/M1_M2.md) §10, et ce que M4 y ajoute (catégories de châtiments, grâce, séquestre, `BlockRestorer`) dans le [design M4](design/M4.md) §13.

## 7. Le Cerveau

### 7.1 Le Directeur
Le Directeur est l'agent principal : c'est lui qui parle aux joueurs et décide. Sa boucle est **événementielle** :

1. Des événements arrivent dans sa boîte de réception : messages du chat, faits de jeu saillants, résultats d'assistants, échéances, battements réguliers.
2. Il est réveillé (avec un court regroupement de ~1,5 s pour les rafales de chat) et reçoit un message contenant les nouveaux événements et un **rapport de situation**.
3. Il enchaîne réflexion et appels d'outils jusqu'à la fin de son tour.
4. **Pendant son tour, les nouveaux événements lui sont glissés avec chaque résultat d'outil** : dans le message qui rend les `tool_result`, un bloc `<new_events>` placé **après** tous les résultats. S'il prépare un défi et qu'un joueur l'interpelle, il le voit à l'étape suivante, sans attendre la fin du tour.
5. Tour fini : il se rendort jusqu'au prochain événement. Ne rien faire est une décision valable.

Rien de long ne s'exécute dans la boucle du Directeur : pas d'attente, pas d'analyse lourde, pas d'écriture de code. Il délègue.

Exemple de message reçu (généré par le code, donc en anglais ; horodatage relatif à `now` ; le chat est cité et échappé, jamais pris pour une consigne). En M3 et M4 s'y ajouteront faveur, défis, réminiscences et tics :

```
<events now="21:32:41" since="21:32:05">
[-36s] CHAT Bartok (to you 0.92, hostile, provocation): "narrateur t'es nul"
[-31s] DEATH Mimosa: fall 218→64 in bamboo_jungle (overworld -120 64 330), inventory dropped
[-11s] ADVANCEMENT clemsix6: "Hidden in the Depths" (nether 40 15 -12)
</events>
<situation now="21:32:41" day="37" time="night" weather="clear">
Bartok: overworld cave y=-58 dripstone_caves · hp 14/20 food 12 · online 1h12 · last 5m: mined 140 (3 diamond_ore y=-54..-51)
Mimosa: overworld, respawning · protected from hostile actions until 21:37 (died)
clemsix6: nether fortress y=72 · hp 20/20 · online 25m · last 5m: killed 12 blaze
Tasks: T-13 analyst running 12s · Your last actions: -12m say→all · -8m apply_effect slowness→Bartok
</situation>
```

### 7.2 Saillance : qui réveille le Directeur
| Événement | Traitement |
|---|---|
| Message qui s'adresse au Narrateur (détecté par les réflexes, §7.6 ; repli : nom, question, réponse à une conversation en cours) | Réveil immédiat |
| Chat entre joueurs | Accumulé ; réveil après quelques messages ou ~45 s, pour qu'il puisse s'en mêler |
| Mort, PvP, vie critique, première fois (diamant, Nether, End, élytres…), boss, fin de défi | Réveil rapide |
| Entrée dans une structure ou un biome rare, trouvaille notable | Regroupé, réveil en quelques secondes |
| Tout le reste (minage, construction, combats ordinaires…) | Résumé dans le **battement** périodique : « depuis 5 min, Bartok a miné 143 blocs dont 3 diamants à y=-54 » |

Le battement a lieu 3 à 8 minutes (intervalle aléatoire) après la fin du dernier tour, tant que des joueurs sont en ligne. C'est là que le Narrateur décide de lancer un défi ou de faire avancer un récit. Les messages privés entre joueurs sont journalisés mais jamais transmis au Directeur. Le tableau complet de saillance est dans le [design M1/M2](design/M1_M2.md) §12.3.

### 7.3 Les assistants
Le Directeur lance un assistant avec `delegate(rôle, consigne)` : l'appel rend immédiatement un numéro de tâche, l'assistant travaille en parallèle avec son propre contexte et ses propres outils, et son résultat revient comme un événement. `tasks` liste et annule les tâches en cours.

| Rôle | Mission | Outils |
|---|---|---|
| **Analyste** | Questions sur l'historique : « où mine Bartok ? », « profil de Mimosa sur ses trois dernières sessions » | SQL, Oracle, mémoire |
| **Éclaireur** | Explorer le monde : trouver une arène pour un duel, la structure la plus proche de chaque joueur, un lieu pour un piège | Oracle, `locate`, `survey` (terrains plats et dégagés), SQL (positions), `recall` |
| **Forgeron** | Écrire, tester et sauver un module de la Forge, commandé par `forge_module` | `module_list`, `module_read`, `game_ids`, `forge_test`, `forge_save` (§10.2) |
| **Scénariste** | Préparer la suite sans jamais agir : pendant le rêve, le programme (des **idées** `P-…` notées sur la nouveauté, le fun et l'équité, adaptées à la progression réelle, une saison, les récits prévus) ; à la demande (`delegate`), trois idées pour un grand moment ([design M6](design/M6.md) §6) | `inspire`, `propose_idea`, `drop_idea`, `remember` (récits prévus, lore), `recall`, `player_profile`, `sql` |
| **Chroniqueur** | Consolider la mémoire : extraction continue, sieste de fin de chapitre | Journal, mémoire (écriture) |
| **Rêveur** | Le rêve profond pendant les temps morts (voir MEMOIRE.md §5) | Journal, mémoire (écriture), Forge, Éclaireur |

Trois assistants au plus en parallèle par défaut, chacun avec un nombre d'étapes et une durée plafonnés. Le Directeur et les assistants partagent le même moteur de conversation. M2 livre l'**Analyste** ; l'**Éclaireur** arrive en M4 avec les mécaniques qui en ont besoin (arènes des duels, lieux des défis et des plans), le **Forgeron** en M5 (une tâche à la fois, 40 requêtes et 20 minutes au plus, commandé par son propre outil), le **Scénariste** en M6 (une étape du rêve, et un rôle de `delegate`), les autres rôles avec leur jalon. L'`enum` des rôles de `delegate` ne contient que les rôles câblés dans le code ; un
rôle momentanément indisponible (le Scénariste quand la mémoire ne tourne pas) est refusé avec sa raison, car la
définition d'un outil ne change jamais ([relecture du design M6](design/reviews/M6_DESIGN_REVIEW.md), constat 4).

### 7.4 Chapitres et contexte
- La conversation du Directeur est **append-only** : on n'édite jamais un tour passé, on ne change pas le prompt système ni la liste d'outils en cours de chapitre. C'est indispensable avec Opus 5.5 (sa réflexion est liée au préfixe exact de la conversation) et c'est ce qui rend le cache de prompt efficace.
- Les informations changeantes (situation, rappels) sont **ajoutées** au nouveau message, jamais réinjectées dans un ancien.
- Les consignes des administrateurs et du moteur arrivent comme messages *système* en cours de conversation, placés juste après le message `user` de l'envoi : autorité d'opérateur, sans casser le cache. Repli réglable (`director.operatorChannel = user_tag`) : une balise `<operator>` ou `<engine>` à la fin du message `user`, que les joueurs ne peuvent pas imiter (`<` et `>` sont échappés dans leur texte). Une 400 qui refuse le rôle `system` déclenche ce repli automatiquement.
- Un envoi n'entre dans l'historique qu'avec une réponse utilisable : un refus, une réponse tronquée ou une erreur ne laissent ni tour `assistant` vide ni message orphelin.
- Au-delà de ~150k tokens (clôture au prochain moment calme ; 180k : clôture immédiate en fin de tour), ou quand le serveur est vide depuis 10 minutes, le chapitre se clôt par un **résumé factuel** écrit par le Directeur dans sa propre conversation (cache chaud), et le suivant repart d'un briefing reconstruit depuis le Journal et, en M3, la mémoire (MEMOIRE.md §2 et §8). Un test vérifie que le briefing contient l'état du monde, les joueurs et le chat récent. L'outillage, le prompt système et le briefing sont mis en cache : un tour ne paie que ses nouveaux tokens.

### 7.5 Latence
Une réponse de chat coûte un appel au modèle, soit quelques secondes. Pour que l'attente fasse partie du jeu, le joueur qui a interpellé le Narrateur voit « ✎ le Narrateur réfléchit… » dans sa barre d'action. Leviers si c'est trop lent : baisser l'effort du Directeur (§14), utiliser le mode rapide d'Opus 5.5 si la passerelle le permet.

### 7.6 Les réflexes : Claude Haiku
Claude Opus est la **réflexion** du Narrateur ; un petit modèle, **Claude Haiku 5.5**, en est le **réflexe**. Il passe par le même LLMGW, avec la même clé (inclus dans l'abonnement), et répond en 1,5 s environ (p95 ≈ 1,8 s) par un objet JSON imposé par un schéma (sortie structurée), sans réflexion, pour 0,0001 à 0,0005 $ par message. Il trie, Claude décide. Le rôle s'appelle `reflex` (section `reflex` de la configuration, prompt `reflex.md`). Jev (TypeSafe AI), prévu à l'origine, n'a jamais eu de clé : il est abandonné (`docs/research/JEV.md`, archive).

| Où | Ce qu'on demande au réflexe | Ce que ça change |
|---|---|---|
| **Tri du chat** (chaque message public et chaque prière) | Une courte raison d'abord, puis : s'adresse-t-il au Narrateur (probabilité) ? Ton envers lui ? Intention (question, demande, offrande, défi, provocation, conversation entre joueurs, autre) ? Tentative de manipulation ? | Réveil immédiat seulement quand c'est utile ; l'événement arrive annoté au Directeur ; une tentative de manipulation compte comme adressée et est signalée |
| **Filtre des battements** | Le résumé des dernières minutes mérite-t-il une intervention, et quelle accroche ? | Pas d'appel à Opus pour un battement sans matière : moins de coût, moins d'interventions forcées |
| **Signaux de personnalité** | Pour chaque message d'un joueur : politesse, vantardise, entraide, agressivité, humour (scores) | Agrégés dans le temps, ils deviennent des **preuves chiffrées** pour les traits de la fiche joueur |
| **Réaction aux interventions** (à venir) | Après une intervention : le joueur a-t-il ri, joué le jeu, ignoré, été agacé ? | Nourrit l'historique des interactions (valence) et le bilan de ce qui marche |
| **Relecture des répliques** (M6) | Chaque réplique du Directeur, après envoi : registre comique, sombre, cruelle, plate, hors personnage, répétitive, mots béquilles | Si le ton dérive, une note du moteur recadre le Directeur à son réveil suivant ; les registres et les béquilles nourrissent la veille du style, `inspire` et la revue du Rêveur ([design M6](design/M6.md) §3.3, §4.3). C'est le garde-fou qui manquait à la v1 |
| **Réminiscences** (à venir) | Ce souvenir est-il un bon rappel pour cet événement ? | Filtre les candidats de la recherche locale avant de les attacher |

Précautions :
- **Le réflexe ne décide jamais seul d'une conséquence importante.** Ses réponses sont non déterministes et peuvent être influencées par un texte écrit pour le tromper. Les jugements sur les joueurs sont **agrégés dans le temps** par le code, et Claude reste le décideur.
- **Ses probabilités ne sont pas calibrées** (Haiku répond 0,05 ou 0,95, presque jamais entre 0,35 et 0,6) : les seuils sont réglés sur un jeu d'éval français de 52 messages (argot, verlan, ironie, prières, injections, contexte), où il trie juste 50 messages sur 52 contre 41 pour l'heuristique (passation `REFLEX-HAIKU`).
- **Le texte des joueurs est échappé** (`ModelText`) : il ne peut ni fermer une balise ni dicter les étiquettes. **Les messages privés ne partent jamais.**
- **Repli** sur l'heuristique française (listes de mots) si le modèle échoue, dépasse 3 s, ou si LLMGW refuse (429, 402) ; jamais de relance ; disjoncteur après 5 échecs de suite. Sans clé LLMGW ou avec `reflex.enabled = false`, l'heuristique fait tout.
- Le chat part vers Anthropic, comme les conversations du Directeur : aucun nouveau tiers.

## 8. La mémoire

Voir **[MEMOIRE.md](MEMOIRE.md)** : les cinq couches, la fiche joueur (identité, récap, personnalité, stats, historique des interactions), le canon, les souvenirs, la chronique hiérarchique, les rêves, le RAG hybride et les budgets de contexte.

## 9. Les mécaniques de jeu

Le moteur des mécaniques (M4) est un abonné du bus de faits : il apparie les faits sur le thread serveur, tient
l'état vivant dans le monde (pièce jointe globale), agit **par les vrais outils**, sous les garde-fous, et prévient
le Directeur. Un défi, un duel, une loi ou un plan est **une ligne du canon** (`C-…`) que le moteur suit et résout
lui-même : le canon fait loi même quand le Directeur se tait. Le design exécutable est dans
[design/M4.md](design/M4.md).

### 9.1 Défis
Des objets de jeu à part entière : participants, durée, enjeux (récompense, gage), une **barre de boss par
participant**, progression, et **résolution automatique** quand le moteur sait détecter la victoire. L'outil
`start_challenge` écrit le canon et lance le suivi ; à la fin, le moteur paie la récompense aux gagnants et la
pénalité aux perdants (un châtiment du catalogue), par les outils et sous les garde-fous, règle les paris, résout
le canon et prévient le Directeur (`CHALLENGE`).

| Type | Exemple | Détection |
|---|---|---|
| Course à l'objet, collecte (`obtain`) | « Le premier qui obtient un œuf de tortue », « 64 bambous en 5 minutes » | inventaire, chaque seconde |
| Course au lieu (`reach`) | « Le premier au sommet de ce pic », « le premier dans une ancient city » | position, structure, biome, dimension, hauteur |
| Chasse (`kill`) | « 10 araignées avant l'aube » | kills (jamais un joueur) |
| Survie (`survive`) | « Survivre 10 minutes sans armure » | morts, armure, départs |
| Quiz, énigme (`answer`) | Répondre dans le chat | chat public et prières, réponses normalisées |
| Construction jugée (`judged`) | « La plus belle statue en 15 minutes » | le Narrateur nomme les gagnants à l'échéance (`JUDGE`) |
| Duel (`propose_duel`) | PvP consenti, dans une arène de leur dimension ou sur place, positions restaurées | coup fatal de l'adversaire **arrêté à 1 PV**, toute autre mort refusée avec un sauvetage : personne ne meurt d'un duel |
| Pari (`/bet`) | Les joueurs misent la pile qu'ils tiennent sur l'issue d'un défi ou d'un duel | l'issue ; les mises perdues vont aux parieurs gagnants, **rien au Narrateur** |
| Arbitré par un module | Un module de la Forge arbitre un défi jugé (« 1, 2, 3, soleil », le loup) et nomme lui-même les gagnants | `deploy_module` avec `challenge` (M5) |

### 9.2 Châtiments
Un **catalogue** (`punish`) de treize punitions composées, avec une **sévérité de 1 à 5** plafonnée par le niveau de
chaos : joueur minuscule, gravité lunaire, pioche au ralenti, pieds de plomb, faim soudaine, inventaire mélangé
(rien n'est perdu), invités surprises (zombies nommés), pluie de slimes, envol, téléportation au sommet d'un arbre,
foudre, cage de verre, toiles (les pièges de blocs, §9.4). Les outils bruts restent pour le reste. Le gage d'un défi
perdu est une punition légère (sévérité 1 ou 2), jamais pour un joueur arrivé en cours de défi. Toute action hostile, de n'importe
quel outil, d'une règle ou d'un enjeu, porte une **catégorie** (taille, gravité, mouvement, foudre…) : une même
catégorie ne frappe pas deux fois le même joueur avant un délai qui dépend du chaos (30 min au chaos 3). Chaque
châtiment a une raison, gardée dans l'historique du joueur, et une durée ; il passe par les garde-fous comme tout
(protégé, mort récente, déjà puni, quatre par heure au plus). Les « poules en orbite » sont un module de l'arsenal de
départ de la Forge (M5).

### 9.3 Faveurs
La carotte : objets nommés avec une histoire (« La Pioche du Pardon »), effets bénéfiques, titres honorifiques,
indices vers un trésor (l'Éclaireur et un livre), et trois faveurs que le moteur fait respecter (`favor`) : le
**pardon** (tout ce que le Narrateur a infligé est levé), la **grâce** (protection temporaire : ni lui ni ses
créatures ne touchent le joueur) et le **joker** (un objet que le joueur joue lui-même pour lever un châtiment). Leur
**coût** : un rationnement par joueur (objets précieux, une grâce par heure, un joker par jour), et les
**offrandes** : un joueur offre de lui-même la pile qu'il tient (`/offer`, une fois par 10 min au plus) ; le
Narrateur ne la prend qu'en accordant une faveur dans le même appel (`favor` avec l'offrande), et c'est tracé ;
sinon elle revient d'elle-même. Le Narrateur ne réclame jamais d'offrande. Une offrande réveille le Narrateur même
quand son budget de tours de l'heure est épuisé, mais quatre fois par heure au plus, tous joueurs confondus : au-delà,
elle attend le réveil suivant ([relecture de M4](design/reviews/M4_REVIEW.md), constat 6).

### 9.4 Pièges et intentions
Reprise d'une bonne idée de la v1 : le Narrateur peut **armer une intention**, une règle évaluée par le moteur —
« quand Bartok entrera dans le Nether, fais apparaître un comité d'accueil », « si Mimosa descend sous y=0,
préviens-moi pour que je la félicite ». C'est une règle de sorte **`plan`** (§10.1), secrète et souvent à usage
unique, gardée au canon pour qu'il s'en souvienne d'un chapitre à l'autre. Les **pièges de blocs** (cage de verre,
toiles) sont deux punitions du catalogue : posés **seulement dans l'air** autour du joueur, pour quelques secondes,
puis retirés tout seuls par le `BlockRestorer`, seul chemin d'écriture de blocs. Ils ne remplacent jamais un bloc,
naturel ou construit : rien de ce que les joueurs ont construit n'est touché, même s'il date d'avant le mod. **Il n'y a
pas de fosse ni de sol piégé** : la décision G de M4 (remplacer temporairement du terrain naturel) est restée fermée
(décision du 19/10/2026, [design M5](design/M5.md) §19.2, Q4) — aucun bloc ne remplace jamais un bloc existant. La porte
pourra être rouverte plus tard.

### 9.5 Récits
Des arcs narratifs avec un titre, une prémisse, des actes (début, escalade, résolution), une fin prévue et une durée
maximale. Le moteur garantit **deux récits actifs au plus**, une **fin à 7 jours au plus** (un `DUE` la rappelle) et
un **thème** par récit, qui entre en **jachère** 20 heures de jeu une fois le récit clos. Les actes vivent dans le
texte du récit et les plans du Grimoire.

### 9.6 Rythme
Des objectifs indicatifs dans le prompt (un défi toutes les 20–40 min quand les joueurs sont actifs, une loi
rarement, des plans librement) et des **limites dures dans le moteur** : deux défis à la fois et 10 min entre deux
départs, huit règles actives, deux récits, quatre châtiments par heure et par joueur, délais par catégorie,
sévérité maximale, durée des pièges, fréquence des règles. Les plafonds qui dépendent du **niveau de chaos** (0 à 5,
3 par défaut) sont dans le tableau du [design M1/M2](design/M1_M2.md) §10.4 et du [design M4](design/M4.md) §6.2. La
situation du Directeur lui montre ce qui le limite (`Pacing`) avant qu'il n'y bute.

## 10. La Forge : du code à chaud

La Forge a deux étages. Le premier couvre la plupart des envies sans une ligne de code ; le second, tout le reste.

### 10.1 Les Règles : déclaratives, instantanées
Une règle est un objet JSON validé : **déclencheur → conditions → effets**. Le Directeur la crée lui-même
(`create_rule`), sans passer par un assistant, et elle s'active immédiatement ; c'est une ligne du canon, une
**loi** (`law`, un décret public, une semaine au plus) ou un **plan** (`plan`, une intention secrète, §9.4).

- **Déclencheurs** : les faits du bus (bloc cassé ou posé, kill, coup porté, projectile qui touche un joueur, mort,
  chat public, dimension, structure, biome, progrès, artisanat, repas, sommeil, connexion), une condition de
  position évaluée chaque seconde (au front montant), et l'apparition d'une créature (`mob_spawn`, un crochet propre).
- **Conditions** : un objet plat, toutes vraies : joueurs, créature, bloc, objet, casse d'une construction d'autrui,
  dimension, structure, biome, progrès, hauteur, rayon autour d'un point, mots du chat, probabilité. Pas de langage
  d'expressions.
- **Effets** (1 à 4) : **un outil du Narrateur avec ses arguments habituels**, une cible `at` que le moteur remplit
  (`actor` le joueur du déclencheur, `subject` sa créature ou le joueur touché, `position`, `everyone`) et un délai.
  Outils permis : parole, mise en scène, effets, attributs, propulsion, téléportation, invocation, foudre,
  explosion, `punish` (pièges compris) ; et trois réservés aux règles : `mob_effect`, `mob_attribute` (sur la créature du
  déclencheur) et `notify` (réveille le Directeur avec sa note). Jamais de don, de confiscation, de commande, de
  météo, ni de règle qui crée une règle.

Les cinq exemples du cahier des charges tiennent dans des règles :

| Envie | Déclencheur | Conditions | Effets |
|---|---|---|---|
| Squelettes à flèches explosives | un projectile touche un joueur | tireur = squelette | `explosion` à l'impact (puissance 2, sans casser de blocs) |
| Silverfish téléporteurs | un joueur blesse une entité | victime = silverfish | `teleport` de l'attaquant au hasard (rayon 300, atterrissage sûr) |
| Vaches qui s'envolent et explosent | un joueur blesse une entité | victime = vache | `mob_effect` lévitation 3 s, puis `explosion` inoffensive et `fireworks` là où est la vache |
| Creepers sous caféine | une créature apparaît ou se charge | type = creeper | `mob_attribute` vitesse ×3, retiré avec la règle |
| Diamants explosifs | un bloc est cassé | bloc = `#diamond_ores` | `explosion` (puissance 3) |

Un seul moteur : les effets passent par les vrais outils, validés côté serveur avec leurs propres décodeurs et
vérifications, **à la création** (erreurs précises : identifiant inconnu, puissance au-delà du chaos, cible mal
placée…) et à l'exécution. Chaque déclenchement est un acte à part, soumis aux garde-fous (pas d'acharnement, délais
par catégorie) ; une explosion refusée se rejoue inoffensive. Une créature qu'une règle touche devient « du
Narrateur » : ses explosions ne cassent aucun bloc. Une règle a une durée, des cibles, une fréquence bornée, se
désactive après cinq échecs et se retire proprement ([design M4](design/M4.md) §8).

### 10.2 Les Modules : du Java compilé à chaud
Pour les mécaniques vraiment nouvelles (mini-jeux, machines à états, créatures pilotées à chaque tick, défis arbitrés),
le Forgeron écrit un module Java. Le design exécutable est dans [design/M5.md](design/M5.md) ; l'étude de faisabilité
dans [research/FORGE.md](research/FORGE.md).

**Pourquoi Java plutôt que Kotlin ?** Nous avons évalué les deux ; des mods Kotlin compilés à chaud existent en 26.3 (Kursive, Katton). Mais Kotlin impose d'embarquer ~61 Mo de compilateur, lent au premier démarrage (plus de 5 s) et à garder synchronisé avec Fabric Language Kotlin. ECJ (3,2 Mo, embarqué) compile un module en ~7 ms une fois chaud dans le serveur (mesuré), sur un JDK comme sur un JRE. Surtout, c'est en Java que Claude écrit le mieux. Le mod lui-même reste en Kotlin ; seuls le SDK vu par les modules et ce qu'appelle leur bytecode réécrit sont en Java.

Principes :

1. **Le SDK, et rien d'autre.** Un module est compilé contre le SDK du Narrateur, une API Java bornée (faits, minuteurs, lectures du monde, actes, créatures pilotées, blocs temporaires, petit état, arbitrage d'un défi jugé), et contre une liste blanche du JDK. Il ne voit **aucune classe du jeu**, de Fabric ni du mod : avec elles, aucun interdit du §17 ne pourrait être garanti (des milliers de méthodes du jeu écrivent le monde ou un joueur, une liste noire ne serait jamais complète). Tout ce qu'il fait au monde passe par les vrais outils et les garde-fous ; chaque invocation qui agit est un acte du moteur, comme une règle qui se déclenche. Les règles du §10.1 et les modules partagent ce moteur.
2. **Cinq barrières.** Un compilateur aveugle (ECJ ne voit que `java.base` et le SDK) ; une garde par liste blanche sur le bytecode (types, membres, appels dynamiques, `catch`, structure) ; une réécriture du bytecode qui mesure le temps (20 ms par appel), l'allocation et la profondeur, et borne tableaux, collections et textes ; un chargeur de classes par module qui refuse au lien tout nom hors liste ; un hôte qui désactive un module fautif, mesure la mémoire qu'il retient et surveille son déchargement. Un coupe-circuit (`/narrator forge off`) arrête tout. Une batterie de modules malveillants vérifie chaque barrière.
3. **Le code d'un module ne tourne jamais sur le thread serveur.** Un seul appel du JDK peut durer sans fin sans exécuter une ligne du module (hacher une liste qui se contient dix mille fois : des minutes), et rien ne l'interrompt. Chaque appel d'un module tourne donc sur un **fil des modules** ; le thread serveur le lui confie et l'attend **au plus son budget** (20 ms, plus 5 ms pour que le compteur se dénoue), en servant pendant ce temps les appels du SDK du module : le monde n'est lu et écrit que par le thread serveur, comme si le module tournait dessus. Au-delà, le thread serveur **abandonne** l'appel et reprend son tick ; le module est désactivé, et son fil, qui finit son calcul à la priorité normale, ne peut plus rien demander au SDK. Au-delà de deux fils abandonnés encore vivants, la Forge s'arrête jusqu'au redémarrage. Reste un coût mesuré : tant qu'un fil perdu calcule, chaque point d'arrêt global de la JVM (une collecte de la mémoire) peut attendre jusqu'à la fin de son calcul en cours, soit 65 à 90 ms par collecte dans l'attaque mesurée (design/M5.md §3.8).
4. **Nos propres crochets.** Les événements de Fabric API ne se désinscrivent pas, et il en manque (projectile qui touche, explosion, tick d'entité). Le mod a donc son propre bus d'événements, alimenté par Fabric API et des mixins ; le moteur des mécaniques en livre les faits aux modules, sur le thread serveur.
5. **Le Forgeron ne devine pas.** Il a la référence du SDK dans son prompt, les identifiants des registres du jeu (`game_ids`), l'arsenal et ses sources (`module_list`, `module_read`), des diagnostics qui pointent vers le SDK, et un banc d'essai (`forge_test`) qui fait tourner le module dans un monde simulé, avec ses scénarios, avant toute sauvegarde (`forge_save`).
6. **Cycle de vie propre.** Écrit → compilé → gardé → réécrit → testé → sauvé (une version immuable de l'arsenal, dans le dossier du monde) → déployé (une ligne du canon, loi ou plan, une durée) → suivi → retiré (abonnements, minuteurs, créatures rendues, chargeur abandonné). Un déploiement survit au redémarrage : recompilé, redémarré avec son petit état.
7. **Tout expire** : un déploiement dure de 5 minutes à 7 jours ; un module qui plante ou ralentit le serveur est désactivé et le Directeur le sait.
8. **Un arsenal qui grandit.** Le mod livre sept modules écrits à la main (poules en orbite, parade, loup, Jacques a dit, chaud ou froid, le sol est de lave, tempête de gravité), qui servent aussi d'exemples au Forgeron ; chaque module forgé s'y ajoute, versionné.

Le Directeur n'écrit pas de code Java : il crée des règles lui-même, commande les modules au Forgeron (`forge_module` : « un 1, 2, 3, soleil qui arbitre le défi »), continue à jouer, puis les déploie (`deploy_module`) au moment le plus dramatique.

## 11. Le ton et le renouvellement

Mécanismes concrets contre la répétition et la dérive :

- **Prompt de voix** avec exemples et contre-exemples (`prompts/director.md`), et une liste de **registres comiques** à faire tourner (faux documentaire, commentaire sportif, bureaucratie divine, tribunal, bulletin météo, prophétie ridicule…).
- **Historique des interventions** dans chaque rapport de situation (`Your last actions`), et rangé par levier et par registre pour `inspire`, le Scénariste et le tableau de bord (M6).
- **Chaque réplique relue** après envoi par le réflexe (M6) : son registre, ses défauts (sombre, cruelle, plate, hors personnage, répétitive) et ses mots béquilles.
- **Tics de langage** calculés par le code sur les répliques réellement envoyées, noms et nombres normalisés (« `<n>` secondes », « Bonsoir, mortels ») : dans le briefing, dans une ligne `Style:` de la situation pendant le chapitre (amorces, expressions et béquilles au repos, registres trop servis), et pour le Rêveur. **Une amorce trop servie est refusée** par `say` : une liste noire glissante, appliquée par le code (M6).
- **Notes de ton** : une dérive (sombre, cruelle, hors personnage, plate ou répétitive) devient une note du moteur au réveil suivant (M6).
- **`inspire`** (M6) : un outil instantané, sans modèle, qui rend trois idées : celles du programme du Scénariste qui conviennent aux joueurs en ligne, puis des graines (levier × souvenir, lieu, gag ou trait de la mémoire × rebondissement d'un deck × registre le moins servi), en excluant ce qui a servi récemment. Le battement porte au plus une idée du programme.
- **Le Scénariste** (M6) prépare le programme pendant les rêves et, à la demande, trois idées notées pour un grand moment ; le Directeur choisit, adapte ou ignore.
- **Arcs à fin programmée** et thèmes en jachère.
- **Auto-évaluation pendant les rêves** : ce qui a fait réagir les joueurs est renforcé, ce qui est tombé à plat est abandonné ; la revue a les réactions par registre (M6).
- **Évaluation du ton hors ligne** (M6) : un juge Opus note les répliques sur une grille (drôle, juste, jamais glauque, jamais cruel, voix, répétition, lisibilité) et compare deux versions du prompt en rejouant des situations figées.
- **Cooldowns et plafonds** dans le moteur : même si le modèle s'emballe, le jeu reste jouable.

## 12. Traces et débogage

Tout doit pouvoir s'expliquer après coup : pourquoi le Narrateur a dit ça, pourquoi il n'a rien fait, pourquoi ce module a planté. Trois niveaux de traces.

### 12.1 Les conversations avec Claude
- **Chaque appel** au modèle est enregistré (table `llm_calls`) : conversation, tour, rôle, but (tour, résumé de chapitre, tâche), modèle, effort, identifiants de requête et de message, durée et temps jusqu'au premier token, tokens (entrée, cache lu, cache écrit, sortie), raison d'arrêt et catégorie de refus, erreurs et tentatives. Les appels des réflexes y figurent aussi (rôle `reflex`, sans conversation). L'identifiant de requête vient de l'en-tête `request-id`, sinon de `X-Cpa-Trace-Id` posé par CLIProxyAPI : LLMGW ne relaie pas `request-id` (vérifié par `liveTest`, `docs/research/LLMGW.md` §3). Le temps jusqu'au premier token représente typiquement 70 à 100 % de la durée totale d'un appel (vérifié par `liveTest`) : le flux SSE arrive presque en bloc à la fin plutôt qu'étalé.
- **Le raisonnement** : les requêtes demandent `thinking.display: "summarized"`, qui renvoie un résumé lisible de la réflexion du modèle (Opus 5.5 ne renvoie jamais son raisonnement brut). Ces résumés sont stockés dans `llm_calls.thinking` et dans les transcriptions.
- **Chaque conversation** (chapitre du Directeur, tâche d'un assistant, rêve) a sa transcription complète, append-only, dans `world/narrator2/logs/conversations/<date>/` : un `.jsonl` exact (pour rejouer ou analyser) et un `.md` lisible où l'on suit, dans l'ordre, les événements reçus, le résumé de réflexion, chaque appel d'outil avec ses arguments et son résultat, et ce qui a été dit aux joueurs. Les envois non validés (refus, réponse tronquée, erreur) y apparaissent comme tels. Les tables `conversations`, `turns` et `tasks` relient chapitres, tours, tâches et transcriptions.

### 12.2 Les décisions du moteur
Journalisées en base et dans le log du serveur, avec leur raison :
- réveils du Directeur (quel événement, quel score de saillance) et non-réveils (regroupé, ignoré, résumé au prochain battement) ;
- actions bloquées (cooldown, plafond de sévérité, commande interdite, zone protégée) ;
- cycle de vie des règles et modules (compilation et erreurs, déploiement, exceptions, dépassement de temps, désactivation automatique) ;
- écritures en mémoire (qui a écrit quoi, depuis quelle conversation) et rapports de rêve ;
- santé du Journal (événements par seconde, taille des lots, latence d'écriture) ;
- chapitres (ouverture, clôture, résumé), refus, blocages de budget et reprises, bascule du canal opérateur, commandes des opérateurs.

### 12.3 Le log du serveur
Des lignes courtes, en anglais, préfixées par sous-système (`[Director]`, `[Tool]`, `[Guard]`, `[Journal]`, `[Observer]`, `[Sql]`, `[Reflex]`, `[LLM]`, `[Worker]`, puis `[Forge]`, `[Memory]`…), avec un niveau réglable par sous-système dans la configuration.

### 12.4 Consulter
```
/narrator trace [n]            # les n derniers tours du Directeur, résumés
/narrator trace turn <id>      # le détail d'un tour : événements, réflexion, outils, répliques
/narrator trace task <id>      # le détail d'une tâche d'assistant
/narrator why                  # pourquoi la dernière action (déclencheur + résumé de réflexion + actions)
/narrator sql <requête>        # requête en lecture seule sur le Journal et les traces
```
Ces commandes attendent que le Journal ait écrit ce qui précède (une seconde au plus) puis lisent la base. Tout est aussi requêtable en SQL (`llm_calls`, `tool_calls`, `engine_decisions`, `turns`…).

**Le tableau de bord web** (M6, [design M6](design/M6.md) §8) : un serveur HTTP du mod, lié à `127.0.0.1` (port 8770 ; à distance, par un tunnel SSH), en lecture seule, protégé par un jeton que donne `/narrator dashboard`. Rendu côté serveur en HTML et CSS, **sans JavaScript** : une politique de sécurité (`Content-Security-Policy: default-src 'none'`) interdit tout script au navigateur. Il montre les tours, les conversations (transcriptions rendues), les tâches, les décisions, les coûts en dollars, la mémoire (canon, idées du Scénariste, documents), les fiches, les mécaniques, l'arsenal de la Forge, le style (diversité, registres, drapeaux de ton) et une page SQL en accès « modèle » : les messages privés y sont inaccessibles par construction.

## 13. Sécurité et administration

- **Le chat est une entrée non fiable.** Les messages des joueurs sont balisés comme paroles de personnages ; le prompt traite les tentatives de manipulation comme un manque de respect (en jeu). Les vraies consignes n'arrivent que par le canal opérateur.
- **Garde-fous du moteur** (§6.3) indépendants du modèle.
- **Canaux de parole** (repris de la v1) : mention publique dans le chat ; `/pray <message>` pour parler au Narrateur en privé (le nom d'une commande est un littéral du code, donc anglais ; « prier le Narrateur-dieu » est dans le ton) ; `/narrator whisper <consigne>`, réservé aux opérateurs, qui arrive au Directeur comme consigne système.
- **Commandes des joueurs pour les mécaniques** (M4) : `/duel accept|decline <id>` (un duel n'a lieu qu'avec les deux acceptations), `/bet <id> <joueur|nobody>` (parier la pile tenue en main sur l'issue d'un défi), `/offer` (offrir au Narrateur la pile tenue en main).
- **Commandes d'administration** :

```
/narrator status | pause | resume | reload
/narrator chaos <0-5>
/narrator say <texte>            # faire parler le Narrateur
/narrator whisper <consigne>     # consigne opérateur au Directeur
/narrator protect <joueur> [on|off]
/narrator chapter close          # clore le chapitre au prochain moment calme
/narrator sql <requête>          # lecture seule
/narrator rule list | remove <id>
/narrator forge [status] | list [page] | show <nom> [version] | approve|reject <nom> [version]
/narrator forge off [raison] | on   # coupe-circuit : tout s'arrête, même après un redémarrage
/narrator module [list] [page] | stop <id> [note]
/narrator challenge list | cancel <id>
/narrator traps [restore]        # pièges en cours ; tout remettre maintenant
/narrator sheet <joueur>
/narrator memory …               # voir MEMOIRE.md §12
/narrator trace …                # voir §12.4
/narrator usage                  # consommation de tokens (et en dollars, M6) par rôle
/narrator dashboard [reset]      # le lien du tableau de bord web ; reset : un nouveau jeton (M6)
/narrator style                  # tics, béquilles, registres, diversité, drapeaux de ton (M6)
/narrator memory ideas [all]     # les idées du Scénariste (M6)
/narrator restart                # reconstruit le Narrateur sans arrêter Minecraft, serveur vide (M6)
```

## 14. Claude via LLMGW

- Le mod utilise le **SDK Java officiel d'Anthropic** avec l'URL de LLMGW et une **clé projet LLMGW**. C'est l'API Messages standard : LLMGW gère les comptes, les budgets et le suivi de consommation.
- **On accède au serveur LLMGW existant du propriétaire par un tunnel SSH, qui passe par Cloudflare Access (`cloudflared`) depuis le 18/10/2026 ; on n'installe jamais LLMGW en local** (voir `CLAUDE.md` et `docs/research/LLMGW.md`).
- **Modèles** : Opus 5.5 pour le Directeur, configurable **par rôle** (modèle et effort).
- **Effort** : le Directeur démarre à `medium`. Sur Opus 5.5, `medium` dépasse le `high` d'Opus 5 et répond plus vite ; `high` reste disponible dans la configuration. Le Forgeron et le Rêveur travaillent en `high` : ils sont asynchrones, la latence n'y coûte rien.
- **Contraintes d'Opus 5.5 prises en compte** : réflexion toujours active (l'effort est le seul réglage), pas de `tool_choice` forcé, historique append-only, gestion du `stop_reason: refusal`.
- **Affinité de session** : chaque conversation envoie un en-tête `X-Session-ID` stable **par rôle** (`narrator2-director`, `narrator2-analyst`…). CLIProxyAPI s'en sert pour choisir le compte amont, là où vit le cache de prompt : toutes les conversations d'un rôle y relisent leur préfixe commun (outils, prompt), d'un chapitre ou d'une tâche à l'autre. L'identifiant de conversation, pour les traces, voyage dans `X-Narrator-Conversation`.
- **Cache de prompt** : outils, prompt système et briefing sont stables pendant un chapitre. `gateway.cacheTtl` (`off` par défaut, `1h` ou `5m` sinon) contrôle le marqueur `cache_control` que le mod pose lui-même. **Vérifié par `liveTest` (25/09/2026)** : le serveur LLMGW du propriétaire pose déjà ses propres marqueurs `ttl=1h` (prompt système, derniers tours), et le cache fonctionne sans marqueur de notre part (5 344 tokens écrits puis relus) ; un marqueur de plus des nôtres est refusé (400 « A maximum of 4 blocks with cache_control ») à partir du deuxième tour, et un `5m` l'est toujours (conflit de `ttl`) — d'où le défaut `off`. `1h`/`5m` restent utiles derrière une passerelle qui ne poserait pas son propre marqueur. Les noms d'outils restent courts (LLMGW les préfixe en `mcp__llmgw__…` et laisse tels quels ceux de plus de 52 caractères).
- **Messages `system` en cours de conversation** : **acceptés et suivis, vérifié par `liveTest` (25/09/2026)** — c'est ce qui permet à `director.operatorChannel` de rester en mode `system_message` par défaut ; le repli `user_tag` reste disponible en configuration et par bascule automatique sur une 400 (voir [design M1/M2](design/M1_M2.md) §12.7, acquis vérifié à son §20 point 1).
- **Quotas** : les budgets de LLMGW (appels, tokens, coût par heure ou par jour) protègent le compte. Le mod suit aussi sa consommation par rôle et espace les battements quand le budget se tend.

### Credentials OAuth ou clé API
Avec des credentials d'abonnement Claude (OAuth), CLIProxyAPI « déguise » chaque requête en Claude Code : il remplace le prompt système par l'identité de Claude Code (le nôtre est déplacé dans des messages système), reconstruit les en-têtes bêta et réécrit les marqueurs de cache. Le mod fonctionne quand même, mais de façon moins prévisible. Surtout, les conditions d'Anthropic réservent l'OAuth des abonnements Free/Pro/Max à Claude Code et aux applications d'Anthropic : alimenter un serveur multijoueur avec n'entre pas dans ce cadre. **Recommandation : une clé API Anthropic déclarée dans LLMGW pour le projet du Narrateur.** Rien ne change côté mod.

**Constaté par `liveTest` (25/09/2026)** : la passerelle réécrit nos requêtes avant de les transmettre — elle insère un bloc avant notre texte et pose elle-même les marqueurs de cache décrits ci-dessus. D'après la lecture du code de LLMGW (`docs/research/LLMGW.md` §2), ce comportement est le signe d'un cloaking CLIProxyAPI actif, donc **vraisemblablement d'un compte d'abonnement (OAuth) en amont plutôt que d'une clé API**. Ce n'est qu'un faisceau d'indices côté client, pas une confirmation du credential réellement configuré côté serveur : à vérifier avec le propriétaire. Cela renforce l'enjeu de la recommandation ci-dessus (conditions d'utilisation d'Anthropic).

## 15. Configuration

`config/narrator2/narrator2.json` : écrit une seule fois, de façon atomique, avec toutes les valeurs par défaut au premier lancement (jamais de clé LLMGW dans le fichier — elle vient uniquement de la variable d'environnement nommée par `gateway.apiKeyEnv`, `NARRATOR_LLMGW_KEY` par défaut). Les chargements suivants relisent le fichier de l'opérateur et le fusionnent avec les défauts **clé par clé** : une section ou un réglage absent du fichier prend sa valeur par défaut sans que le fichier soit réécrit. `config/narrator2/prompts/` surcharge les prompts du jar et est relu par `/narrator reload`.

**M0 ne livre que `gateway`, `roles` et `logging` ci-dessous** (contenu exact du fichier créé par `ConfigStore`, `NarratorConfig.kt`) : `reflex` (ex-`jev`), `language`, `chaos`, `journal`, `observer`, `director`, `workers` et `safety` **arrivent avec M1 et M2** — une section non encore utilisée est retirée du fichier plutôt que d'y traîner sans effet, et revient avec son jalon. Valeurs et règles de validation de ces sections futures : [design M1/M2](design/M1_M2.md) §15.

```json
{
  "gateway": {
    "baseUrl": "http://127.0.0.1:8088",
    "apiKeyEnv": "NARRATOR_LLMGW_KEY",
    "cacheTtl": "off",
    "maxRetries": 3,
    "retryInitialDelayMs": 1000,
    "retryMaxDelayMs": 30000
  },
  "roles": {
    "director":    { "model": "claude-opus-5-5", "effort": "medium", "maxTokens": 32000, "readTimeoutSeconds": 90, "totalTimeoutSeconds": 300 },
    "analyst":     { "model": "claude-opus-5-5", "effort": "low",    "maxTokens": 32000, "readTimeoutSeconds": 90, "totalTimeoutSeconds": 600 },
    "scout":       { "model": "claude-opus-5-5", "effort": "low",    "maxTokens": 32000, "readTimeoutSeconds": 90, "totalTimeoutSeconds": 600 },
    "forger":      { "model": "claude-opus-5-5", "effort": "high",   "maxTokens": 64000, "readTimeoutSeconds": 90, "totalTimeoutSeconds": 1200 },
    "storyteller": { "model": "claude-opus-5-5", "effort": "medium", "maxTokens": 32000, "readTimeoutSeconds": 90, "totalTimeoutSeconds": 600 },
    "chronicler":  { "model": "claude-opus-5-5", "effort": "low",    "maxTokens": 32000, "readTimeoutSeconds": 90, "totalTimeoutSeconds": 600 },
    "dreamer":     { "model": "claude-opus-5-5", "effort": "high",   "maxTokens": 64000, "readTimeoutSeconds": 90, "totalTimeoutSeconds": 1200 }
  },
  "logging": { "transcripts": true, "levels": {} }
}
```

`gateway.cacheTtl` (`off` | `1h` | `5m`, `off` par défaut) et les timeouts par rôle (`readTimeoutSeconds` : silence toléré de la passerelle ; `totalTimeoutSeconds` : tout l'appel, retries compris) sont vérifiés contre le vrai serveur LLMGW (`docs/research/LLMGW.md`, § *Vérifié par liveTest*). La politique de retry (`gateway.maxRetries`, `retryInitialDelayMs`, `retryMaxDelayMs`) est propre au mod : les retries du SDK Anthropic sont désactivés pour ne pas s'empiler sur ceux de CLIProxyAPI en amont (§14).

Une fois M1 et M2 livrés, les plafonds (puissance d'explosion, invocations, durées, quota de morts…) ne seront pas des réglages séparés : ils découleront du niveau de chaos (tableau dans le design M1/M2 §10.4). La liste de base des commandes interdites sera codée en dur ; `safety.extraForbiddenCommands` ne pourra qu'en ajouter. Les commandes permises sont une liste blanche, elle aussi codée en dur ; `safety.extraAllowedCommands` y ajoute les commandes relues des autres mods, jamais une commande de la liste interdite.

## 16. Feuille de route

| Étape | Contenu | Résultat visible |
|---|---|---|
| **M0 — Fondations** — **terminé (25/09/2026)** | Projet Gradle Fabric/Kotlin 26.3, serveur de dev, configuration, client LLMGW, traces des appels, `/narrator ping` | Le mod démarre et parle à Claude — vérifié contre le vrai serveur LLMGW (`gradlew liveTest`) et de bout en bout (`/narrator ping` sur le serveur de dev, réponse en 4,5 s) |
| **M1 — Journal** | Observateur (hooks, statistiques, mixins, positions), DuckDB, outil `sql` | Tout est enregistré et interrogeable. **Terminé le 25/09/2026** (voir `design/handoff/T07.md`) |
| **M2 — Directeur v1** | Boucle événementielle, saillance, parole, Oracle, actions typées, validation des commandes, prompt de voix, transcriptions | Le Narrateur réagit au chat et au jeu. **Terminé le 25/09/2026** : recette en jeu contre le vrai LLMGW (voir `design/handoff/T22.md`), puis correctifs de la relecture fusionnés et vérifiés sur `main` (voir `design/reviews/M2_REVIEW.md`) |
| **M3 — Mémoire** | Fiches joueurs, canon, souvenirs, interactions, chapitres et briefing, Chroniqueur, rêves | Il se souvient, sur des centaines d'heures. **Terminé le 18/10/2026** : correctifs de la relecture fusionnés (voir `design/reviews/M3_REVIEW.md`), puis évals E1 à E6 et recette E7 contre le vrai LLMGW (voir `design/handoff/M3-19.md`) |
| **M4 — Mécaniques** | Moteur des mécaniques (abonné du bus, état dans le monde, actes par les vrais outils) ; défis suivis (objectifs détectés, barres de boss, enjeux payés sous les garde-fous), duels consentis, paris ; catalogue de châtiments, catégories et sévérité ; faveurs (pardon, grâce, joker) et offrandes ; pièges de blocs temporaires (`BlockRestorer`) et index des blocs posés fiable ; Règles (§10.1), lois et plans ; récits bornés ; rythme ; Éclaireur ; rappels persistants. Design : [design/M4.md](design/M4.md) | Des parties rythmées, des règles du monde folles. **Recette réelle le 19/10/2026** : évals L1 à L6 et soirée passées contre le vrai LLMGW (voir `design/handoff/M4-24.md`). **Terminé le 19/10/2026** : relecture du code (42 constats, lots `M4-FIX-A` à `I`) et correctifs de la recette (`design/handoff/M4-POST.md` : les créatures d’un châtiment ne tuent jamais) |
| **M5 — Forge** | SDK Java des modules, compilation à chaud (ECJ), bac à sable (liste blanche, réécriture, chargeur filtrant, hôte), banc d'essai, outils d'introspection (référence du SDK, identifiants du jeu, arsenal), Forgeron, déploiements suivis par le moteur, arsenal de départ. Design : [design/M5.md](design/M5.md) | Il invente de nouvelles mécaniques. **Recette réelle le 21/10/2026** : évals F1 à F4 et soirée passées contre le vrai LLMGW. Le vrai Forgeron a forgé neuf modules (0,77 $ en médiane), refusé les cinq commandes hostiles et réparé une version cassée ; le Directeur a commandé, déployé et fait réparer ses modules (voir `design/handoff/M5-22.md`). **Terminé le 21/10/2026** : relecture du code (`design/reviews/M5_REVIEW.md`, aucun bloquant) et ses lots `M5-FIX-1` à `3` fusionnés, plus `M5-LOAD`, `FLAKY-4` et `CLAUDE-FALLBACK` |
| **M6 — Finitions** | Relecture de chaque réplique (registre, défauts, béquilles) et veille du style (tics normalisés, amorces au repos refusées, ligne `Style:`, notes de ton) ; `inspire` ; le Scénariste (programme du rêve, idées `P-…`, saisons, récits prévus, à la demande) ; juge Opus et rejeu hors ligne ; tableau de bord web sans JavaScript ; `/narrator restart` (serveur vide) ; compactage de `director.md`. Design : [design/M6.md](design/M6.md) (17 tâches, relu le 20/10/2026), après la fin de M5 ; les reliquats de M4 sont faits par le lot M4-LEFTOVERS | Il se renouvelle, et on voit tout |

Le design détaillé et exécutable de M1 et M2 (contrats, schéma SQL, formats, outils, garde-fous, tâches pour les agents) est dans [docs/design/M1_M2.md](design/M1_M2.md). Il tranche en outre, le 25/09/2026 :
- **Un seul bus de faits** pour le Journal, la saillance, puis le moteur de mécaniques et les modules de la Forge (abonnés synchrones ou asynchrones à file bornée, désabonnement par propriétaire). L'interception d'actions du jeu (annuler des dégâts…) relève des crochets de la Forge, pas du bus.
- **L'état qui doit rester cohérent avec le monde vit dans le monde** (pièces jointes Fabric), DuckDB ne garde que l'historique (§2).
- **Outils** : 32 en M2, schémas générés depuis les arguments Kotlin, validés côté serveur, sans `strict: true` ; les outils sont figés par conversation et le modèle ne voit que ceux qui sont réellement branchés ; « ne rien faire » n'est pas un outil, `remind_me` programme un réveil.
- **Garde-fous** : chaque règle du §17 a un composant qui l'applique ; les plafonds découlent du niveau de chaos ; le quota de morts se mesure en temps joué (stat vanilla `PLAY_TIME`) ; tant qu'aucun outil n'écrit de blocs (M2), la règle sur les constructions est garantie par l'absence d'API, la liste des commandes interdites et le blocage des explosions du Narrateur.
- **Réflexes** : une requête par message de chat (tri et signaux de personnalité, stockés dès M2 pour M3), repli heuristique alimenté par un fichier de mots français. Prévus sur Jev, ils passent sur Claude Haiku 5.5 le 19/10/2026 (§7.6, §17.5).
- **Canal privé** : `/pray` ; **Analyste seul** en M2, Éclaireur en M4.

Le design exécutable de M4 est dans [docs/design/M4.md](design/M4.md). Il tranche, le 18/10/2026 :
- **Le moteur des mécaniques est un abonné du bus** qui n'apparie que dans l'appel ; son état vivant est dans une pièce jointe globale du monde, son historique dans le canon, les interactions et les décisions `mechanic`.
- **Un défi, un duel, une loi ou un plan est une ligne du canon** (`C-…`, colonne `mechanic`), que le moteur suit et résout lui-même ; une nouvelle sorte de canon, `plan` (migration V3).
- **Le moteur agit par les vrais outils** (`EngineActions`), chaque acte avec son propre `Caller` : mêmes vérifications, plafonds et garde-fous que le Directeur. Les effets d'une Règle sont des appels d'outils avec une cible `at`.
- **Châtiments** : catégories et délai par catégorie et par joueur pour toute action hostile ; catalogue `punish` à sévérité plafonnée par le chaos.
- **Objets** : mises et offrandes en séquestre dans l'état du joueur, avec une échéance de secours ; paris sans maison ; une offrande n'est prise que contre une faveur accordée dans le même appel, tracée.
- **Blocs** : un seul chemin d'écriture, le `BlockRestorer`, temporaire et **seulement dans l'air** : il ne remplace jamais un bloc, donc ne touche jamais une construction, même d'avant le mod ; l'index des blocs posés vérifie le bloc encore en place (condition `grief` des règles).
- **Duels** : personne n'y meurt ; toute mort d'un duelliste est refusée, avec un sauvetage quand l'adversaire n'en est pas la cause.
- **L'Éclaireur** entre avec `survey` ; les rappels deviennent persistants ; `/narrator restart` est rangé en M6, et les nouvelles tentatives d'ouverture du Journal au démarrage sont faites hors de M4.
- **Relecture du 18/10/2026** ([relecture](design/reviews/M4_DESIGN_REVIEW.md)) : décisions A à F du superviseur intégrées, pièges limités à l'air, offrandes compensées par le code, duels sans aucune mort, découpage corrigé (44 outils pour le Directeur, tous dépendants du moteur).

Le design exécutable de M5 est dans [docs/design/M5.md](design/M5.md), relu et corrigé le 19/10/2026
([relecture](design/reviews/M5_DESIGN_REVIEW.md), décisions Q1 à Q8 du superviseur tranchées). Il tranche :
- **Un module ne voit que le SDK du Narrateur** et une liste blanche du JDK, jamais les classes du jeu ; tout ce qu'il
  fait au monde passe par les vrais outils et les garde-fous, chaque invocation étant un acte du moteur.
- **Cinq barrières** : compilateur aveugle, garde par liste blanche, réécriture (temps, allocation, profondeur, tailles),
  chargeur filtrant, hôte (fautes, mémoire retenue, déchargement) ; coupe-circuit ; batterie d'attaque dans le build.
- **ECJ seul**, embarqué ; mesuré dans le vrai serveur Fabric (7 ms à chaud).
- **Un déploiement est une ligne du canon** (loi ou plan, `mechanic = module`) et une entrée de l'état du monde ; il
  survit au redémarrage. L'arsenal est fait de sources versionnées dans le dossier du monde.
- **Pas de module sans tests** au banc d'essai (un monde simulé) ; revue par un opérateur en option (`approval`,
  `auto` par défaut).
- **Le Forgeron** a cinq outils et le SDK dans son prompt ; **le Directeur** commande (`forge_module`), déploie
  (`deploy_module`) et arrête (`stop_module`) : 47 outils.
- **Pas de piège de sol** (fosse, patinoire) : la décision C reste absolue, aucun bloc ne remplace un bloc existant.

Le design exécutable de M6 est dans [docs/design/M6.md](design/M6.md), rédigé et relu le 20/10/2026
([relecture](design/reviews/M6_DESIGN_REVIEW.md)) ; le superviseur a accepté D1 à D6, D8 et D10, D9 provisoirement
(les noms des cinq registres attendent le propriétaire), et, pour D7, le repli « serveur vide » que proposait la
relecture ; le propriétaire pourra rouvrir plus tard le cas joueurs en ligne (§18.2 du design). Il tranche :
- **Le renouvellement par des mécanismes** : Haiku relit chaque réplique **après** son envoi (registre, défauts, mots
  béquilles ; 0,005 à 0,025 $ de l'heure) ; le code normalise noms et nombres dans les tics, tient une liste noire
  glissante d'amorces que `say` refuse, une ligne `Style:` dans la situation et des notes de ton au réveil suivant.
  Aucune relecture avant envoi (elle ralentirait chaque réponse).
- **Le juge Opus hors ligne seulement**, pour les évals et le rejeu A/B d'un changement de prompt ; en jeu, la relecture
  et la revue du Rêveur (registres × réactions) suffisent.
- **`inspire` est du code** (programme du Scénariste, mémoire, arsenal, deck de rebondissements, exclusions de ce qui a
  servi), outil du Directeur et du Scénariste ; le battement porte au plus une idée.
- **Le Scénariste prépare, le Directeur décide** : des idées `P-…` (une table de la mémoire), une étape du rêve et un
  rôle de `delegate` ; aucun outil d'action ; il ne commande pas de module (Q8 de M5 maintenue). `Plans` quitte le
  Grimoire, et les récits prévus passent du Rêveur au Scénariste (une seule plume par sujet).
- **Le tableau de bord** : lié à `127.0.0.1`, jeton, lecture seule, HTML et CSS rendus côté serveur, CSP sans script ;
  la règle « pas de JavaScript » est appliquée aussi au tableau de bord, et vérifiée par le navigateur.
- **`/narrator restart`** reconstruit le runtime **serveur vide** (les connexions attendent la fin de l'échange), ce
  qui attend étant fait hors du thread serveur : avec des joueurs en ligne, les garde-fous du §17 ne les protégeraient
  plus pendant l'échange.
- **`director.md`** : les consignes restent dans le prompt système (relu du cache d'un chapitre à l'autre, alors que le
  briefing est réécrit à chaque chapitre) ; la place vient du compactage de ce qui répète les descriptions d'outils,
  validé par le rejeu.

## 17. Décisions prises

Tranché le 25/09/2026 :

1. **Serveur de dev** : Minecraft 26.3 vanilla avec Fabric API et le mod, sans autre mod. Le serveur de jeu ne migrera que lorsque ses autres mods (Farmer's Delight, Refurbished Furniture…) existeront en 26.3. Cette migration sort du périmètre du développement.
2. **LLMGW** : le mod ne dépend pas du type de credentials ; il lui faut une URL et une clé projet. On recommande toujours une clé API Anthropic dans LLMGW (§14), mais le développement n'en dépend pas : les tests automatisés utilisent un faux serveur Anthropic.
3. **Modèles** : Opus 5.5 pour tous les rôles, avec l'effort du §15. Le Directeur est en `medium`, pour la latence ; `high` se règle d'une ligne de configuration.
4. **Chaos et interdits** : le chaos vaut 3 sur 5 par défaut. Le moteur applique ces règles, quel que soit le prompt (composant par règle : [design M1/M2](design/M1_M2.md) §10) :
   - Les constructions des joueurs ne sont jamais détruites définitivement. Un bloc posé par un joueur ne peut être modifié que temporairement, et il est restauré automatiquement.
   - Un objet n'est jamais perdu définitivement : une confiscation est temporaire ou compensée, et sa restitution est tracée.
   - Le Narrateur tue directement un joueur au plus une fois par heure de jeu (au chaos 3), l'heure se comptant en temps joué par ce joueur. Le joueur conserve alors son inventaire ; au-delà du quota, la mort est toujours empêchée : le joueur est sauvé, c'est-à-dire ramené à son point de réapparition et soustrait au danger qui le tuait. Un châtiment du catalogue est un gag, pas une exécution (tranché le 19/10/2026, `M4-POST`) : ses créatures (invités, pluie de slimes) ne visent et ne blessent que le joueur puni, disparaissent à sa mort, à son départ et à la fin du châtiment, et leur coup fatal devient un sauvetage sur place, hors quota et sans rien perdre. Il en va de même des créatures hostiles que le Directeur (ou une règle) invoque avec `spawn` (`M4-LEFTOVERS`) : une créature invoquée par le Narrateur ne tue jamais. Celles d'un module non plus (tranché le 21/10/2026, relecture de M5, décision D1, `M5-FIX-1`) : le module les vise et les envoie sur qui il veut, elles poursuivent et frappent, mais leur coup fatal devient un sauvetage sur place, hors quota et sans rien perdre (M5 §4.5).
   - Le PvP n'est jamais forcé : un duel exige l'acceptation des deux joueurs. Les courses et les défis non violents n'en ont pas besoin.
   - Pas d'acharnement : un joueur mort dans les 5 dernières minutes, ou déjà en plein châtiment, n'est pas ciblé par un nouveau châtiment.
   - Les commandes d'administration sont interdites (§6.3).
5. **Réflexes** (révisé le 19/10/2026) : **Claude Haiku 5.5**, par LLMGW, remplace Jev, qui n'a jamais eu de clé. Il s'active dès que la clé LLMGW est présente (`reflex.enabled`, vrai par défaut), sans réflexion (`reflex.thinking = false` : sur notre jeu d'éval, la réflexion à l'effort `low` gagne 2 à 3 tons et 1 intention sur 52, pas un message adressé, pour un p95 plus lent d'environ 0,9 s), avec un délai de 3 s et l'heuristique locale en repli. Le chat ne part vers aucun nouveau tiers. Mesures et choix : passation `REFLEX-HAIKU`.

6. **EULA de Minecraft** : le propriétaire autorise son acceptation pour tous les serveurs de dev et de test.
7. **Vie privée** : les messages privés entre joueurs sont journalisés, mais **jamais exposés au modèle, y compris par l'outil `sql` en lecture seule** — pas seulement écartés du flux d'événements qui réveille le Directeur (§7.2). Le schéma et les vues exposées au modèle (M1, [design M1/M2](design/M1_M2.md) §5 et §6.4) doivent en tenir compte.
8. **Le thread serveur n'attend jamais la base, sauf au démarrage** (tranché le 18/10/2026, relecture de M3, [constat 21](design/reviews/M3_REVIEW.md)). La règle d'équipe (`CLAUDE.md`) vise la boucle de jeu : tout accès à la base passe par le Journal, hors du thread serveur. Une seule exception, bornée : **au démarrage, avant la première tick, une lecture bornée de la base (30 s au total) est permise sur le thread serveur.** Elle sert au rappel `SERVER_STARTED` de la mémoire : lire le schéma, puis charger les horloges de jeu (l'union des sessions). C'est le même régime que l'ouverture du Journal, juste avant : aucun joueur n'est placé tant que le rappel n'a pas rendu la main, et les deux lectures sont courtes. **L'ouverture du Journal a sa propre borne, à part** : **trois tentatives en 10 s au plus** (à 0 s, puis 2 s et 6 s environ), sur le thread serveur, avant la première tick. Seule une erreur passagère est retentée (fichier tenu un instant par une sauvegarde, un antivirus ou un ancien processus ; erreur d'entrée-sortie) ; un fichier corrompu, une migration qui échoue ou une configuration invalide ne le sont jamais. Chaque tentative ratée écrit un `WARN` avec sa cause, l'échec final un `ERROR` puis `DisabledJournal` (`JOURNAL-RETRY`). Charger l'horloge en tâche de fond et retenir les connexions ajouterait à chaque composant un état « mémoire pas prête », sans gain réel. Au-delà de la borne, la mémoire démarre désactivée (`DisabledMemory`) plutôt que de retenir le serveur. À l'arrêt, l'attente est aussi bornée (`ShutdownSequence`, 11 s pour la mémoire).
