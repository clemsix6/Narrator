# Passation M6-FIX-15 : le Forgeron compte ses messages

Dernière tâche de M6 : la décision 2 du superviseur sur M6-FIX-14. Le Forgeron avait le défaut du Scénariste : `forger.md`
comptait en « requêtes », une unité que le modèle ne voit jamais, et rien ne lui disait où il en était. Aucun cas réel
n'avait été observé. Passation lue : M6-FIX-14, avec ses « Décisions du superviseur ». **`forger.md` change de trois mots,
sans changer de taille.** Aucun appel réel.

Branche `worktree-agent-a1194406ce353333a`.

## Ce qui change

**Le compteur** (`agent/Workers.kt`) : `TurnLimits(steps, countdown = storyteller || forger)`. C'est le même compteur
qu'en M6-FIX-14 (`TurnLimits.countdown`, `AgentLoop.countdownNote`), au même endroit, sans nouveau code. Chaque requête
qui rend au Forgeron les résultats de ses outils porte la note du moteur, par exemple avec la limite par défaut :

> Engine: 36 of your 40 messages used (a message counts once, whatever its tool calls): 4 left, the report included.

puis, quand le message suivant est le dernier :

> Engine: 39 of your 40 messages used: the next one is your report, without tool calls (they would not run).

**Le compte suit exactement la limite du moteur.** Pour le Forgeron, `steps` vaut `forge.taskSteps` (40 par défaut, de 10
à 60), et non `workers.maxSteps`. C'est la même valeur qui devient `TurnLimits.maxSteps`, la limite au-delà de laquelle
`AgentLoop` n'exécute plus ses appels, et la note la lit dans `limits.maxSteps` : elles ne peuvent pas diverger.

**Les réparations n'ont pas de limite propre.** Une réparation (la version suivante d'un module cassé, que le Directeur
commande quand un module est désactivé) est une tâche `forge_module` comme une autre : `Workers.submit(Role.FORGER, …)`,
le même `forge.taskSteps` et le même `forge.taskMinutes`. Aucun autre chemin du code ne fait travailler le Forgeron. Le
compteur ne dit rien de la durée (`forge.taskMinutes`, 20 min) : seule la limite d'étapes est comptée, comme pour le
Scénariste.

**Le prompt** (`forger.md`) compte en messages partout où il comptait en requêtes :

- « Tu as un seul tour, avec un nombre de **messages** et une durée limités » ;
- « un module se fait d'ordinaire en cinq à quinze **messages** » ;
- « s'il te reste moins de cinq **messages**, arrête-toi ».

**7 622 caractères, inchangé** (« requêtes » et « messages » ont huit lettres ; budget de 9 000 dans `PromptsTest`). Avec
le compteur, « moins de cinq » se lit tel quel : à « 4 left, the report included », il lui reste un `forge_save` et son
rapport, et de la marge. Je n'ai ajouté aucune phrase sur le compteur : la note se suffit, comme pour le Scénariste.

## Les autres rôles

J'ai cherché « requête », « étape », « appels », « au plus » et « économe » dans tous les prompts. **Aucun autre rôle ne
compte en requêtes** ; aucun n'a été corrigé.

| Rôle | Ce que dit le prompt | Par `Workers` ? | Même cas ? |
|---|---|---|---|
| Analyste (`analyst.md` l. 9 et 26) | « un nombre d'étapes et une durée limités » ; « la plupart des questions se règlent en deux à cinq appels. Si les étapes ou le temps viennent à manquer, rapporte ce qui est vérifié » | oui (`workers.maxSteps`, 15) | **non** : aucun nombre d'étapes ni seuil d'arrêt ; « appels » est une consigne d'économie dans une unité qu'il voit |
| Éclaireur (`scout.md` l. 9 et 31) | les mêmes phrases, « trois à six appels » | oui (`workers.maxSteps`, 15) | **non**, pour la même raison |
| Rêveur (`dreamer.md` l. 9) | « un nombre d'étapes et une durée limités », sans nombre | non (`Dreamer`, `TurnLimits(steps)`) | non |
| Chroniqueur (`chronicler.md` l. 9) | « un nombre d'étapes et une durée limités », sans nombre | non (`Chronicler`, 12 et 16 étapes) | non |
| Directeur (`director.md` l. 103) | « Le nombre d'étapes d'un tour est limité : au-delà, tes appels ne sont plus exécutés », sans nombre | non (`Director`) | non : la note de limite d'étapes le lui dit |
| Scénariste, étape 5 du rêve | aucun budget (déjà relevé par M6-FIX-14) | non (`TurnLimits(10)`) | non |

Les autres « requête » des prompts ne sont pas un budget : une requête SQL (`analyst.md`, `director.md`, l'outil `sql`),
et « Chaque requête contient une seule réplique » (`line_review.md`, `reflex.md`), des classements en un seul appel.

**Une seule remarque** : l'Analyste et l'Éclaireur disent « si les étapes viennent à manquer » sans voir de compte. Ils
n'ont pas de seuil à respecter, et la note de limite d'étapes les arrête de toute façon. Si de vraies tâches montraient un
rapport perdu à la limite, un `countdown` pour eux serait la même ligne dans `Workers`, au prix d'une note par requête.

## Tests

| Test | Ce qu'il vérifie |
|---|---|
| `WorkersTest` « a forger task runs within forge_taskSteps requests and forge_taskMinutes, not the workers' limits, and counts its messages against them » (étendu et renommé) | avec `forge.taskSteps = 3` et `workers.maxSteps = 15` : « 1 of your 3 messages used … 2 left », puis « 2 of your 3 messages used: the next one is your report », puis la seule note de limite deux fois ; la note suit les résultats d'outils ; seuls les appels des deux premières réponses tournent (inchangé) |
| `WorkersTest` « a storyteller task sees every open idea … and an Analyst task neither them nor a countdown » | inchangé (KDoc seulement) : une tâche de l'Analyste n'a toujours pas de compteur |

**Contre-épreuve** : sans `|| forger`, le test étendu échoue sur la liste des notes ; avec, il passe.

**Aucun test existant n'a dû être adapté.** Les tests qui lisent les requêtes du Forgeron lisent déjà le dernier message
`user` : le GameTest `ForgeEndToEndTests` (`lastUser`, puis `toolResults` qui filtre les blocs `tool_result`), et
`DirectorForgeScenariosTest` ne fait pas tourner de conversation du Forgeron (il est scripté). Les autres tests du
Forgeron (`a forger task has the forger's prompt…`, le repli `fallback`, une tâche à la fois, l'interrupteur, la Forge
tombée) n'ont qu'une requête ou ne lisent pas les messages.

## Documentation

- `docs/ARCHITECTURE.md` (tableau des rôles, le Forgeron) : `forge.taskSteps` messages au plus, et le compteur.
- `docs/design/M5.md` dit encore « 40 requêtes » : c'est l'unité du moteur, toujours juste ; le code et cette passation
  font foi pour ce que voit le modèle.

## Ce que les tâches suivantes doivent savoir

- **Une requête du Forgeron qui rend des résultats d'outils finit par une note du moteur** (`role: system`, ou `<engine>`
  en mode `user_tag`) : un test qui y cherche des résultats d'outils lit le dernier message `user`.
- Chaque note coûte une trentaine de tokens, non cachés la première fois ; sur une tâche de 15 messages, c'est
  négligeable devant le préfixe du Forgeron (prompt et SDK, ≈ 38 000 caractères).
- **Rien n'a été rejoué en réel.** Les évals F1 à F4 de M5 n'ont pas été relancées ; la prochaine forge réelle dira si le
  Forgeron s'arrête à temps avec le compteur.

## Vérifications

- Tests ciblés (`WorkersTest`, `AgentLoopTest`, `PromptsTest`, `PromptToolMentionsTest`, `DirectorForgeScenariosTest`,
  `DelegationScenarioTest`, `TestMethodsReturnUnitTest`) : 77 tests, verts.
- Taille de `forger.md` comparée à `HEAD` en caractères UTF-8 : 7 622 avant, 7 622 après.
- **Build complet** (`.\gradlew.bat build --continue`) après `git merge main` (déjà à jour, `8d020d8`) : **2 297 tests
  unitaires, 0 échec, 0 ignoré** (autant qu'en M6-FIX-14 : un test étendu, aucun ajouté) ; **« All 403 required tests
  passed »** (GameTests), en 10 min 41 s. Aucun test instable rencontré.
- Aucun appel réel, aucun tunnel ouvert, aucun serveur de jeu lancé hors des GameTests.
