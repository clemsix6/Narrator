# La Forge — étude de faisabilité (compilation Java à chaud, Minecraft 26.3)

> Recherche pour le jalon **M5** (`docs/ARCHITECTURE.md` §10 et §16). Ce document dérisque le
> design **avant** l'implémentation dans le mod : il s'appuie sur un spike qui **tourne** dans
> `.research/spikes/forge/` (non versionné) et sur des **chiffres mesurés** sur cette machine
> (Windows 11, JDK 25 `C:\Program Files\Java\jdk-25`). Il ne modifie aucun code de `TheMod`.
> Rédigé le 25/09/2026.

## 0. Verdict

**Faisable — feu vert.** Les six questions ont une réponse démontrée par du code exécuté, dont une
**preuve de bout en bout dans un vrai serveur dédié Fabric 26.3 lancé normalement** : le mod du spike
construit son classpath depuis Knot, compile en mémoire une classe qui utilise
`net.minecraft.world.entity.projectile.arrow.*` et `ServerLevel.explode`, la passe au garde-fou
bytecode, la charge dans un classloader enfant et l'active — le tout **sans remapping**.

Chiffres clés (module réaliste = un abonnement à un événement + explosion) :

| Mesure | javac (JDK) | ECJ 3.46.100 (JRE) |
|---|---|---|
| Compilation à froid (1re du process) | ~330–450 ms | ~220 ms |
| Compilation à chaud (médiane, 30 tirages) | **~24 ms** | **~9 ms** |
| Initialisation du compilateur | ~70 ms | ~120 ms |
| Dépendance à embarquer | aucune (fournie par le JDK) | **3,23 Mio** (jar-in-jar) |

- Index de l'API du jeu (ASM, jar serveur) : construit en **~0,5–0,8 s**, **~21 Mio** de tas,
  recherche par mot-clé en **~10–30 ms**, résultat compact (**~300–900 tokens**).
- Déchargement **prouvé** : `WeakReference` + `System.gc()`, classes retirées en **1 GC** ; les fuites
  possibles sont identifiées et neutralisées par construction (voir §4).
- Garde-fou bytecode : un module malveillant de test produit **39 violations** signalées, analyse en
  **~0,5 ms** par module.

Recommandation classpath (§3) : **lire les octets des classes via le classloader de Knot**
(`getResourceAsStream`) et **lister les paquets depuis l'`URLClassLoader` parent de Knot**
(`getURLs()`). C'est la seule source complète : `getClassPath()` seul est **incomplet en production**
(reproduit : 10 102 classes au lieu de 33 006, deux erreurs de compilation).

---

## 1. `javax.tools` en mémoire

### Ce qui a été fait
`JavacCompiler` (`.research/spikes/forge/src/io/github/clemsix6/narrator2/forge/core/JavacCompiler.java`)
compile une source Java 25 **entièrement en mémoire** :

- **Sources** : `SimpleJavaFileObject` sur une `String`, pas de fichier temporaire.
- **Sortie** : `.class` capturés dans des `ByteArrayOutputStream` (jamais écrits sur disque).
- **Classpath** : un `ForwardingJavaFileManager` sert les classes du jeu depuis un `ClassPathIndex`
  (index de paquets → classes, construit une fois). `list()` renvoie les classes du paquet demandé,
  `inferBinaryName` leur nom binaire ; `StandardLocation.CLASS_PATH` et `SOURCE_PATH` sont vidés pour
  que javac **n'aille jamais** lire `java.class.path` ni chercher des sources.
- **Options** : `--release 25 -proc:none -implicit:none -g -Xmaxerrs 20`. Les classes du JDK viennent
  de `ct.sym` du runtime (rien à fournir).

La source de test est réaliste et vérifiée à `javap` : elle fait exploser le point d'impact d'une
flèche de squelette.

```java
ctx.on(ForgeEvent.ProjectileHit.class, e -> {
  if (!(e.projectile() instanceof AbstractArrow arrow)) return;
  var owner = arrow.getOwner();
  if (owner == null || owner.getType() != EntityTypes.SKELETON) return;
  Vec3 at = e.hit().getLocation();
  e.level().explode(arrow, at.x, at.y, at.z, power, Level.ExplosionInteraction.NONE);
  e.level().sendParticles(ParticleTypes.FIREWORK, at.x, at.y, at.z, 20, 0.3, 0.3, 0.3, 0.05);
  arrow.discard();
});
```

### Signatures 26.3 vérifiées (javap, jar mergé)
- `net.minecraft.world.level.Level.explode(Entity, double, double, double, float, Level$ExplosionInteraction)`
  et sa surcharge `(…, float, boolean, …)` ; `ExplosionInteraction` = enum `NONE/BLOCK/MOB/TNT/TRIGGER`.
- `AbstractArrow.getOwner()` hérité de `Projectile` → `Entity` ; `Projectile.onHit(HitResult)` (protégé,
  d'où le mixin du §3) ; `HitResult.getLocation()` → `Vec3` ; `EntityHitResult.getEntity()`,
  `BlockHitResult.getBlockPos()`.
- **Constantes d'entités** : `net.minecraft.world.entity.EntityTypes.SKELETON/COW/ARROW` (attention :
  `EntityType` est la **classe**, `EntityTypes` le **registre de constantes** — piège fréquent, géré
  par les indices du §6).
- `Level.isClientSide()`, `Entity.level()`, `Entity.getServer()`, `ServerLevel.sendParticles(...)`.

### Diagnostics formatés pour le LLM
`DiagnosticFormatter` transforme les `Diagnostic` en un texte dense : **fichier:ligne:colonne**,
message, **extrait de la ligne fautive avec un soulignement `^`**, dédoublonnage (une même erreur de
symbole listée une fois avec ses autres positions), et surtout des **indices tirés du jeu réel**
(§6) : renommages 26.x connus, vrais membres portant ce nom, surcharges de la méthode appelée. Exemple
réel produit par le spike sur un module « écrit de mémoire » :

```
[4] error ExplosiveArrows.java:19:57: cannot find symbol
    symbol:   variable SKELETON
    location: class net.minecraft.world.entity.EntityType
  19 |   if (arrow.getOwner().getType() != EntityType.SKELETON) return;
     |                                     ^^^^^^^^^^^^^^^^^^^
  hint: net.minecraft.world.entity.EntityTypes: static final EntityType<Skeleton> SKELETON
[3] error ExplosiveArrows.java:21:62: incompatible types: boolean cannot be converted to Level.ExplosionInteraction
  hint: net.minecraft.world.level.Level: void explode(Entity, double, double, double, float, Level.ExplosionInteraction)
  hint: net.minecraft.world.level.Level: void explode(Entity, double, double, double, float, boolean, Level.ExplosionInteraction)
```

### Chiffres (javac 25.0.1, classpath production, 86 racines, 34 182 classes indexées)
- Index du classpath : **~60 ms** (une fois, partagé par toutes les compilations).
- `ToolProvider.getSystemJavaCompiler()` + 1er `FileManager` : **~70 ms**.
- **1re compilation** (JIT froid, chargement de javac) : **~330–450 ms**.
- **À chaud** : min 16 / **médiane 24** / p90 44 ms. Cache d'octets des `.class` (option
  `cacheBytes`) : **1,66 Mio**, gagne ~20 % (médiane ~22 ms).
- Depuis le démarrage de la JVM jusqu'au premier `.class` produit : **~650 ms**.

**Conclusion Q1 : oui.** L'objectif « ~50 ms à chaud » du §3 est tenu avec marge (~24 ms). L'API
`javax.tools` est standard et se prête bien au tout-en-mémoire.

---

## 2. ECJ (serveurs sur un JRE sans javac)

### Version
Dernière version publiée sur Maven Central au 25/09/2026 : **`org.eclipse.jdt:ecj:3.46.100`**
(`repo1.maven.org`, sha1 `a06c482…`, **3,23 Mio**, `Main-Class` = `org.eclipse.jdt.internal.compiler.batch.Main`).
Le support de **Java 25** (compliance `-25`) est apparu en **3.44.0** (les 3.42/3.43 plafonnent à
`-24`) ; 3.46.100 gère même `-26`. Le jar est téléchargé dans `.research/spikes/forge/lib/`.

### Ce qui a été fait
Deux voies, toutes deux mesurées :

1. **API native** (`EcjCompiler.java`, retenue) : `org.eclipse.jdt.internal.compiler.Compiler` avec un
   `INameEnvironment` maison. Les classes du jeu viennent du `ClassPathIndex`, les classes du **JDK de
   `jrt:/`** (présent aussi sur un JRE, donc **pas besoin de `ct.sym`**). Piège rencontré et corrigé :
   une unité de compilation doit renvoyer `env.UnNamedModule` depuis `module(...)`, sinon ECJ lève une
   `NullPointerException` dans `moduleNotFound`.
2. **JSR-199** (`EcjJsr199.java`) : `new EclipseCompiler()` via `javax.tools`, classpath en fichiers.
   Fonctionne aussi (à froid ~600 ms, à chaud ~60–90 ms) mais oblige à donner les jars par chemin ;
   l'API native permet de servir les octets directement et est plus rapide.

**Preuve JRE** : sous `java --limit-modules java.base,java.logging,java.xml,java.management,jdk.zipfs`
(runtime sans `jdk.compiler`), `ForgeCompiler.best()` bascule automatiquement sur ECJ et compile les
trois modules d'exemple. La détection est simple et sûre :
`ModuleLayer.boot().findModule("jdk.compiler").isPresent()`.

### Chiffres (mêmes 34 182 classes)
- Init du compilateur : **~120 ms**.
- **1re compilation** : **~220 ms** (plus rapide que javac à froid).
- **À chaud** : min 6 / **médiane 9** / p90 15 ms — **plus rapide que javac** (ECJ lit le JDK depuis
  `jrt:/` sans les vérifications supplémentaires de `--release`).
- Qualité des diagnostics : très bonne, positions ligne/colonne/offset exactes. Les messages diffèrent
  de javac (« cannot be resolved to a type », « The method … is not applicable ») ; le
  `DiagnosticFormatter` gère les deux dialectes et rattache les mêmes indices.

**Conclusion Q2 : oui.** ECJ 3.46.100 (3,23 Mio) est un remplaçant complet de javac pour un serveur
sur JRE, et se révèle même plus rapide à chaud. À embarquer en jar-in-jar, chargé **uniquement** si
`jdk.compiler` est absent (sinon on ne le touche pas, évitant tout conflit de classes).

---

## 3. Le classpath en production (question la plus délicate)

### Le piège, reproduit
Première tentative « raisonnable » : `FabricLauncherBase.getLauncher().getClassPath()` + les origines
des mods (`ModContainer.getOrigin().getPaths()` et `getRootPaths()`). **Résultat mesuré dans le vrai
serveur : 10 102 classes seulement, et la compilation échoue** avec :

```
class file for com.mojang.datafixers.util.Pair not found
class file for it.unimi.dsi.fastutil.objects.Object2LongMap not found
```

Cause, confirmée en lisant Fabric Loader 0.19.5 : `Knot.getClassPath()` ne renvoie que ce qui était
sur `java.class.path` au démarrage. Les **bibliothèques du jeu** (DataFixerUpper, fastutil, Brigadier,
Netty, Guava…) sont découvertes par le `MinecraftGameProvider` (`BundlerProcessor` lance le bundler
Mojang et récupère les URLs) puis **ajoutées via `launcher.addToClassPath()`** dans le classloader de
Knot — elles ne réapparaissent **ni** dans `getClassPath()`, **ni** dans les origines des mods.

### La méthode recommandée (démontrée)
Deux rôles distincts, deux bonnes sources :

1. **Lire les octets des classes → le classloader de Knot.**
   `FabricLauncherBase.getLauncher().getTargetClassLoader()` puis `getResourceAsStream("pkg/Cls.class")`.
   C'est **exactement ce que le jeu exécute** : après mixins et access-wideners, quelle que soit
   l'origine (jar, jar-in-jar extrait, dossier de dev). Le `JavaFileManager`/`INameEnvironment` du
   spike lit par cette voie (`ClassPathIndex.readFrom(loader)`).

2. **Lister les paquets → l'`URLClassLoader` parent de Knot.**
   `KnotClassLoader` délègue à un `DynamicURLClassLoader` (un `URLClassLoader`) accessible via
   `getParent()`. Son **`getURLs()` contient tout**, bibliothèques du jeu comprises, car
   `addToClassPath` fait `urlLoader.addURL(...)`. On en tire la liste des jars/dossiers pour énumérer
   les paquets (ce que le compilateur exige pour les imports `.*` et pour résoudre les types nommés).

`ForgeClasspath.fromKnot()` combine les deux : URLs du parc de Knot (source primaire) + `getClassPath()`
+ racines/origines des mods (ceinture et bretelles), puis dédoublonne, et lit via le classloader.

**Résultat mesuré dans le vrai serveur** : `125 roots (80 from Knot's URLClassLoader, 45 extra mod
paths), 1022 packages, 33006 classes, indexed in 89 ms` → **compilation OK en 265 ms, garde-fou 0
violation, module chargé en 272 ms**. Le jeu, Fabric API (jar-in-jar extraits dans
`.fabric/processedMods/`), notre mod et le JDK (`jrt:/`) sont tous couverts.

### Détails Fabric Loader 0.19.5 vérifiés (code lu)
- `FabricLauncherBase.getLauncher()` → `Knot` ; `Knot.getClassPath()` = liste figée depuis
  `java.class.path` au `init()`.
- `MinecraftGameProvider.unlockClassPath()` ajoute `gameJars` + `miscGameLibraries` via
  `addToClassPath`. Le jar serveur de prod est au **format bundler Mojang** ; le bundler extrait le
  vrai jar dans `versions/26.3/server-26.3.jar` et les libs dans `libraries/`.
- Jar-in-jar : un mod imbriqué sans chemin est **extrait** par `FabricLoaderImpl` dans
  `<gameDir>/.fabric/processedMods/` (nom `id-version-hash.jar`) puis ajouté au classpath ; il apparaît
  donc dans `getURLs()` et comme origine `PATH`. `ModContainer.getRootPaths()` renvoie la racine d'un
  `ZipFileSystem` pour un jar (parcourable) ou les dossiers `build/classes` en dev.
- **Piège javac** noté au passage : avec le `StandardJavaFileManager`, l'**ordre des jars** peut
  masquer un paquet (le jar serveur bundler, signé par digests, fait échouer la résolution d'un module
  Fabric placé après lui). Notre compilateur en mémoire n'y est pas soumis : il résout par **nom
  binaire** dans l'index (premier gagne), pas par balayage de répertoires — un argument de plus pour la
  voie « lecture via classloader ».

---

## 4. Chargement et déchargement

### Ce qui a été fait
`ModuleHost` donne **un `ModuleClassLoader` par module**, enfant du classloader du mod. Il ne définit
que les classes du module (depuis la mémoire) et délègue le reste au parent. Tout ce qu'un module
enregistre passe par le `ModuleContext` (abonnements au bus, tâches planifiées) et **appartient au
module** ; `unload()` retire les abonnements (`EventBus.unsubscribeAll`), annule les tâches
(`Scheduler.cancelAll`), marque le contexte fermé, puis **lâche** l'instance, le contexte et le
classloader.

### Déchargement prouvé (`UnloadDemo`)
`unload()` renvoie une `WeakReference<ClassLoader>` ; le spike force des `System.gc()` et vérifie que la
référence se vide, en croisant avec `ClassLoadingMXBean.getUnloadedClassCount()`.

| Scénario | Résultat mesuré |
|---|---|
| Module propre (bus + scheduler), 1 charge/décharge | **COLLECTÉ en 1 GC**, 2 classes déchargées |
| 20 cycles charge/décharge | **COLLECTÉ**, 40 classes déchargées, 0 abonnement/tâche résiduels |
| Gestionnaire qui lève une exception (l'hôte ne garde que le **texte**) | **COLLECTÉ** |
| Abonnement à un event façon Fabric (non désinscriptible) | **STILL ALIVE** (fuite attendue) |
| Thread lancé par le module, encore vivant | **STILL ALIVE** |
| `ThreadLocal` posé sur le thread serveur (longue vie) | **STILL ALIVE** |
| Objet du module rangé dans une map globale (JVM) | **STILL ALIVE** |
| `Throwable` du module conservé par le mod (sa backtrace épingle les classes) | **STILL ALIVE**, puis **COLLECTÉ** après l'avoir lâché |

### Ce qui empêche le déchargement, et la parade
- **Events Fabric API** : ne se désinscrivent pas. → Le mod a **son propre bus** (`EventBus`) : il
  enregistre **un** listener par event Fabric au démarrage et republie ; chaque abonnement de module y
  est **possédé et retiré** au déchargement. Les modules ne touchent **jamais** `net.fabricmc.*`
  (interdit par le garde-fou, §5).
- **Threads / exécuteurs / timers** : survivent au module. → **Interdits** ; le seul mécanisme
  asynchrone offert est `ctx.runLater(ticks, …)` (sur le thread serveur, annulé au déchargement).
- **`ThreadLocal`** sur le thread serveur : épingle une valeur du module. → **Interdit** (garder l'état
  dans des champs).
- **Caches du jeu / registres statiques / entités persistées** : une entité posée par le module et
  sauvegardée survit. → Les modules **ne peuvent pas sous-classer** `Entity`, `Block`, `Item`,
  `BlockEntity` (interdit) ; tout ce qui est marqué (buts d'IA, modificateurs d'attributs, entités
  taguées) est enregistré via le SDK et retiré au déchargement.
- **`Throwable` conservé** : une exception issue du code du module référence ses classes via la
  backtrace. → L'hôte **ne conserve jamais** de `Throwable` d'un module : `EventBus`/`Scheduler`
  capturent l'erreur et n'en gardent que le **texte** (`describe(t)`), ce qui suffit au Forgeron pour
  corriger. Mesuré : COLLECTÉ.

**Règle pour le bus d'événements** (confirmée) : abonnement **par module** via `ctx.on(...)`,
désabonnement **en masse** par identifiant de module au déchargement, dispatch **sur le thread
serveur**, chaque gestionnaire **chronométré et borné** (§5). C'est la condition pour un déchargement
garanti.

---

## 5. Garde-fous (plus de SecurityManager en Java 25)

### Ce qui a été fait
`BytecodeGuard` (ASM) analyse le **bytecode compilé** du module et refuse les références interdites,
**avant** tout chargement. Vérifications :

- **Tout type référencé** doit être sur la liste blanche (sinon rejet avec motif lisible).
- **Chaque membre appelé** est vérifié via son descripteur : une classe autorisée ne peut pas livrer un
  type interdit (ex. `MinecraftServer.getServerDirectory()` renvoie un `java.nio.file.Path` → rejeté).
- Cas spéciaux : `System` (seuls `nanoTime/currentTimeMillis/arraycopy…`), `Class` (pas de réflexion),
  `invokedynamic` (seuls les bootstraps lambda/`StringConcat`/records/switch), constantes `Handle`/
  `ConstantDynamic`, `catch` trop large (interdit d'avaler `Error`/`Throwable`), `finalize`, méthodes
  `native`, sous-classement d'entités/blocs, classe hors du paquet du module.

Test de non-régression : `Evil.java` (fichiers, `System.getenv`, threads, `Runtime.exec`,
`ProcessBuilder`, réseau, réflexion, `MethodHandles`, `CompletableFuture`, `parallelStream`,
`ThreadLocal`, `System.exit`, `getCommands`, `halt`, sous-classe de `Zombie`…) → **39 violations
signalées**, analyse en **~0,5 ms**. Les trois modules légitimes → **0 violation**. Analyse par
module : **~0,15–0,7 ms**.

### Le SecurityManager n'existe plus : le budget d'exécution
L'analyse statique ne stoppe pas une **boucle infinie**. `BytecodeGuard.instrument()` (ASM) insère un
appel `Budget.tick()` avant chaque **saut arrière** et à chaque **entrée de méthode** ; passé le délai
(20 ms par gestionnaire), `tick()` lève une `Error` que `catch (Exception)` ne peut pas avaler.
Mesuré : un gestionnaire `while(true)` est stoppé **en ~20 ms**, une récursion infinie tombe en
`StackOverflowError` **en ~3 ms** ; dans les deux cas le module est **désactivé et déchargé**
automatiquement. Surcoût sur le bytecode : **+~4 %** (2 219 → 2 306 octets sur l'exemple).

### Listes recommandées
**Interdits** (motif renvoyé au modèle) : `java.io`/`java.nio` (fichiers, sauf `Serializable`),
`java.net`/`javax.net`/`io.netty` (réseau), `Runtime`/`Process`/`ProcessBuilder`/`ProcessHandle`,
`java.lang.reflect`, `java.lang.invoke` (`MethodHandles`), `java.lang.foreign` (FFM/`Unsafe` moderne),
`java.lang.classfile`/`instrument`/`management`/`module`/`StackWalker`, `ClassLoader`, `Thread`/
`ThreadGroup`/`ThreadLocal`/timers, `java.util.concurrent` (sauf `atomic`, `ConcurrentHashMap`,
`ConcurrentLinkedQueue`, `ThreadLocalRandom`, `TimeUnit`), `parallelStream`/`parallel*`,
`ServiceLoader`, `Properties`/`Scanner`/`prefs`, `System.exit`/`getenv`/`getProperties`/`out`/`err`,
`Cleaner`/`PhantomReference`, `finalize`, `wait`. Côté jeu :
`net.minecraft.server.dedicated`/`rcon`/`players` (bans, ops, whitelist), `commands`/`Commands`
(passer par le SDK validé), `network`/`Connection`, `LevelStorageSource`, `FileUtil`/`FileZipper`,
`Util.shutdownExecutors`/`getPlatform`, `MinecraftServer.halt`/`stopServer`/`close`/`save*`, et
`net.fabricmc.*` (events non désinscriptibles).

**Liste blanche** : `java.lang`/`java.util`/`java.time`/`java.text`/`java.math` (moins les interdits),
`net.minecraft.*` (moins la liste ci-dessus), `com.mojang.serialization`,
`com.mojang.datafixers.util`, `it.unimi.dsi.fastutil`, `com.google.common.{collect,base}`, `org.joml`,
et le SDK `io.github.clemsix6.narrator2.forge.api`.

**ASM est déjà présent en production** (dépendance de Fabric Loader : `asm 9.10.1` dans `libraries/` et
sur le classpath de Knot) — **rien à embarquer** pour le garde-fou et l'introspection.

---

## 6. Introspection de l'API pour le modèle

### Ce qui a été fait
`ApiIndex` (ASM) indexe le jar du jeu : classes publiques/protégées, méthodes et champs avec
**signatures génériques lisibles** (`SignatureVisitor` → syntaxe Java, noms simples), **noms de
paramètres** (Mojang livre les tables de variables locales depuis 26.1), un **compteur d'usage** (sites
d'appel dans le code du jeu) pour le classement, et le **filtre du garde-fou** (une API que le module
ne pourrait pas utiliser n'est pas montrée : 2 448 membres masqués). Deux sorties : `api_search`
(recherche par mots-clés) et `api_class` (une classe + ce qu'elle hérite).

Le classement combine : correspondance sur le nom du membre (fort), de la classe et du paquet,
`log(usage)`, pénalités (protégé, constructeur non demandé, champ d'instance, `Codec`, paquets bruyants
comme `network.protocol`/`advancements`/`datafix`), bonus aux classes centrales (`Entity`, `Level`,
`ServerLevel`). Les surcharges sont regroupées, les overrides masqués au profit de la déclaration la
plus haute.

### Chiffres (jar serveur 26.3)
- Construction de l'index : **~0,5–0,8 s** (première fois ; ~0,45 s ensuite, JIT chaud).
- Contenu : **5 872 classes**, **59 371 membres** visibles.
- Empreinte mémoire : **~21 Mio** de tas par index.
- Recherche : **~10–30 ms** par requête.
- Dump texte complet (référence) : 5,9 Mio brut, **606 Kio gzip** — assez petit pour être conservé.

### Format renvoyé au modèle (compact et utile)
Groupé par classe, en-tête `kind FQCN extends/implements`, un membre par ligne en syntaxe Java avec
noms de paramètres, puis une **légende `types:`** (nom simple → FQCN) pour les imports. Taille typique
d'une recherche : **~300–900 tokens**. Exemple réel :

```
api_search "explode": 12 results
abstract class net.minecraft.world.level.Level implements LevelAccessor, AutoCloseable
  void explode(Entity source, double x, double y, double z, float r, Level.ExplosionInteraction blockInteraction)
  void explode(Entity source, double x, double y, double z, float r, boolean fire, Level.ExplosionInteraction blockInteraction)
  ...
class net.minecraft.world.level.ServerExplosion implements Explosion
  int explode()
types: Entity=net.minecraft.world.entity.Entity, Level.ExplosionInteraction=net.minecraft.world.level.Level.ExplosionInteraction, ...
```

Recherches vérifiées : « arrow hit », « explode », « teleport », « levitation », « spawn entity »,
« send particles », « lightning », « set block », « add effect » renvoient toutes la bonne API en tête.

**Conclusion Q6 : oui.** Construit une fois au démarrage (ou paresseusement), l'index sert `api_search`
et `api_class` du Forgeron (§10.2 point 4) pour un coût mémoire et token faible.

---

## 7. Design recommandé pour M5

### API des modules (SDK `forge.api`)
- `interface ForgeModule { void enable(ModuleContext ctx); }` — instancié par constructeur public sans
  argument.
- `ModuleContext` : `id()`, `server()`, `on(Class<E extends ForgeEvent>, Consumer)`,
  `runLater(ticks, Runnable)`, `params()`, `log(String)`. Tout est possédé par le module.
- `sealed interface ForgeEvent` avec des records (`ProjectileHit`, `BlockBroken`, `EntityHurt`, …) —
  alimenté par Fabric API et des **mixins** (ex. `Projectile.onHit` est protégé, d'où un mixin qui
  publie sur le bus ; démontré dans le spike).
- Les modules ne voient **que** `forge.api` ; l'accès aux classes du jeu est direct (mêmes noms Mojang).

### Cycle de vie d'un module
`compiler (javac|ECJ) → CompileResult` → `BytecodeGuard.check` (rejet avec diagnostics si violation) →
`BytecodeGuard.instrument` (budget) → `ModuleClassLoader` (enfant) → `enable(ctx)` (borné) → déployé
(durée, cibles) → `unload` (désabonnements, tâches annulées, refs lâchées) → GC. Un module qui plante
ou dépasse son budget est **désactivé et déchargé** automatiquement, son erreur (texte) remontée au
Forgeron.

### Dépendances à embarquer et tailles
| Dépendance | Rôle | Embarquer ? | Taille |
|---|---|---|---|
| `javax.tools` (javac) | compilation sur JDK | non (fourni par le JDK) | 0 |
| `org.eclipse.jdt:ecj:3.46.100` | compilation sur JRE | **oui (jar-in-jar)**, chargé si `jdk.compiler` absent | **3,23 Mio** |
| ASM 9.10.1 (`asm`, `asm-tree`, …) | garde-fou + introspection | **non** (fourni par Fabric Loader) | 0 |

### Format des diagnostics
Voir §1 : positions exactes, extrait souligné, dédoublonnage, indices tirés du jeu en cours (renommages
26.x, membres homonymes, surcharges). javac et ECJ passent par le **même** `DiagnosticFormatter`.

---

## 8. Risques restants

1. **Complétude de la liste blanche.** L'analyse bytecode est robuste (types **et** descripteurs de
   membres), mais la liste des interdits est à maintenir : nouvelles API JDK (ex. évolutions de
   `java.lang.foreign`), et surtout **méthodes du jeu à effet de bord dangereux** non couvertes par un
   paquet entier. Parade : liste blanche stricte par défaut (tout paquet inconnu est refusé), revue à
   chaque montée de version, et garde-fous **moteur** indépendants (§6.3) en seconde barrière.
2. **Fuites subtiles de déchargement.** Le déchargement est garanti *si* le module reste dans le SDK.
   Un module qui réussirait à faire fuiter une référence via une API du jeu autorisée (une collection
   statique du jeu, un objet passé à un registre) empêcherait le GC. Parade : interdire le
   sous-classement des types persistés (fait), auditer les points d'entrée du SDK qui exposent des
   collections mutables du jeu, et surveiller `getUnloadedClassCount` en prod (alerte si un module ne
   se décharge pas).
3. **Coût mémoire cumulé.** Chaque module vivant = un classloader ; l'index API = ~21 Mio. Acceptable
   pour une quinzaine de modules, mais à surveiller si l'arsenal grandit beaucoup. Parade : décharger
   les modules inactifs (tout expire par défaut, §10.2), un seul index partagé.
4. **Budget d'exécution grossier.** `Budget.tick()` échantillonne (1 sur 1024) et se déclenche sur les
   sauts arrière/entrées de méthode ; un blocage dans **une seule** grande méthode d'API du jeu sans
   boucle instrumentée (ex. un appel bloquant interdit mais oublié dans la liste) ne serait pas borné.
   Parade : les appels bloquants sont déjà interdits (réseau, `wait`, fichiers), et le budget couvre le
   code du module lui-même.
5. **ECJ vs javac : divergences fines.** Messages différents (gérés), et de rares écarts de
   compilation possibles sur des constructions exotiques. Parade : privilégier javac quand il est
   présent ; en CI, compiler l'arsenal de départ avec **les deux** pour détecter toute divergence.
6. **Mixins pour les crochets manquants.** Le bus dépend de mixins (ex. `Projectile.onHit`) ; à
   revérifier à chaque version 26.x (les cibles de `@Inject` peuvent bouger). Parade : couvrir chaque
   crochet par un GameTest en jeu (le spike a la trame d'un test « la flèche du squelette est
   consommée à l'impact »).

---

## Annexe — reproduire le spike

Code : `.research/spikes/forge/` (non versionné). JDK 25. Depuis ce dossier :

- `./build.sh` — compile le SDK, le cœur de la Forge et les bancs d'essai.
- `./run.sh spike.CompileBench <javac|ecj> <roots|loader> <cache|nocache> [hints]` — temps de
  compilation à froid/à chaud + diagnostics.
- `./run.sh spike.IndexBench` / `./run.sh spike.ApiQuery "<requête>"` — index et recherche d'API.
- `./run.sh spike.GuardDemo` — garde-fou bytecode + budget de boucle.
- `./run.sh spike.UnloadDemo` — preuve de déchargement et scénarios de fuite.
- Preuve en serveur réel : `testmod/build_testmod.sh` puis lancer `prodserver/` (Fabric 26.3 dédié) ;
  chercher les lignes `[forge]` dans les logs.
