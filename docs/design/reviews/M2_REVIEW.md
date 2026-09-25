# Relecture de M2 : constats et décisions du superviseur

Relecture du 25/09/2026, sur `main` après la fusion de T08 à T21 et de T09c. Chaque constat est suivi de la **décision** du superviseur. Les corrections sont réparties en lots (§ Lots), qui ne partagent aucun fichier.

## Constats

1. **BLOQUANT : la lévitation tue sans être attribuée.**
   - Où : `tool/action/PlayerActionTools.kt:140` arme la garde pour `seconds + GUARD_MARGIN_SECONDS` (5 s).
   - Scénario : `apply_effect levitation seconds=300 level=2`. Le joueur monte d'environ 540 blocs, et la chute dure environ 9 s après la fin de l'effet. La garde a expiré, donc la mort n'est pas attribuée et l'inventaire tombe là où il meurt. Le niveau I suffit sur 300 s (5,7 s de chute).
   - **Décision** :
     - armer la garde pour la durée de l'effet plus au moins 30 s ;
     - à la fin d'une lévitation posée par le Narrateur, appliquer automatiquement `fall_damage_multiplier ×0` pendant 30 s via l'`AttributeLedger` ;
     - GameTest : lévitation, puis chute après l'échéance, puis mort attribuée.
2. **BLOQUANT : un `apply_effect` « bénéfique » peut raccourcir un effet vital.**
   - Où : `guard/EffectLedger.kt:30` (retire puis ajoute), `PlayerActionTools.kt:113` et `:142-148` (un bienfait ne passe ni par `punish` ni par `armDeathGuard`).
   - Scénario : 30 s de `fire_resistance` remplacent une potion de 8 min en pleine lave du Nether. Même chose pour `water_breathing` et `slow_falling`, y compris sur un joueur protégé.
   - **Décision** :
     - si le joueur a déjà cet effet, plus long ou plus fort, refuser (`INVALID: already has fire_resistance I for 7m50s`) ;
     - si l'effet est seulement plus court ou plus faible, traiter l'appel comme `remove_effect` : action hostile, garde armée 30 s.
3. **IMPORTANT : la mort dans le vide passe toujours, même quota épuisé.**
   - Où : `guard/DeathGuard.kt:83` (`|| source.is(FELL_OUT_OF_WORLD)`).
   - **Décision** : si le quota est épuisé et que la cause est le vide, refuser la mort et sauver le joueur :
     - téléportation au point de réapparition, ou au dernier sol sûr (`Teleports.landing`) ;
     - protection de chute ;
     - décision `death_guard prevented`.
4. **IMPORTANT : une monture du Narrateur disparaît sous son cavalier.**
   - Où : `guard/NarratorMobs.kt:105-112` (`discard()` sans regarder les passagers) ; `WorldActionTools.kt:576` (`Fuses.detonate` sans `release()`).
   - Scénario : un arpenteur sellé traverse un lac de lave, il expire, le cavalier meurt. Même chose avec un happy_ghast en altitude.
   - **Décision** :
     - tant qu'un joueur est dessus, repousser l'échéance, avec un plafond de 10 min ;
     - au plafond, descendre le joueur vers un atterrissage sûr, avec la garde armée et une protection de chute ;
     - `Fuses.detonate` appelle `release()`.
5. **IMPORTANT : `set_weather thunder` provoque de vrais éclairs** : feux, dégâts, pièges à cavaliers squelettes.
   - **Décision** : **retirer l'option `thunder`**. Pour l'ambiance, le Narrateur combine la pluie et l'outil `lightning`, qui est visuel. Mets à jour la description de l'outil.
6. **IMPORTANT : les créatures du Narrateur abîment les blocs posés par les joueurs** : terres cultivées piétinées, œufs de tortue, pots décorés et dripstone cassés par les flèches.
   - **Décision** : des mixins qui refusent l'action quand `CreatureShield.isNarratorsDoing` est vrai :
     - `FarmBlock.fallOn` ;
     - le piétinement dans `TurtleEggBlock` ;
     - le `RemoveBlockGoal` des zombies ;
     - `Projectile.mayInteract` et `mayBreak`.

     Chaque mixin a son GameTest et sa contre-épreuve.
7. **IMPORTANT : `particle` et `playsound` contournent les plafonds par `run_command`.**
   - Exemple : 2 milliards de particules font planter tous les clients.
   - **Décision** : interdire `particle` et `playsound` dans `CommandPolicy` avec `Reason.TOOL`, puisque des outils bornés font déjà la même chose. `random roll` : interdire aussi, car il diffuse hors des outils de parole.
8. **IMPORTANT (vie privée) : `execute … run msg|tell|w|teammsg` est journalisé en clair et lisible par `sql`.**
   - Où : `observer/ObserverHooks.kt:441-448` (le masquage ne regarde que la racine), `event/GameEvent.kt:583`, `journal/JournalMapping.kt:455`. Les commandes de message privé d'autres mods (`/r`, `/reply`, `/whisper`) fuient aussi.
   - **Décision** : le texte d'une commande est gardé **seulement pour une liste blanche de racines**, sans aucune commande de message privé.
     - Masquer si une tête de segment ou une redirection est une commande de message privé : analyse par le dispatcher.
     - Nouveau réglage `observer.privateMessageCommands`, avec comme défauts les commandes vanilla et `r`, `reply`, `whisper`, `pm`, `dm`, `mail`.
     - `narrator` est aussi masqué par défaut, pour que les consignes des opérateurs restent invisibles au modèle.
9. **IMPORTANT : la classification « hostile » de `spawn` est incomplète.**
   - Où : `WorldActionTools.kt:275` (`target || MONSTER`), `DeathGuard.kt:63-69`.
   - Scénario : poisson-globe, ours polaire, abeilles, loups, golem de fer ou chèvres ne sont pas comptés hostiles, donc ils contournent `already_punished` et `fair_share`. Les vex ne sont pas attribués. Les renforts des zombies n'ont pas de tag.
   - **Décision** :
     - hostile = tout ce qui n'est pas sur une **liste blanche de créatures inoffensives** ;
     - `DeathGuard.attributed` utilise `CreatureShield.isNarratorsDoing` ;
     - l'attribut de base `spawn_reinforcements` vaut 0 sur les zombies du Narrateur.
10. **IMPORTANT : `jump_strength` « bénéfique » jusqu'à 1,5 fait des dégâts à chaque saut.** Où : `guard/AttributeTable.kt:34`.
    - **Décision** : le sens favorable s'arrête à 0,6. Au-delà, c'est hostile.
11. **MINEUR : la garde armée devient une immortalité.**
    - Où : `PlayerActionTools.kt:344`, `DeathGuard.kt:87-88`.
    - **Décision** :
      - n'armer la garde que pour les changements nuisibles ;
      - une mort empêchée au-delà du quota ne réarme pas indéfiniment : au plus une réanimation par épisode ;
      - l'attribution exige une source plausible pour l'action armée.
12. **MINEUR : téléportation.**
    - Où : `PlayerActionTools.kt:461-465` et `:483-486`.
    - **Décision** : toute téléportation arme la garde 30 s. Les joueurs protégés ne peuvent pas être téléportés.
13. **MINEUR : aucun plafond de valeur sur les bienfaits.**
    - **Décision** :
      - Résistance plafonnée au niveau III ;
      - enchantements plafonnés au niveau maximum vanilla + 1 ;
      - paliers de valeur par joueur et par heure : une liste d'objets « précieux » (diamant, netherite, élytres, totem, pomme dorée enchantée, balise, shulker…) plafonnée à une petite quantité par heure. Le Narrateur **freine la progression**.
14. **IMPORTANT (coût) : aucune limite par joueur des réveils immédiats.**
    - Où : `command/PrayCommand.kt:49`, `director/Salience.kt`.
    - **Décision** : un budget de réveils immédiats par joueur, 6 par tranche de 10 min. Au-delà, le message est regroupé en `SOON` et la décision `salience throttled` est écrite.
15. **MINEUR : collision de noms dans `chat_log`** (`sql/views.sql:46`, `player = 'NARRATOR'`).
    - **Décision** : `player` vaut NULL pour le Narrateur, et une colonne `speaker` (`narrator`, `player`, `operator`) est ajoutée.
16. **MINEUR : duplication entre les tâches parallèles** : `punish`, `online`, `number`, `refuse`, `roman()`, trois registres par serveur en `WeakHashMap`, deux compteurs « par tour », six formats de durée.
    - **Décision** : un `tool/ToolText.kt` commun (un seul format de durée vu par le modèle) et des limiteurs portés par un `ToolLimits` par serveur. **Après** les lots A et B.
17. **MINEUR (threads) : après l'arrêt, `DirectorModule` touche `playerList` hors du thread serveur** (`director/DirectorModule.kt:66-69`).
    - **Décision** : passer par `onServerThread`.
18. **MINEUR (observabilité) : une mort non attribuée qui suit une action du Narrateur ne laisse aucune trace qui les relie.**
    - **Décision** : à chaque mort, la décision `death_guard` contient les actions du Narrateur sur la victime dans les 60 dernières secondes et l'état de la garde (`armed_until`, quota restant). Les décisions `guard allowed` passent au niveau INFO dans les traces.

## Lots
| Lot | Constats | Fichiers principaux |
|---|---|---|
| A — mort et effets | 1, 2, 3, 10, 11, 12, 13, attribution du 9 | `guard/DeathGuard.kt`, `guard/EffectLedger.kt`, `guard/AttributeTable.kt`, `tool/action/PlayerActionTools.kt`, `tool/action/Teleports.kt`, `tool/action/ItemSpecs.kt`, `PlayerActionGameTests`, `GuardGameTests` |
| B — créatures et monde | 4, 5, 6, classification et renforts du 9 | `guard/NarratorMobs.kt`, `guard/SpawnPolicy.kt`, `tool/action/WorldActionTools.kt`, nouveaux mixins dans `mixin/policy/` et `narrator2.policy.mixins.json`, `WorldActionGameTests`, `GuardGapGameTests` |
| CD — commandes et vie privée | 7, 8, 15 | `guard/CommandPolicy.kt`, `tool/command/CommandTools.kt` (description), `observer/ObserverHooks.kt`, `event/GameEvent.kt`, `config/NarratorConfig.kt` (section observer), `resources/narrator2/sql/views.sql`, leurs tests |
| EF — coût, threads, observabilité | 14, 17, 18 | `director/Salience.kt`, `director/WakeScheduler.kt`, `command/PrayCommand.kt`, `director/DirectorModule.kt`, `guard/GuardsImpl.kt` (`onDeath`), `trace/Decisions.kt` |
| G — factorisation (après A et B) | 16, plus la mise à jour de `director.md` et des passations T15 et T16 | `tool/ToolText.kt`, limiteurs |

Relectures complémentaires à relancer :
- Journal, Observateur et `SqlRunner` ;
- Directeur, `AgentLoop`, Workers et chapitres.

## Addendum : relectures du Journal et du Directeur

19. **BLOQUANT (vie privée) : l'outil `sql` peut lire les fichiers de la base**, WAL compris.
    - Où : `journal/SqlRunner.kt:201-219`. `read_blob`, `read_text`, `glob` et `current_setting` ne sont pas bloquées. Même avec `enable_external_access=false`, DuckDB autorise le fichier de la base, son `.wal` et `<db>.tmp/`. `current_setting('allowed_paths')` révèle les chemins. Un message privé a été extrait des octets bruts de `<db>.tmp/`.
    - **Décision** :
      - pour l'accès MODEL, une **liste blanche du plan** : seulement des parcours de tables et de vues visibles, plus `range`, `unnest` et ce qu'exige `columns_of`. Refuser aussi par le nom toute fonction `read_*`, `glob`, `sniff_csv`, `parquet_*`, `current_setting`, et les scans de remplacement (nom entre guillemets ou qui ressemble à un chemin) ;
      - ajouter des tests sur une **base sur disque** ;
      - corriger la KDoc et le design.
20. **IMPORTANT : fuite par `/narrator sql`.** La décision `operator` garde la requête et le message d'erreur de DuckDB, lisibles par le modèle dans `engine_decisions`.
    - **Décision** : ne jamais garder la requête ni l'erreur d'une commande `/narrator sql` dans une table lisible par le modèle. On ne garde qu'une empreinte (hash), la taille et le statut. Le masquage de l'événement `command` relève du lot CD.
21. **IMPORTANT : le Journal dégradé ne se rétablit jamais** (`JournalWriter.kt:470-478`, `DuckJournal.kt:80,97`).
    - **Décision** :
      - le Journal tente de rouvrir la base toutes les 60 s ;
      - les opérateurs en ligne sont prévenus une fois (message dans le chat), et la décision est écrite au retour ;
      - borner `max_temp_directory_size` ;
      - garantir la monotonie des identifiants après un crash (`TraceSink.kt:14-18`).
22. **IMPORTANT : rien n'est borné pendant une coupure.**
    - `mute()` ne s'applique pas en BACKOFF, donc des milliers de lignes périmées s'accumulent.
    - `ContextExceeded` sur le premier message rouvre le chapitre en boucle.
    - Une 400 systématique rouvre un chapitre toutes les 30 s.
    - **Décision** :
      - la boîte est bornée dans tous les états : au-delà d'un plafond, résumé compté par type ;
      - backoff exponentiel plafonné à 15 min pour les réouvertures de chapitre sur erreur ;
      - `ContextExceeded` au premier message réduit le briefing et réessaie une fois, puis applique un backoff.
23. **IMPORTANT (M3) : le résumé de chapitre est perdu à la moindre erreur passagère** (`Chapters.kt:231-236`).
    - **Décision** : sur une erreur passagère (429, 529, 5xx, réseau, 402), le chapitre reste ouvert en état de clôture et la demande de résumé est réessayée avec backoff. Seuls un refus ou une réponse vide ou tronquée, après ses relances, justifient d'abandonner le résumé.
24. **IMPORTANT : après la limite d'étapes, le chat adressé au Narrateur est marqué vu sans réponse possible** (`AgentLoop.kt:161-170`).
    - **Décision** : ce qui a été injecté après la dernière réponse possible retourne dans la boîte, non vu.
25. **IMPORTANT : un reload qui change le modèle ou l'effort s'applique au chapitre en cours** (`ClaudeClient.kt:157`, `Chapters.kt:170-180`).
    - **Décision** : le modèle et l'effort sont figés par conversation ; le changement vaut au chapitre suivant.
26. **IMPORTANT (coût) : plus de 60 tours Opus par heure qui finissent sans rien faire.** Chaque ligne de chat entre joueurs réveille Opus en 45 s au plus, le battement heuristique vaut toujours 1.0 sans Jev, et il n'existe aucun budget de réveil global.
    - **Décision** :
      - le chat entre joueurs non adressé au Narrateur ne réveille pas : il attend le prochain battement ;
      - le score du battement heuristique reflète vraiment l'activité (événements saillants depuis le dernier tour) ;
      - un budget global de tours par heure, réglable (défaut 30), au-delà duquel seuls le chat adressé et les prières réveillent ;
      - décision `salience throttled`.
27. **IMPORTANT (injection) : les noms de joueurs ne sont pas échappés** (`<engine>` est un pseudo valide hors mode online), et les cellules `sql` et `OracleText.quote` laissent les chevrons tels quels.
    - **Décision** : un seul échappement commun, appliqué à tout texte d'origine joueur envoyé au modèle : noms, chat, cellules SQL, textes de l'Oracle.
28. **Mineurs** :
    - Numéros de chapitre qui repartent à 1 après une erreur du Journal au démarrage : les relire au lieu de repartir de 1. **Lot I.**
    - Chapitres vides résumés quand même : pas de résumé pour un chapitre sans échange. **Lot I.**
    - Chapitres sans résumé qui occupent les 3 places du briefing : ne compter que les chapitres résumés. **Lot I.**
    - Aucun plafond sur la file de `delegate` : 10 tâches en attente au plus. **Lot I.**
    - Rappels perdus au redémarrage : **M4**.
    - Index des blocs posés périmé (explosions, feu, pousses d'arbre) : **à traiter en M3 ou M4**, noté.

### Lots supplémentaires
| Lot | Constats | Fichiers principaux |
|---|---|---|
| H — confidentialité SQL | 19, 20, cellules SQL du 27 | `journal/SqlRunner.kt`, `journal/SqlFormat.kt`, `journal/DuckDatabase.kt`, `command/SqlCommand.kt`, leurs tests |
| I — Directeur | 22, 23, 24, 25, noms et Oracle du 27, mineurs « Lot I » du 28 | `director/Director.kt`, `director/Inbox.kt`, `director/Chapters.kt`, `agent/AgentLoop.kt`, `director/EventFormatter.kt`, `oracle/*Text*`, `agent/Workers.kt` |
| J — Journal | 21 | `journal/JournalWriter.kt`, `journal/DuckJournal.kt`, `trace/TraceSink.kt` |
| EF (étendu) | 26 | `director/Salience.kt`, `director/WakeScheduler.kt` (déjà au lot EF) |
