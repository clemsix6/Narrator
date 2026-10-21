# Passation M5-FIX-2 : le résidu de hachage profond (relecture M5, constat 1, décision D2)

Lot M5-FIX-2, d'après `reviews/M5_REVIEW.md` (constat 1, D2) et `handoff/M5-18.md`. M5-18 avait fermé le hachage profond
**explicite** d'une structure partagée par un parcours mesuré, `Guarded.deep` (4 M emplacements au plus, et un compteur
courant pour tenir à froid). Le résidu, borné seulement par l'hôte (abandon + fil perdu), est ici fermé au site d'appel
pour tout ce qui s'y prête ; le reste est mesuré et documenté. **En cas de contradiction avec `M5.md`, le code et cette
passation font foi.**

Branche `worktree-agent-a2e23407e95657f4c`, fusionnée avec `main` (M5-22 et CLAUDE-FALLBACK compris).

## Ce qui est fermé

Chaque forme passe par `Guarded.deep` avant l'appel du JDK : une structure partagée lève la `ForgeLimit` de M5-18 (« a
structure too large to hash or compare in one call (over 4000000 nested elements)… »), mesurée de **7 à 16 ms**, **sans
fil perdu**. Le message existe déjà : **rien de neuf à écrire dans `sdk.md`** (M5-FIX-1 le possède).

1. **Constructeurs de copie d'une table de hachage** (bornés au site d'appel) : `new HashSet<>(coll)`,
   `new LinkedHashSet<>(coll)` → `Guarded.hashedElements` ; `new HashMap<>(map)`, `new LinkedHashMap<>(map)` →
   `Guarded.hashedKeys`, insérés devant le constructeur comme la capacité ; et leurs `clone()` (`HashSet`, `HashMap`,
   donc aussi les `Linked…`) → `Guarded.clone`.
2. **Fabriques immuables** : `Set.of` (dix surcharges et varargs), `Set.copyOf`, `Map.of` (dix surcharges),
   `Map.ofEntries`, `Map.copyOf` → `Guarded.setOf`, `setCopyOf`, `mapOf`, `mapOfEntries`, `mapCopyOf`. **Écart utile
   trouvé en route** : quand deux éléments (ou clés) sont égaux, le JDK lève une `IllegalArgumentException` dont le
   message contient **tout le texte** de l'élément. Mesuré : 1 000 textes de 65 000 caractères en double donnent un
   message de 65 millions de caractères, soit 312 Mio alloués en un appel. Pour une valeur composite, `Guarded` refuse
   donc le doublon lui-même, avant le JDK, dans le même ordre et avec la même exception (`duplicate element: …`,
   `duplicate key: …`), mais avec un texte fait par `Texts` : au-delà de 65 536 caractères, c'est la `ForgeLimit` « a
   text over 65536 characters ». Quand aucune valeur n'est composite (`Set.of("a","b")`), le contrôle est sauté et le
   JDK fait tout.
3. **Lectures et retraits à une sonde** (la famille de `contains`/`get` de M5-18, restée ouverte) : `Collection.remove`,
   `Map.remove` (deux formes), `getOrDefault`, `replace` (deux formes), `computeIfPresent`, `containsValue`,
   `List.indexOf`/`lastIndexOf`, `Deque.removeFirstOccurrence`/`removeLastOccurrence`, `Collections.frequency`/
   `disjoint`. Exemple : `map.remove(l1)` hachait L1 (10⁸ emplacements) en un appel, comme `get` avant M5-18.
4. **Beaucoup de petits parcours dans un même appel** : `Arrays.hashCode` (ou `equals`, `Objects.hash`, les éléments
   d'une fabrique) de 65 536 références à une liste de 2 000. Chaque parcours reste sous le pas d'échantillonnage de
   M5-18 (2 048), et aucun code du module ne tourne entre eux : environ 2,6×10⁸ opérations sans aucune lecture du
   compteur, puis abandon par l'hôte. **Correctif** : chaque parcours composite de `deep` se termine par un
   `Meter.sample` (une ligne). L'appel s'arrête à son budget de temps, proprement.

## L'agrégat de records : déjà borné (la relecture le croyait au chemin de l'hôte)

La sonde, lancée sur le code **d'avant** ce lot, montre qu'une liste de 10 000 records enveloppant chacun une liste
(L0 de 10 000 ou de 2 000) est déjà arrêtée par le compteur, à **20,1-20,2 ms**, avec la ligne du module (« time budget
at …java:16 »), sans abandon. La raison : le `hashCode` généré d'un record est une méthode **du module**, réécrite, dont
l'entrée compte un `tick`. Le chiffre de M5-18 (« ~150 ms, abandonné ») date d'avant M5-LOAD et ne tient plus sur
`main`. `RecordAggregateHash` reste dans la batterie comme garde-fou de non-régression.

## Ce qui reste résidu (chemin de l'hôte : abandon, deux fils perdus au plus, puis Forge bloquée)

- **`Stream.distinct()` et les collecteurs qui hachent** (`Collectors.toSet`, `toUnmodifiableSet`, `toMap`,
  `groupingBy`, `toCollection`…). Mesuré : un `distinct` ou un `toSet` de L1 court jusqu'au bout, environ 700 ms, et
  l'hôte l'abandonne à 25,1 ms d'attente du serveur. **Laissés en v1, conformément à D2.** Je les ai fermés à l'essai :
  un `peek` borné devant `distinct`, et un `mapping` ou une clé bornée pour chaque collecteur. Cela marche : arrêt
  propre en environ 7 ms. Je l'ai pourtant retiré, pour deux raisons :
  - c'est le seul chemin restant pour **tester l'abandon de l'hôte** sur une structure partagée (`DistinctShared`,
    `AbandonTest` timers 1 et 2, `SharedStructureAttack` et ses dix minutes de L2, le `hasher` de `ModuleEngineTest`) ;
  - le fermer obligerait à réécrire ces tests, hors de ma liste, sans attaque de remplacement aussi longue.

  Le code de l'essai est prêt à reprendre si le superviseur le décide (voir « Pour la suite »).
- **L'O(n·m) en un appel** : `indexOf` de texte, et le **balayage d'égalité** d'une liste. Exemple : `contains` ou
  `indexOf` d'une sonde de près de 4 M emplacements contre une liste de 100 éléments presque égaux, soit 2,2 s mesurées.
  `deep` borne la sonde, pas `n` comparaisons. C'est inhérent (§3.8.1).
- **Un doublon dans `Collectors.toMap`** (non enveloppé, donc résidu) : son message contient le texte des deux valeurs.
  Mesuré : 130 millions de caractères et 687 Mio alloués en un appel. Le compteur d'allocation le voit **après coup**
  (« allocation budget »), et l'appel n'est pas plus long que le reste du résidu.

## Trouvé hors du hachage (à décider par le superviseur, non traité)

Les **flux paresseux enchaînés** : `Stream.concat` répété dans une boucle du module (chaque tour compte un `tick`)
construit un flux de 400 × 65 536 éléments. Son opération terminale tourne ensuite **sans aucun code du module**.
Mesuré : `toList()` alloue 124 Mio en un appel (100 plages), `IntStream…toArray()` 100 Mio (400 plages), `distinct()`
402 Mio en 270 ms. Ce n'est pas du hachage profond, et le compteur ne le voit qu'au `disarm` : c'est une **allocation
en un seul appel du JDK**, au-delà des 16 Mio par appel, pour le compteur d'allocation et la limite de 256 Mio par
minute. Piste, si l'on veut fermer : borner `Stream.concat`/`IntStream.concat` (taille connue d'un flux `SIZED`, ou un
`peek` qui compte). À décider.

## Mesures (Windows 11, JDK 25, machine au repos ; sonde `M5Fix2ProbeTest`, retirée)

| Forme (L1 = 10 000 × L0 partagé) | Avant (code de `main`) | Après |
|---|---|---|
| copie `HashSet`/`LinkedHashSet`/`HashMap`/`LinkedHashMap` | 147-176 ms en un appel, rien ne l'arrête | `ForgeLimit` en 7-16 ms |
| `Set.of`, `Set.copyOf`, `Map.of`, `Map.ofEntries` | 174-178 ms en un appel | `ForgeLimit` en 7-16 ms |
| `HashSet.clone` (200 clés de 3,9 M) | 1,2 s en un appel | `ForgeLimit` en 7-15 ms |
| `remove`, `getOrDefault`, `computeIfPresent`, `indexOf`, `frequency`, `disjoint` | 174-200 ms en un appel | `ForgeLimit` en 7-16 ms |
| doublon composite dans `Set.of` (texte de 65 M caractères) | 312 Mio alloués en un appel | `ForgeLimit` « a text over 65536 characters » en 0,1 ms |
| `Arrays.hashCode` de 65 536 × une liste de 2 000 | non mesuré avant (raisonné : aucune lecture du compteur) | budget de temps (`GuardedTest`, compteur armé) |
| agrégat de records | **déjà** arrêté à 20,1-20,2 ms | inchangé |
| résidu `distinct`/collecteurs de L1 | ~700 ms, abandonné | inchangé (résidu) |

**Coût d'un fil perdu sur le ramasse-miettes** (la mesure demandée par D2). Dispositif : `SharedStructureAttack`, une
JVM à part qui fait tourner le résidu `Stream.distinct(L2)`, avec le fil perdu à la **priorité normale** (M5-LOAD).
Résultat : `System.gc()` passe de **5-8 ms** au repos à **133-277 ms** par collecte tant que le fil hache. M5-05 avait
mesuré 65-90 ms à la priorité minimale. C'est donc plus long à la priorité normale : le fil n'est plus affamé, et chaque
point d'arrêt attend un pas de hachage plus avancé. Le coût reste borné : deux fils perdus au plus, puis la Forge se
bloque. Il n'arrive qu'avec un `distinct` ou un collecteur appliqué à une structure partagée, et le module est désactivé
au premier essai.

**Surcoût de l'usage normal** (temps CPU par appel, module compilé non réécrit puis réécrit). La ligne témoin, une
boucle sans forme fermée, coûte 2,6 ns de plus par tour : c'est le `tick` du compteur, déjà là avant ce lot. Mon
surcoût propre est cet écart moins 2,6 ns :

| Usage | JDK | réécrit | surcoût propre |
|---|---|---|---|
| `Set.of("a","b")` | 1,2 ns | 6,6 ns | ≈ 3 ns |
| `Map.of` à 2 clés `String` | 9,8 ns | 14,6 ns | ≈ 2 ns |
| `map.getOrDefault`, `list.indexOf`, `set.remove` d'un `String` | 3-12 ns | 6-17 ns | 0 à 3 ns |
| `new HashMap<>(table de 20)` | 176 ns | 198 ns | ≈ 20 ns |
| `Set.copyOf(liste de 20)` | 336 ns | 376 ns | ≈ 38 ns |
| `new HashSet<>(liste de 20)` | 155 ns | 222 ns | ≈ 65 ns |
| `Set.of` de deux petites listes | 3 ns | 93 ns | ≈ 87 ns (deux parcours, deux lectures du compteur, le contrôle de doublon) |

Ce sont quelques dizaines de nanosecondes au pire, pour un budget de 20 ms par appel. Les sept modules de l'arsenal et
`ArsenalScenariosTest` restent verts (aucun faux positif).

## Fichiers

- `forge/rt/Guarded.java` :
  - nouvelles méthodes `hashedElements`, `hashedKeys`, `clone` (×2) ;
  - les lectures et retraits ci-dessus ;
  - `setOf`/`setCopyOf`/`mapOf`/`mapOfEntries`/`mapCopyOf`, avec leurs privés `elements`/`pairs`/`keysAndValues` ;
  - un `Meter.sample` en fin de chaque parcours composite de `deep`.
- `forge/sandbox/Instrumenter.kt` :
  - les règles d'appel de tout ce qui précède ;
  - `ConstructorRule` généralisé (`check`/`checkDescriptor` à la place de `trusted`), qui porte `hashedElements` et
    `hashedKeys` sur l'argument du haut de pile ;
  - `checkCapacity` renommé `checkArgument`.
- `forge/sandbox/SandboxPolicy.kt` : **inchangé**. Les formes fermées sont des membres déjà permis, et
  `SandboxPolicyTest` ne vise que les capacités `int`.
- Batterie, sous `test/resources/forge/evil/` :
  - fermées par la garde (« too large to hash ») : `CopyCtorHash`, `CopyCtorMapHash`, `SetOfHash`, `MapOfHash`,
    `SetCopyOfHash`, `MapRemoveHash` ;
  - arrêtées par le compteur (« time budget ») : `ArraysHashMany`, `RecordAggregateHash` ;
  - le corpus passe à **32 modules**, lus automatiquement par `EvilModulesTest` ;
  - `EvilModulesGameTests.GUARDED` gagne `CopyCtorHash`, `SetOfHash`, `MapOfHash` et `MapRemoveHash`, sur le thread
    serveur, sans fil perdu.
- Tests unitaires de mes fichiers :
  - `GuardedTest` : chaque forme fermée lève sur une structure partagée ; l'usage normal rend le résultat du JDK ; le
    doublon a un texte borné ; et, compteur armé, beaucoup de petits parcours s'arrêtent au budget ;
  - `InstrumenterTest` : chaque forme fermée, compilée par ECJ, passe par son jumeau de `Guarded`, et aucun de ces appels
    ne reste au JDK.
- `docs/design/M5.md` : encart au §3.8 et au §16.2, une ligne du tableau de la batterie, remesure du §3.8.1 bis.

## Coordination : un fichier hors de ma liste

`forge/host/AbandonTest.kt` n'est pas dans ma liste. Fermer les constructeurs de copie cassait pourtant deux de ses
attaques, qui s'en servaient pour **déclencher l'abandon de l'hôte** : c'est la même nécessité que M5-18 avec ses tests.
Les deux appels `new HashSet<>(deep)` (deep = 10 × L1) deviennent `deep.stream().distinct().count()`, sur le résidu :
- dans le test `at the bench the caller may wait for the line of the abandoned call` ;
- dans la source `SHARED` (timer 2), utilisée par `past the lost threads the Forge allows, no module runs`.

Le comportement observé ne change pas : abandon, fil perdu, cause « … and was abandoned in java.util. … at
Search.java: ».

Sont **inchangés** : les autres attaques d'`AbandonTest` (timers 0 et 1), `SharedStructureAttack`, `DistinctShared` et
son GameTest, ainsi que le `hasher` de `ModuleEngineTest` (fichier de M5-FIX-1, non touché).

## Sondes

- **Avant/après, sur toutes les formes** (`M5Fix2ProbeTest`, retiré). Lancée sur le code de `main` **avant** mes
  modifications, puis après, avec deux budgets : 20 s (le coût brut) et 20 ms (le budget du jeu). Avant, chaque forme
  fermée tournait de 150 ms à plus d'une seconde en un appel, sans rien pour l'arrêter. Après, chacune lève la
  `ForgeLimit` de la garde. C'est la preuve de chaque protection : la même attaque et le même harnais, avec et sans elle.
- **Le surcoût** (`M5Fix2CostProbeTest`, retiré) : le tableau ci-dessus.
- **Les sondes par retrait n'ont pas pu être lancées.** Il s'agissait de casser une protection à la fois (vider
  `hashedElements`, `setOf`, une règle d'`Instrumenter`, le `Meter.sample` de fin de parcours) pour voir le test qui
  échoue. La permission « Security Test Removal » du mode automatique l'a refusé, comme pour FLAKY-4 (§4 de sa
  passation). Je ne l'ai pas contourné. Les tests sont écrits pour échouer dans ces cas :
  - `GuardedTest` attend une `ForgeLimit` là où le JDK seul n'en lève aucune ;
  - le test de routage d'`InstrumenterTest` exige chaque appel à `Guarded` et l'absence de tout appel direct au JDK ;
  - le test « many small walks », compteur armé, ne lève que grâce au `Meter.sample` de fin de parcours.

  Pour ce dernier, l'échec sans la protection est **raisonné**, pas exécuté. Si le superviseur accorde la permission,
  les quatre retraits se rejouent en quelques minutes.
- **La batterie unitaire ne distingue pas** un arrêt par la garde d'un dépassement constaté après coup au `disarm` : son
  verdict « limit » accepte « time budget » pour un parcours froid. C'est le **GameTest**
  (`theDeepHashAttacksAreStoppedByTheGuardWithNoLostThread`) qui exige « pas d'abandon » et un tick court : c'est lui qui
  verrait une forme rouverte, en plus des deux tests unitaires de mes fichiers ci-dessus.

## Pour la suite

- **M5-FIX-1 / `sdk.md`** : rien à ajouter. Aucun refus nouveau : le message est celui de M5-18, et un module sain ne le
  rencontre pas.
- **Fermer `distinct` et les collecteurs** (si le superviseur le veut) :
  - `Guarded.distinct(s) = s.peek(borne).distinct()` ;
  - `toSet`/`toCollection` = `Collectors.mapping(borne, …)` ;
  - `toMap`/`groupingBy` = la même fonction de clé, bornée ;
  - `toMap` à deux arguments refait l'accumulateur du JDK avec un message de doublon borné ;
  - un compteur d'éléments qui lit `Meter.sample` tous les 1 024 éléments.

  J'ai validé cet essai : arrêt en environ 7 ms, sans fil perdu. Il faudrait alors une **autre** attaque longue pour les
  tests de l'abandon de l'hôte. Candidat : le balayage O(n·m) d'une liste, déjà mesuré à 2,2 s pour 100 éléments.
- **Les flux enchaînés** (ci-dessus) : une allocation en un appel, hors du hachage.
- **Deux tests instables vus pendant les builds, sans lien avec ce lot** :
  - le GameTest de la parade (`ArsenalCreaturesGameTests.theParadeFollowsItsPlayer…`), constat 5 de la relecture et
    lot de M5-FIX-1 ;
  - `AbandonTest` › *a single long call of the JDK…* : vu rouge une fois sous la charge d'un build complet (l. 57,
    `disabled` nul : l'appel jugé sans faute, sans doute `LATE` ou `starved`) ; vert seul et au build suivant. C'est la
    famille « un appel jamais commencé à temps », non couverte par une sonde selon M5-LOAD.

## Vérifications

Voir le rapport : `git merge main`, puis build complet `--continue` (tests unitaires et GameTests). Aucun appel réel à
Claude ; pas de `runServer`.
