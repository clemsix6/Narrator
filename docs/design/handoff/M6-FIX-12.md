# Passation M6-FIX-12 : le coût du Chroniqueur

Défaut 5 de la recette M6-18 (`M6-18.md`, « Coût » et « Décisions du superviseur »). Branche
`worktree-agent-a378ff2121ce5d847`. **En cas de contradiction avec `M3.md` §7, le code et cette passation font foi.**

## En bref

| | Avant (passe 2 de M6-18) | Après (même soirée rejouée) |
|---|---|---|
| Conversations / requêtes | 6 / 12 | 1 / 8 |
| Prompt système écrit en cache | 6 fois (≈ 31 400 tokens) | 1 fois |
| Coût de la soirée (30 min) | **0,648 $** | **0,414 $** (−36 %), 0,455 $ à cache égal (−30 %) |
| Heure chargée (projection : 6 fenêtres de 10 min, une sieste) | ≈ 0,78 $ | ≈ 0,58 $ (−26 %) |
| Ce qui est attrapé | 2 promesses, 3 interactions, 5 gags resservis, 4 souvenirs, 4 « depuis la dernière fois » | les mêmes, 5 souvenirs, 2 souvenirs marqués repris |

**La division par deux n'est pas atteinte à qualité constante** avec Opus 5.5. La cause est corrigée (le prompt
réécrit par chaque conversation) ; ce qui reste est le contenu lui-même, au prix d'Opus. Les leviers restants sont à
trancher (voir la fin). Coût réel de la tâche : **1,14 $** (plafond 1,50 $).

## 1. Diagnostic

Données : `.research/m6-live/m6-18/run2/` (le Journal ouvert en copie, ses transcriptions) et `run3-quiet/`.

### Ce que la soirée a lancé

| Travail | Déclencheur | Fenêtre | Conversations | Coût |
|---|---|---|---|---|
| lot 8 | minuterie (22:35) | 22:28–22:35 | 1 | 0,131 $ (préfixe des outils froid) |
| lot 10 | minuterie (22:45) | 22:35–22:45 | 1 | 0,095 $ |
| sieste 11 | chapitre 3 clos par la console (22:53) | dernier lot 22:45–22:53, puis consolidation | 2 | 0,272 $ |
| sieste 13 | chapitre 4 (ouvert par les départs, défaut 4) clos par le banc (22:58) | dernier lot 22:54–22:58, puis consolidation | 2 | 0,150 $ |

- **La cadence était conforme** : deux lots de minuterie en 25 min, un toutes les 10 min. Les « 12 appels » viennent de
  ce que chaque conversation fait **deux requêtes** (les appels d'outils, puis le rapport) et que **chaque sieste fait
  deux conversations** (son dernier lot, puis la consolidation). Aucune fenêtre n'était vide : la règle des fenêtres
  sautées n'a rien à sauter dans une soirée chargée.
- La soirée calme : 3 travaux, 3 conversations, 6 requêtes, 0,236 $.

### Où va l'argent (passe 2, 0,648 $)

| Poste | Tokens | Coût | Part |
|---|---|---|---|
| écritures en cache (1 h, 8 $/M : deux fois le prix d'entrée) | 62 066 | 0,497 $ | 77 % |
| dont le prompt système, réécrit par chaque conversation | ≈ 31 400 (6 × 5 240) | 0,251 $ | 39 % |
| dont les outils et le bloc de la passerelle (une fois, à froid) | 5 272 | 0,042 $ | 6 % |
| dont les premiers messages (journal d'échanges, `<known>`, matière de la sieste) | ≈ 19 000 | 0,152 $ | 23 % |
| dont les allers-retours (réflexion et appels réenvoyés, résultats) | ≈ 6 600 | 0,053 $ | 8 % |
| sortie (réflexion, appels d'outils, rapports) | 6 487 | 0,130 $ | 20 % |
| lectures du cache (0,20 $/M) | 108 180 | 0,022 $ | 3 % |

Soirée calme : le prompt réécrit fait 53 % du coût (3 × 5 240 tokens sur 0,236 $).

### Pourquoi le cache est réécrit

- **Ce n'est ni l'heure, ni un contenu variable, ni l'effort, ni la durée du cache.** Le prompt système (11 610
  caractères) et les dix outils (13 377 caractères) ont la même empreinte dans les 9 conversations des deux soirées ;
  aucune heure ni aucun identifiant n'y entre ; l'effort est `low` ; deux lots sont à 10 min l'un de l'autre, loin de
  l'heure du cache. Nous ne posons aucun marqueur (`gateway.cacheTtl = off`) : LLMGW pose les siens (`ttl=1h`).
- **C'est la conversation neuve, à chaque travail, et ce que LLMGW fait du prompt système.** La passerelle rend notre
  prompt **après le premier message** (démontré en M3-POST §3, remesuré ici) : d'une conversation à l'autre, seuls les
  outils et son bloc (**5 272 tokens**, constants) sont relus ; le prompt (≈ **5 240 tokens**) et le premier message sont
  réécrits, au prix d'écriture d'une heure. Mesure : chaque premier appel de lot lit 5 272 et écrit 5 240 + son message
  (par exemple 7 576 = 5 240 + 2 336 pour le lot 10, dont le message fait 4 961 caractères).
- **Dans le code (ancien, `main` à `d2b22b0`)** : `memory/chronicle/Chronicler.kt` l. 134, chaque bloc d'un lot appelle
  `agents.run(…)` ; l. 89 puis l. 99, la sieste appelle son dernier lot (`chronicle(…, last = true)`) puis une autre
  conversation pour la consolidation ; `memory/MemoryAgents.kt` l. 84-98, chaque `run` crée une `Conversation` neuve
  (`chronicler-<8 hex>`), fermée à la fin du tour.
- Le correctif côté passerelle (laisser notre prompt dans `system`, marqué) reste à faire chez le propriétaire
  (`docs/research/LLMGW.md`, M3-POST §3) ; il profiterait aussi au Rêveur, au Scénariste et aux travailleurs.

## 2. Correctif

### Une conversation pour les travaux successifs du Chroniqueur

- `MemoryAgents.Session` (nouveau) : une conversation d'un rôle de mémoire que plusieurs travaux se partagent, un tour
  chacun, **en ajout seul** comme le chapitre du Directeur. Créée avec son premier travail ; chaque travail apporte son
  `RunContext` (les outils écrivent pour lui, le magasin vérifie les citations contre **sa** fenêtre). Un tour qui finit
  mal (refus, coupure, délai, erreur de passerelle ou du moteur, annulation) termine la conversation : son historique
  peut finir sur des appels sans résultat. `MemoryAgents.run` (le Rêveur, le Scénariste) est une session d'un seul
  travail : rien ne change pour eux.
- `Chronicler.converse` : chaque bloc de lot et chaque sieste est un tour de la conversation ouverte, **quel que soit le
  chapitre**. Une nouvelle conversation commence quand l'ouverte (`Session.stale`) :
  - n'a pas servi depuis **45 min** (`MAX_IDLE` : LLMGW cache une heure ; au-delà, tout l'historique serait réécrit) ;
  - a un contexte d'au moins **40 000 tokens** (`MAX_CONTEXT_TOKENS` : chaque tour relit le contexte deux fois à
    0,20 $/M ; au-delà, réécrire le prompt dans une conversation neuve coûte moins ; le modèle du coût donne un optimum
    vers 40 000) ;
  - a un prompt, des outils, un modèle ou un effort qui ne sont plus ceux en vigueur (`/narrator reload`) ;
  - a fini un tour en échec.
- `ChronicleRunner.close()` (nouveau, vide par défaut) : `MemoryJobs.stop` ferme la conversation ouverte une fois les
  travaux arrêtés (fin `done`, le dernier rapport en `summary`). Un crash la laisse ouverte : la réparation du Journal
  la marque `interrupted`, comme toute conversation.
- La ligne `conversations` s'appelle `Chronicle from run N` ; chaque travail est noté dans la transcription
  (`Batch of chapter 3 (run 15): batch run 15`). `memory_runs.conversations` d'un travail donne la conversation de ses
  tours (dédoublonnée) : plusieurs travaux portent la même.
- **Les refus d'un travail** (`RunOutcome.rejected`) se comptent désormais par conversation **et** depuis le début du
  travail (`tool_calls.ts`) : une conversation survit à un travail. Les travaux ne se chevauchent jamais.

### La sieste lit elle-même la fin du chapitre

- La fin du chapitre qu'aucun lot n'a lue (la fenêtre de la sieste) est dans **le message de la sieste** quand elle
  tient en un bloc (24 000 caractères) : `unread_from="22:45:15"` dans l'en-tête, son bloc `<exchange>` (sauf si le
  journal entier du chapitre est déjà là, sans résumé), et son `<known>` réduit au canon et aux défis ouverts
  (`ExchangeLog.known(…, window = false)` : `<written>` et `<shown>` disent le reste pour tout le chapitre). Le modèle
  fait d'abord le travail d'un lot sur ces lignes, puis la sieste : **un tour au lieu de deux**, deux requêtes au lieu
  de quatre.
- Plus d'un bloc (une fin de chapitre que les lots n'ont pas suivie) : les tours de lot d'avant, comme avant, avec le
  passage de fenêtre (`window_passed`) quand l'un échoue.

### `chronicler.md` (le fond est gardé)

Trois retouches, pour ce que le moteur a changé, rien d'autre :
- « Ce que tu reçois » : « Un seul message… Tu as un seul tour » devient « Chaque travail est un message… La
  conversation enchaîne les travaux, parfois de plusieurs chapitres : fais seulement le dernier, sur ses propres
  lignes ; les précédents sont finis, et ce qu'ils ont écrit est en mémoire. Tu as un seul tour par travail… » ; et
  `<known>` « prime sur les travaux précédents ».
- La sieste : « Les lots ont enregistré les engagements des lignes qu'ils ont lues : n'en ajoute pas. Si l'en-tête
  porte unread_from, aucun lot n'a lu les lignes de <exchange> à partir de cette heure : fais d'abord sur elles le
  travail d'un lot, avec le <known> joint, puis la sieste. »
- Le rapport : « en français » (un rapport de sieste est venu en anglais au premier rejeu ; les écritures, elles,
  étaient en français).

Le prompt passe de 11 610 à 12 094 caractères ; son budget de `PromptsTest` de 11 700 à 12 200 (il est écrit une fois
par conversation, plus à chaque lot).

### Ce qui n'a pas changé

- **La cadence reste de 10 min** (`memory.chronicleMinutes`). Un lot toutes les 20 min a été mesuré (B2) : il gagne peu
  (0,018 $ sur la soirée, ≈ 0,04 $ de l'heure en projection : un lot de 20 min réfléchit et écrit presque autant que
  deux de 10) et **son lot n'a compté aucun gag resservi** (« I want to keep these additions modest rather than
  overdone ») là où les autres variantes en comptaient trois ou cinq. Pas à qualité constante.
- Pas de « compaction » de `<known>`/`<written>` déjà vus dans la conversation : ≈ 5 % estimés, pour un mécanisme
  délicat (formats différents entre `<known>` et `<written>`, statuts qui changent).
- Pas de suppression de la requête du rapport : ≈ 8 % estimés, mais le modèle ne verrait plus les refus de ses
  outils avant de finir.

## 3. Mesures

### Le banc

`live/LiveChronicleReplayTest` (nouveau, tag `live`) rejoue les travaux du Chroniqueur d'une soirée archivée sur une
copie de son Journal :

```
$env:NARRATOR2_CHRONICLE_JOURNAL = "C:\Users\Clément\Desktop\Narrator2\.research\m6-live\m6-18\run2\narrator.duckdb"
$env:NARRATOR2_CHRONICLE_RUNS = "8,10,11,13"     # les memory_runs à rejouer (défaut : tous les lots et siestes)
$env:NARRATOR2_CHRONICLE_MINUTES = "20"          # optionnel : recoupe les lots à cette cadence
$env:NARRATOR2_CHRONICLE_BUDGET = "0.5"          # s'arrête entre deux travaux au-delà (0,8 $ par défaut)
.\gradlew.bat liveTest --tests "*LiveChronicleReplayTest*"
```

La copie est d'abord remise dans l'état d'avant ces travaux (lignes insérées supprimées, canon rendu à ses usages et
statut d'avant, audit retiré) : `revertRun` refuse de défaire deux travaux qui ont touché le même canon. Puis chaque
travail repart à son heure d'origine, avec sa fenêtre et un nouveau numéro, sur le vrai magasin et les vrais outils.
Rapport : `build/reports/liveTest/memory/chronicle-replay.md` ; copie et transcriptions dans
`build/live-memory/chronicle-replay-*`. Le banc ne touche qu'aux API publiques du Chroniqueur : il compile aussi sur
l'ancien code.

### Avant / après (Opus 5.5, prix de `trace/Prices`)

| Variante | Travaux | Conv. | Req. | Écrit | Lu | Sortie | Coût |
|---|---|---|---|---|---|---|---|
| A, ancien code (la passe 2 elle-même) | 8, 10, 11, 13 | 6 | 12 | 62 066 | 108 180 | 6 487 | **0,648 $** |
| B1, conversation partagée seule | 8, 10, 11 | 1 | 8 | 26 178 | 137 241 | 5 744 | 0,352 $ (A : 0,498 $) |
| B2, final, lots de 20 min | 8, 10, 11, 13 | 1 | 6 | 28 402 | 122 790 | 6 087 | 0,374 $ |
| **B3, final** | 8, 10, 11, 13 | 1 | 8 | 30 270 | 157 355 | 6 994 | **0,414 $** |

- A a écrit à froid le préfixe des outils (5 272 tokens) ; B1 à B3 l'ont lu. À cache égal, ajouter 0,041 $ aux B :
  B3 = 0,455 $ (**−30 %**).
- Par travail (A → B3) : lot 8 0,131 → 0,092 $ ; lot 10 0,095 → 0,059 $ ; sieste 11 0,272 → 0,184 $ ; sieste 13
  0,150 → 0,078 $. Les siestes gagnent le plus (une conversation, un tour, le prompt déjà là).
- **La conversation est relue entière à chaque tour** : la première requête du tour 2 lit exactement ce que la
  dernière du tour 1 avait (13 957 = 13 028 + 929) ; chaque tour n'écrit que son message (2 700 tokens pour le lot 10).
- **Heure chargée, projection** (5 lots comme le lot 10, une sieste comme la 11, un départ à froid, la conversation
  recommencée une fois à 40 000 tokens) : avant ≈ 0,78 $ (72 000 écrits, 126 000 lus, 9 000 en sortie), après ≈ 0,58 $
  (44 000, 210 000, 9 150) : **−26 %**. Le gain d'une soirée dépend du nombre de siestes : la passe 2 en avait deux
  en 30 min.

### Ce qui a été extrait

| | A | B1 | B2 (20 min) | B3 |
|---|---|---|---|---|
| promesse d'Opaline (choix du jeu), fête de Lisette « reportée » | C-44, C-47 | les deux | les deux | les deux |
| titre « Premier pas en enfer » | — | — | oui | — |
| prière de Lisette, cadeau à Firmin, provocation de Firmin | 3 | 3 | 3 | 3 |
| gags resservis (`use_canon`) | 5 (C-20, C-21, C-19 ×2, C-17) | 3 (sans le chapitre 4) | **2** | **5** (les mêmes) |
| souvenirs de la sieste 11 / « depuis la dernière fois » | 4 / 4 | 5 / 4 | 4 / 4 (+2) | 5 / 4 (+2) |
| souvenirs marqués repris | 0 | 0 | 1 | 2 |

B3 attrape tout ce qu'attrapait A. Une note au Directeur de B3 (N-84) relève que le résumé du chapitre cite C-47 et
C-44 : artefact du rejeu (les promesses ont été réenregistrées sous d'autres numéros), et le signe que la sieste lit bien
le résumé contre la mémoire.

### Coût de la mesure réelle

**1,14 $** : B1 0,352 $, B2 0,374 $, B3 0,414 $ (plafond 1,50 $). **A n'a pas été rejoué** : c'est la passe 2 de M6-18,
mesurée sur ces fenêtres exactes avec l'ancien code ; la rejouer coûtait ≈ 0,60 $, de quoi dépasser le plafond avec
B3. Les premiers tours de B1 à B3 refont d'ailleurs exactement le geste de l'ancien code (5 272 lus, prompt et message
écrits). Tunnel : celui d'un autre agent a disparu en cours de route ; j'ai ouvert le mien (ssh 28292), testé
`/healthz`, fermé à la fin. La clé n'apparaît dans aucun fichier archivé ni dans `docs/` et `TheMod/src`.

Archives : `.research/m6-live/m6-fix-12/` : `b1-session-only/`, `b2-20min/`, `b3-final/` (copie du Journal après le
rejeu, avec `llm_calls` ; transcription), `b3-final/report.md`, `costs.tsv`.

### Ce que coûteraient d'autres modèles (mêmes tokens, chiffrage seul)

Prix par million (skill `claude-api`, `trace/Prices`) : Sonnet 5.5 2 / 10 / 0,20 / 4 $ (entrée, sortie, lecture,
écriture d'une heure) ; Haiku 5.5 0,10 / 0,50 / 0,01 / 0,20 $.

| | Opus 5.5 | Sonnet 5.5 | Haiku 5.5 |
|---|---|---|---|
| passe 2, ancien code | 0,648 $ | 0,335 $ | 0,017 $ |
| passe 2, nouveau code (B3) | 0,414 $ | 0,223 $ | 0,011 $ |
| heure chargée, ancien code | ≈ 0,78 $ | ≈ 0,40 $ | ≈ 0,02 $ |
| heure chargée, nouveau code | ≈ 0,58 $ | ≈ 0,31 $ | ≈ 0,016 $ |

À tokens égaux : chaque modèle produit sa propre réflexion et ses propres écritures. La lecture du cache coûte le même
prix chez Sonnet 5.5 que chez Opus 5.5 (0,20 $/M) : la conversation partagée y gagne moins. Aucun des deux n'a été
évalué sur le travail du Chroniqueur (citations exactes, preuves, français à la troisième personne : les évals E1 et E2
de M3 le mesureraient).

## 4. À trancher

1. **La division par deux n'est pas atteinte** : −30 % à cache égal sur la soirée, ≈ −26 % sur une heure chargée, à
   qualité égale. Ce qui reste est le contenu lu et écrit au prix d'Opus. Les leviers, du plus sûr au plus risqué :
   - **le modèle** : Sonnet 5.5 diviserait encore par deux (≈ 0,31 $ l'heure), après une éval ; décision du propriétaire ;
   - **le correctif de LLMGW** (le prompt système laissé dans `system`, avec un marqueur) : peu pour le Chroniqueur
     maintenant, mais pour tous les rôles à conversations courtes ;
   - **la cadence** à 20 min : ≈ −5 points de l'heure, et un lot qui a sauté les gags resservis dans la seule mesure ;
     déconseillé ;
   - la suppression de la requête de rapport (≈ −8 %) ou la compaction de `<known>` (≈ −5 %) : du code délicat pour peu.
2. **La conversation traverse les chapitres** (une sieste puis les lots du chapitre suivant). À dire si l'on préfère une
   conversation par chapitre : plus simple à lire dans les transcriptions, une réécriture du prompt de plus par
   chapitre (≈ 0,04 $).

## Fichiers

- Production : `memory/MemoryAgents.kt` (`Session`, `open`), `memory/chronicle/Chronicler.kt` (`converse`, `close`, la
  sieste et `Unread`, les refus par travail, `MAX_IDLE`, `MAX_CONTEXT_TOKENS`), `memory/chronicle/ExchangeLog.kt`
  (`known(…, window)`), `memory/MemoryJobsControl.kt` (`ChronicleRunner.close`), `memory/MemoryJobs.kt` (`stop`),
  `prompts/chronicler.md`.
- Tests : `ChroniclerTest` (la conversation partagée, octet pour octet ; inactive 45 min, pleine, effort changé ; les
  refus par travail ; la sieste qui lit sa fin ; la fin trop longue lue par des lots), `ChroniclerReplayTest`,
  `MemoryAgentsTest` (une session), `PromptsTest` (budget), `live/LiveChronicleReplayTest` (nouveau).
- Docs : `MEMOIRE.md` §4 et §5, `research/LLMGW.md`.
- Hors de mes fichiers : aucun. Je n'ai touché ni à l'ouverture ni à la fermeture des chapitres (M6-FIX-11), ni à
  `director.md`, `storyteller.md` (M6-FIX-13), ni aux fichiers de M6-POLISH.

## Ce que les tâches suivantes doivent savoir

- Une conversation du Chroniqueur **reste ouverte** entre deux travaux : sa ligne `conversations` n'a ni fin ni raison
  tant qu'elle sert. Un test qui lit sa fin appelle `chronicler.close()` (ce que fait `MemoryJobs.stop`).
- `memory_runs.conversations` : plusieurs travaux peuvent porter le même identifiant.
- Un test qui enchaîne des travaux sur un même `Chronicler` les voit dans la même conversation : la dernière requête
  porte le travail dans son **dernier** message, plus dans son premier.
- La sieste n'a plus de « dernier lot » quand la fin du chapitre tient en un bloc : pas de conversation ni de tour de lot
  à attendre, un seul tour.

## Vérifications

- Tests ciblés (`ChroniclerTest`, `ChroniclerReplayTest`, `MemoryAgentsTest`, `PromptsTest`, `MemoryJobsTest`,
  `MemoryServiceTest`, `ExchangeLogTest`, `Config*`, `DreamReplayTest`) verts à chaque étape.
- Build complet après `git merge main` : voir le commit.
