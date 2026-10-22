# Passation M7-REVOKE : confisquer pour de bon, reprendre les reliques

Passations lues : M7-RELICS, M7-BALANCE (M7-SKELETONS n'existait pas encore). **En cas de contradiction, le code et cette
passation font foi.**

## Pourquoi

Le 22/10 au soir, le Directeur a confisqué à Thawyn « La Hache du Non-Lieu » (hache en fer Tranchant IV, forgée avant
M7-BALANCE) pour 5 min, puis pour 30 min après la consigne de l'opérateur (« ne la lui rends pas ») : une confiscation
était toujours temporaire, et la hache revenait chaque fois. Décision du superviseur, élargie en cours de tâche : une
confiscation peut être **définitive**, pour tout objet, sous les mêmes garde-fous qu'une confiscation temporaire.

## Ce qui change

### `take_item` gagne `permanent` (booléen, faux par défaut)

- **Avec `permanent`**, rien de ce qui est pris ne revient. L'outil prend d'abord dans **ce que le Narrateur détient
  déjà** pour le joueur (une confiscation en cours, une mise, une offrande, ce qui lui est dû), puis dans l'inventaire
  (armure et main secondaire comprises). Un objet saisi pour un temps devient donc définitif : c'est le cas de la hache.
- **Ce qui ne change pas** : le plafond du nombre d'objets selon le chaos (`confiscatedItems`), le fait que ce soit une
  punition (`ctx.punish`, catégorie `inventory` : mêmes refus qu'avant), le garde-mort de 15 s, la journalisation.
  `minutes` reste obligatoire : avec `permanent`, il ne fixe plus que la durée de la punition (l'épisode du joueur).
- **Laissé de côté** : une mise déjà cédée à un gagnant (`owedTo`) n'est plus au joueur ; les piles illisibles restent
  dans le registre ; un objet dans une boîte de Shulker ou un sac n'est pas cherché. Une entrée vidée disparaît (une
  entrée vide resterait sinon détenue pour toujours : `give` la garde quand rien n'est rendu).
- **Réponse** : `ok: took 5x minecraft:diamond from Bob for good; 3x minecraft:diamond of it out of confiscation 812:
  it never comes back, tell them clearly; seizure 9001`.
- **Description** : « Temporary by default: they come back by themselves. permanent takes them for good: only for a real
  fault or an item far too strong for them, and always tell the player clearly. » Le joueur n'est pas prévenu par le
  moteur : c'est le Narrateur qui l'annonce.

### `ItemLedger.seize` et `heldCount`

`seize(player, match, max, reason, turnId)` fait le travail des deux chemins : registre d'abord, puis inventaire,
décision `ledger seized` (ce qui est pris, ce qui sortait du registre et de quelle entrée, la raison, le tour), le tout
dans le même tick. `heldCount` compte ce que `seize` pourrait prendre dans le registre, pour que l'outil refuse « has no
… » avant d'ouvrir une punition. Résultat : `Seizure(id, taken, held)`.

### `/narrator relics revoke <player>` (opérateur)

- Reprend **toutes les reliques** du joueur (`RelicItems.isRelic` : `CUSTOM_DATA {narrator2: {kind: "relic"}}`),
  inventaire et registre, sans quota ni garde-fous, par `ItemLedger.seize`. Jamais un objet ordinaire : l'équipement
  des champions (`champion_gear`), les consommables et les livres ne sont pas des reliques.
- Répond la liste : `revoked for good from Thawyn:`, puis une ligne par objet (`1× La Hache du Non-Lieu
  (minecraft:iron_axe)`) et ce qui sortait du registre. Décisions `ledger seized` et `operator relics_revoke`.
- Joueur hors ligne : `Thawyn is not online: relics are revoked from a player online only (an offline player's inventory
  is not read).` L'argument est un mot (avec les joueurs en ligne en suggestion), pas un sélecteur, pour pouvoir dire
  cela plutôt que « No player was found ».
- Le Directeur n'est pas prévenu : si un rappel lui fait reprendre l'objet plus tard, `take_item` répondra « has no … ».

## Décisions

1. **L'option plutôt qu'un outil `revoke_relic`** : pas d'outil de plus à nommer dans `director.md` (49 outils, comptés
   par `PromptToolMentionsTest` et `ForgeEndToEndTests`), et l'élargissement à tout objet s'y prête.
2. **Pas d'exception pour une consigne opérateur** dans l'outil : la commande opérateur couvre le cas, et M7-LOOSEN
   allège les règles de punition elles-mêmes. Avant ce dernier, un `take_item` permanent sur un objet déjà confisqué
   par un **autre tour** est refusé (`already_punished`) tant que la première confiscation court : c'est la règle
   « une punition à la fois », que je n'ai pas touchée.
3. **`director.md`** : la clause « aucune perte définitive d'objet ; » (34 caractères) est retirée de la liste des
   règles du moteur, parce qu'elle contredisait l'outil. Rien n'est ajouté : la description de `take_item` porte la
   consigne. Le prompt reste dans son budget. **Risque de conflit** : la même ligne (123) énumère les règles de
   punition que M7-LOOSEN assouplit.
4. **Docs** : `ARCHITECTURE.md` §6.1 (une confiscation est temporaire, sauf `permanent`), §9.7 (la commande) et §17
   (un objet n'est perdu définitivement que par `permanent` ou `/narrator relics revoke`).

## Vérifications

- GameTests (`PlayerActionGameTests`) :
  - `aPermanentTakeNeverGivesBack` : 3 diamants confisqués pour un temps, puis 5 en permanent. 3 sortent de la
    confiscation, qui disparaît, et 2 de l'inventaire ; rien ne revient une heure plus tard.
  - `relicsAreRevokedForGood` : une hache relique en inventaire et un bouclier relique en confiscation (avec 4 diamants)
    sont repris par la commande, qui les liste ; la hache ordinaire reste ; les diamants reviennent, jamais le
    bouclier ; un joueur hors ligne est refusé avec la raison.
- Build complet (`.\gradlew.bat build --continue`, `main` à `bd88242` fusionné) : **2 335 tests unitaires passés, aucun
  échec** ; « **All 411 required tests passed** » pour les GameTests ; 10 min 56 s. Aucun appel à Claude, aucun serveur
  de jeu touché.
