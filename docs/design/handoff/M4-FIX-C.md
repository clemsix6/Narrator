# Passation M4-FIX-C : duels (relecture de M4, constats 3, 19, 21, 25, 26 et 34)

Lot C des corrections de la relecture de M4 (`design/reviews/M4_REVIEW.md`), selon les décisions du superviseur (C3,
C19 ; C18 côté duel n'est **pas** dans ce lot). Il corrige le **bloquant n° 3** : un perdant de duel pouvait mourir
après la fenêtre de 5 s, alors que **personne ne meurt d'un duel**. **En cas de contradiction avec `M4.md` §5.5 ou avec
la passation M4-19, le code et cette passation font foi.**

## Ce qui change

| Fichier | Changement |
|---|---|
| `mechanics/challenge/Duels.kt` | protection après le duel (`protect`, `Hazard`), épargne ou sauvetage selon l'endroit, duel annulé quand un tiers le tranche (`outsider`), `isDueling`, retours extraits dans **`DuelReturns`** (même fichier), détails des constats 25, 26 et 34 ; `DuelLines.outside`, `DuelLines.notBack` |
| `tool/mechanics/DuelTools.kt` | description de `propose_duel` : une blessure mortelle venue d'en dehors du duel l'annule |
| `mechanics/MechanicsTypes.kt` (**hors liste**, une constante) | `DUEL_RESISTANCE_SECONDS = 5` devient **`DUEL_PROTECTION_SECONDS = 10`** (décision C3) |
| `mechanics/MechanicsState.kt` (**hors liste**, un champ) | `SavedPosition.savedAt` (`saved_at`, epoch ms, facultatif), pour dater les retours (constat 21) |
| tests | `DuelLinesTest` (+1 : lignes `outside` et `notBack`, budgets) ; `DuelGameTests` (+15, et `isDueling` vérifié dans le premier test) |

`MechanicsState.kt` est au lot A : je n'y ai ajouté qu'un champ à `SavedPosition`, à l'écart de `Settlement` et du
codec. `MechanicsTypes.kt` n'a pas de propriétaire : une constante renommée, utilisée seulement par `Duels`.

## Constat par constat

### 3 (bloquant) : personne ne meurt d'un duel, même après la fenêtre

- **La protection après le duel** (`Fight.over`, `protect`, à chaque tick) commence quand le duel quitte l'état,
  quel qu'en soit le chemin, pour **les deux** duellistes. À chaque tick : feu éteint, air rendu, effets nuisibles du
  combat retirés (pas ceux du registre du Narrateur), et **sortie de la lave ou du vide** par le sauvetage de la garde
  de mort (`Guards.rescue`, raison `after duel C-52: lava`). Toute mort de l'un d'eux reste refusée tant qu'elle
  dure (épargne ou sauvetage, comme avant, sans rien finir).
- **Durée** : `DUEL_PROTECTION_SECONDS` (10 s) au moins, **et tant qu'un danger persiste** ; 2 minutes au plus
  (`PROTECTION_MAX_MS`), après quoi un duelliste encore en danger est sauvé, puis la protection tombe. Un duelliste
  hors ligne garde sa protection jusqu'à son retour (en mémoire : un redémarrage l'oublie).
- **Les dangers** (`Hazard`, lus dans les blocs eux-mêmes, pas dans l'état laissé par le dernier tick du joueur) :

  | Danger | Quand | Au coup fatal de l'adversaire | Pendant la protection |
  |---|---|---|---|
  | `lava` | lave dans la boîte du joueur ou sous ses pieds | sauvetage au lieu d'épargne | sauvé aussitôt |
  | `the void` | sous le monde, ou en l'air sans rien que de l'air jusqu'au fond (ni élytres ni vol) | sauvetage | sauvé aussitôt |
  | `a dangerous block` | feu, magma, cactus, baies, rose du wither, neige poudreuse (`EntityType.isBlockDangerous`) | sauvetage | prolonge seulement |
  | `fire` | le joueur brûle | épargne (il est éteint) | éteint à chaque tick |
  | `a fall` | en l'air, distance de chute non nulle | épargne | prolonge jusqu'à l'atterrissage |

  Un spectateur n'a aucun danger ; un joueur en créatif, seulement le vide.
- **L'épargne** donne la résistance V pendant `DUEL_PROTECTION_SECONDS` (10 s au lieu de 5).
- **Fire Aspect** : l'épée rallume le perdant juste après l'épargne (la mort refusée laisse `hurtServer` rendre vrai) ;
  le tick suivant l'éteint, et il ne brûle plus.

### 19 : un duel tranché par un tiers est annulé

- `outsider` : celui qui a décidé de la mort, vu d'en dehors du duel :
  - un **autre joueur** : l'entité du coup, ou à défaut le dernier joueur qui l'a blessé (`getKillCredit`), ce qui
    couvre « poussé dans la lave » ;
  - une **créature du Narrateur** (`CreatureShield.isNarratorsDoing`, l'entité ou le projectile) ;
  - le **Narrateur** lui-même, quand sa garde de mort couvre la cause (`Guards.deathAttribution(...).narrator`).

  L'adversaire, le monde et le joueur lui-même ne sont pas des tiers.
- Le duelliste est sauvé, et le duel **annulé** : pas de récompense, toutes les mises rendues (`Challenges.finish`
  avec `CANCELLED`), `duel.cancelled` aux deux, ligne `C-52 cancelled: Cid interfered; Bartok would have died
  (player_attack by Cid) and was rescued instead` (`the Narrator`, `the Narrator's zombie`). Pas de clé de texte
  nouvelle.
- **`Duels.isDueling(uuid)`** : vrai du départ du duel à sa fin, pas pendant l'invitation ; une simple lecture de
  `fighting`, sur le thread serveur. C'est ce que demande le contrat `Guards.isDueling` du lot F (le refus `duel` de
  `hostile` et l'épargne des créatures). **Le branchement est au lot H** (voir plus bas).
- Limite : une lave versée par un tiers sous un duelliste, **sans l'avoir frappé**, ne laisse aucune trace : c'est
  alors une mort « du monde », et l'adversaire gagne.

### 21 : retours d'arène

- `DuelReturns` (extrait de `Duels.putBack`) :
  - remet chaque duelliste sur un sol sûr **à 8 blocs au plus** de la hauteur où il se tenait : le plus haut en
    dessous, sinon le plus proche au-dessus ;
  - **sinon à son point de réapparition** (lit, ancre ou point du monde, comme le sauvetage), jamais à la position
    exacte. Il n'est donc jamais posé au-dessus du vide ou dans la lave, ni dans une poche 50 blocs plus bas ;
  - même chose pour une dimension disparue.
- `SavedPosition.savedAt` est posé au départ du duel. Un retour de **plus de 24 h** est abandonné, avec une décision
  `mechanic return_dropped`, sujet le joueur, raison `not put back where they stood before their duel (x …, y …, z …
  in overworld): that was 1d1h ago, more than 1d0h; left where they are`. Un retour sans date (état d'avant ce lot)
  est remis comme avant.

### 25 (duels) : départ interrompu après l'écriture du canon

Après `writeCanon`, la suite (`settle`) tourne sous `NonCancellable` : un délai d'outil ou un arrêt n'arrête plus
l'insertion. Toute exception de l'insertion (thread serveur trop lent ou arrêté, erreur) annule la ligne du canon
(`cancelRefused`) et revient en `Invalid arguments: the duel could not be set up (…); try again`, sauf si le duel est
entré dans l'état malgré tout.

### 26 (duels) : spectateurs et créatifs

Un duelliste doit pouvoir être blessé :
- refus à la proposition (`Invalid arguments: Bartok is in creative mode: a duelist must be able to get hurt
  (survival or adventure mode)`) ;
- annulation au départ (`unfit`) ;
- annulation en cours de duel, à la seconde, si un opérateur change son mode (`cancelled: Bartok is in creative mode`).

### 34 : détails

- **Départ qui n'arrive jamais** : `awaitLoaded` est dans le `try`, et `starting` (un ensemble concurrent) est vidé
  dans un `finally`. Échéance dure : un duel accepté par les deux et pas commencé **30 s** après la fin de son
  invitation est annulé (`cancelled: both accepted, but it could not start within 30s of the invitation's end`).
- **Invitation terminée `won` ou `lost`** : `Duels.end` refuse (`Invalid arguments: C-52 is only an invitation, not
  accepted by both: nobody can win or lose it; end it cancelled`) ; `cancelled` reste permis.
- **Duelliste qui ne revient pas après un redémarrage** : une minute après le démarrage du moteur
  (`RESTART_GRACE_MS`), un duelliste absent est parti : l'autre gagne (`… left the server`). Quand les deux sont
  absents, le duel est annulé (`cancelled: neither Bartok nor Mimosa came back after the restart`).
- **Pas de pression** : un joueur qui a refusé un duel, ou laissé passer une invitation sans l'accepter, n'en reçoit
  pas d'autre pendant **10 minutes** : `DENIED (duel_declined): Bartok turned a duel down 3m ago; nobody is pressed to
  fight: ask them again at 21:40 (in 7m)`. En mémoire (un redémarrage l'oublie).

## Écarts et décisions

1. **`DUEL_RESISTANCE_SECONDS` renommée `DUEL_PROTECTION_SECONDS`, 10 s** : c'est la durée minimale de la
   protection et celle de la résistance V de l'épargne.
2. **Deux sortes de dangers** : ceux dont l'endroit même blesse (lave, vide, bloc dangereux) font **sauver** le
   perdant au lieu de l'épargner. Pendant la protection, seuls la lave et le vide font sauver le joueur aussitôt (« retiré
   de la lave ou du vide ») : un bloc dangereux, un feu ou une chute la prolongent seulement. S'ils tuent, la mort est
   refusée et le joueur sauvé.
3. **Un plafond de 2 minutes** évite une protection sans fin (un joueur qui reste dans un feu avec une potion de
   résistance au feu).
4. **Le dernier coup d'un tiers compte** (`getKillCredit`) quand la cause n'a pas d'entité. Le dernier coup de
   l'adversaire ne change rien : il gagne, comme avant.
5. **Le sauvetage d'un duel compte dans `rescues`** et pose le bouclier de 5 min du lot F (`Guards.rescue` le fait
   seul).

## Ce que le lot H doit savoir

### `DuelReturns` pour le gardien du moteur désactivé (constat 9)

```kotlin
class DuelReturns(store: StateStore, decisions: Decisions, clock: Clock) {
    fun putBack(server: MinecraftServer, busy: (UUID) -> Boolean = { false })   // thread serveur
    companion object { const val RETURN_DROPPED = "return_dropped"; const val MAX_AGE_MS = 24 h }
}
```

- Sans moteur : `DuelReturns(store, Decisions(services.trace, clock), clock).putBack(server)` chaque seconde, après
  que le gardien a passé les duels de l'état en retours (`Reconcile.drop`). `busy` vaut faux par défaut, puisqu'aucun
  duel ne tourne. Aucune base n'est lue ; la décision `return_dropped` passe par la trace.
- Un joueur hors ligne attend ; un tronçon non chargé est demandé, et le joueur part à l'appel suivant.
- Les retours écrits avant ce lot n'ont pas de date : ils sont remis quel que soit leur âge.

### C19 : brancher `Guards.isDueling`

Au démarrage du moteur, `services.guards.isDueling = { duels.isDueling(it) }`, et `{ false }` à son arrêt (contrat
du lot F). `Duels.isDueling` est une lecture d'une table, sur le thread serveur : sans coût pour `spares`.

### C18 côté duel : le bouclier après l'épargne

- Dans `Duels.allowDeath`, juste après `spare(player)` (coup fatal de l'adversaire, 1 PV sur place), ajouter
  `services.guards.shield(player, clock.millis() + Guards.RESCUE_SHIELD_MS, "duel $ref: fatal blow stopped at 1 hp")`.
  Le sauvetage (`Guards.rescue`) pose déjà le sien.
- La fenêtre contre l'ex-adversaire : la protection de ce lot refuse déjà **toute** mort des deux duellistes pendant
  10 s au moins (un nouveau coup fatal de l'ex-adversaire est une nouvelle épargne, sans résistance de plus). Pour la
  porter à 30–60 s contre l'ex-adversaire seulement, comme le propose la relecture, il suffit de garder l'entrée de
  `fighting` plus longtemps pour ce cas.

### Pour les GameTests de l'assemblage

- La protection dure **10 s d'horloge réelle** après chaque duel du vrai moteur : un second duel des mêmes joueurs
  qui commence dans ces 10 s remplace simplement l'entrée.
- Les joueurs de test rejoignent en **créatif** (mode par défaut du serveur de GameTest) : un duel est maintenant
  refusé tant qu'ils n'ont pas `makeVulnerable()`. `MechanicsAssemblyGameTests` le fait déjà avant ses duels.
- Les GameTests de duel ont un banc qui **laisse tourner le serveur** (`DuelGameTests.ServerTicks`) :
  - chaque tick du serveur, l'horloge du moteur avance d'un tick (60 ms, un serveur chargé), les joueurs du test font
    leur `doTick` (comme les fait faire la connexion d'un vrai client), puis le moteur tourne ;
  - la boucle de `doTick` à l'intérieur d'un seul tick ne laisse jamais tomber le temps de recharge des dégâts
    (`invulnerableTime` ne décroît que dans `Entity.commonTick`, appelé par le monde), d'où un seul dégât de feu en
    8 s : c'est la cause du « témoin » de la relecture et de l'instabilité du duel de l'assemblage (constat 22) ;
  - à 50 ms par tick, l'ancien code survivait à la lave par hasard : la résistance (100 ticks) et la fenêtre (5 000
    ms) finissaient au même tick.

## Vérifications

- Signatures vérifiées avec `.research/jp.ps1` (une classe par appel) et le bytecode quand il fallait :
  - `LivingEntity.getKillCredit`, `getLastHurtByPlayer`, `isFallFlying` ;
  - `Entity` : `commonTick` décrémente `invulnerableTime`, `baseTick` (feu tous les 20 ticks, `isInLava`),
    `lavaHurt`, `onGround`, `fallDistance` ;
  - `ServerGamePacketListenerImpl.tickPlayer` → `ServerPlayer.doTick` (seul appelant) ;
  - `EntityType.isBlockDangerous`, `FluidState.is(TagKey)` (par `TypedInstance`), `FluidTags.LAVA`,
    `BlockPos.betweenClosed(AABB)`, `AABB.deflate`/`expandTowards` ;
  - `ServerPlayer.gameMode()`, `GameType.getSerializedName`, `Player.isSpectator`/`isCreative`/`getAbilities`,
    `Abilities.flying` ;
  - `ServerPlayer.findRespawnPositionAndUseSpawnBlock`/`teleport(TeleportTransition)`, `TeleportTransition` ;
  - `ItemStack.enchant`, `Enchantments.FIRE_ASPECT`, `Player.attack`.
- **Chaque nouveau test échouait avant le correctif.** L'ancien `Duels.kt` a été remis le temps d'un lancement, avec
  de simples cales de compilation : les **15 nouveaux GameTests échouent** et les 15 anciens passent. Les deux
  nouveaux tests unitaires échouaient aussi. Les quatre tests du constat 3 y **tuaient** le perdant : « burned to a
  crisp », « tried to swim in lava » deux fois, « fell out of the world », inventaire au sol.
- `git merge main` (lots E, F, G et conception de M5) : sans conflit. `runningDuelOf`, d'abord exposé pour le lot F,
  a laissé place à `isDueling`, le contrat que le lot F a finalement posé.
- `.\gradlew.bat build` sur l'état fusionné : **vert**, **1 507 tests unitaires** (0 échec) et **« All 328 required
  tests passed »** (dont les 30 de `DuelGameTests`). Avant la fusion, les GameTests de duel, lancés seuls, étaient
  déjà passés deux fois.
- Aucun appel réel à Claude ; pas de `runServer`.
