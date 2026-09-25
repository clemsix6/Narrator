# La mémoire du Narrateur

> La mémoire est la fondation de l'expérience : c'est elle qui fait qu'un surnom donné il y a trois semaines revient au bon moment, qu'une menace est tenue, qu'un joueur timide n'est pas traité comme un fanfaron, et que le Narrateur ne raconte jamais deux fois la même blague.
>
> Contrainte : **des centaines d'heures de jeu, sans jamais vider la mémoire**, avec un prompt dont la taille ne grossit pas avec le temps.

## 1. Principes

1. **Rien ne se perd, tout se condense.** Les faits bruts ne sont jamais supprimés. Ce qui entre dans le prompt est une vue condensée, reconstruite à la demande.
2. **Le contexte est une fenêtre, pas un disque dur.** La conversation du Directeur est découpée en *chapitres* de taille bornée. À chaque nouveau chapitre, le contexte est reconstruit depuis la mémoire persistante : la continuité vient de la mémoire, pas de l'historique de conversation.
3. **Structuré avant d'être textuel.** Ce qui peut être calculé l'est par le code (statistiques, lieux, compteurs) et reste donc exact. Le LLM rédige seulement ce qui demande du jugement (récap, personnalité, souvenirs), avec des preuves.
4. **La mémoire ne dépend pas de la bonne volonté du modèle.** Le Directeur écrit en mémoire explicitement, mais le Chroniqueur passe derrière lui : une promesse faite dans le chat est enregistrée même si le Directeur a oublié d'appeler `remember`.
5. **Oublier n'est pas effacer.** Les souvenirs ont une *force* qui décroît avec le temps et se renforce quand on s'en sert. Un souvenir faible remonte rarement, mais il existe toujours.
6. **Tout est inspectable, versionné et corrigeable** par un admin.
7. **La mémoire est factuelle.** Elle est rédigée à la troisième personne, sans la voix du Narrateur. La voix vient du prompt système, qui est figé : la mémoire ne la réécrit jamais. C'est une leçon directe de la v1, où l'autoportrait réécrit dans la voix du personnage puis réinjecté à chaque réveil a fait dériver le Narrateur vers une mythologie sombre de lui-même.

Inspirations : le *memory stream* et la *réflexion* des Generative Agents (Park et al., 2023), la mémoire hiérarchique de MemGPT/Letta, et le *sleep-time compute* (consolider et planifier pendant les temps morts plutôt qu'en pleine conversation).

**Par rapport à la v1** : les fiches joueurs en markdown libre deviennent des fiches structurées (sections typées, stats calculées, traits avec preuves, historique d'interactions notées). Les fichiers `narrator_self` / `recent_punchlines` / `active_arcs` deviennent des tables et des documents versionnés, réécrits dans un budget fixe. Le RAG par modèles locaux (≈ 2,3 Go de Qwen3) laisse place à une recherche structurée + plein texte, légère ; les vecteurs restent une option (§9).

## 2. Les cinq couches

```
            ┌────────────────────────────────────────────────┐
 toujours   │ 4. MÉMOIRE CENTRALE — « le Grimoire » (~4k)    │ identité, état des relations,
 en contexte│    réécrite pendant les rêves                  │ promesses actives, gags vivants
            └────────────────────────────────────────────────┘
            ┌────────────────────────────────────────────────┐
 à la       │ 3. CONNAISSANCES                               │ FICHES JOUEURS, canon, lieux,
 demande    │                                                │ récits, chronique hiérarchique
 (RAG)      ├────────────────────────────────────────────────┤
            │ 2. SOUVENIRS (épisodique)                      │ épisodes marquants rédigés,
            │    importance · valence · force · tags         │ notés, indexés
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

- **Couche 0 — Journal** : tous les événements de jeu, tout le chat, toutes les actions du Narrateur. Immuable, requêtable en SQL. C'est la source de vérité (détails dans [ARCHITECTURE.md](ARCHITECTURE.md)).
- **Couche 1 — Mémoire de travail** : la conversation du Directeur avec l'API, *append-only* (exigence d'Opus 5.5 pour préserver sa réflexion et le cache de prompt). Au-delà d'un seuil (~150k tokens, configurable) ou à la fin d'une session de jeu, le chapitre est clos, consolidé (la « sieste », §5), et un nouveau chapitre démarre avec le prompt système figé et un **briefing** reconstruit depuis les couches 2 à 4. On ne rejoue rien de l'ancien chapitre, donc aucun conflit avec la réflexion préservée du modèle.
- **Couche 2 — Souvenirs** : des épisodes marquants rédigés en langage naturel, par exemple :
  > *3 oct., 21h40 — Thawyn a exigé des diamants « en guise de salut ». Châtiment : gravité lunaire pendant 5 min. Il a passé le châtiment à insulter le ciel ; clemsix6 filmait.*

  Chaque souvenir porte une importance (1–10), une valence par joueur (−3 à +3), des joueurs, des entités (lieux, objets, mobs), des tags (thèmes, arc), des liens vers les événements bruts, et une force (§7).
- **Couche 3 — Connaissances** : les **fiches joueurs** (§3), le **canon** (tout ce que le Narrateur a affirmé, décrété, promis ou inventé : lois, prophéties, titres, menaces, promesses et leur statut, lore, running gags, opinions — le canon fait loi), les **lieux** nommés, les **récits** (arcs passés et actifs) et la **Chronique** hiérarchique (§6).
- **Couche 4 — Le Grimoire** : un document compact (~4k tokens, budget fixe) toujours présent dans le briefing — relation avec chaque joueur en une ou deux lignes, promesses et menaces actives, running gags vivants, arcs en cours, notes de jeu (ce qui a fait réagir les joueurs, ce qui les lasse), tics de langage à éviter. Factuel, jamais dans la voix du Narrateur. **Réécrit pendant les rêves dans le même budget** : le prompt ne grossit jamais, même après 500 heures.

## 3. La fiche joueur

Chaque joueur a une fiche vivante, en cinq sections. Chaque section a un **propriétaire** : le code (toujours exact) ou le LLM (jugement, avec preuves).

| Section | Propriétaire | Mise à jour |
|---|---|---|
| **Identité** | code (+ surnoms/titres via le Narrateur) | en continu |
| **Récap** | LLM | réécrit à chaque rêve profond ; delta à chaque fin de session |
| **Personnalité** | LLM | réflexion pendant les rêves, avec preuves et confiance |
| **Stats** | code (SQL sur le Journal + stats vanilla) | toutes les quelques minutes, sans LLM |
| **Historique des interactions** | Directeur + Chroniqueur | en continu, append-only |

### 3.1 Identité
Pseudo, UUID, rôle (opérateur ?), surnoms donnés par le Narrateur (avec date et origine), titre actuel, première et dernière connexion, nombre de sessions, temps de jeu.

### 3.2 Récap
Trois à six phrases : qui est ce joueur dans l'histoire du serveur, ce qu'il fait en ce moment, où il en est, où il en est avec le Narrateur. Plus un paragraphe **« Depuis la dernière fois »**, régénéré à chaque fin de session, qui sert au briefing de reconnexion.

### 3.3 Personnalité
- **Traits** : `{trait, intensité 1–5, confiance, preuves (ids de souvenirs), depuis, tendance}`. Un trait sans preuve n'est pas retenu ; un trait contredit par les faits récents voit sa confiance baisser plutôt que d'être empilé avec son contraire. Exemple : *téméraire ●●●●○ (sûr, 6 preuves)*. Deux sources de preuves : les souvenirs, et des **signaux chiffrés** mesurés par Jev sur chaque message du joueur (politesse, vantardise, entraide, agressivité, humour), agrégés sur des centaines de messages pour qu'une phrase isolée ne fasse pas un trait.
- **Style de jeu** : répartition explorateur / combattant / bâtisseur / mineur / fermier / social, dérivée des stats puis commentée par le LLM.
- **Rapport au Narrateur** : attitude (respectueux, provocateur, négociateur, indifférent…) et score de respect.
- **Relations** : avec chaque autre joueur (duo, rival, protecteur, souffre-douleur…).
- **Goûts et peurs** : ce qu'il aime, ce qui l'agace, ce qu'il redoute.
- **Compétences évaluées** : PvE, PvP, construction, minage, exploration, survie, chance — note sur 10 avec une justification courte, appuyée sur les stats.
- **Hameçons narratifs** : les leviers utiles au Narrateur (« sa boucle mort/respawn est un running gag, à doser », « sa base en canopée est un point d'ancrage »).

### 3.4 Stats (calculées, jamais inventées)
- **Activité** : temps de jeu (total, 7 derniers jours), sessions, horaires habituels.
- **Survie** : morts (total, top causes, dernière mort), kills par type de mob, PvP (victoires / défaites).
- **Minage et construction** : blocs minés, minerais rares (diamants, débris antiques…), profondeur moyenne, blocs posés.
- **Exploration** : distance parcourue, biomes et structures visités, dimensions atteintes.
- **Progression** : meilleur tier d'équipement (armure, arme, outils), advancements clés, niveau d'XP maximal.
- **Économie** : échanges avec les villageois, émeraudes dépensées.
- **Lieux** (clusters calculés sur les positions) : base principale, lit / point d'apparition, zone de minage favorite.
- **Rapport au Narrateur** : faveur (score et tendance), défis gagnés / perdus / abandonnés, châtiments reçus, bénédictions reçues, messages adressés au Narrateur.
- **Indice de chance** : heuristique (morts absurdes, drops rares, taux de réussite aux paris).

Les stats vanilla (`world/stats/<uuid>.json`) permettent aussi d'initialiser les totaux d'un monde existant, avant l'installation du mod.

### 3.5 Historique des interactions
Un journal append-only de chaque interaction marquante entre le Narrateur et le joueur : date, type (conversation, défi, châtiment, bénédiction, pari, provocation, excuse, aide…), résumé en une ligne, **valence −3 à +3**, effet sur la faveur, lien vers le souvenir, le défi ou le châtiment concerné.

- **Faveur** = somme des valences avec décroissance (les vieilles fautes pèsent moins, sans disparaître), bornée.
- **Dette d'attention** : temps depuis la dernière interaction — pour que personne ne soit oublié (Clowew, dans la v1).
- Dans le prompt : les ~8 dernières interactions + les plus marquantes de toujours (|valence| maximale).

### 3.6 Rendu dans le prompt
La fiche est stockée en tables et **rendue** en texte compact (budget ~600 tokens par joueur en ligne), par exemple :

```
═══ FICHE — Thawyn ═══
Surnom : « le Pilleur de Chambres » (12/05) · Titre : aucun
Joueur depuis le 26/04 · 23 sessions · 61 h · vu hier 22h14

RÉCAP
Explorateur tête baissée qui écume les chambres d'épreuve avec clemsix6, en fer,
les poches pleines de butin de donjon. Provoque le Narrateur, puis revient lui
demander de l'aide. Depuis la dernière fois : 4 diamants, 3 morts dans la même
chambre d'épreuve.

PERSONNALITÉ
Traits : téméraire ●●●●○ (sûr) · provocateur ●●●○○ (probable) · rancunier ●●○○○ (incertain)
Style : explorateur 60 % · combattant 30 % · bâtisseur 10 %
Envers le Narrateur : provocateur puis quémandeur (respect 35/100)
Relations : duo inséparable avec clemsix6 · croise rarement Clowew
Aime : butin rare, combats · Agacé par : les téléportations
Hameçons : sa désinvolture ; le duo avec clemsix6 comme cible collective

STATS (auto)
Morts 87 (chute 31, zombie 12, lave 9) · Kills 1 204 · PvP 3/5
Minage 18 400 blocs, 41 diamants, profondeur moy. y=-38
Équipement : fer, épée en diamant · Nether ✔ · End ✘
Base (-1240, 72, 3310) · Mine favorite (-1400, -40, 3100)
Faveur −12 ↘ · Défis 4/6 · Châtiments 7 · Bénédictions 2

INTERACTIONS
14/05 21:40 ▼▼ a exigé des diamants « comme salut » → gravité lunaire 5 min
13/05 20:02 ▲  a gagné la course au Nether contre clemsix6
…
```

Le Directeur consulte la fiche complète avec `player_profile`. Il ne modifie pas les stats ; il enrichit l'historique (`note_interaction`), donne des surnoms et des titres, et laisse le récap et la personnalité aux rêves — sauf révélation majeure, qu'il peut noter tout de suite (`update_profile`).

## 4. Écrire en mémoire

| Quand | Qui | Quoi |
|---|---|---|
| En direct | Directeur (outils) | `remember` (canon), `note_interaction`, surnoms et titres, `update_profile` pour une révélation majeure |
| Toutes les ~10 min | Chroniqueur (micro-lots) | Extraction automatique depuis le chat et les actions : promesses, surnoms, affirmations, interactions notées — le filet de sécurité |
| Toutes les quelques minutes | Code (SQL, sans LLM) | Stats et lieux des fiches |
| Fin de chapitre | Chroniqueur (« sieste ») | Souvenirs du chapitre, statut des promesses, résumé du chapitre |
| Temps mort | Rêveur (« rêve profond ») | Récaps, personnalités, Grimoire, Chronique, hygiène (§5) |

## 5. Les rêves : consolider pendant les temps morts

Le Narrateur « dort » quand personne ne joue, et c'est là qu'il fait son travail de fond.

### La sieste — à chaque fin de chapitre (toutes les 1–2 h de jeu actif)
- Rédiger les souvenirs épisodiques du chapitre (importance, valence, tags, liens).
- Mettre à jour le canon : promesse tenue ? menace exécutée ? loi abrogée ?
- Écrire le résumé du chapitre (premier niveau de la Chronique).
- Vérifier la cohérence : si une affirmation récente contredit le canon, laisser une note au Directeur.
- Régénérer le « Depuis la dernière fois » des joueurs dont la session s'est terminée.

### Le rêve profond — serveur vide depuis ~20 min, ou une fois par jour
1. **Réflexion sur chaque joueur** : à partir des nouveaux souvenirs, des interactions et des stats, mettre à jour récap et personnalité. Détecter les changements plutôt que d'empiler : *« Thawyn, ex-trouillard, est devenu téméraire depuis sa victoire au duel »*.
2. **Bilan du Narrateur** (factuel, pas d'introspection dans sa voix) : les tics sont **calculés par le code** à partir des répliques réellement envoyées (expressions et structures surutilisées) ; le Rêveur évalue ce qui a marché (les joueurs ont ri, répondu, joué le jeu) et ce qui est tombé à plat, met à jour les running gags (frais / usé / retraité) et les notes de jeu.
3. **Chronique** : remonter les résumés d'un niveau (chapitres → session → saison → saga).
4. **Réécrire le Grimoire** dans son budget fixe.
5. **Hygiène** : fusionner les souvenirs en double, appliquer la décroissance, archiver le trivial, expirer les vieilles menaces (avec une conséquence narrative si elles n'ont pas été tenues).
6. **Préparer la suite** : prévoir des défis adaptés à la progression réelle, préparer un rebondissement d'arc, commander un module à la Forge, repérer des lieux. Quand les joueurs reviennent, le Narrateur a un plan, et le chat reste réactif.

### Le réveil — à la connexion d'un joueur
Le Directeur reçoit, avec l'événement de connexion, la fiche condensée du joueur et son « Depuis la dernière fois ». Il accueille avec continuité, et peut raconter un rêve : *« Cette nuit, j'ai rêvé de Thawyn. Il tombait. Encore. »* Ces rêves racontés sont du contenu de jeu, écrits pour les joueurs : ils ne sont jamais réinjectés comme mémoire.

## 6. La Chronique hiérarchique

Sur 300 h de jeu, il y aura environ 200 chapitres : trop de résumés pour un prompt. La Chronique est une pyramide :

```
saga (~1,5k tokens) ← saisons (~1k chacune) ← sessions (~500) ← chapitres (~800)
```

Le briefing contient la saga, la saison en cours et les dernières sessions. Le reste est accessible par recherche. La Chronique peut aussi être offerte aux joueurs sous forme de livre en jeu (« La Chronique du Narrateur, tome III »).

## 7. Oubli et renforcement

Chaque souvenir a une **force** entre 0 et 1 :
- elle décroît avec le temps, avec une demi-vie qui dépend de l'importance (un souvenir trivial s'estompe en quelques jours, un souvenir majeur presque jamais) ;
- elle remonte quand le souvenir est rappelé et utilisé (renforcement, comme la répétition espacée) ;
- elle ne tombe jamais à zéro : rien n'est supprimé.

```
score de rappel = pertinence × (a + b·importance + c·récence) × force
```

Un peu de hasard contrôlé fait parfois remonter un vieux souvenir important — anniversaires, retours sur les lieux d'un drame : *« Il y a exactement un mois, Clowew tombait de sa canopée. Même heure, même vent. »*

## 8. Relire : comment la mémoire arrive au Directeur

1. **Le briefing de chapitre**, avec un budget par section — environ 15–20k tokens, constants dans le temps, et mis en cache :

   | Section | Budget indicatif |
   |---|---|
   | Grimoire | ~4k tokens |
   | Fiches des joueurs en ligne | ~600 tokens par joueur |
   | Canon actif (promesses, menaces, lois, gags vivants) | ~1,5k |
   | Chronique (saga + saison + dernières sessions) | ~3k |
   | Souvenirs pertinents pour les joueurs en ligne | ~2k |
   | Notes de cohérence et plans préparés pendant les rêves | ~1k |

2. **Les réminiscences automatiques** : quand un événement arrive (Thawyn entre dans le Nether, Clowew retourne là où elle est tombée), le harness cherche un ou deux souvenirs très pertinents et les attache à l'événement. Recherche locale sans LLM, avec anti-répétition (un souvenir récemment remonté ne remonte pas tout de suite).
3. **Les outils à la demande** : `player_profile`, `recall` (souvenirs et canon), `sql` (Journal et tables de mémoire), ou une analyse déléguée à l'Analyste.

## 9. Le RAG : recherche hybride

Les questions du Narrateur portent surtout sur des entités précises (un joueur, un lieu, un objet, un arc) : des filtres structurés les trouvent mieux qu'une similarité de vecteurs. On combine :

1. **Filtres structurés** : joueur, type, période, arc, lieu (SQL sur DuckDB).
2. **Plein texte** : Lucene avec analyseur français (racinisation, élisions), score BM25. Index local de quelques Mo.
3. **Vecteurs (phase 2, optionnel)** : Lucene sait aussi faire du k-NN. Il faudra un modèle d'embeddings — un petit modèle multilingue local (~120 Mo quantifié), bien plus léger que la v1, ou une API d'embeddings. Le schéma prévoit la colonne dès le départ.
4. **Reranking** : le score du §7. Pour les briefings (peu fréquents, importants), un passage LLM peut choisir les souvenirs vraiment utiles parmi une trentaine de candidats.

## 10. Cohérence et contradictions

- Avant d'affirmer quelque chose d'important, le Directeur consulte le canon (le Grimoire liste l'essentiel).
- Le Chroniqueur compare les nouvelles affirmations au canon ; en cas de contradiction, il laisse une note au Directeur, qui peut s'en sortir avec élégance (« J'ai changé d'avis. C'est mon droit. Je suis le Narrateur. »).
- Promesses et menaces ont une échéance ; le rapport de situation rappelle celles qui arrivent à terme.

## 11. Robustesse sur la durée

- Tout est persistant dans DuckDB, dans le dossier du monde : un redémarrage ne perd rien. Le chapitre en cours est clos proprement, le suivant repart du briefing.
- Les documents rédigés par le LLM (récaps, personnalités, Grimoire) sont **versionnés** : on peut revenir en arrière si un rêve a produit quelque chose de faux.
- Les budgets sont vérifiés par le code (comptage de tokens) ; ce qui déborde est recondensé au rêve suivant.
- Maintenance automatique pendant les rêves : checkpoint DuckDB, fusion de l'index Lucene.

## 12. Administration

```
/narrator fiche <joueur>              # affiche la fiche
/narrator memory canon [filtre]       # liste le canon actif
/narrator memory forget <id>          # archive un souvenir ou une entrée du canon
/narrator memory dream                # force un rêve profond
/narrator memory rollback <doc> <v>   # restaure une version (récap, personnalité, Grimoire)
```

## 13. Mesurer la qualité

Des tests rejouent des sessions enregistrées et vérifient que :
- les promesses faites sont retrouvées ;
- les réponses ne contredisent pas le canon ;
- les répliques évitent les tics identifiés ;
- les fiches reflètent les faits du Journal, sans invention.

## 14. Schéma (esquisse)

```sql
-- Fiche : identité + scores (propriétaire : code)
CREATE TABLE players (
  uuid UUID PRIMARY KEY, name VARCHAR, is_op BOOLEAN,
  first_seen TIMESTAMPTZ, last_seen TIMESTAMPTZ, sessions INTEGER, playtime_s BIGINT,
  title VARCHAR, favor DOUBLE, respect DOUBLE
);
CREATE TABLE player_nicknames (player_uuid UUID, nickname VARCHAR, given_at TIMESTAMPTZ,
  origin VARCHAR, active BOOLEAN);

-- Fiche : sections rédigées par le LLM (récap, depuis-la-dernière-fois, style, rapport au Narrateur,
-- goûts, hameçons…), versionnées
CREATE TABLE player_sections (player_uuid UUID, section VARCHAR, version INTEGER,
  content VARCHAR, evidence BIGINT[], written_by VARCHAR, written_at TIMESTAMPTZ,
  PRIMARY KEY (player_uuid, section, version));
CREATE TABLE player_traits (player_uuid UUID, trait VARCHAR, intensity TINYINT, confidence DOUBLE,
  evidence BIGINT[], since TIMESTAMPTZ, trend TINYINT, updated_at TIMESTAMPTZ,
  PRIMARY KEY (player_uuid, trait));
CREATE TABLE player_skills (player_uuid UUID, skill VARCHAR, score TINYINT, rationale VARCHAR,
  updated_at TIMESTAMPTZ, PRIMARY KEY (player_uuid, skill));
CREATE TABLE player_relations (player_uuid UUID, other_uuid UUID, relation VARCHAR, note VARCHAR,
  updated_at TIMESTAMPTZ, PRIMARY KEY (player_uuid, other_uuid));

-- Fiche : stats (propriétaire : code) — cache rafraîchi depuis le Journal
CREATE TABLE player_stats (player_uuid UUID, stat VARCHAR, value DOUBLE, detail JSON,
  computed_at TIMESTAMPTZ, PRIMARY KEY (player_uuid, stat));

-- Fiche : historique des interactions (append-only)
CREATE TABLE interactions (id BIGINT PRIMARY KEY, ts TIMESTAMPTZ, player_uuid UUID,
  kind VARCHAR, summary VARCHAR, valence TINYINT, favor_delta DOUBLE,
  memory_id BIGINT, ref_kind VARCHAR, ref_id BIGINT);

-- Souvenirs (épisodique)
CREATE TABLE memories (id BIGINT PRIMARY KEY, created_at TIMESTAMPTZ,
  occurred_from TIMESTAMPTZ, occurred_to TIMESTAMPTZ,
  kind VARCHAR,                          -- episode | reflection | dream | observation
  title VARCHAR, content VARCHAR,
  players VARCHAR[], entities VARCHAR[], tags VARCHAR[], arc_id BIGINT,
  importance TINYINT, valence JSON, strength DOUBLE,
  recall_count INTEGER, last_recalled_at TIMESTAMPTZ,
  source_chapter BIGINT, source_events BIGINT[],
  embedding FLOAT[384],                  -- phase 2, NULL au départ
  archived BOOLEAN DEFAULT false, superseded_by BIGINT);

-- Canon
CREATE TABLE canon (id BIGINT PRIMARY KEY, created_at TIMESTAMPTZ,
  kind VARCHAR,       -- claim | promise | threat | law | title | nickname | lore | prophecy | opinion | running_gag
  statement VARCHAR, subjects VARCHAR[], importance TINYINT,
  status VARCHAR,     -- active | fulfilled | broken | expired | retired
  due_at TIMESTAMPTZ, resolved_at TIMESTAMPTZ, resolution VARCHAR, source VARCHAR);

-- Chronique hiérarchique
CREATE TABLE chronicle (id BIGINT PRIMARY KEY, level VARCHAR,   -- chapter | session | season | saga
  period_from TIMESTAMPTZ, period_to TIMESTAMPTZ, title VARCHAR, summary VARCHAR, parent_id BIGINT);

-- Documents du Narrateur (Grimoire, notes de voix, tics…), versionnés
CREATE TABLE narrator_docs (name VARCHAR, version INTEGER, content VARCHAR, created_at TIMESTAMPTZ,
  PRIMARY KEY (name, version));

CREATE TABLE dreams (id BIGINT PRIMARY KEY, started_at TIMESTAMPTZ, ended_at TIMESTAMPTZ,
  kind VARCHAR, report VARCHAR, dream_text VARCHAR);
```
