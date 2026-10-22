# Passation M7-RELOAD : la difficulté relue à chaud, creepers plus rapides

- **`/narrator reload` relit la section `difficulty`** et l'applique aussitôt (`Difficulty.reload`, sur le thread
  serveur) : le porteur `@Volatile Difficulty.config` est remplacé, puis toutes les créatures chargées de toutes les
  dimensions passent par `onMobLoad` (idempotente ; même part de vie ; `enabled: false` retire les bonus). Dégâts,
  régénération et coffres changent dès le prochain coup, tick ou coffre ; les minerais pour les chunks générés ensuite.
  Les mixins lisaient déjà `Difficulty.config` à chaque appel : rien à y changer.
- La réponse ajoute une ligne grise : `[Difficulty] on: <résumé> · N loaded creatures reconciled.` Section invalide :
  `runtime.reload()` échoue comme pour toute section, les réglages en vigueur restent.
- `/narrator chaos <n>`, qui relit tout le fichier, applique aussi la difficulté (sans ligne dans sa réponse).
- `relics` était déjà relue à chaud (`RelicsService` lit `settings.get().config.relics` à chaque usage) : rien changé.
- **Nouveau réglage `mobSpeed`** (demande du propriétaire) : type de créature (chemin dans `entity_type`) → facteur de
  vitesse, défaut `{"creeper": 2.0}`, bornes 0,5 à 3. Modificateur permanent `narrator2:difficulty/movement_speed`
  (`ADD_MULTIPLIED_TOTAL`, facteur − 1) sur `MOVEMENT_SPEED`, posé à `ENTITY_LOAD`, retiré au facteur 1, hors de la
  table ou socle coupé, réconcilié par le reload. Il touche tout `Mob` du type listé, boss compris si on en liste un.
  Pas de conflit avec les règles : leurs bornes ne comptent que `rule/…` et `module/…` (`MarkOwner.isOwners`), et
  leurs facteurs se multiplient avec le nôtre (creeper ×2 du socle × ×3 d'une règle = ×6, accepté).
- `mobSpeed` est **libre** (`FREE_FORM` de `ConfigStore`) : on peut ajouter un type, mais le défaut `creeper` reste
  tant que le fichier ne le fixe pas ; `"creeper": 1.0` lui rend sa vitesse vanilla. Un type absent du registre est une
  erreur de configuration. Seuls les types hors des défauts sont cherchés dans le registre : les tests unitaires qui
  chargent une configuration n'ont donc pas besoin d'amorcer Minecraft (`DifficultyTest` l'amorce pour tester l'erreur).
- **Piège des essais `runServer`** : la tâche injecte la vraie clé (`.secrets/llmgw.key`) ; avec le tunnel ouvert,
  l'Armurier remplit aussitôt ses réserves avec Opus (4 appels réels ici avant l'arrêt). Pour un essai sans Claude,
  mettre `gateway.apiKeyEnv` sur une variable inexistante dans le `narrator2.json` de `TheServer/`.
- `onMobLoad` renvoie désormais un `Boolean` (créature modifiée ou non). Résumé et briefing : `movement speed creeper x2`.
  GameTests : un creeper neuf est ×2, un zombie non ; `aReloadReconcilesTheLoadedCreatures` (zombie ×1,5 → ×3, creeper
  sorti de la table, socle coupé, puis retour, le tout dans un seul appel).
