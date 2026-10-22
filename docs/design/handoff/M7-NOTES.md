# Passation M7-NOTES : les notes du serveur, les objets des autres mods

## Le réglage

`worldNotes`, à la racine de `narrator2.json` (juste après `difficulty`) : une liste de chaînes, vide par défaut. Ce
sont des données de l'opérateur, en français ou en anglais.

```json
"worldNotes": [
  "Universal Graves est installé : à sa mort, l'inventaire d'un joueur reste dans une tombe à l'endroit de sa mort. Rien n'est perdu tant qu'il retrouve la tombe.",
  "Farmer's Delight est installé : cuisine, couteaux, plats cuisinés (farmersdelight:…), planche à découper et marmite.",
  "Un mod de meubles arrive bientôt."
]
```

- Validation (`WorldNotes.problems`, appelée par `ConfigStore.problems`) : 12 notes au plus, 300 caractères chacune,
  aucune vide. Un message d'erreur ne cite jamais une note.
- Rendu (`config/WorldNotes.render`) : `<server_notes>` avec une note par ligne, échappées par `ModelText.escape` (ni
  balise ni saut de ligne) ; rien quand la liste est vide.

## Qui les voit

- **Le Directeur** : dans son briefing, juste après `<world/>` et avant `<players_online>`. Le briefing ouvre le chapitre
  et reste dans la conversation : les notes sont là à chaque réveil du chapitre. `Briefing.MAX_CHARS` grandit de
  `WorldNotes.BLOCK_MAX` (3 644 caractères, 12 notes pleines).
- **L'Armurier** : dans le premier message de ses trois sortes de tâches (champion, relique, réassort des réserves :
  reliques, consommables, livres), juste avant `<context/>` (`RelicsService.context`).
- **Le Scénariste** : dans ses tâches de `delegate` (après `<context/>`, avant ses idées ouvertes, `Workers.opening`) et
  dans le programme du rêve (juste après la ligne `<job/>`, `ProgrammeInputs.opening` ; `STEP_MAX` en tient compte).
- Aucun prompt n'a été touché : le nom de l'élément parle de lui-même.

## `/narrator reload`

- L'Armurier et le Scénariste lisent `settings.get().config` à chaque tâche : une nouvelle tâche a les nouvelles notes.
- Le Directeur : une conversation est en ajout seul, son briefing ne change pas en cours de chapitre. Un reload qui
  change `worldNotes` clôt donc le chapitre au prochain moment calme (`Chapters.reloadChange`, comme un prompt changé) ;
  le briefing du suivant montre les nouvelles notes.
- La réponse de `/narrator reload` a une ligne grise de plus : `worldNotes: N server notes; …`.

## Les objets des autres mods

Constat : l'équipement des champions passait déjà par `ItemSpecs.resolve`, qui accepte tout objet du registre ; les
reliques aussi, si l'objet est dans un tag d'arme, d'outil ou d'armure du jeu. **Seuls les consommables étaient
fermés** : une énumération de 23 aliments vanilla (`ConsumableFood`).

- **Consommables** : `ConsumableDraft.food` est désormais un identifiant (`String`, 64 caractères), et
  `ConsumableFood` a disparu. `RelicItems.consumable` exige un objet du registre (`ItemSpecs.resolve` : connu, activé,
  pas interdit) qui porte le composant `FOOD`, et pas précieux (`ItemSpecs.preciousness` > 0 : pomme dorée, pomme dorée
  enchantée). Les aliments vanilla hors de l'ancienne liste (chair putréfiée, lapin cuit…) passent aussi. Les réserves
  déjà stockées (`"food": "apple"`) se relisent telles quelles. Un plat moddé dont la classe applique ses propres effets
  en le mangeant (`finishUsingItem`) les garde en plus de ceux de l'Armurier : les nôtres remplacent seulement les effets
  du composant `CONSUMABLE`.
- **Reliques** : les bases acceptent aussi les tags conventionnels de Fabric où les mods rangent leurs objets
  (`ConventionalItemTags` : `MELEE_WEAPON_TOOLS`, `RANGED_WEAPON_TOOLS`, `MINING_TOOL_TOOLS`, `HUMANOID_ARMORS`). Le
  palier d'un objet moddé se lit sur son chemin (`othermod:iron_knife` est fer, `Tier.ofMaterial`) ; un chemin sans
  matériau connu compte toujours comme diamant (prudent).
- **« Jamais de netherite »** valait seulement pour `minecraft:` : il regarde maintenant le chemin, quel que soit le mod
  (reliques et équipement des champions).
- Inchangé : nourriture pour un consommable, catégorie d'arme, d'outil ou d'armure pour une relique, armure dans son
  emplacement pour un champion.
- `Threat` (le palier d'un joueur) ne lit toujours que les tags du jeu : une arme moddée hors de ces tags ne monte pas le
  palier du joueur (sa relique reste plus modeste, jamais plus forte).

## Vérifications

- Tests unitaires : `WorldNotesTest` (lecture du fichier, bornes et messages sans citation, rendu échappé, taille
  maximale), `BriefingTest` (les notes juste après `<world/>`, rien sans notes, budget tenu avec 12 notes pleines),
  `ConfigStoreTest` (la clé écrite au premier lancement), `RelicBalanceTest` (palier d'un objet moddé).
- `build` vert (tests unitaires et GameTests).
- Non testé en jeu : un vrai plat de Farmer's Delight (pas de mod tiers dans les GameTests).
