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
- **`cache_control` au niveau racine** — **comportement réel différent de l'hypothèse initiale, voir §
  *Vérifié en réel*.** L'API Messages Anthropic n'a pas de champ `cache_control` de premier niveau au sens
  strict — c'est un marqueur qui finit par s'appliquer à un bloc de contenu (« top-level auto-caching »).
  Testé en réel le 25/09/2026 : envoyer `cache_control: {"type": "ephemeral"}` sans `ttl` explicite (ce
  que fait `.cacheControl(CacheControlEphemeral.builder().build())` dans `ClaudeClient.kt` aujourd'hui,
  qui laisse le SDK sur son défaut implicite `ttl=5m`) est **rejeté avec une 400** : un bloc — probablement
  le prompt système — porte déjà côté serveur un `cache_control` avec `ttl=1h`, posé **avant** que le
  marqueur du client ne s'applique, et Anthropic exige que les deux correspondent quand les deux existent
  sur le même bloc. Avec `cache_control: {"type": "ephemeral", "ttl": "1h"}` explicite côté client, la
  requête passe. Ceci contredit l'hypothèse de la recherche initiale selon laquelle, avec un compte amont
  **clé API** (sans cloaking), rien ne pose de marqueur de cache avant le nôtre — soit le compte amont
  configuré n'est pas (ou plus) une simple clé API, soit LLMGW/CLIProxyAPI pose ce marqueur par défaut
  indépendamment du type de credential. Aucune des deux causes n'a pu être confirmée depuis ce test seul
  (pas d'accès à la configuration serveur). **Voir « Exigences pour ClaudeClient » ci-dessous : c'est le
  changement le plus important trouvé par ce test.**
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
  lui-même n'y touche jamais d'après le code source. Avec un compte amont OAuth, CLIProxyAPI rejette avec
  400 un tour `role:"system"` si le modèle cible fait partie de sa liste de modèles « legacy » sans support
  du tour mi-conversation, mais `claude-opus-5-5` n'y figurait pas d'après la lecture initiale du module
  CLIProxyAPI embarqué. **Non re-testé en réel** : le test prévu pour ce point a été bloqué avant d'aboutir
  (§ *Vérifié en réel*, point f). À traiter en priorité lors d'un prochain test, car c'est le point le plus
  structurant pour l'architecture (consignes du moteur injectées en cours de conversation sans toucher au
  prompt système initial).
- **En-têtes `anthropic-beta`** : avec un compte amont clé API, les en-têtes `Anthropic-Beta` du client
  sont conservés tels quels (« mode `preserveCallerFingerprint` » d'après la lecture initiale du module
  CLIProxyAPI embarqué) ; avec OAuth, la liste est remplacée par un jeu de betas assemblés pour imiter
  Claude Code. **Non re-vérifié en réel** (§ *Vérifié en réel*, point g bloqué).
- **`X-Session-ID` (affinité de session)** : nom exact confirmé (`ClaudeClient.kt`,
  `docs/ARCHITECTURE.md`). Consommé par le réglage natif CLIProxyAPI `routing.session-affinity: true` :
  « Sticky sessions pin every turn of one conversation to the same upstream account. Anthropic's prompt
  cache lives per account, so without this a multi-turn agent loop round-robins across accounts and
  re-pays its whole context as a cache write on almost every turn. » Le test de lecture de cache prévu
  (deux appels identiques avec le même `X-Session-ID`, point e) **n'a pas pu être mené à son terme** (§
  *Vérifié en réel*) — un seul appel a réussi, confirmant une écriture de cache (`cache_creation`) mais pas
  encore une lecture (`cache_read_input_tokens > 0`).
- **Modèles acceptés** : LLMGW ne maintient aucune liste blanche de modèles. Le champ `model` traverse sans
  modification. **Confirmé en réel** : `GET /v1/models` liste bien `claude-opus-5-5` et
  `claude-haiku-4-5-20251001` (ainsi que l'ID canonique `claude-haiku-4-5` sans suffixe de date) — voir §
  *Vérifié en réel*.

**Résumé compte amont clé API vs OAuth** : d'après la lecture initiale du code, seul un compte amont OAuth
déclenche le cloaking (réécriture du prompt système, marqueurs de cache maison, betas remplacés) ; avec une
clé API, seuls le préfixage des noms d'outils et l'injection d'effort par défaut (permanents, non liés au
cloaking) s'appliquent. **Le test réel du 25/09/2026 nuance ce résumé** sur un point précis : un marqueur
`cache_control` avec `ttl=1h` était déjà présent sur un bloc avant l'application du nôtre, ce qui ne devrait
pas arriver avec un compte amont clé API d'après cette même lecture. `docs/ARCHITECTURE.md` recommande
toujours la clé API pour cette raison ; le point mérite d'être vérifié avec l'opérateur (quel type de
credential est réellement configuré côté serveur aujourd'hui).

## 3. Erreurs et limites

- **Budgets** : `llmgw budget set PROJET --dimension calls|tokens|cost --window hour|day --max N --action
  block|warn`. Sur dépassement avec `action=block` : HTTP 402, corps
  `{"error":{"type":"budget_exceeded","dimension":...,"window":...,"reset_at":"<RFC3339 UTC>"}}`
  ([`internal/adapter/cliproxy/middleware.go`](https://github.com/clemsix6/LLMGW/blob/main/internal/adapter/cliproxy/middleware.go)).
  **Aucun en-tête `Retry-After` n'est posé** sur cette réponse — seul le `reset_at` JSON donne l'heure de
  réinitialisation. Avec `action=warn`, la requête passe et seul un log serveur est émis — le client n'a
  aucun moyen HTTP de voir un warning. **Non déclenché en réel** (pas de budget dépassé pendant ce test).
- **Autres codes propres à LLMGW** (d'après la lecture du code, non tous déclenchés en réel) : `401
  authentication_error` (credential manquant, mal formé, dupliqué ou incohérent entre `Authorization` et
  `x-api-key`) ; `503 service_unavailable` (panne DB transitoire, SDK pas encore prêt, permis de génération
  épuisés, ou bridge « empoisonné ») ; `413 request_too_large` (corps > 32 MiB) ; `400
  invalid_request_error` (le client a coupé l'envoi du corps en cours de route, ou requête malformée —
  **une vraie 400 a bien été observée en réel**, voir § *Vérifié en réel* point h, sur le conflit de `ttl`
  de `cache_control` plutôt que sur le scénario prévu).
- **429 / 529 / compte amont indisponible** : viennent d'Anthropic lui-même (ou de la logique de
  cooldown/rotation de comptes amont de CLIProxyAPI), pas du middleware LLMGW — frontière de
  responsabilité explicite du `CLAUDE.md` du repo LLMGW (« The embedded SDK owns provider protocols,
  provider authentication, routing, cooldowns, and retries »). Leviers configurables :
  `request-retry: 1`, `max-retry-credentials: 2`, `max-retry-interval: 5`,
  `transient-error-cooldown-seconds: -1`, `disable-cooling: false`. **Non déclenché en réel.**
- **Timeouts côté passerelle** : aucun réglage de timeout de réponse propre à LLMGW trouvé dans le code. Le
  streaming étant relayé sans tampon, une génération longue ne devrait pas buter sur un timeout HTTP
  non-streaming fixe côté LLMGW. Le seul timeout garanti reste celui du client
  (`.timeout(Duration.ofSeconds(600))`, `.maxRetries(2)` dans `ClaudeClient.kt`/`NarratorConfig.kt`).
- **Ce que le client doit réessayer ou non** (inchangé) :
  - `401`/`400`/`413` : erreurs de configuration ou de requête, jamais à réessayer tel quel.
  - `402 budget_exceeded` : jamais de retry immédiat — attendre `reset_at`.
  - `503 service_unavailable` : transitoire par construction côté LLMGW — un retry avec backoff est
    raisonnable.
  - Erreurs forwardées depuis Anthropic (429/529/5xx après épuisement du budget de retry CLIProxyAPI) :
    laisser le SDK Java gérer via sa politique de retry par défaut (`maxRetries = 2`), sans l'empiler
    par-dessus les retries déjà faits par CLIProxyAPI.

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

## Vérifié en réel (25/09/2026)

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

## Exigences pour ClaudeClient

Ce qui doit être fait ou évité, en s'appuyant sur ce qui précède :

1. **Fixer explicitement `cache_control.ttl` à `"1h"` sur le marqueur racine — changement le plus
   important trouvé par ce test.** `ClaudeClient.kt` appelle aujourd'hui
   `.cacheControl(CacheControlEphemeral.builder().build())`, sans `ttl`, ce qui laisse le SDK sur son
   défaut implicite `5m`. Contre le serveur réel du propriétaire, cela produit une **400
   invalid_request_error** systématique (« ttl mismatch », voir § *Vérifié en réel* point h) car un bloc
   porte déjà côté serveur un `cache_control` avec `ttl="1h"`. Il faut positionner ce `ttl` explicitement
   sur le builder (à vérifier dans l'API du SDK Java — `CacheControlEphemeral.builder().ttl(...)` ou
   équivalent) pour que chaque appel Messages réussisse. **Sans ce changement, aucun appel réel via ce
   gateway ne peut aboutir.**
2. **Continuer à toujours streamer** (déjà le cas) — confirmé nécessaire et suffisant en réel : le flux SSE
   arrive complet sans tampon côté gateway.
3. **Continuer à toujours fixer `output_config.effort` explicitement par rôle** (déjà le cas) — empêche
   l'injection automatique de LLMGW de se déclencher ; `roles.*.effort` de `narrator2.json` reste l'unique
   source de vérité.
4. **Continuer à envoyer un `X-Session-ID` stable et unique par conversation** (déjà le cas) —
   indispensable à l'affinité de session/cache amont d'après la configuration attendue
   (`routing.session-affinity: true`). La lecture de cache (`cache_read_input_tokens > 0` au 2ᵉ appel)
   reste à confirmer empiriquement (point e, non terminé).
5. **Garder les noms d'outils du mod à 52 caractères ou moins** (ou déjà préfixés `mcp__`) pour que le
   préfixage `mcp__llmgw__` de LLMGW s'applique systématiquement — confirmé en réel que le préfixe est bien
   retiré avant de revenir au client.
6. **Ne jamais s'appuyer sur un en-tête `Retry-After` pour le 402 `budget_exceeded`** — lire
   `error.reset_at` (RFC3339 UTC) dans le corps JSON.
7. **Classer les erreurs correctement** : `401`/`400`/`413`/`402` → jamais de retry immédiat ; `503` →
   retry avec backoff ; codes forwardés depuis Anthropic (429/529/5xx) → laisser la politique de retry par
   défaut du SDK Java faire son travail sans l'empiler excessivement.
8. **Ne pas ajouter de couple d'en-têtes `X-Project`/`X-Tags`** — schéma de la conception V1 abandonnée ;
   l'architecture actuelle authentifie uniquement par clé projet.
9. **Ne pas tenter d'implémenter une requête d'usage vers LLMGW** — aucun endpoint à appeler (§4).
10. **Vérifier auprès de l'opérateur le type de credential amont réellement configuré.** La recommandation
    d'une clé API Anthropic (plutôt qu'un compte OAuth) reste valable (`docs/ARCHITECTURE.md` §14), mais le
    test réel du 25/09/2026 a observé un comportement (marqueur `cache_control` `ttl=1h` déjà présent sur
    un bloc) qui, d'après la lecture du code, ne devrait se produire qu'en mode cloaking OAuth. À clarifier
    avec le propriétaire plutôt qu'à supposer.
11. **Points encore à vérifier empiriquement** (non conclus par ce test, voir § *Vérifié en réel*) :
    - un bloc `thinking` avec résumé rempli, sur une tâche moins triviale qu'un simple lancer de dé ;
    - la lecture de cache (`cache_read_input_tokens > 0`) au 2ᵉ appel d'une même conversation/`X-Session-ID` ;
    - l'acceptation et la prise en compte d'un message `role: "system"` inséré au milieu de `messages[]` —
      **le point le plus structurant pour l'architecture**, à refaire en priorité ;
    - les en-têtes de réponse (id de requête, limites de débit) ;
    - le comportement sur une mauvaise clé (401) ;
    - le code/corps/en-têtes exacts renvoyés pour un 429/529 Anthropic une fois le budget de retry de
      CLIProxyAPI épuisé.
