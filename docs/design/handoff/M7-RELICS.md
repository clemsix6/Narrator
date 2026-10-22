# Passation M7-RELICS : l'Armurier, les champions, les reliques et les réserves des coffres

Tâche M7-RELICS (décisions du superviseur du 22/10/2026 ; note `docs/research/DIFFICULTY_API.md` §4 et §5 ; passations
lues : M6-08, M6-FIX-14, M6-FIX-15, M5-12, M7-BASE). **En cas de contradiction avec la note de recherche, le code et
cette passation font foi.** `docs/ARCHITECTURE.md` n'a pas été touché : le superviseur s'en charge (rôle de l'Armurier
au §7.3, outil `summon_champion` au §6.1, section `relics` au §15).

Branche `worktree-agent-a61153c18715ecd85`, avec `main` fusionné à `4e72dca` (M7-BASE).

## Ce qui est livré

| Fichier | Rôle |
|---|---|
| `relics/RelicSpecs.kt` (nouveau) | les brouillons typés de l'Armurier (`RelicDraft`, `ConsumableDraft`, `BookDraft`, `ChampionDraft`), les listes blanches avec leurs plafonds, et `RelicRules`, la validation pure (sans registre) |
| `relics/RelicItems.kt` (nouveau) | les brouillons en objets du jeu, sur le thread serveur : `ItemSpecs.resolve` puis base de relique, enchantements (adaptés, compatibles, niveau vanilla, sans malédiction), jamais de netherite ; composants 26.3 ; marqueur `CUSTOM_DATA {narrator2: {kind, id}}` |
| `relics/Armorer.kt` (nouveau) | les conversations de l'Armurier : une par tâche, sur `AgentLoop`, compteur de messages du moteur, transcription, ligne `conversations` |
| `relics/RelicsService.kt` (nouveau) | le service : plafonds, invocation, mort, relique du tueur, réserves, tirage dans les coffres, réassort, lignes pour le Directeur |
| `relics/RelicsStore.kt` (nouveau) | les tables `relic_stock` et `champions` dans le Journal |
| `relics/RelicsHooks.kt` (nouveau) | `MODIFY_DROPS`, `AFTER_DEATH`, `AFTER_DAMAGE`, `MOB_CONVERSION`, `ENTITY_UNLOAD` |
| `tool/relics/RelicTools.kt` (nouveau) | `summon_champion` (Directeur) ; `create_champion`, `create_relic`, `create_consumable`, `write_book` (Armurier) |
| `command/RelicsCommands.kt` (nouveau) | `/narrator champion <joueur> [brief]`, `/narrator relics`, `/narrator relics restock` |
| `prompts/armorer.md` (nouveau) | le prompt de l'Armurier, en français (6 025 caractères ; budget 7 000 dans `PromptsTest`) |
| `llm/Role.kt`, `config/NarratorConfig.kt`, `config/Prompts.kt` | le rôle `armorer` (Opus 5.5, effort `medium`), la section `relics` et sa validation, le prompt |
| `tool/Tool.kt`, `tool/ToolCatalog.kt`, `director/Chapters.kt` | `ToolEnv.relicsAvailable` ; les outils du rôle ; le Directeur voit `summon_champion` quand l'Armurier peut travailler |
| `director/DirectorControl.kt` | `MechanicSignal.CHAMPION` et `MechanicSignal.TREASURE` (lignes `CHAMPION …` et `TREASURE …` de `<events>`) |
| `runtime/NarratorServices.kt`, `NarratorRuntime.kt`, `Narrator2.kt`, `NarratorCommand.kt`, `core/Log.kt` | le service (`relics`, nul sans Journal), ses crochets, ses commandes, le tag de log `Relics` |
| `prompts/director.md` | une mention : `summon_champion` dans « Actions sur le monde » ; une phrase raccourcie d'autant (34 999 → 34 998 caractères) |
| GameTest `LiveRelicsGameTests` (nouveau) | l'essai réel, hors `build` (voir plus bas) |

### Les champions

- **`summon_champion(player, brief)`** (Directeur, effet `TASK`) : refusé sans Armurier, sans clé, pour un joueur hors
  ligne, au-delà des plafonds (`DENIED (champion_limit)`), ou par les garde-fous (`Guards.vet`, catégorie `creatures` :
  protégé, mort récente, grâce, duel, châtiment déjà en cours, même catégorie trop tôt). Sinon il rend la main tout de
  suite (`ok: the armorer designs a champion for …`) et lance la tâche de l'Armurier. Les consignes d'usage sont dans la
  description de l'outil (rare ; joueur fort qui se vante, se repose ou a mérité une épreuve, sommet d'un récit ; jamais
  un joueur nouveau ou en difficulté, ni deux fois dans la soirée pour le même).
- **La tâche** voit la consigne et le joueur tel qu'il est (`PlayerReport` : vie, position, biome, équipement) ; elle
  appelle `create_champion` une fois. Le code vérifie le brouillon puis invoque le champion **par l'outil `spawn`**
  (`ToolRunner.invoke`), avec tous ses garde-fous et ses plafonds : c'est une créature du Narrateur (marquée, persistante,
  liée à son joueur, coup fatal transformé en sauvetage, retirée si le joueur meurt ou part, disparaît à son échéance).
  Puis le service l'habille : équipement (`RelicItems.gear`), effets permanents, lueur, étiquette `narrator2_champion`.
- **La fiche** est en base (`champions` : entité, joueur, brouillon JSON, échéance, statut `active` / `slain` / `died` /
  `gone`, tueur, combat, relique). Une conversion (zombie → noyé) suit le champion (`MOB_CONVERSION`).
- **La mort** (`AFTER_DEATH`) : tué par un joueur (`source.getEntity()`, sinon `getLastHurtByPlayer()`), son équipement
  tombe **entier** (chance de drop 0 pour le jeu, puis lâché par le service : voir les écarts), et une tâche « relique pour
  le tueur » part avec la fiche du champion, la fiche du tueur et le combat (durée, dégâts reçus par attaquant, dégâts
  infligés, dernier coup). La relique est donnée au tueur, ou posée à ses pieds si son inventaire est plein ; s'il est
  parti entre-temps, elle va dans la réserve des coffres. Tué autrement : aucune relique, équipement perdu. Sans Claude
  ou en cas d'échec : l'équipement seul.
- **Le Directeur est prévenu** par des lignes `CHAMPION` (sans priorité, urgence SOON) : apparition (description,
  position, échéance, légende), échec de conception, mort (tueur, combat, relique donnée ou raison de son absence), ou
  disparition sans vainqueur (seulement si elle survient avant l'échéance + 2 min : une nouvelle périmée n'est pas dite).

### Les reliques (« une relique reste toujours bonne »)

`RelicRules.relic` (pur, testé) puis `RelicItems.relic` (registres) :
- base : épée, hache, pioche, pelle, houe, lance (tags `SWORDS`… `SPEARS`, `*_ARMOR`), bouclier, arc, arbalète,
  trident, masse ; **jamais de netherite** ;
- nom (40 caractères) et lore (1 à 4 lignes de 80) obligatoires et visibles une fois nettoyés ;
- enchantements adaptés à l'objet (`Enchantment.canEnchant`), compatibles entre eux, au niveau max vanilla au plus,
  jamais une malédiction (`EnchantmentTags.CURSE`) ;
- **au moins un bonus** (un enchantement ou un attribut positif) et **au plus un malus**, léger ;
- incassable permis ; `RARITY` épique ; modificateurs ajoutés **à ceux de l'objet** (une épée garde ses dégâts de base),
  identifiant `narrator2:relic/<emplacement>/<attribut>` (deux reliques portées ne s'écrasent pas, et le registre des
  attributs du Narrateur, préfixe `mod/`, ne les touche pas).

| Attribut | Opération | Bonus max | Malus min |
|---|---|---|---|
| `attack_damage` | + valeur | 4 | −1 |
| `attack_speed` | × base | +30 % | −10 % |
| `max_health` | + valeur | 6 (3 cœurs) | −2 (1 cœur) |
| `armor` | + valeur | 4 | −2 |
| `armor_toughness` | + valeur | 3 | jamais |
| `knockback_resistance` | + valeur | 0,5 | jamais |
| `movement_speed` | × base | +20 % | −10 % |
| `luck` | + valeur | 3 | −2 |
| `block_break_speed` | × base | +50 % | −10 % |
| `attack_knockback` | + valeur | 1,5 | jamais |
| `entity_interaction_range` | + valeur | 1,5 | −0,5 |
| `block_interaction_range` | + valeur | 2 | −0,5 |
| `oxygen_bonus` | + valeur | 3 | jamais |

Rien d'autre : pas de `scale`, de `gravity`, de saut, de hauteur de pas ni de chute (un test le garde).

### Les consommables et les livres

- **Consommable** : un aliment de la liste blanche (pomme, pain, biscuit, carottes, pommes de terre, viandes et poissons
  cuits, baies, tarte, varech, miel, soupes ; ni pommes dorées ni fruit du chorus), dont le composant `consumable` est
  remplacé (même durée, animation et son que l'aliment) par 1 à 4 effets, **chacun avec sa chance** (vanilla :
  `ApplyStatusEffectsConsumeEffect(effet, probabilité)`). 26 effets, chacun plafonné en durée et en niveau ; rien de
  mortel (ni dégâts instantanés, ni wither, ni lévitation ; le poison s'arrête à un demi-cœur dans le jeu, 8 s au plus).
  Reflet enchanté, rareté peu commune.
- **Livre** : `written_book`, titre (32, limite vanilla `TITLE_MAX_LENGTH`), auteur (32), 1 à 8 pages de 700 caractères
  mises en page par `Speech.layOut` (14 lignes visibles, 100 pages au plus). Il parle de vrais joueurs et événements
  quand la mémoire en a (`recall`, `player_profile`) ; sinon du monde, sans nommer personne.

### Les réserves des coffres

- **Préparées à l'avance**, en base (`relic_stock`, statut `stock` / `found` / `broken`), chargées en mémoire au
  démarrage. Le tirage se fait sur le thread serveur sans attendre personne.
- **Tirage** (`MODIFY_DROPS`) : tables `minecraft:chests/…` (trial chambers comprises), sauf `chests/spawn_bonus_chest` ;
  une fois par contexte de loot (les tables imbriquées le partagent). Chaque sorte a sa chance ; l'objet le plus ancien de
  sa réserve est reconstruit depuis son brouillon et ajouté. L'ouvreur (`THIS_ENTITY`) connu : ligne `TREASURE` au
  Directeur (« X found a relic, « … », in a chest (simple_dungeon): … »).
- **Réassort** : quand une réserve passe sous la moitié de sa cible, une tâche remplit **toutes** les réserves jusqu'à leur
  cible ; une à la fois ; au démarrage puis après chaque prise dans un coffre. Une tâche qui n'a rien fait bloque le
  réassort automatique 30 min. Pas d'intégration au rêve.

### La configuration

```json
"relics": {
  "enabled": true,
  "relicChance": 0.03, "consumableChance": 0.08, "bookChance": 0.05,
  "relicStock": 5, "consumableStock": 8, "bookStock": 5,
  "maxChampions": 3, "maxChampionsPerPlayer": 1,
  "championMinutes": 10
}
```

Bornes : chances 0 à 1 ; réserves 0 à 20 ; `maxChampions` 1 à 10 ; `maxChampionsPerPlayer` 1 à 3 ; `championMinutes` 1 à
10 (la durée de vie maximale de `spawn`). Lue à chaque usage : `/narrator reload` s'applique.

### Les plafonds des champions, avec le socle de M7-BASE

Les facteurs du champion passent par `spawn` (modificateurs `ADD_MULTIPLIED_TOTAL`) et **se multiplient** avec ceux du
socle (`narrator2:difficulty/max_health`, ×1,5 ; dégâts ×1,25 sur les joueurs), qui s'appliquent aussi aux créatures du
Narrateur.

| | Champion | Avec le socle par défaut |
|---|---|---|
| PV | ×1,5 à ×3 | ×2,25 à ×4,5 (un zombie : 45 à 90 PV) |
| dégâts | ×1 à ×1,5 | ×1,25 à ×1,9 |
| vitesse | ×1 à ×1,3 | inchangé |
| effets | 3 au plus : vitesse, force, résistance, régénération, résistance au feu, respiration, chute lente (niveau 1) ; saut, absorption (niveau 2) | |
| créatures | zombie, husk, noyé, squelette, vagabond, bogged, wither squelette, araignée, araignée venimeuse, sorcière, vindicateur, pillard, piglin zombifié (rien qui explose ni se téléporte) | |
| équipement | 6 pièces, armure à sa place, enchantements au niveau vanilla, jamais de netherite ni d'objet interdit (`ItemSpecs`) | |

Mesuré dans l'essai : un husk ×2 est apparu à 60/60 PV (20 × 2 × 1,5).

## Écarts et décisions

1. **Pas de migration du Journal.** Les deux tables sont créées par `RelicsStore.open` (`CREATE TABLE IF NOT EXISTS`) au
   démarrage du service. Une migration V5 aurait changé la version du schéma que plusieurs tests épinglent, et
   `SchemaDoc` (la description de `sql`) aurait dû les loger dans son budget. Elles restent lisibles par `sql`, sans être
   décrites. Identifiants : `max(id) + 1` sur le fil d'écriture.
2. **L'Armurier n'est pas un rôle de `Workers` ni de `delegate`.** Ses tâches sont lancées par le moteur (réassort,
   relique) ou par `summon_champion`, et ne doivent pas revenir au Directeur comme des `TASK` : un petit exécuteur à lui
   (`Armorer`, sur `AgentLoop`), comme `MemoryAgents`. Ses appels sont des `llm_calls` du rôle `armorer`, but `worker`
   (coûts du tableau de bord par rôle). Limites : 5 messages (champion, relique), 8 (réassort) ; 4 et 8 min.
3. **Ses outils** : ses quatre sorties typées, plus `player_profile` et `recall`. Pas d'`inspect_player` ni de
   `game_ids` : l'ouverture de la tâche donne l'état du joueur, et les listes blanches sont des `enum` du schéma.
4. **Le hasard des consommables est « une chance par effet »**, pas « exactement un effet parmi N » : c'est ce que permet
   le composant vanilla sans code au moment de manger. Des chances qui font environ 1 donnent un effet parmi plusieurs
   (le prompt l'explique) ; deux ou aucun peuvent arriver.
5. **L'équipement du champion tombe entier, lâché par le service.** Avec une chance de drop de 1,0, le jeu l'aurait lâché
   seulement sur un coup de joueur (bien), mais **usé au hasard** (bytecode de `Mob.dropCustomDeathLoot`), et un drop
   garanti l'aurait lâché pour n'importe quelle mort et rendu à l'expiration (`Belongings`). Le service met la chance à 0
   et lâche lui-même des copies intactes quand un joueur le tue.
6. **Le champion est une créature du Narrateur** (il passe par `spawn`) : ses coups ne tuent jamais (un coup fatal est un
   sauvetage, le champion est alors retiré), il ne vise que son joueur, il disparaît au bout de `championMinutes`
   (10 au plus, la limite de `spawn`). Un autre joueur peut l'abattre et prendre la relique.
7. **Le décompte du combat** (`AFTER_DAMAGE`) ne voit pas le coup fatal (Fabric ne l'appelle pas pour un coup qui tue) :
   le dernier coup est décrit à part (type de dégâts, arme).
8. **Un tueur parti avant la fin de la tâche** : sa relique va dans la réserve des coffres plutôt que de se perdre.
9. **Rareté** : le service ne rationne pas les reliques par joueur (le Directeur décide des champions, et les coffres
   tirent au hasard) ; à surveiller en vraie partie.
10. **`director.md`** : seulement le nom de l'outil (les consignes sont dans sa description). `PromptToolMentionsTest`
    voit donc 49 outils au Directeur (avec `relicsAvailable`), et vérifie le prompt de l'Armurier.

### Fichiers hors de ma fiche (changements minimaux)

| Fichier | Pourquoi |
|---|---|
| `ConfigStoreTest`, `PromptsTest` (2 listes), `StatusLinesTest` (28 lignes au plus : un rôle de plus), `LogTest` (tag `Relics`) | listes attendues |
| `PromptToolMentionsTest` | `relicsAvailable`, 49 outils, un test du prompt de l'Armurier |
| GameTest `ForgeEndToEndTests` | le Directeur réel a 49 outils, `summon_champion` compris (une ligne) |
| `src/gametest/resources/fabric.mod.json` | `LiveRelicsGameTests` |

## Ce que les tâches suivantes doivent savoir

- Dans les GameTests, le service tourne (Journal présent) mais ne contacte jamais Claude au démarrage (pas de clé) ; il ne
  le fait ensuite qu'après une prise dans un coffre, une mort de champion ou une commande. Un GameTest qui donne une clé
  au Directeur et compte ses outils en voit 49.
- `RelicsService.TAG` (`narrator2_champion`) marque les champions ; `/kill @e[tag=narrator2_champion]` les retire (ils
  sont alors `gone`).
- Les lignes `CHAMPION` et `TREASURE` ne sont pas décrites dans `director.md` : elles sont lisibles telles quelles, et la
  description de `summon_champion` les annonce.

## Vérifications

- **Tests unitaires** : `RelicRulesTest` (10 tests) : relique bonne (un bonus, au plus un malus léger, plafonds, pas de
  doublon, nom et lore visibles, aucun attribut de déplacement), consommables (durées, niveaux, rien de mortel),
  champions (facteurs plafonnés par le schéma, créeper refusé, effets plafonnés), livres, et existence de chaque
  identifiant des listes blanches dans les registres 26.3. Plus `PromptToolMentionsTest` (prompt de l'Armurier).
- **Build complet** (`.\gradlew.bat build --continue`) sur la fusion de `main` (`4e72dca`, M7-BASE) : **2 313 tests
  unitaires, 0 échec, 0 ignoré** ; **« All 405 required tests passed »** (GameTests ; `LiveRelicsGameTests` n'y fait
  rien sans `-Dnarrator2.live`), en 10 min 42 s. Le premier build, avant la fusion, n'avait qu'un rouge :
  `StatusLinesTest` (28 lignes de statut pour 27 permises, un rôle de plus), corrigé.
- **Essai réel, `runServer`** (`TheServer/` du worktree, port 25602, RCON 25612, tunnel déjà ouvert réutilisé) : au
  démarrage, le réassort a rempli **5 reliques, 8 consommables et 5 livres en 1 min 08 s** (5 requêtes ; trois noms trop
  longs refusés puis corrigés par l'Armurier) ; `/narrator relics restock` : « no restock: the reserves are full enough » ;
  `/narrator champion` sans joueur : refusé par le sélecteur. Arrêté par `stop`.
- **Essai réel, GameTest `live_relics`** (`.\gradlew.bat liveGameTest "-Pnarrator2.live.filter=narrator2-gametest:live_relics_*"`,
  un joueur simulé, Directeur endormi, chances à 1) : réassort au démarrage (18 objets, 1 min 09 s) ;
  `/narrator champion` → champion conçu en 12 s et debout ; tué d'un coup d'épée du joueur → relique forgée et donnée en
  9 s ; coffre `chests/simple_dungeon` ouvert par le joueur → une relique, un consommable et un livre tirés des réserves,
  trois `treasure_found`. « All 1 required tests passed ». Rapport et transcriptions archivés dans
  `.research/m7-live/m7-relics/`.
- **Coût réel : ≈ 0,66 $** (réassort de `runServer` ≈ 0,30 $ ; GameTest 0,359 $ pour 10 requêtes de l'Armurier : un
  réassort complet ≈ 0,30 $, un champion ≈ 0,03 $, une relique ≈ 0,03 $).
- **La clé** n'a jamais été affichée ni copiée : une recherche exacte ne la trouve ni dans les sources, ni dans `docs/`,
  ni dans l'archive. Tunnel : le port 8088 écoutait déjà (pas le mien), je ne l'ai ni ouvert ni fermé.

## Exemples réels

**Champion** (consigne : « Il a volé toutes les pommes du verger du Narrateur et s'en vante. » ; le joueur, en fer, dans
un désert) : husk « Le Gardien du Verger Pillé », vie ×2, dégâts ×1,2, vitesse ×1,1, résistance au feu ; « La Binette de
l'Inventaire » (houe en fer, efficacité III, solidité II) et « Chapeau de Paille Rancunier » (protection II). Légende :
« Jadis jardinier du Narrateur, il comptait chaque pomme du verger, une à une, chaque matin. Ce matin, il en a compté
zéro, et entendu un voleur s'en vanter. Il a traversé le désert à pied pour récupérer les pépins. » L'Armurier a dosé :
« Le joueur n'a qu'une épée et un plastron en fer, donc je me suis limité à vie x2. »

**Relique du tueur** : épée en fer « Le Sécateur du Receleur » (tranchant III, butin II, solidité II ; chance +1, vitesse
d'attaque +10 %) : « Le jardinier a marché trois jours dans le désert. / Le combat a duré zéro seconde. / Il comptait les
pommes ; toi, tu ne comptes même pas les coups. / Rends les pépins. On sait que c'est toi. »

**Reliques des coffres** : « Le Bouclier Anti-Creeper Garanti* » (« *Garantie non valable en cas de creeper. / Testé en
laboratoire sur trois cochons consentants. / Le quatrième n'a pas signé. ») ; « La Pioche du Dernier Diamant Promis »
(efficacité IV, fortune II ; +20 % de vitesse de minage, chance −1 : « Elle a creusé jusqu'à la couche -59. / Elle a
trouvé du gravier. Beaucoup de gravier. / Le diamant était juste à côté. Il l'est toujours. ») ; « Hache du Vindicateur
En Préavis » (+2 dégâts, −10 % de vitesse d'attaque).

**Consommables** : « Pomme d'une honnêteté douteuse » (vitesse II 2 min ou nausée 10 s, une chance sur deux chacune :
« Garantie 100 % bénéfique. / Le Narrateur a écrit cette garantie lui-même. ») ; « Miel des Abeilles Rancunières »
(absorption II 60 %, poison 4 s 40 %).

**Livre** : « Règlement Intérieur du Monde », par le Narrateur : « Article 4 : Le Narrateur voit tout, commente tout, et
ne rembourse rien. […] Fait au sommet d'une montagne, par beau temps, avec mépris. »

## À trancher

1. **Les champions ne tuent jamais** (créatures du Narrateur) : voulu par « le chemin d'invocation existant, avec ses
   garde-fous ». Si le propriétaire veut des champions réellement mortels, il faudra les sortir de `spawn`.
2. **Durée de vie de 10 minutes au plus** (celle de `spawn`) : suffisante pour un combat, courte si le joueur fuit.
3. **Le ton des noms** : drôle et lié au brief ; sur un serveur sans mémoire, les objets des coffres parlent du monde
   seulement (testé ainsi : la mémoire était vide). La première vraie partie dira s'ils citent bien les joueurs.
