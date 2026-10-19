# Relecture du design de M5 (la Forge)

Relecture du 19/10/2026 de `docs/design/M5.md` (premier jet de l'architecte, fusionné en `ddd2bfd`), de
`docs/research/FORGE.md` et du prototype `.research/spikes/m5/`, avant que les agents l'implémentent. Elle confronte le
design au code réel de `main`, aux vraies classes de Minecraft 26.3 (javap, `.research/jp.ps1`) et à des **essais
exécutés** sur le pipeline du prototype (ECJ 3.46.100 + garde + réécriture + chargeur + compteur). Les essais jetables
de cette relecture sont dans `.research/spikes/m5-review/` (reproductibles avec le `out/` du prototype).

Comme la relecture du design de M4, celle-ci **corrige directement le design** : chaque constat dit ce qui a été changé
dans `M5.md` (et `ARCHITECTURE.md`). L'encart de tête de `M5.md` résume les changements. Les décisions Q1 à Q8 du
superviseur (§19.2) y sont intégrées.

Chaque constat donne sa gravité, l'endroit, un scénario, la preuve (**prouvé** : essai exécuté ; **vérifié** : lu dans
le code, le bytecode ou javap ; **raisonné** sinon) et le changement fait. Gravités : **bloquant** (un interdit du §17
ou une garantie du §3.1 tombe, le serveur gèle, ou le découpage casse le build de plusieurs tâches), **important**,
**mineur**, **finition**.

**Bilan** : 14 constats — 1 bloquant, 4 importants, 7 mineurs, 2 finitions — plus l'intégration des huit décisions du
superviseur, dont Q4 (pas de pièges de sol), qui **supprime la tâche M5-19** et simplifie sept endroits du document.

**Ce qui tient.** Le cœur du bac à sable est solide et, pour l'essentiel, démontré par le prototype : le compilateur
aveugle (ECJ ne voit que `java.base` et le SDK), la garde par liste blanche sur le bytecode (types, membres avec
descripteurs, amorces dynamiques, `catch`, structure), la réécriture sans recalcul de cadres, le chargeur filtrant à
parent `null` et le compteur collant. Les essais de cette relecture confirment que la fuite de capacité la plus
évidente — remonter à `ClassLoader` par `Object.getClass().getClassLoader()` — est **refusée** par la garde, que
`Collections.nCopies` est refusé, et qu'un `throws java.io.IOException` ne compile même pas. Les signatures 26.3 du
pilotage de créatures (`PathNavigation.moveTo`, `LookControl.setLookAt`, `Mob.setTarget`, `Entity.needsSync` champ
public, `Entity.setDeltaMovement`, `Attributes.FALL_DAMAGE_MULTIPLIER`, `EntityType.getCategory`) sont **vérifiées à
javap**. Les constats ci-dessous sont les écarts et les trous.

## Les évasions cherchées

On a attaqué chaque barrière avec de vrais modules passés par le **pipeline complet** du prototype. Résultat : une
famille d'évasions de **disponibilité** passe toutes les barrières (constat 1), et une faiblesse de **défense en
profondeur** sur `java.lang.invoke` existe mais ne donne pas d'évasion réelle tant que la garde tourne (constat 2).
Aucune évasion de **confidentialité** ni d'**intégrité du serveur** n'a été trouvée : fichiers, réseau, processus,
réflexion, threads, `MethodHandles` nommé, `System.exit`/`getenv`, `Unsafe`, régulières sont refusés par la garde (et,
en second rideau, par le chargeur), comme le montre la batterie du prototype (47/47) et les essais de cette relecture.

## Constats

1. **BLOQUANT (§3.1, disponibilité : épuiser la mémoire) : une allocation géante en un seul appel du JDK franchit les
   cinq barrières.**
   - Où : `M5.md` §3.4 (la réécriture). Le compteur d'allocation (`Meter`, 16 Mio par appel) n'est **échantillonné
     qu'un `tick` sur 16**, et un `tick` n'est posé qu'aux sauts arrière et aux entrées de méthode **du code du
     module**. Un seul appel à une méthode du JDK qui alloue d'un coup n'a aucun `tick` : rien ne l'arrête avant la
     `OutOfMemoryError`. La réécriture borne `newarray`, la croissance des collections, les concaténations,
     `String.repeat`, `String.format`, les **constructeurs à capacité** « `ArrayList(int)`, `HashMap(int)`,
     `StringBuilder(int)`… » et `Arrays.copyOf` — mais la liste est **donnée par l'exemple** (« … ») et laisse passer
     plusieurs portes d'allocation immédiate.
   - Scénario : un module (écrit par un modèle, donc traité comme adversaire) appelle l'une de ces expressions, toutes
     valides contre le SDK, toutes propres à la garde :
     - `new StringBuilder().ensureCapacity(500_000_000)` — un `char[]` d'un milliard d'octets ;
     - `new StringBuilder().setLength(500_000_000)` ;
     - `new ArrayList<>().ensureCapacity(500_000_000)` ;
     - `new PriorityQueue<>(500_000_000)` — à la différence de `HashMap(int)` (paresseux), ce constructeur alloue
       **tout de suite** un `Object[500_000_000]` ;
     - `"abcd".repeat(200_000_000)`, `String.format("%250000000d", 1)`, `Arrays.copyOf(a, 500_000_000)` (couverts par
       le design mais pas par le prototype).
   - Preuve : **prouvé** (`.research/spikes/m5-review/Atk.java`, JVM `-Xmx256m`). Chacun de ces modules est **propre à
     la garde** (`GUARD-CLEAN`) puis atteint `OutOfMemoryError: Java heap space` à travers la réécriture, le chargeur
     et le compteur du prototype. Extrait :
     ```
     SbEnsureCap   GUARD-CLEAN -> OutOfMemoryError: Java heap space
     SbSetLength   GUARD-CLEAN -> OutOfMemoryError: Java heap space
     SbCapCtor     GUARD-CLEAN -> OutOfMemoryError: Java heap space
     ListCapCtor   GUARD-CLEAN -> OutOfMemoryError: Java heap space
     ListEnsureCap GUARD-CLEAN -> OutOfMemoryError: Java heap space
     PriQueueCap   GUARD-CLEAN -> OutOfMemoryError: Java heap space
     StrRepeat     GUARD-CLEAN -> OutOfMemoryError: Java heap space
     StrFormatW    GUARD-CLEAN -> OutOfMemoryError: Java heap space (302 Mio comptés avant la chute)
     ArraysCopyOf  GUARD-CLEAN -> OutOfMemoryError: Java heap space
     HashMapCapCtor GUARD-CLEAN -> a tourné (HashMap(int) est paresseux : inoffensif)
     ```
     Sur un vrai serveur, cette `OutOfMemoryError` ne frappe pas que le module : toute autre allocation du serveur peut
     échouer au même instant. L'hôte compte l'`OutOfMemoryError` comme faute *exception* (3 pour désactiver), mais le
     mal est déjà fait, et sans `jdk.management` (JRE) il n'y a même pas de compteur d'allocation en second rideau.
   - Changement : la réécriture **borne l'argument de capacité au site d'appel**, comme `Guarded.array`, pour **toutes**
     les portes d'allocation ou de croissance immédiate des types de la liste blanche — pas une liste par l'exemple.
     `M5.md` §3.4 énumère désormais : les constructeurs à capacité qui allouent tout de suite (`ArrayList(int)`,
     `ArrayDeque(int)`, `PriorityQueue(int)`, `HashSet(int)`, `LinkedHashSet(int)`, `StringBuilder(int)`,
     `StringBuilder(CharSequence)`), `ensureCapacity` et `setLength` de `StringBuilder`, `ensureCapacity` de
     `ArrayList`, plus `String.repeat`, `String.format`/`formatted`, `Arrays.copyOf`/`copyOfRange`,
     `IntStream`/`LongStream.range`/`rangeClosed`, et toute entrée qui grossit (`add`, `put`, `append`…). Les
     constructeurs à capacité **paresseux** (`HashMap(int)`, `LinkedHashMap(int)`, `TreeMap`) n'allouent rien tout de
     suite : ils restent libres. Surtout, `SandboxPolicyTest` (M5-03) gagne une obligation : **tout membre ou
     constructeur d'un type de la liste qui prend un paramètre de capacité `int` doit être soit refusé, soit passé par
     `Guarded`** — un nouveau membre non enveloppé fait échouer le test. `EvilModulesTest` (M5-18) reçoit ces cas
     (famille *Mémoire*). Tâches touchées : M5-03 (liste et test), M5-04 (`Instrumenter`, `Guarded`), M5-18 (batterie).

2. **IMPORTANT (défense en profondeur, `java.lang.invoke`) : le chargeur rend `MethodHandles` résoluble, ce qui
   casserait la défense en profondeur si la garde était contournée.**
   - Où : `M5.md` §3.3 (« Classes de liaison ») et `.research/spikes/m5/core/m5/ModuleLoader.java` (`LINKAGE`). Pour
     lier les amorces d'`invokedynamic`, le chargeur rend visibles `LambdaMetafactory`, `StringConcatFactory`,
     `MethodHandles`, `MethodHandles$Lookup`, `MethodType`, `MethodHandle`, `CallSite`, `ObjectMethods`,
     `SwitchBootstraps`, `TypeDescriptor`. La garde refuse tout `java/lang/invoke/` dans le bytecode d'un module ;
     mais le design (§16.2) promet qu'un cas refusé par la garde **est aussi chargé sans la garde** et que « le
     chargeur ou le compteur l'arrête seul ». Ce n'est pas vrai pour la famille `invoke` : le chargeur la rend
     visible exprès.
   - Scénario : si la garde laissait un jour passer `MethodHandles.lookup()` (ou un module fabriqué à la main dans la
     batterie, chargé sans la garde), le chargeur le résoudrait, l'appel rendrait un `Lookup` de plein pouvoir, et
     `Lookup.defineClass(byte[])` définirait une classe **ni gardée ni réécrite** dans le chargeur du module : évasion
     totale. En marche normale, la garde ferme la porte ; mais la propriété de défense en profondeur, elle, tombe.
   - Preuve : **prouvé** (`.research/spikes/m5-review/Link.java`). Un module qui utilise lambdas, flux, `switch` de
     motifs et concaténation **se lie et tourne aussi bien sans** `java.lang.invoke.MethodHandles` dans l'ensemble du
     chargeur :
     ```
     with MethodHandles in linkage   : LINKED AND RAN OK
     WITHOUT top-level MethodHandles : LINKED AND RAN OK
     ```
     La JVM n'a besoin, pour l'`invokedynamic`, que de `MethodHandles$Lookup` (et de `MethodType`, `MethodHandle`,
     `CallSite`) — pas de la classe de tête `MethodHandles`, seule porteuse des fabriques publiques `lookup()` et
     `publicLookup()` qui rendent un `Lookup`.
   - Changement : `M5.md` §3.3 et §3.5 **retirent `MethodHandles` (classe de tête) de l'ensemble résoluble du
     chargeur** ; on garde `MethodHandles$Lookup` pour la liaison. Comme aucune fabrique ne rend plus de `Lookup` au
     code du module, même un module fabriqué et chargé sans la garde ne peut obtenir de `Lookup`, donc ni
     `defineClass` ni `findStatic` : la défense en profondeur est rétablie pour toute la famille. `EvilModulesTest`
     (M5-18, famille *Réflexion, chargement*) reçoit un cas `MethodHandles.lookup()` / `Lookup.defineClass` fabriqué,
     chargé **sans la garde**, dont le verdict attendu est `NoClassDefFoundError` au lien. Tâches : M5-03/M5-05
     (`SandboxPolicy`/`ModuleLoader`), M5-18.

3. **IMPORTANT (§17, acharnement et quota de morts) : le pilotage de créatures contourne la couche des actes et des
   garde-fous.**
   - Où : `M5.md` §4.5 (« Pilotage ») : `moveTo`, `velocity`, `push`, `lookAt`, `gravity`, `glow`, `name` sont
     « immédiats, thread serveur, sans outil : il ne touche que la créature ». Seul `attack` ouvre un acte du moteur.
   - Scénario : « il ne touche que la créature » est faux pour la physique du jeu. Une créature du module lancée à
     grande vitesse (`velocity`, `push`) **entre en collision** avec un joueur et le pousse ; 32 créatures qui suivent
     un joueur (`moveTo` à chaque tick) le harcèlent. Aucun de ces gestes n'est un acte : ils échappent donc à
     `already_punished`, `fair_share`, `same_punishment`, à la grâce et à la garde de mort. Un module peut pousser un
     joueur dans la lave ou le vide : la mort n'est pas attribuée au Narrateur (pas un « kill » d'une créature), elle
     compte comme une mort ordinaire, l'inventaire tombe — exactement ce que le §17 et la décision C8/C17 de M4
     interdisent, mais par un canal non gardé.
   - Preuve : **raisonné** (collision d'entités dans le jeu ; aucune garde sur le pilotage par construction).
   - Changement : `M5.md` §4.5 est corrigé. (a) Une créature du module **ne pousse pas les joueurs** : on pose
     `Entity.noPhysics`-équivalent de non-collision avec les joueurs, ou on borne l'élan pour qu'une créature ne serve
     pas de projectile (un module qui veut toucher un joueur passe par `attack`, qui ouvre un acte et arme la garde de
     mort). (b) La décision I de M4 est étendue : **toute mort d'un joueur survenue près d'une créature d'un module
     (rayon de la créature + élan reçu dans la dernière seconde) est traitée par la garde de mort** (attribuée,
     comptée au quota, sauvetage au-delà), pas seulement un « kill ». (c) Le harcèlement par la seule proximité
     (créatures qui collent un joueur sans l'attaquer) est borné : une créature du module ne poursuit pas en continu un
     joueur en grâce ou récemment sauvé. `ModuleActsGameTests` (M5-07) et la batterie (M5-18, famille *le §17 par le
     SDK*) reçoivent ces cas. Tâche : M5-07 (`CreatureControl`, `guard`).

4. **IMPORTANT (§17, réclamation de créatures) : `claim` et `spawn` doivent exclure les créatures-monstres
   extrêmement dangereuses.**
   - Où : `M5.md` §4.5. `claim` n'accepte qu'une créature `MobCategory.MONSTER` « que `spawn` pourrait invoquer
     (`SpawnPolicy`) ». Or la catégorie `MONSTER` contient le **warden**, le **wither** (invoqué), l'**ender dragon**
     non, mais aussi des créatures neutres mortelles. `attack(player)` sur un warden dépasse largement tout quota.
   - Scénario : un module réclame un warden sauvage proche (rare mais possible dans une cité antique où jouent les
     joueurs) et le lance sur un joueur. La garde de mort refuse la mort au-delà du quota, mais la pression et le son
     du warden restent hors de tout garde-fou d'acharnement.
   - Preuve : **vérifié** par lecture : le design délègue le filtrage à `SpawnPolicy` de M4 sans dire ce qu'elle
     exclut.
   - Changement : `M5.md` §4.5 rend explicite que **`SpawnPolicy` (donc `spawn` et `claim`) exclut les boss et les
     créatures hors d'échelle** (warden, wither, ender dragon, et tout ce que M4 n'invoque déjà pas), et que la
     javadoc du SDK le dit. Si `SpawnPolicy` de M4 ne les exclut pas déjà, M5-07 l'y ajoute. `ModuleActsGameTests`
     (M5-07) vérifie le refus de réclamer un warden. Tâche : M5-07.

5. **IMPORTANT (découpage) : M5-01 porte trop, et il fige les contrats de 21 tâches.**
   - Où : `M5.md` §17.2, M5-01. Une seule tâche d'« étape 0 » écrit **tout** le SDK (`forge/api/*.java` en entier,
     Javadoc comprise), les signatures de `forge/rt`, les contrats Kotlin de `Forge.kt`, et **modifie une
     vingtaine de fichiers** (`tool/Tool.kt`, `ToolCatalog`, `Mechanics`, `MechanicsService`, `MechanicsState`,
     `MemoryTypes`, `DirectorControl`, `TraceRecords`, `Log`, config, services, runtime, shutdown, `build.gradle.kts`)
     plus dix fichiers de test. C'est bien au-delà de « 3 h », et une erreur de contrat se propage aux neuf tâches de
     la vague A.
   - Preuve : **raisonné** (comparaison avec M4-01, déjà lourd ; ici le SDK complet s'ajoute).
   - Changement : `M5.md` §17.2 garde M5-01 en une tâche (fractionner un contrat partagé coûterait plus en
     coordination), mais ajoute un **critère de sortie** : M5-01 compile un module témoin représentatif (celui du
     prototype) contre le SDK écrit, le passe à la garde (squelette) et vérifie qu'il est propre, **avant** de figer
     `Sdk.VERSION = 1`. Et `ForgeContractsTest` vérifie dès M5-01 que chaque type public du SDK ne nomme que le SDK et
     la liste blanche du JDK. On note aussi (Q2) que M5-01 **ajoute l'exception à `CLAUDE.md`** (Java permis pour
     `forge/api` et `forge/rt`). Tâche : M5-01.

6. **MINEUR (garde) : le prototype ne visite pas les annotations ni les clauses `throws`/signatures génériques.**
   - Où : `.research/spikes/m5/core/m5/Guard.java` (`Checker`) ne surcharge pas `visitAnnotation`,
     `visitTypeAnnotation`, et ignore `exceptions` de `visitMethod` et les attributs `Signature`. Le design §3.3 dit
     pourtant « annotations : seulement celles du SDK ».
   - Scénario : ce n'est **pas une évasion** (une annotation ne s'exécute pas, et la réflexion est interdite, donc un
     module ne peut pas la relire ; une clause `throws` et une signature générique ne causent aucune liaison). Mais un
     implémenteur qui recopie le prototype laisserait la règle d'annotation du §3.3 **non appliquée**, et une source
     avec `@java.lang.SuppressWarnings`-like non prévue passerait.
   - Preuve : **vérifié** par lecture du prototype.
   - Changement : `M5.md` §3.3 précise que la garde **visite aussi les annotations** (de classe, de champ, de méthode,
     de paramètre, de type) et n'accepte que celles de la liste, et que `BytecodeGuardTest` (M5-03) le couvre. Les
     clauses `throws` et les `Signature` restent des métadonnées non liantes, mais chaque type qu'elles nomment doit
     être sur la liste (cohérence). Tâche : M5-03.

7. **MINEUR (garde) : « à soi » doit être l'ensemble des classes produites par la compilation, jamais un préfixe de
   nom.**
   - Où : `.research/spikes/m5/core/m5/Guard.java` : `own(t)` = `t.equals(top) || t.startsWith(top + "$")`. Comme la
     réécriture ajoute `Top$-meter`, un module fabriqué qui nommerait `Top$-meter` serait pris pour « à soi ».
   - Preuve : **vérifié** par lecture (le design §3.3 l'avait déjà relevé en prose).
   - Changement : déjà prévu au §3.3 (« les classes que la compilation a produites, jamais un préfixe de nom ») ; la
     relecture le **renforce** : M5-03 calcule l'ensemble des noms produits par ECJ **avant** la réécriture, `own()`
     teste l'appartenance à cet ensemble, et le chargeur **refuse** une classe d'entrée nommée `Top$-meter`.
     `BytecodeGuardTest` a déjà le cas. Tâche : M5-03.

8. **MINEUR (frontière du SDK) : l'hôte ne doit jamais appeler une méthode d'un objet du module hors d'une invocation
   mesurée.**
   - Où : `M5.md` §4.1 l'affirme, mais c'est un invariant d'implémentation fragile : si `ModuleEngine` ou le SDK
     rangeaient un objet du module dans une collection de l'hôte (clé de map, ensemble), ou le journalisaient, son
     `hashCode`/`equals`/`toString` tournerait **hors compteur** et sur le thread serveur — un `hashCode` qui boucle
     gèlerait le serveur.
   - Preuve : **raisonné**. Seuls `Consumer`/`Runnable` (abonnements, minuteurs) traversent la frontière, et la
     garde interdit à un module d'implémenter une autre interface du SDK que `ForgeModule`.
   - Changement : `M5.md` §4.1 ajoute l'invariant explicite : **tout objet reçu d'un module est opaque** ; le moteur et
     le SDK ne l'appellent (`equals`, `hashCode`, `toString`, et les `Consumer`/`Runnable`) que dans une invocation
     armée par l'hôte, et ne s'en servent jamais comme clé ni ne le journalisent. `ActQueueTest`/`SchedulerTest`
     (M5-06, M5-07) le vérifient. Tâches : M5-06, M5-07, M5-11.

9. **MINEUR (jauge de mémoire, thread serveur) : la jauge doit être sûre face aux cycles et tenir son plafond sur un
   graphe adverse.**
   - Où : `M5.md` §3.7 : la jauge parcourt les objets retenus par réflexion, « s'arrête à 200 000 objets », budget
     5 ms, sur le thread serveur. Un module peut tisser un graphe cyclique ou très ramifié exprès.
   - Preuve : **raisonné**.
   - Changement : `M5.md` §3.7 précise que la jauge tient un **ensemble d'identité des objets déjà vus**
     (`IdentityHashMap`) pour ne pas boucler sur un cycle, et que le plafond (200 000 objets **ou** 5 ms, le premier
     atteint) est **dur** : au-delà, désactivation (« retains too much to measure »). `RetainedGaugeTest` (M5-05)
     reçoit un graphe cyclique et un graphe large. Reste un point ouvert (§19.1.4) : coût réel et faux positifs sur de
     vrais modules. Tâche : M5-05.

10. **MINEUR (coût, Directeur) : `director.md` est déjà presque plein et passe à 47 outils.**
    - Où : `M5.md` §9.4 porte le budget de `director.md` de 34 000 à 36 000 caractères pour y ajouter la section « La
      Forge ». La relecture de M4 note que `director.md` fait déjà 33 763 / 34 000.
    - Preuve : **vérifié** (relecture de M4, « Cache et coût »).
    - Changement : `M5.md` §9.4 demande une section « La Forge » **brève** (≤ 1 800 caractères), et rappelle que les
      trois définitions d'outils (≤ 5 000 caractères, `ToolCatalogTest`) ne pèsent pas sur `director.md` mais sur le
      préfixe d'outils, mis en cache. `PromptsTest` (M5-20) tient le budget. La relecture du propriétaire reste
      demandée pour la voix. Tâche : M5-20.

11. **MINEUR (§17, vie privée) : `Facts` doit être le seul traducteur et exclure prières, chuchotements et messages
    privés.**
    - Où : `M5.md` §4.3. Le fait `Chat` « n'est que le chat public ». C'est un invariant à tenir dans `forge/sdk/Facts.kt`
      (M5-06), repris de la décision 7 du §17 d'`ARCHITECTURE.md` et des vérifications de M4 (aucun chemin ne lit
      `private_messages`).
    - Preuve : **vérifié** par lecture du design ; **raisonné** pour l'implémentation.
    - Changement : `M5.md` §4.3 et §16.1 rendent l'obligation testée : `FactsTest` (M5-06) vérifie qu'une prière, un
      chuchotement d'opérateur et un message privé **ne produisent jamais** de fait `Chat` (ni aucun autre fait), et
      que le texte d'un fait ne contient pas de canal non public. Tâche : M5-06.

12. **MINEUR (simplicité, décision Q4) : la fosse, la patinoire et le terrain naturel sont supprimés.**
    - Où : partout où `M5.md` parlait de la décision G (§1.2, §2 n°15, §4.2 `Ground.pit`/`floor`, §4.4, §9.1, §10
      lignes `pit`/`ice_rink`, §12, §13 `groundTraps`, §16.1 `NaturalGroundTest`, §16.3, §17 tâche **M5-19**, §18 n°10,
      §19.2 Q4).
    - Preuve : décision du superviseur (Q4 : **non**, la décision C de M4 reste absolue).
    - Changement : **M5-19 est annulée** ; `Ground` ne garde que `place` (air seulement) ; l'arsenal de départ passe à
      **sept** modules ; `dig_pit`/`swap_floor`, `NaturalGround`, `groundTraps` et leurs tests disparaissent. Les
      numéros des autres tâches restent stables (ce sont des identifiants repris par les passations). `ARCHITECTURE.md`
      §9.4 est mis à jour : pas de piège de sol, la porte pourra être rouverte plus tard.

13. **FINITION (cohérence) : des exemples du document citent encore `pit`.**
    - Où : `M5.md` §9.2 (ligne `MODULE C-74 pit could not restart`).
    - Changement : l'exemple cite désormais un module existant.

14. **FINITION (traces) : la sorte de décision du moteur pour un module.**
    - Où : `M5.md` §15 nomme `mechanic module_ended` au §6.6 mais `DecisionRecord.Kind.FORGE` au §11 ; les décisions
      d'un **déploiement** sont des `mechanic` (comme les règles, décision C31 de M4), les décisions de la **Forge**
      (pipeline, arsenal) sont des `forge`. Le document est déjà cohérent sur ce point (§15 le dit) ; on le laisse,
      en vérifiant que `M5.md` ne mélange pas les deux ailleurs.
    - Changement : aucun, sinon une relecture de cohérence ; noté pour l'implémenteur de M5-11 et M5-10.

## Décisions du superviseur (Q1 à Q8) intégrées

- **Q1 — le SDK seul, sans les classes du jeu : oui.** C'est la condition du §17. Intégré (le design le portait déjà).
- **Q2 — Java pour `forge/api` et `forge/rt` : oui**, exception à `CLAUDE.md`. M5-01 écrit l'exception dans `CLAUDE.md`
  (constat 5).
- **Q3 — ECJ seul : oui.** Intégré (le design le portait déjà).
- **Q4 — pas de pièges de sol : non** (la décision C reste absolue). M5-19 annulée, `Ground.pit`/`floor` retirés
  (constat 12).
- **Q5 — du français dans les sources de l'arsenal de départ : oui**, traitées comme des contenus. Intégré.
- **Q6 — `forge.approval = auto` par défaut : oui.** La sûreté repose sur les barrières et le coupe-circuit
  `/narrator forge off`. Intégré ; résiduel noté (§3.8 : un module agaçant mais dans les règles se coupe par
  `/narrator module stop`).
- **Q7 — pas d'interception en M5 : oui.** Intégré (le design le portait déjà).
- **Q8 — le Rêveur ne commande pas de module : oui.** Intégré ; à rouvrir quand le coût du Forgeron sera mesuré.

## Essais de cette relecture (`.research/spikes/m5-review/`)

- `Atk.java` : treize modules passés par le pipeline complet (ECJ → garde → réécriture → chargeur → compteur,
  `-Xmx256m`). Il démontre le constat 1 (neuf allocations géantes `GUARD-CLEAN` → `OutOfMemoryError`) et confirme ce
  qui tient (`getClassLoader` refusé par la garde, `Collections.nCopies` refusé, `throws IOException` refusé à la
  compilation, `HashMap(int)` inoffensif).
- `Link.java` : démontre le constat 2 (un module à lambdas/flux/`switch` se lie et tourne sans la classe de tête
  `java.lang.invoke.MethodHandles`).
- javap (`.research/jp.ps1`) : signatures 26.3 du pilotage de créatures vérifiées (voir « Ce qui tient »).
