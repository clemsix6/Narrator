# Passation FLAKY-1 : cinq tests instables sous charge

Mission : rendre déterministes les tests qui échouaient par intermittence quand plusieurs builds tournent en même temps. Pour chacun, la vraie cause a été recherchée, puis on a tranché : test ou code. **En cas de contradiction avec un document de conception, le code et cette passation font foi.**

| Test | Cause | Où était le défaut | Correction |
|---|---|---|---|
| `SqlFileAccessTest` › `the attacks are real…` | DuckDB compresse ou non les blocs qu'il déverse, selon un choix adaptatif | test | messages privés incompressibles |
| `IdReservationsTest` › `an id is reserved on disk before it is handed out` | sous Windows, le test lit `narrator.ids` pendant son remplacement, et un remplacement raté relevait quand même le plafond | **test et code** | lecture sous le verrou dans le test ; dans le code, nouvelles tentatives et plafond fidèle au fichier |
| `JournalRecoveryTest` › `a failed database is opened again…` | course entre la réouverture automatique (500 ms) et le programme enfant | test | la première tentative attend que l'enfant tienne le fichier |
| `JournalThroughputTest` | seuils en temps réel : la charge divise le débit par deux | test | seuils sur le temps CPU du fil d'écriture, et seuils larges en temps réel |
| `JevClientTest` › `no retry when the time left is too short` | budget de 400 ms pour un vrai échange HTTP | test | horloge injectée, échéance confortable |

## 1. `SqlFileAccessTest` : la compression adaptative de DuckDB

### Cause

Le test prouve que l'attaque est réelle : avec l'accès opérateur, un `read_blob` sur un fichier de `<db>.tmp/` doit rendre le texte d'un message privé. Or ce texte n'y est en clair que si DuckDB a écrit le bloc **sans le compresser**.

DuckDB 1.5 compresse chaque bloc qu'il déverse (ZSTD, plusieurs niveaux) ou l'écrit brut. Le choix vient de `TemporaryFileCompressionAdaptivity` : il compare le temps mesuré des dernières écritures compressées et non compressées, avec une part de tirage aléatoire. Le choix dépend donc de la charge de la machine et du hasard. Aucun réglage ne le désactive : `duckdb_settings()` n'en propose aucun.

Mesures (sonde jetable, même données et même code, 16 exécutions) :

| Résultat | Fréquence | Occurrences du secret dans les fichiers déversés |
|---|---|---|
| bloc écrit brut (`DEFAULT`) | 5 sur 16 | 4 199 |
| bloc compressé à un niveau rapide (`S64K`, `S32K`) | 11 sur 16 | 2 ou 3, par chance (littéraux non codés) |
| bloc compressé à un niveau fort (littéraux codés par Huffman) | jamais vu ici, mais possible | 0 : **le test échoue** |

Le cas « 0 » est celui que la charge rend fréquent. Relancer la pression mémoire ne sert à rien : la décision reste adaptative. Le comportement de la production n'est pas en cause : la barrière du modèle (`the model reads none of DuckDB's own files…`) ne dépend pas du débordement et était déjà déterministe.

### Correction (test seulement)

Chaque message privé porte, après `the base is behind the waterfall #N`, environ 3 000 caractères de bruit (`Random(19)`) :
- trois sur quatre en ASCII (U+0001 à U+007F), les autres sur 2 octets en UTF-8 (U+0080 à U+07FF) ;
- les octets couvrent presque toutes les valeurs, avec environ 7,7 bits d'entropie par octet.

ZSTD ne peut alors ramener aucun de ces blocs sous la plus grande classe compressée (224 Kio). DuckDB les écrit donc **toujours bruts**, quel que soit le niveau choisi : c'est la branche « `compressed_size > MaximumCompressedTemporaryBufferSize` » de `TemporaryFileManager::CompressBuffer`.

Rien n'est affaibli :
- ce sont toujours de vrais messages privés, écrits par le vrai Journal et déversés par une requête autorisée au modèle ;
- toutes les attaques restent refusées pour le modèle, et la contre-épreuve opérateur est inchangée ;
- seul le nombre de messages passe de 5 000 à 1 000, avec 3,7 ko chacun.

Sonde avec le bruit : 10 exécutions sur 10 ont écrit les blocs bruts, avec 966 à 1 000 secrets sur 1 000 lisibles dans le fichier déversé.

## 2. `IdReservations` : un remplacement de fichier refusé sous Windows

### Cause

Sous Windows, `Files.move(…, ATOMIC_MOVE, REPLACE_EXISTING)` (`MoveFileEx`) **échoue tant qu'un autre handle tient le fichier cible ouvert**. C'est vrai même pour un lecteur Java, pourtant ouvert avec `FILE_SHARE_DELETE` : on obtient `AccessDeniedException`.

Mesuré avec un lecteur en boucle : 1 576 remplacements ratés sur 2 000. En revanche, le lecteur ne voit **jamais** un fichier absent ou partiel (0 erreur et 0 fichier vide sur 4 267 lectures) : le remplacement est bien atomique.

Le test relisait `narrator.ids` en boucle pendant que la réservation d'arrière-plan le remplaçait. Le remplacement échouait, et le code faisait alors ceci :

```kotlin
write(ceilings)
// Raised even when the write failed: ids keep coming, and the error is in the log.
ceilings.forEach { (kind, value) -> reserved.getValue(kind).set(value) }
```

Le plafond en mémoire était relevé **au-delà de ce que contenait le fichier**. Les identifiants suivants partaient sans réservation sur disque, et rien n'était retenté avant 1 s. D'où « turn 29 handed out past the file ».

### Le risque en production

Il est réel, mais pas là où on le cherchait.

- **Lecture concurrente pendant un remplacement, ou plantage pendant un remplacement** : aucun risque. Le fichier temporaire est forcé sur le disque avant le déplacement atomique, donc on garde soit l'ancien fichier, soit le nouveau, jamais un fichier absent ni à moitié écrit. Un `narrator.ids.tmp` laissé par un plantage est ignoré, puis écrasé. Seul le Journal lit le fichier, et seulement à l'ouverture.
- **Le vrai défaut** : n'importe quel programme qui ouvre brièvement `narrator.ids` fait échouer la réservation, par exemple Windows Defender qui analyse un fichier qu'on vient d'écrire, une sauvegarde, un indexeur, ou un opérateur qui lit le fichier. Le code traitait cet échec passager comme un disque plein : l'invariant « un identifiant n'est distribué qu'une fois couvert par le fichier » sautait pendant environ une seconde, en silence (une seule ligne d'erreur). Un plantage dans cette fenêtre pouvait redistribuer des identifiants déjà cités.

### Correction du code (`journal/IdReservations.kt`)

- **Nouvelles tentatives** (`replace`) : le déplacement est retenté après 1, 2, 4, 8, 16 puis 32 ms, soit 7 tentatives et 63 ms au plus. C'est plus long qu'une analyse ou une copie d'un fichier de quelques lignes. Une interruption pendant l'attente rend l'échec et rétablit l'indicateur d'interruption.
- **Plafond fidèle au fichier** : `reserved` n'est relevé que si l'écriture a réussi (`if (write(ceilings)) …`). La KDoc le disait déjà (« Raised once the file says so ») et le code faisait l'inverse.
- **Mode dégradé explicite** : tant que la dernière écriture a échoué, les identifiants partent quand même, ce qui est le comportement documenté pour un disque plein. Ils ne passent plus par le chemin synchrone : le fil serveur n'attend pas 63 ms par identifiant. Une nouvelle tentative a lieu en arrière-plan au plus une fois par seconde.
- `lock` devient `internal` : un test le tient pour lire le fichier entre deux écritures.
- `open(…, pause)` : une attente injectable entre deux tentatives, `Thread.sleep` par défaut. Un test s'en sert pour fermer son lecteur au bon moment.

### Correction des tests (`IdReservationsTest`)

- **`an id is reserved on disk before it is handed out`** : le test tient `ids.lock` depuis la demande d'identifiant jusqu'à la fin de sa lecture. Aucune réservation d'arrière-plan ne peut couvrir l'identifiant entre-temps, ni remplacer le fichier pendant la lecture. C'est déterministe, et **plus strict** qu'avant : avant, une écriture d'arrière-plan glissée entre la distribution et la lecture pouvait masquer une distribution non couverte.
- **Nouveau, `a program that has the file open while it is replaced lets no id out past it`** : reproduit le défaut du code. Un lecteur tient `narrator.ids` ouvert, et l'attente injectée le ferme après la première tentative ratée. Aucun identifiant ne doit dépasser le fichier.
- **Nouveau, `ids go on while the file cannot be replaced, and it covers them again once it can`** : le fichier est tenu longtemps. Les identifiants continuent, l'erreur est loguée, puis le fichier les couvre de nouveau une fois relâché.

Contre-épreuves, sous Windows :

| Code | Test du lecteur |
|---|---|
| ancien comportement (pas de nouvelle tentative, plafond relevé malgré l'échec) | **échoue** : « turn 5 handed out past the file » |
| nouveau code sans nouvelles tentatives (`REPLACE_MAX_WAIT_MS = 0`) | **échoue**, même message |
| nouveau code | passe |

Sur l'ancien code, le premier test, qui lit désormais sous le verrou, passe : la course venait du test.

## 3. `JournalRecoveryTest` : la réouverture gagnait la course

### Cause

Elle a été reproduite sous charge dès la première exécution :
- 18:03:41, le Journal tombe et ferme la base ;
- 18:03:42, il la **rouvre lui-même** (`reopenIntervalMs = 500`) avant que l'enfant, ralenti par la charge, ait pu la prendre ;
- l'enfant essaie ensuite pendant 60 s (« being used by another process », avec le PID de la JVM du test), puis meurt ;
- le test échoue avec « the child ended before printing 'READY' ».

Le code est correct : si personne ne tient le fichier, il doit le rouvrir. C'est la chorégraphie du test qui reposait sur l'horloge.

### Correction

- `DuckJournal.open(…, beforeReopen)` : un paramètre interne, `{}` par défaut, appelé sur le fil d'écriture avant chaque tentative de réouverture. `JournalFixture` le transmet.
- Le test y compte les tentatives et fait **attendre la première jusqu'à ce que l'enfant tienne le fichier** (`READY`, puis un `CountDownLatch`). La tentative échoue alors sur le vrai verrou du système, à coup sûr.
- `Thread.sleep(1_200) // two more attempts` est remplacé par une attente de la 3ᵉ tentative (`attempts >= 3`) : les deux premières ont échoué, quelle que soit la vitesse du fil d'écriture.

Contre-épreuve : avec un enfant artificiellement ralenti de 2 s, comme sous forte charge, l'ancien test échoue toujours (« the child ended before printing 'READY' »), et le nouveau passe.

## 4. `JournalThroughputTest` : mesurer ce que coûte l'écriture

### Cause

Les seuils portaient sur le temps réel. Sous charge, le fil d'écriture attend un cœur, et le débit tombe sous 5 000 événements par seconde pour tous les types. Le seuil était de 5 000, pour environ 7 500 au repos : une marge de 1,5 seulement.

### Choix

Il fallait choisir entre un seuil réglable par une propriété, une mesure relative à une référence calibrée, et le temps CPU. J'ai retenu le **temps CPU du fil `narrator2-journal`** (`ThreadMXBean.getThreadCpuTime`, code natif de DuckDB compris).

- C'est ce que coûte l'écriture : les autres builds n'y ajoutent presque rien. Un seuil réglable laisserait le test rouge sur les machines non configurées. Une référence calibrée mesure deux choses bruitées à des instants différents, sous une charge qui varie.
- Le temps CPU ne voit pas un fil qui **attend**, par exemple une synchronisation disque par ligne ou un verrou. Des seuils en temps réel restent donc, mais assez larges pour une machine chargée : ils attrapent une régression d'un ordre de grandeur.

| Mesure | au repos (3 exécutions) | avec 20 fils de calcul et 2 fils d'écriture sur disque en parallèle (3 exécutions) | seuil |
|---|---|---|---|
| jeu, par seconde CPU | 256 000 – 312 000 | 133 000 – 298 000 | > 30 000 |
| tous types, par seconde CPU | 7 470 – 8 850 | 6 400 – 6 700 | > 3 000 |
| jeu, temps réel | 205 000 – 279 000 | 65 900 – 149 000 | > 10 000 (inchangé) |
| tous types, temps réel | 7 450 – 8 790 | **3 300 – 4 630** (l'ancien seuil de 5 000 échouait) | > 1 000 |

Le test imprime désormais les deux séries de chiffres. **Pour le critère ±10 % de M3 §19.2, comparez les chiffres CPU de plusieurs exécutions** : ils varient bien moins que le temps réel.

## 5. `JevClientTest` : une horloge au lieu d'un chronomètre

### Cause

`no retry when the time left is too short` jouait sur un vrai budget de 400 ms : la réponse 503 arrivait après 250 ms, puis venaient le délai d'attente (200 à 400 ms) et le minimum de 200 ms. Sous charge, l'échange HTTP lui-même dépassait les 400 ms, d'où « timeout after 400 ms » au lieu de « no time left to retry ».

### Correction

- Le test a une échéance de 10 s, et l'horloge injectée du client **saute de 9,7 s dès que Jev a reçu la requête**, comme si la réponse avait pris ce temps. Il ne reste donc que 300 ms moins la durée réelle de l'échange, toujours moins que le délai d'attente le plus court plus 200 ms. Le vrai échange peut prendre le temps que la charge lui impose. Le test vérifie aussi `attempts == 1`.
- Mêmes fragilités, corrigées en même temps :
  - le délai par défaut du client de test passe de 1,5 s à 10 s : les tests de nouvelles tentatives (429, 5xx) disposaient d'à peine 900 ms pour leur premier échange ;
  - `a timeout falls back…` : Jev répond en 10 s, le client abandonne à 400 ms, et le test exige moins de 5 s au lieu de 1,5 s. Il prouve toujours que le client n'attend pas la réponse ;
  - `the key appears in no log line…` : délai de 2 s au lieu de 700 ms, réponse tardive de 10 s au lieu de 2 s. Le 429 garde le temps d'être retenté.

## Fichiers

- **Code** :
  - `journal/IdReservations.kt` : nouvelles tentatives, plafond fidèle, mode dégradé, `lock` interne, `pause` ;
  - `journal/DuckJournal.kt` : paramètre `beforeReopen` de `open`, ajout seul.
- **Tests** : `SqlFileAccessTest`, `IdReservationsTest`, `JournalRecoveryTest`, `JournalFixture` (paramètre `beforeReopen`, ajout seul), `JournalThroughputTest`, `JevClientTest`.
- **Doc** : `design/M1_M2.md` §5.4 (réservations).

## Pour la suite

- Un test qui lit un fichier que le code remplace doit tenir le verrou du code, ou accepter les échecs de remplacement : sous Windows, un lecteur suffit à bloquer un `ATOMIC_MOVE`.
- Un test qui fait intervenir un autre processus doit ordonner les événements par un signal (verrou, latch, ligne imprimée), jamais par un délai.
- Tout ce qui dépend de ce que DuckDB écrit dans `<db>.tmp/` doit tenir compte de la compression adaptative.
- Un seuil de performance doit porter sur le temps CPU du fil concerné, avec seulement un seuil large en temps réel.

## Vérifications

- **Reproduction avant correction**, sous charge (20 fils de calcul et 2 fils d'écriture forcée sur disque, plus les builds des autres agents) : `JournalRecoveryTest` a échoué dès la première exécution (« the child ended before printing 'READY' »). Les autres défauts ont été reproduits par les sondes et les contre-épreuves décrites plus haut, de façon déterministe.
- **Preuve de stabilité** : deux boucles en parallèle, chacune de 15 exécutions des cinq classes (`SqlFileAccessTest`, `IdReservationsTest`, `JournalRecoveryTest`, `JournalThroughputTest`, `JevClientTest`, soit 32 tests par exécution) :
  - l'une dans le worktree, l'autre dans une copie du module (un dossier Gradle distinct) ;
  - pendant tout ce temps, 20 fils de calcul et 2 fils d'écriture forcée tournaient, ainsi que les builds d'autres agents ;
  - résultat : **30 exécutions sur 30 vertes, soit 960 exécutions de tests sans un échec**, de 111 à 225 s par exécution.
- Sous cette charge, `JournalThroughputTest` mesurait tous types confondus 3 840 à 4 655 événements par seconde en temps réel (l'ancien seuil de 5 000 aurait échoué), et 6 640 à 7 310 par seconde CPU.
- `git merge main` (jusqu'à M3-12, garde-fou `TestMethodsReturnUnitTest` compris) : sans conflit.
- `.\gradlew.bat build` après la fusion : **vert**, avec **965 tests unitaires** (0 échec, 0 ignoré) et **169 GameTests** (« All 169 required tests passed »).
- Pas de `runServer`. Aucun appel réel à Claude ni à Jev.
