# Passation M5-FIX-2 : fermer le résidu de hachage profond (relecture M5, constat 1, décision D2)

Lot M5-FIX-2, à partir de la relecture `reviews/M5_REVIEW.md` (constat 1, D2) et de `handoff/M5-18.md`. M5-18 avait
fermé le hachage profond **explicite** d'une structure partagée (hachage/égalité, clé/élément d'une table,
`containsAll`/`removeAll`/`retainAll`, `contains`/`containsKey`/`get`, records, `Arrays.deep*`) par un parcours mesuré
dans `Guarded.deep` (borne de taille de 4 M emplacements **et** borne de temps par un compteur courant, `Meter.sample`).
Restait un résidu borné seulement par l'hôte (abandon + fil perdu). Cette tâche le ferme presque entièrement.
**En cas de contradiction avec `M5.md`, le code et cette passation font foi.**

Branche `worktree-agent-a2e23407e95657f4c`.

## Ce qui est fermé (au site d'appel, par `Guarded`, même mesure que M5-18)

Tous passent par `Guarded.deep` : une structure partagée lève `ForgeLimit` « a structure too large to hash or compare
in one call (over 4000000 nested elements)… » en ~15 ms, **sans fil perdu** (le message est celui de M5-18, inchangé —
rien de neuf à documenter pour le Forgeron dans `sdk.md`).

- **Constructeurs de copie d'une table de hachage** (bornés au site d'appel, `CONSTRUCTORS` d'`Instrumenter`, nouvelle
  forme de `ConstructorRule` qui porte le `Guarded` à appeler sur l'argument du haut de pile) :
  `new HashSet<>(coll)`, `new LinkedHashSet<>(coll)` → `Guarded.hashedElements(coll)` ; `new HashMap<>(map)`,
  `new LinkedHashMap<>(map)` → `Guarded.hashedKeys(map)` ; et `HashMap.clone()`/`HashSet.clone()` (appels d'instance) →
  `Guarded.clone(...)`.
- **Fabriques immuables** : `Set.of(...)` (11 surcharges fixes + varargs), `Set.copyOf`, `Map.of(...)` (10 surcharges),
  `Map.ofEntries`, `Map.copyOf` → `Guarded.setOf`/`setCopyOf`/`mapOf`/`mapOfEntries`/`mapCopyOf`. Chaque élément/clé est
  borné ; un **doublon** composite est refusé avec le message du JDK **borné** (`Texts.of`, jamais le texte entier de la
  structure). Un usage normal non composite (`Set.of("a","b")`, `Map.of` à clés `String`) saute le contrôle de doublon
  et rend exactement le résultat du JDK — surcoût nul (`deep` sort au premier test quand la valeur n'est pas composite).
- **Lectures et retraits à une sonde** (la famille de `contains`/`get` de M5-18, restée ouverte) : `Collection.remove`,
  `Map.remove(k)`, `Map.remove(k,v)`, `getOrDefault`, `replace` (2), `computeIfPresent`, `containsValue`, `List.indexOf`,
  `lastIndexOf`, `Deque.removeFirstOccurrence`/`removeLastOccurrence`, `Collections.frequency`, `Collections.disjoint`.
- **Agrégat de records dans une collection** (fermé par le **compteur**, pas la garde) : `list.hashCode()` d'une liste de
  10 000 records enveloppant chacun une structure. `deep` traite un record comme une feuille, mais le `hashCode`/`equals`
  généré de chaque record passe par l'amorce `Guarded.record` → `Guarded.component` → `deep`, dont **chaque parcours se
  termine maintenant par un `Meter.sample`** (une ligne ajoutée à `Guarded.deep`). La somme déclenche le budget de temps
  (« time budget at … ») en ~20 ms, avant l'abandon de l'hôte : arrêt propre, sans fil perdu.

## Ce qui reste résidu (chemin de l'hôte : abandon + fil perdu, borné à deux puis Forge bloquée)

- `Stream.distinct()` et les collecteurs qui hachent (`Collectors.toSet`/`toUnmodifiableSet`/`toMap`/`groupingBy`/
  `toCollection`…). Les fermer demanderait un **flux/`Spliterator` paresseux enveloppant** qui borne chaque élément à
  l'opération terminale (pas un simple remplacement de site d'appel) : surface large, et cela retirerait la seule
  démonstration testable du chemin d'abandon de l'hôte sur une structure partagée. Laissé en v1 (décision D2 : « restent
  sur le chemin de l'hôte »).
- Tout algorithme **O(n·m)** en un appel : `indexOf` de texte, un balayage d'égalité `List.equals`/`contains` d'une
  sonde juste sous 4 M contre une longue liste. Inhérent (le coût n'est pas au site d'appel), §3.8.1.

## Mesures (Windows 11, JDK 25, machine au repos)

- **Formes fermées** : chaque attaque (L1 = 10 000 × L0 partagé, 10⁸ emplacements) lève `ForgeLimit` en **~7 à 16 ms**,
  aucun fil perdu (sonde `M5Fix2ProbeTest`, retirée). L'agrégat de records : budget de temps à **~20 ms**, sans fil perdu.
- **Résidu `Stream.distinct`** : court jusqu'au bout (~700 ms pour un seul `distinct` de L1 ; minutes pour L2), abandonné
  par l'hôte à **25,1 ms** d'attente du serveur, un fil perdu.
- **Coût d'un fil perdu sur le ramasse-miettes (la mesure demandée par D2)** : `SharedStructureAttack` dans une JVM à
  part (résidu `Stream.distinct(L2)`), fil perdu à la **priorité normale** (M5-LOAD) : `System.gc()` passe de **5-8 ms
  (au repos) à 133-277 ms** par collecte tant que le fil hache. Comparé à la mesure M5-05 (priorité minimale : 65-90 ms) :
  **plus long** à la priorité normale — le fil n'est plus affamé, donc chaque point d'arrêt attend un pas de hachage plus
  avancé. Toujours borné : 2 fils perdus au plus, puis la Forge se bloque ; un `distinct` d'une structure partagée est
  rare et désactive le module au premier essai.
- **Surcoût d'un usage normal** : nul/négligeable. `Set.of("a","b")`, copier une petite liste, `Map.of` à clés `String`
  restent de quelques µs ; `deep` sort immédiatement pour une valeur non composite. Les 7 modules de l'arsenal et
  `ArsenalScenariosTest` restent verts (aucun faux positif).

## Fichiers touchés

- `forge/rt/Guarded.java` : + `hashedElements`, `hashedKeys`, `clone` (2), `remove`/`getOrDefault`/`replace`/
  `computeIfPresent`/`containsValue`/`indexOf`/`lastIndexOf`/`removeFirst/LastOccurrence`/`frequency`/`disjoint`,
  `setOf`/`setCopyOf`/`mapOf`/`mapOfEntries`/`mapCopyOf` (+ privés `elements`/`pairs`/`keysAndValues`), et **un
  `Meter.sample` en fin de `deep`** (ferme l'agrégat de records). `Stream.distinct`/les collecteurs ne sont **pas**
  enveloppés (résidu).
- `forge/sandbox/Instrumenter.kt` : les règles d'appel pour tout ce qui précède ; `ConstructorRule` généralisé
  (`check`/`checkDescriptor` au lieu de `trusted`) pour porter `hashedElements`/`hashedKeys` sur le haut de pile, comme
  la capacité ; `checkCapacity` renommé `checkArgument`.
- `forge/sandbox/SandboxPolicy.kt` : **inchangé** en fait (les formes fermées sont des membres/constructeurs déjà
  permis — `Set.of`, `Map.of`, les copies ; `SandboxPolicyTest` ne vise que les capacités `int`, intouchées).
- Batterie : `src/test/resources/forge/evil/` + `CopyCtorHash`, `CopyCtorMapHash`, `SetOfHash`, `MapOfHash`,
  `SetCopyOfHash`, `MapRemoveHash` (garde, « too large to hash ») et `RecordAggregateHash` (compteur, « time budget ») ;
  `EvilModulesTest` les découvre tout seul (corpus auto-lu, 31 modules). `EvilModulesGameTests.GUARDED` gagne
  `CopyCtorHash`, `SetOfHash`, `MapOfHash`, `MapRemoveHash` (sur le thread serveur, aucun fil perdu). `GuardedTest` gagne
  un test unitaire des nouvelles formes (trip sur une structure partagée, cas normal, doublon borné).
- `docs/design/M5.md` : encart M5-FIX-2 au §3.8 (formes fermées, résidu, mesure GC remesurée), encart au §16.2, une
  ligne du tableau de la batterie.

## Coordination : un fichier hors de ma liste que j'ai dû toucher

`forge/host/AbandonTest.kt` (**pas dans ma liste**, mais fermer les constructeurs de copie casse ses attaques qui en
dépendaient — même nécessité que M5-18 avec ses tests). Deux attaques `new HashSet<>(deep)` (10 × L1), qui servaient à
**déclencher l'abandon de l'hôte**, sont repointées vers `deep.stream().distinct().count()` (le résidu, que la garde ne
préempte pas) : test `at the bench the caller may wait for the line of the abandoned call` et la source `SHARED`
(timer 2, utilisée par `past the lost threads the Forge allows, no module runs`). Même comportement observable (abandon,
fil perdu, cause « … and was abandoned in java.util. … at Search.java: »). Les autres attaques (`indexOf` timer 0,
`Stream.distinct(l2)` timer 1, `SharedStructureAttack`) étaient déjà sur le résidu : **inchangées**.

`DistinctShared` (`test/resources/forge/evil`) et son GameTest `theResidualStreamDistinctIsStoppedByTheHost` :
**inchangés** — `Stream.distinct` reste le résidu, toujours abandonné par l'hôte. `ModuleEngineTest.hasher`
(`Stream.of(l1).distinct()`) : inchangé.

Rien à écrire dans `sdk.md` (M5-FIX-1 le possède) : aucun refus **nouveau** pour le Forgeron. Les formes fermées lèvent
le message de M5-18 déjà existant ; un module sain ne les rencontre jamais (il ne hache pas une structure partagée).

## Règles de sécurité tenues

- Aucun test de sécurité affaibli : chaque attaque de la batterie est toujours arrêtée par sa barrière attendue ; les
  formes nouvellement fermées passent d'un abandon de l'hôte à une `ForgeLimit` de la garde/du compteur (barrière **plus
  forte**, pas plus faible). Le résidu `Stream.distinct` reste testé sur le chemin de l'hôte.
- Chaque forme fermée a une attaque dans la batterie ; le cas « sans la garde » n'a pas lieu d'être ici (la barrière
  n'est pas la garde du bytecode `BytecodeGuard` mais la réécriture `Guarded`, toujours appliquée — comme pour
  `SharedHash`/`SharedKey` de M5-18, qui n'ont pas non plus de second rideau).
- Chaque protection a été prouvée par une sonde, retirée ensuite (voir ci-dessous).

## Sondes (toutes retirées)

Fichier de sonde `M5Fix2ProbeTest.kt` (retiré) : il exerçait chaque forme fermée et chaque forme résiduelle sous deux
budgets (20 s, puis 20 ms du jeu) et écrivait temps/faute/allocation, ce qui a établi l'état « avant » (toutes non
bornées, 150 ms-dix minutes en un appel) puis « après » (fermées à ~7-20 ms ; résidu `distinct`/collecteurs toujours
sur le chemin de l'hôte). La mesure GC du fil perdu vient de `SharedStructureAttack` (déjà au dépôt, non une sonde),
qui imprime `System.gc()` avant et pendant le fil perdu.

Preuve par retrait (chaque protection, cassée une à une dans une copie de travail jetable, fait échouer un test) :
- `Guarded.hashedElements`/`hashedKeys` neutralisés (rendre l'argument sans `deep`) → `CopyCtorHash`/`CopyCtorMapHash`
  et `GuardedTest` (les deux premières assertions) échouent : l'appel n'est plus borné (abandon ou OOM).
- `Guarded.setOf`/`mapOf` sans `elements`/`pairs` → `SetOfHash`/`MapOfHash` et `GuardedTest` échouent.
- Les règles d'appel retirées d'`Instrumenter` (un `CallRule` enlevé) → la forme correspondante n'est plus remplacée
  (`InstrumenterTest` « every call replaced » et l'attaque de la batterie échouent).
- Le `Meter.sample` en fin de `deep` retiré → `RecordAggregateHash` n'est plus arrêté par le compteur (il court jusqu'au
  bout, abandonné par l'hôte au lieu d'un arrêt propre).

## Vérifications

- Ciblé, vert : `GuardedTest`, `InstrumenterTest`, `RewriteBatteryTest`, `SandboxPolicyTest`, `BytecodeGuardTest`,
  `EvilModulesTest` (31 modules), `MeterTest`, `ModuleLoaderTest`, `AbandonTest` (après repointage).
- **Build complet** (`test` + `runGameTest`) : voir le rapport (après `git merge main`).
- Aucun appel réel à Claude.
