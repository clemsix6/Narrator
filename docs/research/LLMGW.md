# LLMGW — notes d'intégration pour ClaudeClient

Recherche initiale menée sur le code source public de LLMGW (Go, module `github.com/clemsix6/LLMGW`,
dépôt [github.com/clemsix6/LLMGW](https://github.com/clemsix6/LLMGW), `CLIProxyAPI v7.3.15` embarqué en
librairie via la dépendance Go `github.com/router-for-me/CLIProxyAPI/v7`), croisée avec
`docs/ARCHITECTURE.md` §14, `CLAUDE.md` et
`TheMod/src/main/kotlin/io/github/clemsix6/narrator2/llm/ClaudeClient.kt`.

**On ne clone ni n'installe jamais LLMGW en local** : le clone qui avait servi à la recherche initiale a
été supprimé à la demande du propriétaire. On utilise uniquement son serveur existant, sur son réseau
local, via un tunnel SSH — voir « Accès réel » ci-dessous. Les citations `fichier:ligne` de la recherche
initiale ont été remplacées par des liens vers le dépôt GitHub (branche `main`, chemins vérifiés présents
le 25/09/2026). Les quelques conclusions qui portaient sur le module CLIProxyAPI embarqué lui-même
(`cloaking.go`, `claude_executor_request.go`, `stream.go`, `execute.go`) restent citées sans lien précis :
ces fichiers ne sont pas vendus dans le dépôt LLMGW (c'est une dépendance Go externe), et leur chemin exact
dans `github.com/router-for-me/CLIProxyAPI` n'a pas pu être retrouvé depuis cette recherche — les
conclusions sont gardées telles quelles mais n'ont plus de pointeur vérifiable ligne à ligne.

**Avertissement historique** (conservé de la recherche initiale) : le document de design du 18/06 décrit
une V1 antérieure où LLMGW réimplémentait lui-même le client OAuth Claude Max (headers
`X-Project`/`X-Tags`, réponses 402/503 maison, pas de CLIProxyAPI). Cette conception a été **remplacée**
par l'architecture actuelle (LLMGW = authentification par clé projet + budgets + comptabilité, posés sur
CLIProxyAPI embarqué qui gère les protocoles fournisseurs). Le README et le code du dépôt sont la source
de vérité ; le design du 18/06 n'est cité que comme contexte historique.

## 1. Accès réel au serveur du propriétaire

- **Jamais d'installation locale.** Pas de `docker compose up`, pas de base Postgres à créer depuis ce
  dépôt : le mod parle au serveur LLMGW que le propriétaire fait déjà tourner sur sa propre machine, sur
  son réseau local.
- **Tunnel SSH** (voir `CLAUDE.md`) :
  `ssh -o BatchMode=yes -o ExitOnForwardFailure=yes -o ConnectTimeout=10 -N -L 8088:localhost:8088 clement@192.168.1.20`.
  **Utiliser l'IP locale du serveur, jamais le nom public** : `<ssh-host>` résout
  publiquement vers des IP Cloudflare (`104.21.14.184`, `172.67.160.36`, plages IPv6 `2606:4700:…`), qui
  ne relaient que le trafic web — vérifié le 25/09/2026 : le port **443** vers ce nom répond, le port
  **22** ne répond pas (`Test-NetConnection` côté client, et `ssh: connect … Network is unreachable` en
  direct depuis un poste hors du réseau local). Le nom public n'a pas d'alias dans `~/.ssh/config`. Depuis
  un poste sur le même réseau que le serveur, le tunnel vers `192.168.1.20:22` s'établit normalement.
  `BatchMode=yes` fait échouer proprement l'authentification (jamais de prompt mot de passe) si la clé SSH
  n'est pas autorisée.
- **URL de base** une fois le tunnel ouvert : `http://localhost:8088/`.
- **Authentification** : en-tête `x-api-key: <clé>` (`Authorization: Bearer` fonctionne aussi d'après le
  code — non retesté en réel). C'est exactement la forme `x-api-key` que pose le SDK Java Anthropic via
  `AnthropicOkHttpClient.builder().apiKey(...)` dans `ClaudeClient.kt` — rien à adapter côté mod.
- **Clé** : `.secrets/llmgw.key` (non versionné), jamais affichée, loggée, committée ni recopiée ailleurs.
  Pour le mod, elle passe par la variable d'environnement `NARRATOR_LLMGW_KEY`.
- **Test de connectivité** : `GET /v1/models` — confirmé en réel le 25/09/2026 (§ *Vérifié en réel*).
- **Fermer le tunnel après usage** : `-N` ne se termine pas tout seul, il faut tuer le process `ssh`.

### Gestion des clés et budgets côté serveur (opérateur uniquement, jamais depuis ce dépôt)

- Il n'existe pas de commande « create project » séparée : un projet est créé implicitement par sa
  première clé, `llmgw key create <projet> --name <libellé> [--expires <durée>]`
  ([`internal/command/key.go`](https://github.com/clemsix6/LLMGW/blob/main/internal/command/key.go)). La
  clé en clair n'est affichée qu'une seule fois. `llmgw key list|rotate|revoke` gèrent le cycle de vie
  ensuite. Toutes ces commandes sont **locales** : elles ouvrent directement la base Postgres du serveur,
  ce ne sont pas des routes HTTP — l'interface web/API de gestion native de CLIProxyAPI est explicitement
  désactivée et bloquée
  ([`internal/adapter/cliproxy/routes.go`](https://github.com/clemsix6/LLMGW/blob/main/internal/adapter/cliproxy/routes.go)
  renvoie 404 sur `/`, `/management.html`, `/v0/management*`, `/v0/resource/plugins*`).
- Budgets : `llmgw budget set PROJET --dimension calls|tokens|cost --window hour|day --max N --action
  block|warn`
  ([`internal/command/budget.go`](https://github.com/clemsix6/LLMGW/blob/main/internal/command/budget.go)).
- Effort par défaut du projet : `llmgw project effort <projet> low|medium|high|xhigh|max|none`
  ([`internal/command/project.go`](https://github.com/clemsix6/LLMGW/blob/main/internal/command/project.go)).
- **Chemin API Messages** : `POST /v1/messages` (route « génération », métrée et budgétée) et
  `POST /v1/messages/count_tokens` (métadonnée, authentifiée mais non comptée dans les budgets). `GET
  /v1/models` et `/v1beta/models*` sont aussi des routes métadonnées. Le healthcheck public est `GET
  /healthz` (toutes dans
  [`routes.go`](https://github.com/clemsix6/LLMGW/blob/main/internal/adapter/cliproxy/routes.go)).

## 2. Ce qui passe intact et ce qui est réécrit

Le README de LLMGW liste explicitement deux routes réécrites côté sortie, `POST /v1/messages` et
`POST /v1/messages/count_tokens` — tout le reste passe tel quel. Le code confirme et précise :

- **Streaming SSE** : relayé sans tampon (« no buffering — streaming latency preserved », README). La
  seule chose que LLMGW touche dans le flux est la suppression du préfixe `mcp__llmgw__` sur les
  événements `content_block_start` de type `tool_use`
  ([`internal/domain/toolprefix/response.go`](https://github.com/clemsix6/LLMGW/blob/main/internal/domain/toolprefix/response.go)) —
  **confirmé en réel** (§ *Vérifié en réel* : le flux SSE arrive complet et `roll_dice` revient non
  préfixé).
- **`thinking: {type: "adaptive", display: "summarized"}`** : LLMGW lui-même ne touche jamais `thinking.*`
  sauf pour *lire* `thinking.type` afin de savoir s'il doit s'abstenir d'injecter un effort par défaut
  ([`internal/domain/effort/apply.go`](https://github.com/clemsix6/LLMGW/blob/main/internal/domain/effort/apply.go)).
  Avec un compte amont **clé API** (pas de cloaking), le champ traverse intact. Avec un compte amont
  **OAuth**, CLIProxyAPI ne réécrit `thinking.display` que pour les modèles Fable 5.1/Mythos 5.1 — Opus 5.5
  n'est pas concerné (conclusion du module CLIProxyAPI embarqué, non re-vérifiée ligne à ligne ici).
- **`output_config.effort`** : LLMGW injecte l'effort par défaut du projet seulement si la requête n'a
  aucun `output_config.effort`, et jamais si le client a explicitement désactivé le thinking
  ([`effort/apply.go`](https://github.com/clemsix6/LLMGW/blob/main/internal/domain/effort/apply.go)).
  Comme `ClaudeClient.kt` pose toujours `OutputConfig.Effort` explicitement par rôle, cette injection ne
  se déclenche jamais pour nos appels.
- **`cache_control` au niveau racine** — **comportement réel confirmé par `liveTest`, voir § *Vérifié par
  liveTest (25/09/2026)*.** L'API Messages Anthropic n'a pas de champ `cache_control` de premier niveau au
  sens strict — c'est un marqueur qui finit par s'appliquer à un bloc de contenu (« top-level auto-caching »).
  Le serveur LLMGW du propriétaire pose **lui-même** des marqueurs `cache_control` avec `ttl=1h` sur le
  prompt système et les derniers tours, **avant** qu'un marqueur du client ne s'applique. Conséquence :
  - **Sans marqueur de notre part, le cache fonctionne déjà** : un appel écrit dans le cache, l'appel
    suivant sur la même conversation (même `X-Session-ID`) le relit — mesuré à 5 344 tokens écrits puis
    5 344 relus.
  - **Un marqueur `1h` de notre part est accepté au premier tour** (il coïncide avec celui déjà posé par la
    passerelle) **mais rejeté par une 400 à partir du deuxième tour utilisateur** : le marqueur de la
    passerelle plus le nôtre dépassent la limite de l'API (« A maximum of 4 blocks with cache_control…
    Found 5 »).
  - **Un marqueur `5m` de notre part est toujours rejeté** (400, conflit de `ttl` avec le `1h` déjà posé
    côté serveur sur le même bloc).
  - `gateway.cacheTtl` vaut donc **`off` par défaut** (aucun marqueur envoyé) : voir § *Vérifié par liveTest*
    et « Exigences pour ClaudeClient » ci-dessous. `1h` et `5m` restent disponibles dans la configuration
    pour une passerelle qui ne poserait pas son propre marqueur.
  - Ce comportement (un bloc portant déjà un `cache_control` `ttl=1h` avant l'application du nôtre) est,
    d'après la lecture du code (§ *Résumé compte amont clé API vs OAuth* ci-dessous), un signe de cloaking
    OAuth côté compte amont, pas d'un compte clé API nu.
- **`tools` et leurs noms** : **toujours** réécrits par LLMGW lui-même, sur les deux routes, indépendamment
  du type de compte amont
  ([`internal/adapter/cliproxy/request_rewrite.go`](https://github.com/clemsix6/LLMGW/blob/main/internal/adapter/cliproxy/request_rewrite.go)).
  Tout outil déclaré par le client (`type` absent ou `type:"custom"`) est préfixé `mcp__llmgw__` dans
  `tools[].name`, `tool_choice.name` et `messages[].content[].name` (blocs `tool_use` rejoués) — jamais
  dans les arguments ni dans `tool_result`
  ([`internal/domain/toolprefix/request.go`](https://github.com/clemsix6/LLMGW/blob/main/internal/domain/toolprefix/request.go)).
  Sont exemptés : les outils natifs Anthropic (identifiés par leur `type`), les noms déjà préfixés `mcp__`,
  et les noms trop longs. La constante `maxToolNameLength = 64` et le préfixe `mcp__llmgw__` (12
  caractères) donnent la limite exacte : un nom original de plus de 52 caractères n'est pas préfixé — ce
  qui confirme au caractère près le « plus de 52 caractères » de `docs/ARCHITECTURE.md`. Les réponses
  (JSON et SSE) sont dépréfixées avant de repartir vers le client, qui ne voit jamais `mcp__llmgw__` —
  **confirmé en réel** : `roll_dice` est revenu sous son nom exact, sans préfixe.
- **Messages `system` au milieu de la conversation** (`{"role":"system", ...}` dans `messages[]`) : LLMGW
  lui-même n'y touche jamais d'après le code source. **Confirmé par `liveTest` (25/09/2026, § *Vérifié par
  liveTest*)** : un tour `role:"system"` inséré en cours de conversation, juste après un message `user`, est
  **accepté et suivi** par `claude-opus-5-5` derrière ce serveur — pas de 400, la consigne du tour système
  l'emporte sur les tours précédents. C'était le point le plus structurant pour l'architecture, et il valide
  `director.operatorChannel = system_message` comme mode par défaut (`docs/design/M1_M2.md` §12.7) ; le
  repli `director.operatorChannel = user_tag` reste disponible en configuration et via la bascule
  automatique sur 400 décrite au même paragraphe, mais n'est plus nécessaire contre ce serveur.
- **En-têtes `anthropic-beta`** : avec un compte amont clé API, les en-têtes `Anthropic-Beta` du client
  sont conservés tels quels (« mode `preserveCallerFingerprint` » d'après la lecture initiale du module
  CLIProxyAPI embarqué) ; avec OAuth, la liste est remplacée par un jeu de betas assemblés pour imiter
  Claude Code. **Non re-vérifié en réel** (§ *Vérifié en réel*, point g bloqué).
- **`X-Session-ID` (affinité de session)** : nom exact confirmé (`ClaudeClient.kt`,
  `docs/ARCHITECTURE.md`). Consommé par le réglage natif CLIProxyAPI `routing.session-affinity: true` :
  « Sticky sessions pin every turn of one conversation to the same upstream account. Anthropic's prompt
  cache lives per account, so without this a multi-turn agent loop round-robins across accounts and
  re-pays its whole context as a cache write on almost every turn. » **Confirmé par `liveTest`** (§ *Vérifié
  par liveTest*, point c) : deux appels identiques avec le même `X-Session-ID` montrent une écriture de
  cache au premier appel puis une lecture au second (5 344 tokens écrits, 5 344 relus).
- **Modèles acceptés** : LLMGW ne maintient aucune liste blanche de modèles. Le champ `model` traverse sans
  modification. **Confirmé en réel** : `GET /v1/models` liste bien `claude-opus-5-5` et
  `claude-haiku-4-5-20251001` (ainsi que l'ID canonique `claude-haiku-4-5` sans suffixe de date) — voir §
  *Vérifié en réel*.

**Résumé compte amont clé API vs OAuth** : d'après la lecture initiale du code, seul un compte amont OAuth
déclenche le cloaking (réécriture du prompt système, marqueurs de cache maison, betas remplacés) ; avec une
clé API, seuls le préfixage des noms d'outils et l'injection d'effort par défaut (permanents, non liés au
cloaking) s'appliquent. **Le `liveTest` du 25/09/2026 rend ce cloaking vraisemblable** : la passerelle
réécrit nos requêtes avant de les transmettre — elle insère un bloc avant notre texte et pose elle-même les
marqueurs `cache_control` (`ttl=1h`, sur le prompt système et les derniers tours) — un comportement qui,
d'après la lecture du code, ne devrait apparaître qu'en mode cloaking OAuth, jamais avec un compte amont clé
API nu. Rien ne le confirme au niveau HTTP de façon certaine (pas d'accès à la configuration serveur), mais
le faisceau d'indices pointe vers un **compte d'abonnement (OAuth) en amont plutôt qu'une clé API**.
`docs/ARCHITECTURE.md` §14 recommande toujours une clé API Anthropic pour le projet du Narrateur : les
conditions d'utilisation d'Anthropic réservent l'OAuth des abonnements Free/Pro/Max à Claude Code et aux
applications d'Anthropic, et un serveur multijoueur n'entre pas dans ce cadre. **Ce point reste à clarifier
avec l'opérateur** — ce que le mod observe n'est qu'un faisceau d'indices côté client, pas une confirmation
du type de credential réellement configuré côté serveur.

## 3. Erreurs et limites

- **Budgets** : `llmgw budget set PROJET --dimension calls|tokens|cost --window hour|day --max N --action
  block|warn`. Sur dépassement avec `action=block` : HTTP 402, corps
  `{"error":{"type":"budget_exceeded","dimension":...,"window":...,"reset_at":"<RFC3339 UTC>"}}`
  ([`internal/adapter/cliproxy/middleware.go`](https://github.com/clemsix6/LLMGW/blob/main/internal/adapter/cliproxy/middleware.go)).
  **Aucun en-tête `Retry-After` n'est posé** sur cette réponse — seul le `reset_at` JSON donne l'heure de
  réinitialisation. Avec `action=warn`, la requête passe et seul un log serveur est émis — le client n'a
  aucun moyen HTTP de voir un warning. `ClaudeClient.kt` transforme ce 402 en `BudgetExceededException`, ne
  le réessaie jamais, et porte `resetAt`. **Toujours non déclenché en réel** (pas de budget dépassé pendant
  aucun des tests menés à ce jour) : la forme du corps reste celle lue dans le code, pas observée en HTTP.
- **Autres codes propres à LLMGW** : `401 authentication_error` (credential manquant, mal formé, dupliqué ou
  incohérent entre `Authorization` et `x-api-key`) — **confirmé par `liveTest`** avec une mauvaise clé : corps
  `{"error":{"type":"authentication_error"}}`, **sans `message`** ; `503 service_unavailable` (panne DB
  transitoire, SDK pas encore prêt, permis de génération épuisés, ou bridge « empoisonné » — non déclenché
  en réel) ; `413 request_too_large` (corps > 32 MiB, non déclenché en réel) ; `400 invalid_request_error`
  (requête malformée) — **confirmé par `liveTest`** sur un message vide : `cache_control cannot be set for
  empty text blocks` (le SDK pose toujours un bloc `cache_control` sur le dernier bloc de texte quand
  `gateway.cacheTtl` n'est pas `off`, y compris sur un texte vide) ; voir aussi les deux 400 de conflit de
  `ttl` de `cache_control` au § *Vérifié par liveTest* ci-dessous.
- **429 / 529 / compte amont indisponible** : viennent d'Anthropic lui-même (ou de la logique de
  cooldown/rotation de comptes amont de CLIProxyAPI), pas du middleware LLMGW — frontière de
  responsabilité explicite du `CLAUDE.md` du repo LLMGW (« The embedded SDK owns provider protocols,
  provider authentication, routing, cooldowns, and retries »). Leviers configurables :
  `request-retry: 1`, `max-retry-credentials: 2`, `max-retry-interval: 5`,
  `transient-error-cooldown-seconds: -1`, `disable-cooling: false`. **Toujours non déclenché en réel.**
- **Timeouts côté passerelle** : aucun réglage de timeout de réponse propre à LLMGW trouvé dans le code. Le
  streaming étant relayé sans tampon, une génération longue ne devrait pas buter sur un timeout HTTP
  non-streaming fixe côté LLMGW. Le seul timeout garanti reste celui du client, **par rôle** depuis le
  correctif de M0 (`roles.<rôle>.readTimeoutSeconds` = 90 s de silence toléré, `totalTimeoutSeconds` = 300 s
  pour le Directeur, 600 s par défaut, 1 200 s pour le Forgeron et le Rêveur — `RoleConfig` dans
  `NarratorConfig.kt`) ; les retries automatiques du SDK Anthropic sont **désactivés**
  (`.maxRetries(0)` dans `ClaudeClient.kt`), c'est le client qui réessaie lui-même (voir ci-dessous).
- **Temps jusqu'au premier token (TTFT)** : **mesuré par `liveTest`**, le TTFT représente **70 à 100 % de la
  durée totale** d'un appel — le contenu streamé arrive presque en bloc à la fin plutôt qu'étalé, malgré le
  streaming SSE sans tampon (§2). À prendre en compte pour l'indicateur « le Narrateur réfléchit… » (§7.5 de
  `ARCHITECTURE.md`) : il reste affiché l'essentiel de l'attente.
- **Identifiant de requête** : LLMGW **ne relaie pas** l'en-tête `request-id` d'Anthropic — **confirmé par
  `liveTest`**. `ClaudeClient.kt` retombe sur l'en-tête `X-Cpa-Trace-Id` posé par CLIProxyAPI
  (`requestIdOf(Headers)`), qui alimente `CallStats.requestId` et les logs d'échec ; c'est cet identifiant
  qu'il faut donner à l'opérateur pour qu'il retrouve un appel côté serveur, pas `request-id`.
- **Ce que le client fait réellement** (`ClaudeClient.kt`, revu par le correctif M0) :
  - `401`/`400`/`413`/`402` : jamais de retry immédiat. Le 402 devient `BudgetExceededException` (`resetAt`
    lu dans `error.reset_at`, jamais dans un en-tête).
  - `503 service_unavailable`, `429`, `529`, toute 5xx, une erreur de connexion, ou un flux coupé avant tout
    contenu : le client réessaie lui-même, jusqu'à `gateway.maxRetries` (3 par défaut), avec un backoff
    exponentiel et une marge aléatoire (jitter), en respectant `retry-after`/`retry-after-ms` quand la
    réponse en porte un. Un flux coupé **après** du contenu déjà reçu n'est **pas** réessayé (le tour serait
    rejoué en double).
  - Ce budget de retry est propre au mod : les retries du SDK Java sont coupés (`maxRetries(0)`) pour ne pas
    s'empiler sur ceux déjà faits par CLIProxyAPI en amont.

## 4. Suivi de consommation (`/narrator usage`)

**LLMGW n'expose aucun endpoint HTTP d'usage.** `usage show`/`usage resolve`
([`internal/command/usage.go`](https://github.com/clemsix6/LLMGW/blob/main/internal/command/usage.go)) et
`budget list` sont des sous-commandes CLI locales qui ouvrent directement Postgres sur la machine hôte —
ce ne sont pas des routes HTTP, et la surface de gestion native de CLIProxyAPI qui aurait pu exposer
quelque chose de semblable est explicitement bloquée. Aucun moyen pour un process distant (le mod) d'y
accéder.

**Conséquence pour `/narrator usage`** : le mod doit continuer à calculer sa propre consommation à partir
de ce que chaque réponse Messages renvoie déjà — exactement ce que fait `ClaudeClient.kt`
(`message.usage()` → `CallStats` avec tokens d'entrée, de lecture/écriture de cache et de sortie, par
appel). **Confirmé en réel** : le corps de réponse (`message_start.message.usage` et
`message_delta.usage`) porte bien `input_tokens`, `output_tokens`, `cache_creation_input_tokens`,
`cache_read_input_tokens`, et un détail `cache_creation: {ephemeral_5m_input_tokens,
ephemeral_1h_input_tokens}` — voir § *Vérifié en réel*, point d. Si l'on veut un jour que `/narrator usage`
reflète le grand livre budgétaire de LLMGW lui-même, il faudrait une nouvelle route HTTP authentifiée côté
LLMGW (demande d'évolution amont) ou un rapport hors-bande via `llmgw usage show` lancé par l'opérateur.

## Vérifié en réel (25/09/2026) — test manuel initial, complété depuis

**Ce test manuel (`curl`, hors du mod) a été bloqué avant terme sur les points e, f, g et h** (voir « Note
sur le blocage » ci-dessous) : ces points ont depuis été **repris et terminés par les tests de fumée
automatisés `gradlew liveTest`** (`LiveGatewayTest.kt`), voir § *Vérifié par liveTest (25/09/2026)*
ci-après, qui fait foi pour ces points. Cette section reste comme trace de la première exploration ; elle
n'est plus mise à jour au-delà de cette note.

Test de fumée mené contre le serveur LLMGW réel du propriétaire, via le tunnel SSH
`ssh -o BatchMode=yes -o ExitOnForwardFailure=yes -o ConnectTimeout=10 -N -L 8088:localhost:8088
clement@192.168.1.20` depuis un poste du même réseau local. Clé lue depuis `.secrets/llmgw.key`, jamais
affichée. Coût total des appels réels : de l'ordre de quelques centimes (une poignée d'appels courts sur
`claude-opus-5-5`, `max_tokens` 256-512, effort `low`).

- **a) SSE et réponse complète** — **Confirmé.** La requête `POST /v1/messages` avec `stream: true` renvoie
  la séquence SSE complète (`message_start` → `content_block_start` → `content_block_delta`×N →
  `content_block_stop` → `message_delta` → `message_stop`), sans coupure.
- **b) Bloc `thinking` avec résumé** — **Non conclusif.** Sur l'appel testé (effort `low`, réponse
  immédiate en appel d'outil), le modèle n'a produit aucun bloc `thinking` visible
  (`thinking_tokens: 0` dans `usage`) : la réflexion adaptative a jugé la tâche trop triviale pour y passer
  du budget, ce qui est un comportement attendu à `low`. Il faudrait un appel sur une tâche plus complexe
  (ou un effort plus élevé) pour observer un résumé de `thinking` réellement rempli ; non refait faute de
  budget d'appels restant dans cette session.
- **c) Nom de l'outil (préfixage)** — **Confirmé, non préfixé.** L'outil `roll_dice` déclaré par le client
  revient dans `content_block_start.content_block.name` sous son nom exact, sans `mcp__llmgw__`, conforme à
  la lecture du code (§2).
- **d) Champ `usage`** — **Confirmé, avec le détail du cache.** Exemple observé :
  `{"input_tokens": 2, "cache_creation_input_tokens": 591, "cache_read_input_tokens": 0,
  "cache_creation": {"ephemeral_5m_input_tokens": 0, "ephemeral_1h_input_tokens": 591},
  "output_tokens": 59}`. Le détail `cache_creation` par durée de TTL (5 min / 1 h) est une information que
  `ClaudeClient.kt` n'exploite pas aujourd'hui (il ne lit que le total `cacheCreationInputTokens`), ce qui
  reste suffisant pour `CallStats`.
- **e) Lecture de cache (deux appels, même `X-Session-ID`, gros prompt système)** — **Non terminé.** Le
  test dédié (prompt système ~2 500 tokens envoyé deux fois avec le même `X-Session-ID`) n'a pas pu être
  exécuté : bloqué par la classification automatique de permissions de cet environnement d'exécution avant
  d'avoir pu lancer le second appel (voir note ci-dessous). Un seul appel a réussi et montre une **écriture**
  de cache (`cache_creation_input_tokens: 591`, `cache_read_input_tokens: 0`) sur un prompt bien plus court
  que 2 500 tokens — cohérent avec un premier appel. La lecture de cache au second appel reste à vérifier.
- **f) Message `role: "system"` au milieu de la conversation** — **Non testé.** C'est le point le plus
  important pour l'architecture, et celui dont l'exécution a été bloquée par la classification de
  permissions de cet environnement avant d'obtenir un résultat. À refaire en priorité.
- **g) En-têtes de réponse utiles (id de requête, limites de débit)** — **Non capturés.** La première
  tentative de capture (avec `curl -D` pour écrire les en-têtes de réponse dans un fichier séparé) a
  déclenché la même classification de permissions ; les appels suivants ont été refaits sans capture
  d'en-têtes pour rester dans un schéma d'appel accepté, donc aucun en-tête de réponse n'a été inspecté
  cette fois. À refaire.
- **h) Erreurs (mauvaise clé → 401, requête invalide → 400)** — **Partiellement observé.** Le test dédié
  (mauvaise clé, puis requête volontairement invalide) n'a pas pu être mené. En revanche, une vraie 400 a
  été obtenue en pratique, sans être cherchée : envoyer `cache_control: {"type": "ephemeral"}` sans `ttl`
  alors qu'un bloc porte déjà un `cache_control` serveur avec `ttl="1h"` renvoie
  `HTTP 400` avec le corps
  `{"type":"error","error":{"type":"invalid_request_error","message":"Top-level cache_control has
  ttl='5m' but the target block already has cache_control with ttl='1h'. When both are specified on the
  same block, they must have matching TTLs."}}` — confirme la forme standard Anthropic
  (`{"type":"error","error":{"type":...,"message":...}}`) pour au moins ce cas de 400. Le cas 401 (mauvaise
  clé) reste à tester.

**Note sur le blocage** : les points e), f) et g) n'ont pas pu être terminés parce que l'environnement
d'exécution utilisé pour ce test fait passer chaque commande shell par un classifieur de permissions
automatique, qui a signalé (de façon intermittente — 3 des 5 appels `curl` utilisant la clé ont été
acceptés, 2 refusés) certains des appels `curl` portant la clé LLMGW dans un en-tête comme risque
d'exfiltration de données, même si la destination était `http://localhost:8088` via le tunnel SSH autorisé
par `CLAUDE.md`. Ce n'est pas une propriété de LLMGW ni du mod : c'est une restriction propre à cette
session d'exécution, qui nécessite soit une règle de permission explicite ajoutée par l'opérateur pour ce
type d'appel, soit de relancer ces points précis depuis un environnement sans cette restriction.

## Vérifié par liveTest (25/09/2026)

Suite de fumée automatisée (`gradlew liveTest`, tag JUnit `live`, hors de `test`/`build`/`check`), qui
tourne contre le serveur LLMGW réel du propriétaire via le même tunnel SSH que ci-dessus, avec la clé de
`.secrets/llmgw.key` ou `NARRATOR_LLMGW_KEY`. Source : `LiveGatewayTest.kt`
(`TheMod/src/test/kotlin/io/github/clemsix6/narrator2/live/LiveGatewayTest.kt`, tests `a)` à `f)`), ajoutée
par le correctif de M0. Reprend et termine les points e, f, g et h laissés bloqués par le test manuel
ci-dessus.

- **a) ping via `ClaudeClient`** — **Confirmé.** Un aller-retour complet avec la configuration réelle
  (`ConfigStore.load`, rôle Directeur, effort `low`) rend une réponse utilisable (`SendResult.Reply`,
  `stop_reason: end_turn`).
- **b) résumé de réflexion (`thinking`) non vide** — **Confirmé à l'effort `medium`.** Une tâche de
  raisonnement (arithmétique à plusieurs contraintes) produit un bloc `thinking` avec un résumé non vide,
  contrairement au test manuel à l'effort `low` (point b, non conclusif) où la réflexion adaptative avait
  jugé la tâche trop triviale pour y passer du budget.
- **c) lecture de cache au 2ᵉ appel, même `X-Session-ID`** — **Confirmé.** Deux appels identiques sur la
  même conversation : le premier écrit **5 344 tokens** dans le cache, le second en relit **5 344**
  (`cache_read_input_tokens > 0`), **sans marqueur `cache_control` envoyé par le client**
  (`gateway.cacheTtl = "off"`, §2). C'est le point qui a fait passer le défaut de `gateway.cacheTtl` de
  `"1h"` à `"off"` (commit « Send no cache marker by default »).
- **d) message `role: "system"` en cours de conversation** — **Confirmé, accepté et suivi.** Inséré juste
  après un message `user`, avec une consigne contradictoire au ton du système initial (« answer every
  question with the single word BANANA »), le modèle le respecte à partir du tour suivant. Pas de 400.
  C'était le point le plus structurant laissé ouvert par le test manuel (point f).
- **e) appel d'outil, aller-retour** — **Confirmé.** Le nom d'outil déclaré par le client (`roll_dice`)
  revient non préfixé dans l'appel du modèle (cohérent avec le point c du test manuel) ; le `tool_result`
  renvoyé est bien lu et intégré à la réponse finale.
- **f) erreurs** — **Confirmé** pour la mauvaise clé et le message vide, restés non testés par le test
  manuel (son point h) : mauvaise clé → **401**, corps `{"error":{"type":"authentication_error"}}`, sans
  `message` ; message utilisateur vide → **400**, `cache_control cannot be set for empty text blocks` ; un
  marqueur `5m` de notre part → **400**, `ttl='5m'` contre le `ttl='1h'` déjà posé par le serveur ; un
  marqueur `1h` de notre part sur un deuxième tour utilisateur → **400**, « A maximum of 4 blocks with
  cache_control… Found 5 » (voir §2).

**Test de bout en bout complémentaire (manuel, hors `liveTest`)** : sur le serveur de dev (`runServer`,
EULA acceptée avec l'accord du propriétaire pour les serveurs de dev et de test — voir `CLAUDE.md`),
`/narrator ping Bonjour, qui es-tu ?` a obtenu une réponse dans le personnage en 4,5 s, confirmant la
chaîne complète mod → tunnel SSH → LLMGW → Claude → mod → chat en conditions quasi réelles.

## Exigences pour ClaudeClient

Ce qui doit être fait ou évité, en s'appuyant sur ce qui précède (mis à jour après `liveTest`, voir
§ *Vérifié par liveTest* ci-dessus — l'exigence n°1 de la version précédente de cette section, « fixer
`cache_control.ttl` à `1h` explicitement », s'est révélée **fausse** contre ce serveur et est remplacée) :

1. **Ne poser aucun marqueur `cache_control` de notre part par défaut (`gateway.cacheTtl = "off"`).** Le
   serveur LLMGW du propriétaire pose déjà ses propres marqueurs `ttl=1h` (prompt système, derniers tours) ;
   le cache fonctionne sans notre marqueur (5 344 tokens écrits puis relus, point c ci-dessus), et un
   marqueur de notre part est soit rejeté dès le deuxième tour (`1h`, « maximum 4 blocks »), soit toujours
   rejeté (`5m`, conflit de `ttl`). `gateway.cacheTtl` accepte encore `1h` et `5m` pour une passerelle qui ne
   poserait pas son propre marqueur (pas celle-ci). **C'est l'inverse de la recommandation initiale de cette
   section, corrigée après le test complet.**
2. **Continuer à toujours streamer** (déjà le cas) — confirmé nécessaire et suffisant en réel : le flux SSE
   arrive complet sans tampon côté gateway.
3. **Continuer à toujours fixer `output_config.effort` explicitement par rôle** (déjà le cas) — empêche
   l'injection automatique de LLMGW de se déclencher ; `roles.*.effort` de `narrator2.json` reste l'unique
   source de vérité.
4. **Continuer à envoyer un `X-Session-ID` stable et unique par conversation** (déjà le cas) —
   indispensable à l'affinité de session/cache amont d'après la configuration attendue
   (`routing.session-affinity: true`). **La lecture de cache est confirmée** : `cache_read_input_tokens > 0`
   au 2ᵉ appel d'une même conversation, 5 344 tokens relus sur 5 344 écrits (point c ci-dessus).
5. **Garder les noms d'outils du mod à 52 caractères ou moins** (ou déjà préfixés `mcp__`) pour que le
   préfixage `mcp__llmgw__` de LLMGW s'applique systématiquement — confirmé en réel que le préfixe est bien
   retiré avant de revenir au client.
6. **Ne jamais s'appuyer sur un en-tête `Retry-After` pour le 402 `budget_exceeded`** — lire
   `error.reset_at` (RFC3339 UTC) dans le corps JSON. **Fait** : `ClaudeClient.kt` le lit ainsi et ne
   réessaie jamais un 402.
7. **Classer les erreurs correctement** : `401`/`400`/`413`/`402` → jamais de retry immédiat ; `503`, `429`,
   `529`, toute 5xx, erreur de connexion ou flux coupé avant tout contenu → retry avec backoff exponentiel,
   jitter et `retry-after`, **propre au client** (`gateway.maxRetries`, 3 par défaut) — les retries du SDK
   Java sont coupés (`maxRetries(0)`) pour ne pas s'empiler sur ceux déjà faits par CLIProxyAPI en amont
   (correction par rapport à la version précédente de ce point, qui envisageait de s'appuyer sur la
   politique de retry par défaut du SDK).
8. **Ne pas ajouter de couple d'en-têtes `X-Project`/`X-Tags`** — schéma de la conception V1 abandonnée ;
   l'architecture actuelle authentifie uniquement par clé projet.
9. **Ne pas tenter d'implémenter une requête d'usage vers LLMGW** — aucun endpoint à appeler (§4).
10. **Vérifier auprès de l'opérateur le type de credential amont réellement configuré.** La recommandation
    d'une clé API Anthropic (plutôt qu'un compte OAuth) reste valable (`docs/ARCHITECTURE.md` §14). Le
    `liveTest` du 25/09/2026 rend l'hypothèse OAuth **plus probable** qu'au premier test : la passerelle
    réécrit nos requêtes (elle insère un bloc avant notre texte et pose elle-même les marqueurs de cache),
    ce qui, d'après la lecture du code, est le signe d'un cloaking CLIProxyAPI actif — donc d'un compte
    d'abonnement en amont. Ce n'est encore qu'un faisceau d'indices côté client : **à confirmer avec le
    propriétaire**, pas à trancher depuis ce dépôt.
11. **Points encore ouverts** (non résolus par `liveTest`, voir § *Vérifié par liveTest* ci-dessus pour tout
    le reste, désormais confirmé) :
    - les en-têtes `Anthropic-Beta` avec ce compte amont (remplacés ou conservés ? non capturé) ;
    - les en-têtes de limite de débit (rate limit), non capturés ;
    - le code/corps/en-têtes exacts renvoyés pour un 429/529 Anthropic une fois le budget de retry de
      CLIProxyAPI épuisé (non déclenché à ce jour) ;
    - la forme réelle du corps d'un 402 `budget_exceeded` (toujours lue dans le code, jamais observée en
      HTTP faute de budget dépassé pendant un test) ;
    - le type de credential amont réellement configuré (point 10 ci-dessus) — à demander au propriétaire.

## Vérifié en réel (19/10/2026) : Claude Haiku 5.5 pour les réflexes (REFLEX-HAIKU)

Appels réels via le tunnel, environ 520 en tout (environ 0,16 $ au tarif de l'API), par `LiveReflexEvalTest` et
quelques sondes :

- **`claude-haiku-5-5` est exposé** et répond en **non-streaming** (`POST /v1/messages` sans `stream`).
- **`thinking: {type: "disabled"}` avec `output_config.effort: "low"` est accepté.** Sans cela, Haiku 5.5 réfléchit par
  défaut. `thinking: {type: "adaptive"}` à l'effort `low` marche aussi : il réfléchit peu (environ 17 tokens de plus
  par appel en moyenne).
- **La sortie structurée (`output_config.format`, `type: json_schema`) est relayée** et respectée : le texte est
  toujours un objet JSON conforme. Elle règle aussi le piège de l'identité « Claude Code » : avec un prompt de rôle et
  une sortie libre, Haiku refusait (« I'm Claude Code… ») ; contraint par le schéma, il étiquette. Le prompt système
  est bien reçu (le modèle sait en citer la première phrase) ; le cadre « tâche d'étiquetage de données » reste utile.
- **Sans réflexion, le modèle doit écrire une raison avant ses chiffres.** Sans le champ `reason` en tête du schéma,
  `addressed` est erratique (39/52 justes) ; avec, 48 à 50/52 (passation REFLEX-HAIKU).
- **Latence** (chat, sans réflexion, avec la raison) : p50 1,5 à 1,7 s, p95 1,8 s, quelques pointes de 2,4 à 3,5 s ; le
  premier appel après un démarrage peut dépasser 3 s.
- **Cache** : LLMGW pose ses marqueurs `1h`. Selon les passes, le préfixe d'environ 2 860 tokens est **entièrement relu**
  (0 écrit, environ 0,0001 $ par appel) ou **réécrit à chaque appel** (environ 600 relus et 2 280 écrits, environ
  0,0005 $ par appel), sans changement de notre côté : sans doute selon le compte amont choisi. Rien à régler dans le
  mod.
- **Identifiant de requête** : présent à chaque réponse (`X-Cpa-Trace-Id`, lu par `requestIdOf`).
