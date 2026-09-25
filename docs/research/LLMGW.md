# LLMGW — notes d'intégration pour ClaudeClient

Recherche menée sur le code source de LLMGW cloné dans `.research/LLMGW/` (Go, module
`github.com/clemsix6/LLMGW`, `CLIProxyAPI v7.3.15` embarqué en librairie — voir
`.research/LLMGW/go.mod:9` et `.research/LLMGW/VERSION` = `1.7.0`), croisé avec
`docs/ARCHITECTURE.md` §14, `CLAUDE.md` et
`TheMod/src/main/kotlin/io/github/clemsix6/narrator2/llm/ClaudeClient.kt`. Les extraits de
CLIProxyAPI lui-même (`cloaking.go`, `claude_executor_request.go`, `stream.go`, `execute.go`)
sont dans le scratchpad de session ; les références `fichier:ligne` de cette section pointent
vers ces extraits, pas vers `.research/LLMGW`.

**Avertissement historique** : `.research/LLMGW/docs/specs/2026-06-18-llmgw-design.md` décrit
une V1 antérieure où LLMGW réimplémentait lui-même le client OAuth Claude Max (headers
`X-Project`/`X-Tags`, réponses 402/503 maison, pas de CLIProxyAPI). Cette conception a été
**remplacée** par l'architecture actuelle (LLMGW = authentification par clé projet + budgets +
comptabilité, posés sur CLIProxyAPI embarqué qui gère les protocoles fournisseurs). Le README,
le `CLAUDE.md` du repo et le code sous `internal/` sont la source de vérité ; le document de
design du 18/06 est cité seulement comme contexte historique.

## 1. Lancement et accès

- **Lancement local** : `docker compose up -d` depuis `.research/LLMGW/` (ou une copie du repo
  hors `.research/`, qui n'est pas versionné) après `cp config.example.yaml config.yaml`,
  `chmod 600 config.yaml` et `cp .env.example .env` (`.research/LLMGW/README.md` section
  *Deploy*). Alternative binaire hôte : `LLMGW_CONFIG=./config.yaml llmgw serve` (même
  README). Postgres est **externe** : il faut créer la base `llmgw` à la main avant le premier
  démarrage — le `docker-compose.yml` ne fournit pas de service Postgres
  (`.research/LLMGW/docker-compose.yml:24-31`). Le `Justfile` ne sert qu'au dev du repo LLMGW
  lui-même (`fmt`, `vet`, `test-unit`, `test-integration`, `test-live`, `docker-verify`) — rien
  à lancer depuis là pour un simple test d'intégration côté mod.
- **Port par défaut / URL de base** : `8088`, écoute conteneur sur `0.0.0.0:8088` mais publiée
  uniquement sur `127.0.0.1:8088` par `docker-compose.yml`
  (`.research/LLMGW/config.example.yaml:1-2`, `.research/LLMGW/docker-compose.yml:23-27`) —
  cohérent avec la valeur par défaut du mod,
  `TheMod/src/main/kotlin/io/github/clemsix6/narrator2/config/NarratorConfig.kt:44`
  (`baseUrl = "http://127.0.0.1:8088"`).
- **Chemin API Messages** : `POST /v1/messages` (route « génération », métrée et budgétée) et
  `POST /v1/messages/count_tokens` (métadonnée, authentifiée mais non comptée dans les
  budgets) — `.research/LLMGW/internal/adapter/cliproxy/routes.go:58-70`. `GET /v1/models` et
  `/v1beta/models*` sont aussi des routes métadonnées (mêmes lignes). Le healthcheck public est
  `GET /healthz` (`routes.go:24-26`) — c'est lui que `curl -fsS http://127.0.0.1:8088/healthz`
  interroge dans le README.
- **Authentification** : `Authorization: Bearer <clé projet>` **ou** `x-api-key: <clé projet>`,
  les deux formes sont acceptées et doivent être cohérentes si les deux sont présentes
  (`.research/LLMGW/internal/adapter/cliproxy/access.go:66-119`). C'est exactement la forme
  `x-api-key` que pose le SDK Java Anthropic via `AnthropicOkHttpClient.builder().apiKey(...)`
  dans `ClaudeClient.kt:34-36` — rien à adapter côté mod.
- **Créer une clé projet** : il n'existe **pas** de commande « create project » séparée — le
  projet est créé implicitement par la première clé :
  `llmgw key create <projet> --name <libellé> [--expires <durée>]`
  (`.research/LLMGW/internal/command/key.go:39-79`, confirmé par le README « A project is
  created by issuing its first key »). La clé en clair n'est affichée **qu'une seule fois**
  (`key.go:200-208`) — à stocker immédiatement dans la variable d'environnement
  `NARRATOR_LLMGW_KEY` que lit `NarratorConfig.kt:47,51`. `llmgw key list|rotate|revoke` gèrent
  le cycle de vie ensuite (`key.go:83-193`). Toutes ces commandes sont **locales** : elles
  ouvrent directement la base Postgres (`openStore(...)`), pas d'API HTTP d'administration —
  l'interface web/API de gestion native de CLIProxyAPI est explicitement désactivée et bloquée
  (`routes.go:37-45` renvoie 404 sur `/`, `/management.html`, `/v0/management*`,
  `/v0/resource/plugins*` ; confirmé par `remote-management.disable-control-panel: true` et
  `home.enabled: false` dans `config.example.yaml:6-12`).

## 2. Ce qui passe intact et ce qui est réécrit

Le README de LLMGW liste explicitement deux routes réécrites côté sortie,
`POST /v1/messages` et `POST /v1/messages/count_tokens` — tout le reste passe tel quel
(`.research/LLMGW/README.md` section *What the gateway rewrites on Anthropic requests*). Le
code confirme et précise :

- **Streaming SSE** : relayé sans tampon (« no buffering — streaming latency preserved »,
  README section *Public API*). La seule chose que LLMGW touche dans le flux est la
  suppression du préfixe `mcp__llmgw__` sur les événements `content_block_start` de type
  `tool_use` — les octets de trame SSE (`event:`, `data:`, délimiteurs) sont préservés à
  l'identique (`.research/LLMGW/internal/domain/toolprefix/response.go:26-56`).
- **`thinking: {type: "adaptive", display: "summarized"}`** : LLMGW lui-même ne touche jamais
  `thinking.*` sauf pour *lire* `thinking.type` afin de savoir s'il doit s'abstenir d'injecter
  un effort par défaut (`.research/LLMGW/internal/domain/effort/apply.go:14-17,35-37`). Avec un
  compte amont **clé API** (pas de cloaking, voir plus bas), le champ traverse intact. Avec un
  compte amont **OAuth**, CLIProxyAPI ne réécrit `thinking.display` que pour les modèles
  Fable 5.1/Mythos 5.1 (`cloaking.go:975-990` dans le scratchpad) — Opus 5.5 n'est pas concerné.
- **`output_config.effort`** : LLMGW injecte l'effort par défaut du projet
  (`llmgw project effort <projet> low|medium|high|xhigh|max|none`,
  `.research/LLMGW/internal/command/project.go:99-125`) **seulement si la requête n'a aucun
  `output_config.effort`**, quelle que soit sa valeur — et jamais si le client a explicitement
  désactivé le thinking (`thinking.type == "disabled"`), car Anthropic refuse un effort
  au-dessus de `high` combiné à un thinking désactivé
  (`.research/LLMGW/internal/domain/effort/apply.go:20-44`). Comme `ClaudeClient.kt:54` pose
  toujours `OutputConfig.Effort` explicitement par rôle, cette injection ne se déclenche
  jamais pour nos appels — c'est la configuration `roles.*.effort` de `narrator2.json` qui reste
  la seule source de vérité.
- **`cache_control` au niveau racine** : l'API Messages Anthropic n'a pas de champ
  `cache_control` de premier niveau — c'est un marqueur par bloc de contenu. Le
  `.cacheControl(CacheControlEphemeral.builder().build())` de `ClaudeClient.kt:57` est un
  helper du SDK Java (pas un champ JSON racine littéral) ; son effet exact sur le JSON envoyé
  n'a pas pu être vérifié depuis le code source lu ici — **à confirmer empiriquement** (log
  `request-log` côté LLMGW ou inspection via `Transcripts`) une fois un vrai gateway démarré.
  Ni `effort.Apply` ni `contextedit.Claim` ni `toolprefix.PrefixRequest` ne touchent aux
  marqueurs `cache_control` existants. En revanche, en mode **cloaking OAuth**, CLIProxyAPI pose
  ses **propres** marqueurs `cache_control: {type:"ephemeral"}` sur les blocs système qu'il
  injecte et sur le premier bloc de texte utilisateur réel (`cloaking.go:1160,1269-1275` du
  scratchpad), et ajoute potentiellement un `ttl` étendu via le beta
  `extended-cache-ttl-2025-04-11` pour les comptes OAuth hors sous-agent/sonde
  (`claude_executor_request.go:266-273` du scratchpad).
- **`tools` et leurs noms** : **toujours** réécrits par LLMGW lui-même, sur les deux routes
  (`/v1/messages` et `count_tokens`), **indépendamment du type de compte amont** — ce n'est pas
  un comportement de cloaking OAuth mais une normalisation LLMGW permanente
  (`.research/LLMGW/internal/adapter/cliproxy/request_rewrite.go:57-73`). Tout outil déclaré par
  le client (`type` absent ou `type:"custom"`) est préfixé `mcp__llmgw__` dans `tools[].name`,
  `tool_choice.name` et `messages[].content[].name` (blocs `tool_use` rejoués) — jamais dans les
  arguments ni dans `tool_result` (`.research/LLMGW/internal/domain/toolprefix/request.go:37-70`).
  Sont exemptés : les outils natifs Anthropic (identifiés par leur `type`, jamais par leur nom,
  `request.go:49-52,95-100`), les noms déjà préfixés `mcp__` (`request.go:53-54,174-176`), et les
  noms trop longs. La constante `maxToolNameLength = 64` et le préfixe `mcp__llmgw__` (12
  caractères) donnent la limite exacte : **un nom original de plus de 52 caractères n'est pas
  préfixé** et part tel quel (`request.go:19,26-29,171-177`) — ce qui confirme au caractère près
  le « plus de 52 caractères » de `docs/ARCHITECTURE.md:383`. Les réponses (JSON et SSE) sont
  dépréfixées avant de repartir vers le client, qui ne voit jamais `mcp__llmgw__`
  (`.research/LLMGW/internal/domain/toolprefix/response.go`). Raison documentée : sans ce
  préfixe stable, le SDK embarqué renomme lui-même les noms d'outils avec une valeur
  par-requête en mode OAuth, ce qui invalide le cache de prompt Anthropic à chaque appel
  (`request.go:11-19`, confirmé par le README).
- **Messages `system` au milieu de la conversation** (`{"role":"system", ...}` dans
  `messages[]`) : LLMGW lui-même n'y touche jamais. Avec un compte amont **clé API** (pas de
  cloaking), ces tours partent tels quels vers Anthropic. Avec un compte amont **OAuth**,
  CLIProxyAPI **rejette avec 400** un tour `role:"system"` du client si le modèle cible fait
  partie de sa liste de modèles « legacy » sans support du tour mi-conversation — mais
  `claude-opus-5-5` n'y figure pas (`claude_executor_request.go:427-456` du scratchpad ne liste
  que des IDs jusqu'à `sonnet-4-6`/`opus-4-7`), donc aucun impact pour le rôle Directeur tel que
  configuré aujourd'hui. À surveiller si un rôle est un jour rétrogradé vers un modèle plus
  ancien sous credential OAuth.
- **En-têtes `anthropic-beta`** : avec un compte amont **clé API**, nos propres en-têtes
  `Anthropic-Beta` (ceux posés par le SDK Java) sont conservés tels quels (« mode
  `preserveCallerFingerprint` », `claude_executor_request.go:1052-1057,1094-1095,1218-1229` du
  scratchpad) ; seul un `speed:"fast"` dans le corps ajoute automatiquement le beta
  `fast-mode-2026-02-01`. Avec un compte amont **OAuth**, la liste entière est **remplacée**
  par un jeu de ~20 betas assemblés pour imiter Claude Code (`claudeCodeCLIBetas`,
  `claude_executor_request.go:135-281` du scratchpad — `context-management-2025-06-27`,
  `prompt-caching-scope-2026-01-05`, `mid-conversation-system-2026-04-07`,
  `effort-2025-11-24`, `extended-cache-ttl-2025-04-11`, etc. selon le corps de la requête) ; les
  betas que nous enverrions et que CLIProxyAPI ne « gère » pas lui-même restent transmis en plus
  (`claude_executor_request.go:1166-1177` du scratchpad, commentaire « newer-client features…
  forwarded »).
- **`X-Session-ID` (affinité de session)** : nom exact confirmé (`ClaudeClient.kt:58`,
  `docs/ARCHITECTURE.md:382`). Consommé par le réglage natif CLIProxyAPI
  `routing.session-affinity: true`, activé dans `config.example.yaml:20-23` avec le
  commentaire : « Sticky sessions pin every turn of one conversation to the same upstream
  account. Anthropic's prompt cache lives per account, so without this a multi-turn agent loop
  round-robins across accounts and re-pays its whole context as a cache write on almost every
  turn. » Le mécanisme précis de correspondance en-tête → session → compte amont vit dans le
  module CLIProxyAPI embarqué (`sdk/cliproxy/session/*`,
  `internal/runtime/executor/helps/claude_code_session.go` d'après l'arborescence
  `cliproxy_tree.txt` du scratchpad) et n'était **pas** dans les extraits fournis pour cette
  recherche (`cloaking.go`/`claude_executor.go`/`claude_executor_request.go`/`stream.go`/
  `execute.go` ne montrent que la lecture d'un `claudeSessionID` déjà résolu via
  `helps.ClaudeAgentSessionUUIDForRequest(...)`, pas le nom littéral de l'en-tête). Ce point
  précis reste donc documenté au niveau config/README uniquement, pas vérifié ligne à ligne dans
  le code — à valider empiriquement (§ *Pour lancer un test réel*).
- **Modèles acceptés** : LLMGW ne maintient **aucune liste blanche de modèles**. Le champ
  `model` traverse sans modification (aucune des trois réécritures — outils, effort, contexte —
  ne le touche) ; c'est CLIProxyAPI qui route vers le compte amont configuré, et Anthropic qui
  valide l'identifiant en dernier ressort. `claude-opus-5-5` et `claude-haiku-4-5-20251001`
  passeront tous deux tant que le compte amont y a accès et qu'Anthropic reconnaît l'ID — pour
  Haiku, noter que l'ID canonique sans suffixe de date est `claude-haiku-4-5` ; un ID daté
  `-20251001` peut exister ou non côté Anthropic, à vérifier indépendamment de LLMGW avant de le
  figer dans `roles.*.model`.

**Résumé compte amont clé API vs OAuth** : le seul déclencheur du cloaking est
`resolveClaudeWirePolicy` (`cloaking.go:1290-1347` du scratchpad) — en mode `auto` (celui de
`config.example.yaml`), seules les vraies creds OAuth Claude (ou un opt-in explicite de
config) sont cloakées ; « Other API keys and delegated providers keep the caller shape »
(commentaire `cloaking.go:1341-1345`). Avec une **clé API**, aucune des réécritures OAuth
ci-dessus ne s'applique : prompt système, `cache_control`, betas et forme générale de la requête
restent exactement ce que le mod envoie — seul le préfixage des noms d'outils (permanent, non
lié au cloaking) et l'injection d'effort/`context_management` par défaut (permanentes elles
aussi, mais neutralisées par notre client qui les fixe toujours lui-même) s'appliquent.
`docs/ARCHITECTURE.md:387` recommande déjà la clé API pour cette raison — le code le confirme
strictement.

## 3. Erreurs et limites

- **Budgets** : `llmgw budget set PROJET --dimension calls|tokens|cost --window hour|day --max N
  --action block|warn` (`.research/LLMGW/internal/command/budget.go:32-62`). Sur dépassement
  avec `action=block` : **HTTP 402**, corps
  `{"error":{"type":"budget_exceeded","dimension":...,"window":...,"reset_at":"<RFC3339 UTC>"}}`
  (`.research/LLMGW/internal/adapter/cliproxy/middleware.go:51-62,313-328`). **Aucun en-tête
  `Retry-After` n'est posé** sur cette réponse — seul le `reset_at` JSON donne l'heure de
  réinitialisation, à calculer soi-même côté client. Avec `action=warn`, la requête passe et
  seul un log serveur est émis (`middleware.go:330-340`) — **le client n'a aucun moyen HTTP de
  voir un warning** (pas d'en-tête, pas de corps modifié) ; seul `action=block` est visible du
  mod.
- **Autres codes propres à LLMGW** : `401 authentication_error` (credential manquant, mal
  formé, dupliqué ou incohérent entre `Authorization` et `x-api-key`,
  `access.go:66-119` + `middleware.go:142-159`) ; `503 service_unavailable` (panne DB
  transitoire, SDK pas encore prêt après un redémarrage, permis de génération épuisés, ou bridge
  « empoisonné » après une erreur interne irrécupérable — `middleware.go:115-137,171-176,
  283-298`) ; `413 request_too_large` (corps > 32 MiB, la limite de réécriture,
  `request_rewrite.go:21,123-137`) ; `400 invalid_request_error` (le client a coupé l'envoi du
  corps en cours de route, `request_rewrite.go:139-143`).
- **429 / 529 / compte amont indisponible** : ces cas viennent d'Anthropic lui-même (ou de la
  logique de cooldown/rotation de comptes amont de CLIProxyAPI) et ne sont **pas** possédés par
  le middleware LLMGW — celui-ci ne fait qu'entourer la requête (réécriture avant, dépréfixage
  après), jamais réinterpréter le code retour du handler de génération du SDK. C'est une
  frontière de responsabilité explicite du `CLAUDE.md` du repo LLMGW (« The embedded SDK owns
  provider protocols, provider authentication, routing, cooldowns, and retries »). Les leviers
  configurables sont dans `config.example.yaml:15-19`
  (`request-retry: 1`, `max-retry-credentials: 2`, `max-retry-interval: 5`,
  `transient-error-cooldown-seconds: -1`, `disable-cooling: false`) mais le mapping exact
  429/529 → cooldown/retry vit dans `sdk/cliproxy/auth/conductor*.go` du module CLIProxyAPI
  embarqué, absent des extraits fournis pour cette recherche. À vérifier empiriquement : une
  fois le budget de retry/rotation de CLIProxyAPI épuisé, on peut s'attendre à recevoir la forme
  native Anthropic (`error.type` du genre `rate_limit_error`/`overloaded_error`, en-têtes natifs
  `retry-after`/`anthropic-ratelimit-*`), mais ce n'est pas garanti sans test réel.
- **Timeouts côté passerelle** : aucun réglage de timeout de réponse côté gateway n'a été trouvé
  dans `config.example.yaml` (38 lignes au total) ni dans le code propre à LLMGW lu ici. Le
  streaming étant relayé sans tampon (§2), une génération longue ne devrait pas buter sur un
  timeout HTTP non-streaming fixe côté LLMGW. Le seul timeout garanti aujourd'hui est **celui du
  client** : `ClaudeClient.kt` pose `.timeout(Duration.ofSeconds(600))` et
  `.maxRetries(2)` (`NarratorConfig.kt:48-49`), indépendamment de LLMGW — à confirmer par un
  test réel qu'il n'existe pas de timeout de lecture plus court côté CLIProxyAPI.
- **Ce que le client doit réessayer ou non** :
  - `401`/`400`/`413` : erreurs de configuration ou de requête, **jamais** à réessayer tel quel.
  - `402 budget_exceeded` : **jamais** de retry immédiat — attendre `reset_at`.
  - `503 service_unavailable` : transitoire par construction côté LLMGW (DB, démarrage SDK,
    permis épuisés) — un retry avec backoff est raisonnable.
  - Erreurs forwardées depuis Anthropic (429/529/5xx après épuisement du budget de retry
    CLIProxyAPI) : laisser le SDK Java gérer via sa politique de retry par défaut, déjà active
    (`maxRetries = 2`). Éviter d'empiler un `maxRetries` client élevé par-dessus les retries
    déjà faits par CLIProxyAPI (`request-retry: 1` × `max-retry-credentials: 2`), pour ne pas
    créer une tempête de retries qui grille un budget déjà serré.

## 4. Suivi de consommation (`/narrator usage`)

**LLMGW n'expose aucun endpoint HTTP d'usage.** `usage show`/`usage resolve`
(`.research/LLMGW/internal/command/usage.go`) et `budget list`
(`.research/LLMGW/internal/command/budget.go`) sont des **sous-commandes CLI locales** qui
ouvrent directement Postgres sur la machine hôte (`openStore(ctx, configPath(streams),
streams)`) — ce ne sont pas des routes HTTP. `routes.go` ne mappe aucun chemin vers un handler
d'usage/budget, et la surface de gestion native de CLIProxyAPI (qui aurait pu exposer quelque
chose de semblable) est explicitement bloquée (`routes.go:37-45`, § 1). Aucun moyen pour un
process distant (le mod) d'interroger LLMGW pour sa consommation.

**Conséquence pour `/narrator usage`** : le mod doit continuer à calculer sa propre
consommation à partir de ce que chaque réponse Messages renvoie déjà — exactement ce que fait
`ClaudeClient.kt:77-95` (`message.usage()` → `CallStats` avec tokens d'entrée, de lecture/écriture
de cache et de sortie, par appel). Si l'on veut un jour que `/narrator usage` reflète le grand
livre budgétaire de LLMGW lui-même (tentatives échouées, coût notional agrégé, comptabilité
« unknown »), il faudrait soit une nouvelle route HTTP authentifiée côté LLMGW (demande
d'évolution amont), soit un rapport hors-bande via `llmgw usage show PROJET --since 24h --by
model` lancé par l'opérateur sur la machine hôte.

## 5. État local observé (lecture seule, rien lancé ni modifié)

- **Aucun processus n'écoute sur le port 8088** (ni 8089) sur cette machine — vérifié via
  `netstat -ano` (liste complète des sockets en `LISTENING`, boucle locale et toutes
  interfaces).
- **Docker n'est pas installé** sur cette machine (`docker ps` : commande introuvable) — donc
  pas non plus d'instance LLMGW lancée via `docker-compose.yml`.
- **`NARRATOR_LLMGW_KEY`** : variable d'environnement **absente** (vérifié via
  `Test-Path Env:NARRATOR_LLMGW_KEY`, aucune valeur affichée ni lue).
- Conclusion : LLMGW ne tourne pas localement aujourd'hui, et `ClaudeClient.isConfigured`
  (`ClaudeClient.kt:42`) serait `false` en l'état — `resolveApiKey()` ne trouve ni valeur en dur
  ni variable d'environnement (`NarratorConfig.kt:51`).

## Exigences pour ClaudeClient

Ce qui doit être fait ou évité, en s'appuyant sur ce qui précède :

1. **Continuer à toujours streamer** (déjà le cas) — c'est ce qui évite tout risque de timeout
   HTTP non-streaming côté gateway ; ne jamais repasser en non-streaming pour de longues
   générations.
2. **Continuer à toujours fixer `output_config.effort` explicitement par rôle** (déjà le cas,
   `ClaudeClient.kt:54`) — cela empêche l'injection automatique de LLMGW de jamais se déclencher
   et garde `roles.*.effort` de `narrator2.json` comme unique source de vérité ; cela évite aussi
   tout risque de combinaison `effort > high` + `thinking.disabled` que la V1 pourrait injecter
   par erreur si l'effort venait à manquer.
3. **Continuer à envoyer un `X-Session-ID` stable et unique par conversation** (déjà le cas,
   `ClaudeClient.kt:58`) — indispensable à l'affinité de session/cache amont. S'assurer auprès de
   l'opérateur que `routing.session-affinity: true` reste actif dans le `config.yaml` déployé
   (il ne l'est pas par défaut dans CLIProxyAPI sans cette ligne explicite).
4. **Garder les noms d'outils du mod à 52 caractères ou moins** (ou déjà préfixés `mcp__` par
   nous-mêmes) pour que le préfixage `mcp__llmgw__` de LLMGW s'applique systématiquement et que
   le préfixe de prompt reste stable côté cache amont — un nom plus long échappe au préfixage et
   perd cette garantie de stabilité. À auditer dans le code des outils de `TheMod`.
5. **Ne jamais s'appuyer sur un en-tête `Retry-After` pour le 402 `budget_exceeded`** — LLMGW
   n'en pose pas ; lire `error.reset_at` (RFC3339 UTC) dans le corps JSON et planifier la
   prochaine tentative/heartbeat sur cette base.
6. **Classer les erreurs correctement** : `401`/`400`/`413` et `402` → jamais de retry immédiat
   (bug de config ou budget épuisé) ; `503` → retry avec backoff (transitoire côté LLMGW) ; codes
   forwardés depuis Anthropic (429/529/5xx) → laisser la politique de retry par défaut du SDK
   Java faire son travail, sans l'empiler excessivement par-dessus les retries déjà faits par
   CLIProxyAPI en amont.
7. **Ne pas ajouter de couple d'en-têtes `X-Project`/`X-Tags`** — ce schéma appartenait à la
   conception V1 abandonnée (`docs/specs/2026-06-18-llmgw-design.md`) ; l'architecture actuelle
   authentifie uniquement par clé projet (`Authorization`/`x-api-key`), sans dimension « tag »
   dans le schéma actuel (`internal/domain/governance/budget_types.go`).
8. **Ne pas tenter d'implémenter une requête d'usage vers LLMGW** — il n'y a aucun endpoint à
   appeler (§4). Si `/narrator usage` doit un jour refléter le grand livre LLMGW plutôt que le
   seul comptage local du mod, c'est une demande d'évolution à porter côté LLMGW, pas un
   changement côté `ClaudeClient`.
9. **Recommandation opérationnelle réaffirmée par le code** : déclarer une **clé API Anthropic**
   comme credential amont dans LLMGW plutôt qu'un compte OAuth d'abonnement. C'est uniquement
   avec une clé API que le chemin « `preserveCallerFingerprint` » de CLIProxyAPI s'applique et
   laisse notre prompt système, nos marqueurs `cache_control` et nos en-têtes `anthropic-beta`
   strictement inchangés ; avec OAuth, le cloaking réécrit le prompt système, pose ses propres
   marqueurs de cache et remplace la liste de betas — un comportement nettement moins
   prévisible, en plus d'être hors du cadre contractuel d'un abonnement Claude personnel
   utilisé pour faire tourner un serveur multijoueur (déjà noté dans
   `docs/ARCHITECTURE.md:387`).
10. **Points à vérifier empiriquement dès qu'un vrai gateway tourne** (non déductibles du seul
    code source) :
    - la forme exacte du JSON produit par `.cacheControl(CacheControlEphemeral.builder()...)`
      du SDK Java (où le marqueur atterrit réellement) ;
    - que `X-Session-ID` produit bien `cache_read_input_tokens > 0` dès le 2ᵉ appel d'une même
      conversation ;
    - le code/corps/en-têtes exacts renvoyés par LLMGW pour un 429/529 Anthropic une fois le
      budget de retry de CLIProxyAPI épuisé, et l'absence ou non d'un timeout de lecture propre
      à CLIProxyAPI plus court que les 600 s du client.

## Pour lancer un test réel

Étapes pour le propriétaire (rien de ceci n'a été exécuté) :

1. **Prérequis** : Docker Desktop/Engine, accès à une instance PostgreSQL existante (créer une
   base dédiée : `CREATE DATABASE llmgw;`), et soit une **clé API Anthropic** (recommandé),
   soit une session key claude.ai d'un compte Max/Pro si l'on veut malgré tout tester le chemin
   OAuth (déconseillé en usage réel, cf. point 9 ci-dessus).
2. Copier le repo LLMGW hors de `.research/` (non versionné, à ne pas utiliser comme dossier
   d'exécution) — ou cloner `github.com/clemsix6/LLMGW` directement.
3. `cp config.example.yaml config.yaml && chmod 600 config.yaml` puis `cp .env.example .env`.
4. Générer le pepper (`openssl rand -base64 48`) → `LLMGW_KEY_PEPPER` dans `.env` ; renseigner
   `LLMGW_POSTGRES_DSN` (utiliser `host.docker.internal` si Postgres tourne sur l'hôte) ;
   laisser `LLMGW_DISCORD_WEBHOOK_URL` vide si l'alerting Discord n'est pas voulu pour ce test.
5. `docker compose up -d` puis `docker compose logs -f llmgw` (les migrations tournent au
   démarrage) ; vérifier `curl -fsS http://127.0.0.1:8088/healthz`.
6. Déclarer le credential Anthropic : `llmgw auth login claude` en suivant le flux clé API
   (recommandé). Éviter le flux OAuth/session key sauf test explicite du chemin cloaking.
7. Créer la clé projet du mod : `llmgw key create narrator2 --name mod-client` → copier la
   clé affichée **une seule fois** dans la variable d'environnement `NARRATOR_LLMGW_KEY` de la
   machine qui exécutera `TheMod`.
8. Poser des garde-fous avant le premier vrai trafic : par exemple
   `llmgw budget set narrator2 --dimension calls --window hour --max 200 --action block`, et
   fixer un effort par défaut si besoin : `llmgw project effort narrator2 medium`.
9. Démarrer `TheMod` (`cd TheMod; .\gradlew.bat runServer` depuis PowerShell — jamais
   `./gradlew`, cf. `CLAUDE.md`) avec `config/narrator2/narrator2.json` →
   `gateway.baseUrl` sur `http://127.0.0.1:8088` (valeur par défaut) et `NARRATOR_LLMGW_KEY`
   positionnée dans l'environnement du process serveur.
10. Vérifier après quelques échanges : `llmgw usage show narrator2 --since 1h --by model` côté
    LLMGW (tokens/coût notional remontés), et côté mod les `CallStats`/`Transcripts` pour
    confirmer que `cacheReadTokens > 0` dès le 2ᵉ appel d'une même conversation (validation de
    l'affinité de session).
11. Ne pas réutiliser cette instance de test pour du trafic de production sans revoir les
    budgets à la hausse et sans décider explicitement de l'alerting Discord.
