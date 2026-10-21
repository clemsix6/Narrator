# Passation M6-FIX-9 : la fin d'un tour, le silence et le Scénariste

Lot M6-FIX-9 de `design/reviews/M6_REVIEW_3.md` (constats 1, 2, 4, 5, 7 et la part du constat 8 qui le concerne), avec
les décisions du superviseur du 21/10/2026 : **D8 oui** (on garde « Fin. », avec un garde-fou), **D9 oui** (« un début
de chapitre » sort de « Ne rien faire »), **D11 non** (pas de borne horaire aux commandes au Scénariste). Passations
lues : M6-16 et M6-08. **En cas de contradiction avec `M6.md` ou la relecture, le code et cette passation font foi.**

Branche `worktree-agent-abf1966e1c56b8a90`.

**En bref.** Un résumé de chapitre de moins de **100 caractères** n'en est plus un : le moteur le redemande **une fois**,
puis ferme le chapitre sans résumé, avec un `WARN`. `chapter_summary.md` dit que le résumé n'est jamais « Fin. ».
« Un début de chapitre » ne fait plus partie des moments où se taire. Le Scénariste n'est plus proposé quand le Journal
est tombé. Quatre retouches de prompts (`remember`, `P-41`, la limite de six requêtes, les poules en orbite), et les docs
et le KDoc périmés. `director.md` passe de **34 995 à 34 973 caractères** (marge de 1 027 sous les 36 000).

## Ce qui change

| Fichier | Changement |
|---|---|
| `director/Chapters.kt` | `MIN_SUMMARY_CHARS` = 100 ; `Closing.summary()` ignore les réponses plus courtes ; une réponse trop courte fait repartir la demande **une fois**, avec `NOT_A_SUMMARY_NOTE` ; textes de l'issue (voir plus bas) ; KDoc |
| `prompts/chapter_summary.md` | une phrase : « Ce n'est pas une fin de tour : le résumé n'est jamais « Fin. », ni une ligne, ni le silence. » (3 567 → **3 660**, budget 4 000) |
| `prompts/director.md` | D9, `remind_me` seul, « que le moteur te demande », `P-41` dans les références, une coupe en compensation (34 995 → **34 973**) |
| `prompts/storyteller.md` | la limite de six requêtes à la demande ; les poules en orbite (8 450 → **8 638**, budget 9 000) |
| `agent/Workers.kt` | le Scénariste exige le Journal **et** la mémoire ; raison `the Journal is unavailable` quand c'est le Journal qui manque ; KDoc |
| `memory/MemoryService.kt` | KDoc d'`OpenIdeas` : le cache sert la ligne `Idea:` du battement et `inspire`, pas `<programme>` |
| `docs/design/M6.md` | §6.4 (le Journal, six requêtes, coût de M6-08), §6.7 (sans `sql`, coût), §7 (le refus, le format `novelty 4 fun 4 fair 5`) |
| `docs/MEMOIRE.md` | §8, point 5 : le Scénariste à la demande exige le Journal ; ses trois idées comptent dans le plafond de 12 |

## Le garde-fou du résumé (constat 1, D8)

**Choix : redemander une fois**, plutôt que fermer tout de suite sans résumé comme le proposait la relecture. Le code le
permet en une dizaine de lignes (`ask` se rappelle avec une nouvelle demande), et un résumé perdu emporte les fils et
les engagements que le greffier n'a pas encore rattrapés, alors qu'une requête de plus sur le cache chaud coûte peu.
« Garder le précédent » était déjà fait : `Closing.summary()` prend la plus longue des réponses, et ne retient
désormais que celles d'au moins 100 caractères.

Le déroulé, quand la réponse à la demande de résumé (`TurnOutcome.Ended`) n'a aucun texte d'au moins 100 caractères :

1. `WARN` « chapter N: an answer of 4 characters is no summary (fewer than 100); asked once more », et une note dans la
   transcription ;
2. la demande repart, dans la même conversation, avec la note du moteur (en balise `<engine>`, puisqu'elle n'a pas de
   bloc) : *« "Fin." ends a turn, not a chapter: reply with the chapter summary itself, in the format asked, every
   section included, as plain text. »* Ses appels d'outils sont refusés comme ceux de la première demande ;
3. la décision `chapter closed` dit ce qui s'est passé :
   - `…; summary of 1823 characters, asked again after an answer of 4 characters` (`INFO` pour une fermeture usuelle) ;
   - `…; no summary: the longest answer has 4 characters, fewer than 100, even asked again` (`WARN`, `summary_chars` 0) ;
   - `…; no summary: no usable answer (refused (cyber)), asked again after an answer of 4 characters` (`WARN`).

Jamais une troisième fois. Une erreur de passerelle qui mérite d'attendre pendant la seconde demande garde le chapitre
ouvert, comme avant : `resumeSummary` renvoie la seconde demande, pas la première. Une fermeture forcée (`close`,
l'arrêt du serveur) pendant l'attente ne garde une réponse que si c'est un résumé. Un chapitre fermé sans résumé suit le
chemin habituel : `<previous_chapters>` le montre sans texte et la mémoire en est prévenue (`Memory.chapterClosed`).

**Pourquoi 100.** Le format en impose sept sections, même vides (« aucun ») : 180 caractères au minimum. Une ligne
seule, ou « Fin. », n'y arrive pas.

**Tests touchés hors de ma fiche** (le garde-fou rend trop courts leurs faux résumés) : `DirectorScenariosTest` (deux
tests), `MemoryScenariosTest` et `PersonaFrozenTest` répondaient « Joueurs vus : Bartok. » (21 caractères) ; ils
répondent désormais un résumé aux sept sections vides (`EMPTY_SUMMARY`, une constante privée du fichier). Les GameTests
`DirectorEndToEndTests`, `ForgeEndToEndTests`, `MechanicsEndToEndTests` et `StyleGameTests` ont un `SUMMARY` allongé
(même début, trois sections de plus), et `MemoryEndToEndTests` un `EMPTY_SUMMARY`. Rien d'autre n'y change. **Tout faux
serveur qui répond à `<chapter_end` doit rendre au moins 100 caractères**, sinon une seconde demande part, sans
`<chapter_end` dans le message.

## Les prompts

### `director.md` : 34 995 → 34 973 caractères

Comptés en unités UTF-16, comme `PromptsTest`. Lignes 1 à 54 (la voix) inchangées.

| # | Où | Changement | Écart |
|---|---|---|---|
| D9 | « Ne rien faire » | « un joueur inactif, un début de chapitre : laisse passer » → « un joueur inactif : laisse passer » | − 22 |
| 5 | « La fin d'un tour » | « passe par `remind_me` ou `remember` » → « passe par `remind_me` » : `remember` écrit au canon un engagement pris devant les joueurs, pas une intention | − 14 |
| 1 | « La fin d'un tour » | « le résumé qu'il te demande » → « le résumé que le moteur te demande » (le « il » renvoyait de loin au moteur) | + 8 |
| 5 | « Ta mémoire » | les références gagnent « P-41 une idée de ton programme, qu'`inspire` relit en entier » | + 62 |
| coupe | « Déléguer » | le troisième exemple de question pour l'Analyste, « que s'est-il passé entre Josiane et Bernard hier ? », retiré : deux exemples suffisent | − 56 |
| | | **Total** | **− 22** |

```diff
-[…] une mort banale, un joueur inactif, un début de chapitre : laisse passer ; […]
+[…] une mort banale, un joueur inactif : laisse passer ; […]
-[…] Une intention pour plus tard passe par `remind_me` ou `remember`. Seule exception : le résumé qu'il te demande quand un chapitre se ferme.
+[…] Une intention pour plus tard passe par `remind_me`. Seule exception : le résumé que le moteur te demande quand un chapitre se ferme.
-Ses références (C-45 au canon, M-120 un souvenir, I-512 une interaction, H-88 la chronique, N-9 une note) servent à tes outils ; […]
+Ses références (C-45 au canon, M-120 un souvenir, I-512 une interaction, H-88 la chronique, N-9 une note, P-41 une idée de ton programme, qu'`inspire` relit en entier) servent à tes outils ; […]
-Quand une question demande plusieurs requêtes ou du recoupement (« où Bernard mine-t-il depuis trois jours ? », « qui ai-je le moins visé ce soir, et avec quoi ? », « que s'est-il passé entre Josiane et Bernard hier ? »), confie-la […]
+Quand une question demande plusieurs requêtes ou du recoupement (« où Bernard mine-t-il depuis trois jours ? », « qui ai-je le moins visé ce soir, et avec quoi ? »), confie-la […]
```

Le cas que visait « un début de chapitre » (la situation `empty_heartbeat` du rejeu de M6-16, une ouverture sans
matière) reste couvert par « un battement sans matière ». Je n'ai pas ajouté « jamais « Fin. » » à l'exception du résumé
dans `director.md` : la demande de résumé le dit elle-même, au plus près du moment, et le moteur redemande.

### `storyteller.md` : 8 450 → 8 638 caractères

```diff
-Évite les tics […], « Bienvenue au Grand… », les poules en orbite, et le registre qui domine déjà <history>.
+Évite les tics […], « Bienvenue au Grand… », les poules en orbite citées à tout propos (le module, lui, reste une idée comme une autre), et le registre qui domine déjà <history>.
-Trois idées pour le moment que décrit le Narrateur. Ton rapport les classe, […]
+Trois idées pour le moment que décrit le Narrateur, en six requêtes au plus : lis les fiches et tire `inspire` dans un même message, écris les trois idées dans un autre. Ton rapport les classe, […]
```

La consigne des poules proscrit la formule resservie, pas le module `orbiting_chickens`, l'un des sept modules intégrés
que `<arsenal>` montre au Scénariste.

### `chapter_summary.md` : 3 567 → 3 660 caractères

```diff
-[…] et par rien d'autre (ni introduction, ni conclusion).
+[…] et par rien d'autre (ni introduction, ni conclusion). Ce n'est pas une fin de tour : le résumé n'est jamais « Fin. », ni une ligne, ni le silence.
```

## Le Scénariste et le Journal tombé (constat 4)

`Workers.roles` : `if (services.journal.available && services.memory.available) add(Role.STORYTELLER)`.
`MemoryService.available` vaut toujours vrai, alors que `DuckJournal.available` tombe quand l'écrivain échoue. Une
commande ne pourrait alors plus rien écrire (`propose_idea` passe par le Journal) : elle n'est plus proposée. Le refus
de `delegate` dit `no storyteller can take a task: the Journal is unavailable` quand c'est le Journal qui manque (même
quand la mémoire manque aussi : c'est la cause première), `… the memory is unavailable` sinon. Le chapitre suivant ne
montre plus `inspire` (`storytellerAvailable` suit `roles`). Le chapitre en cours garde sa liste d'outils, comme
toujours : `delegate` y refuse le Scénariste avec sa raison. Le Scénariste revient dès que le Journal revient.

## Vérifications

- **Tests nouveaux** :
  - `ChapterScenariosTest` (+2) : une demande répondue « Fin. » repart une fois, avec la note, dans la même session, et
    la seconde réponse est le résumé (décision `…, asked again after an answer of 4 characters`, deux appels
    `chapter_summary`) ; le scénario de la sonde de la relecture (un tour fini par « Fin. », 11 min de serveur vide, deux
    « Fin. ») ferme le chapitre **sans résumé**, `summary_chars` 0, et la décision est un `WARN`.
  - `WorkersTest` (+1) : le Journal tombe pendant que la mémoire dit tourner, le Scénariste n'est plus proposé et
    `delegate` dit `the Journal is unavailable` ; il revient avec le Journal. Le banc de test a un Journal qui peut
    tomber (`journalUp`) ; une assertion passe à `the Journal is unavailable` (Journal et mémoire absents).
  - `DirectorStorytellerScenariosTest` (+1, constat 8) : un battement qui porte `Idea: P-41` et dont la requête est
    refusée ne rend rien (`dealt` 0, aucun `recalls`) et ne lance pas le repos de 2 h : le battement suivant, trois
    minutes plus tard, porte la même idée, et sa requête acceptée la rend.
- **Tests ciblés** (les quatre ci-dessus, `DirectorScenariosTest`, `MemoryScenariosTest`, `PersonaFrozenTest`,
  `PromptsTest`, `PromptToolMentionsTest`, `DirectorResilienceTest`, `TestMethodsReturnUnitTest`) : 111 verts.
- **Build complet** (`.\gradlew.bat build --continue`) après `git merge main` (`0631ef5` : FLAKY-6), sans conflit :
  **2 249 tests unitaires, 0 échec, 0 ignoré** (`TestMethodsReturnUnitTest` compris) ; **« All 398 required tests
  passed »** (GameTests), en 6 min 25 s. Aucun test instable rencontré.
- Aucun appel réel à Claude (pas de vérification réelle demandée). Journaux de build sous des noms `m6-fix-9-*`.

## Points à faire relire par le propriétaire

1. **« Ne rien faire »** sans « un début de chapitre » (D9).
2. **La coupe** du troisième exemple de « Déléguer » (« que s'est-il passé entre Josiane et Bernard hier ? »), qui
   compense les ajouts.
3. **Les poules en orbite** chez le Scénariste : « citées à tout propos (le module, lui, reste une idée comme une
   autre) ».
4. **`chapter_summary.md`** : « Ce n'est pas une fin de tour : le résumé n'est jamais « Fin. », ni une ligne, ni le
   silence. »

## Ce que les tâches suivantes doivent savoir

- **M6-18** (T6, une vraie soirée) :
  - **la fin de tour** : les tours finissent toujours par « Fin. » (`tails.py` de l'archive de M6-16) ;
  - **le résumé** : compter les décisions `chapter closed` qui contiennent `asked again after an answer of` (le
    garde-fou a servi et rattrapé) ou `even asked again` (perdu), et les lignes `WARN` « is no summary ». Attendu : zéro,
    ou presque ; plus d'un chapitre sur dix rattrapé dirait que la consigne « Fin. » déborde sur le résumé ;
  - **le silence** : la part des ouvertures de chapitre sur une arrivée (JOIN avec SHEET) qui restent muettes. Attendu :
    faible, maintenant que « un début de chapitre » n'y invite plus ; et la part de battements silencieux, qui doit
    rester la plus fréquente ;
  - **`remember`** : aucune intention jamais annoncée aux joueurs écrite au canon (un DUE sur une menace que personne
    n'a entendue) ;
  - **le Scénariste** (T5, D11) : le nombre de commandes par soirée ; qu'une commande finisse avec son rapport classé
    (aucune en `step limit` : lectures groupées, puis les trois idées) ; qu'un programme propose à l'occasion le module
    `orbiting_chickens`.
- **Tout faux serveur** qui scénarise une fermeture de chapitre doit répondre un résumé d'au moins 100 caractères (voir
  plus haut). `Chapters.MIN_SUMMARY_CHARS` et `Chapters.NOT_A_SUMMARY_NOTE` sont publics pour les tests.
- **`director.md`** est à 34 973 : toute retouche future doit encore retirer ce qu'elle ajoute.
