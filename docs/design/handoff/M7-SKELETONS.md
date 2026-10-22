# Passation M7-SKELETONS : des squelettes canons de verre

Demande du propriétaire : « possible de faire en sorte que les squelettes aient 50% de vie en moins que la normale, mais
ils tirent 2x plus vite ? ». Le propriétaire a ensuite descendu la vie à **25 %** de la vie vanilla. Deux tables par
type s'ajoutent à la section `difficulty` (M7-BASE, M7-RELOAD).

```json
"difficulty": {
  "mobHealth": 3,
  "mobHealthByType": { "skeleton": 0.25, "stray": 0.25, "bogged": 0.25, "parched": 0.25 },
  "mobFireRate": { "skeleton": 2.0, "stray": 2.0, "bogged": 2.0, "parched": 2.0 }
}
```

(Ces deux tables sont les défauts : un fichier qui ne les donne pas les reçoit quand même.)

- **`mobHealthByType`** : type → facteur **par rapport à la vie vanilla**, qui **remplace** `mobHealth` pour les types
  listés. Bornes 0,1 à 10. Même modificateur `narrator2:difficulty/max_health`, posé à `ENTITY_LOAD` et réconcilié par
  `/narrator reload`, avec la même part de vie. Squelette et vagabond : 20 → 5 PV ; bogged et parched : 16 → 4 PV.
  Comme `mobSpeed`, la table touche tout `Mob` du type listé, boss ou animal compris si on en liste un.
- **`mobFireRate`** : type → facteur de cadence des archers à l'arc (`RangedBowAttackGoal`). Bornes 0,5 à 4. Lu à
  chaque tick : un reload s'applique aussitôt. Les wither squelettes (au corps à corps) ne sont pas listés.
- Les deux tables sont **libres** (`FREE_FORM` de `ConfigStore`), comme `mobSpeed` : on ajoute un type, un défaut reste
  tant que le fichier ne le fixe pas (`"stray": 1` lui rend sa valeur vanilla). Un type absent du registre est une
  erreur de configuration.
- **`mobDamage`** s'applique toujours aux flèches (×1,25 sur ce que reçoit un joueur), sans changement.

## Comment la cadence est doublée (bytecode 26.3)

`RangedBowAttackGoal.tick` : l'archer tire quand `getTicksUsingItem() >= 20`, avec la puissance
`BowItem.getPowerForTime(ticks)` de cette même valeur, puis attend `attackIntervalMin` ticks avant de bander à nouveau
(`AbstractSkeleton.reassessWeaponGoal` : 20 en difficile, 40 sinon ; bogged et parched 50 et 70). Un cycle vaut donc
environ intervalle + 20 ticks.

Le mixin `mixin/difficulty/RangedBowAttackGoalFireRateMixin` pose deux `@ModifyExpressionValue` dans `tick` :
- sur `getTicksUsingItem()`, multiplié par le facteur (`Difficulty.drawTicks`) : à ×2, l'arc est « plein » après 10
  ticks. La même valeur passe à `getPowerForTime`, donc **chaque flèche part à pleine puissance** (1,0 ; la puissance ne
  joue que sur les dégâts de base de la flèche, `setBaseDamageFromMob`, la vitesse étant fixe à 1,6) ;
- sur la lecture de `attackIntervalMin`, divisé par le facteur (`Difficulty.attackInterval`, arrondi, au moins 1).

Les deux moitiés du cycle sont divisées : un squelette tire toutes les ~20 ticks en difficile (au lieu de ~40), toutes
les ~30 ticks sinon (au lieu de ~60). Un type absent de la table (illusionniste, wither squelette armé d'un arc) garde le
comportement vanilla.

## Écart par rapport à la commande

- **`parched` ajouté aux défauts** : c'est un squelette archer de 26.3 (`AbstractSkeleton`, arc, 16 PV), oublié de la
  liste du superviseur ; la demande du propriétaire vise « les squelettes ». `"parched": 1` dans les deux tables le
  remet en vanilla.

## Briefing et journaux

Résumé `[Difficulty] on: …` et attribut `difficulty=` : `…; health by type, over vanilla:
skeleton/stray/bogged/parched x0.25; movement speed creeper x2; bow fire rate skeleton/stray/bogged/parched x2; …`.
Aucun prompt touché.

## Vérifications

- `DifficultyTest` : résumé, fusion clé par clé des deux tables, bornes et type inconnu.
- GameTest `skeletonsAreFrailAndShootTwiceAsFast` : sur le socle par défaut (`mobHealth` ×1,5), un squelette neuf a
  5 PV pleins et un wither squelette 30 ; puis, chacun dans un couloir muré face à un golem de fer immobile, un
  squelette et un wither squelette armé d'un arc (même goal, mêmes intervalles, hors des tables) tirent pendant 240
  ticks : le plus court écart entre deux flèches du squelette vaut environ la moitié de celui du wither squelette
  (rapport attendu entre 0,4 et 0,6). Mesuré : **30 ticks contre 60** (serveur de test en difficulté normale). Le test
  vérifie aussi que `drawTicks(squelette, 10)` donne une pleine puissance.
- `aReloadReconcilesTheLoadedCreatures` : un squelette sorti de `mobHealthByType` par un reload passe au facteur hostile
  (×3 → 60 PV), puis revient à 5.
