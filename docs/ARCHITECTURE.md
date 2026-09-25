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
- **La Forge** : le Narrateur crée de nouvelles mécaniques (squelettes à flèches explosives, vaches qui s'envolent…) en code compilé à chaud contre les vraies classes du jeu, avec retour du compilateur, recherche dans l'API réelle de 26.3 et déchargement propre.
- **Ton et renouvellement garantis par des mécanismes** (historique des interventions, tics détectés, registres comiques, arcs à fin programmée, cooldowns), pas seulement par le prompt.

## 1. Ce qu'on retient de la v1

Les idées de la v1 étaient bonnes ; l'exécution était du bricolage. L'analyse du code et des specs de la v1 montre que la plupart des problèmes venaient de **fils non branchés** et d'une **charte qui poussait au sombre**, plus que du modèle lui-même. On repart de zéro en gardant les idées et en traitant chaque cause par un mécanisme.

| Problème de la v1 | Causes trouvées | Réponse de la v2 |
|---|---|---|
| Récits qui tournent en rond | Briefing construit avec des entrées vides : fiches joueurs, chat et état du monde n'arrivaient jamais au modèle, seuls ses propres arcs passés y étaient. Consolidation aveugle (elle ne voyait pas ce que le Narrateur disait) : les punchlines « à ne pas répéter » étaient inventées. Anciens événements rejoués comme neufs. Intentions et assistants inopérants présentés comme actifs. Arcs impossibles à clore. Cadence imposée (« au moins une action », relances quand il se taisait). | Briefing couvert par un test qui vérifie que fiches, chat et état y sont. Consolidation sur la transcription complète. Tics calculés mécaniquement à partir de ce qui a vraiment été dit. Curseur d'événements par conversation et horodatage relatif. Le modèle ne voit que des outils réellement branchés (doc générée depuis le registre d'outils). Arcs avec identifiant, fin programmée et jachère. Aucun quota : ne rien faire est une décision valable. |
| Ton qui devient glauque | Charte de surveillant : quota de 70 % de sabotage, destruction d'une partie « régulière », jamais de prévenance, tribut exigé et colère s'il est ignoré, exemples gothiques. Autoportrait et réflexions rédigés dans la voix du personnage puis réinjectés à chaque réveil : le Narrateur se mythifiait lui-même. | Voix comique d'abord, avec exemples et contre-exemples. Registres comiques qui tournent. « Méchant de dessin animé ». La persona est figée dans le prompt : la mémoire est **factuelle, à la troisième personne**, et ne réécrit jamais la voix. Carotte et bâton, lisibilité (« le joueur peut raconter en une phrase ce qui lui est arrivé »). |
| Commandes de datapack invalides | Le modèle écrivait de mémoire une syntaxe qui avait changé, et la documentation censée l'aider était fausse : filtrée au mauvais seuil de version, tirée de la dernière snapshot au lieu de 26.1.2, tronquée par la fin. | Plus de datapacks. Actions **typées** validées côté serveur. Commandes brutes analysées par le vrai parseur du serveur *avant* exécution, avec l'usage exact de la commande en cas d'erreur. Toute connaissance de version vient du serveur en cours d'exécution (arbre des commandes, registres), jamais d'Internet. |
| Plugins JS mal maîtrisés | API maison, erreurs découvertes seulement à l'exécution, crochets manquants (mort, dégâts, connexion…), assistants lancés sans aucun outil qui annonçaient « plugin écrit » sans rien faire. | Règles déclaratives validées (§10.1) et modules Java compilés contre les vraies classes (§10.2), avec erreurs immédiates, recherche d'API dans le jeu en cours d'exécution et un bus d'événements riche. Assistants avec de vrais outils. |
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
- **les réglages** (config, prompts, textes affichés, clients Claude et Jev) : remplacés d'un bloc par `/narrator reload`.

L'arrêt se fait en deux temps : à `SERVER_STOPPING`, plus aucun travail nouveau et les appels à Claude sont annulés sans rien ajouter aux conversations ; à `SERVER_STOPPED`, une fois les joueurs déconnectés et leurs départs journalisés, le Journal est vidé (avec un délai maximal) puis la base fermée.

**Ce qui doit rester cohérent avec le monde vit dans le monde** : l'index des blocs posés, les lieux visités, l'état des garde-fous (modificateurs d'attributs, confiscations, mort protégée) et l'échéance des créatures du Narrateur sont des pièces jointes Fabric persistantes, sauvées avec le tronçon ou le joueur. DuckDB garde l'historique ; il n'est jamais la source d'un état de jeu.

## 3. Stack technique

| Brique | Choix | Pourquoi |
|---|---|---|
| Plateforme | Fabric (Loader + Fabric API), **côté serveur uniquement** | Léger, à jour rapidement, idéal pour un mod serveur. Aucun ajout requis chez les joueurs. |
| Langage du mod | Kotlin (via Fabric Language Kotlin), mixins en Java | Coroutines pour l'orchestration asynchrone ; DSL agréable pour le SDK de la Forge |
| Noms du jeu | Noms officiels de Mojang (le jeu n'est plus obfusqué depuis 26.1) | Le code compilé à chaud voit exactement les mêmes noms que le jeu en cours d'exécution |
| Claude | SDK Java officiel d'Anthropic, pointé sur **LLMGW** | Protocole Anthropic Messages standard ; LLMGW gère clés projet, budgets et comptes |
| Journal et mémoire | **DuckDB** embarqué (JDBC) | Base analytique en colonnes : agrégations rapides sur des millions de lignes, SQL riche (fenêtres, `QUALIFY`, JSON, listes), aucun serveur à gérer |
| Recherche plein texte | Lucene (analyseur français) | BM25 local, léger ; k-NN possible plus tard |
| Code à chaud | **Java 25** compilé en mémoire : javac si le serveur tourne sur un JDK, sinon ECJ embarqué (3,4 Mo) | ~50 ms par compilation une fois chaud, API standard, et c'est en Java que Claude écrit le mieux contre les classes de Minecraft (voir §10) |

Versions vérifiées (fixées dans `TheMod/gradle.properties`) :

| Composant | Version |
|---|---|
| Minecraft | 26.3 (sortie le 15/09/2026), Java 25 |
| Fabric Loader / Fabric API | 0.19.5 / 0.161.0+26.3 |
| Loom / Gradle | 1.18 (plugin `net.fabricmc.fabric-loom`, sans remapping) / 9.7.1 |
| Fabric Language Kotlin | 1.14.1 (Kotlin 2.4.20, coroutines 1.11) |
| SDK Anthropic Java | 2.65.0 |

Depuis 26.1, Minecraft n'est plus obfusqué : plus d'Intermediary ni de Yarn, les mods sont compilés et s'exécutent directement avec les noms de Mojang. C'est ce qui rend la Forge possible sans remapping.

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

- **Parole et mise en scène** : `say` (tout le monde ou certains joueurs), `show_title`, `actionbar`, `play_sound`, `give_book` (une prophétie, un contrat, une amende…). La barre de boss arrive en M4, attachée aux défis.
- **Effets sur les joueurs** : `apply_effect` / `remove_effect`, `set_attribute` / `reset_attribute` (temporaire), `teleport` (atterrissage sûr, même dimension en M2), `launch`, `give_item` / `take_item` (objets décrits en JSON : nom, lore, enchantements, incassable… convertis en composants 26.x par le code ; une confiscation est toujours temporaire).
- **Effets sur le monde** : `spawn` (mobs équipés, nommés, à durée de vie), `lightning`, `explosion`, `fireworks`, `particles`, `set_weather`, `set_time`.
- **Commandes brutes** : `run_command`, en dernier recours. La commande est d'abord **analysée par le parseur Brigadier du serveur sans être exécutée** : si elle est invalide, le Narrateur reçoit l'erreur, sa position, et l'usage exact de la commande en 26.3. L'outil `command_help` renvoie l'arbre de syntaxe d'une commande. C'est ce qui manquait à la v1.

La liste exacte des 32 outils de M2, avec leurs paramètres, garde-fous et retours, est dans le [design M1/M2](design/M1_M2.md) §9. Les schémas JSON sont générés depuis les classes d'arguments Kotlin et validés côté serveur ; on n'utilise pas `strict: true` (trop limité en nombre d'outils et en contraintes, et non vérifié à travers LLMGW).

### 6.2 L'arsenal des attributs
Les attributs de 26.x offrent des châtiments et récompenses spectaculaires, visibles par tous les clients, sans une ligne de code : `scale` (joueur minuscule ou géant), `gravity` (gravité lunaire), `jump_strength`, `movement_speed`, `block_break_speed`, `safe_fall_distance`, `step_height`, `block_interaction_range`, `entity_interaction_range`, `max_health`, `attack_speed`, `knockback_resistance`, `oxygen_bonus`, `burning_time`… Chaque modification porte un identifiant et une durée : elle est retirée automatiquement, même après un redémarrage.

### 6.3 Garde-fous
Commandes interdites (`stop`, `op`, `deop`, `ban`, `whitelist`, `save-off`… et toutes celles qui modifient des blocs, des objets, la progression ou des entités, y compris derrière `execute … run`) ; seules les commandes relues pour le Narrateur passent, celles des autres mods sont refusées tant qu'un opérateur ne les a pas autorisées. Puissance d'explosion plafonnée, nombre d'entités invoquées plafonné, joueurs protégés configurables (ses créatures ne les blessent pas plus que les joueurs morts depuis peu), aucune destruction de base. Les plafonds dépendent du niveau de chaos ; le composant qui applique chaque règle du §17 est décrit dans le [design M1/M2](design/M1_M2.md) §10.

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
| Message qui s'adresse au Narrateur (détecté par Jev, §7.6 ; repli : nom, question, réponse à une conversation en cours) | Réveil immédiat |
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
| **Éclaireur** | Explorer le monde : trouver une arène pour un duel, la structure la plus proche de chaque joueur, un lieu pour un piège | Oracle, `locate`, SQL (positions) |
| **Forgeron** | Écrire, compiler et tester un module de la Forge | Outils de la Forge (§10) |
| **Scénariste** | Proposer trois idées originales (défi, châtiment, rebondissement) notées sur la nouveauté, le fun et l'équité, en évitant ce qui a déjà été fait | Mémoire, historique des interventions, `inspire` |
| **Chroniqueur** | Consolider la mémoire : extraction continue, sieste de fin de chapitre | Journal, mémoire (écriture) |
| **Rêveur** | Le rêve profond pendant les temps morts (voir MEMOIRE.md §5) | Journal, mémoire (écriture), Forge, Éclaireur |

Trois assistants au plus en parallèle par défaut, chacun avec un nombre d'étapes et une durée plafonnés. Le Directeur et les assistants partagent le même moteur de conversation. M2 livre l'**Analyste** ; l'Éclaireur arrive en M4 avec les mécaniques qui en ont besoin, les autres rôles avec leur jalon. L'`enum` des rôles de `delegate` ne contient que les rôles réellement câblés.

### 7.4 Chapitres et contexte
- La conversation du Directeur est **append-only** : on n'édite jamais un tour passé, on ne change pas le prompt système ni la liste d'outils en cours de chapitre. C'est indispensable avec Opus 5.5 (sa réflexion est liée au préfixe exact de la conversation) et c'est ce qui rend le cache de prompt efficace.
- Les informations changeantes (situation, rappels) sont **ajoutées** au nouveau message, jamais réinjectées dans un ancien.
- Les consignes des administrateurs et du moteur arrivent comme messages *système* en cours de conversation, placés juste après le message `user` de l'envoi : autorité d'opérateur, sans casser le cache. Repli réglable (`director.operatorChannel = user_tag`) : une balise `<operator>` ou `<engine>` à la fin du message `user`, que les joueurs ne peuvent pas imiter (`<` et `>` sont échappés dans leur texte). Une 400 qui refuse le rôle `system` déclenche ce repli automatiquement.
- Un envoi n'entre dans l'historique qu'avec une réponse utilisable : un refus, une réponse tronquée ou une erreur ne laissent ni tour `assistant` vide ni message orphelin.
- Au-delà de ~150k tokens (clôture au prochain moment calme ; 180k : clôture immédiate en fin de tour), ou quand le serveur est vide depuis 10 minutes, le chapitre se clôt par un **résumé factuel** écrit par le Directeur dans sa propre conversation (cache chaud), et le suivant repart d'un briefing reconstruit depuis le Journal et, en M3, la mémoire (MEMOIRE.md §2 et §8). Un test vérifie que le briefing contient l'état du monde, les joueurs et le chat récent. L'outillage, le prompt système et le briefing sont mis en cache : un tour ne paie que ses nouveaux tokens.

### 7.5 Latence
Une réponse de chat coûte un appel au modèle, soit quelques secondes. Pour que l'attente fasse partie du jeu, le joueur qui a interpellé le Narrateur voit « ✎ le Narrateur réfléchit… » dans sa barre d'action. Leviers si c'est trop lent : baisser l'effort du Directeur (§14), utiliser le mode rapide d'Opus 5.5 si la passerelle le permet.

### 7.6 Les réflexes : Jev
Claude est la **réflexion** du Narrateur ; **Jev** (TypeSafe AI) en est le **réflexe**. Jev est un modèle de classification hébergé : on lui envoie un état et des questions typées (oui/non, choix parmi une liste, score sur une échelle), il répond par des probabilités en 0,25 à 0,7 s depuis l'Europe, pour un coût quasi nul. Il ne sait pas écrire de texte, et c'est très bien ainsi : il trie, Claude décide.

| Où | Questions posées à Jev | Ce que ça change |
|---|---|---|
| **Tri du chat** (chaque message) | S'adresse-t-il au Narrateur ? Intention (question, provocation, insulte, demande, compliment, excuse, blague, conversation entre joueurs, tentative de manipulation) ? Ton envers le Narrateur (hostile → amical) ? | Réveil immédiat seulement quand c'est utile ; l'événement arrive annoté au Directeur ; les tentatives de manipulation sont signalées |
| **Filtre des battements** | Le résumé des 5 dernières minutes contient-il quelque chose qui mérite une intervention ? | Pas d'appel à Opus pour un battement sans matière : moins de coût, moins d'interventions forcées |
| **Signaux de personnalité** | Pour chaque message d'un joueur : politesse, vantardise, entraide, agressivité, humour (scores) | Agrégés dans le temps, ils deviennent des **preuves chiffrées** pour les traits de la fiche joueur |
| **Réaction aux interventions** | Après une intervention : le joueur a-t-il ri, joué le jeu, ignoré, été agacé ? | Nourrit l'historique des interactions (valence) et le bilan de ce qui marche |
| **Surveillance du ton** | Chaque réplique du Narrateur, après envoi : sombre ? cruelle ? répétitive ? plate ? | Si le ton dérive, une consigne système recadre le Directeur. C'est le garde-fou qui manquait à la v1 |
| **Réminiscences** | Ce souvenir est-il un bon rappel pour cet événement ? | Filtre les candidats de la recherche locale avant de les attacher |

Précautions :
- **Jev ne décide jamais seul d'une conséquence importante.** Ses réponses sont non déterministes et peuvent être influencées par un texte écrit pour le tromper (un joueur qui se fait passer pour gentil). Les jugements sur les joueurs sont **agrégés dans le temps** par le code, et Claude reste le décideur.
- **Le français n'est pas encore évalué** (Jev est entraîné surtout en anglais). Avant de s'y fier, on mesure sa précision sur un jeu de messages français constitué pour l'occasion.
- **Le chat part vers des serveurs aux États-Unis** : à mentionner dans les règles du serveur.
- Client HTTP maison (il n'y a pas de SDK Java officiel), tracé comme les appels à Claude, avec **repli** sur des heuristiques si Jev est indisponible ou non configuré. Version épinglée (`jev-1.13.0`) une fois les seuils réglés.

## 8. La mémoire

Voir **[MEMOIRE.md](MEMOIRE.md)** : les cinq couches, la fiche joueur (identité, récap, personnalité, stats, historique des interactions), le canon, les souvenirs, la chronique hiérarchique, les rêves, le RAG hybride et les budgets de contexte.

## 9. Les mécaniques de jeu

### 9.1 Défis
Des objets de jeu à part entière : participants, règles, durée, enjeux (récompense, gage), barre de boss, progression, et **résolution automatique** quand le moteur sait détecter la victoire.

| Type | Exemple | Détection |
|---|---|---|
| Course à l'objet | « Le premier qui obtient un œuf de tortue » | inventaire / ramassage / fabrication |
| Course au lieu | « Le premier au sommet de ce pic », « le premier dans une ancient city » | positions, structures |
| Collecte | « 64 bambous en 5 minutes » | inventaire |
| Chasse | « 10 araignées avant l'aube » | kills |
| Survie | « Survivre 10 minutes sans armure » | morts, équipement |
| Duel | PvP dans une arène, positions restaurées après | morts, dégâts |
| Pari | Les joueurs misent des objets sur une issue | selon l'issue |
| Quiz / énigme | Répondre dans le chat | chat |
| Construction jugée | « La plus belle statue en 15 minutes » | jugement du Narrateur (Oracle sur la zone) |
| Personnalisé | Condition implémentée par un module de la Forge | module |

### 9.2 Châtiments
Un catalogue paramétrable, avec une sévérité de 1 à 5 : joueur minuscule, gravité lunaire, pioche au ralenti, inventaire mélangé, poules en orbite, pluie de slimes, téléportation au sommet d'un arbre, faim soudaine, invités surprises (mobs nommés), éclair « d'avertissement »… Plus tout ce que la Forge sait fabriquer. Chaque châtiment a une raison, une durée, et passe par les **cooldowns du moteur** (par joueur, par catégorie, plafond de sévérité selon le niveau de chaos configuré).

### 9.3 Faveurs
La carotte : objets nommés avec une histoire (« La Pioche du Pardon »), effets bénéfiques, titres honorifiques, protection temporaire, jokers (annuler un châtiment), indices vers un trésor.

### 9.4 Pièges et intentions
Reprise d'une bonne idée de la v1 : le Narrateur peut **armer une intention**, une règle évaluée par le moteur — « quand Bartok entrera dans le Nether, fais apparaître un comité d'accueil », « si Mimosa descend sous y=0, félicite-la ». Il prépare ainsi des surprises sans surveiller en boucle.

### 9.5 Récits
Des arcs narratifs avec un titre, une prémisse, des actes (début, escalade, résolution), une fin prévue et une durée maximale. Un arc actif à la fois (deux au plus). Un thème clos entre en « jachère » et ne revient pas tout de suite.

### 9.6 Rythme
Des objectifs indicatifs dans le prompt (un défi toutes les 20–40 min, quelques altérations du monde par session…) et des **limites dures dans le moteur** (cooldowns, nombre de châtiments actifs, sévérité maximale), réglées par un **niveau de chaos** de 0 à 5.

## 10. La Forge : du code à chaud

La Forge a deux étages. Le premier couvre la plupart des envies sans une ligne de code ; le second, tout le reste.

### 10.1 Les Règles : déclaratives, instantanées
Une règle est un objet JSON validé par un schéma strict : **déclencheur → conditions → effets**. Le Directeur les crée lui-même, sans passer par un assistant, et elles s'activent immédiatement. Les cinq exemples du cahier des charges tiennent dans des règles :

| Envie | Déclencheur | Conditions | Effets |
|---|---|---|---|
| Squelettes à flèches explosives | un projectile touche | tireur = squelette | explosion (puissance 2, sans casser de blocs) |
| Silverfish téléporteurs | une entité est blessée | victime = silverfish, attaquant = joueur | téléporter l'attaquant au hasard (rayon 300, atterrissage sûr) |
| Vaches qui s'envolent et explosent | une entité est blessée | victime = vache | lévitation 3 s, puis explosion et feux d'artifice |
| Creepers sous caféine | une entité apparaît ou se charge | type = creeper | vitesse ×3 (modificateur d'attribut temporaire) |
| Diamants explosifs | un bloc est cassé | bloc = minerai de diamant | explosion (puissance 3) |

Déclencheurs, conditions et effets correspondent aux actions typées du §6 : un seul moteur, validé côté serveur, avec des erreurs précises (identifiant inconnu, puissance hors limites…). Une règle a une durée, des cibles (joueurs, zone) et se retire proprement.

### 10.2 Les Modules : du Java compilé à chaud
Pour les mécaniques vraiment nouvelles (IA de mobs, mini-jeux, machines à états), le Forgeron écrit un module Java.

**Pourquoi Java plutôt que Kotlin ?** Nous avons évalué les deux ; des mods Kotlin compilés à chaud existent en 26.3 (Kursive, Katton). Mais Kotlin impose d'embarquer ~61 Mo de compilateur, lent au premier démarrage (plus de 5 s) et à garder synchronisé avec Fabric Language Kotlin. javac compile un module en ~50 ms une fois chaud (mesuré sur cette machine), l'API `javax.tools` est standard, et ECJ (3,4 Mo) prend le relais si le serveur tourne sur un simple JRE. Surtout, l'immense majorité du code de mods Minecraft est en Java : c'est dans ce langage que Claude écrit le mieux contre les classes du jeu. Le mod lui-même reste en Kotlin.

Principes :

1. **Compilé contre le vrai jeu.** Le classpath de compilation est construit à partir des jars réellement chargés (le jeu, Fabric API, le SDK du Narrateur). Depuis 26.1, les noms du jeu à l'exécution sont ceux de Mojang : pas de remapping, pas d'API inventée, erreurs de compilation immédiates avec ligne, colonne et message.
2. **Un SDK court et stable** au-dessus de Minecraft pour l'essentiel des besoins (réagir à un coup, à une mort, à un bloc cassé, à un projectile qui touche, à l'apparition ou au tick d'un mob ; exploser, lancer, téléporter, invoquer ; planifier ; stocker un état), avec accès complet aux classes du jeu pour le reste. Les règles du §10.1 et les modules partagent ce moteur.
3. **Nos propres crochets.** Les événements de Fabric API ne se désinscrivent pas, et il en manque (projectile qui touche, explosion, tick d'entité). Le mod a donc son propre bus d'événements, alimenté par Fabric API et des mixins, où chaque abonnement appartient à un module.
4. **Le Forgeron ne devine pas.** Il cherche les vraies signatures dans le jeu en cours d'exécution (`api_search`, `api_class`), liste les registres (entités, objets, blocs, particules, sons, effets), consulte la doc du SDK et les modules existants, compile, lit les erreurs, corrige.
5. **Cycle de vie propre.** Compiler → charger (inactif) → déployer (durée, cibles) → décharger. Un chargeur de classes par module ; tout ce qu'il enregistre passe par le SDK et lui appartient. Au déchargement, tout est retiré (abonnements, tâches, modificateurs d'attributs, buts d'IA, entités marquées) puis le chargeur est abandonné.
6. **Garde-fous.** Java n'a plus de bac à sable intégré : on filtre à la compilation (pas de fichiers, réseau, processus, threads ni réflexion), on chronomètre chaque gestionnaire, on désactive automatiquement un module qui plante ou ralentit le serveur, et tout expire par défaut.
7. **Un arsenal qui grandit.** Les règles et modules réussis sont conservés, réutilisables et paramétrables. Le mod en livre une quinzaine écrits à la main, qui servent aussi d'exemples au Forgeron.

Le Directeur n'écrit pas de code Java : il crée des règles lui-même, et commande les modules au Forgeron (« un mini-jeu de chaises musicales avec des minecarts autour de Bartok »), continue à jouer, puis déploie au moment le plus dramatique.

## 11. Le ton et le renouvellement

Mécanismes concrets contre la répétition et la dérive :

- **Prompt de voix** avec exemples et contre-exemples (`prompts/director.md`), et une liste de **registres comiques** à faire tourner (faux documentaire, commentaire sportif, bureaucratie divine, tribunal, bulletin météo, prophétie ridicule…).
- **Historique des interventions** dans chaque rapport de situation : type, cible, registre, heure.
- **Tics de langage** détectés pendant les rêves (expressions et structures surutilisées) et listés dans le rapport.
- **`inspire`** : tire trois graines d'idées (mécanique × thème × rebondissement) dans un deck, en excluant ce qui a servi récemment.
- **Le Scénariste** pour les interventions importantes : trois propositions, notées, la meilleure retenue.
- **Arcs à fin programmée** et thèmes en jachère.
- **Auto-évaluation pendant les rêves** : ce qui a fait réagir les joueurs est renforcé, ce qui est tombé à plat est abandonné.
- **Cooldowns et plafonds** dans le moteur : même si le modèle s'emballe, le jeu reste jouable.

## 12. Traces et débogage

Tout doit pouvoir s'expliquer après coup : pourquoi le Narrateur a dit ça, pourquoi il n'a rien fait, pourquoi ce module a planté. Trois niveaux de traces.

### 12.1 Les conversations avec Claude
- **Chaque appel** au modèle est enregistré (table `llm_calls`) : conversation, tour, rôle, but (tour, résumé de chapitre, tâche), modèle, effort, identifiants de requête et de message, durée et temps jusqu'au premier token, tokens (entrée, cache lu, cache écrit, sortie), raison d'arrêt et catégorie de refus, erreurs et tentatives. Les appels à Jev y figurent aussi (`provider = 'jev'`). L'identifiant de requête vient de l'en-tête `request-id`, sinon de `X-Cpa-Trace-Id` posé par CLIProxyAPI : LLMGW ne relaie pas `request-id` (vérifié par `liveTest`, `docs/research/LLMGW.md` §3). Le temps jusqu'au premier token représente typiquement 70 à 100 % de la durée totale d'un appel (vérifié par `liveTest`) : le flux SSE arrive presque en bloc à la fin plutôt qu'étalé.
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
Des lignes courtes, en anglais, préfixées par sous-système (`[Director]`, `[Tool]`, `[Guard]`, `[Journal]`, `[Observer]`, `[Sql]`, `[Jev]`, `[LLM]`, `[Worker]`, puis `[Forge]`, `[Memory]`…), avec un niveau réglable par sous-système dans la configuration.

### 12.4 Consulter
```
/narrator trace [n]            # les n derniers tours du Directeur, résumés
/narrator trace turn <id>      # le détail d'un tour : événements, réflexion, outils, répliques
/narrator trace task <id>      # le détail d'une tâche d'assistant
/narrator why                  # pourquoi la dernière action (déclencheur + résumé de réflexion + actions)
/narrator sql <requête>        # requête en lecture seule sur le Journal et les traces
```
Ces commandes attendent que le Journal ait écrit ce qui précède (une seconde au plus) puis lisent la base. Tout est aussi requêtable en SQL (`llm_calls`, `tool_calls`, `engine_decisions`, `turns`…). Un tableau de bord web des traces viendra en M6.

## 13. Sécurité et administration

- **Le chat est une entrée non fiable.** Les messages des joueurs sont balisés comme paroles de personnages ; le prompt traite les tentatives de manipulation comme un manque de respect (en jeu). Les vraies consignes n'arrivent que par le canal opérateur.
- **Garde-fous du moteur** (§6.3) indépendants du modèle.
- **Canaux de parole** (repris de la v1) : mention publique dans le chat ; `/pray <message>` pour parler au Narrateur en privé (le nom d'une commande est un littéral du code, donc anglais ; « prier le Narrateur-dieu » est dans le ton) ; `/narrator whisper <consigne>`, réservé aux opérateurs, qui arrive au Directeur comme consigne système.
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
/narrator module list | unload <id> | unload-all
/narrator challenge list | cancel <id>
/narrator fiche <joueur>
/narrator memory …               # voir MEMOIRE.md §12
/narrator trace …                # voir §12.4
/narrator usage                  # consommation de tokens par rôle
```

## 14. Claude via LLMGW

- Le mod utilise le **SDK Java officiel d'Anthropic** avec l'URL de LLMGW et une **clé projet LLMGW**. C'est l'API Messages standard : LLMGW gère les comptes, les budgets et le suivi de consommation.
- **On accède au serveur LLMGW existant du propriétaire par un tunnel SSH sur son réseau local ; on n'installe jamais LLMGW en local** (voir `CLAUDE.md` et `docs/research/LLMGW.md`).
- **Modèles** : Opus 5.5 pour le Directeur, configurable **par rôle** (modèle et effort).
- **Effort** : le Directeur démarre à `medium`. Sur Opus 5.5, `medium` dépasse le `high` d'Opus 5 et répond plus vite ; `high` reste disponible dans la configuration. Le Forgeron et le Rêveur travaillent en `high` : ils sont asynchrones, la latence n'y coûte rien.
- **Contraintes d'Opus 5.5 prises en compte** : réflexion toujours active (l'effort est le seul réglage), pas de `tool_choice` forcé, historique append-only, gestion du `stop_reason: refusal`.
- **Affinité de session** : chaque conversation envoie un en-tête `X-Session-ID` stable. CLIProxyAPI s'en sert pour garder une conversation sur le même compte amont, là où vit son cache de prompt.
- **Cache de prompt** : outils, prompt système et briefing sont stables pendant un chapitre. `gateway.cacheTtl` (`off` par défaut, `1h` ou `5m` sinon) contrôle le marqueur `cache_control` que le mod pose lui-même. **Vérifié par `liveTest` (25/09/2026)** : le serveur LLMGW du propriétaire pose déjà ses propres marqueurs `ttl=1h` (prompt système, derniers tours), et le cache fonctionne sans marqueur de notre part (5 344 tokens écrits puis relus) ; un marqueur de plus des nôtres est refusé (400 « A maximum of 4 blocks with cache_control ») à partir du deuxième tour, et un `5m` l'est toujours (conflit de `ttl`) — d'où le défaut `off`. `1h`/`5m` restent utiles derrière une passerelle qui ne poserait pas son propre marqueur. Les noms d'outils restent courts (LLMGW les préfixe en `mcp__llmgw__…` et laisse tels quels ceux de plus de 52 caractères).
- **Messages `system` en cours de conversation** : **acceptés et suivis, vérifié par `liveTest` (25/09/2026)** — c'est ce qui permet à `director.operatorChannel` de rester en mode `system_message` par défaut ; le repli `user_tag` reste disponible en configuration et par bascule automatique sur une 400 (voir [design M1/M2](design/M1_M2.md) §12.7, acquis vérifié à son §20 point 1).
- **Quotas** : les budgets de LLMGW (appels, tokens, coût par heure ou par jour) protègent le compte. Le mod suit aussi sa consommation par rôle et espace les battements quand le budget se tend.

### Credentials OAuth ou clé API
Avec des credentials d'abonnement Claude (OAuth), CLIProxyAPI « déguise » chaque requête en Claude Code : il remplace le prompt système par l'identité de Claude Code (le nôtre est déplacé dans des messages système), reconstruit les en-têtes bêta et réécrit les marqueurs de cache. Le mod fonctionne quand même, mais de façon moins prévisible. Surtout, les conditions d'Anthropic réservent l'OAuth des abonnements Free/Pro/Max à Claude Code et aux applications d'Anthropic : alimenter un serveur multijoueur avec n'entre pas dans ce cadre. **Recommandation : une clé API Anthropic déclarée dans LLMGW pour le projet du Narrateur.** Rien ne change côté mod.

**Constaté par `liveTest` (25/09/2026)** : la passerelle réécrit nos requêtes avant de les transmettre — elle insère un bloc avant notre texte et pose elle-même les marqueurs de cache décrits ci-dessus. D'après la lecture du code de LLMGW (`docs/research/LLMGW.md` §2), ce comportement est le signe d'un cloaking CLIProxyAPI actif, donc **vraisemblablement d'un compte d'abonnement (OAuth) en amont plutôt que d'une clé API**. Ce n'est qu'un faisceau d'indices côté client, pas une confirmation du credential réellement configuré côté serveur : à vérifier avec le propriétaire. Cela renforce l'enjeu de la recommandation ci-dessus (conditions d'utilisation d'Anthropic).

## 15. Configuration

`config/narrator2/narrator2.json` : écrit une seule fois, de façon atomique, avec toutes les valeurs par défaut au premier lancement (jamais de clé LLMGW dans le fichier — elle vient uniquement de la variable d'environnement nommée par `gateway.apiKeyEnv`, `NARRATOR_LLMGW_KEY` par défaut). Les chargements suivants relisent le fichier de l'opérateur et le fusionnent avec les défauts **clé par clé** : une section ou un réglage absent du fichier prend sa valeur par défaut sans que le fichier soit réécrit. `config/narrator2/prompts/` surcharge les prompts du jar et est relu par `/narrator reload`.

**M0 ne livre que `gateway`, `roles` et `logging` ci-dessous** (contenu exact du fichier créé par `ConfigStore`, `NarratorConfig.kt`) : `jev`, `language`, `chaos`, `journal`, `observer`, `director`, `workers` et `safety` **arrivent avec M1 et M2** — une section non encore utilisée est retirée du fichier plutôt que d'y traîner sans effet, et revient avec son jalon. Valeurs et règles de validation de ces sections futures : [design M1/M2](design/M1_M2.md) §15.

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
| **M2 — Directeur v1** | Boucle événementielle, saillance, parole, Oracle, actions typées, validation des commandes, prompt de voix, transcriptions | Le Narrateur réagit au chat et au jeu |
| **M3 — Mémoire** | Fiches joueurs, canon, souvenirs, interactions, chapitres et briefing, Chroniqueur, rêves | Il se souvient, sur des centaines d'heures |
| **M4 — Mécaniques** | Défis, châtiments, faveurs, pièges, récits, cooldowns, barres de boss, Règles (§10.1) | Des parties rythmées, des règles du monde folles |
| **M5 — Forge** | SDK, compilation Java à chaud, outils d'introspection, Forgeron, arsenal de départ | Il invente de nouvelles mécaniques |
| **M6 — Finitions** | Scénariste, `inspire`, tics, évaluation du ton, tableau de bord web des traces | Il se renouvelle, et on voit tout |

Le design détaillé et exécutable de M1 et M2 (contrats, schéma SQL, formats, outils, garde-fous, tâches pour les agents) est dans [docs/design/M1_M2.md](design/M1_M2.md). Il tranche en outre, le 25/09/2026 :
- **Un seul bus de faits** pour le Journal, la saillance, puis le moteur de mécaniques et les modules de la Forge (abonnés synchrones ou asynchrones à file bornée, désabonnement par propriétaire). L'interception d'actions du jeu (annuler des dégâts…) relève des crochets de la Forge, pas du bus.
- **L'état qui doit rester cohérent avec le monde vit dans le monde** (pièces jointes Fabric), DuckDB ne garde que l'historique (§2).
- **Outils** : 32 en M2, schémas générés depuis les arguments Kotlin, validés côté serveur, sans `strict: true` ; les outils sont figés par conversation et le modèle ne voit que ceux qui sont réellement branchés ; « ne rien faire » n'est pas un outil, `remind_me` programme un réveil.
- **Garde-fous** : chaque règle du §17 a un composant qui l'applique ; les plafonds découlent du niveau de chaos ; le quota de morts se mesure en temps joué (stat vanilla `PLAY_TIME`) ; tant qu'aucun outil n'écrit de blocs (M2), la règle sur les constructions est garantie par l'absence d'API, la liste des commandes interdites et le blocage des explosions du Narrateur.
- **Jev** : une requête de 9 questions par message de chat (tri et signaux de personnalité, stockés dès M2 pour M3), questions en anglais, repli heuristique alimenté par un fichier de mots français.
- **Canal privé** : `/pray` ; **Analyste seul** en M2, Éclaireur en M4.

## 17. Décisions prises

Tranché le 25/09/2026 :

1. **Serveur de dev** : Minecraft 26.3 vanilla avec Fabric API et le mod, sans autre mod. Le serveur de jeu ne migrera que lorsque ses autres mods (Farmer's Delight, Refurbished Furniture…) existeront en 26.3. Cette migration sort du périmètre du développement.
2. **LLMGW** : le mod ne dépend pas du type de credentials ; il lui faut une URL et une clé projet. On recommande toujours une clé API Anthropic dans LLMGW (§14), mais le développement n'en dépend pas : les tests automatisés utilisent un faux serveur Anthropic.
3. **Modèles** : Opus 5.5 pour tous les rôles, avec l'effort du §15. Le Directeur est en `medium`, pour la latence ; `high` se règle d'une ligne de configuration.
4. **Chaos et interdits** : le chaos vaut 3 sur 5 par défaut. Le moteur applique ces règles, quel que soit le prompt (composant par règle : [design M1/M2](design/M1_M2.md) §10) :
   - Les constructions des joueurs ne sont jamais détruites définitivement. Un bloc posé par un joueur ne peut être modifié que temporairement, et il est restauré automatiquement.
   - Un objet n'est jamais perdu définitivement : une confiscation est temporaire ou compensée, et sa restitution est tracée.
   - Le Narrateur tue directement un joueur au plus une fois par heure de jeu (au chaos 3), l'heure se comptant en temps joué par ce joueur. Le joueur conserve alors son inventaire ; au-delà du quota, la mort est empêchée.
   - Le PvP n'est jamais forcé : un duel exige l'acceptation des deux joueurs. Les courses et les défis non violents n'en ont pas besoin.
   - Pas d'acharnement : un joueur mort dans les 5 dernières minutes, ou déjà en plein châtiment, n'est pas ciblé par un nouveau châtiment.
   - Les commandes d'administration sont interdites (§6.3).
5. **Jev** est intégré aux réflexes et s'active automatiquement dès que `TYPESAFE_API_KEY` est présente. Sans clé, des heuristiques locales le remplacent. Le chat part déjà vers les États-Unis (Anthropic) : Jev n'ajoute pas de nouvelle catégorie de risque.

6. **EULA de Minecraft** : le propriétaire autorise son acceptation pour tous les serveurs de dev et de test.
7. **Vie privée** : les messages privés entre joueurs sont journalisés, mais **jamais exposés au modèle, y compris par l'outil `sql` en lecture seule** — pas seulement écartés du flux d'événements qui réveille le Directeur (§7.2). Le schéma et les vues exposées au modèle (M1, [design M1/M2](design/M1_M2.md) §5 et §6.4) doivent en tenir compte.
