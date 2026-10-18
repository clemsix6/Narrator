# The Narrator 2 — règles d'équipe

Mod Minecraft **26.3** (Fabric, côté serveur uniquement, Kotlin) qui branche Claude sur un serveur pour jouer un Narrateur-dieu sarcastique, drôle et sévère. Le projet est mené par un superviseur qui délègue à des agents : chaque agent reçoit une mission précise et rend un rapport.

## À lire avant de coder
- `docs/ARCHITECTURE.md` : l'architecture, la feuille de route (§16) et les décisions prises (§17).
- `docs/MEMOIRE.md` : la mémoire du Narrateur, dont la fiche joueur (§3).
- `docs/research/` : les notes de recherche vérifiées (signatures d'API 26.3, LLMGW, Jev, Forge…).
- `.research/` (non versionné) : des références brutes. `porting/` contient les guides de migration 26.1 à 26.3 et `jev/` la doc de Jev.

## LLMGW (accès à Claude)
- On utilise **le serveur LLMGW existant du propriétaire** : **on ne clone ni n'installe jamais LLMGW en local**.
- On y accède par un tunnel SSH vers le serveur du réseau local : `ssh -o BatchMode=yes -N -L 8088:localhost:8088 clement@192.168.1.20`. Le nom public `<ssh-host>` passe par Cloudflare, qui ne relaie pas SSH : utilise toujours l'IP locale.
- URL de base : `http://localhost:8088/`. Authentification : en-tête `x-api-key: <clé>` (`Authorization: Bearer` marche aussi). Test : `GET /v1/models`.
- La clé se trouve dans `.secrets/llmgw.key` (non versionné). Pour le mod, elle passe par la variable d'environnement `NARRATOR_LLMGW_KEY`. **Ne jamais l'afficher, la logger, la commiter ni la recopier ailleurs.**
- Tunnel partagé : avant d'ouvrir le tunnel, vérifie si le port 8088 écoute déjà (`netstat -ano | findstr :8088`). S'il écoute, réutilise ce tunnel. Ne ferme que le processus ssh que tu as lancé toi-même.
- Les tests automatisés n'appellent jamais le vrai serveur. Les appels réels servent seulement aux tests de fumée, avec des requêtes minimales.

## On repart de zéro
La v2 est une réécriture complète. Les leçons de la v1 sont résumées dans `docs/ARCHITECTURE.md` §1, et c'est tout ce qu'on en garde.
- **Aucune migration** ni reprise de données : pas de mémoire, de fiches, de journaux, de configuration ou de monde de la v1. Aucune compatibilité avec ses formats.
- **Les structures de données sont libres.** Si un format, un schéma ou une structure des docs peut être meilleur, améliore-le, puis mets la doc à jour et signale-le dans ton rapport. Chaque fois qu'on peut faire mieux que la v1, on le fait.

## Arborescence
- `TheMod/` : le mod (Gradle). Le code est sous `src/main/kotlin/io/github/clemsix6/narrator2/`, les mixins Java sous `src/main/java/.../mixin/` et les prompts sous `src/main/resources/narrator2/prompts/`.
- `TheServer/` : le dossier d'exécution du serveur de dev (`runServer`). Il n'est pas versionné.
- `docs/` : la documentation en français.

## Build — à lire absolument
Le profil Windows s'appelle `Clément` (non-ASCII), ce qui casse les sockets AF_UNIX de Java dans `%TEMP%` (« Unable to establish loopback connection »). `gradlew.bat` redirige `TEMP` vers `C:\Users\Public\narrator2-tmp`.
- Depuis PowerShell : `cd TheMod; .\gradlew.bat build` (ou `test`, `runServer`, `runGameTest`…).
- Depuis Bash : `cd TheMod && cmd //c gradlew.bat build`. **Jamais `./gradlew`** : ce script n'a pas le correctif.
- **Un seul build Gradle à la fois par dossier.** Si tu travailles dans un worktree, il a son propre dossier `build/`.
- Avertissement Loom inoffensif : « 1.5.5.1 is not valid semver » (DuckDB).

## Minecraft 26.3
- Le jeu n'est **plus obfusqué** depuis 26.1 : on utilise les noms officiels de Mojang, sans Yarn ni Intermediary. Beaucoup de noms ont changé par rapport à tes souvenirs, par exemple `Identifier` au lieu de `ResourceLocation`.
- **Ne jamais écrire une signature de mémoire.** Vérifie-la avec javap : `powershell -File .research/jp.ps1 -Classes net.minecraft.server.level.ServerPlayer -Pattern "teleport"`. Le script couvre le jar Minecraft 26.3 et tous les modules de Fabric API.
- Le JDK est installé dans `C:\Program Files\Java\jdk-25`.

## Conventions de code
- Kotlin pour le mod, Java uniquement pour les mixins.
- **Tout ce qui est dans le code est en anglais** : identifiants, commentaires, KDoc, messages de log, textes des commandes d'administration, messages d'erreur, noms de fichiers et de tables, en-têtes des transcriptions, noms des tests.
- Le français est réservé à ce qui est hors du code : les prompts (`src/main/resources/narrator2/prompts/`), la documentation (`docs/`) et les échanges avec le propriétaire. Ce que le Narrateur dit aux joueurs est en français parce que c'est Claude qui l'écrit, guidé par les prompts, pas parce qu'il y a du texte français dans le code.
- Les définitions d'outils (noms, descriptions, schémas), les résultats d'outils et les messages d'événements générés par le moteur pour Claude sont eux aussi en anglais : ce sont des sorties du code.
- Les docs nomment les rôles en français ; dans le code et la configuration, ils ont un identifiant anglais :

  | Doc | Code |
  |---|---|
  | Directeur | `director` |
  | Analyste | `analyst` |
  | Éclaireur | `scout` |
  | Forgeron | `forger` |
  | Scénariste | `storyteller` |
  | Chroniqueur | `chronicler` |
  | Rêveur | `dreamer` |

  De même, la fiche joueur s'appelle `PlayerSheet`, le Journal `Journal`, l'Oracle `Oracle` et la Forge `Forge`.
- **Logs** : passe par `core/Log` (`Log.of("Tag")`), avec un tag par sous-système. On journalise tout ce qui aide à déboguer : décisions du moteur, appels d'outils avec leurs arguments et résultats, erreurs avec leur contexte, durées. Les conversations avec Claude sont intégralement transcrites par `trace/Transcripts`.
- **Threads** : tout accès au monde passe par le thread serveur (`server.onServerThread { }`). Le thread serveur ne doit jamais attendre le réseau ni la base de données.
- **Claude** : SDK Java officiel d'Anthropic, pointé sur LLMGW. Les conversations sont en ajout seul : on ne modifie jamais les tours précédents, le prompt système ni la liste d'outils. `tool_choice` n'est jamais forcé (Opus 5.5 renvoie une erreur 400).
- Le modèle ne doit voir **que des outils réellement branchés**. Toute action est validée côté serveur avant d'être exécutée.
- Pas de datapacks, pas de JavaScript.
- Du code simple et lisible : pas d'abstraction spéculative ni de code mort.

## Tests
- `.\gradlew.bat test` lance les tests unitaires (JUnit 5).
- `.\gradlew.bat runGameTest` lance les tests en jeu (Fabric GameTest, sur un serveur de test sans EULA).
- `.\gradlew.bat liveTest` lance les tests de fumée réels (tag JUnit `live`, hors de `test`, `build` et `check`) contre LLMGW via le tunnel ouvert sur `localhost:8088` ; clé lue dans `NARRATOR_LLMGW_KEY` ou `.secrets/llmgw.key`, tests ignorés sans clé ni tunnel.
- **Aucun appel réel à Claude ni à Jev dans les tests automatisés** : ils utilisent le faux serveur Anthropic des tests.
- **Piège `runBlocking`** : `@Test fun x() = runBlocking { … }` prend pour type de retour celui de la dernière expression, et JUnit ignore sans bruit toute méthode de test qui ne renvoie pas `void` ; écris `runBlocking<Unit> { … }` (ou un corps en bloc). Le test `testing/TestMethodsReturnUnitTest` échoue en listant les méthodes fautives.
- Une tâche n'est finie que si le build et les tests passent. Le rapport indique ce qui a été vérifié et comment.

## EULA de Minecraft
Le propriétaire a donné son accord (25/09/2026) : on peut accepter l'EULA de Minecraft (`eula=true`) pour tout serveur de dev ou de test (`TheServer/`, spikes, dossiers temporaires), sans lui redemander. On lance donc `runServer` librement pour les tests manuels ou de bout en bout ; les GameTests restent le moyen privilégié pour les tests automatisés.

## Passation entre tâches
- Avant de coder, lis `docs/design/handoff/` : au minimum les fichiers des tâches dont tu dépends. Ils décrivent les écarts entre le design et le code réel. **En cas de contradiction, le code et la passation font foi**, pas le design.
- En fin de tâche, écris `docs/design/handoff/<TÂCHE>.md` : tes écarts par rapport au design, les décisions que tu as prises, et ce que les tâches suivantes doivent savoir. C'est le seul endroit où la connaissance circule entre agents.

## Travail en parallèle (worktrees)
Pendant les vagues parallèles, chaque agent travaille dans son propre worktree git, sur sa propre branche.
- Les dossiers non versionnés n'existent que dans le checkout principal : utilise `C:\Users\Clément\Desktop\Narrator2\.research\` et `C:\Users\Clément\Desktop\Narrator2\.secrets\` en chemins absolus.
- Ne modifie que les fichiers que ta tâche possède (voir `docs/design/`). Un fichier partagé en ajout reçoit seulement ta ligne.
- Avant de finir : `git merge main` dans ta branche, résous les éventuels conflits, relance `build` (tests unitaires et GameTests) jusqu'au vert, puis commite.
- **Ne fusionne jamais toi-même dans `main`** : le superviseur s'en charge. Donne le nom de ta branche dans ton rapport.
- Ferme les serveurs de jeu que tu as lancés. **N'utilise jamais `gradlew --stop`** : il arrête les démons Gradle de TOUS les agents et casse leurs builds en cours. Les démons inactifs s'arrêtent seuls au bout de 3 h.

## Git
- Dépôt local sur la branche `main`, sans remote : **ne jamais pousser**.
- Commite chaque étape validée, avec un message court en anglais à l'impératif et le trailer `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Ne réécris jamais l'historique : pas de `reset --hard`, pas de `push --force`, pas de `commit --amend` sur un commit existant.
