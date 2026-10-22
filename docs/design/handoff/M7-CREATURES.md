# Passation M7-CREATURES : des créatures costumées

Retour du propriétaire, le 22/10/2026 : « tout à l'heure le narrateur avait spawn des huissiers, c'était grave bien ca,
j'aimerais voir encore plus de mobs "customs" du genre. » Passations lues : M7-RELICS, M7-FIX, M7-CLARITY, M7-TITLES,
M7-RELOAD, M7-BALANCE, T15, T16. **En cas de contradiction, le code et cette passation font foi.**

## Comment les huissiers ont été faits

Transcription `director-995936a8` du serveur du propriétaire (lue sans rien modifier), tour 49, 18:52:57. clemsix6
vient de casser le lit jaune de Thawyn. Le Directeur pense « a themed guest summon — maybe a "Huissier du Lit Jaune" »
et appelle :

```json
punish {"player": "clemsix6", "punishment": "guests", "severity": 2, "name": "Huissier du Lit Jaune",
        "reason": "a cassé le lit jaune de Thawyn juste après avoir été averti pour ses coups sur Clowew et la plainte de Thawyn"}
```

Le catalogue en fait trois bébés zombies nommés « Huissier du Lit Jaune », une citrouille sculptée sur la tête, lancés
sur clemsix6 pour 2 minutes. La réplique : « Trois Huissiers du Lit Jaune viennent saisir ta tranquillité pour deux
minutes. » Au tour 50 (18:59:50), il apprend que le lit était un cadeau et offre trois `golden_carrot` nommées
« Carotte de l'Erreur Judiciaire » par `give_item`.

Ce qui a plu, c'est le nom lié à la situation. Le costume, lui, se limitait à la citrouille du catalogue : l'outil
`spawn` ne savait mettre que des objets nus (des identifiants).

## Ce qui a été ajouté

### `spawn` (`tool/action/WorldActionTools.kt`)

- **Chaque pièce d'équipement est un `WornItem`** : `item`, plus, au choix :
  - `color` (`#RRGGBB`), pour teindre le cuir (casque, plastron, jambières, bottes ; refusé sur tout autre objet) ;
  - `skin`, le pseudo dont une `player_head` montre le visage (refusé sur tout autre objet) ;
  - `name`, visible seulement si l'objet tombe : un client vanilla n'affiche pas le nom de ce qu'une créature tient ;
  - `glint`, le reflet d'un objet enchanté, sans enchantement.
- **Forme courte** : `"head": "golden_helmet"` vaut `{"item": "golden_helmet"}`. C'est une tolérance générale de
  `ToolArgs` : une chaîne là où le schéma attend un objet dont la seule propriété requise est une chaîne. Les règles
  déjà enregistrées avec l'ancien format restent donc valides, et le modèle peut écrire un identifiant seul. Le chemin
  est noté `args_coerced` comme les autres tolérances.
- **`glowing`** : la lueur, visible à travers les murs (`setGlowingTag`).
- **`drop_chance`** (0 à 1, 0 par défaut) : la chance que chaque pièce du costume tombe quand un joueur tue la
  créature. Un objet précieux (`ItemSpecs.preciousness` > 0 : diamant, netherite, totem…) ne tombe jamais, quelle que
  soit la chance. Ce que `finalizeSpawn` donne de lui-même reste à 0. Les chances restent ≤ 1, donc jamais
  « préservées » au sens de `Belongings`.
- La taille (`attributes` : `scale`, de 0,25 à 4), la vitesse, la version bébé et le reste existaient déjà. Rien n'a
  changé aux plafonds (nombre par appel et en vie selon le chaos), aux joueurs protégés, à la durée de vie (10 min au
  plus), ni au rationnement des cadeaux.
- La réponse décrit le costume : `equipped (head player_head of clemsix6, chest leather_chestplate #F9D71C, mainhand
  paper "Mandat de saisie" shimmering), glowing; …`, et `each piece drops at 25% when a player kills one (never
  diamond_sword: precious)` quand une chance est demandée.
- La description de l'outil dit qu'une créature du Narrateur est presque toujours un personnage nommé et costumé, lié
  à la situation, « each time a new one », avec deux appels complets en exemple : un « Comité de Politesse » (casque
  blanc, plastron rouge, cloche) pour un joueur grossier, des « Fan de Bob » bébés et lumineux qui portent sa tête pour
  un vantard. Elle dit aussi où l'armure se voit : zombies, squelettes, piglins et leurs variantes ; une tête se voit
  aussi sur les illageois et les marchands ambulants (vérifié dans les `*Renderer` du jar 26.3 : `HumanoidArmorLayer`,
  `CustomHeadLayer`).
- Le premier exemple était un voleur de torches : à l'essai, le Directeur le recopiait presque tel quel sur la
  situation des torches (même casque jaune, même plastron noir, même torche). Les exemples ne doivent pas ressembler
  aux situations courantes du serveur.

### `punish` guests : inchangé, exprès

Un `costume` sur les invités de `punish` a été essayé puis retiré : le schéma d'un `EquipmentSpec` (six emplacements,
chacun un `WornItem` complet) faisait passer les arguments des mécaniques de ~9 400 à 14 774 caractères, loin du
budget de `MechanicsContractsTest` (9 400) et de `ToolCatalogTest` (14 000). Les invités du catalogue gardent donc leur
citrouille ; un invité costumé passe par `spawn` avec `target: true`, qui est un châtiment du joueur sous les mêmes
garde-fous (catégorie `creatures`), et que `Sanctions` range aussi dans l'historique du joueur. La description de
`punish` dit maintenant « guests (named zombies in pumpkins; spawn dresses them up) », et le prompt pousse vers
`spawn` pour les créatures.

### La tête de joueur en 26.3

`ResolvableProfile.createUnresolved(pseudo)` (profil dynamique). **En 26.3, c'est le client qui résout le skin**
(`PlayerSkinRenderCache`, avec son propre `ProfileResolver` : recherche du pseudo chez Mojang, puis du skin) ;
`SkullBlockEntity` ne résout plus rien côté serveur. Conséquences :
- tout pseudo d'un vrai compte Minecraft marche, connecté ou non ;
- un pseudo inexistant, ou un client sans accès aux services Mojang, donne un skin par défaut ;
- rien à faire côté serveur, ni appel réseau sur le thread serveur.

### Les prompts

- `director.md`, une ligne ajoutée dans « Les mécaniques », après celle du catalogue : « **Tes créatures** sont des
  personnages, jamais des mobs nus. Préfère `spawn` aux invités du catalogue, qui n'ont qu'une citrouille : un nom et
  un costume tirés de la situation (des « Contrôleurs des Torches » casqués de jaune pour un voleur de torches) rendent
  la blague visible ; ta réplique dit en clair qui ils sont. » La phrase « Préfère `spawn`… » est nécessaire : sans
  elle, à l'essai, le Directeur gardait `punish` guests (6 tours sur 6). Dans les leviers, « invités costumés »
  devient « invités », et « visite de créatures nommées » devient « créatures costumées ». Pour compenser, ont été
  retirés : ce que le moteur fait des défis (redit dans « Les mécaniques »), « La mise en scène rend le reste plus
  lisible et plus drôle », « Ta situation liste tes dernières actions et le Journal garde le reste », et la phrase sur
  les créatures qui blessent a été raccourcie. **Taille : 34 963 caractères avant, 34 998 après** (cible 35 000).
- `storyteller.md` : « créatures costumées et nommées comme des « Huissiers du Lit Jaune » » parmi les châtiments
  composés. **8 915 caractères avant, 8 965 après** (budget 9 000).

## Tests

- GameTest `aCostumedCreatureWearsItsCostumeAndDropsNoneOfIt` : un zombie « Huissier du Lit Jaune », plastron jaune
  `#F9D71C`, tête de clemsix6, papier nommé et brillant, taille ×1,5, lumineux. Tué par le joueur de test, il ne
  laisse aucune pièce du costume ; un second, avec `drop_chance` 1, laisse les trois.
- Unitaire : la forme courte et l'objet dans `WorldActionToolsTest`.
- `gradlew.bat build` vert depuis PowerShell : tests unitaires, et les 409 GameTests requis.
- Les tests qui écrivaient `EquipmentSpec(head = "…")` passent à `WornItem("…")`. `ReplayActs` (le banc d'essai)
  recopie le costume dans sa réponse factice de `spawn`.

## Essai réel (LLMGW, 22/10/2026)

Banc `DirectorReplayEval` (`liveTest`), Directeur en `high`, avec le `director.md` et les outils de la branche ; les
actes ne sont pas exécutés (`ReplayActs`). Tunnel existant réutilisé, rien d'autre lancé. Trois situations, hors du
dépôt (dans le dossier de travail de l'agent, comme celles de M7-CLARITY) :
- `torch_again` : tour 39 de `director-995936a8` (Thawyn décroche encore une torche de Clowew) ;
- `yellow_bed` : tour 49, celui des huissiers (clemsix6 casse le lit jaune de Thawyn) ;
- `boaster` : écrite à la main à partir de la fixture `01-provocation` (Bartok se vante de ses 31 diamants).

| Passe | torch_again | yellow_bed | boaster | Coût |
|---|---|---|---|---|
| 1. ligne « Tes créatures » sans « Préfère `spawn` » | `punish` guests « Syndicat des Torches » ; `punish` tiny | `punish` guests « Huissier du Lit Jaune », « Syndicat des Lits Jaunes » | `punish` slow_hands (+ une couronne en cadeau), slow_hands | 1,20 $ |
| 2. avec « Préfère `spawn` » | `spawn` costumé ×2 | `spawn` costumé ×2 | `punish` slow_hands ×2 | 1,19 $ |
| 3. exemple de l'outil changé (torches → politesse) | `punish` tiny ×2 | — | — | 0,60 $ |

**Coût total : 2,98 $.**

Les créatures inventées en passe 2 :
- « Huissier du Lit Jaune » : trois bébés zombies, casque et plastron en cuir jaune `#F9D71C`, laine blanche en main,
  2 min. Réplique : « clemsix6 a démonté le lit jaune de Thawyn, sept minutes après ma mise en garde. Trois huissiers
  en pyjama jaune viennent te réclamer l'oreiller, pendant deux minutes : bonne sieste. »
- « Inspecteur des Torches » : trois zombies, casque jaune, plastron noir, torche en main, 2 min. Réplique : « Trois
  Inspecteurs des Torches arrivent pour un contrôle de deux minutes : ils ne tuent pas, mais ils insistent. »
- « Syndic des Torches de Clowew » : deux bébés zombies, même costume, 1 min.

Ce qu'on en retient :
- les noms sont bons et liés à la situation dès la passe 1 ; c'est le chemin (`punish` guests) qui empêchait le
  costume ;
- en passe 2, les deux costumes des torches recopiaient l'exemple de l'outil, d'où la passe 3. Le Directeur y choisit
  alors le rapetissement : l'historique du tour contient sa menace « la prochaine torche se paie en taille de lapin »,
  qu'il tient ;
- au vantard, il préfère deux fois un châtiment sans créature, ce qui est raisonnable ;
- une réplique de la passe 2 dit encore « Le crayon retourne au dossier » : c'est le gag C-4 de l'historique de la
  soirée (voir M7-CLARITY), pas un effet de cette tâche.

## Pour la suite

- Le nom d'un objet tenu ne se voit pas en jeu tant qu'il ne tombe pas : seul le nom de la créature s'affiche.
- Le banc a déclaré 48 outils (sans `summon_champion`), la soirée 49 : sans effet ici.
- Si l'on veut un jour des invités costumés dans `punish`, il faudra un schéma bien plus court qu'un `EquipmentSpec`
  (le budget des mécaniques est plein), par exemple une seule couleur de tunique et un visage.
- Les fixtures de l'essai réel contiennent le chat des joueurs du propriétaire : elles ne sont pas versionnées (comme
  celles de M7-CLARITY). Pour les rejouer : `NARRATOR2_REPLAY_DIR` sur un dossier qui contient `transcripts/` (la copie
  de `director-995936a8.jsonl`) et les fiches, `NARRATOR2_REPLAY_SAMPLES_A=0`, `NARRATOR2_REPLAY_SAMPLES_B=2`.
