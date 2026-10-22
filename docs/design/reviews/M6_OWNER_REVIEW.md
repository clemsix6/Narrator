# M6 : ce qui attend le propriétaire

Rédigé par le superviseur à la fin de M6 (22/10/2026). Ce document contient ce que les agents ont tranché à ta place et
qui mérite ton avis : des choix à faire, puis des passages de prompt à relire. Le détail de chaque point est dans les
passations citées (`docs/design/handoff/`).

## 1. Les choix qui t'appartiennent

| # | Question | État actuel | Ce que je recommande |
|---|---|---|---|
| 1 | **Le modèle du Chroniqueur** (il tient la mémoire à jour pendant la soirée) | Opus 5.5. Il coûte ≈ 0,58 $ par heure chargée depuis M6-FIX-12 (contre 0,78 $ avant). | Garder Opus pour l'instant. Sonnet 5.5 ferait ≈ 0,31 $/h, mais personne ne l'a évalué sur ce travail : il faudrait d'abord une éval E1/E2, ≈ 1 $ (M6-FIX-12). |
| 2 | **Ta passerelle LLMGW** déplace notre prompt système après le premier message | Le prompt système ne se relit pas en cache d'une conversation à l'autre. **Tous les rôles** paient sa réécriture (`docs/research/LLMGW.md`, M3-POST, M6-FIX-12). | La corriger côté passerelle : laisser `system` à sa place, avec son marqueur de cache. C'est ton serveur, nous n'y touchons jamais. |
| 3 | **Les noms des registres** (D9) | 16 registres dans `director.md` (ligne 13) : documentaire animalier, commentaire sportif, bureaucratie divine, tribunal, bulletin météo, prophétie ridicule, critique gastronomique, jeu télévisé, conte qui dérape, service client, petites annonces, bande-annonce, guide touristique, horoscope, mode d'emploi, vente aux enchères. « Service client » est désormais sans « céleste ». | Les garder : en réel, « céleste » est passé de 18 % à 0 %, et aucun registre ne dépasse 25 % d'une soirée. |
| 4 | **Les titres à l'écran pour un simple accueil** | Le Directeur en met à l'accueil et à la première entrée dans le Nether. Ces titres sont rarement drôles et tirent la note de drôlerie vers le bas : 1,73 pour les répliques parlées, 1,44 avec les titres (M6-FIX-13). | À toi de voir. Les décourager coûterait une phrase dans un prompt plein. |
| 5 | **L'annonce d'un module qui n'existe pas encore** | Une allusion vague est permise, jamais une promesse précise (choix du superviseur). | Garder. |
| 6 | **Le tableau de bord web** | Optionnel et désactivé par défaut. Pour l'activer : `"dashboard": { "enabled": true }` dans `config/narrator2/narrator2.json`, puis `/narrator restart` et `/narrator dashboard` pour obtenir le lien. | Aucune action. Si tu l'actives un jour, regarde-le dans un vrai navigateur : aucune personne ne l'a encore fait. |
| 7 | **Le mod se charge aussi côté client** (`"environment": "*"`) | Le Narrateur marche en solo et en LAN (serveur intégré). | Garder. Le seul risque connu, deux ouvertures du Journal sur le même fichier, est fermé (M6-POLISH). |
| 8 | **Les anciens dossiers `.claude/worktrees/`** | Des restes de worktrees d'agents, sans branche. | Les supprimer quand tu veux. Je ne le fais pas sans ton accord. |

## 2. Les passages de prompt à relire (le ton)

Les prompts sont pleins : `director.md` fait 34 999 caractères pour une marge visée de 35 000, et `storyteller.md`
8 894 sur 9 000. Toute retouche devra retirer autant qu'elle ajoute.

**`director.md`**
- **La voix et la mission** :
  - « Le plat » : un accueil, une ouverture ou l'annonce d'un jeu porte une vraie pointe (M6-FIX-13).
  - « La lisibilité » : un châtiment mystérieux, ou un défi dont on ignore la fin, ressemble à un bug (M6-FIX-13).
  - Les tics proscrits : « Mesdames et messieurs », « Bienvenue au Grand… », les poules en orbite à tout propos
    (M6-14, M6-FIX-9).
- **`show_title`** : à réserver aux grands moments, avec sa propre pointe ; la réplique ne le répète pas (M6-FIX-13).
- **L'annonce d'un défi** : son but, ses enjeux et sa fin (heure, durée ou nombre de manches), dans une même réplique
  (M6-FIX-13).
- **« Fin. » à chaque fin de tour**, au lieu d'un compte rendu. En réel, 22 tours sur 22 la respectent (M6-16, M6-18).
- **« Ne rien faire »** et le silence : ne rien dire est la décision la plus fréquente, et un début de chapitre n'y
  invite plus (M6-FIX-9).
- **Le compactage de M6-14** : dix points, dont :
  - les refus résumés en une phrase ;
  - la faveur ;
  - le duel (« ne le dis jamais mort ») ;
  - les événements des mécaniques ;
  - `remember` sans la liste de ses engagements ;
  - l'indice.
  
  La liste complète est dans `M6-14.md`, « Points à faire relire ».
- **Les coupes** :
  - le troisième exemple de « Déléguer » (M6-FIX-9) ;
  - quatre passages descriptifs (M6-FIX-13), dont l'introduction de « Ta mémoire ».
- **La section « La Forge »** de M5-20, jamais relue par toi.

**`storyteller.md`** (le Scénariste)
- Les huit points de M6-07 :
  - le cadrage de la salle d'écriture ;
  - ce qui fait une bonne idée ;
  - les leviers ;
  - les tics ;
  - les règles du programme ;
  - les notes honnêtes ;
  - les exemples, bons et mauvais ;
  - le mode à la demande.
- Les poules en orbite « citées à tout propos » (M6-FIX-9).
- Une règle à guetter seconde par seconde demande un module qui l'arbitre ; sinon, l'idée n'est pas proposée
  (M6-FIX-13).
- Le budget se compte en « messages » (M6-FIX-14).

**Les autres prompts**
- `chapter_summary.md` : « Ce n'est pas une fin de tour : le résumé n'est jamais « Fin. », ni une ligne, ni le silence. »
  (M6-FIX-9).
- `chronicler.md` : trois retouches de M6-FIX-12 :
  - « Chaque travail est un message, fais seulement le dernier » ;
  - le paragraphe `unread_from` dans la sieste ;
  - le rapport « en français ».
- `forger.md` : le budget se compte en « messages » (M6-FIX-15).

## 3. Ce que disent les évals réelles

**Ce qui marche (M6-18, M6-19)**
- Le programme du Scénariste : 100 % d'idées réalisables, de 4 à 5 sortes.
- Les commandes au Scénariste.
- 22 tours sur 22 finissent par « Fin. ».
- Aucun résumé de chapitre perdu.
- Aucune ouverture muette.
- Aucun chapitre ouvert par un départ.
- La voix à 100 % sur la dernière soirée.
- Aucune réplique sombre ni cruelle.
- Les modules de la Forge sans faute.

**Les limites**
- La drôlerie tourne autour du seuil de 1,5. Elle vaut 1,41 sur la soirée chargée de M6-18. Ensuite, M6-FIX-13 l'a
  mesurée à 1,73 sur les répliques parlées, et M6-19 à 1,50, sur deux répliques seulement.
- Les échantillons des soirées réelles sont petits : 17 répliques, puis 2.

**Le coût d'une soirée**
- Soirée chargée : ≈ 3 à 4 $ de l'heure (M6-18 : 2,03 $ en 30 min). Une part vient du cache de la passerelle (choix 2).
- Soirée calme : ≈ 1 $ de l'heure.

**Le budget des évals de M6** : ≈ 27,7 $ dépensés pour 12 $ prévus. Tu avais autorisé les tests réels.

## 4. Ta première vraie partie : ce qu'il faut surveiller

- **Un réveil du Directeur qui porte une idée hors de propos** : c'est arrivé deux fois sur deux soirées. Le Directeur
  en a chaque fois choisi une autre (M6-19).
- **Le juge et les duels** : depuis la recalibration, le juge exige une fin pour un duel. Cela ne touche que nos évals,
  pas le jeu (M6-FIX-14).
- **Une idée qui reprend le fond d'une autre sous un autre titre** : les commandes voient maintenant les idées ouvertes,
  mais le magasin ne les refuse pas (M6-FIX-14).
- **Les répliques longues** : le Directeur reçoit une note `long` au plus une fois toutes les 30 min (M6-FIX-11).
- **Une machine chargée** : la preuve CPU de la Forge n'a été mesurée que sur une machine calme (constat 4(a) de
  FORGE-ABANDON).
- **Un chapitre vidé reste ouvert 10 min** : un retour rapide n'a pas de nouvelle ouverture, c'est voulu
  (M6-FIX-11).
