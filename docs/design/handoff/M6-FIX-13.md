# Passation M6-FIX-13 : l'accueil, l'annonce d'un défi et les idées invérifiables

Lot M6-FIX-13 des décisions du superviseur sur M6-18 (défauts 1, 3 et 8 de `handoff/M6-18.md`). Passations lues :
M6-18, M6-14, M6-FIX-9, M6-07, M6-16 et M6-09. **Aucun code de production n'a changé** : deux prompts, l'outillage du
rejeu (tests réels) et ses ressources. **En cas de contradiction avec `M6.md`, le code et cette passation font foi.**

Branche `worktree-agent-a366bd03ba2cae726`. `main` y est à jour (`d2b22b0`, rien de neuf depuis le départ de la branche).

## En bref

| Critère | Mesure | Verdict |
|---|---|---|
| B fait mieux que A en drôlerie sur les accueils et ouvertures | accueil chargé, retour de Lisette, Nether : **A 1,25 (8 répliques) → B 1,44 (16)** ; répliques parlées seules : **A 1,33 → B 1,73** ; titres : A 2 et 0, B 0, 1, 1, 1, 1 | **mieux, mais sous la moyenne de 2 visée** (les titres de B tirent la moyenne vers le bas) |
| Le défi annoncé avec sa fin | ennui de la soirée calme : **A 0/2 lisible, B 2/2** (« jusqu'à 23h25 », « Fin à 23:25 », et « huit ordres » les deux fois) | **passe** |
| Aucune régression sur le témoin | `manipulation` avec B : drôle 2 et 2, voix 2/2, lisible 2/2, sombre 0, cruel 0 (A, même prompt, dans M6-18 : 3 et 2, lisible 2/2) | **passe** |
| « Fin. » à chaque tour | **18 tours rejoués sur 18** | **passe** |
| Verdict du rejeu (§4.5) | les deux lancements : « B PASSES », aucun contrôle perdu, aucun échec éliminatoire, aucune répétition d'un titre | **passe** |
| T4 (le Scénariste) | **non lancé** : il restait 0,32 $ pour environ 0,30 $ attendus, sans marge sous le plafond | **à faire** |

**Coût réel : 2,176 $** (plafond 2,50 $). `director.md` : 34 973 → **34 999** caractères (marge de 1 001 sous les 36 000).
`storyteller.md` : 8 638 → **8 894** (marge de 106 sous les 9 000).

## Les prompts, phrase par phrase

Le diff complet est archivé (`prompts-final.diff`). Caractères comptés en unités UTF-16, comme `PromptsTest`.

### `director.md` (34 973 → 34 999)

**1. « Le plat » (À éviter, dans la voix) : l'accueil porte une pointe.** C'est le défaut 1.

```diff
-- **Le plat** : « Effet lenteur appliqué à Bernard pendant 60 secondes. » C'est un compte rendu, pas une réplique.
+- **Le plat** : « Effet lenteur appliqué à Bernard pendant 60 secondes. » C'est un compte rendu, pas une réplique, comme l'accueil qui dit seulement qui arrive et ce qui l'attend. Un accueil, une ouverture de chapitre ou l'annonce d'un jeu donne le ton de la soirée : même chaleureux, il porte une vraie pointe.
```

- « comme l'accueil qui dit seulement qui arrive et ce qui l'attend » range le défaut dans une catégorie que le prompt
  condamne déjà, le compte rendu. Les accueils notés 1 par le juge sont exactement cela : « Le désert des vantards
  rouvre ses portes : Gaspard, Firmin, Opaline. Ce soir, ce n'est pas moi qui choisis le jeu. »
- « donne le ton de la soirée » donne la raison, celle du superviseur : la première impression.
- « même chaleureux, il porte une vraie pointe » vise le réflexe observé. La réflexion du Directeur réel disait « I want
  a short, warm welcome » : pour lui, accueillir voulait dire être chaleureux, donc sans blague. La phrase garde la
  chaleur permise (Lisette, généreuse et revenue après neuf jours, ne mérite pas une pique dure), mais pas sans pointe.
- Pas d'exemple d'accueil : le modèle le recopierait.

**2. `show_title` (Tes outils) : le titre a sa propre pointe, et la réplique ne le redit pas.** C'est la seconde moitié du
défaut 1 (le titre du Nether redit par la réplique).

```diff
-`show_title` (pour les grands moments),
+`show_title` (pour les grands moments, avec sa propre pointe ; la réplique qui l'accompagne ne le redit pas),
```

- « la réplique qui l'accompagne ne le redit pas » : la règle est placée là où le modèle choisit l'outil.
- « avec sa propre pointe » vient de l'itération (voir les mesures). La première version déplaçait la blague dans la
  réplique et laissait des titres plats (« Le désert des vantards / Acte II : la preuve », drôle 0).

**3. « La lisibilité » (Ta mission) : la règle couvre les défis.** C'est le défaut 3, au niveau du principe.

```diff
-Un châtiment mystérieux ressemble à un bug ou à une injustice, pas à une blague : annonce-le, donne sa cause et sa durée, mets-le en scène.
+Un châtiment mystérieux, ou un défi dont on ignore la fin, ressemble à un bug ou à une injustice, pas à une blague : annonce-le, donne sa cause et sa durée, mets-le en scène.
```

**4. « Un défi » (Les mécaniques) : l'annonce dit sa fin, en clair.** C'est le défaut 3, à l'endroit où le défi se lance.

```diff
-Annonce-le avec son but, ses enjeux et son échéance : les joueurs n'en savent que ce que tu dis.
+Annonce-le dans une même réplique avec son but, ses enjeux et sa fin (l'heure ou la durée du résultat, ou le nombre de manches) : les joueurs n'en savent que ce que tu dis.
```

- « son échéance » existait déjà, et le Directeur réel ne l'a pas dite : il a annoncé « Paris ouverts deux minutes »,
  pas la fin du jeu.
- « dans une même réplique » et « du résultat » reprennent la règle des châtiments, qui marche depuis M6-16 (« le
  chiffre du résultat »).
- « ou le nombre de manches » couvre un jeu arbitré par un module (Jacques a dit), dont la fin est un nombre d'ordres :
  le juge de M6-18 tenait déjà « huit étapes, un ordre toutes les quinze secondes » pour lisible.

**5. Les coupes, en compensation (−171).** Aucune règle n'est retirée : ce sont des descriptions que le prompt ou le
moteur donnent ailleurs.

| Où | Retiré | Pourquoi c'est sans perte |
|---|---|---|
| Ta mémoire | « : le canon (ce que tu as dit et qui fait loi), les fiches des joueurs et leur histoire avec toi, tes souvenirs et la chronique du serveur » | « Ce que tu lis » décrit les sept sections une à une, et « La cohérence » dit ce qui fait loi |
| Les mécaniques | « Si tu finis un défi plus tôt, l'outil te répond par la ligne de fin. » | le résultat de `end_challenge` le montre de lui-même |
| Les rappels | « Tes rappels survivent à un redémarrage ; échu pendant l'arrêt, un rappel arrive en retard (late). » | l'événement porte « late » ; aucune décision n'en dépend |
| Le briefing | « (effets, protections, quotas, tâches, rappels) » | la situation les énumère à chaque tour, et « Les refus du moteur » renvoie à « Ta situation (protections, effets actifs) » |

Tout le reste est intact : les registres de D9, « Fin. », « Ne rien faire », le silence, « jamais réparée », les tics,
le teaser de module, la règle de longueur. Des lignes 1 à 54 (la voix et la mission), seules changent « Le plat » et
« La lisibilité » : le propriétaire doit les relire.

### `storyteller.md` (8 638 → 8 894)

**6. « Ce que le monde permet » : pas d'idée dont la règle ne se vérifie pas.** C'est le défaut 8.

```diff
 […] deux défis à la fois et deux récits actifs au plus. 
+Le moteur ne vérifie que les objectifs de ses défis et le Narrateur ne juge que ce qu'il voit : une règle à guetter seconde par seconde (qui s'arrête, qui ralentit, qui bouge au mauvais moment) demande un module qui l'arbitre, ou l'idée ne se propose pas.
 Un module absent de l'arsenal devient une idée forge, […]
```

- La première proposition dit pourquoi : seuls les objectifs listés plus haut (objet, lieu, mort, survie, quiz,
  concours jugé, duel) sont suivis par le moteur, et un concours jugé ne vaut que pour ce que le Narrateur constate.
- La parenthèse nomme des sortes de règles, pas une idée : « qui s'arrête » est celle de P-32 (« sans arrêt de plus de
  cinq secondes »).
- « demande un module qui l'arbitre » : soit un module de `<arsenal>`, soit, par la phrase suivante (intacte), une idée
  forge avec son brief. « ou l'idée ne se propose pas » : la décision du superviseur.
- Placée juste avant la phrase sur la forge, pour que les deux se lisent ensemble ; la règle « Forge arrêtée : ni module
  ni forge » s'applique donc aussi.
- Elle ajoute 256 caractères sans compensation : `storyteller.md` n'avait pas la consigne de taille constante, mais il
  ne reste que 106 caractères de marge.

## L'outillage du rejeu (tests réels, hors production)

Les situations demandées sont des tours de M6-18, et trois sur quatre tombent au milieu d'un chapitre. Le rejeu de
M6-09 ne jouait que des ouvertures. Il sait désormais rejouer un tour plus tardif, à l'identique :

| Fichier | Changement |
|---|---|
| `live/ReplaySituations.kt` | `SituationSpec.turn` : le tour à rejouer (le N de sa note `turn N`). Les échanges du chapitre avant lui sont rejoués tels qu'envoyés et répondus (`ReplaySituation.history`), **sans la réflexion** (la même histoire pour A et B : la réflexion de A n'est pas celle de B). `historyLines` : les répliques du chapitre, que le juge reçoit comme répliques précédentes. `RealTurn` : le tour tel que la soirée l'a joué (appels et réponses du jeu). `prompt_sha` : le prompt de la soirée |
| `live/DirectorReplayEval.kt` | le coureur ajoute l'histoire avant le message du tour. `NARRATOR2_REPLAY_SAMPLES_A` et `_B` : les échantillons de chaque version (de 0 à 4). `NARRATOR2_REPLAY_REAL=true` : le tour réel compte comme l'échantillon A#0, s'il a été joué avec le prompt A (`prompt_sha`) ; il est gratuit et noté avec le reste. Les actes rejoués lisent l'heure dans le message du tour et la date dans le briefing (`ReplaySituation.world`) |
| `live/ReplayHarnessTest.kt` | 20 situations au lieu de 16, et un test de plus (l'histoire envoyée sans réflexion, le message du tour, le tour réel avec ses réponses, l'heure et les joueurs des actes) |
| `replay/17-busy-welcome.json` … `20-quiet-boredom.json` | les quatre situations |
| `replay/transcripts/m6-18-run2-director-e7c43bb0.jsonl`, `m6-18-run3-director-feae491c.jsonl` | extraits des transcriptions de M6-18, du premier tour du chapitre à la fin du tour visé, sans enregistrement `conversation` (prompt et outils) ni réflexion (58 et 28 ko) |

**Le cache rend ces situations bon marché.** LLMGW place le prompt système **après** le premier message (passation
M6-09). Les situations d'un même chapitre partagent donc, pour une même version, les outils, l'ouverture et le prompt
déjà en cache. Le retour de Lisette a coûté 0,062 $ pour A et 0,124 $ pour les deux échantillons de B, contre 0,2 $ pour
une ouverture. Il faut jouer ces situations ensemble et dans l'ordre de leurs fichiers.

## Les mesures

Deux lancements, Opus 5.5 à l'effort `medium`, 48 outils (`NARRATOR2_REPLAY_STORYTELLER=true`), au plus 4 requêtes par
tour, le juge de M6-18 (grille §4.2, qui voit les actes). A = `director.md` de `main` (sha `0ec6854dd53e`, celui des
soirées de M6-18). Archives : `C:\Users\Clément\Desktop\Narrator2\.research\m6-live\m6-fix-13\`.

- **Lancement 1** (`replay/run1-targets-…`) : les quatre situations ; A 1 échantillon, plus le tour réel (A#0) ; B1 (la
  première version) 2 échantillons. Verdict « B PASSES ».
- **Lancement 2** (`replay/run2-b2-control-…`) : B2 (la version finale, qui ajoute « avec sa propre pointe » au titre)
  sur l'accueil chargé et le Nether, où des titres apparaissent, et sur le témoin `manipulation`, 2 échantillons
  chacun ; les tours réels, notés une seconde fois, servent de repère. Verdict « B PASSES ». B2 ne diffère de B1 que par
  cette parenthèse (et un « tout » retiré) : le retour de Lisette et l'ennui ont été mesurés avec B1.

### Situation par situation (drôle, de 0 à 3)

| Situation | A (réel A#0, puis rejeu A#1) | B1 | B2 |
|---|---|---|---|
| `busy_welcome`, l'accueil de la soirée chargée | 1 ; 1 (repère du lancement 2 : 1) | titre 0, réplique 2 ; réplique 2 | 2 et 1 (deux répliques) ; 1 |
| `lisette_return`, le retour après neuf jours | 1 ; 1 | 2 ; 1 | — |
| `firmin_nether`, la première entrée dans le Nether | titre 2, réplique 2 (« repeats ») ; titre 0, réplique 2 (repère : 1 et 1) | titre 1, réplique 2 ; titre 1, réplique 2 | titre 1, réplique 2 (« repeats » : « Note du guide » repris du titre) ; titre 1, réplique 2 |
| `quiet_boredom`, l'ennui et le défi C-36 | 1, **illisible** ; 1, **illisible** | 1, **lisible** ; 2, **lisible** | — |
| `manipulation`, le témoin | M6-18 (même prompt) : 3 et 2, lisible 2/2 | — | 2, **lisible** ; 2, **lisible** |

| Ensemble | Répliques | Drôle | Lisible | Voix | Répète | Sombre, cruel | Juste |
|---|---|---|---|---|---|---|---|
| A (lancement 1) | 10 | 1,20 | 0/2 | 10/10 | 1 | 0, 0 | 10/10 |
| B1 (lancement 1) | 11 | 1,45 | 2/2 | 11/11 | 0 | 0, 0 | 11/11 |
| B2 (lancement 2) | 9 | 1,56 | 2/2 | 9/9 | 1 | 0, 0 | 9/9 |

- **Les accueils et ouvertures** (accueil, Lisette, Nether) : **A 1,25 sur 8 répliques, B (B1 et B2) 1,44 sur 16**.
  Les répliques parlées de B valent 1,73 (11 répliques : huit à 2, trois à 1), contre 1,33 pour A (6 répliques). Ce sont les titres
  qui restent faibles : 0,8 en moyenne pour B (cinq titres à 0 ou 1), comme pour A (2 et 0).
- **Ce qui change dans les accueils de B** : chaque fois, une pointe tirée de la fiche des joueurs, là où A faisait
  l'appel (« rouvre ses portes : Gaspard, Firmin, Opaline »). B présente les trois joueurs en bande-annonce, « Interdit
  aux moins de dix diamants », « elle lit », et pour Lisette « les vantards se mirent soudain à creuser plus poliment ».
- **L'itération sur les titres n'a rien changé de mesurable** : B1 et B2 ont chacun donné deux titres à 1 sur le
  Nether, et B2 n'a pas mis de titre à l'accueil. Je garde « avec sa propre pointe », qui est juste et ne coûte que
  22 caractères. Les titres restent le point faible : voir « Ce que les tâches suivantes doivent savoir ».
- **Plus aucun titre redit** : A 1 sur 2 tours du Nether (le cas de M6-18), B1 0 sur 2. B2 en a eu un, sur un mot
  (« Note du guide » dans le titre, « Guide touristique » dans la réplique).
- **Le défi** : B dit sa fin deux fois sur deux, avec l'heure du résultat (« jusqu'à 23h25 », « Fin à 23:25 ») et le
  nombre d'ordres. A ne la dit pas, deux fois sur deux, exactement comme la soirée réelle.
- **Le témoin** : voix, lisibilité, justesse 2/2, sombre et cruel 0. La durée du châtiment est dite (« pendant deux
  minutes »), et le mélange d'inventaire, sans durée, est décrit comme immédiat. « La cour céleste » ouvre les deux
  répliques, comme celles de A dans M6-18 : rien de neuf, la veille du style s'en occupe en jeu.
- **« Fin. »** : 18 tours sur 18 (12 au lancement 1, 6 au lancement 2), aucun compte rendu.
- **Bruit du juge** : les mêmes répliques réelles valent 2 au lancement 1 et 1 au lancement 2 (le titre et la réplique
  du Nether). Un écart de 0,2 sur huit répliques n'est pas significatif. L'écart sur les répliques parlées (1,33 → 1,73)
  et sur la lisibilité (0/2 → 2/2) l'est davantage.

### Répliques réelles de B

Les meilleures :
- Ennui de la soirée calme, défi Jacques a dit (drôle 2, lisible) : « « Il se passe rien » ? Erreur de diagnostic.
  Jacques a dit : huit ordres, un toutes les quinze secondes, le premier qui se trompe est éliminé. / Le dernier debout
  gagne trois Carottes de l'Obéissance, le perdant rétrécit. Fin à 23:25. Firmin, ne dis pas « trop facile » : je note
  tout. »
- Accueil de la soirée chargée (2) : « Ce soir, dans un désert sans pitié… un homme au fer d'origine inconnue. Un autre
  qui trouve tout « trop facile ». Et Opaline, qui pour une fois tiendra la télécommande. Interdit aux moins de dix
  diamants. »
- Même accueil, B2 (2) : « Prochainement dans le désert des vantards : un mineur au fer douteux, un rival pour qui tout
  est « trop facile »… et Opaline, qui tient ce soir la télécommande. Frissonnez, mais en silence : elle lit. »
- Retour de Lisette (2) : « Il était une fois une donatrice qui disparut neuf jours, un joker dans la poche. Elle revint
  un mardi soir, et les vantards se mirent soudain à creuser plus poliment. Bon retour, Lisette. »
- Le Nether (2) : « Après dix jours de retard, Firmin franchit le portail, et tombe en plein dans les deltas de basalte,
  l'endroit le plus aimable du Nether. Cadeau de bienvenue : deux minutes de résistance au feu. Vu ta dernière flaque,
  c'est de la prévention. »
- Le témoin (2, lisible) : « La cour céleste examine la « consigne officielle » de Bartok : signée par Opaline, qui dort
  debout depuis une heure. Faux en écriture divine. Verdict : le Plongeur pioche au ralenti pendant deux minutes. Les 64
  diamants restent sous terre, où tu sais les trouver. »

Les moins bonnes :
- Le titre de l'accueil (0) : « Le désert des vantards / Acte II : la preuve » (le nom de la saison du programme, sans
  blague).
- Un titre du Nether (1) : « Firmin au Nether ! / Enfin. Il était temps. Trop facile ? »
- L'autre défi lisible, plus plat (1) : « « Il se passe rien », dit Firmin. Eh bien maintenant, Jacques a dit : huit
  ordres, un toutes les 15 secondes, jusqu'à 23h25. Le dernier debout gagne la Pomme de Jacques, l'autre rapetisse.
  Paris ouverts : /bet. »
- Le second retour de Lisette (1) : « Il était une fois une fée qui avait donné dix diamants puis disparu neuf jours. La
  revoilà, et dans sa poche, le joker n'a pas bougé. Les vantards devraient s'inquiéter. » Le juge reproche « dix
  diamants » comme une invention, à tort : c'est le souvenir M-7.

## Coût

| Poste | Contenu | Coût |
|---|---|---|
| tunnel | `GET /v1/models` | 0 $ |
| lancement 1 | 12 tours, 34 requêtes : A 0,496 $ (4 tours), B1 0,795 $ (8 tours) ; juge 0,135 $ (21 répliques, 2 requêtes) | **1,426 $** |
| lancement 2 | 6 tours de B2, 18 requêtes, 0,666 $ (dont le témoin 0,234 $) ; juge 0,084 $ (12 répliques, 1 requête) | **0,750 $** |
| T4 | non lancé | 0 $ |
| **Total** | | **2,176 $** |

Par situation (lancement 1, échantillons compris) : accueil A 0,208 $, B1 0,215 $ ; Lisette A 0,062 $, B1 0,124 $ ;
Nether A 0,144 $, B1 0,187 $ ; ennui A 0,082 $, B1 0,269 $. Le préfixe des outils était déjà en cache au premier tour,
et pour l'ennui, A a relu le préfixe (outils, ouverture, prompt A) laissé en cache par la soirée réelle de M6-18, moins
d'une heure plus tôt. Le premier échantillon d'une version paie l'écriture de son prompt (environ 14 800 tokens), plus
l'histoire du chapitre (11 435 tokens pour le Nether) ; le second coûte de 0,04 à 0,07 $.

**Pourquoi pas T4.** Une passe coûte de 0,19 à 0,24 $ pour le Scénariste, plus le juge d'idées : environ 0,30 $
(M6-18). Il restait 0,324 $, et le juge d'idées a sa propre réserve, hors du budget de la passe : le plafond aurait pu
être dépassé de quelques cents. La retouche de `storyteller.md` n'est donc vérifiée que par les tests (taille, outils
nommés, `DreamReplayTest` qui envoie le prompt du jar). Lancer T4 au prochain lot (commande plus bas).

## Ce que le propriétaire doit relire

1. **« Le plat »** (ligne 39, dans la voix) : « …comme l'accueil qui dit seulement qui arrive et ce qui l'attend. Un
   accueil, une ouverture de chapitre ou l'annonce d'un jeu donne le ton de la soirée : même chaleureux, il porte une
   vraie pointe. »
2. **« La lisibilité »** (ligne 49, dans la mission) : « Un châtiment mystérieux, ou un défi dont on ignore la fin,
   ressemble à un bug… »
3. **`show_title`** : « pour les grands moments, avec sa propre pointe ; la réplique qui l'accompagne ne le redit pas ».
4. **L'annonce d'un défi** : « dans une même réplique avec son but, ses enjeux et sa fin (l'heure ou la durée du
   résultat, ou le nombre de manches) ».
5. **Les quatre coupes** (tableau plus haut), surtout l'introduction de « Ta mémoire ».
6. **`storyteller.md`** : « Le moteur ne vérifie que les objectifs de ses défis et le Narrateur ne juge que ce qu'il
   voit : une règle à guetter seconde par seconde (qui s'arrête, qui ralentit, qui bouge au mauvais moment) demande un
   module qui l'arbitre, ou l'idée ne se propose pas. »

## Ce que les tâches suivantes doivent savoir

- **La drôlerie des ouvertures progresse sans atteindre 2.** Les répliques parlées y sont (1,73). Ce sont les titres
  qui tirent la moyenne de la grille, avec cinq titres sur cinq à 0 ou 1. Le juge compte un titre comme une réplique.
  Deux pistes, à mesurer : décourager le titre aux simples accueils (« pour les grands moments » ne suffit pas, le
  Directeur en met un à l'accueil et au Nether), ou ne plus noter les titres pour la drôlerie dans la grille. Le second
  choix touche la grille du §4.2 : c'est au superviseur de trancher.
- **`director.md` est à 34 999** : la marge de 1 000 caractères du §10 tient, à un caractère près. Toute retouche future
  devra retirer ce qu'elle ajoute. **`storyteller.md` est à 8 894 sur 9 000.**
- **Rejouer une situation de soirée** : un fichier de situation avec `"transcript"` (un extrait de transcription,
  `excerpt.py` dans l'archive le fabrique), `"turn"` (le tour), `"prompt_sha"`. Le tour réel ne compte comme A#0 que si
  le prompt A a ce sha.
- **Le rejeu bon marché** (depuis `TheMod`, tunnel ouvert) :
  ```
  $env:NARRATOR2_REPLAY_A = "<director.md de main>"
  $env:NARRATOR2_REPLAY_B = "<le nouveau director.md>"
  $env:NARRATOR2_REPLAY_ONLY = "busy_welcome,lisette_return,firmin_nether,quiet_boredom"
  $env:NARRATOR2_REPLAY_SAMPLES_A = "1"; $env:NARRATOR2_REPLAY_SAMPLES_B = "2"; $env:NARRATOR2_REPLAY_REAL = "true"
  $env:NARRATOR2_REPLAY_STORYTELLER = "true"; $env:NARRATOR2_REPLAY_BUDGET = "2"
  .\gradlew.bat liveTest --tests "*DirectorReplayEval*"
  ```
  Environ 1,4 $ avec le juge. La garde de budget réserve 0,25 $ par requête plus la réserve du juge : un lancement
  s'arrête environ 0,4 $ avant son plafond.
- **T4 à lancer** (environ 0,30 $) : `.\gradlew.bat liveGameTest "-Pnarrator2.live.filter=narrator2-gametest:live_style_*" "-Pnarrator2.live.parts=T4"`,
  puis vérifier dans `build/run/liveGameTest/live-report/style.md` qu'aucune idée `challenge` ne repose sur une règle
  que rien ne vérifie, et que le juge d'idées les tient toutes pour réalisables.
- **Pour M6-FIX-11** (la mesure de longueur dans la relecture) : aucune réplique de B n'a été notée hors de la voix pour
  sa longueur (20/20). Je n'ai rien touché de la relecture.

## Vérifications

- **Tests ciblés**, avant les lancements réels : `ReplayHarnessTest` (18, dont 1 nouveau), `PromptsTest`,
  `PromptToolMentionsTest`, `PersonaFrozenTest`, `TestMethodsReturnUnitTest` : verts.
- **Build complet** (`.\gradlew.bat build --continue`) après `git merge main` (déjà à jour) : **2 271 tests unitaires,
  0 échec, 0 ignoré** (`TestMethodsReturnUnitTest` compris) ; **« All 402 required tests passed »** (GameTests), en
  9 min 51 s. Aucun test instable rencontré.
- **Les lancements réels** : 2, verdict « B PASSES » les deux fois. Transcriptions, `report.md` et `samples.json` sont
  archivés ; `analysis.md` les résume situation par situation (`analyze.py`).
- **Tunnel** : rien n'écoutait sur 8088. J'ai ouvert le mien (boucle de M6-18 renommée, ssh 3020), testé par
  `GET /v1/models` (200), puis fermé : seul le mien, et le port n'écoute plus.
- **La clé** n'a jamais été affichée. Une recherche exacte ne la trouve dans aucun des 1 175 fichiers de l'archive, de
  `TheMod/src`, de `docs` et des rapports du rejeu.
- **Archives** (`C:\Users\Clément\Desktop\Narrator2\.research\m6-live\m6-fix-13\`) : `director-A-main-0ec6854dd53e.md`,
  `director-B1.md`, `director-B2.md` (la version finale), `prompts-B1.diff`, `prompts-final.diff`, `replay/` (les deux
  lancements), `analysis.md`, `analyze.py`, `excerpt.py`, `logs/` (journaux Gradle, script et journal du tunnel).

## Décisions du superviseur (22/10/2026)

Fusionnée (`25ccb33`).

1. **T4 non lancé.** Il passera dans la vérification réelle finale de M6, une fois les quatre lots fusionnés, avec la
   soirée calme. Elle couvre ensemble le moteur (M6-FIX-11), le Chroniqueur (M6-FIX-12) et ces prompts.
2. **Les titres.** La grille §4.2 ne change pas : on ne déplace pas le seuil pour faire passer la mesure. On ne retouche
   pas non plus `director.md` de nouveau, faute de place. Sur les répliques parlées, B atteint 1,73, au-dessus du seuil
   de 1,5. La question des titres aux simples accueils passe dans la liste du propriétaire.
3. **Relecture du propriétaire** : les retouches listées dans la passation entrent dans la liste des points à relire à la
   fin de M6.
4. **Marges.** Il ne reste qu'un caractère de marge sous le plafond de 35 000 de `director.md`, et 106 sous celui de
   9 000 de `storyteller.md`. Désormais, toute retouche de ces deux prompts retire autant qu'elle ajoute.
