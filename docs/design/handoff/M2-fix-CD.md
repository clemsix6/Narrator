# Passation M2-fix-CD : commandes et vie privée

Branche `worktree-agent-a2a785478759b2ad5`. Ce lot corrige les constats **7, 8 et 15** de la relecture de M2 (`docs/design/reviews/M2_REVIEW.md`), selon les décisions du superviseur, et le masquage de l'événement `command` de `/narrator sql` que l'addendum (constat 20) laisse au lot CD. **En cas de contradiction, le code fait foi**, et ce fichier explique pourquoi.

## Ce qui a changé

| Fichier | Changement |
|---|---|
| `guard/CommandPolicy.kt` | `particle` et `playsound` interdites (`Reason.TOOL`) ; `random roll` interdite (`Reason.SPEECH`) ; `FORBIDDEN_EXECUTE` devient `FORBIDDEN_BRANCHES`, chemins complets depuis la racine |
| `tool/command/CommandTools.kt` | description de `run_command` |
| `observer/CommandPrivacy.kt` | **nouveau** : ce que le Journal garde d'une commande tapée, par l'analyse du dispatcher |
| `observer/ObserverHooks.kt` | `onCommand` passe par `CommandPrivacy` |
| `event/GameEvent.kt` | `CommandIssued.TEXT_KEPT_ROOTS` (liste blanche) ; KDoc de `text` et de `PRIVATE_MESSAGE_COMMANDS` |
| `config/NarratorConfig.kt` | `observer.privateMessageCommands` (nouveau) et sa validation ; `narrator` ajouté aux défauts de `observer.redactedCommands` |
| `resources/narrator2/sql/views.sql` | `chat_log.speaker`, `player` NULL pour le Narrateur (dans `chat_log` et `timeline`) ; deux commentaires raccourcis |
| `journal/SchemaDoc.kt` | l'exemple de `chat_log` lit `speaker` |
| `docs/design/M1_M2.md` | §5 (`CommandIssued`, `chat_log`), §7.2 (mixin 10), §10.6 (listes de commandes), §15 (réglages) |

Tests : `CommandPolicyTest`, `CommandPrivacyTest` (nouveau), `SqlViewsTest`, `SchemaDocTest`, `ConfigValidationTest` ; GameTests `GuardPolicyGameTests`, `CommandToolGameTests`, `ObserverMixinGameTests` (un test de plus).

## Constat 7 : `particle`, `playsound`, `random roll`

- `particle` et `playsound` quittent `CommandPolicy.ALLOWED` pour `FORBIDDEN`, avec `Reason.TOOL` :
  ``DENIED (forbidden_command): `particle` is forbidden: a tool with its own limits does it; use the particles tool instead.``
  (`playsound` → `use the play_sound tool instead`). `stopsound` reste permise.
- `random roll` est une **branche** interdite : `random` reste permise, pour `random value`, qui ne répond qu'à la source.
  ``DENIED (forbidden_command): `random roll` is forbidden: the Narrator speaks through its speech tools (say, show_title, actionbar, give_book), which are recorded; use random value (its result reaches you alone) instead.``
- Pour cela, `FORBIDDEN_EXECUTE` (chemins sous `execute`) devient **`FORBIDDEN_BRANCHES`** (chemins depuis la racine : `listOf("execute", "store")`, `listOf("random", "roll")`). Le message et la détection sont les mêmes pour toutes les branches ; `command_help random` marque `random roll … (forbidden)`.
- `safety.extraAllowedCommands` ne peut pas les rouvrir : la validation refuse une racine de la liste interdite, et la politique fait gagner la liste interdite.
- `command_help` sans commande liste maintenant 13 commandes en 26.3 sans autre mod (`compute`, `execute`, `help`, `list`, `random`, `return`, `scoreboard`, `stopsound`, `stopwatch`, `swing`, `trigger`, `version`, `waypoint`).

## Constat 8 : le texte des commandes

### La règle (`CommandPrivacy.text`)

Le texte d'une commande tapée par un joueur n'est gardé que si **chaque commande que la ligne exécute** est dans la liste blanche `CommandIssued.TEXT_KEPT_ROOTS` et n'est pas retenue par la configuration (`observer.privateMessageCommands` et `observer.redactedCommands`, sans espace de noms ni casse). Sinon, `CommandIssued.text` est `null` ; la racine, elle, est toujours journalisée.

« Chaque commande que la ligne exécute » se lit dans l'analyse du **dispatcher du serveur**, avec la source du joueur :
- la tête de chaque segment : la commande tapée, et ce que chaque `execute … run` ou `return run` exécute ;
- chaque commande où atterrit une redirection (`tell` → `msg`, `tm` → `teammsg`, un alias d'un autre mod) ;
- une redirection vers le milieu d'une autre commande (un alias qui vise un nœud d'argument) est retenue d'office : on ne cherche pas à qui appartient ce nœud.

Une ligne qui ne s'analyse pas **jusqu'au bout** (erreur, reste non lu, commande invisible pour ce joueur) est retenue : ce qui suit l'erreur n'a pas été analysé, et la ligne n'exécute rien. Une racine hors liste blanche n'est jamais analysée : l'analyse ne coûte que pour les commandes vanilla.

### La liste blanche (`TEXT_KEPT_ROOTS`)

Les commandes vanilla de 26.3 et `/pray` (la prière est aussi une ligne de `chat`). En sont exclues :
- les messages privés : `msg`, `tell`, `w`, `teammsg`, `tm` ;
- les textes montrés à des joueurs choisis : `tellraw`, `title`, `dialog`, `bossbar`, et les raisons de `kick`, `ban` et `ban-ip` ;
- les commandes de développement (`test`, `raid`…) ;
- `narrator` : les consignes, répliques et requêtes des opérateurs ;
- **toute commande d'un autre mod** : elle ne garde que sa racine.

Un test vérifie qu'aucune commande de message privé (vanilla ou de la configuration) n'y figure.

### Configuration

```json
"observer": {
  "redactedCommands": ["login", "l", "register", "reg", "changepassword", "narrator"],
  "privateMessageCommands": ["msg", "tell", "w", "teammsg", "tm", "r", "reply", "whisper", "pm", "dm", "mail"]
}
```

- `privateMessageCommands` : racines en minuscules, validées comme `redactedCommands`. Elles sont retenues partout dans la ligne, même derrière `execute … run`.
- Les deux listes ne font que **retenir davantage** : une commande hors de `TEXT_KEPT_ROOTS` ne garde jamais son texte. Retirer `msg` ou `narrator` de la configuration ne les rend donc pas visibles.
- Un `narrator2.json` existant garde sa propre liste `redactedCommands` (fusion clé par clé), sans `narrator` : c'est sans effet, puisque `narrator` n'est pas dans la liste blanche.

### Ce qui ne change pas

- Le `ChatMessage(WHISPER)` (table `private_messages`, pour les opérateurs) n'est publié que pour une ligne dont la **racine** est un message privé vanilla, comme avant.
- Le texte gardé est la ligne telle que tapée, sans `/`.

## Constat 15 : `chat_log.speaker`

- `chat_log(id, ts, channel, speaker, player, recipients, text)` :
  - `speaker` vaut `narrator` (canal `narrator`), `operator` (canal `operator`, `/narrator say`) ou `player` (chat public et prières) ;
  - `player` est **NULL** pour les répliques du Narrateur. Un joueur nommé `NARRATOR` n'est plus confondu avec lui : `WHERE player = 'NARRATOR'` ne rend que ses lignes à lui, et `recent('narrator')` sa propre chronologie.
- `timeline` : `player` est NULL pour les lignes `chat:narrator` et `chat:operator` (commentaire de colonne).
- **`SchemaDoc`** : l'exemple de `chat_log` lit `speaker`. Pour tenir le budget, deux commentaires de vues sont raccourcis (`chat_log` : « Chat, prayers… » au lieu de « Public chat, prayers… » ; `player_stats` : sans `custom:jump`). **Mesure : 7 476 caractères, soit ≈ 2 492 tokens sur 2 500. Il reste 24 caractères de marge** (38 avant ce lot). M3 (§13) prévoit de sortir les types d'événements de la description.
- **Prompts** : aucun ne cite `player = 'NARRATOR'` (recherche dans `resources/narrator2/prompts/`), rien à changer.
- `director/Briefing.kt` lit `chat_log` par colonne et distingue le Narrateur par `channel` : rien à changer.
- `trace/TraceQueries.kt` (T21) garde un `coalesce(c.player, 'NARRATOR')` sur la table `chat`, pour l'affichage aux opérateurs de `/narrator why` : ce n'est pas lisible par le modèle, et ce fichier n'est pas à ce lot.

## Limites

1. **`engine_decisions` garde encore des textes d'opérateurs**, lisibles par le modèle :
   - la décision `operator whispered` de `/narrator whisper` (`detail.text`, `command/DirectorCommands.kt`, T21). La consigne arrive de toute façon au modèle, mais elle reste relisable par `sql` dans les chapitres suivants ;
   - la requête de `/narrator sql` : c'est le constat 20, confié au lot H.

   Le lot CD ne masque que l'événement `command`. À trancher par le superviseur pour `whispered`.
2. **Un message privé derrière `execute`** (`execute as X run msg Y …`) n'est plus journalisé nulle part avec son texte : pas de `ChatMessage(WHISPER)`, donc rien dans `private_messages` pour les opérateurs. Seul `execute` apparaît, sans texte. Publier ce message demanderait d'évaluer les sélecteurs dans le contexte d'`execute`.
3. **Branches ajoutées par un mod sous une commande vanilla** : un mod qui ajouterait un message privé sous `execute` (ou sous une autre racine de la liste blanche) sans redirection ne serait pas reconnu. Un alias réalisé par `dispatcher.execute(…)` dans une commande d'un autre mod est sans risque ici, puisque cette commande ne garde jamais son texte.
4. **Texte libre dans une commande gardée** : les pages d'un livre écrit donné par `give`, un `CustomName` posé par `data`, `say` et `me` (publics) sont gardés. Ce ne sont pas des messages entre joueurs.
5. Les commandes des autres mods (`/home`, `/tpa Bartok`…) ne gardent plus que leur racine. Si le Narrateur en a besoin un jour, une liste blanche réglable (`observer.keptCommands`) pourrait s'ajouter, validée contre `privateMessageCommands`.

## Pour les autres lots

- **Lot H** : l'événement `command` de `/narrator sql` et de `/narrator whisper` n'a plus de texte (ce lot). Reste la décision `operator` (constat 20).
- **Lot I** (`oracle/*Text*`) : **bogue trouvé en passant**. `OracleText.distance(dx, dy, dz)` calcule `dx * dx + dy * dy + dz * dz` en `Int` : au-delà d'environ 46 000 blocs entre deux joueurs d'une même dimension, la somme déborde, `sqrt` rend NaN et `roundToInt()` lève `IllegalArgumentException`. `list_players` échoue alors. Un GameTest de ce lot l'a déclenché (des joueurs au point d'apparition, loin des structures de test) et a fait planter le serveur de test ; mes joueurs de test se placent maintenant dans leur zone. Correctif : calculer en `Long` ou en `Double`.
- **T22 et M3** : `chat_log.speaker` existe ; M3-10 peut le lire. Le masquage de `narrator` est fait.
- **GameTests** : sur le serveur de test, un `op` simple donne le niveau de **tous les joueurs** (`operatorUserPermissions()` vaut `ALL`). Pour qu'un joueur puisse taper `execute`, il faut `op(nameAndId, Optional.of(LevelBasedPermissionSet.GAMEMASTER), Optional.empty())`. Un message privé non signé part en `ClientboundDisguisedChatPacket`, pas en `ClientboundPlayerChatPacket`.

## Vérifications

- `gradlew build` passe **après fusion de `main`** : 651 tests unitaires et 141 GameTests, sans échec.
- **Tests d'attaque (constat 8)** :
  - `CommandPrivacyTest`, sur un dispatcher construit comme celui du jeu, avec en plus `r` et `mail` d'un autre mod, un alias `psst` → `msg` que personne ne liste et un alias `secretly` vers les arguments de `msg`. Il couvre 27 lignes : `msg`, `tell`, `w`, `teammsg`, `tm`, `execute as X run msg Y …`, `execute run tell`, `… run w`, `… run teammsg`, `execute … run execute run tm`, `return run msg`, `Minecraft:MSG`, `r`, `reply`, `whisper`, `pm`, `dm`, `mail send`, les deux alias (seuls et derrière `execute`), une commande d'un autre mod, `login`, `narrator whisper`. Ces lignes sont publiées dans un vrai Journal, puis **chaque table et chaque vue lisible par le modèle** (tout sauf `Journal.HIDDEN_FROM_MODEL`) est fouillée avec l'accès du modèle (`CAST(r AS VARCHAR) ILIKE '%…%'`) : le secret n'est nulle part. Le test couvre aussi les commandes gardées, une ligne mal formée, la liste blanche sans message privé, et une racine jamais analysée ;
  - GameTest `aPrivateMessageReachesNothingTheModelReads`, sur le vrai serveur : un opérateur (niveau maître du jeu) envoie des messages privés par `execute as @s run msg`, `execute run tell`, `execute … run w`, `execute … run teammsg`, `execute run execute run tm`, `return run msg`, les redirections `tell`, `w`, `tm`, et les commandes de message privé d'autres mods (`r`, `reply`, `whisper`, `pm`, `dm`, `mail send`, `execute run r`), inconnues du serveur mais vues par l'Observateur. Un joueur tente `/narrator whisper` et `/narrator sql`. Le test vérifie : que les messages sont bien partis (le destinataire les reçoit) ; que seules deux commandes sans message privé gardent leur texte ; que le secret n'apparaît dans **aucune** des 33 tables et vues lisibles par le modèle ; que les deux textes gardés, eux, sont trouvés dans `events` (la recherche trouve ce qui est là) ; que les messages vanilla sont dans `private_messages` pour les opérateurs.
- **Contre-épreuves**, correctifs retirés (ancienne liste d'`ALLOWED` et pas de `random roll`, ancienne règle « racine seule », anciennes vues) :
  - tests unitaires : 11 échecs, dont `CommandPolicyTest` (2), `CommandPrivacyTest` (4 ; le secret se retrouve dans 19 lignes d'`events`), `SqlViewsTest` (3) et `SchemaDocTest` (2) ;
  - GameTests : 4 échecs, dont le test de confidentialité (les textes des messages privés sont gardés), `GuardPolicyGameTests` (particules, sons et `random roll` autorisés), et deux de `CommandToolGameTests`. Sans le correctif, `execute as @a at @s run particle minecraft:heart ~ ~ ~ 0 0 0 1 2000000000 force` a vraiment tourné par `run_command`.

  Les correctifs ont été remis ensuite, et le build repasse.
- Pas de `runServer`.
