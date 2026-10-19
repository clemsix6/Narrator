# Relecture de M4 : constats et lots de corrections

Relecture du 19/10/2026, sur `main` après la fusion de M4-01 à M4-23 et de FLAKY-2 (`5d85922`, assemblage compris).
Elle porte sur le code des mécaniques (`mechanics/**`, `guard/**` : `GuardsImpl`, `Episodes`, `ItemLedger`, `Escrow`,
`BlockRestorer`, `DeathGuard`…), `observer/PlacedBlocks.kt`, `oracle/Survey.kt`, les outils de M4 (`tool/mechanics/**`,
`PunishTools`, `FavorTools`), les commandes (`Duel`, `Bet`, `Offer`, `Mechanics`, `Guard`), `director/ReminderStore.kt`
et ce que M4 a changé dans `director/` et `memory/`, `V3__mechanics.sql`, `views.sql` et les prompts (`director.md`,
section « Les mécaniques », `chronicler.md`, `dreamer.md`, `analyst.md`). Les passations M4-01 à M4-23 et FLAKY-2 ont
été lues, ainsi que `M4.md` (encart et §24), `M4_DESIGN_REVIEW.md` et `ARCHITECTURE.md` §17.

Chaque constat donne sa gravité, l'endroit, un scénario, la preuve (**prouvé** : test jetable rouge sur le vrai code ;
**vérifié** : lu des deux côtés, bytecode compris quand il le faut ; **à confirmer** sinon) et un **correctif proposé**.
Le superviseur décide. Les lots de la fin ne partagent aucun fichier ; l'ordre conseillé est donné.

**État de départ.**
- Avant la fusion de M4-23 (`7cae2ed`) : `build` vert, 1 488 tests unitaires, « All 297 required tests passed ».
- Après (`5d85922`) : tests unitaires verts (1 490), mais **`runGameTest` rouge à chacun des quatre lancements**, sur
  quatre tests différents (constat 22).
- Seize tests jetables (cinq tests unitaires, onze GameTests), ajoutés dans des classes existantes puis retirés :
  aucun n'est commité. Douze sont rouges et prouvent leur constat. Les quatre autres ne prouvent rien de plus que ce
  qui est dit aux constats 3 et 8 (un allumage prouvé, une létalité non reproduite, un témoin du banc).

## Réponses aux trois questions de M4-23

1. **`SchemaDoc.text` au démarrage, sur le thread serveur.** Acceptable en l'état : c'est avant la première tick, une
   base en mémoire et non celle du monde, environ 100 ms, dans l'esprit de l'exception de démarrage du §17.8. Mais la
   cause est ailleurs : `MechanicsService` donne à `EngineActions` **tout** le catalogue
   (`MechanicsService.kt:53`), qui construit l'outil `sql` (`SqlTool.kt:57`). Le moteur n'appelle pourtant que les
   dix-sept outils permis aux règles, plus `give_item` et `favor` pour les enjeux. Les lui donner seuls supprime la
   base en mémoire et le double « applied migration » du log, qui trompe à la lecture. Surtout, `run_command`, `sql`
   ou `delegate` deviennent inatteignables par un acte du moteur, même si une validation laissait passer un jour un
   outil interdit. Constat 23, mineur.
2. **Un défi échu pendant l'arrêt est `cancelled`, sans enjeux, paris rendus.** **Aucune perte d'objet, vérifié de
   bout en bout** :
   - `Reconcile.startup` puis `drop` (`Reconcile.kt:72-73`, `:214-226`) changent chaque pari en
     `Settlement(to = null)`, sans doublon ;
   - `Bets` les applique chaque seconde aux joueurs en ligne, et à la connexion avant le `JOIN` des garde-fous ;
   - `release` est idempotent ;
   - l'échéance de secours (fin + 7 jours) couvre le reste.

   Deux réserves, qui ne touchent pas aux objets :
   - **c'est injuste pour un `judged`** : un concours de construction dont l'échéance tombe pendant l'arrêt est
     annulé, alors que les joueurs ont construit. De même, un `reach` à gagnants multiples perd ses gagnants déjà
     acquis ;
   - ce chemin n'existe que quand le moteur tourne au démarrage. Avec `DisabledMechanics`, rien ne se règle
     (constat 9).

   Voir le constat 13.
3. **Les GameTests partagent le moteur du serveur.** Le risque n'est pas théorique : `main` n'est plus vert de façon
   fiable (constat 22). En plus des causes propres à chaque test :
   - le lot `assembly` est **exactement à la limite** de `maxChallenges = 2`. Ses quatre tests tournent en parallèle :
     un défi et des duels successifs. Un test de plus, ou un test qui échoue sans finir son défi, fait refuser le
     suivant (`challenge_limit`) ;
   - ses tests ne terminent pas dans leur `finally` ce qu'ils ont lancé. Un échec laisse au moteur du serveur un défi,
     un duel ou une loi pour les lots suivants, ainsi que `lastChallengeAt`, qui bloque tout départ de défi pendant
     10 minutes ;
   - le `RuleEngine` du serveur ôte, au chargement d'une créature, les marques des règles qu'il ne connaît pas
     (`RuleEngine.kt:208-215`), donc celles des moteurs de test. C'est voulu par `creepersUnderCaffeineAndBackToNormal`,
     mais une créature d'un test de règles rechargée pendant ce test perd ses marques.

   Correctifs dans le lot H.

## Constats

1. **BLOQUANT (§17, objets) : une pile dont le SNBT dépasse 65 535 octets est effacée du fichier du joueur.**
   - Où :
     - `guard/GuardState.kt:142` : `Confiscation.items` est un `Codec.STRING.listOf()`, une `StringTag` par pile ;
     - `guard/ItemLedger.kt:519` : `encode` écrit la pile entière en SNBT.
   - Mécanisme (bytecode 26.3) : `PlayerDataStorage.save` → `NbtIo.writeCompressed` → `NbtIo.write` →
     `writeUnnamedTagWithFallback` → `NbtIo$StringFallbackDataOutput.writeUTF`. Ce dernier attrape
     `UTFDataFormatException`, logue « Failed to write NBT String » et **écrit `""` à la place**.
   - Scénario : Mimosa offre (`/offer`) ou mise (`/bet`) un livre et plume plein (100 pages de 1 024 caractères), ou une
     boîte de Shulker pleine de livres écrits. Puis elle se déconnecte, ou le serveur redémarre. À la relecture,
     `decode("")` rend null : l'entrée est « unreadable » pour toujours, et les opérateurs lisent un faux diagnostic
     (« item gone from the game? »). La même faille touche les confiscations de M2, `owe`, les gains d'une remise et
     `recover`.
   - Preuve : **prouvé** (GameTest jetable dans `EscrowGameTests`). Un livre plein mis en séquestre, l'état passé par
     `NbtIo.write` puis relu, revient avec `items = [""]`. Mécanisme vérifié au bytecode.
   - Correctif proposé :
     - stocker chaque pile en `CompoundTag` (`CompoundTag.CODEC.listOf()`) au lieu d'une chaîne, en relisant l'ancien
       format ;
     - à défaut, refuser dans `hold` et `confiscate` toute pile dont l'encodage dépasse 60 000 octets, avec
       `offer.no_item` ou `bet.no_item` adaptés ;
     - test : la boucle complète `GuardState.CODEC` → `NbtIo.write` → `read` → `parse` avec un livre plein.

2. **BLOQUANT (§17, constructions et objets ; décision C) : la fin d'un piège détruit le bloc d'un joueur s'il est
   identique au bloc du piège.**
   - Où : `guard/BlockRestorer.kt:266-272`. Le retrait ne compare que l'état (`current == placed`), avec des drapeaux
     (818) qui suppriment tout drop.
   - Scénario : Bartok, en cage, casse une vitre avec une pioche Toucher de soie et la repose ; ou Mimosa pose son
     propre verre dans une case libérée. Une toile reprise aux cisailles et reposée, ou un verre de joueur poussé dans
     la case par un piston collant, donnent le même résultat. À l'échéance, ce bloc redevient de l'air, sans drop : une
     construction est détruite et un objet perdu. La passation M4-06 (« encore celui qu'on a posé » suffit) se trompe
     sur ce point.
   - Preuve : **prouvé** (GameTest jetable dans `TrapGameTests`). Une case d'une cage est cassée puis reprise par du
     verre marqué « posé par un joueur » dans `PlacedBlocks`. À l'échéance, la vitre du piège part, comme prévu, mais
     le verre du joueur aussi.
   - Correctif proposé :
     - dans `BlockRestorer.registerHooks`, un `PlayerBlockBreakEvents.AFTER` qui oublie l'entrée de la case cassée.
       Une case cassée par un joueur n'est alors plus jamais touchée, et cela marche moteur arrêté ;
     - dans `takeAway`, sauter une case dont `PlacedBlocks.placerAt` connaît un poseur ;
     - test : celui ci-dessus, plus le cas du piston.

3. **BLOQUANT (décision D : personne ne meurt d'un duel ; à confirmer en jeu) : le perdant épargné reste en danger
   au-delà de la fenêtre de 5 s.**
   - Où : `mechanics/challenge/Duels.kt:476-477` et `:490-495` (`spare` : 1 PV, feu éteint, résistance V pour 5 s, le
     joueur laissé sur place) ; `:569-579` (fenêtre `Fight.until`, 5 s) ; `:469-471` (après la fenêtre, `allowDeath`
     laisse mourir).
   - Scénarios :
     - **Fire Aspect.** `LivingEntity.hurtServer` rend vrai même quand la mort est refusée (bytecode). `Player.attack`
       applique donc ensuite les effets d'après-coup de l'arme : Fire Aspect II allume le perdant **après** l'épargne,
       pour 8 s. La résistance et la fenêtre durent 5 s. Avec moins de 18 de nourriture, le perdant meurt du feu vers
       6 à 7 s. La garde de mort ne l'attribue pas au Narrateur, et ses objets tombent aux pieds de son adversaire.
     - **Lave, feu, vide.** Un coup fatal porté dans la lave, ou un recul au-dessus du vide qui dure plus de 5 s :
       `spare` laisse le joueur sur place, et il meurt à la fin de la protection.
   - Preuve :
     - **prouvé en jeu** pour l'allumage : un vrai `Player.attack` avec une épée Fire Aspect II laisse le perdant à
       1 PV, en feu pour 160 ticks, après la fin du duel (GameTest jetable) ;
     - **vérifié au bytecode** pour la valeur de retour de `hurtServer` ;
     - **la mort elle-même n'est pas reproduite par le banc**. Un témoin le montre : un joueur de test qui brûle
       pendant 8 s dans une boucle de `doTick` ne prend qu'un seul dégât. Les GameTests de duel (`livesOn`) ne peuvent
       donc voir aucune mort après la fenêtre ; ils ne prouvent pas que le perdant survit. À confirmer avec un vrai
       client ou par un GameTest qui laisse tourner le serveur.
   - Correctif proposé :
     - après l'épargne, `aftercare` à **chaque tick** de la fenêtre (feu, effets du combat), et ne fermer la fenêtre
       que quand le joueur ne brûle plus et touche un sol sûr ;
     - si la position n'est pas sûre (lave, feu, liquide, vide sous les pieds), `guards.rescue` au lieu de `spare` ;
     - côté tests, un GameTest qui fait vraiment passer les ticks du serveur.

4. **IMPORTANT (thread serveur, ticks du moteur) : chaque mise perdue remise fait deux `PlayerList.saveAll()` sur le
   thread serveur.**
   - Où : `guard/ItemLedger.kt:331` et `:334` (`transfer`). `saveAll` écrit **tous** les joueurs en ligne : `.dat`
     compressé, statistiques et progrès de chacun.
   - Scénario : un défi ouvert à tous se termine avec trois mises perdues et vingt joueurs en ligne. Résultat : 120
     écritures de fichiers dans une seule tick, éventuellement pendant une connexion (`Bets.onJoin` → `forfeit` →
     `transfer`).
   - Preuve : **vérifié dans les logs des lancements de cette relecture**. Les secondes du moteur ont duré 1 094, 634,
     1 064, 552, 1 093, 503, 1 021 et 456 ms, partie la plus lente `Bets`. Chacune tombe exactement dans la seconde
     d'une ou deux lignes `transferred`, alors que le budget est de 2 ms. Ces gels contribuent aussi aux délais
     dépassés des GameTests (constat 22).
   - Correctif proposé :
     - n'écrire que les deux fichiers, gagnant puis perdant (`PlayerList.save(ServerPlayer)` est protégé : un
       accesseur mixin, ou `PlayerDataStorage.save` par accesseur) ;
     - regrouper les remises d'une même seconde en une seule écriture par joueur.

5. **IMPORTANT (rappels persistants, §15.1) : un rappel échu pendant une pause, ou budget épuisé, est retiré du
   monde avant d'être dit.**
   - Où : `director/Director.kt:294`, où `releaseReminders` passe avant la branche `if (paused)` ; `:1024-1050`, qui
     retire le rappel et sauve le monde dès qu'il entre dans la boîte.
   - Scénario : `remind_me` à 21:10, `/narrator pause` à 21:00, arrêt du serveur à 21:30 : R-1 n'existe plus, jamais
     dit. Même chose quand le budget de tours est épuisé : un `ReminderDue` n'est pas exempté et peut attendre une
     heure. De même en `BLOCKED` et en `BACKOFF`. Le prompt affirme pourtant « Tes rappels survivent à un
     redémarrage ».
   - Preuve : **prouvé** (test unitaire jetable dans `DirectorMechanicsScenariosTest`). Rappel de 60 s, `Pause`, 120 s
     plus tard : aucune requête au modèle, et le monde ne contient plus R-1.
   - Correctif proposé :
     - ne retirer un rappel du monde qu'une fois envoyé le tour qui le porte (`afterTurn`) ;
     - variante simple : à l'arrêt, réécrire les `ReminderDue` encore dans la boîte, puis laisser la logique « en
       retard » existante les reprendre.

6. **IMPORTANT (coût) : `/offer` réveille le Directeur au-delà du budget de tours, sans plafond global.**
   - Où : `command/OfferCommand.kt:76` (`priority = true`), `director/WakeScheduler.kt:61-65` (`pastBudget`). La seule
     limite est par joueur : une offrande par `offeringMinutes`, réglable de 2 à 60 minutes, et n'importe quel bloc de
     terre suffit.
   - Scénario : huit joueurs offrent chacun un bloc de terre toutes les 10 minutes, à plus de 10 s d'écart, au-delà du
     regroupement `SOON`. Cela fait 48 tours d'Opus de plus par heure, au-delà des 30 du budget, et 240 avec
     `offeringMinutes = 2`.
   - Les autres réveils prioritaires sont bornés par le Directeur lui-même : fins de défi et de duel, `JUDGE`. Les
     réveils des règles (`notify`, un toutes les 5 min par règle) ne sont pas prioritaires et restent dans le budget.
   - Preuve : **vérifié** par lecture des deux côtés.
   - Correctif proposé : un petit sous-budget horaire des réveils prioritaires d'offrandes (par exemple 4 par heure),
     compté dans `TurnBudget`. Au-delà, une offrande reste `SOON` et attend.

7. **IMPORTANT (règles) : les effets différés d'une règle finie s'exécutent quand même.**
   - Où :
     - `mechanics/rule/RuleEngine.kt:304-317` et `:332-352` : la coroutine de `fire` ne vérifie jamais que la règle vit
       encore avant chaque effet ;
     - `:418-448` (`runMobFirings`, `onCreature`) : même absence de contrôle ;
     - `:480-489` : `left()` ne retire pas les déclenchements en attente.
   - Scénario : une loi `damage_dealt cow` porte un `mob_attribute movement_speed` avec `delay_seconds: 30`. Le
     Directeur la retire par clémence, ou elle arrive à échéance, ou elle est désactivée. 30 s plus tard, l'effet
     repose le modificateur et les tags `narrator2`, que plus rien ne retire tant que la vache reste chargée. Un
     `punish`, une `explosion` ou un `teleport` différés frappent jusqu'à 60 s après un `remove_rule`, et un `notify`
     réveille le Directeur pour une loi retirée.
   - Preuve : **prouvé** (test unitaire jetable dans `RuleEngineTest`). Une loi `notify` différée de 10 s, retirée par
     un opérateur pendant le délai : 10 s plus tard, le Directeur reçoit quand même `C-1000 fired: … your note`.
   - Correctif proposé :
     - un `Job` par règle, annulé dans `left()` et `end()` ;
     - à défaut, contrôler `store.snapshot.rule(id) != null && id !in ending` avant chaque effet ;
     - `mobFirings.removeIf { it.rule.id in ids }` dans `left()`.

8. **IMPORTANT (règles ; à trancher) : une loi peut tuer le bétail et les villageois, malgré « pas d'effet qui tue »
   (§8.4, décision I).**
   - Où :
     - `mechanics/rule/RuleOnlyTools.kt:135-176` : `mob_effect` accepte `levitation`, jusqu'à 300 s et au niveau 2 au
       chaos 3 ;
     - `mob_attribute` reprend les bornes de `spawn` : `scale` ×4, `jump_strength` ×3, `gravity` ×0,1 ;
     - seul `mob_spawn` exclut les animaux apprivoisés et les créatures nommées (`RuleEngine.kt:468-473`).
   - Scénario : « les vaches s'envolent » (lévitation 300 s, niveau 2) balaie toutes les vaches chargées. Elles montent
     jusqu'en haut, puis tombent et meurent. Avec `scale` ×4, des villageois de halle de commerce se retrouvent la tête
     dans le plafond. Le chien d'un joueur, touché par `damage_dealt`, subit le même traitement.
   - Preuve :
     - **prouvé** pour l'acceptation : test unitaire jetable dans `RuleValidationTest`, la loi `mob_spawn villager`
       avec lévitation 300 s niveau 2 est créée ;
     - **la létalité est à confirmer** : une vache soulevée 10 s au niveau 2 dans un GameTest a survécu, mais la
       structure de test est basse, et 300 s n'ont pas été essayées.
   - Correctif proposé :
     - interdire `levitation` aux règles, ou la plafonner à 3 s ;
     - dans une règle, borner `scale` ≤ 1,5, `jump_strength` ≤ 1,5 et `gravity` ≥ 0,5 ;
     - appliquer les exclusions de `spawnable` (apprivoisé, nommé) à tous les déclencheurs ;
     - à trancher : rendre ces créatures insensibles aux chutes et à l'étouffement tant que la règle les marque.

9. **IMPORTANT (redémarrage) : avec le moteur désactivé au démarrage, son état reste figé dans le monde, et personne
   ne le dit.**
   - Où : `mechanics/MechanicsModule.kt:16-30` rend `DisabledMechanics` quand `mechanics.enabled = false`, ou quand le
     Journal ou la mémoire sont indisponibles. Ce dernier cas suffit qu'un antivirus tienne le fichier DuckDB. Alors
     aucune partie ne tourne, `allowDeath` vaut toujours vrai et `onMobLoad` ne fait rien.
   - Conséquences :
     - **duels** : les duellistes restent à l'arène, et `returns` n'est jamais appliqué. Une mort entre eux redevient
       réelle, alors que l'invitation promettait « personne n'y meurt ». Au retour du moteur, des jours plus tard,
       `putBack` les téléporte à une position périmée (constat 21) ;
     - **règles** : les créatures marquées gardent leurs modificateurs permanents (vitesse ×3, PV ×5…) et le tag
       `narrator2`. Leurs morts restent « du Narrateur », elles ne ramassent plus rien (constat 30) ;
     - **paris** : les instructions dorment dans l'état, les mises ne reviennent qu'à fin + 7 jours. Une cession en
       attente revient alors au perdant, et le gagnant n'est jamais payé ;
     - **canon** : les lois et défis restent `active` ;
     - offrandes, dus et mises déjà cédées : corrects, ils tournent avec les garde-fous.
   - Preuve : **vérifié** par lecture.
   - Correctif proposé : un « gardien » minimal quand le moteur est désactivé, sans base, sur le thread serveur :
     - `Reconcile.drop` de tous les duels de l'état (vers `returns`) ;
     - application des retours ;
     - `RuleEngine.unmark` des créatures qui se chargent ;
     - application des instructions de paris ;
     - un message `duel.cancelled` à la connexion.

     Il faut pour cela extraire `putBack` de `Duels` et l'application des instructions de `Bets`.

10. **IMPORTANT (garde-fous, quota de morts) : `tree_top` n'arme que `Peril.FALL`, alors que toute téléportation
    arme `Peril.ANY`.**
    - Où : `mechanics/punish/PunishmentCatalog.kt:242`, `Draft(TREE_TOP_SECONDS = 60, …, Peril.FALL, …)` ;
      `tool/action/PlayerActionTools.kt:662`, où `teleport` arme `Peril.ANY` pendant 30 s (relecture M2, constat 12 :
      « la zone d'arrivée peut être dangereuse »).
    - Scénario : de nuit, en jungle, un joueur est déposé sur une cime à 25-30 blocs. Un squelette ou des phantoms le
      tuent : la mort n'est pas au Narrateur, et ses objets tombent. Depuis une grotte (`MOTION_BLOCKING`), `tree_top`
      sort un mineur de y = -40 et le pose plus de 110 blocs plus haut.
    - Preuve : **prouvé** (GameTest jetable dans `PunishGameTests`). Juste après `punish tree_top`,
      `DeathGuard.attributed(joueur, generic)` est faux.
    - Correctif proposé :
      - armer aussi `Peril.ANY` pendant 30 s après le déplacement ;
      - une durée de `FALL` qui croît avec la hauteur ;
      - borner la montée (32 blocs au plus) ou exiger le ciel visible.

11. **IMPORTANT (acharnement) : une explosion refusée laisse des « punitions fantômes » aux autres spectateurs.**
    - Où : `tool/action/NarratorExplosionDamage.kt:83-90`. `allow` appelle `guards.hostile` sur chaque spectateur dans
      l'ordre. `hostile` ouvre l'épisode, le compte dans `fair_share` et met la catégorie au repos. Le premier refus
      sort de la boucle, mais les épisodes déjà ouverts restent. Même chose dans `Fuses.detonate`
      (`WorldActionTools.kt:614-622`).
    - Scénario : la loi des diamants explosifs. Bob mine près de Mimosa, déjà punie. L'explosion est refusée puis
      rejouée inoffensive, sans toucher Bob. Pourtant Bob reçoit un épisode, une punition comptée sur ses quatre de
      l'heure et la catégorie `explosion` au repos pour 30 min. Répété à chaque diamant, cela vide son quota sans
      qu'il subisse rien.
    - Preuve : **prouvé** (GameTest jetable dans `GuardPolicyGameTests`). Mimosa est punie par un autre acte ;
      `allow([Bob, Mimosa])` est refusé, et Bob est pourtant « puni ».
    - Correctif proposé : deux phases. D'abord un contrôle pur de tous les spectateurs (protection, chaos, grâce,
      `TargetingPolicy.check`, sans effet de bord), puis `hostile` sur tous, seulement si tous passent.

12. **IMPORTANT (règles, PvP ; à trancher) : une loi `damage_dealt` peut viser la victime d'un coup entre joueurs.**
    - Où : `mechanics/rule/RuleValidator.kt:194-202`. Sans `when.entity`, ou avec `entity: player`, `at subject`
      désigne le joueur frappé, et `punish`, `explosion`, `teleport`, `lightning` ou `apply_effect` y sont permis.
    - Scénario : la loi `damage_dealt → punish smite (3) at subject`. N'importe quel joueur peut ainsi diriger le
      Narrateur contre un autre : il lui suffit de le frapper, une fois toutes les 3 s. Les garde-fous bornent la
      fréquence, mais pas l'instrumentalisation. Le moteur dit lui-même « a fight between players is a duel's, never a
      rule's ».
    - Preuve : **prouvé** (test unitaire jetable dans `RuleValidationTest`). Cette loi est acceptée sans condition
      comme avec `entity: player`.
    - Correctif proposé :
      - quand le sujet peut être un joueur, `at subject` réservé à la parole (`say`, `actionbar`, `show_title`,
        `play_sound`) et à `notify` ;
      - les outils hostiles ne visent que l'acteur ;
      - à trancher : `teleport … to_player` nommé, qui réunit deux joueurs sans l'accord du second.

13. **IMPORTANT (défis) : le redémarrage traite mal les défis jugés, et le Directeur n'en sait rien.**
    - Où :
      - `mechanics/Reconcile.kt:72` et `:229-230` : un `judged` encore `RUNNING` dont `endsAt` tombe pendant l'arrêt
        est annulé, au lieu de passer en `JUDGING` ;
      - `Challenges.kt:592-599` : un défi déjà `JUDGING` au redémarrage n'envoie plus de `JUDGE`, puisque le signal
        vivait dans l'`Inbox`, en mémoire. Il expire sans réveil prioritaire ;
      - aucune annulation de démarrage n'est dite au Directeur : seule la décision `mechanic cancelled` existe.
    - Scénario : « la plus belle statue en 15 minutes », redémarrage à la quatorzième. Les statues sont bâties, et le
      défi est annulé en silence.
    - Preuve : **vérifié** par lecture.
    - Correctif proposé :
      - pour un `judged`, échéance de démarrage `endsAt + judgeMinutes`, puis passage en `JUDGING` ;
      - renvoyer `JUDGE` à la **première seconde**, le Directeur étant créé après le moteur ;
      - un signal `CHALLENGE` non prioritaire pour chaque défi annulé au démarrage.
      - Le reste est à trancher : appliquer `Goals.verdict(c, deadline)` aux défis non jugés dont des participants ont
        `doneAt`, avec des récompenses payées à la connexion.

14. **IMPORTANT (défis, gameplay) : un message qui énumère des propositions gagne un quiz.**
    - Où : `mechanics/challenge/Answers.kt:39-42`. Une réponse est acceptée dès qu'elle figure en mots entiers dans le
      message, quelle que soit sa longueur, sans limite de tentatives (`Challenges.kt:515-521`).
    - Scénario : Bartok écrit « diamant fer or émeraude enclume pioche épée arc » et gagne le quiz « enclume ».
    - Preuve : **prouvé** (test unitaire jetable dans `AnswersTest`) : `matches(…, ["enclume"])` est vrai.
    - Correctif proposé : un message ne compte que s'il a au plus `mots de la réponse + 3` mots, ce qui laisse passer
      « c'est une enclume ». Plus un délai par participant entre deux tentatives comptées (par exemple 5 s).

15. **IMPORTANT (défis, PvP indirect) : un défi `kill` compte les animaux apprivoisés et nommés des autres joueurs.**
    - Où : `mechanics/challenge/Challenges.kt:510-511` ne filtre que `!event.pvp`. Pourtant `EntityKilled.tamed`
      existe, et la victime porte son nom.
    - Scénario : « tuer 3 loups » : le plus court est de tuer les chiens de Mimosa. Le moteur pousse à nuire au bien
      d'un autre joueur.
    - Preuve : **vérifié** par lecture des deux côtés. Les règles excluent ces cas (`RuleEngine.spawnable`), les défis
      non.
    - Correctif proposé : `if (!event.pvp && !event.tamed && event.victim.name == null)`.

16. **IMPORTANT (acharnement ; à trancher) : un même acte peut empiler autant de châtiments qu'il veut sur un joueur.**
    - Où : `guard/TargetingPolicy.kt:49` et `:68`. Un épisode du même acte passe `already_punished` comme
      `same_punishment`, et `Episodes.kt:62` le prolonge simplement.
    - Scénario : en un seul tour, `cage` + `smite` ×2 + `guests` + `hunger` + `butterfingers` sur Bartok ne comptent
      que pour une punition sur les quatre de l'heure. Une règle à quatre effets `punish` sur l'acteur fait pareil à
      chaque déclenchement. Le §6.3 dit qu'un appel « compose » un châtiment ; le §17.4 interdit un « nouveau
      châtiment » à qui est « déjà en plein châtiment ».
    - Preuve : **prouvé** (GameTest jetable dans `PunishGameTests`). Trois `punish` du même acte (`hunger`,
      `heavy_feet`, `smite`) passent tous, et Bartok est foudroyé en plus des deux autres.
    - Correctif proposé : au plus un `punish` par acte et par joueur. Le second serait refusé `already_punished`
      (« this act already punished Bartok (hunger) »). Côté règles, au plus un effet `punish` par cible.

17. **IMPORTANT (créatures du Narrateur ; à trancher) : les zombies « invités » attaquent les villageois.**
    - Où : `guard/CreatureShield.kt:65-66`. `keepsAway` ne protège que les joueurs, alors que le zombie vanilla vise
      aussi `AbstractVillager`, `IronGolem` et `Turtle`.
    - Scénario : `punish guests` sur Bartok dans son village ou sa halle de commerce. Bartok est sous grâce, ou à
      l'abri. Les zombies du Narrateur se rabattent sur les villageois : mort ou conversion, perte durable.
    - Preuve : **prouvé** (GameTest jetable dans `GuardPolicyGameTests`). Un villageois et un zombie du Narrateur
      laissés seuls : après 300 ticks, le villageois est blessé.
    - Correctif proposé : une créature du Narrateur ne vise que des joueurs non épargnés (`keepsAway` et
      `MobTargetGuardMixin` : `target !is Player || spares(target)`).

18. **IMPORTANT (acharnement, duels) : un joueur épargné ou sauvé peut être achevé aussitôt, Narrateur compris.**
    - Où : `guard/GuardsImpl.kt:350-352`. `lastDeathAt` n'est écrit qu'à `AFTER_DEATH`. Une mort refusée n'y arrive
      jamais : ni l'épargne d'un duel (`Duels.spare`), ni un sauvetage (`DeathGuard.rescue`, au-delà du quota ou en
      duel).
    - Scénario : le Directeur lit « DUEL C-52 won by Ann: Bob's fatal blow was stopped at 1 hp » et lance `punish smite`
      sur Bob, à 1 PV. `no_pile_on` ne joue pas, et la mort compte dans le quota. Sans arène, Ann attend 6 s et achève
      Bob : une mort JcJ réelle, avec les objets au sol.
    - Preuve : **vérifié** par lecture (`GuardsImpl.kt:96`, `:293-300`, `:350-352` ; `TargetingPolicy.kt:33-40`).
    - Correctif proposé :
      - une épargne ou un sauvetage écrivent `lastDeathAt = now` par une méthode des garde-fous (pas d'écriture
        directe dans la pièce jointe) : le Narrateur et ses créatures laissent alors le joueur 5 min, comme après une
        mort ;
      - côté duel, une fenêtre contre l'ex-adversaire de 30 à 60 s, avec une nouvelle épargne.

19. **IMPORTANT (duels, paris) : un tiers, Narrateur compris, décide de l'issue d'un duel.**
    - Où : `mechanics/challenge/Duels.kt:476-483`. Toute mort d'un duelliste qui ne vient pas de l'adversaire donne
      quand même la victoire à l'autre. `Guards.hostile` ne connaît pas les duels.
    - Scénario :
      - pendant le duel, `punish smite`, une créature du Narrateur, une règle ou une explosion portent le coup mortel à
        Bob : Bob est sauvé, mais **perd**. Ann reçoit la récompense, et les paris se règlent en conséquence ;
      - un parieur tiers fait pareil : il frappe Bob ou verse de la lave sous lui pour gagner sa mise, aux dépens
        d'objets réels.
    - Preuve : **vérifié** par lecture.
    - Correctif proposé :
      - un coup mortel qui ne vient pas de l'adversaire : sauvetage et duel **annulé**, paris rendus ;
      - à trancher en plus : `hostile` refuse une cible en duel (« nothing hostile against a duelist while the duel
        runs »).

20. **IMPORTANT (redémarrage, objets) : une mise d'un défi illisible ne revient qu'au bout de 7 jours.**
    - Où : `mechanics/MechanicsState.kt:95` et `:104-115`. Un défi illisible est écarté **avec ses paris**, et
      `Reconcile.startup` ne voit que `state.challenges` : aucune instruction n'est écrite. Même chose pour une
      instruction illisible, ou un état global illisible en entier.
    - Rien n'est perdu, mais l'objet reste bloqué jusqu'à l'échéance de secours (fin + 7 jours).
    - Preuve : **vérifié** par lecture.
    - Correctif proposé : au décodage, garder les paris d'une entrée illisible et les changer en
      `Settlement(to = null)`. À défaut, un balayage dans `Bets` : une `STAKE` sans `owedTo`, dont le canon n'est plus
      dans l'état et qui n'a pas d'instruction, est rendue.

21. **IMPORTANT (duels, objets) : les retours d'arène peuvent poser un joueur au-dessus du vide ou de la lave, et
    n'ont pas d'âge.**
    - Où :
      - `mechanics/challenge/Duels.kt:621` : sans sol sûr dans la colonne, `putBack` remet le joueur à la position
        exacte ;
      - `tool/action/Teleports.kt:108-121` : `landing` cherche dans toute la colonne, sans borne de hauteur ;
      - `SavedPosition` n'a pas d'horodatage.
    - Scénario : Bob a accepté en construisant un pont au-dessus du vide de l'End, ou sur un pont du Nether au-dessus
      de la mer de lave, cassé depuis. Il est remis en l'air. Si c'est après une reconnexion, la fenêtre de 5 s est
      passée depuis longtemps : il meurt, et ses objets sont perdus. Une lave versée sur la position gardée l'envoie
      dans la première poche sûre en dessous, qui peut être une grotte fermée 50 blocs plus bas.
    - Preuve : **vérifié** par lecture.
    - Correctif proposé : borner l'écart de hauteur (±8, comme `spots`). Sans sol sûr, le point de réapparition. Dater
      les retours et abandonner, avec une décision, un retour trop vieux (par exemple plus de 24 h).

22. **IMPORTANT (tests, stabilité de `main`) : `runGameTest` est rouge à chaque lancement depuis la fusion de M4-23.**
    - Preuve (quatre lancements, `5d85922` et ses dérivés) :
      1. `rule_engine_game_tests_a_diamond_ore_mined_explodes…` : « timed out waiting for: the blast where the ore
         was ». Un joueur d'un test voisin, déjà puni, était dans le rayon : l'explosion a été refusée puis rejouée
         inoffensive, ce qui est le constat 11, et `ExplosionCoalescer` l'a sans doute fusionnée (à confirmer) ;
      2. trois échecs :
         - `mechanics_assembly_game_tests_a_duel_of_the_servers_engine_kills_nobody` : « rescued, alive, put out:
           3.33 » ;
         - le même test du diamant : « timed out waiting for: C-122 closed RETIRED » ;
         - `director_end_to_end_tests_a_chat_wakes_the_director…` : « sql reads the greeting in the Journal: 0 rows » ;
      3. `rule_engine_game_tests_a_welcome_committee_awaits_the_player_in_the_nether_once` : « no free ground for
         minecraft:zombified_piglin within 8 blocks » ;
      4. de nouveau le duel de l'assemblage, à l'identique.

      Avant la fusion, le lancement de référence était vert.
    - Causes :
      - **horloges du banc des règles** (vérifié) : `RuleBook.create` date l'échéance avec `services.clock`
        (`RuleBook.kt:82-87`), l'horloge réelle du banc, alors que `RuleEngine.second` la compare à `rig.clock`
        (`RuleEngine.kt:273`). Dès que plus de 2 à 3 s réelles séparent la construction du banc et la création de la
        loi, ce qu'un gel de `Bets` (constat 4) suffit à provoquer, la loi n'expire jamais dans le test. En jeu, les
        deux valent `services.clock`, mais l'API est fragile ;
      - **duel de l'assemblage** (à confirmer) : les deux duels démarrent dans la même seconde. La lave du second
        arrive pendant le temps de recharge des dégâts laissé par le coup fatal du premier, et la boucle de `doTick` à
        la main ne laisse jamais ce temps passer. Le témoin du constat 3 montre que le banc n'inflige qu'un dégât de
        feu sur 8 s. Quand les deux duels sont à plus d'une seconde d'écart, le test passe ;
      - les autres (Nether, lecture `sql` du Journal) dépendent de l'ordre des lots et des gels du serveur de test
        (constat 4 ; écrivain du Journal tenu 1,5 à 2 s, déjà connu).
    - Correctif proposé (lot H) :
      - `RuleBook(…, clock)` partagé avec le `RuleEngine` ;
      - les tests de règles qui explosent dans une salle à l'écart ;
      - le second duel de l'assemblage avec d'autres joueurs, ou après 20 ticks réels ;
      - le comité du Nether dans une salle préparée plus grande ;
      - le lot `assembly` qui termine dans son `finally` ce qu'il a lancé, avec `challengeGapMinutes = 0` dans son
        environnement.

      Puis cinq lancements verts d'affilée, comme FLAKY-2.

23. **MINEUR (assemblage) : `EngineActions` reçoit tout le catalogue.**
    - Où : `MechanicsService.kt:53`.
    - Effets : la base en mémoire de `SchemaDoc` est construite au démarrage, sur le thread serveur, et le log montre
      les migrations deux fois.
    - Défense en profondeur absente : seule la validation d'une règle ou d'un enjeu sépare un acte du moteur de
      `run_command`, `sql` ou `delegate`. L'exécution le refait aussi, mais seulement dans `RuleEngine` (`:334`).
    - Correctif proposé : `EngineActions` ne reçoit que `RuleTargets.ALLOWED` et les outils des enjeux (`give_item`,
      `apply_effect`, `favor`, `punish`).

24. **MINEUR (défis) : une fin interrompue est résolue au canon avec une fausse explication.**
    - Où :
      - `Challenges.kt:415-423` : `conclude` paie les enjeux, environ une seconde par `punish`, **avant** de résoudre
        le canon, alors que l'état est déjà retiré ;
      - `MechanicsService.start` : si le démarrage échoue après `Engine.begin`, l'état a déjà été élagué.
    - Scénario : un arrêt, un plantage ou la mémoire indisponible entre les deux. Au démarrage suivant, `Reconcile`
      résout le défi gagné `cancelled`, avec la note fausse « lost when the world rolled back ».
    - Preuve : **vérifié** par lecture.
    - Correctif proposé : résoudre le canon d'abord, sous `NonCancellable` (la résolution ne dépend pas des enjeux).

25. **MINEUR (défis, duels) : un départ interrompu après l'écriture du canon laisse une ligne orpheline jusqu'au
    redémarrage.**
    - Où : `Challenges.kt:195-197` et `Duels.kt:151-153`. Après `writeCanon`, plusieurs choses annulent `insert` : le
      délai de l'outil (10 s), celui de `onServerThread` (5 s) ou l'arrêt.
    - Effet : `resolve` refuse la ligne (« end it with end_challenge »), `end_challenge` répond « not running », et un
      nouvel essai crée une seconde ligne, puisque `lastChallengeAt` n'est pas posé.
    - Correctif proposé : passer en `NonCancellable` après `writeCanon`. Toute exception d'`insert` appelle
      `cancelRefused`, pas seulement un refus de rythme.

26. **MINEUR (défis, duels) : spectateurs et joueurs en créatif sont inscrits d'office et peuvent gagner.**
    - Où : `Challenges.kt:204-208` (`prepare`), `:614-624` (`onJoin`) ; `Duels.prepare` et `unfit`.
    - Scénario : un opérateur en spectateur gagne d'office un `survive`. En créatif, il gagne un `obtain` en piochant
      l'objet dans l'inventaire du créatif, avec la récompense, et clôt le défi à `winners = 1`. Un duelliste en créatif
      est invulnérable.
    - Correctif proposé :
      - exclure `isSpectator || isCreative` de l'inscription automatique ;
      - ne pas faire progresser un participant nommé tant qu'il est dans l'un de ces modes ;
      - refuser un tel duelliste dans `unfit`.

27. **MINEUR (défis) : détails du cycle de vie.**
    - Les participants sont figés à `prepare`, pas à `insert` (`Challenges.kt:204-257`). Un joueur qui part dans cette
      fenêtre reste « en lice » d'un `survive` sans pouvoir en sortir ; un joueur qui arrive n'est jamais inscrit.
    - Les retardataires reçoivent l'interaction `challenge_result` « lost the challenge » (`Challenges.kt:465-479`),
      contrairement à « peut gagner, jamais perdre ».
    - `Challenges.target` met en cache une cible nulle avec `getOrPut`, qui ne garde jamais `null`
      (`Challenges.kt:602-608`) : un objectif devenu introuvable est résolu de nouveau, avec un `ERROR`, à chaque
      seconde.

    Correctifs évidents, dans le lot B.

28. **MINEUR (objets) : une remise rejouée après un crash paie le gagnant deux fois.**
    - Où : `ItemLedger.kt:325-349` (`transfer`), `Escrow.kt:92-93` (`add` ne voit que les entrées présentes).
    - Scénario : un crash, ou l'échec de l'écriture du fichier du perdant, entre les deux `saveAll`. Au redémarrage, le
      gagnant est livré en premier (connexion, ordre de `playerList`). Puis la remise du perdant ne retrouve plus l'id
      chez le gagnant, l'ajoute de nouveau, et le livre une seconde fois. La KDoc (« a replay gives nothing twice ») est
      fausse, et le GameTest existant ne couvre que l'ordre favorable.
    - Preuve : **prouvé** (GameTest jetable dans `EscrowGameTests`) : gagnant livré d'abord, puis remise du perdant,
      **24 lingots au lieu de 12**.
    - Correctif proposé : un registre des ids de mises reçues dans le `GuardState` du gagnant, gardé
      `CEDED_RESCUE_DAYS`, écrit à l'étape 1 et consulté par `add`.

29. **MINEUR (objets, latent) : `narrator2:mechanics` est un seul texte JSON, soumis au même plafond de 65 535 octets
    que le constat 1 ; et `settlements` n'est jamais purgé.**
    - Où : `MechanicsState.kt:123` (`Codec.STRING.xmap`).
    - Effet : au-delà du plafond, l'état est écrit `""`, puis relu « cannot be read, the engine starts empty » : défis,
      règles, paris et instructions perdus. On reste loin du plafond avec les réglages par défaut, mais `maxRules` va
      jusqu'à 20, les règles portent leurs textes, et une instruction dont le propriétaire ne revient jamais reste pour
      toujours (`Bets.kt:152-165`).
    - Preuve : **vérifié** pour le mécanisme (constat 1). Le passage de la pièce jointe globale par `NbtIo.write` et
      la taille d'un état plein sont **à confirmer**.
    - Correctif proposé : une chaîne par entrée (le décodage est déjà fait entrée par entrée), une alerte sous le
      plafond, et `Settlement` daté, abandonné après `CEDED_RESCUE_DAYS`.

30. **MINEUR (règles) : la garde de ramassage s'applique aux créatures marquées par une règle.**
    - Où : `mixin/policy/MobPickUpGuardMixin.java:18-21` teste le tag `narrator2`, que `RuleMarks.mark` pose.
    - Scénario : « les piglins courent ×2 » : ils ne ramassent plus l'or jeté, donc plus de troc par l'or au sol. Les
      villageois touchés ne ramassent plus pain ni récoltes : plus de reproduction, plus de fermiers. Cela dure 7 jours,
      ou pour toujours avec le constat 9.
    - Preuve : **vérifié** par lecture.
    - Correctif proposé : tester `hasAttached(NarratorMob.TYPE)`, c'est-à-dire seulement les créatures invoquées, qui
      seules disparaissent avec ce qu'elles tiennent.

31. **MINEUR (Chroniqueur, `ENGINE_NEWS`) : les fins de règles y entrent par homonymie, et une règle retirée par un
    opérateur n'est jamais dite au Directeur.**
    - Où :
      - `ExchangeLog.kt:533-536` lit `mechanic:expired` pour les défis jugés non tranchés, mais les règles écrivent la
        même décision (`RuleEngine.kt:544`, `RuleBook.kt:134`). Le Chroniqueur voit donc `expired` d'une loi, mais
        jamais `disabled`, `removed` ni `fulfilled` ;
      - `/narrator rule remove` ne poste aucun signal (`RuleBook.kt:105-135`), alors que `challenge cancel` envoie un
        `CHALLENGE`.
    - Preuve : **vérifié** par lecture des deux côtés.
    - Correctif proposé :
      - nommer les fins de règles à part (`ended`, avec `detail.ending`) et les ajouter à `ENGINE_NEWS`, avec un test
        dans `ExchangeLogTest` ;
      - un `RULE` non prioritaire quand un opérateur retire une règle.

32. **MINEUR (mémoire) : le Chroniqueur peut doubler un résultat ou une ligne du moteur.**
    - `note_interaction` n'a pas d'argument `canon` (`MemoryTools.kt:310-318`), et `Dedup.interaction` ne fusionne une
      interaction du moteur sans preuve qu'avec le même `canon_id`. Un `challenge_result` noté par le Chroniqueur
      s'ajoute donc à celui du moteur, et la faveur bouge deux fois.
    - `Dedup.canon` (`Dedup.kt:23-33`) exige les mêmes joueurs. Or un défi du moteur « → all » a `players = []`. Un
      `remember challenge` du Chroniqueur nommant les joueurs fait donc une seconde ligne, non suivie, avec son
      échéance et un `DUE` plus tard (à confirmer en réel).
    - Correctif proposé :
      - « les résultats » dans `chronicler.md` (82 caractères de marge) ;
      - `Dedup` compare un brouillon `challenge` aux lignes ouvertes avec `mechanic` des dernières heures, quels que
        soient les joueurs.

33. **MINEUR (règles, coût et équité) : limites partagées, balayages sans borne, désactivations injustes.**
    - Les règles partagent avec le Directeur les quotas de parole du serveur (`SpeechLimiter.kt:121-126`). Une loi
      `block_break → say to everyone` en rafale fait refuser `speech_limit` au `say` du Directeur pendant une minute.
    - Un balayage `mob_spawn` passe toutes les créatures appariées en une tick (`RuleEngine.kt:418-432`), soit
      10 à 20 ms pour 500 vaches, et écrit une décision `fired` par créature, jusqu'à 200 par minute et par règle. La
      file des traces jette le trop-plein.
    - Les `FAILED` passagers comptent comme des échecs : joueur mort, pas de sol libre, tronçon non chargé. De même
      `mob_attribute` sur un type sans cet attribut. Cinq vaches de suite désactivent une loi pendant son balayage
      même.
    - `teleport random_radius` d'une règle charge, voire génère, un tronçon avant le contrôle des gardes
      (`PlayerActionTools.kt:627-660`).
    - `glowing` est NEUTRAL dans le jeu, donc non hostile pour `apply_effect` : une loi peut faire briller un joueur
      900 s au niveau 5 à chaque déclenchement, sans garde-fou.
    - Correctif proposé :
      - une part des quotas de parole réservée au Directeur, ou 2 répliques par minute par règle ;
      - un balayage étalé (50 créatures par tick) et une décision résumée par minute ;
      - les échecs passagers rangés en `skipped` ;
      - l'attribut vérifié à la création (`DefaultAttributes`) ;
      - un pré-contrôle pur des gardes avant le chargement ;
      - `glowing` rangé parmi les effets hostiles d'`apply_effect`.

34. **MINEUR (duels) : détails.**
    - Un duel accepté par les deux reste `INVITED` pour toujours si la coroutine de chargement de l'arène meurt :
      `awaitLoaded` est hors du `try`, et l'id reste dans `starting` (`Duels.kt:371-372`, `:587`). Le duel occupe une
      place de `maxChallenges`.
    - `end_challenge` ou `/narrator challenge cancel` en `won` ou `lost` sur un duel encore `INVITED` (`Duels.kt:533`) :
      récompense payée et historique « lost the duel » pour un duel jamais accepté.
    - Après un redémarrage, un duelliste qui ne revient jamais n'est jamais compté comme parti.
    - Aucun délai entre deux propositions au même joueur après un refus : une pression contraire à l'esprit de
      « jamais forcé ».
    - Correctif proposé :
      - `awaitLoaded` dans le `try`, `starting.remove` dans un `finally`, et une échéance dure ;
      - `won` et `lost` refusés sur un duel `INVITED` ;
      - un délai de grâce de 60 s au `started` ;
      - `DENIED (duel_declined)` pendant 10 min après un refus.

35. **MINEUR (garde-fous) : un joker est consommé pour rien après un châtiment instantané.**
    - Où : `GuardsImpl.kt:261`, où `isPunished` est vrai tant qu'un épisode court : 15 à 60 s pour `butterfingers`,
      `skyward`, `tree_top` ou `smite`. `Jokers.kt:151-164` lève alors « punishment ended » et consomme le joker.
    - Effet : le joueur reste sur son arbre et garde son inventaire mélangé, mais perd son joker. En plus, fermer
      l'épisode retire `already_punished`.
    - Correctif proposé : ne jouer le joker que s'il y a quelque chose de nuisible à lever (`activeOn(…).any {
      it.harmful }`). Sinon, « garde ton joker ».

36. **MINEUR (objets, à trancher) : paris et récompenses.**
    - Un participant peut parier contre lui-même puis perdre exprès (`Bets.kt:85`) : par exemple un duelliste qui
      parie sur l'autre puis se déconnecte, ce qui fait gagner l'autre (`Duels.onLeave`). Correctif : un participant
      ne parie que sur lui-même.
    - Une récompense `give_item` tombe aux pieds quand l'inventaire est plein (`PlayerActionTools.kt:855-872`), et y
      disparaît en 5 min ou brûle. Or les gagnants d'un `obtain` ont souvent l'inventaire plein. Correctif :
      récompenses du moteur par `ItemLedger.owe`, comme les jokers.

37. **MINEUR (commandes) : un opérateur ou un bloc de commande peut accepter un duel, miser ou offrir à la place d'un
    joueur.**
    - Où : `DuelCommand.kt:50`, `BetCommand.kt:38` et `OfferCommand.kt:38` prennent `source.player`, c'est-à-dire
      l'entité visée par `execute as`. `run_command` est bien bloqué, y compris derrière `execute`.
    - Scénario : `execute as @a run duel accept C-52` dans un bloc de commande force un duel. `execute as Bob run offer`
      prend sa pile.
    - Correctif proposé : n'accepter que si l'émetteur d'origine est le joueur lui-même. `CommandSourceStack.source`
      est privé, sans accesseur en 26.3 (javap) : il faut un accesseur mixin. À défaut, documenter que les opérateurs
      sont de confiance.

38. **FINITION (ton) : `duel.lost` dit « Le Narrateur t'a laissé la vie, par pure bonté ».**
    - Cela suggère que la mort était possible, alors que l'invitation dit « personne n'y meurt » et que le prompt
      interdit de dire le vaincu mort. Sarcastique, pas glauque : à trancher par goût.
    - Proposition : « Tu perds le duel. Le Narrateur t'a rattrapé avant le sol, par pure bonté. »
    - Les autres textes de M4 (`fr.properties`, descriptions d'outils, lignes du moteur, section « Les mécaniques » de
      `director.md`) sont sobres. L'offrande n'y est jamais exigée.

39. **FINITION (pièges) :**
    - un verre poussé par un piston reste dans le monde pour toujours, puisque le `BlockRestorer` ne connaît que la case
      d'origine : à documenter ;
    - une toile cassée donne une ficelle, et la toile elle-même aux cisailles : jusqu'à 6 ficelles gratuites en
      sévérité 4 ou 5. À accepter, ou à neutraliser par `PlayerBlockBreakEvents.BEFORE` ;
    - « left as it is » pour une vitre déjà cassée (air), au lieu de « déjà partie » ;
    - un `punish` dont toutes les étapes échouent compte quand même dans `fair_share` ;
    - les jokers se transmettent d'un joueur à l'autre (à trancher).

40. **FINITION (objets) :**
    - un joker caché dans une boîte de Shulker est misé ou offert, puis détruit si l'offrande est prise
      (`ItemLedger.kt:128`, `:482`) ;
    - le SNBT en séquestre ne passe pas par le DataFixer : une mise à jour de Minecraft pendant un séquestre peut
      altérer l'objet (risque faible) ;
    - trace trompeuse « returned … at its fallback deadline (its settlement never came) » pour un propriétaire absent
      7 jours dont le règlement est bien venu ;
    - aucune commande d'opérateur pour rendre ou libérer une entrée du séquestre (`GuardCommands` est en lecture seule).

41. **FINITION (prompts, Chroniqueur) :**
    - `director.md:166` donne « échéance d'un défi » comme exemple de rappel, puis dit « pas plus qu'un défi du
      moteur » ;
    - `director.md:197` dit « `resolve` de celle-ci » pour une loi remplacée, alors qu'une loi `(enforced)` se retire
      par `remove_rule` ;
    - les raisons `judging` et `notified`, adressées au Directeur (« judge it now with end_challenge… »), arrivent
      telles quelles au Chroniqueur, qui n'a pas cet outil ;
    - `narrator_actions` (`views.sql:58-62`) ignore les actes du moteur, et `director.md` ne cite pas
      `engine_decisions` ;
    - une fenêtre faite d'une seule ligne `ENGINE` lance un lot du Chroniqueur, y compris pour un simple
      `ledger returned` d'une confiscation de M2. La KDoc de `Chronicler.kt:43` et son message l. 74 sont devenus faux ;
    - deux offrandes d'un même joueur prises à moins de 10 min d'écart (avec `offeringMinutes` < 10) : la seconde
      interaction est avalée par la déduplication sans preuve.

42. **FINITION (KDoc, code mort, duplication) :**
    - KDoc fausses :
      - `MechanicsState.returns` et `settlements` disent « offline or dead », alors que toute fin d'arène et tout
        règlement y passent ;
      - `ChallengeView.line` dit « situation line », alors que `end` y met la ligne de fin ;
      - la classe `Challenges` dit « each second and at LEAVE » ;
      - `MechanicPart.onEvent` dit « inline », `Challenges.onEvent` dit « Any thread » ;
      - `ItemLedger` (« a crash can only lose both or neither ») est contredite par le constat 1, et `transfer` comme
        `Escrow` (l. 29-30) par le constat 28 ;
      - `WakeScheduler.kt:59` dit « 10 minutes » ;
      - l'ordre d'éviction dans `Inbox.kt:86-91`.
    - Code mort :
      - `OutReason.DECLINED` n'est écrit nulle part, et `OutReason.LEFT` « or a duel » n'arrive jamais ;
      - la branche `Mechanic -> SOON` de `Inbox.kt:36` est redondante.
    - Duplication :
      - la fenêtre des paris est calculée à **trois** endroits (`Challenges.kt:280`, `Duels.kt:430`,
        `ChallengeTools.kt:79`) : constat connu, complété ;
      - l'analyseur `C-50`/`c50`/`50` existe en quatre copies (`DuelCommand.kt:66`, `MechanicsCommands.kt:185`,
        `Bets.kt:229`, `ChallengeTools.kt:146`), alors que `RuleTools` refuse un nombre nu ;
      - `writeCanon`, `cancelRefused`, `replace` et la recherche des joueurs par nom existent dans `Challenges` et dans
        `Duels` ;
      - `BetBook.giveBack`, `Challenges.close` et `Reconcile.drop` construisent les mêmes instructions et retours ;
      - `Challenges.resolve` réessaie sur un `MemoryRejected` « déjà fermée », d'où un `ERROR` inutile.

### Constats déjà connus, confirmés

- **`PairStats.write`** : confirmé dans les logs de cette relecture. L'écrivain du Journal a été tenu 1 542, 1 727,
  1 913 et 2 033 ms. Cela contribue aux délais des GameTests (constat 22).
- **`JoinBrief` « While away »** : pas de plans pour le joueur. Confirmé par la passation M4-08, non revu.
- **`jokersGivenAt` écrit directement dans la pièce jointe** : non revu. Le correctif du constat 18 ne doit pas
  refaire cette erreur.
- **Fenêtre des paris dupliquée** : trois copies, pas deux (constat 42).

## Vérifié, sans constat

- **Threads** :
  - aucun `runBlocking`, `.join()` ni attente bloquante dans `mechanics/**`, `guard/**`, `command/**` ;
  - `punish` et tous les actes du moteur passent par `actions.run`, suspendu, appelé dans `services.scope`. Aucun ne
    tourne sur le thread serveur, d'où aucun interblocage ;
  - les écritures du canon (`writeCanon`, `resolve`, `Reconcile`, `RuleBook`) sont hors du thread serveur ;
  - l'état n'est lu et écrit que sur le thread serveur (`check(isSameThread)`) ; ailleurs, seul l'instantané est lu ;
  - `Decisions.record` va dans une file non bloquante, et `trace.nextId` est un compteur en mémoire ;
  - `reach`, `survey`, les positions et `near` ne chargent aucun tronçon (`getChunkNow`). `putBack` et les arènes ne
    font que demander le chargement, puis attendent la seconde suivante ;
  - le seul gel mesuré est celui du constat 4.
- **Pièces jointes paresseuses** : les sept types (`mechanics`, `reminders`, `guard_state`, `mob`, `restorations`,
  `placed_blocks`, `visited`) sont touchés à l'initialisation du mod. Les trois démarrages réels de M4-23
  n'ont donné aucun « unknown attachment type ».
- **Interdits tenus** :
  - `run_command` refuse `pray`, `duel`, `bet` et `offer`, seules, derrière `execute … run`, `return run` et les
    redirections. La liste interdite l'emporte sur `extraAllowedCommands` ;
  - une règle ne peut appeler ni `run_command`, ni `give_item`, ni `take_item`, ni `create_rule`, ni `propose_duel` :
    refusé à la validation **et** à l'exécution ;
  - le déclencheur `chat` ne lit que le canal public, et son texte n'est jamais dans le fait, une décision ou un
    `notify` ;
  - les pièges ne se posent que dans l'air sans entité, en tout ou rien, sans gel au `CHUNK_LOAD`, avec la garde `ANY`
    armée. La cage se colle à la boîte du joueur ; ni verre ni toile n'étouffent ;
  - `butterfingers` ne permute que les cases 0 à 35 ; `smite` ne met pas le feu ; les slimes ne font aucun dégât ;
  - ordre des garde-fous : protégé, chaos 0, grâce, `no_pile_on`, `already_punished`, `fair_share`,
    `same_punishment`. Le tour 812 et l'acte -812 sont deux actes ;
  - le gage est de sévérité 1 ou 2 (schéma et `Stakes`), jamais pour un retardataire ni un absent, et passe par tous
    les garde-fous. Le gage d'un défi, une loi et un `punish` du Directeur ne se cumulent pas sur un joueur : le second
    acte est `already_punished` (constat 16 mis à part) ;
  - un duel exige deux `/duel accept` : un étranger reçoit `not_invited`, et le duel contre soi-même est refusé.
    `pvp`, la présence, la vie et la dimension sont revérifiés au départ. Le moteur n'a aucun `ALLOW_DAMAGE` : aucun
    PvP n'est permis hors duel. Le vide passe par `hurtServer` ; `generic_kill` ne vient que de `/kill` ;
  - une offrande n'est prise qu'après la faveur accordée, dans la même tâche sur le thread serveur. Une faveur
    refusée la laisse en séquestre.
- **Objets** :
  - `/bet` et `/offer` refusent avant toute prise, et `hold` écrit l'entrée avant de vider la main ;
  - les instructions sont écrites dans le même `update` que la sortie de l'état, sans doublon, et `release` comme
    `forfeit` sont idempotents ;
  - rien ne va au Narrateur ; tout est rendu pour `cancelled`, `expired` ou sans pari gagnant ;
  - les composants (enchantements, nom, contenu d'une Shulker) sont conservés par `ItemStack.CODEC`, hors constats 1
    et 40 ;
  - rien ne tombe au sol par le registre.
- **Jointures** :
  - `ENGINE_NEWS` : chaque décision attendue par M4-08 est écrite, avec une raison qui tient seule. `won`, `lost`,
    `expired` et `cancelled` sont dans `Challenges.kt:429` et `Reconcile.kt:80` ; `judging` à `:598` ; `notified` dans
    `RuleOnlyTools.kt:318` ; `joker` dans `Jokers.kt:168` ; `ledger held`, `offered` et `returned` dans
    `ItemLedger.kt:132`, `:208` et `:378` ;
  - `StakePayer.pay` et `BetBook.settle` sont appelés une seule fois par fin ;
  - les interactions du moteur portent leur `canon` ;
  - le rythme est revérifié dans `insert` ; la réconciliation ne résout jamais deux fois ;
  - l'ordre des parties de l'assemblage (défis, duels, paris, barres, règles) règle et redessine une fin dans sa
    seconde. `end` passe par `duels.end`, et un seul `EngineActions`, un seul `Stakes` et un seul `Bossbars` sont
    partagés ;
  - un défi échu pendant l'arrêt ne perd aucun objet (question 2).
- **Cache et coût** :
  - le Directeur a **44 outils** avec le moteur, 37 sans ; `survey` n'est qu'à l'Éclaireur, et les trois outils des
    règles ne sont à personne ;
  - `ToolEnv.mechanicsAvailable` est figé à l'ouverture d'un chapitre, et `MechanicsTool.isAvailable` est `final` ;
  - les descriptions sont statiques, et les sept définitions tiennent dans 13 045 caractères sur 14 000 ;
  - `director.md` fait **33 763 / 34 000** caractères (points de code). Les autres prompts sont dans leurs budgets.
- **Vie privée** :
  - aucun chemin de M4 ne lit `private_messages`. Les réponses de quiz lisent les canaux public et prière, et rien
    n'expose une prière aux autres joueurs ;
  - les plans secrets ne sortent que vers le Directeur, le Chroniqueur, l'Analyste, l'Éclaireur (par `sql`) et
    `/narrator rule list`.
- **Échappement** : toute ligne du moteur est ré-échappée (`ModelText`). Les arguments de `/bet` et `/duel` sont des
  `word()`, et les objets sont décrits par leurs identifiants.
- **Rappels** : la pièce jointe est lue sur le thread serveur et écrite par `runOnServerThread`, dans l'ordre. Un
  rappel échu pendant l'arrêt arrive en retard, et il est abandonné au-delà de 24 h (constat 5 mis à part).
- **Barres de boss** : retirées à la fin, au départ d'un joueur et à l'arrêt, puis redessinées depuis l'état.

## Lots de corrections

Chaque lot possède ses fichiers et les tests qui les couvrent. Aucun fichier n'est commun à deux lots de A à G. Le lot
H passe après A, C, D et F ; le lot I passe en dernier.

| Lot | Constats | Fichiers |
|---|---|---|
| **A — Séquestre et paris** | 1, 4, 20 (décodage), 28, 29, 36 (pari contre soi), 37, 40 | `guard/GuardState.kt` (codec de `Confiscation`), `guard/ItemLedger.kt`, `guard/Escrow.kt`, `mechanics/MechanicsState.kt` (codec, `Settlement` daté, KDoc), `mechanics/challenge/Bets.kt`, `command/BetCommand.kt`, `command/OfferCommand.kt`, `command/DuelCommand.kt` (seulement la source du joueur, 37), deux mixins accesseurs nouveaux (`PlayerList.save`, `CommandSourceStack.source`) et leur ligne dans `narrator2.mixins.json` ; tests `LedgerEscrowTest`, `MechanicsStateTest`, `BetSettlementTest`, GameTests `EscrowGameTests`, `BetGameTests` |
| **B — Défis** | 13, 14, 15, 24, 25 (défis), 26 (défis), 27, 36 (récompenses par `owe`) | `mechanics/challenge/Challenges.kt`, `Goals.kt`, `Answers.kt`, `Stakes.kt`, `mechanics/Reconcile.kt`, `tool/mechanics/ChallengeTools.kt` ; tests `GoalsTest`, `AnswersTest`, `StakesTest`, `ReconcileTest`, `ChallengeToolsTest`, GameTests `ChallengeGameTests`, `ChallengeStakesGameTests` |
| **C — Duels** | 3, 19, 21, 25 (duels), 26 (duels), 34 ; 18 côté duel, après le contrat du lot F | `mechanics/challenge/Duels.kt`, `tool/mechanics/DuelTools.kt` ; GameTests `DuelGameTests`, dont un test qui laisse tourner le serveur (constat 3) |
| **D — Règles** | 7, 8, 12, 30, 31 (côté règles : nom des fins, signal d'opérateur), 33 (règles), 22 (horloges ; tests de règles : explosions à l'écart, comité du Nether) | `mechanics/rule/RuleEngine.kt`, `RuleContext.kt`, `RuleValidator.kt`, `RuleOnlyTools.kt`, `RuleBook.kt`, `tool/mechanics/RuleTools.kt`, `mixin/policy/MobPickUpGuardMixin.java` ; tests `RuleEngineTest`, `RuleValidationTest`, `RuleMatcherTest`, GameTests `RuleEngineGameTests`, `RuleGameTests` |
| **E — Pièges** | 2, 39 (pièges) | `guard/BlockRestorer.kt`, `mechanics/traps/TrapShapes.kt` ; GameTests `TrapGameTests` |
| **F — Garde-fous et catalogue** | 10, 11, 16, 17, 18 (contrat et côté garde-fous), 33 (`random_radius`, `glowing`, part de parole), 35, 39 (`punish` sans effet), `jokersGivenAt` (connu) | `guard/Guards.kt`, `guard/GuardsImpl.kt`, `guard/DeathGuard.kt`, `guard/TargetingPolicy.kt`, `guard/Episodes.kt`, `guard/CreatureShield.kt`, `mixin/policy/MobTargetGuardMixin.java`, `tool/action/NarratorExplosionDamage.kt`, `tool/action/WorldActionTools.kt`, `tool/action/PlayerActionTools.kt`, `tool/speech/SpeechLimiter.kt`, `mechanics/punish/PunishmentCatalog.kt`, `mechanics/punish/Primitives.kt`, `tool/action/PunishTools.kt`, `mechanics/Jokers.kt`, `testing/FakeGuards.kt` ; tests `TargetingPolicyTest`, `EpisodesTest`, `PunishmentCatalogTest`, GameTests `PunishGameTests`, `GuardPolicyGameTests`, `GuardFavorGameTests`, `PlayerActionGameTests`, `WorldActionGameTests` |
| **G — Directeur, mémoire, coût** | 5, 6, 31 (côté `ENGINE_NEWS`), 32, 41, `PairStats` et `JoinBrief` (connus) | `director/Director.kt`, `director/WakeScheduler.kt`, `director/Inbox.kt`, `director/DirectorControl.kt` (si le sous-budget l'exige), `memory/chronicle/ExchangeLog.kt`, `memory/chronicle/Chronicler.kt`, `memory/Dedup.kt`, `memory/JoinBrief.kt`, `memory/stats/PairStats.kt`, `resources/narrator2/sql/views.sql`, `prompts/director.md`, `prompts/chronicler.md` ; tests `DirectorMechanicsScenariosTest`, `WakeBudgetScenariosTest`, `WakeSchedulerTest`, `ExchangeLogTest`, `ChroniclerTest`, `DedupTest`, `JoinBriefTest`, `PairStatsTest`, `PromptsTest`, `PromptToolMentionsTest` |
| **H — Assemblage, moteur désactivé, stabilité** (après A, C, D et F) | 9, 22 (lot `assembly`, `DirectorEndToEndTests`, puis cinq lancements verts), 23, question 3 | `mechanics/MechanicsService.kt`, `mechanics/MechanicsModule.kt`, `mechanics/Mechanics.kt` (`DisabledMechanics` et son gardien), `mechanics/EngineActions.kt` (si besoin), GameTests `MechanicsAssemblyGameTests`, `test_environment/assembly.json`, `DirectorEndToEndTests` ; les fonctions extraites de `Duels` (`putBack`), de `Bets` (instructions) et de `RuleEngine` (`unmark`) sont fournies par C, A et D |
| **I — Finition** (en dernier) | 38, 42 (KDoc, code mort, duplications : fenêtre des paris, analyseur de références, `writeCanon`…) | les fichiers cités, une fois les autres lots fusionnés ; `resources/narrator2/ui/fr.properties` (`duel.lost`) |

Ordre conseillé :
- **A, E et C portent les trois bloquants** et peuvent partir tout de suite, en parallèle. C prend le contrat du
  constat 18 au lot F (une méthode `Guards` qui marque une mort refusée) : soit F le pose d'abord en une petite
  étape, soit C attend F pour cette seule ligne.
- **B, D, F et G** partent en parallèle des trois premiers. D et G partagent le contrat du constat 31 (le nom des
  décisions de fin des règles) : le fixer dans les deux missions.
- **H** ensuite : il assemble le gardien du moteur désactivé à partir de ce que C, A et D extraient, puis rend
  `runGameTest` vert cinq fois de suite.
- **I** en dernier.

## Décisions demandées au superviseur

- **C3** : bloquant (« personne ne meurt d'un duel ») sur la foi du mécanisme vérifié, ou important en attendant une
  vérification avec un vrai client ?
- **C8** : interdire `levitation` aux règles, ou rendre leurs créatures insensibles aux chutes ? Faut-il des bornes
  d'attributs propres aux règles ?
- **C12** : les outils hostiles des règles ne visent jamais un joueur frappé (`at subject`) ? Et `teleport to_player`
  dans une règle ?
- **C13** : un `judged` échu pendant l'arrêt passe en `JUDGING` ; faut-il aussi appliquer le verdict acquis aux autres
  objectifs ?
- **C16** : un seul `punish` par acte et par joueur ?
- **C17** : les créatures du Narrateur ne visent plus que des joueurs ?
- **C19** : un duel tranché par un tiers est-il annulé ? `hostile` refuse-t-il un duelliste ?
- **C36** : un participant ne parie-t-il que sur lui-même ?
- **C37** : accesseur mixin pour `CommandSourceStack.source`, ou opérateurs de confiance ?
- **C38** : nouveau texte de `duel.lost` ?

## Décisions du superviseur (19/10/2026)

Toutes les propositions de correctif sont acceptées, sauf ce qui suit.

- **C3** : **bloquant.** La protection d'un duelliste épargné dure tant qu'un danger persiste (feu, lave, vide, chute en cours), avec 10 s au moins. Le duelliste est éteint, et retiré de la lave ou du vide, avant que la protection tombe.
- **C8** : `levitation` est interdite aux règles. Les attributs posés par une règle ont des bornes propres : `scale` entre 0,5 et 2, jamais de vie maximale réduite, jamais de dégâts de chute provoqués.
- **C12** : dans une règle, les outils hostiles ne visent jamais la victime d'un coup entre joueurs, seulement l'auteur du fait. `teleport to_player` est interdit dans une règle.
- **C13** :
  - Un concours jugé échu pendant l'arrêt passe en `JUDGING` au redémarrage, et `JUDGE` est renvoyé.
  - Pour les autres objectifs, si au moins un participant avait fini (`doneAt`) avant l'arrêt, le défi est gagné par ces participants, avec leurs récompenses et sans gage pour les autres.
  - Sinon, il est annulé sans enjeux, et les paris sont rendus.
- **C16** : un seul `punish` par acte et par joueur.
- **C17** : les créatures du Narrateur ne visent que des joueurs, jamais des villageois ni des animaux.
- **C18** : le contrat des garde-fous (protection après un sauvetage ou une mort refusée) va au lot F. Le côté duel est branché par le lot H.
- **C19** :
  - Un duel dont l'issue est décidée par un tiers (coup d'un autre joueur, du Narrateur ou d'une créature du Narrateur) est **annulé**, et les paris sont rendus.
  - `hostile` refuse de viser un duelliste pendant son duel.
- **C31** : les fins de règles s'écrivent dans une décision `mechanic rule_ended`, avec `detail.ending` valant `expired`, `disabled`, `removed` ou `fulfilled`.
  - Le lot D l'écrit.
  - Le lot G l'ajoute à `ENGINE_NEWS`.
  - Un `RULE` non prioritaire part quand un opérateur retire une règle.
- **C36** : un participant ne parie que sur lui-même.
- **C37** : on ajoute un accesseur mixin pour `CommandSourceStack.source`. `/duel accept` et `/bet` n'agissent qu'au nom du joueur réel, même derrière `execute as`.
- **C38** : le lot I réécrit `duel.lost` : railleur, jamais glauque.
