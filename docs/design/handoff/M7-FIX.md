# Passation M7-FIX : le `stop` de la console, des champions qui tuent, et la relecture de M7

Passations lues : M7-BASE, M7-RELICS. **En cas de contradiction, le code et cette passation font foi.**

## 1. Le `stop` de la console : un bug vanilla, pas le mod

**Cause.** Dans `DedicatedServer.initServer`, le fil « Server console handler » démarre **avant** `loadLevel()`. Il lit
stdin et appelle `createCommandSourceStack()` **au moment de la lecture**. Avant le chargement du monde,
`findRespawnDimension()` (puis `overworld()`) rend `null`. La source de la commande garde donc un niveau nul, et
l'exécution, au premier tick, échoue dans `Commands.executeCommandInContext` (ligne 432 de 26.3) :

```
Command exception: /stop
java.lang.NullPointerException: Cannot invoke "net.minecraft.server.level.ServerLevel.getGameRules()" because the return value of "net.minecraft.commands.CommandSourceStack.getLevel()" is null
	at net.minecraft.commands.Commands.executeCommandInContext(Commands.java:432)
	at net.minecraft.commands.Commands.performCommand(Commands.java:370)
	at net.minecraft.commands.Commands.performPrefixedCommand(Commands.java:356)
	at net.minecraft.server.dedicated.DedicatedServer.handleConsoleInputs(DedicatedServer.java:412)
```

Une entrée redirigée (`< stop.txt`) livre la ligne tout de suite, donc toujours avant le monde. Une ligne tapée à la
main ou envoyée par un panneau d'hébergement **pendant le démarrage**, avant le chargement du monde, échoue de la même
façon (c'est la même lecture).

**Preuves** (journalisation de debug de `net.minecraft.commands.Commands` par un fichier log4j2) :
- Avec le mod (sans clé), `stop` redirigé : la trace ci-dessus, puis le serveur continue à tourner.
- **`server.jar` vanilla seul** (ni Fabric, ni mod), `stop` redirigé : **la même trace**, et le serveur ne s'arrête
  pas non plus.
- Avec le mod, `stop` envoyé **après le démarrage**, comme un opérateur au clavier (le script écrit dans un tube 5 s
  après `[Narrator] ready`) : « Stopping the server », les arrêts du Narrateur, les sauvegardes, « Journal closed in
  26 ms », « stopped », puis le processus sort avec le code 0.
- Bytecode 26.3 vérifié (`javap -c`) : le fil console est lancé à l'offset 32 de `initServer`, et `loadLevel` est appelé
  à l'offset 482.

**Rien à corriger dans le mod.** Pour les scripts : envoyer `stop` après `Done` (ou après `[Narrator] ready`), ou
passer par RCON. N.B. : le dossier `boot-graves` de la reproduction contenait aussi FarmersDelight ; mes essais se sont
faits sans aucun autre mod que Fabric API et Fabric Language Kotlin.

## 2. Les champions peuvent tuer

- `GuardsImpl.allowDeath` : si le coup fatal vient d'un champion (`CreatureShield.isChampions` : le champion, ou ce
  qu'il a tiré, porte le tag `narrator2_champion`, `RelicsService.TAG`) **et** qu'il reste du quota
  (`DeathGuard.quotaLeft(player)`), la mort passe par le garde-fou de mort ordinaire (`DeathGuard.allowDeath`). Le
  joueur meurt, garde son inventaire et son expérience (règle de toute mort causée par le Narrateur), et la mort compte
  dans le quota (`afterDeath`). Le champion est retiré avec la mort de son joueur, comme avant.
- Au-delà du quota, rien ne change : sauvetage sur place, joueur protégé 5 min, champion renvoyé.
- Les autres protections restent en amont, sur les dégâts (`CreatureShield.allowsDamage`) : joueur protégé, mort
  récente, grâce, bouclier d'un sauvetage, duel. Aux niveaux de chaos 1 et 2, le quota est de 0 : les champions n'y
  tuent toujours pas.
- Textes mis à jour : la description de `summon_champion` (« its fatal blow kills within your quota of deaths, else it
  is a rescue ») et `armorer.md` (« Ses coups peuvent tuer »).

## 3. Les champions durent 30 minutes

- `relics.championMinutes` vaut 30 par défaut, avec des bornes de 1 à 60.
- `spawn` reste borné à 10 minutes par son schéma : le service l'appelle avec `min(championMinutes, 10)`, puis
  `NarratorMobs.extend` repousse l'échéance du champion (et de sa monture ou de ses cavaliers éventuels) jusqu'à la fin
  de sa fiche. La marque persistante et le registre sont tous deux mis à jour. Rien d'autre n'impose 10 minutes à une
  créature déjà marquée. Le retrait à la mort ou au départ du joueur visé ne change pas.
- **Non prolongés, et c'est voulu** (pour rester minimal) : l'épisode du châtiment (`guards.hostile`) et l'armement du
  garde-fou de mort restent à 10 minutes. Au-delà, le Directeur peut de nouveau punir ce joueur pendant que le champion
  le chasse, et une mort que le champion ne cause pas directement (une chute après un recul, par exemple) n'est plus
  attribuée au Narrateur. Les coups du champion lui-même restent toujours attribués.
- **Configurations existantes** : un `narrator2.json` écrit depuis M7-RELICS contient `"championMinutes": 10`, qui
  l'emporte sur le nouveau défaut. Le passer à 30 à la main.

## 4. Relecture de M7 (`51b719e..main`)

### Corrigé

| Gravité | Problème | Correctif |
|---|---|---|
| **Haute** | Un **coffre-fort (vault) actif** des trial chambers tire sa table (`chests/trial_chambers/reward…`) **chaque seconde** pour choisir l'objet qu'il affiche, puis jette ce tirage (`VaultBlockEntity.Server.getRandomDisplayItemFromLootTable`, vérifié dans le bytecode ; `MODIFY_DROPS` enveloppe `getRandomItemsRaw`, donc ce tirage passe aussi par l'évènement). Un joueur qui restait près d'un vault vidait les réserves (environ 2 reliques, 5 consommables et 3 livres par minute, perdus) et relançait sans fin des réassorts payants (≈ 0,30 $ chacun). | `onChestLoot` ne puise dans les réserves que pour un tirage fait pour un joueur (`THIS_ENTITY` est un `ServerPlayer` : un coffre qu'il ouvre, la récompense d'un vault). Un coffre vidé par un entonnoir ou une explosion, ou un `/loot` lancé depuis la console, ne reçoit plus rien des réserves. |
| Moyenne | Les fiches de champion recevaient `max(id) + 1` en base, alors que le service mettait à jour son propre compteur (`nextChampion`). Deux champions enregistrés dans le désordre, ou un identifiant perdu (un champion disparu à l'habillage, une écriture échouée), décalaient tout : la mort, la relique et le départ étaient alors notés sur la mauvaise fiche, et une fiche pouvait rester `active` à tort. Au redémarrage, ce champion fantôme comptait dans les plafonds jusqu'à son échéance. | `RelicsStore.addChampion` insère l'identifiant du service. |
| Moyenne | Rien n'empêchait l'Armurier d'équiper un champion d'élytres, d'un totem, d'une pomme dorée enchantée, d'une balise, d'une étoile du Nether, d'une boîte de Shulker… Cet équipement tombe entier entre les mains du tueur, sans le rationnement de `give_item`. | `RelicItems.gear` refuse tout objet qui vaut une heure de points (`ItemSpecs.preciousness`), sauf une base de relique (armes, outils, armures, bouclier, arc, arbalète, trident, masse : le diamant reste permis). `armorer.md` le dit. |

### Restant (non corrigé)

| Gravité | Point |
|---|---|
| Basse | Épisode du châtiment et armement du garde-fou de mort à 10 minutes pour un champion de 30 minutes (voir §3). |
| Basse | `summon` vérifie les plafonds, puis ajoute le joueur à `designing` : deux commandes simultanées pour deux joueurs différents peuvent dépasser `maxChampions` d'une unité. |
| Basse | Un objet tiré d'une réserve n'est marqué `found` en base qu'après coup (hors du thread serveur). Si le serveur plante entre-temps, l'objet revient dans la réserve au redémarrage : il existe alors deux fois. |
| Basse | Un coffre trop plein (vanilla jette ce qui dépasse les cases libres) pourrait perdre un objet tiré. C'est négligeable : 27 cases, 3 objets ajoutés au plus. |
| Info | Quand un champion tue son joueur, le Directeur reçoit « … is gone, unslain: sent away before its time (its player died, left or was pardoned) », en plus de la mort du joueur elle-même. |

Rien trouvé qui fasse attendre le thread serveur sur le réseau ou la base, ni de fuite : les tâches de l'Armurier sont
annulées à l'arrêt, et leur conversation est fermée dans un `finally`. Rien trouvé non plus côté M7-BASE : les mixins, la
lecture de la config à `SERVER_STARTING` et `thinChest` ne touchent que les diamants et les pommes dorées enchantées.

## Vérifications

- **GameTest** `PunishmentCreaturesGameTests.aChampionKillsWithinTheQuotaOfDeaths` : un zombie de `spawn` étiqueté
  champion est prolongé à 30 minutes (la marque et le registre le disent). Son coup fatal tue Pistache : la mort est
  marquée (inventaire gardé) et comptée, puis le champion part. Bartok, dont le quota est épuisé, est sauvé sur place
  avec 3 cœurs, et ce champion-là est renvoyé.
- **`build --continue`** sur la fusion de `main` (`d36f168`) : 2 313 tests unitaires sans échec ni test ignoré, et
  « All 406 required tests passed » pour les GameTests, en 10 min 03 s.
- **Démarrages réels** (`TheServer/` du worktree, lanceur Fabric de production, port 25603, `eula=true`, seuls Fabric
  API et Fabric Language Kotlin à côté du mod) :
  - `stop` redirigé : la trace ci-dessus, et le serveur ne s'arrête pas (comme `server.jar` vanilla seul).
  - Avec le jar final, sans clé, `stop` tapé 5 s après `ready` : arrêt propre (sauvegardes, « Journal closed in 27 ms »,
    code de sortie 0).
  - Avec le jar final, Narrateur actif (clé, tunnel déjà ouvert que je n'ai ni ouvert ni fermé), `stop` tapé 5 s après
    `ready`, pendant le réassort de démarrage de l'Armurier (sa deuxième requête était en cours) : l'appel est annulé
    (« armorer-… shutdown in 5s: cancelled »), puis l'arrêt est propre (« Journal closed in 42 ms », code 0). Coût : deux
    requêtes de l'Armurier, dont une annulée, soit quelques centimes.
- La clé n'apparaît dans aucun fichier : vérifié par une recherche exacte, sans l'afficher, dans `TheMod/src`, `docs/`,
  la sortie du serveur et `TheServer/`.
