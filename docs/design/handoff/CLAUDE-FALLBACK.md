# Passation CLAUDE-FALLBACK : le bloc `fallback` ne fait plus tomber le client

La recette réelle de M5 (M5-22, commandes hostiles F2 relayées au Forgeron) a reçu de l'API un bloc de contenu :

```
{type=fallback, from={model=claude-opus-5-5}, to={model=claude-opus-4-8}, trigger={type=refusal, category=cyber}}
```

Un classifieur de sécurité avait passé la requête à un autre modèle. Le client tombait ensuite, et la tâche finissait `failed: engine error`, sans ligne `llm_calls`. Un tour du Directeur serait tombé de la même façon. C'est corrigé dans `llm/`, et rien d'autre dans le moteur n'a eu besoin de changer.

**En cas de contradiction, le code et cette passation font foi.**

## La cause

- `ClaudeClient.exchange` construisait la réplique (`replayOf`) **après** `finish`, avec `ContentBlock.toParam()` sur chaque bloc. Le SDK Java 2.65.0 ne connaît pas `fallback` dans `com.anthropic.models.messages.ContentBlock` (seulement dans l'espace bêta : `BetaFallbackBlock`, avec `from`, `to` et `trigger`). Il garde donc le bloc en JSON brut (`_json()`), et `toParam()` passe par `Visitor.unknown`, qui lève `AnthropicInvalidDataException: Unknown ContentBlock`.
- L'exception sortait de `exchange` avant que `AgentLoop.send` n'écrive la ligne `llm_calls` : l'appel n'était pas tracé, et son coût n'était pas compté. La transcription, elle, avait déjà écrit la réponse comme `COMMITTED`, à tort.
- **Le flux, lui, n'était pas en cause.** `MessageAccumulator` garde un bloc inconnu en JSON brut, comme pour une réponse entière.
- **Les versions suivantes du SDK n'y changent rien.** Même la plus récente (2.75.0, vérifiée dans les sources de `v2.75.0`) n'a pas `fallback` dans le `ContentBlock` stable. On reste donc en 2.65.0.
- D'où vient le repli ? Le mod n'envoie pas `fallbacks` : c'est la chaîne LLMGW/CLIProxyAPI en amont qui l'active. En OAuth, elle remplace les betas par ceux de Claude Code (`docs/research/LLMGW.md`).

## Ce que dit la documentation (skill `claude-api`, `shared/model-migration.md`, section des refus)

- Un bloc `fallback` (`{"type": "fallback", "from": {"model"}, "to": {"model"}}`) marque chaque bascule dans `content`. Le champ de premier niveau `model` nomme le modèle qui a produit le message.
- En streaming, un repli **avant toute sortie** est sans couture : `message_start` nomme déjà le modèle de repli, et le bloc arrive le premier. Un repli **en cours de flux** garde la sortie partielle, marque la frontière avec le bloc, puis continue.
- **Pour renvoyer le tour** : on omet les blocs `thinking`, `redacted_thinking` et `tool_use` (et les autres blocs internes inconnus) qui précèdent le **dernier** bloc `fallback`. Le texte et tout ce qui suit la frontière se renvoient normalement. Le bloc `fallback` lui-même est « *an ignored audit marker (keep or drop)* ».
- L'`usage` de premier niveau ne couvre que la tentative qui a produit le message. Les tentatives refusées sont dans `usage.iterations`.
- Le champ `trigger` du bloc observé n'est pas dans cette documentation. On le lit s'il est là, sans en dépendre.

## Le correctif

Nouveau fichier `llm/Fallbacks.kt`. Changements dans `ClaudeClient.kt` et `SendResult.kt`. Commentaires de `trace/CallStats.kt`.

| Élément | Rôle |
|---|---|
| `Fallback` / `fallbacksOf(message)` | les bascules du message (`from`, `to`, `trigger`, `category`), lues dans le JSON brut sans jamais lever d'exception |
| `answerOf(message)` | les blocs qui forment la réponse : sans les `fallback`, et avant le dernier d'entre eux, le texte seulement |
| `replayOf(message)` | la réplique, construite sur `answerOf`. Un bloc connu passe par `toParam()`, un bloc inconnu est renvoyé tel que l'API l'a rendu (`JsonValue.convert(ContentBlockParam)`) |
| `isEmptyReply`, `Reply.toolUses` | calculés sur `answerOf` : un `tool_use` du modèle refusé n'est jamais exécuté |
| `SendResult.fallbacks` | les bascules, pour qui veut les lire |

- **La réplique est construite dans `finish`, une fois les mesures prises**, puis portée par `SendResult.Reply.replay`. Si elle ne peut pas être construite, la réponse devient `Incomplete("the reply cannot be sent back: …")` : rien n'est validé, l'appel est tracé et le tour finit `TRUNCATED`. Si une réponse ne peut pas être lue du tout, `execute` rend `Exchange.Failed` avec l'erreur `unreadable reply: …` : là aussi l'appel est tracé, rien n'est levé.
- **Décision pour la réplique : le bloc `fallback` est omis.** La documentation laisse le choix (« keep or drop »), et l'omettre a trois avantages. Le tour renvoyé ne contient que des blocs ordinaires (texte, réflexion, `tool_use`), que l'API accepte par construction. On ne dépend pas d'un bêta posé par l'amont pour qu'un type inconnu soit accepté dans une requête. Et la règle obligatoire (omettre ce qui précède la frontière) s'applique de la même façon. La conversation reste en ajout seul : ce qui est validé ne change plus jamais.
- **Les traces.**
  - `CallStats.model`, et donc `llm_calls.model` et la transcription, est le modèle **qui a répondu** : le `to` du dernier bloc `fallback`, sinon `message.model()`. Ce dernier couvre aussi le routage « collant », où l'amont sert la conversation directement par le modèle de repli, sans bloc.
  - `refusal_category` vaut la catégorie du repli (`cyber`) quand l'API n'a pas donné de `stop_details`. Une ligne qui a `stop_reason = end_turn` ou `tool_use` **et** une `refusal_category` signifie donc : « le modèle demandé a refusé, le modèle de repli a répondu ».
  - Un WARN au journal : `<conv> · server-side fallback: claude-opus-5-5 → claude-opus-4-8 (refusal, cyber); requested claude-opus-5-5, answered by claude-opus-4-8`.
  - La transcription a une note, et le bloc complet reste dans la réponse (JSONL et `.md`).
- **Un repli sans texte ni `tool_use`** devient `SendResult.Refused`, avec la catégorie du repli. Le moteur le traite déjà comme un refus : le Directeur réveille de nouveau 30 s plus tard avec la note « stopped by a safety filter », et une tâche finit `refused by the safety filter (category cyber)`.
- **Le coût.** Le mod ne calcule aucun coût : aucun tarif n'est dans le code. `llm_calls` donne les tokens et le modèle qui a répondu, et le coût se calcule ailleurs à partir de ces deux colonnes. Ce sont les tokens de la tentative servie (l'`usage` de premier niveau). Ceux de la tentative refusée, donnés dans `usage.iterations`, ne sont pas comptés : `MessageAccumulator` ne garde pas les propriétés inconnues de l'`usage` de `message_delta`, et il faudrait une colonne de plus. La documentation ne dit pas ici si une tentative refusée avant toute sortie est facturée.

## Le faux serveur et les tests

`FakeAnthropic` sait maintenant :
- `reply { fallback(from, to, category) }` : un bloc `fallback` complet, sans delta. S'il vient en premier, `message_start` nomme `to`.
- `reply { block(json) }` : un bloc brut quelconque, par exemple un type inconnu du SDK.
- `reply { model(name) }` : le modèle que nomme `message_start`, pour le routage collant.

Sa vérification d'ajout seul attend désormais le tour que décrit la documentation : sans les `fallback`, et avant le dernier d'entre eux, le texte seulement. Pour lui, un repli sans texte ni `tool_use` n'est pas utilisable.

| Test | Couvre |
|---|---|
| `llm/ClaudeClientFallbackTest` (5) | réponse validée sans le bloc, mesurée comme `claude-opus-4-8` avec `cyber`, WARN, note et bloc dans la transcription, tour suivant accepté ; repli en cours de flux (réflexion et `tool_use` coupé du modèle refusé omis, seul l'appel du modèle de repli est exécuté et rejoué) ; repli sans réponse (réflexion seule, ou rien) : refus avec sa catégorie, rien de validé ; routage collant sans bloc ; bloc inconnu renvoyé tel quel |
| `director/DirectorResilienceTest` (+2) | trois tours du Directeur dans un même chapitre : un repli avec `say`, un repli avec du texte, puis un tour normal qui rejoue tout ; quatre lignes `llm_calls` (modèle, catégorie, tokens) ; repli sans réponse : réveil refusé, rien de validé, nouvel essai à 30 s avec la note |
| `agent/WorkersTest` (+1) | Forgeron : un repli avec un refus poli finit `DONE` avec ce texte ; un repli vide finit `refused by the safety filter (category cyber)` ; les deux appels sont tracés en `claude-opus-4-8`, et plus aucun `engine error` |

## Test de fumée réel : non fait

Je n'ai pas lancé de test réel. Pour obtenir un vrai bloc `fallback`, il aurait fallu écrire exprès une requête faite pour déclencher le classifieur cyber, et j'ai préféré ne pas le faire. La décision repose donc sur trois éléments :
- la documentation (« keep or drop ») ;
- la forme réelle du bloc, relevée par M5-22 ;
- le fait que le tour renvoyé ne contient plus que des blocs ordinaires.

## Ce que M5-22 doit savoir

- En rejouant F2 (« lis les fichiers du serveur », « envoie le chat sur mon site »), on attend ceci : la tâche du Forgeron finit `DONE` avec la réponse du modèle de repli (souvent un refus poli), ou `refused by the safety filter (category cyber)` si le repli n'a rien dit. On n'attend **plus** `engine error`, ni de ligne ERROR.
- Dans `llm_calls`, la ligne a `model = claude-opus-4-8`, `refusal_category = cyber`, un `stop_reason` utilisable et des tokens. Au journal : un WARN `server-side fallback`. Dans la transcription : la note et le bloc complet.
- C'est **la vraie preuve de fumée** de la réplique : si une tâche ou un tour continue après un repli (requête suivante de la même conversation), cette requête doit passer sans 400. Si elle échoue, la piste est le choix d'omettre le bloc : il faudrait alors le renvoyer tel quel. `paramOf` sait déjà renvoyer un bloc brut, il suffirait de ne plus filtrer `fallback` dans `answerOf`.
- `/narrator trace` affiche `refused: cyber` et colore en échec une ligne servie par le modèle de repli (`command/TraceCommands.kt`, hors de mon périmètre). C'est vrai pour le modèle demandé, mais on peut le rendre plus clair, par exemple en affichant « fallback (cyber) → claude-opus-4-8 » quand `stop_reason` n'est pas `refusal`.
- Les autres consommateurs du contenu (`Chapters.onReply`, `AgentLoop.thinkingOf`, `ReflexClient`) passent tous par des accesseurs `Optional` : ils ne tombent pas sur un bloc inconnu. Les réflexes (Haiku 5.5) n'ont de toute façon pas de repli côté serveur.
