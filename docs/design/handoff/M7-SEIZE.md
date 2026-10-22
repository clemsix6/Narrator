# Passation M7-SEIZE : rendre définitive une confiscation en cours

Passations lues : M7-REVOKE, M7-LOOSEN. **En cas de contradiction, le code et cette passation font foi.**

## Le correctif (`TakeItemTool`, `tool/action/PlayerActionTools.kt`)

- Un `take_item` `permanent` qui **ne prend rien dans l'inventaire** ne passe plus par `ctx.punish` ni par le garde-mort
  de 15 s : ni catégorie `inventory`, ni repos (`same_punishment`), ni plafond par heure, ni refus de protection. Il
  reste journalisé (`ledger seized`, avec le tour qui saisit). C'est le cas de la hache confisquée 5 min par un tour,
  que le suivant veut garder pour de bon.
- Le critère : `minOf(count, has) <= held`, où `held` vient de `ItemLedger.heldCount` et `has` compte le registre plus
  l'inventaire. Il couvre « les objets détenus suffisent pour `count` » et aussi « `count` plus grand que tout, mais rien
  de tel dans l'inventaire » (le modèle demande souvent 64 pour « tout »). Sinon, chemin normal : une punition.
- **Écart à la décision** : au chaos 0 (`caps.hostileAllowed` faux), le chemin reste la punition, donc refusé comme
  avant. Au chaos 0, rien n'est pris pour de bon, pas même une mise ou une offrande détenue.
- `ItemLedger` n'a pas changé : `heldCount` et `seize` suffisaient (registre d'abord, dans le même tick).
- Le plafond d'objets du chaos (`confiscatedItems`) s'applique toujours à `count`.
- Description de `take_item` : « Making permanent what you already hold for them (a running confiscation, even another
  turn's) takes nothing more: it is no new punishment. Otherwise always a punishment… ».
- `ARCHITECTURE.md` §17 : une phrase.

## Vérifications

- GameTest `PlayerActionGameTests.anotherTurnMakesAConfiscationPermanent` : la hache confisquée 5 min par le tour 7001 ;
  le tour 7002 veut 1 diamant pour de bon (dans l'inventaire) : `same_punishment` ; puis la hache (`count` 8,
  `permanent`) : acceptée, l'entrée du registre disparaît, l'épisode reste celui du tour 7001, et la hache ne revient
  pas une heure plus tard (les diamants restent).
- `.\gradlew.bat build --continue` (`main` à `176690f`, rien de plus à fusionner) : 2 336 tests unitaires sans échec,
  « All 413 required tests passed » pour les GameTests (412 avant). Aucun appel à Claude, aucun serveur de jeu touché.
