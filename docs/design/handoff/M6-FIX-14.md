# Passation M6-FIX-14 : la commande au Scénariste voit ses idées et compte ses messages, le juge accepte les manches

Dernier correctif de M6 : les défauts 1 et 2 de la vérification réelle M6-19 (« Décisions du superviseur », points 1 et
2). Passations lues : M6-19, M6-FIX-11, M6-07, M6-08 (par M6-16), M6-16, M6-18, M6-09. **`director.md` n'a pas changé.**
`storyteller.md` change d'un mot, sans changer de taille. Aucune nouvelle soirée réelle.

Branche `worktree-agent-a5bee33c7eede5894`.

## En bref

| # | Défaut | Cause | Correctif |
|---|---|---|---|
| 1a | La commande « soirée creuse » propose le titre de P-31 | son premier message ne porte que la demande et `<context>` ; `inspire`, sans joueur en ligne ni joueur nommé, ne rend aucune idée du programme | le premier message d'une commande montre **toutes les idées ouvertes en bref** : la tête de leur ligne du programme (référence, sorte, joueurs, titre) |
| 1b | Elle croit avoir épuisé ses six requêtes après six appels d'outils | le prompt compte en « requêtes », une unité que le modèle ne voit jamais ; rien ne lui dit où il en est | **un compteur du moteur** après chaque réponse dont les outils ont tourné, et le prompt compte en « messages » (un mot, même taille) |
| 2 | Le juge tient pour illisible un jeu annoncé par ses étapes | son critère exigeait « pour combien de temps ou jusqu'à quand » et refusait une durée que seuls les actes donnent | une heure, une durée ou un nombre de manches ou d'étapes dit la fin ; un seul suffit. **T2 : 20/20, 19/20, 0,219 $** |

## Défaut 1 : la commande au Scénariste

### Les causes (transcription `storyteller-2e2117df`, M6-19 lancement 1)

1. **Aucune idée ouverte dans ce qu'elle lit.** `Workers.opening` n'écrivait que `<task>` et `<context>`. La seule autre
   source était `inspire` : sans joueur nommé, `Deck.ideas` ne garde que les idées pour tout le monde ou dont tous les
   joueurs sont en ligne, et le banc n'a personne en ligne (`players_online="none"`). Elle a tiré trois graines et zéro idée, puis proposé « Le procès du Fer
   Suspect », le titre de P-31. En jeu, des joueurs seraient en ligne, mais `inspire` ne rend que **deux** idées au plus :
   la collision restait possible avec les dix autres.
2. **Un budget dans une unité invisible.** `storyteller.md` disait « en six requêtes au plus ». Le modèle ne voit ni
   requêtes ni compteur : il voit ses messages et ses appels d'outils. Sa réflexion le dit : « I've already used six
   requests total ». Il avait fait 2 `player_profile`, 1 `inspire` et 3 `propose_idea`, soit six appels en **deux**
   messages, et il en restait quatre. Le refus de titre arrive au deuxième message ; il a cité P-31 dans son rapport au
   lieu d'écrire une troisième idée.

### Le correctif, en code d'abord

**Les idées ouvertes dans le premier message** (`agent/Workers.kt`, `memory/dream/ProgrammeInputs.kt`) :

- `ProgrammeInputs.head(idea)` : `P-41 challenge · Bartok, Mimosa · « La course au pic »` (`everyone` sans joueur, titre
  échappé). C'est désormais **la tête de la ligne d'une idée ouverte dans `<ideas>` du programme** (`openLine`), qui ne
  s'écrit plus deux fois. Seul changement visible à l'étape 5 : « … of play left » passe après le titre (`- P-41 challenge
  · Bartok, Mimosa · « La course au pic » · 2d0h of play left · n4 f4 e4 … · dealt …: pitch`).
- `ProgrammeInputs.taskIdeas(open)` : le bloc d'une commande, sans pitch, sans notes ni rendus.

  ```
  <ideas open="3">
  Open, the latest first (the Narrator already has them):
  - P-31 twist · Gaspard, Firmin · « Le procès du Fer Suspect »
  - P-28 challenge · Firmin, Gaspard · « Le Nether, trop facile ? »
  - P-27 season · everyone · « La saison des vantards »
  </ideas>
  ```

  Sans idée ouverte : `<ideas open="0">(none open)</ideas>`.
- `Workers.opening` l'ajoute après `<context>` **pour le seul Scénariste**. Les idées viennent du cache de la mémoire
  (`Memory.openIdeas()`, rafraîchi après chaque écriture) : rien n'attend la base. Toutes les idées ouvertes y sont,
  dans l'ordre du programme (les plus récentes d'abord) : la saison comprise (une idée ordinaire ne peut pas prendre son
  titre), et une idée échue que l'hygiène n'a pas encore close (le refus de titre de M6-FIX-11 la compte encore).
- La balise est la même que celle du programme, `<ideas>` : le prompt la décrit déjà (« tes idées ouvertes… ») et « La
  variété » dit « ne refais pas ce que <ideas> montre déjà ». **Aucune phrase du prompt n'a été ajoutée pour elle.** La
  ligne « (the Narrator already has them) » dit pourquoi elles ne comptent pas parmi les trois idées à écrire : le
  rapport de M6-19 avait classé P-31 en troisième.
- Taille : environ 75 caractères par idée, douze au plus, soit ≈ 1 000 caractères (≈ 450 tokens, relus du cache ensuite).

**Le compteur** (`agent/AgentLoop.kt`) :

- `TurnLimits` gagne `countdown` (faux par défaut). Avec lui, chaque requête qui rend les résultats d'outils exécutés porte
  une note du moteur (`AgentLoop.countdownNote`), le chemin de toutes les notes (`role: system` après les résultats, ou
  `<engine>` en mode `user_tag`) :

  > Engine: 2 of your 6 messages used (a message counts once, whatever its tool calls): 4 left, the report included.

  et quand le message suivant est le dernier :

  > Engine: 5 of your 6 messages used: the next one is your report, without tool calls (they would not run).

- Le compte est celui de la limite elle-même (`calls`, le nombre de requêtes parties, relance après une réponse vide
  comprise) : la note ne peut pas dire autre chose que ce que le moteur applique. À partir de la limite, seule la note de
  limite d'étapes parle, comme avant.
- `Workers` le met pour le seul Scénariste (`TurnLimits(steps, countdown = storyteller)`). Ni l'Analyste, ni l'Éclaireur,
  ni le Forgeron, ni l'étape 5 du rêve (`TurnLimits(10)`, dont le prompt ne donne pas de budget) ne changent.
- Les conversations restent en ajout seul : la note fait partie du message suivant, validé avec la réponse.

**Le prompt** (`storyteller.md`) : « en six **requêtes** au plus » devient « en six **messages** au plus ». Le prompt
comptait déjà en messages ailleurs (« dans un même message », « Ton dernier message, sans appel d'outil, est ton
rapport », « Plusieurs appels par message »), et la note du moteur parle de la même unité. **8 894 caractères, inchangé**
(« requêtes » et « messages » ont huit lettres). Je n'ai pas ajouté « un refus se corrige par une autre idée » : le refus
le dit déjà (« find another idea, or another title »), et le compteur lui montre qu'il en a la place.

### Ce que ce correctif ne garantit pas

- **La redite de fond** (P-40 contre P-28) : la commande voit maintenant « P-28 challenge · Firmin, Gaspard · « Le Nether,
  trop facile ? » », pas le pitch. Le titre et les joueurs suffisent à reconnaître ce cas, mais rien dans le code ne
  refuse une redite de fond sous un autre titre (le BM25 de M6-06 ne l'a pas vue). Seule une vraie commande le dira.
- **Rien n'a été rejoué en réel** (décision 5 du superviseur). La vérification est celle des tests ; la première vraie
  partie du propriétaire dira si la commande écrit trois idées neuves.

## Défaut 2 : le juge de ton et les jeux à manches

### La cause

`live/ToneJudge.kt` (outillage de test) demandait pour `legible` : « why, and for how long or until when », puis « a
duration only the acts give, and the line leaves out, is not legible ». La réplique L2 de M6-19 annonçait le Jacques a
dit par « huit étapes, une toutes les quinze secondes » ; ses actes portaient le défi de 5 minutes. Pour le juge, la durée
était donc dans les actes et pas dans la réplique : illisible. `director.md` accepte pourtant « sa fin (l'heure ou la
durée du résultat, ou le nombre de manches) ».

### Le correctif

Le critère `legible` du prompt du juge (et le KDoc de `Legibility`) :

> legible: for a line that announces a punishment, a challenge, a law or a duel: yes when the player can tell what happens
> to them (or what to do), why, and when it ends; no otherwise. n_a for any other line. A time, a duration, or a number
> of rounds or steps (« trois manches », « huit ordres ») each tells the end: one is enough, even when the acts give
> another. The acts tell what the line announces: a law the acts just made is a new law, even when an older one was
> disabled; a line that gives no end at all, when only the acts give one, is not legible.

Les exemples ne reprennent pas la réplique de M6-19 (« huit étapes ») pour ne pas calibrer le juge sur le cas qu'il doit
trancher.

### La recalibration (T2, `LiveToneEvalTest`)

| | M6-09 | M6-18 | **M6-FIX-14** |
|---|---|---|---|
| mauvaises attrapées (≥ 18) | 20/20 | 20/20 | **20/20** |
| bonnes acceptées (≥ 18) | 19/20 | 18/20 | **19/20** |
| bonne refusée | m4-41 (`repeats`) | m4-39, m4-41 (`repeats`) | **m4-56 (`illegible`)** |
| coût | 0,212 $ | 0,205 $ | **0,219 $** (2 requêtes, 17 662 tokens écrits en cache, 3 871 en sortie) |

- **Les trois mauvaises illisibles restent attrapées** : un châtiment sans effet ni durée, un défi sans but ni enjeu ni
  fin, une loi cachée.
- **La bonne refusée est nouvelle** : good-m4-56, la proposition de duel « Réglez ça. Le vainqueur gagne une pomme dorée
  et mon respect provisoire. Paris ouverts ! », notée « no end given ». M6-09 et M6-18 l'avaient acceptée, le second
  avec « though no deadline ». Le nouveau texte insiste sur « when it ends », et le juge le demande maintenant aussi à un
  duel, qui finit pourtant avec son vainqueur. C'est un seul cas : il peut s'agir de bruit, mais je pense plutôt que la
  nouvelle formulation en est la cause. Voir « À trancher ».
- **Contrôle sur le cas réel** : les deux répliques de la soirée calme de M6-19 rejugées
  (`NARRATOR2_TONE_EVENING`, budget 0,075 $) : **L2 « huit étapes… » est lisible** (« Clear Simon-says rules, steps,
  reward and penalty »), L1 reste `n_a` ; drôle 1,50, voix 100 %, seuils d'une soirée tous tenus. 0,013 $.
- **Coût total réel : 0,232 $** (plafond 0,30 $). Archives :
  `C:\Users\Clément\Desktop\Narrator2\.research\m6-live\m6-fix-14\` : `tone/calibration-20261022-111544.md` (T2),
  `tone/evening-20261022-111706.md` (rejugement de M6-19), `logs/` (journaux Gradle des deux lancements).

## Tests

| Test | Ce qu'il vérifie |
|---|---|
| `AgentLoopTest` « a countdown turn tells after each reply… » (nouveau) | trois appels d'outils dans une réponse comptent pour un message ; « 1 of your 3… 2 left », puis « the next one is your report », puis la seule note de limite ; sans `countdown`, aucune note |
| `WorkersTest` « a storyteller task sees every open idea in short… » (nouveau) | le premier message d'une commande : les idées ouvertes en bref, les plus récentes d'abord, la saison comprise, une idée abandonnée absente, le titre échappé, aucun pitch ; le compteur après la première réponse ; une tâche de l'Analyste n'a ni idées ni compteur |
| `WorkersTest` « a storyteller task … runs within six requests » | le premier message finit par `<ideas open="0">(none open)</ideas>` ; les requêtes 2 à 6 portent le compte 1 à 5 sur 6, les requêtes 7 et 8 la seule note de limite |
| `ProgrammeInputsTest` « the ideas say each time one was dealt… » | la ligne du programme commence par la tête de l'idée ; `taskIdeas` rend les mêmes têtes, dans le même ordre, et chaque ligne du programme commence par celle de la commande |
| `DirectorStorytellerScenariosTest` S-S1 | lit les résultats d'outils dans le dernier message `user` : le dernier message d'une requête du Scénariste est désormais la note du compteur |

## Documentation

- `docs/ARCHITECTURE.md` (tableau des rôles, le Scénariste) : six messages au plus, ses idées ouvertes en bref, le
  compteur.
- `docs/MEMOIRE.md` (§ des outils à la demande) : une commande voit les idées ouvertes en bref.
- `docs/design/M6.md` §6.4 dit encore « six requêtes au plus » : le code et cette passation font foi.

## Ce que les tâches suivantes doivent savoir

- **Une requête du Scénariste à la commande finit par une note du moteur** (`role: system`) : un test qui lit le dernier
  message d'une requête pour y trouver des résultats d'outils doit lire le dernier message `user`
  (`DirectorHarness.lastUser`, `toolResults`).
- **La ligne d'une idée ouverte dans le programme** commence par sa tête (`ProgrammeInputs.head`) ; le temps de jeu restant
  vient après le titre.
- **Le juge** accepte un nombre de manches ou d'étapes comme fin ; toute retouche de son prompt se recalibre sur T2
  (≈ 0,22 $).

## À trancher

1. **Le juge et les duels.** Après la recalibration, il demande sa fin à un duel (good-m4-56 refusée, acceptée deux fois
   avant). Un duel finit avec son vainqueur, et `director.md` ne lui demande pas d'échéance. Si les vraies soirées
   montrent des duels notés illisibles, ajouter au critère « a duel ends with its winner » et recalibrer (≈ 0,22 $). Je
   ne l'ai pas fait : une seconde calibration dépassait le plafond de 0,30 $.
2. **Le Forgeron compte aussi en « requêtes »** (`forger.md` : « s'il te reste moins de cinq requêtes, arrête-toi ») sans
   compteur. C'est le même défaut, sans cas observé. Un `countdown = forger` dans `Workers` le réglerait en une ligne, avec
   « requêtes » remplacé par « messages » dans `forger.md`, mais aucune forge réelle ne l'a vérifié.
3. **La redite de fond** sous un autre titre reste possible (voir plus haut). La commande la voit maintenant ; le magasin
   ne la refuse pas.

## Vérifications

- Tests ciblés (`AgentLoopTest`, `WorkersTest`, `ProgrammeInputsTest`, `DirectorStorytellerScenariosTest`, `PromptsTest`,
  `PromptToolMentionsTest`, `ReplayHarnessTest`, `DelegationScenarioTest`, `TestMethodsReturnUnitTest`) : 102 tests,
  verts.
- **Build complet** (`.\gradlew.bat build --continue`) après `git merge main` (déjà à jour, `6bc0d2c`) : **2 297 tests unitaires, 0 échec, 0 ignoré**
  (2 295 + 2 nouveaux, `TestMethodsReturnUnitTest` compris) ; **« All 403 required tests passed »** (GameTests), en
  10 min 53 s. Aucun test instable rencontré.
- **Lancements réels** : T2 une fois (vert), le rejugement des deux répliques de M6-19 une fois. 0,232 $ en tout.
- **Tunnel** : rien n'écoutait sur 8088. J'ai ouvert mon propre ssh deux fois (12692 pour T2, testé par `GET /v1/models`
  → 200 ; 8564 pour le rejugement), et je l'ai fermé après chaque lancement : seul le mien, et le port n'écoute plus.
- **La clé** n'a jamais été affichée. Une recherche exacte ne la trouve dans aucun des 1 100 fichiers de l'archive, de
  `TheMod/src` et de `docs/`.

## Décisions du superviseur (22/10/2026)

Fusionnée.

1. **Le juge et les duels : accepté en l'état.** T2 passe à 19/20. On ajoutera « a duel ends with its winner » si une
   vraie soirée montre le refus d'un duel.
2. **Le Forgeron compte aussi en « requêtes » : corriger (M6-FIX-15).** Le défaut est le même et le correctif coûte peu :
   le compteur du moteur s'active pour le Forgeron, et `forger.md` passe à « messages » à taille égale.
3. **La redite de fond sous un autre titre : acceptée.** Une commande voit maintenant les idées ouvertes ; la première
   vraie partie dira si cela suffit.
