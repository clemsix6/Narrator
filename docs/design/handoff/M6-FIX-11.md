# Passation M6-FIX-11 : quatre correctifs du moteur trouvés par la recette M6-18

Lot M6-FIX-11 des « Décisions du superviseur » de `handoff/M6-18.md` (défauts 2, 4, 6 et 7). Passations lues : M6-18,
M6-FIX-9, M6-16, M6-08, M6-07, M6-14, M6-02 (la veille du style), M4-14 (la validation des règles). **Aucun prompt n'a
changé** (M6-FIX-13 les retouche en parallèle) ; une description d'outil a changé, voir le point 3. **En cas de
contradiction avec `M6.md`, le code et cette passation font foi.**

Branche `worktree-agent-a5d52b8308dde0c88`.

## En bref

| # | Défaut | Cause réelle | Correctif |
|---|---|---|---|
| 4 | Un chapitre s'ouvre sur des départs, pour personne | tout réveil sans chapitre en ouvrait un (`Chapters.current()`), et un `LEAVE` est saillant | rien ne s'ouvre sur un serveur vide ; sans chapitre, un départ n'est pas raconté ; le `JOIN` du retour ouvre le chapitre |
| 6 | Plafond contourné par une loi à deux effets | `RuleValidator` faisait passer à chaque effet les vérifications de son outil, un par un | les quantités des effets d'un même outil s'additionnent avant d'être comparées au plafond |
| 7 | Un titre repris tel quel n'est pas une redite | la règle de redite est un BM25 sur titre + pitch, seuil 0,6 : un autre pitch dilue le titre | un titre normalisé égal à celui d'une idée ouverte est refusé, avant le BM25 |
| 2 | Répliques trop longues, aucun drapeau | la relecture Haiku ne mesure pas la longueur ; les notes de ton ne lisaient que ses étiquettes | le code compte phrases et paragraphes de chaque réplique relue ; au-delà, note de ton `long` |

## 1. Un départ n'ouvre jamais de chapitre, et rien ne s'ouvre sur un serveur vide (défaut 4)

### La cause

- `director/Director.kt`, `wake()` (aujourd'hui l. 674) appelle `chapters.current()`, et `director/Chapters.kt:149`
  (`suspend fun current(): Chapter = current ?: open()`) ouvre un chapitre **à tout réveil sans chapitre**, sans regarder
  qui est en ligne ni ce qui réveille.
- `director/Salience.kt:314` : `PlayerLeft -> Route.Forward(Urgency.SOON, "left")`. Un départ réveille le Directeur 3 s
  après (`WakeScheduler.SOON_QUIET_MS`).
- La soirée de M6-18 (transcription `director-5d0e6d54`, archivée dans `.research/m6-live/m6-18/run2/`) : la console ferme
  le chapitre 3 ; les quatre `LEAVE` ouvrent le chapitre 4 sur « No player online » (24 973 tokens écrits en cache pour
  « Fin. ») ; Gaspard et Firmin reviennent 30 s plus tard dans ce chapitre, leur fiche dit `joined; away 33s`, et le
  Directeur se tait (« Just reconnecting briefly »).

### Le correctif (`director/Director.kt`, `step()`)

- **Sans chapitre ouvert et sans personne en ligne** (`awaitArrival`) : aucun réveil. Ce qui s'est passé en jeu part comme
  pendant un silence (`mute()` : les événements non immédiats, les indices de la mémoire, les notes de ton) ; le reste
  attend (tâches, rappels, engagements échus, chuchotements d'opérateur, nouvelles du moteur, notes de la mémoire). L'état
  publié est `idle`, raison `nobody online` ; une ligne `INFO` une fois par attente. Le `JOIN` du premier joueur réveille
  le Directeur, ouvre le chapitre avec son briefing, et emporte ce qui attendait.
- **Sans chapitre ouvert, avec du monde** (`dropDepartures`) : les `LEAVE` en attente sont jetés (`DEBUG`). Un départ
  n'ouvre rien, et le briefing du chapitre suivant dit qui est en ligne.
- **Dans un chapitre ouvert, serveur vide** : les événements du jeu ne réveillent plus le Directeur (ils restent dans la
  boîte, non réveillants). Le dernier `LEAVE` ne coûte plus un tour « Fin. » ; le chapitre se clôt `server_empty` au bout
  de `director.emptyServerCloseMinutes` (10), comme avant ; un joueur qui revient avant le réveille dans le même chapitre,
  avec les départs restés en attente (« LEAVE Bartok », puis « JOIN Bartok »). Les autres signaux (une tâche, un rappel, un
  engagement échu) réveillent toujours un chapitre ouvert.
- Pause, blocage, recul et chapitre en clôture passent avant, comme avant.

### Les cas voisins, vérifiés

- **La mort ou le changement de dimension d'un joueur seul** : `observer/PlayerStates` ne retire un joueur qu'à son
  `PlayerLeft` (une mort, une réapparition ou un changement de dimension le laissent en ligne : `PlayerStatesTest`) ; la
  mort d'un joueur seul ouvre donc le chapitre comme avant (nouveau test). Un changement de dimension est `NEXT` : il ne
  réveille personne, seul ou pas. Un joueur seul qui meurt puis quitte aussitôt : la mort part avec le départ (serveur vide).
- **Un chapitre ouvert sans personne** : il ne peut plus s'**ouvrir** ainsi. Un chapitre qui **devient** vide reste ouvert
  10 minutes (décision : voir plus bas), sans tour de plus.
- **Ce qui ferme un chapitre quand le serveur se vide** : `Chapters.emptyServerCloseAt`, inchangé (10 min sans personne,
  comptées au plus tôt depuis l'ouverture). Testé avec les départs qui ne réveillent plus.
- **Les rappels** : un rappel échu sur un serveur vide sans chapitre attend la première arrivée (le test « reminder late »
  de M4 a changé en conséquence).

### Tests

- `ChapterScenariosTest`, cinq nouveaux : les départs après une clôture par un opérateur n'ouvrent rien et le retour reçoit
  le chapitre 2 avec son briefing (sans `LEAVE`) ; sur un serveur vide, une mort et un départ sont oubliés, un rappel
  attend l'arrivée de Mimosa, qui ouvre le chapitre 1 (déclencheur `reminder`) ; dans un chapitre ouvert, le dernier départ
  ne réveille personne et le chapitre se clôt `server_empty` ; un joueur revenu avant la clôture réveille le même
  chapitre, avec son départ ; la mort d'un joueur seul ouvre le chapitre.
- Trois tests existants mettaient un joueur en ligne **après** avoir posté leurs signaux sur un serveur vide sans chapitre
  (`DirectorStyleTest`, `MemoryScenariosTest`, `DirectorMechanicsScenariosTest` pour le rappel en retard) : le joueur est
  maintenant en ligne d'abord, ou arrive avec un `JOIN`.
- **GameTest** `DirectorChapterGameTests` (lot à part, `narrator2-gametest:director_chapters`), le scénario de M6-18 avec
  le faux serveur Anthropic : deux joueurs arrivent (le chapitre s'ouvre), la console ferme le chapitre (résumé), les deux
  partent ; 12 s plus tard, aucune requête du Directeur, pas de chapitre, état `idle` « nobody online » ; ils reviennent
  (nouveaux objets joueurs, mêmes profils) : nouvelle conversation, briefing qui porte le résumé du chapitre fermé, leurs
  deux `JOIN` et aucun `LEAVE` ; l'ouverture parle (`say` → « ok: said to everyone (2 online) »).

## 2. Le plafond d'une action vaut pour la règle entière (défaut 6)

### La cause

`mechanics/rule/RuleValidator.kt`, étape 3 (l. 411) : chaque effet, rempli d'une cible fictive, passe les vérifications
à sec de son outil (`EngineActions.check`), **un par un**. `SpawnTool.check` (`tool/action/WorldActionTools.kt:242`)
compare le `count` d'**un** appel à `Caps.spawnsPerAction`. Deux `spawn` de 4 et de 1 au chaos 2 (plafond 4) passaient
donc : cinq cochons par mise à mort. Le cas du rejeu n'était pas un faux positif du rejeu : le vrai code l'acceptait.

### Le correctif

Étape 5 nouvelle de la validation (`wholeRule`, l. 295), après les vérifications de chaque effet : pour chaque quantité
plafonnée qui **s'additionne** quand un déclenchement exécute plusieurs effets du même outil, la somme doit passer le
plafond d'un appel. Le refus dit ce qui dépasse, en anglais :

> Invalid arguments: effects 1 and 2 (spawn) summon 5 creatures together, and at chaos 2 one firing may summon 4 creatures
> at most: the caps hold for the whole rule, not for each effect; lower them

| Outil | Quantité | Plafond |
|---|---|---|
| `spawn` | `count` (1 s'il manque) | `spawnsPerAction` |
| `lightning` | `damage_hearts` | `lightningHearts` |
| `explosion` | `power` (montre comprise : l'outil plafonne aussi celles qui ne blessent pas) | `explosionPower` |
| `teleport` | `random_radius` (deux sauts au hasard vont deux fois plus loin) | `teleportBlocks` |

**Pas additionnés, par décision** : les niveaux et les durées (`apply_effect`, `set_attribute`, `mob_effect` : un effet
remplace celui de sa sorte ; un enchaînement par délais est borné par `delay_seconds` ≤ 60) ; `launch` (il fixe la
vitesse du joueur, il ne s'y ajoute pas) ; `punish` (déjà un par cible, `oncePunished`).

### Le même trou ailleurs ?

- **Récompenses et gages des défis et des duels** (`mechanics/challenge/Stakes.kt`) : un seul enjeu par gagnant
  (`RewardSpec`) et un seul gage par perdant (`ForfeitSpec`), chacun vérifié par son outil : pas de somme possible.
- **`punish`** (`mechanics/punish/PunishmentCatalog.kt`) : chaque plan a au plus une étape par outil (un `spawn`, un
  `lightning`, un `launch`), plafonnée par le catalogue lui-même (`creatures`, `lasting`, `level`) : pas de trou.
- **Modules** : un module agit appel par appel, comme le Directeur ; chaque appel passe ses plafonds, et le nombre de
  créatures vivantes du Narrateur (`livingMobs`) borne le tout. Ce n'est pas une « action à plusieurs effets ».
- **À trancher** (voir la fin) : une règle peut encore combiner `punish guests` (jusqu'à 4 zombies au chaos 2) et un
  `spawn` (4 de plus) ; ce sont deux outils différents. Et une règle validée au chaos 3 garde ses effets si l'opérateur
  baisse le chaos : à l'exécution, chaque appel repasse son plafond, mais la somme n'est revue qu'à la création.

### Tests

`RuleValidationTest`, un nouveau test : six cochons refusés au chaos 2 ; **4 + 1 refusés** (le cas exact) ; trois `spawn`
sans `count` plus un de 2 (5) refusés ; deux sauts au hasard de 150 refusés (300 > 200) ; 2 + 2 acceptés, `spawn` 4 plus
une `explosion` acceptés (outils différents) ; au chaos 3, deux foudres de 2 cœurs (4 > 3) et deux explosions de 2
(4 > 3, séparées par un `say`) refusées.

## 3. Un titre repris tel quel est une redite (défaut 7)

### La cause

`memory/DuckMemoryStore.kt`, `Job.notRepeated` (l. 313) : la redite est le BM25 du titre **et du pitch** de la nouvelle idée
contre les idées des 14 derniers jours, rapporté au BM25 du texte contre lui-même, seuil 0,6. P-41 « Le procès du Fer
Suspect » (`twist`, autre pitch) contre P-28 du même titre (`challenge`) : trois mots communs dans un texte d'une
vingtaine, bien sous 0,6.

### Le correctif

`Job.newTitle` (l. 328), appelé par `addIdea` juste **avant** `notRepeated` : une idée **ouverte** dont le titre normalisé
est le même refuse la nouvelle. Normalisation (`memory/MemoryRows.kt`, `titleKey`) : décomposition NFD sans les accents,
minuscules, `œ`/`æ` écrits en deux lettres, tout ce qui n'est ni lettre ni chiffre (ponctuation, guillemets, espaces)
réduit à une espace, bords coupés. « Le procès du Fer Suspect » et « le PROCES du « fer suspect » ! » sont le même titre.

> Invalid arguments: same title as P-28 « Le procès du Fer Suspect » (open, never dealt): find another idea, or another title

- **Contre les idées ouvertes seulement** (la mission) : une idée close libère son titre ; le BM25 garde la mémoire des
  14 jours pour le reste.
- **Une saison n'est pas comparée à la saison qu'elle remplace** (`makeRoom` la ferme dans le même travail) : une saison
  peut garder son titre d'un rêve à l'autre. Une idée ordinaire ne peut pas prendre le titre de la saison ouverte.
- **L'abandon d'une idée par une commande** (P-29) : accepté par le superviseur, rien n'a changé.
- **Description d'outil changée (du code, pas un prompt)** : `propose_idea` dit maintenant « Refused when it is too close
  to a recent idea **or takes the title of an open one**, … » (`tool/storyteller/StorytellerTools.kt`). `storyteller.md`
  n'a pas changé ; M6-FIX-13 peut le mentionner s'il le juge utile.

### Tests

`IdeasStoreTest` : le cas exact (« Le procès du Fer Suspect », autre sorte, autre pitch) refusé avec casse, accents,
guillemets et espaces différents ; un autre titre passe ; le titre d'une idée abandonnée est libre ; une nouvelle saison
garde le titre de l'ancienne (à la ponctuation près), et une idée `speech` ne peut pas le prendre. Le test BM25 existant
réutilisait le titre exact : sa seconde idée s'appelle maintenant « Course au pic » (le BM25 la refuse toujours).
`StorytellerToolsTest` : idem, plus le refus du titre par l'outil.

## 4. La relecture des répliques mesure la longueur (défaut 2)

### La cause

La relecture (`style/LineReviewer.kt`, `LineReviewLabels.kt`) demande à Haiku un registre, cinq défauts et des
béquilles : rien sur la longueur. Les notes de ton (`style/ToneWatch.kt`, `drifts`, l. 117) ne lisaient que ces
étiquettes. Les deux répliques de cinq et six phrases en deux paragraphes n'ont donc levé aucun drapeau.

### Le chemin des drapeaux (il existait)

`ToneWatch` poste un `DirectorSignal.StyleNote` (urgence `NEXT` : jamais un réveil) ; le Directeur le rend au modèle comme
note du moteur, `Engine: tone check: …`, avec sa requête suivante (le réveil suivant, ou entre deux appels d'outils du même
tour), la plus grave seulement ; une décision `style tone_noted` le trace. La longueur prend ce chemin.

### Le correctif

- `style/LineLength.kt` (nouveau) : **phrases** (une suite de `.`, `!`, `?` ou `…`, suivie des guillemets et parenthèses
  qui ferment, puis d'une espace, insécables françaises comprises, ou de la fin ; une dernière partie sans ponctuation
  compte ; le point de `2.5` ou de `21:49:30` n'en est pas un) et **paragraphes** (les parties non vides qu'un saut de ligne
  sépare). Trop longue : **plus de trois phrases, ou plus d'un paragraphe**. Le seuil vient de `director.md` (« une à trois
  phrases par message, souvent une seule »), sans le modifier ; le saut de ligne est un second message collé au premier.
  Pas de seuil en caractères : `director.md` n'en donne pas.
- `StyleNoteKind.LONG` (`director/DirectorControl.kt`), **le moins grave** : un drapeau de ton sur la même requête passe
  avant lui.
- `ToneWatch.said` (l. 70) : la mesure de chaque nouvelle réplique, **qu'elle soit étiquetée par Haiku ou non** (relecture
  coupée, en pause, au plafond horaire, en échec), appelée par `StyleWatchImpl.said` (l. 91) pour les répliques que la
  relecture lit (`Tics.opens` : dites avec `say`, à tous ou en chuchotement, 25 caractères au moins ; **jamais un titre**,
  une ligne de barre d'action ni un livre). Les lignes relues du Journal au démarrage ne sont pas mesurées. Même cadence
  que les autres dérives (une note `long` toutes les 30 min au plus), même interrupteur (`style.toneNotes`). La note :

  > Engine: tone check: your line « Ce que j'ai trouvé ? … Moi, j'ap… » has 5 sentences in 2 paragraphs: a message is one
  > paragraph of one to three sentences, often one; keep the punchline

  La décision `style tone_noted` (sujet `long`) porte la réplique, `sentences` et `paragraphs`.
- **Aucun refus** de `say` ni d'un chuchotement : la réplique part, la note règle les suivantes.
- `StyleReport.FLAWS` : la liste des cinq défauts que la relecture note (colonnes de `narrator_lines`) ; `Flaw.entries` ne
  convenait plus, `LONG` n'étant pas une colonne. Le rapport `/narrator style` et la page « Style » du tableau de bord ne
  montrent pas la longueur (ils lisent les étiquettes) ; les notes `long` sont dans les décisions.

### Tests

`LineLengthTest` (nouveau) : les deux répliques de M6-18 (5 phrases en 2 paragraphes, 6 en 2), les bonnes de la soirée
(« Petite annonce… » : 3 phrases malgré les guillemets ; la flaque de lave : 3), les fins de phrase (espaces insécables,
`?!`, parenthèse, guillemet fermant, nombre, heure, dernière partie sans point, lignes vides), le seuil. `ToneWatchTest`,
trois nouveaux : la note `long` et sa décision ; trois phrases ne sont pas longues, cadence de 30 min et interrupteur ; la
veille mesure les répliques relues, jamais un titre ni une ligne trop courte ni une ligne déjà tenue.

## Écarts et décisions

1. **Un chapitre qui devient vide reste ouvert ses 10 minutes**, et un joueur qui revient dans ce délai le réveille sans
   nouvelle ouverture (sa fiche dit `joined; away …`, les départs suivent). Le fermer dès le dernier départ coûterait un
   résumé et un briefing neuf (≈ 0,2 $) à chaque reconnexion brève, et casserait la continuité d'une même soirée. Ce que la
   mission demande, « un joueur qui arrive après un chapitre fermé reçoit une vraie ouverture », est fait.
2. **Sans chapitre, les départs sont jetés** plutôt que gardés pour l'ouverture : un `LEAVE` raconté une heure plus tard
   est du bruit, et le briefing dit déjà qui est en ligne et qui est passé dans les 24 h.
3. **Sur un serveur vide sans chapitre, ce qui s'est passé en jeu est oublié comme pendant une pause** (même tri que
   `mute()`), y compris une prière d'un joueur qui est parti : la même règle que pendant un silence, un seul code.
4. **Les sommes des règles** : quatre quantités seulement (tableau du point 2), celles qui s'additionnent vraiment.
5. **La longueur ne passe pas par Haiku** : mesurée par le code au moment où la réplique est dite, elle ne dépend ni de la
   relecture ni de son quota. Elle n'est pas écrite dans `line_labels` (aucune migration).

## Ce que le superviseur doit trancher

- **`punish` + `spawn` dans une même règle** : deux outils, donc pas de somme ; au chaos 2, `punish guests` (jusqu'à
  4 zombies) plus `spawn` 4 font 8 créatures par déclenchement (borné par `livingMobs`, 8 au chaos 2). Faut-il compter les
  créatures du plan de `punish` dans la somme de `spawn` ? C'est faisable (le plan est calculable à la création).
- **Le chaos baissé après la création d'une règle** : la somme n'est vérifiée qu'à la création ; chaque appel repasse son
  propre plafond à l'exécution. Revoir la somme à chaque déclenchement compterait des échecs (cinq désactivent la règle).
- **La longueur dans le tableau de bord** : faut-il une colonne (migration de `line_labels`) ? Aujourd'hui, seules les
  décisions `tone_noted` la gardent.

## Vérifications

- Tests ciblés verts au fil de l'eau : `director.*` (197), `style.*`, `mechanics.rule.*`, `IdeasStoreTest`,
  `tool.storyteller.*`, `web.*`.
- Build complet avant la fusion (`.\gradlew.bat build --continue`) : **2 285 tests unitaires, 0 échec, 0 ignoré**
  (2 270 + 15 nouveaux) ; **« All 403 required tests passed »** (402 + `DirectorChapterGameTests`, 64,7 s), en 11 min.
- `git merge main` (M6-FIX-13 : `director.md`, `storyteller.md`, l'outillage du rejeu), sans conflit, puis build complet :
  **2 286 tests unitaires, 0 échec, 0 ignoré** ; **« All 403 required tests passed »** (le nouveau GameTest en 58,4 s), en
  10 min 39 s. Aucun test instable rencontré.
- La règle de longueur de `director.md` (« une à trois phrases par message ») n'a pas changé avec M6-FIX-13.
- Aucun appel réel à Claude, aucun tunnel ouvert.
