# Passation M7-CLARITY : un Narrateur qu'on comprend

Retour du propriétaire, le 22/10/2026 : « il est bizarre le narrateur, c'est incompréhensible ce qu'il raconte, il
parle de formulaire, de crayon etc. ». Les deux répliques en cause :

- « Sous la plage, la taupe Thawyn a trouvé sa vocation : trente-quatre filons de charbon, pas une construction
  d'autrui abîmée. Le ciel, ému, retire un crayon de son dossier. Un seul, ne t'emballe pas. »
- « Trois minutes. Le crayon retiré du dossier de Thawyn aura tenu trois minutes avant qu'une torche de Clowew ne
  disparaisse. Formulaire 12-T : crayon réintégré, et la prochaine torche se paie en taille de lapin. »

**En cas de contradiction, le code et cette passation font foi.**

## La cause

Les transcriptions du serveur du propriétaire (`director-cc91a4f9`, `director-995936a8`, lues sans rien modifier)
montrent une métaphore filée sur toute la soirée :

1. À 17:25, le Directeur répond à une plainte de Clowew : « le ministère note ton avarice fongique dans ton dossier, au
   crayon pour l'instant ». Il en fait un running gag du canon (C-4, « le Narrateur tient un dossier sur clemsix6 »).
2. Le briefing du chapitre suivant lui remet ce gag sous les yeux. Le « dossier » revient dans six répliques, avec
   « le greffe », des « pages blanches », puis le « crayon » passe sur Thawyn et finit en « Formulaire 12-T ».
3. Le prompt y poussait : registre « bureaucratie divine (formulaires, tampons, amendes) », un exemple « Ministère de
   la Survie, formulaire 27-B », une mémoire « tenue comme un registre de greffe », un « greffier » qui relit le chat.
   Le moteur aussi : sa ligne `Style:` propose `divine_bureaucracy` parmi les registres les moins servis.
4. Rien ne disait au Directeur que le sens passe avant la blague. Il a donc laissé la menace à moitié dite (« la
   prochaine torche se paie en taille de lapin ») et repris les mots du moteur (« construction d'autrui »).
5. Ni la relecture des répliques ni la veille du ton ne connaissaient ce défaut.

## Ce qui a changé

### Les prompts

- `director.md`, nouvelle règle de voix **« Clair d'abord »** : la réplique se comprend à la première lecture, sans
  connaître le mod ni les répliques d'avant ; menace et récompense se disent en clair (avec un exemple et un
  contre-exemple) ; le sens d'abord, la blague ensuite ; ni métaphore filée, ni jargon inventé (formulaire numéroté,
  dossier, crayon), « même si tes répliques d'avant en usaient : abandonne-les ».
- Ce dernier membre de phrase vient du banc d'essai : sans lui, le Directeur en `high` écrivait encore « Le crayon
  retourne dans le dossier » dans une conversation déjà pleine de dossiers.
- « De la suite dans les idées » : un gag revient « s'il se comprend sans l'épisode d'avant ».
- La sortie de personnage couvre aussi les mots du moteur (construction d'autrui, quota, sévérité). Le ton qui dérive
  peut être « obscur ».
- Ont été retirés : l'exemple du formulaire 27-B, « (formulaires, tampons, amendes) » (devenu « (amende, demande
  rejetée) »), « registre de greffe », « greffier » (devenu « le Chroniqueur »), « remplir des registres » et
  « un dossier à lire à voix haute ».
- Titres, à la demande de M7-TITLES : `show_title` devient « rare : un nom, un mot, « CHAPITRE II » ; ce qui doit être
  compris va dans `say` », et `say` « ta voix ». Le « titre à l'écran » des faveurs devient « titre honorifique ».
- Pour rester dans le budget, les passages suivants ont été raccourcis : la relecture des registres, les messages
  privés, la phrase du « dieu mal renseigné » et un exemple de délégation.
- `line_review.md` : sixième défaut, `obscure`.
- `storyteller.md` : « dossier, formulaire » rejoignent les tics à éviter.
- `dreamer.md` : `obscure` parmi les drapeaux, et les exemples « formulaires administratifs » ont été remplacés.

**Taille de `director.md` : 34 998 caractères avant, 34 963 après** (cible 35 000 ; le test `PromptsTest` tolère 36 000).

### La relecture des répliques et la veille du ton

- **Défaut `obscure`** (0 à 1) dans le schéma de Haiku. On le trouve dans `LineLabels.obscure`, la colonne
  `line_labels.obscure` (migration `V5__line_obscure.sql`, NULL dans les lignes plus anciennes), la vue
  `narrator_lines`, `StyleReport.FLAWS`, `StyleNoteKind.OBSCURE` (après hors personnage, avant plat), les
  `<tone_flags>` du Rêveur et le tableau de bord.
- **Seuil de la note : `ToneWatch.OBSCURE = 0,6`**, et non 0,8 comme le prompt le demande. Haiku note les deux
  répliques du propriétaire entre 0,6 et 0,7, et les 40 bonnes répliques de l'éval 0,35 au plus.
- **Jargon mesuré sans appel** (`ToneWatch.said`, `ToneWatch.JARGON` : dossier, formulaire, crayon, tampon, en début de
  mot) : la note « your line « … » uses invented paperwork (« dossier ») that players do not follow; say plainly what
  happened, what you think and what comes next; no jargon, no metaphor carried over » part dès que la réplique est dite,
  relue ou non. Une note par sorte toutes les 30 min, comme les autres.
- Le rapport du Rêveur et le tableau de bord gardent leur seuil unique `StyleReport.FLAG = 0,7`. Une réplique notée
  0,6 en obscur reçoit donc une note de ton sans compter parmi les répliques signalées du rapport. C'était déjà le cas
  pour plat et répétitif.

### Les efforts (`NarratorConfig.DEFAULT_ROLES`)

| Rôle | Avant | Après |
|---|---|---|
| director, storyteller, armorer | medium | **high** |
| analyst, scout, chronicler | low | **medium** |
| forger, dreamer | high | **xhigh** |
| relecture des répliques (Haiku) | low (`reflex.effort`) | **medium**, nouvelle clé `style.lineReviewEffort` |

- La relecture a désormais son propre effort : `ReflexClient.label(…, effort)`, validé parmi `low | medium | high`.
- Les autres réflexes (étiquettes du chat, battements) restent à `reflex.effort = low`. Ils réveillent le Directeur
  sous un délai de 3 s, et le propriétaire n'a demandé que la relecture.

### Le banc d'essai

- `DirectorReplayEval` mesure maintenant la latence et l'effort de chaque version :
  - `NARRATOR2_REPLAY_EFFORT_A` et `_B` donnent l'effort de chaque version ;
  - `NARRATOR2_REPLAY_DIR` désigne un dossier de situations hors du dépôt, avec leurs transcriptions ;
  - le rapport ajoute le coût par tour rejoué, le temps jusqu'à la première réplique et le temps du tour.
- `ToneJudge` : nouveau critère `clear`, rapporté à part (il n'entre pas dans le verdict).
- `LiveLineReviewEvalTest` :
  - il compte le défaut `obscure` ;
  - `NARRATOR2_LINE_REVIEW_EFFORT` choisit l'effort ;
  - le jeu passe à 62 répliques, dont les deux du propriétaire (r41, r42, avec leur contexte).
- Les quatre situations du soir n'ont pas été versionnées : elles contiennent le chat des joueurs. Ce sont 01 à 04
  (`mushroom_complaint`, `thawyn_coal`, `torch_again`, `harassment_prayer`), tirées des tours 4, 38, 39 et 47 des
  transcriptions, avec `prompt_sha` `1e5b371f7c78`, celui de la soirée. Pour les rejouer :
  1. copier les deux `.jsonl` dans un dossier `transcripts/` ;
  2. y écrire les fiches JSON, au format de `src/test/resources/narrator2/replay/18-lisette-return.json` ;
  3. pointer `NARRATOR2_REPLAY_DIR` sur ce dossier.

## Essai réel (LLMGW, 22/10/2026)

A = l'ancien `director.md` (celui du serveur) en `medium`. B = le nouveau en `high`. Les tours réels de la soirée
servent de A#0, et le juge est Opus.

| | A, ancien prompt, medium | B, nouveau prompt, high |
|---|---|---|
| répliques claires selon le juge | 55 % (11 répliques) | 80 % puis **100 %** après l'ajout « même si… » (5 puis 6 répliques) |
| jargon (dossier, crayon, formulaire, greffe, registre) | 6 tours sur 8 | 1 sur 4, puis 0 sur 4 |
| 1re réplique d'un tour, moyenne | 6,3 s (4 tours rejoués) | 6,2 s (8 tours) |
| tour complet, moyenne | 7,3 s | 7,5 s |
| tokens de sortie par tour (mêmes situations) | 413 | 441 (+7 %) |
| coût d'un tour, cache chaud | ≈ 0,06 $ | ≈ 0,06 $ |

Pour comparaison, les tours réels de la soirée (`medium`) ont mis 3,8 à 5,3 s par requête.
**`high` ne ralentit pas le Directeur de façon mesurable** : on reste loin du ×2 et des 20 s.

Relecture Haiku sur les 62 répliques :

| | low | medium |
|---|---|---|
| p50, p95 et max | 1 787, 2 272 et 1 délai dépassé (3 s) | 1 857, 2 272 et 2 985 ms |
| registre juste | 74 % | 82 % |
| fausses alertes | 2 sur 40 | 1 sur 40 |
| « céleste » trouvé | 8 sur 11 | 10 sur 11 |
| r41 et r42 en obscur | 0,60 et 0,70 | 0,60 et 0,60 |

**Coût total de l'essai : environ 2,45 $** (rejeu 1,73 $, second rejeu 0,68 $, relecture moins de 0,05 $). La
relecture coûte environ 0,0001 $ par réplique quand le cache est chaud.

### Répliques avant (soirée réelle et A)

- « Plainte enregistrée, Clowew : trois champignons de charité t'ont été versés d'en haut. clemsix6, le ministère note
  ton avarice fongique dans ton dossier, au crayon pour l'instant. »
- « clemsix6, deux pichenettes sur Clowew. Le dossier a encore des pages blanches, ne les remplis pas. »
- « clemsix6, deux gifles sur Clowew en une minute : ton dossier ouvre un troisième volet, « percussions ». Encore une
  et la cour te trouve un instrument à ta taille. »

### Répliques après (B, `high`)

- « Le tribunal céleste est saisi : clemsix6 refuse de partager quatre-vingt-dix champignons. Verdict provisoire :
  Clowew reçoit un champignon divin, et clemsix6 reçoit le titre officieux de Radin des Bois. »
- « Trois minutes après mes compliments, Thawyn décroche encore une torche de Clowew. Une minute à taille réduite,
  Thawyn : un voleur de torches doit pouvoir passer sous les portes. »
- « Trois minutes après mes compliments, Thawyn décroche encore une torche de Clowew. Puisque tu les aimes tant, je te
  confisque les tiennes : seize torches, rendues dans cinq minutes. D'ici là, mine dans le noir. »
- « Prière reçue, Thawyn, mais clemsix6 est à cinq cents blocs de toi, occupé à tapoter Clowew. Si c'est du
  harcèlement, il le fait par télépathie, et ça, c'est mon rayon : je surveille. »
- « clemsix6, deux pichenettes sur Clowew en une minute : encore une et tu passes la soirée à la taille d'un lapin. »

Le verdict automatique du banc (« B FAILS : funny 1,73 → 1,40 ») porte sur un échantillon trop petit pour compter. Ce
n'était pas le but de l'essai.

## Pour la suite

- **Le serveur du propriétaire garde le gag C-4** (« le Narrateur tient un dossier sur clemsix6 »), et chaque briefing
  le remontre. Le nouveau prompt et la note de jargon devraient l'éteindre. Le plus sûr reste de le clore : par un mot
  d'opérateur au Narrateur, ou par la revue du Rêveur (`set_gag retired`).
- **Configuration du serveur** : ses rôles sont écrits en toutes lettres dans `config/narrator2/narrator2.json`, donc
  les nouveaux défauts ne s'y appliquent pas d'eux-mêmes. Il faut y mettre :
  - director, storyteller : high ;
  - analyst, scout, chronicler : medium ;
  - forger, dreamer : xhigh ;
  - armorer : high (rôle absent du fichier, il prend déjà le défaut) ;
  - `style.lineReviewEffort` : medium (clé absente, défaut medium).
- Un changement d'effort du Directeur en cours de chapitre est noté par `Chapters` (« the director effort went
  from … »).
- Les répliques des faux modèles de test ne doivent pas contenir les mots de `ToneWatch.JARGON`, sinon une note
  « obscure » de plus s'ajoute aux décisions comptées. Deux GameTests ont été retouchés pour cette raison :
  `StyleAssemblyGameTests` (« Vu le dossier… » devient « Vu les aveux… ») et `DirectorEndToEndTests`.
- Depuis M7-TITLES, la barre d'action refuse plus de 40 caractères. La réplique « clemsix6, deux pichenettes… » en
  faisait 99.
