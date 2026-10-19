# Passation M3-POST : corrections après la recette réelle de M3

Lot de correctifs issu de `M3-19.md` (constats 1 à 7, §21.2, §21.3). **En cas de contradiction avec `M3.md` ou `M3-19.md`, le code et cette passation font foi.**

| # | Point | Fait | Test qui le prouve |
|---|---|---|---|
| 1 | `<chat>` du Rêveur | chaque ligne annotée par la logique de `ExchangeLog` | `DreamInputsTest` › *the chat says whom each message addressed, as the exchange log does* |
| 2 | arguments en chaînes | décodage tolérant dans `ToolArgs`, décision `tool args_coerced` | `ToolArgsTest` (4 tests), `ToolRunnerTest` › *arguments written as JSON text run the tool…* |
| 3 | cache du prompt système | **cause côté LLMGW** (preuves ci-dessous), rien à corriger chez nous ; test de non-régression | `ChroniclerTest` › *every conversation of the Chronicler sends the same tools and system prompt, byte for byte* ; `DreamReplayTest` (6 conversations) |
| 4 | notes `coherence` en double | le magasin refuse le doublon (`Dedup.note`) | `DuckMemoryStoreTest` › *the same note on the same rows…* ; `ChroniclerToolsTest` › *the same incoherence noted again…* |
| 5 | tics imbriqués | un seul tic par imbrication | `TicsTest` › *of tics nested in one another, one is shown…* |
| 6 | canon du Directeur sans preuve | `remember` cite les lignes du tour qui portent sa citation | `MemoryToolsTest` › *the Director's canon cites the lines of its turn…* |
| 7 | promesse d'un joueur | une phrase dans `director.md` : pas de `remember`, au besoin `note_interaction` | `PromptsTest`, `PromptToolMentionsTest`, `PersonaFrozenTest` |

## 1. Le `<chat>` du Rêveur dit à qui parlait le joueur

- `ExchangeLog` expose dans son compagnon `Signal`, `signal(row, column)` (lit les six colonnes de `chat_signals`) et `annotation(pray, signal)`. Ce sont les fonctions privées d'avant, déplacées, pas recopiées : le journal d'échanges et le Rêveur écrivent la même annotation.
- `DreamInputs` lit, pour chaque message du joueur, son dernier triage (même `QUALIFY row_number()` que le journal d'échanges, filtré sur le joueur). Une ligne devient :
  - `- [10-01 20:40] e123 CHAT (between players, banter_between_players): "non je mine, débrouille toi"` ;
  - `- [10-01 20:03] e45 PRAY (addressed 0.9, respectful, request): "…"` ;
  - `- [10-01 20:01] e47 CHAT (not triaged): "mdr ok"`.
- **`CHAT_MAX` passe de 3 000 à 4 000 caractères** : l'annotation ajoute environ 35 caractères par ligne, et le bloc garde ainsi ses 30 lignes. `STEP_MAX[1]` grandit d'autant (environ 450 tokens au plus). `M3.md` §8.2 dit encore 3 000.
- `dreamer.md` : la parenthèse de `<chat>` (« sans dire à qui il parlait… », retouche de M3-19) est remplacée par l'annotation et sa lecture : « un indice, pas une vérité : un message qui ne visait pas le Narrateur ne lui répond pas ». 14 223 caractères sur 15 000.
- Le message auquel le joueur répondait n'est pas joint (autre piste de M3-19) : l'annotation suffit au cas de la recette.

## 2. Arguments sérialisés en chaînes

`ToolArgs.decode` passe d'abord sur l'arbre JSON (`coerce`) : là où le schéma attend une liste, un objet, un entier, un nombre ou un booléen et où le modèle a écrit une chaîne qui **se lit** comme une valeur de ce type, la valeur remplace la chaîne. Puis les contrôles habituels s'appliquent à la valeur : bornes, `@Items`, arguments inconnus, imbrication (objets et éléments de listes compris).

- **Écart avec la mission : les nombres et les booléens aussi.** La mission parlait des tableaux et des objets, mais tous les refus réels mêlaient les deux : `players` et `tags` en chaînes **et** `importance: "4"`, `valence: "-1"` dans le même appel (`cycle-run3.md` l. 468, `evals-e1e2e5-low.md` l. 218). Sans les entiers, ces appels restaient refusés et le tour restait perdu.
- Jamais convertis : une chaîne attendue (même si elle ressemble à du JSON), une valeur d'énumération, une chaîne qui ne se lit pas comme le type attendu (`"[\"Bob\""`, `"2.5"` pour un entier, `"null"`, `"1"` pour un booléen). Le refus reste celui d'avant, avec le type attendu et la valeur reçue : `to must be a list (got "[\"Bob\"")`.
- `Decoded.Ok.coerced` donne les chemins convertis (`players`, `enchantments[0].level`). `ToolRunner` en fait une décision **`tool args_coerced`** (nouveau `DecisionRecord.Kind.TOOL`, id `tool`), au niveau `DEBUG` : sujet = l'outil, raison = `players, valence written as JSON text, read as the value it holds (chronicler)`, avec la conversation et le tour. La ligne `tool_calls.args` garde ce que le modèle a envoyé.
- Pas de `strict: true` (décision de M2, inchangée).
- Le commentaire SQL des sortes de décisions (`V1__journal.sql`) n'a pas été retouché : c'est une migration.

## 3. Le cache d'une conversation de mémoire à la suivante : la cause est LLMGW

**Diagnostic : nos octets sont identiques ; c'est la passerelle qui place le prompt système après le premier message.**

### Rien ne varie de notre côté

- Transcriptions de la recette (passe 3, `.research/m3-live/worlds/e7-cycle-1018-225103/`) : empreinte SHA-256 du JSON de `system` et de `tools` de chaque conversation :
  - les 2 conversations du Chroniqueur : `system` 11 662 caractères et `tools` 12 656, empreintes identiques ;
  - les 5 du Rêveur : identiques (14 930 et 16 735) ;
  - les 2 chapitres du Directeur : identiques (29 104 et 16 293).
- Ni date, ni identifiant, ni `now` dans le prompt système : c'est le fichier du prompt, tel quel (`PersonaFrozenTest`). Les outils sont triés par nom (`ToolSet`), leurs schémas déterministes (`ToolSchema`). La requête n'a aucun `cache_control` (`gateway.cacheTtl = off` : LLMGW pose ses propres marqueurs, `docs/research/LLMGW.md` §2). L'en-tête `X-Session-ID` est celui du rôle.

### La preuve que le prompt système est rendu après le premier message

Les mêmes fixtures (premier message identique, octet pour octet) ont tourné dans trois passes. Premier appel de chaque lot du Chroniqueur (`evals-*.md`), en tokens :

| Fixture | `low`, passe 1 : lu / écrit | `medium` (même prompt) : lu / écrit | `low` après retouche de `chronicler.md` : lu / écrit |
|---|---|---|---|
| `e1e2-evening-a` | 5 185 / 6 698 | 7 183 / 4 700 | 7 183 / 4 747 |
| `e1e2-evening-b` | 5 185 / 6 085 | 6 570 / 4 700 | 6 570 / 4 747 |
| `e5-*` | 5 185 / 5 307 | — | 5 792 / 4 747 |

- 5 185 est constant : c'est le préfixe que la passerelle met en cache entre conversations (les outils du rôle et son propre bloc).
- Dans les passes suivantes, la partie relue en plus (7 183 − 5 185 = 1 998 ; 6 570 − 5 185 = 1 385 ; 5 792 − 5 185 = 607) est exactement **le premier message** de chaque fixture (6 698 − 4 700 = 1 998 ; 6 085 − 4 700 = 1 385 ; 5 307 − 4 700 = 607), et la partie réécrite est exactement **le prompt système** (4 700 tokens, puis 4 747 après la retouche, soit + 47).
- Or l'API rend `tools`, puis `system`, puis `messages`, et un octet changé invalide tout ce qui suit. Si notre prompt était à sa place, la retouche de `chronicler.md` aurait invalidé le premier message. **Dans la requête réellement envoyée à Anthropic, notre prompt système est donc après le premier message.**
- La colonne `medium` va dans le même sens : à prompt inchangé, un changement d'effort, qui invalide le cache des messages mais pas celui de `system`, a fait réécrire les 4 700 tokens du prompt. Il est donc rendu parmi les messages.
- C'est cohérent avec ce que `docs/research/LLMGW.md` a observé : la passerelle (le *cloaking* de CLIProxyAPI pour un compte d'abonnement) réécrit nos requêtes, insère son bloc avant notre texte et pose elle-même ses marqueurs. Elle déplace aussi notre prompt.
- Second indice : un même premier message déjà vu, avec le même prompt (`e5-sleep` après la petite passe), est relu en entier (10 548 lus, 0 écrit).

### Ce qu'on ne peut pas faire chez nous

- Poser notre propre marqueur à la fin du prompt système : la passerelle en pose déjà, et un marqueur de plus est refusé dès le deuxième tour (400 « A maximum of 4 blocks with cache_control », `LLMGW.md`). De toute façon, le prompt est déplacé.
- Mettre le prompt dans le premier message : contraire à l'architecture (le prompt système est figé et seul, `PersonaFrozenTest`), et sans marqueur à sa fin, rien ne serait relu non plus.

### À faire côté LLMGW (je n'y ai pas touché)

Laisser le prompt système du client dans `system`, juste après le bloc de la passerelle, avec un marqueur `cache_control` à sa fin (ou poser ce marqueur à la fin du bloc déplacé). Gain estimé par M3-19 : environ 0,1 $ par chapitre du Directeur et 0,24 $ par rêve à deux joueurs.

### Le test de non-régression

- `RecordedRequest.raw(name)` (faux serveur) rend le texte exact d'un champ du corps, pour comparer des octets et pas des arbres JSON.
- `ChroniclerTest` : un lot, puis la sieste (son dernier lot et sa consolidation), soit trois conversations ; `tools` et `system` identiques octet pour octet, même `X-Session-ID`, aucun `cache_control`.
- `DreamReplayTest` : la même vérification sur les six conversations d'un rêve.

## 4. Notes `coherence` en double

- N-12 a été écrite par le Rêveur **à l'étape 1** (Mimosa, `dreamer-d43005d9`), pas à l'étape du Grimoire comme le disait M3-19. L'étape 1 ne voit pas `<notes>` : une consigne de prompt n'y pouvait rien. Le dédoublonnage est donc au magasin, qui sert tous les appelants.
- `Dedup.note` : une note pas encore `done`, de même sorte, sur le même ensemble de joueurs et le même ensemble de lignes citées (non vide). Une note sans `refs` (mot d'un opérateur, piste de rêve) n'est jamais un doublon. Une note `shown` reste ouverte : le doublon est refusé tant que l'hygiène ne l'a pas close (après `NOTE_SHOWINGS` affichages) ; ensuite, la même contradiction se note à nouveau.
- **Changement de signature** : `MemoryStore.addNote` rend `Added<Note>`, comme `addCanon`. Le doublon laisse une décision `memory duplicate` (`DEBUG`). Appelants mis à jour : `note_to_director`, `dream_hint`, `/narrator memory note`, l'hygiène, `DisabledStore`, `FakeMemory`.
- `note_to_director` sur un doublon : `ok: already noted as N-8 for the Narrator (the same rows, still shown to it); nothing added`, et **aucun** signal `NOTE` n'est envoyé au Directeur.

## 5. Tics imbriqués

- Règle : de deux tics imbriqués (une amorce dans une tournure, une tournure dans une plus longue), un seul est montré : celui des lignes les plus nombreuses, et le plus long à compte égal.
- Ce n'est pas toujours « le plus long » : quand le plus court est plus fréquent, l'éviter évite aussi le plus long, et il couvre plus de lignes. Sur le cas de la recette, il reste **« Ministère de l'Aviation Divine » ×5** à la place des trois.
- L'ancienne règle (une tournure dans une plus longue aussi fréquente est écartée) reste la première passe : sans elle, une sous-tournure d'une tournure écartée (« l'Aviation Divine guichet » ×3) reviendrait.

## 6. Le canon du Directeur a ses preuves

- `remember`, dans un tour du Directeur et avec une citation, ajoute à `evidence_events` les lignes qui contiennent cette citation (comparaison normalisée, `Quotes.saidIn`) parmi :
  - les lignes du Narrateur dites dans ce tour (`chat.turn_id`, canal `narrator` : `say`, titre, livre) ;
  - les lignes du Narrateur ou d'un opérateur en son nom (canal `operator`) parmi les événements qui ont réveillé le tour (`turns.trigger_events`).
- Les trois entrées de la recette auraient eu leur preuve : C-2 et C-4 citent la réplique `say` du même tour, C-5 la ligne de l'opérateur du réveil (« YOU SAID (through operator Clement) »).
- **Sans citation, rien** : aucune autre ligne ne dit à coup sûr de quoi parle l'entrée. Les lignes des joueurs ne sont jamais prises.
- Limites, comme pour `note_interaction` (M3-FIX-B) : une ligne dite plus loin dans la même réponse, ou injectée entre deux appels (`injected_events`, écrits en fin de tour), n'est pas trouvée.
- Ces preuves ne servent qu'à l'audit (`/narrator memory`) : `Dedup.canon` ne les lit pas. Le log `DEBUG` dit `C-5 cites e910 of turn 42`.

## 7. `director.md` : la promesse d'un joueur

- Dans la recette, le Directeur a écrit les trois diamants promis par Mimosa en `claim` : C-4 en passe 3, C-3 en passe 1 (après un refus de `due_in_minutes`, qu'un `claim` n'accepte pas).
- **Décision : le design range la promesse d'un joueur dans ses interactions, pas au canon.**
  - `claim` est une affirmation **du Narrateur** qu'il devra assumer (description de `remember`).
  - `chronicler.md` le dit déjà : « La promesse d'un joueur au Narrateur (trois diamants en offrande) est une interaction du joueur, pas du canon. »
  - Les sortes d'interaction ont ce qu'il faut (`offering`, `request`, `bet`), et la promesse reste sur la fiche du joueur.
  - Ce que le Narrateur promet en retour est sa propre promesse (`remember` en `promise`).
- Phrase ajoutée au point `note_interaction` de « Ce que tu notes », et rien d'autre : « La promesse qu'un joueur te fait (trois diamants demain) n'engage que lui : pas de `remember`, au besoin `note_interaction` avec ses mots. » 27 874 caractères sur 30 000.

## Passe réelle (`LiveMemoryCycleTest`, une fois, 19/10/2026)

Par le tunnel partagé ouvert par le superviseur (ni ouvert ni fermé par moi). **Coût : 1,56 $** (28 appels à 1,49 $, plus le juge à 0,07 $). Rapport et transcriptions copiés dans **`C:\Users\Clément\Desktop\Narrator2\.research\m3-post-live\`** (`cycle.md`, `costs.tsv`, `transcripts/`). La clé n'apparaît dans aucun de ces fichiers (recherche exacte).

**Résultat : 29 vérifications sur 30 passent ; E4 échoue à 18/20 = 0,90 (seuil 0,95).**

- **E4, les deux refus** visent les deux « depuis la dernière fois » écrits par la sieste, et le juge se trompe :
  - le texte de Bartok commence par « Session de 48 s le 2026-10-19 avec Mimosa », et le juge y lit une session de Mimosa ;
  - le juge reçoit le texte sans savoir de quelle fiche il vient (`sheet:Bartok:since_last`) : le texte est exact ;
  - ce n'est pas la voix, et M3-POST n'a touché ni `chronicler.md` ni la sieste.
  - Correctif conseillé, côté banc et non produit : donner au juge le nom du joueur de chaque `since_last`.
- **Point 3, cache** : comme prévu, rien ne change, puisque la cause est LLMGW.
  - Le premier appel d'une nouvelle conversation relit toujours 5 185 tokens (Chroniqueur), 6 861 (Rêveur) et 6 715 (Directeur), et écrit le reste.
  - La première conversation de chaque rôle relit 0 : le cache d'une heure de la veille a expiré.
- **Point 2, arguments en chaînes** : le modèle n'en a envoyé aucun dans cette passe.
  - La tolérance n'a donc pas été exercée en réel : aucune décision `tool args_coerced`.
  - Aucun refus pour cette raison non plus.
  - Les deux seuls refus de la passe sont un tag inventé (`gag:reclamation-bartok`) et un « ton » hors citation dans la revue.
- **Point 6, vérifié en réel** (base de la passe) :
  - C-3, la promesse des bottes dite par l'opérateur, a pour preuve `[338]`, la ligne `operator` du réveil du tour 4 ;
  - C-12, le défi lancé par `say` dans le tour 5, a pour preuve `[343]`.
- **Point 7, vérifié en réel** : la promesse des trois diamants de Mimosa est devenue I-2 (`note_interaction`, prière, « a promis trois diamants… »), et non un `claim`.
- **Point 1** : le `<chat>` du Rêveur montre `CHAT (addressed 0.95, hostile, provocation)` pour l'insulte et `PRAY (addressed 0.97, respectful, request)` pour la prière.
  - L'historique placé dans le passé par le banc n'a pas de triage : « non je mine, débrouille toi » y est `(not triaged)`.
  - Le Rêveur l'a écarté de lui-même (« an ambiguous chat line that isn't clearly addressed to anyone ») et n'a fondé `defiant 2/5` (confiance 0,35) que sur l'insulte.
- **Point 4** : une seule note `coherence` (N-11, du Rêveur, sur C-3). La sieste n'en a pas écrit cette fois : il n'y avait pas de doublon à refuser.

## Vérifications

- `git merge main` (le design de M4, docs seulement) : sans conflit.
- `.\gradlew.bat build` sur l'état final : **vert**, avec **1 154 tests unitaires** (0 échec, 0 ignoré ; 11 de plus que M3-19) et **172 GameTests** (`All 172 required tests passed`).
- `PromptsTest` (plafonds), `PromptToolMentionsTest` et `PersonaFrozenTest` sont verts :
  - `director.md` fait 27 874 caractères sur 30 000 ;
  - `dreamer.md` fait 14 223 caractères sur 15 000.
- Aucun appel réel dans les tests automatisés. Aucun serveur de jeu lancé hors des GameTests. Je n'ai pas touché à `docs/design/M4.md`.
