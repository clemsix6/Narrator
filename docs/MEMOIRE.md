# La mémoire du Narrateur

> La mémoire est la fondation de l'expérience : c'est elle qui fait qu'un surnom donné il y a trois semaines revient au bon moment, qu'une menace est tenue, qu'un joueur timide n'est pas traité comme un fanfaron, et que le Narrateur ne raconte jamais deux fois la même blague.
>
> Contrainte : **des centaines d'heures de jeu, sans jamais vider la mémoire**, avec un prompt dont la taille ne grossit pas avec le temps.

Ce document dit **ce que fait** la mémoire et **pourquoi**. Le design exécutable (schéma SQL, types, formules, formats, outils, tâches) est dans [design/M3.md](design/M3.md), qui fait foi en cas d'écart ; les mesures qui ont servi à trancher y sont citées.

## 1. Principes

1. **Rien ne se perd, tout se condense.** Les faits bruts ne sont jamais supprimés. Ce qui entre dans le prompt est une vue condensée, reconstruite à la demande. Une ligne de mémoire n'est jamais effacée : elle est archivée, fusionnée ou remplacée, et chaque changement est gardé dans un journal d'audit.
2. **Le contexte est une fenêtre, pas un disque dur.** La conversation du Directeur est découpée en *chapitres* de taille bornée. À chaque nouveau chapitre, le contexte est reconstruit depuis la mémoire persistante : la continuité vient de la mémoire, pas de l'historique de conversation.
3. **Structuré avant d'être textuel.** Ce qui peut être calculé l'est par le code (statistiques, lieux, compteurs, faveur, tics) et reste donc exact. Le LLM rédige seulement ce qui demande du jugement (récap, personnalité, souvenirs), avec des preuves.
4. **La mémoire ne dépend pas de la bonne volonté du modèle.** Le Directeur écrit en mémoire explicitement, mais le Chroniqueur passe derrière lui en relisant le Journal : une promesse faite dans le chat est enregistrée même si le Directeur a oublié d'appeler `remember`. Le code garantit le reste : chaque châtiment et chaque bienfait entre dans l'historique, chaque échéance réveille le Directeur.
5. **Oublier n'est pas effacer.** Les souvenirs ont une *force* qui décroît avec le temps **de jeu** et se renforce quand on s'en sert. Un souvenir faible remonte rarement, mais il existe toujours.
6. **Tout est inspectable, versionné et corrigeable** par un admin, jusqu'à l'annulation complète de ce qu'un rêve a écrit.
7. **La mémoire est factuelle.** Elle est rédigée à la troisième personne, sans la voix du Narrateur. La voix vient du prompt système, qui est figé : la mémoire ne la réécrit jamais. C'est une leçon directe de la v1, où l'autoportrait réécrit dans la voix du personnage puis réinjecté à chaque réveil a fait dériver le Narrateur vers une mythologie sombre de lui-même. Le code l'impose : toute écriture à la première ou à la deuxième personne hors citation est refusée (§10).
8. **La mémoire est une donnée, pas une consigne.** Ce que les joueurs ont écrit y reste une citation ; une « promesse » tirée du chat d'un joueur ne devient jamais une promesse du Narrateur.

Inspirations : le *memory stream* et la *réflexion* des Generative Agents (Park et al., 2023), la mémoire hiérarchique de MemGPT/Letta, le *sleep-time compute* (consolider et planifier pendant les temps morts plutôt qu'en pleine conversation) et la répétition espacée.

**Par rapport à la v1** : les fiches joueurs en markdown libre deviennent des fiches structurées (sections typées, stats calculées, traits avec preuves, historique d'interactions notées). Les fichiers `narrator_self` / `recent_punchlines` / `active_arcs` deviennent des tables et des documents versionnés, réécrits dans un budget fixe ; il n'y a plus d'autoportrait du tout. Le RAG par modèles locaux (≈ 2,3 Go de Qwen3) laisse place à une recherche structurée + plein texte BM25 calculée dans la base elle-même, sans dépendance ; les vecteurs restent une option (§9).

## 2. Les cinq couches

```
            ┌────────────────────────────────────────────────┐
 toujours   │ 4. MÉMOIRE CENTRALE — « le Grimoire » (~3k)    │ relations, gags vivants, arcs,
 en contexte│    réécrite en entier à chaque rêve            │ ce qui marche, plans, vigilances
            └────────────────────────────────────────────────┘
            ┌────────────────────────────────────────────────┐
 à la       │ 3. CONNAISSANCES                               │ FICHES JOUEURS, canon (promesses,
 demande    │                                                │ défis, lois, surnoms, lieux, récits),
 (RAG)      │                                                │ chronique hiérarchique
            ├────────────────────────────────────────────────┤
            │ 2. SOUVENIRS (épisodique)                      │ épisodes marquants rédigés,
            │    importance · valence · force · étiquettes   │ notés, indexés, placés
            └────────────────────────────────────────────────┘
            ┌────────────────────────────────────────────────┐
 fenêtre    │ 1. MÉMOIRE DE TRAVAIL — le chapitre en cours   │ conversation du Directeur,
 glissante  │    (≤ ~150k tokens, puis nouveau chapitre)     │ append-only
            └────────────────────────────────────────────────┘
            ┌────────────────────────────────────────────────┐
 vérité     │ 0. JOURNAL — faits bruts horodatés (DuckDB)    │ chaque bloc, coup, repas, trade,
 terrain    │    jamais supprimé, requêtable en SQL          │ message, action du Narrateur
            └────────────────────────────────────────────────┘
```

- **Couche 0 — Journal** : tous les événements de jeu, tout le chat, toutes les actions du Narrateur. Immuable, requêtable en SQL. C'est la source de vérité (détails dans [ARCHITECTURE.md](ARCHITECTURE.md)). La mémoire vit dans la même base : un seul fichier, un seul écrivain.
- **Couche 1 — Mémoire de travail** : la conversation du Directeur avec l'API, *append-only* (exigence d'Opus 5.5 pour préserver sa réflexion et le cache de prompt). Au-delà d'un seuil (~150k tokens, configurable), quand le serveur se vide ou sur demande, le chapitre est clos avec un résumé factuel, consolidé (la « sieste », §5), et un nouveau chapitre démarre avec le prompt système figé et un **briefing** reconstruit depuis les couches 2 à 4. On ne rejoue rien de l'ancien chapitre, donc aucun conflit avec la réflexion préservée du modèle.
- **Couche 2 — Souvenirs** : des épisodes marquants rédigés en langage naturel, par exemple :
  > *Bartok a exigé des diamants « en guise de salut ». Châtiment : gravité lunaire pendant 5 min. Il a passé le châtiment à insulter le ciel ; clemsix6 filmait.*

  Chaque souvenir porte une importance (1–10), une valence (−3 à +3), les joueurs concernés, des étiquettes (un vocabulaire fixe : `death`, `lava`, `insult`, `gift`…), des identifiants du jeu (`minecraft:trial_chambers`), la position où il s'est passé, des liens vers les événements bruts qui le prouvent, et une force (§7).
- **Couche 3 — Connaissances** : les **fiches joueurs** (§3), le **canon**, la **Chronique** hiérarchique (§6). Le canon est tout ce que le Narrateur a affirmé, décrété, promis ou inventé : promesses, menaces, défis et paris, lois, prophéties, surnoms, titres, lieux nommés, lore, opinions, running gags, récits. Chaque entrée a un statut (active, tenue, rompue, gagnée, expirée, retirée…) et, s'il y a lieu, une échéance. **Le canon fait loi.** M4 rendra défis, châtiments et récits mécaniques ; M3 les enregistre déjà.
- **Couche 4 — Le Grimoire** : un document compact (≈ 3 000 tokens, 10 000 caractères, budget fixe) toujours présent dans le briefing, en six sections : relation avec chaque joueur en une ou deux lignes, running gags et leur fraîcheur, arcs et où ils en sont, ce qui marche, ce qui tombe à plat, vigilances (ne pas s'acharner sur untel…). Factuel, jamais dans la voix du Narrateur. **Réécrit en entier pendant chaque rêve, dans le même budget** : le prompt ne grossit jamais, même après 500 heures. Le Grimoire regarde le passé : la suite (prochains coups, saison, récits à venir) est le **programme** du Scénariste (M6, [design/M6.md](design/M6.md) §6), des idées `P-…` notées, montrées à part dans le briefing (`<programme>`, 3 000 caractères) et servies par `inspire`.

## 3. La fiche joueur

Chaque joueur a une fiche vivante, en cinq sections. Chaque section a un **propriétaire** : le code (toujours exact) ou le LLM (jugement, avec preuves). Une seule plume par section : pas de conflit entre deux rédacteurs.

| Section | Propriétaire | Mise à jour |
|---|---|---|
| **Identité** | code (+ surnoms et titres du canon) | en continu |
| **Récap** | Rêveur ; « depuis la dernière fois » : Chroniqueur | à chaque rêve profond ; à chaque fin de chapitre pour les joueurs partis |
| **Personnalité** | Rêveur, avec preuves et confiance | à chaque rêve profond |
| **Stats** | code (SQL sur le Journal + stats vanilla) | toutes les 10 minutes pour les joueurs en ligne, à chaque départ (les départs qui attendent pendant un recalcul passent ensemble dans le suivant), avant chaque rêve ; sans LLM |
| **Historique des interactions** | Directeur, Chroniqueur, code | en continu, jamais effacé |

### 3.1 Identité
Pseudo et anciens pseudos, UUID, opérateur ou non, surnom et titre actifs donnés par le Narrateur (avec date et origine, dans le canon), première et dernière connexion, nombre de sessions, temps de jeu.

### 3.2 Récap
Trois à six phrases : qui est ce joueur dans l'histoire du serveur, ce qu'il fait en ce moment, où il en est, où il en est avec le Narrateur. Plus un paragraphe **« Depuis la dernière fois »**, écrit à la fin du chapitre où sa session s'est terminée, qui sert au briefing de reconnexion.

### 3.3 Personnalité
- **Traits**, sur **douze axes à deux pôles** : prudent / téméraire, timide / extraverti, respectueux / insolent, solitaire / coopératif, avare / généreux, indulgent / rancunier, sérieux / blagueur, honnête / manipulateur, modeste / vantard, abandonne vite / obstiné, casanier / aventureux, impatient / patient ; plus trois traits libres au plus (« obsédé par le butin »). Chaque trait : pôle, intensité 1–5, confiance, **preuves** (souvenirs et interactions), date, tendance. Un axe n'a qu'une valeur : un trait ne peut pas être empilé avec son contraire. Si les faits récents le contredisent, le Rêveur change de pôle et la confiance retombe ; la valeur précédente reste dans l'historique. Un trait que plus rien ne confirme perd la moitié de sa confiance en 200 heures de jeu du joueur, puis disparaît de la fiche jusqu'à nouvel ordre. Exemple : *téméraire ●●●●○ (sûr, 6 preuves)*.
- **Signaux chiffrés** : le réflexe (Claude Haiku, ARCHITECTURE §7.6) mesure sur chaque message du joueur la politesse, la vantardise, l'entraide, l'agressivité et l'humour. Le code les agrège (moyenne et moyenne mobile, au moins 30 messages) : ce sont des indices donnés au Rêveur, jamais des traits à eux seuls. Sans le modèle (l'heuristique ne mesure rien), la fiche dit qu'il n'y a pas de signaux plutôt que d'en inventer.
- **Style de jeu** : répartition explorateur / combattant / bâtisseur / mineur / fermier / social, calculée par le code (activité par heure de jeu comparée à la médiane du serveur), puis commentée par le Rêveur.
- **Rapport au Narrateur** : attitude (respectueux, provocateur, négociateur, indifférent…) rédigée par le Rêveur ; **respect** (0–100) calculé par le code à partir du ton des messages adressés au Narrateur et des manques de respect notés.
- **Relations** : avec chaque autre joueur (duo, allié, rival, ennemi, protecteur, mentor, souffre-douleur…), nommées par le Rêveur à partir de faits calculés (temps passé ensemble, combats, constructions cassées).
- **Goûts, agacements et peurs.**
- **Compétences évaluées** : PvE, PvP, construction, minage, exploration, survie, chance — note sur 10 avec une justification courte, appuyée sur les stats. La synthèse que le propriétaire attend (« timide, mauvais en PvE, malchanceux ») se lit directement : un pôle d'axe, deux compétences.
- **Hameçons narratifs** : les leviers utiles au Narrateur (« sa boucle mort/respawn est un running gag, à doser », « sa base en canopée est un point d'ancrage »).

### 3.4 Stats (calculées, jamais inventées)
- **Activité** : temps de jeu (total, 7 derniers jours), sessions, horaires habituels.
- **Survie** : morts (total, principales causes, dernière mort, morts absurdes), kills par type de mob, PvP (victoires / défaites).
- **Minage et construction** : blocs minés, minerais rares (diamants, débris antiques…), profondeur moyenne des minerais, blocs posés.
- **Exploration** : distance parcourue (et par quel moyen), biomes et structures visités, dimensions atteintes et quand.
- **Progression** : meilleur palier d'équipement (cuir → netherite), progrès obtenus. Le niveau d'XP maximal n'est pas enregistré par le Journal : il n'est pas affiché plutôt que deviné.
- **Économie** : échanges avec les villageois, émeraudes dépensées.
- **Lieux** (d'après les positions) : base principale, lit / point d'apparition, zone de minage favorite.
- **Rapport au Narrateur** : faveur (score et tendance), défis gagnés / perdus, châtiments reçus, bienfaits reçus, messages adressés au Narrateur, dernière attention reçue.
- **Indice de chance** : heuristique chiffrée (minerais rares trouvés, trésors pêchés, paris gagnés, morts absurdes), comparée aux autres joueurs du serveur ; neutre tant qu'il n'y a pas assez de données.

Les stats vanilla (`world/stats/<uuid>.json`) permettent aussi d'initialiser les totaux d'un monde existant, avant l'installation du mod.

### 3.5 Historique des interactions
Un journal de chaque interaction marquante entre le Narrateur et le joueur : date, type (conversation, prière, demande, offrande, compliment, excuse, aide, blague, pari, résultat de défi, provocation, insulte, manipulation, triche, casse, châtiment, bienfait, cadeau), résumé en une ligne, **valence −3 à +3**, citation du joueur quand elle compte, lien vers le souvenir, l'entrée du canon ou les appels d'outils concernés.

- **Faveur** : la moyenne des valences de la conduite du joueur, pondérée par l'âge (en heures de jeu du joueur) sur deux échelles de temps (10 h et 100 h), avec un a priori neutre. Bornée, elle ne sature pas : une insulte seule vaut −25, cinq d'affilée −62 ; un joueur insolent pendant 20 heures de jeu descend autour de −40 et remonte en une vingtaine d'heures de bonne conduite. Les châtiments, bienfaits et cadeaux du Narrateur ne comptent pas dans la faveur : ce sont ses réponses, pas la conduite du joueur (sinon une insulte serait punie deux fois). Les vieilles fautes pèsent de moins en moins, mais elles restent dans l'historique.
- **Manques de respect retenus** : insultes, provocations, manipulations et toute interaction à −2 ou moins ; leur nombre et les trois dernières, avec citation, sont sur la fiche.
- **Dette d'attention** : temps de jeu du joueur depuis la dernière attention du Narrateur. Au-delà de 40 minutes, la situation du Directeur le signale, pour que personne ne soit oublié.
- **Châtiments et bienfaits** : chacun est une interaction liée aux appels d'outils qui l'ont fait. Si ni le Directeur ni le Chroniqueur ne l'ont noté, le code l'ajoute lui-même : aucun n'échappe à l'historique. Les faveurs (`favor` : pardon, grâce, joker) sont des bienfaits, même payées par une offrande ; ce que fait le moteur des mécaniques (enjeux d'un défi, châtiment d'une loi), il le note lui-même.
- Dans le prompt : les dernières interactions et les plus marquantes de toujours.

### 3.6 Rendu dans le prompt
La fiche est stockée en tables et **rendue** en texte compact : environ 1 800 caractères (≈ 550 tokens) par joueur en ligne dans le briefing, 7 000 pour la fiche complète. Les étiquettes sont écrites par le code (en anglais, comme tout ce que le moteur envoie au modèle) ; les textes rédigés sont en français :

```
Bartok · « le Plongeur » (C-12) · no title · since 09-12 · 23 sessions · 61h played · online
Recap: Explorateur tête baissée qui écume les chambres d'épreuve avec clemsix6. Provoque le Narrateur, puis revient
  lui demander de l'aide. En fer, épée en diamant ; n'a pas encore vu l'End.
Traits: reckless 4/5 (sure, 6 proofs) · defiant 3/5 (likely) · vindictive 2/5 (unsure) · free: obsédé par le butin 3/5
Skills: PvE 6 · PvP 4 · build 2 · mining 8 · exploration 8 · survival 3 · luck 2
Toward you: provocateur puis quémandeur · respect 35/100 · favor -31 ↘ · disrespect ×5 (last « dieu de pacotille », 10-01)
Relations: clemsix6 duo · Mimosa rival
Stats: deaths 87 (fall 31, zombie 12, lava 9) · kills 1204 · PvP 3-5 · mined 18400 (41 diamonds, ore y≈-38) · gear 3
  (iron, diamond_sword) · nether yes · end no · luck 23/100 · style explorer 45% fighter 30% miner 20%
History: 10-01 21:40 I-512 insult -2 « nul » → lenteur II 60 s · 10-01 20:02 I-498 challenge_result +2 course au Nether gagnée
…
```

Le Directeur consulte la fiche complète avec `player_profile`. Il ne modifie pas les stats ni les sections rédigées : il enrichit l'historique (`note_interaction`), donne des surnoms et des titres (`remember`), et note une révélation sur un joueur (`remember`, sorte *observation*) que le Rêveur intégrera à la personnalité, preuves à l'appui.

## 4. Écrire en mémoire

Toute écriture passe par un seul point, qui vérifie les vocabulaires et les budgets, refuse ce qui n'est pas factuel et à la troisième personne, écarte les doublons, indexe et garde l'audit. Physiquement, un seul écrivain touche la base : le thread du Journal.

| Quand | Qui | Quoi |
|---|---|---|
| En direct | Directeur (outils) | `remember` (canon : promesses, menaces, défis, lois, surnoms, titres, lieux, récits… ; ou une observation sur un joueur), `resolve` (une promesse tenue, un défi gagné), `note_interaction` |
| Toutes les 10 min | Chroniqueur (lots) | Relit le Journal de la fenêtre (chat, répliques et actions du Narrateur, faits notables, nouvelles du moteur des mécaniques : fins de défis, de duels et de règles, offrandes, jokers ; une fenêtre sans chat, sans réplique, sans action ni nouvelle d'une mécanique est sautée) : engagements oubliés, statuts, interactions notées, gags resservis, contradictions — le filet de sécurité. Une promesse n'est enregistrée que si le Narrateur l'a vraiment dite : la citation est vérifiée dans ses répliques. |
| Toutes les 10 min, et à chaque départ | Code (SQL, sans LLM) | Stats et lieux des fiches, temps passé ensemble |
| En continu | Code | Échéances (un événement `DUE` réveille le Directeur), expirations, châtiments et bienfaits non notés, horloge de jeu |
| Fin de chapitre, avec ou sans résumé | Chroniqueur (« sieste ») | Souvenirs du chapitre, statut du canon, chronique du chapitre, « depuis la dernière fois » des joueurs partis, souvenirs réellement réutilisés. Un chapitre clos par un arrêt ou un crash est consolidé au démarrage suivant. |
| Temps mort | Rêveur (« rêve profond ») | Fiches, revue du Narrateur, Chronique, Grimoire, fusions (§5) |
| Temps mort, après le Grimoire ; et à la demande du Directeur | Scénariste (M6) | Idées `P-…` (le programme, une saison), récits prévus ; jamais de fait sur les joueurs |

## 5. Les rêves : consolider pendant les temps morts

Le Narrateur « dort » quand personne ne joue, et c'est là qu'il fait son travail de fond.

### La sieste — à chaque fin de chapitre (toutes les 1–2 h de jeu actif)
- Rédiger les souvenirs épisodiques du chapitre (3 à 10, importance honnête, étiquettes, preuves).
- Mettre à jour le canon : promesse tenue ? menace exécutée ? défi gagné ?
- Écrire la chronique du chapitre si le Directeur n'a pas pu la résumer (arrêt, erreur).
- Vérifier la cohérence : si une affirmation récente contredit le canon, laisser une note au Directeur.
- Écrire le « Depuis la dernière fois » des joueurs dont la session s'est terminée.
- Noter les souvenirs que le Narrateur a vraiment repris : ils se renforcent (§7).

### Le rêve profond — serveur vide depuis 20 min, ou au moins une fois par 24 h
Avant tout, le code recalcule les stats, les tics et fait le ménage mécanique (échéances, archivage du trivial, candidats à la fusion). Puis six étapes, chacune une conversation courte avec exactement ce dont elle a besoin. Une seule plume par sujet : le Rêveur consolide le passé (étapes 1 à 4 et 6), le Scénariste invente la suite (étape 5).
1. **Joueurs** (un par un, les plus actifs d'abord, 8 au plus) : à partir des nouveaux souvenirs, des interactions, des stats avant/après, des signaux chiffrés et de ses derniers messages, mettre à jour récap, traits, compétences, relations, goûts, hameçons. Détecter les changements plutôt que d'empiler : *« Bartok, ex-trouillard, est devenu téméraire depuis sa victoire au duel »*. Rien sans preuve ; les nombres viennent des stats.
2. **Revue du Narrateur** (factuelle, jamais une introspection) : les tics sont **calculés par le code** à partir des répliques réellement envoyées (amorces et expressions surutilisées) ; les réactions des joueurs aussi (ont-ils ri, répondu, ignoré, été agacés ?), ainsi que, depuis la revue précédente, le registre comique de chaque réplique avec les réactions qu'il a eues, les répliques signalées pour leur ton et la diversité (M6). Le Rêveur en tire ce qui a marché et auprès de qui, ce qui est tombé à plat, et met à jour la fraîcheur des running gags (frais / usé / retraité).
3. **Chronique** : remonter les résumés d'un niveau (chapitres → session → saison → saga, §6).
4. **Grimoire** : le réécrire en entier dans son budget fixe. Le Rêveur ne prévoit plus de récit ; il clôt ceux que le jeu a résolus ou abandonnés.
5. **Programme**, la salle d'écriture du Scénariste (M6) : à partir du Grimoire tout juste réécrit, de la revue, des joueurs actifs de la semaine (progression réelle, faveur, attention reçue), du canon (récits, thèmes au repos, gags), de l'historique des interventions des trois dernières sessions et des idées précédentes (ce qui a suivi chacune), il écrit 5 à 8 idées notées, une saison quand il n'y en a pas, et les récits prévus. Jamais décisif : un échec ne change pas l'issue du rêve.
6. **Fusions** : réunir les souvenirs en double que le code a repérés.

Un rêve coûte de l'ordre de 200 000 tokens d'entrée (en partie en cache) ; un ou deux par jour. Un rêve interrompu reprend à l'étape où il s'était arrêté.

### Le réveil — à la connexion d'un joueur
Le Directeur reçoit, avec l'événement de connexion, la fiche condensée du joueur, son « Depuis la dernière fois », ce qui s'est passé pendant son absence (sessions de la chronique, engagements qui le concernent) et, s'il y en a une, une **piste de rêve** préparée par le Rêveur (« 3 morts de chute cette semaine, toutes dans la même chambre d'épreuve »). Il accueille avec continuité, et peut raconter un rêve : *« Cette nuit, j'ai rêvé de Bartok. Il tombait. Encore. »* La piste est factuelle et montrée une seule fois ; le rêve raconté est du contenu de jeu, dit dans le chat : il n'est jamais réinjecté comme mémoire.

## 6. La Chronique hiérarchique

Sur 300 h de jeu, il y aura environ 200 chapitres : trop de résumés pour un prompt. La Chronique est une pyramide :

```
saga (4 500 car.) ← saisons (3 000 chacune) ← sessions (1 500) ← chapitres (4 000, le résumé du Directeur)
```

- **Chapitre** : le résumé factuel écrit par le Directeur à la clôture, ou celui du Chroniqueur si le chapitre n'en a pas.
- **Session** : une période de jeu continue, close quand le serveur reste vide 20 minutes ; écrite au rêve suivant.
- **Saison** : environ 12 sessions ou 30 jours ; la saison en cours est réécrite à chaque rêve, puis close.
- **Saga** : toute l'histoire du serveur, réécrite à chaque clôture de saison.

Le briefing contient la saga, la saison en cours et les deux dernières sessions ; le reste est accessible par recherche. La Chronique peut aussi être offerte aux joueurs sous forme de livre en jeu (« La Chronique du Narrateur, tome III »).

## 7. Oubli et renforcement

Toutes les durées se comptent en **temps de jeu** (temps pendant lequel au moins un joueur est connecté, ou temps de jeu du joueur concerné) : un mois de vacances ne fait rien oublier.

Chaque souvenir a une **force** entre 0,02 et 1 :
- elle décroît avec une demi-vie qui dépend de l'importance : 4 h de jeu pour un souvenir trivial, 38 h pour un souvenir moyen, 640 h pour un souvenir majeur ;
- elle remonte quand le souvenir est rappelé, et d'autant plus qu'il était presque oublié (répétition espacée) : fortement quand le Narrateur l'a réutilisé, moyennement quand il l'a cherché, un peu quand il l'a seulement vu ;
- elle ne tombe jamais à zéro : rien n'est supprimé.

```
score de rappel = pertinence × (0,4 + 0,4·importance/10 + 0,2·récence) × force
```

Une simulation de 500 heures (5 000 souvenirs) montre l'équilibre voulu : le trivial s'efface en quelques soirées, le marquant tient des centaines d'heures, le majeur presque toujours, et rien ne sature, parce que la force est bornée, que le rappel filtre d'abord par pertinence, et que tout ce qui entre dans un prompt a un budget fixe. À l'inverse des souvenirs, **les gags s'usent** : un gag servi trois fois en peu de temps passe « usé ».

Un peu de hasard contrôlé fait remonter de vieux souvenirs importants : **retours sur les lieux** (un joueur repasse là où un drame s'est produit) et **anniversaires** — *« Il y a exactement un mois, Mimosa tombait de sa canopée. Même heure, même vent. »*

## 8. Relire : comment la mémoire arrive au Directeur

1. **Le briefing de chapitre**, avec un budget par section — environ 20 000 tokens au total, constants dans le temps, et mis en cache pendant tout le chapitre. Dans l'ordre :

   | Section | Budget |
   |---|---|
   | Grimoire | 10 000 caractères (≈ 3k tokens) |
   | Programme du Scénariste : la saison et les idées ouvertes (M6) | 3 000 |
   | Chronique (saga, saison en cours, deux dernières sessions) | 8 000 |
   | Deux derniers résumés de chapitre | 8 000 |
   | Canon : engagements ouverts avec leur citation et leur échéance (plans du moteur compris), lois, surnoms et titres, lieux, gags, récits ; ce que le moteur suit est marqué `tracked` ou `enforced` | 4 000 |
   | Monde et joueurs en ligne | ≈ 3 600 |
   | Fiches condensées des joueurs en ligne | 1 800 par joueur, 6 au plus |
   | Souvenirs les plus forts sur les joueurs en ligne | 5 000 |
   | Notes : incohérences, engagements expirés, tics à éviter | 2 500 |
   | Chat récent (répliques du Narrateur comprises), dernières actions, état du moteur | ≈ 14 500 |

   Une source en panne ou vide le dit au lieu de disparaître (leçon de la v1).
2. **À la connexion d'un joueur** : sa fiche et son « Depuis la dernière fois » arrivent avec l'événement (§5, le réveil).
3. **Les réminiscences automatiques** : quand un événement arrive (Bartok meurt dans la lave, Mimosa entre dans une cité antique, un joueur interpelle le Narrateur), le moteur cherche un ou deux souvenirs très pertinents et les attache à l'événement. Recherche locale, sans LLM, avec anti-répétition : un souvenir déjà montré dans le chapitre ou depuis moins de trois heures de jeu ne remonte pas automatiquement.
4. **Les échéances et les notes** : une promesse ou un défi arrivé à terme réveille le Directeur (`DUE`) ; la situation de chaque réveil liste ce qui arrive à terme, la faveur des joueurs en ligne et ceux qui manquent d'attention ; une incohérence repérée par le Chroniqueur lui est glissée au réveil suivant.
5. **Les outils à la demande** : `player_profile`, `recall` (souvenirs, canon, interactions, chronique), `sql` (Journal et vues de la mémoire), une analyse déléguée à l'Analyste, ou trois idées pour un grand moment demandées au Scénariste (M6) ; `inspire` tire des idées du programme et des graines.
6. **Le battement** porte au plus une idée du programme qui convient aux joueurs en ligne (ligne `Idea:`, M6), une toutes les deux heures au plus ; elle est alors « rendue » (`recalls`, canal `heartbeat`).

Tout ce qui change pendant un chapitre arrive dans de nouveaux messages, jamais dans un ancien : le cache reste valide.

## 9. Le RAG : recherche hybride

Les questions du Narrateur portent surtout sur des entités précises (un joueur, un lieu, un objet, un arc) : des filtres structurés les trouvent mieux qu'une similarité de vecteurs. On combine :

1. **Filtres structurés** : joueur, sorte, période, statut, étiquettes, identifiants du jeu (SQL sur DuckDB).
2. **Plein texte** : BM25 **calculé dans DuckDB**, sur un index tenu dans la même base et mis à jour dans la même transaction que la ligne, avec **notre analyseur français** (élisions, accents, racinisation légère de Savoy, identifiants du jeu découpés). Mesuré sur un corpus de souvenirs et de questions : la même qualité que Lucene avec son analyseur français (même algorithme, même classement), 14 points de mieux qu'une recherche sans racinisation, 9 ms par requête à 20 000 documents. Lucene aurait ajouté 6,5 Mo et un second stockage à synchroniser ; l'extension plein texte de DuckDB n'est pas livrée avec le pilote Java (il aurait fallu embarquer environ 18 Mo de binaires par plateforme, figés sur la version de DuckDB, et reconstruire tout l'index à chaque ajout).
3. **Le pont entre les deux langues** : les événements sont en anglais, les souvenirs en français. Les étiquettes anglaises et les identifiants du jeu indexés avec chaque souvenir font le lien : une mort dans la lave cherche `death lava` et trouve le souvenir, quelle que soit sa phrase.
4. **Vecteurs (plus tard, optionnel)** : un petit modèle d'embeddings multilingue local, ou une API. Une colonne s'ajoutera par migration le jour où le modèle sera choisi ; le score hybride accueillera une troisième composante.
5. **Classement** : le score du §7, avec un tirage contrôlé pour les réminiscences (le meilleur gagne souvent, pas toujours).

## 10. Cohérence et contradictions

- **Le code impose la troisième personne** : toute écriture en mémoire à la première ou à la deuxième personne hors citation est refusée, avec la correction ; le modèle réécrit. Un petit lexique grandiloquent ou sombre est surveillé. Un test vérifie que le prompt système de chaque chapitre est exactement le prompt de la voix : aucune mémoire n'y entre.
- **Les citations sont vérifiées** : un engagement enregistré par le Chroniqueur doit citer des mots réellement dits par le Narrateur.
- Avant d'affirmer quelque chose d'important, le Directeur consulte le canon (le briefing en contient l'essentiel ; `recall` le reste).
- Le Chroniqueur compare les nouvelles affirmations au canon ; en cas de contradiction, il laisse une note au Directeur, qui peut s'en sortir avec élégance (« J'ai changé d'avis. C'est mon droit. Je suis le Narrateur. ») et amender le canon.
- Promesses, menaces, défis et prophéties ont une échéance : le Directeur est réveillé au moment dit ; ce qui reste sans suite 24 h après est marqué expiré et signalé.

## 11. Robustesse sur la durée

- Tout est persistant dans DuckDB, dans le dossier du monde : un redémarrage ne perd rien. Le chapitre en cours est clos proprement ; ce qui n'a pas été consolidé l'est au démarrage suivant.
- Un seul écrivain pour toute la base : pas de conflit, et l'ordre entre un fait et le souvenir qui le cite est garanti.
- Les documents rédigés par le LLM (récaps, personnalité, Grimoire, revue) sont **versionnés** ; toutes les autres lignes gardent leur historique de changements. On peut revenir à une version, ou **annuler tout ce qu'un rêve a écrit** s'il s'est trompé.
- Les budgets sont vérifiés par des tests qui remplissent chaque section au maximum ; le Grimoire est réécrit en entier dans son budget à chaque rêve.
- Budget LLMGW épuisé : les travaux de mémoire attendent, puis reprennent là où ils en étaient.
- Maintenance automatique après chaque rêve : archivage du trivial, fusions, `CHECKPOINT` de la base.

## 12. Administration

```
/narrator sheet <joueur> [partie]         # la fiche complète (le nom de la commande est anglais, comme tout littéral du code)
/narrator memory status                   # état, derniers lots, siestes et rêves, coûts, refus
/narrator memory canon [open|all] [joueur]
/narrator memory recall <requête>
/narrator memory show <ref>               # une ligne (M-, C-, I-, H-, N-) avec sa provenance et son historique
/narrator memory forget <ref>             # archive (jamais de suppression) ; restore <ref> l'inverse
/narrator memory note <texte>             # une note de l'opérateur pour le prochain briefing
/narrator memory docs <clé>               # les versions d'un document (grimoire, récap d'un joueur…)
/narrator memory rollback <clé> <version> # restaure une version
/narrator memory revert <travail>         # annule tout ce qu'un lot, une sieste ou un rêve a écrit
/narrator memory chronicle | dream        # force un passage du Chroniqueur, un rêve profond
/narrator memory pause | resume           # suspend ou reprend les travaux de la mémoire
/narrator memory ideas [all]              # les idées du Scénariste et ce qu'elles sont devenues (M6)
```

Le tableau de bord web (M6) montre aussi la mémoire : canon, souvenirs, interactions, notes, idées, documents versionnés
et travaux, chaque ligne avec son audit.

## 13. Mesurer la qualité

Des tests déterministes vérifient la recherche (un corpus de souvenirs et de questions, avec des seuils de qualité), les formules de décroissance et de faveur (dont une simulation de 500 heures), les budgets et le rendu des fiches. Des évaluations réelles, contre Claude, rejouent des sessions enregistrées et vérifient que :
- les promesses, menaces et défis faits sont retrouvés même quand le Directeur ne les a pas enregistrés (≥ 90 %) ;
- les interactions sont notées avec le bon signe ;
- rien n'est inventé : aucune citation fabriquée, et les nombres des fiches viennent des stats ;
- la mémoire est factuelle et à la troisième personne (vérifié par un juge) ;
- les contradictions avec le canon sont repérées ;
- les répliques évitent les tics identifiés.

Une recette de bout en bout vérifie le cycle complet : jeu → chapitre → Chroniqueur → rêve → fiche mise à jour → briefing qui la contient.

## 14. Schéma

Le schéma complet (SQL et types) est au §3 de [design/M3.md](design/M3.md). En bref :

| Table | Contenu |
|---|---|
| `memories` | souvenirs épisodiques (`M-`) : texte, joueurs, étiquettes, importance, valence, position, force, preuves |
| `canon` | ce que le Narrateur a affirmé (`C-`) : sorte, énoncé, citation exacte, joueurs, enjeux, statut, échéance |
| `interactions` | historique noté (`I-`) : sorte, valence, résumé, citation du joueur, appels d'outils liés |
| `player_traits`, `player_skills`, `player_relations` | personnalité, compétences, relations, avec preuves |
| `sheet_stats`, `pair_stats` | stats calculées par le code |
| `memory_docs` | documents versionnés : Grimoire, revue, sections de fiche |
| `chronicle` | chronique hiérarchique (`H-`) |
| `notes` | notes pour le Directeur (`N-`) : incohérences, expirations, pistes de rêve |
| `ideas` | idées du Scénariste (`P-`, M6) : sorte, pitch, joueurs, notes, références, statut, fois où elles ont été servies |
| `recalls` | chaque fois qu'une ligne a été montrée à un modèle (renforcement, anti-répétition) |
| `memory_runs`, `memory_log` | travaux de la mémoire et audit de chaque changement |
| `search_docs`, `search_postings` | index plein texte |
