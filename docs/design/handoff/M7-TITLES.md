# Passation M7-TITLES : des titres lisibles par construction

Retour du propriétaire : les textes affichés au milieu de l'écran sont souvent coupés ou disparaissent trop vite. Le
correctif est côté serveur et minimal. **En cas de contradiction, le code et cette passation font foi.**

## Ce qui s'affiche au milieu de l'écran

- `show_title` : l'outil du Directeur. Les lois s'en servent aussi (M4 §8.4), comme les modules de la Forge
  (`acts().title`, qui appelle l'outil).
- `actionbar` : l'outil du Directeur et des lois.
- Le titre de début de duel, affiché par le moteur (`Duels.title`).
- Hors du champ : le `hud` des modules (la barre d'action d'un jeu, rafraîchie chaque seconde, 80 caractères, inchangé),
  l'indicateur « Le Narrateur réfléchit… » (23 caractères) et les barres de boss, déjà ajustées par `BarText`.

## Limites (`Speech.TITLE_MAX`, `SUBTITLE_MAX`, `ACTIONBAR_MAX`)

Largeurs vérifiées dans le jar 26.3 : `Hud.extractTitle` dessine le titre à l'échelle ×4 et le sous-titre à ×2.
Les avances de la police par défaut ont été mesurées sur `ascii.png` et `accented.png`. Toutes les lettres font 6 px,
majuscules et lettres accentuées comprises (é, É, à, ç…). Font exception `i` (2), `l` (3), `t` et `I` (4), `f` et
`k` (5), l'espace (4), `«` et `»` (7) et `œ` (10).

Un écran de 1920×1080 à l'échelle automatique 4 fait 480 px d'interface de large :
- titre : 120 px de police, soit 20 lettres bord à bord. **Limite : 18.**
- sous-titre : 240 px, soit 40 lettres. **Limite : 36.**
- barre d'action : 480 px, mais le client ne l'affiche que 3 s (60 ticks codés en dur, dont la dernière seconde en
  fondu), et le serveur n'y peut rien. **Limite : 40**, ce qu'on lit d'un coup d'œil.

La longueur se compte en caractères visibles : texte nettoyé par `Speech.clean`, en points de code. Les codes `§` ne
comptent pas.

**Au-delà, l'outil refuse** (`check`, donc aussi à la création d'une loi et au banc de la Forge) avec par exemple :
`title is 32 characters, but a title shows 18 at most: shorten it to a few words and put the rest in the chat with say`.
Il ne tronque jamais. Ces trois champs n'ont plus de `@MaxLen` : sans cela, la validation générique répondrait avant
`check` avec un message sans consigne. La limite figure dans le `@Doc`.

## Durée (`Speech.titleStay`)

L'apparition dure 10 ticks et la disparition 20. Entre les deux, le titre reste pleinement affiché
**3 s + 1 s par tranche de 15 caractères entamée**, titre et sous-titre comptés ensemble. Au maximum (18 + 36
caractères), cela fait 7 s. L'argument `seconds` (désormais facultatif, de 1 à 10) ne sert qu'à prolonger : on prend le
maximum des deux.

## Pas d'écrasement (`SpeechLimiter.titles` / `engineTitle`)

Le limiteur sait jusqu'à quand chaque joueur a un titre à l'écran.
- Pour le Narrateur : toujours un titre par joueur toutes les 30 s. Le délai ne peut pas être plus court que le temps
  d'affichage, et le Narrateur ne peut pas recouvrir le titre d'un duel. Un joueur qui attend est sauté ou refusé
  comme avant (`… got one 2s ago (next in 4s)`).
- Pour le moteur (`engineTitle`) : le titre de duel ne passe que si l'écran est libre. Sinon, il part dans le chat, en
  privé. Il ne déclenche pas le délai de 30 s du Narrateur.

## Duel

`duel.start` vaut maintenant « En garde ! », sans paramètre. Le nouveau texte `duel.start_subtitle`, « contre
{opponent} », donne le sous-titre. Ces deux textes sont coupés (`ModelText.cut`) si un opérateur les a allongés.
**Une surcharge opérateur de `duel.start` à l'ancien format afficherait « {opponent} » tel quel.**

## Descriptions des outils

`show_title` et `actionbar` disent que le chat (`say`) est la voix normale. Un titre n'y est qu'une ponctuation rare et
très courte (un nom, CHAPITRE II, un mot), jamais une phrase à lire. Ce qui doit être compris va dans le chat.

## Autres changements

- Arsenal `orbiting_chickens` : le sous-titre (58 caractères) devient « Des poules gravitent autour de toi. »
- `sdk.md` et `Acts.java` donnent maintenant 18 et 36 caractères. L'exemple `WordRace` met le nom du gagnant en titre.
- Tests : `SpeechTest` (durées), `SpeechToolsTest` (refus, limite pile, rien n'est compté),
  `SpeechLimiterTest` (moteur et Narrateur ne se recouvrent pas). Les GameTests de duel, de parole, d'assemblage du
  style et de la Forge ont été ajustés.

## Pour la suite

- `director.md` (M7-CLARITY) pousse encore aux titres chargés : à la ligne 92, « `show_title` (pour les grands moments,
  avec sa propre pointe ; la réplique qui l'accompagne ne le redit pas) » met le trait d'esprit dans le titre et le
  retire du chat. Il faudrait plutôt : « un nom, un mot, CHAPITRE II, ce qui doit être compris va dans le chat ». La
  ligne 61 compte aussi le « titre à l'écran » parmi les faveurs.
- Une loi ou un module déjà en place avec un texte trop long sera désormais refusé à chaque déclenchement (`invalid`).
- Le `hud` des modules reste à 80 caractères, soit 480 px dans le pire cas, juste la largeur d'un écran 1080p.
