# The Narrator 2 — règles d'équipe

Mod Minecraft **26.3** (Fabric, côté serveur uniquement, Kotlin) qui branche Claude sur un serveur pour jouer un Narrateur-dieu sarcastique, drôle et sévère. Le projet est mené par un superviseur qui délègue à des agents : chaque agent reçoit une mission précise et rend un rapport.

## À lire avant de coder
- `docs/ARCHITECTURE.md` : l'architecture, la feuille de route (§16) et les décisions prises (§17).
- `docs/MEMOIRE.md` : la mémoire du Narrateur, dont la fiche joueur (§3).
- `docs/research/` : les notes de recherche vérifiées (signatures d'API 26.3, LLMGW, Jev, Forge…).
- `.research/` (non versionné) : des références brutes. `v1/` est l'ancienne version : **on lit les idées, on ne copie jamais son code ni ses prompts**. `porting/` contient les guides de migration 26.1 à 26.3, `jev/` la doc de Jev et `LLMGW/` le code de la passerelle.

## Arborescence
- `TheMod/` : le mod (Gradle). Le code est sous `src/main/kotlin/io/github/clemsix6/narrator2/`, les mixins Java sous `src/main/java/.../mixin/` et les prompts sous `src/main/resources/narrator2/prompts/`.
- `TheServer/` : le dossier d'exécution du serveur de dev (`runServer`). Il n'est pas versionné.
- `docs/` : la documentation en français.

## Build — à lire absolument
Le profil Windows s'appelle `Clément` (non-ASCII), ce qui casse les sockets AF_UNIX de Java dans `%TEMP%` (« Unable to establish loopback connection »). `gradlew.bat` redirige `TEMP` vers `C:\Users\Public\narrator2-tmp`.
- Depuis PowerShell : `cd TheMod; .\gradlew.bat build` (ou `test`, `runServer`, `runGametest`…).
- Depuis Bash : `cd TheMod && cmd //c gradlew.bat build`. **Jamais `./gradlew`** : ce script n'a pas le correctif.
- **Un seul build Gradle à la fois par dossier.** Si tu travailles dans un worktree, il a son propre dossier `build/`.
- Avertissement Loom inoffensif : « 1.5.5.1 is not valid semver » (DuckDB).

## Minecraft 26.3
- Le jeu n'est **plus obfusqué** depuis 26.1 : on utilise les noms officiels de Mojang, sans Yarn ni Intermediary. Beaucoup de noms ont changé par rapport à tes souvenirs, par exemple `Identifier` au lieu de `ResourceLocation`.
- **Ne jamais écrire une signature de mémoire.** Vérifie-la avec javap : `powershell -File .research/jp.ps1 -Classes net.minecraft.server.level.ServerPlayer -Pattern "teleport"`. Le script couvre le jar Minecraft 26.3 et tous les modules de Fabric API.
- Le JDK est installé dans `C:\Program Files\Java\jdk-25`.

## Conventions de code
- Kotlin pour le mod, Java uniquement pour les mixins. La KDoc et les commentaires sont en anglais. Les textes vus par les joueurs et les messages de log sont en français.
- **Logs** : passe par `core/Log` (`Log.of("Tag")`), avec un tag par sous-système. On journalise tout ce qui aide à déboguer : décisions du moteur, appels d'outils avec leurs arguments et résultats, erreurs avec leur contexte, durées. Les conversations avec Claude sont intégralement transcrites par `trace/Transcripts`.
- **Threads** : tout accès au monde passe par le thread serveur (`server.onServerThread { }`). Le thread serveur ne doit jamais attendre le réseau ni la base de données.
- **Claude** : SDK Java officiel d'Anthropic, pointé sur LLMGW. Les conversations sont en ajout seul : on ne modifie jamais les tours précédents, le prompt système ni la liste d'outils. `tool_choice` n'est jamais forcé (Opus 5.5 renvoie une erreur 400).
- Le modèle ne doit voir **que des outils réellement branchés**. Toute action est validée côté serveur avant d'être exécutée.
- Pas de datapacks, pas de JavaScript.
- Du code simple et lisible : pas d'abstraction spéculative ni de code mort.

## Tests
- `.\gradlew.bat test` lance les tests unitaires (JUnit 5).
- `.\gradlew.bat runGametest` lance les tests en jeu (Fabric GameTest, sur un serveur de test sans EULA).
- **Aucun appel réel à Claude ni à Jev dans les tests automatisés** : ils utilisent le faux serveur Anthropic des tests.
- Une tâche n'est finie que si le build et les tests passent. Le rapport indique ce qui a été vérifié et comment.

## Git
- Dépôt local sur la branche `main`, sans remote : **ne jamais pousser**.
- Commite chaque étape validée, avec un message court en anglais à l'impératif et le trailer `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Ne réécris jamais l'historique : pas de `reset --hard`, pas de `push --force`, pas de `commit --amend` sur un commit existant.
