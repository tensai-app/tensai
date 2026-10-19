# File d'attente QA device

Vérifications qui demandent un téléphone et qui n'ont pas pu être faites. Chaque
entrée est exécutable de bout en bout sans rien relire d'autre.

Device de référence : OPPO CPH2251, Dimensity 900, 8 Go, ColorOS/Android 13.
Modèles attendus dans l'app : `qwen3-1.7b-q4km`, `phi4mini-q4km`, `gemma4-e2b-q4km`.

Dernière mise à jour : 2026-09-05.

---

## Règles qui priment sur tout le reste

- **Jamais** `./gradlew connectedDebugAndroidTest` ni `connectedCheck`. AGP
  désinstalle l'app en nettoyage et efface les modèles, plusieurs Go.
  Pour l'instrumenté, passer par `am instrument` sous wrapper hôte (voir §10).
- **Jamais** `adb reboot`, `adb shell reboot`, `adb shell su`, `adb uninstall`,
  `pm clear`. Un builder a déjà redémarré le téléphone de l'utilisateur.
- Installer avec `adb install -r`, qui préserve la base et les modèles.
- Le téléphone est personnel. Demander avant une session longue, et préférer les
  lectures en base aux captures d'écran quand c'est possible.

## Pièges de mesure, tous rencontrés pour de vrai

- **Toujours comparer à une baseline.** Ne jamais tester une condition absolue.
- **Toujours tirer le `-wal` avec le `.db`**, sinon la lecture est périmée.
- Une génération **menée à terme** est la seule mesure fiable : lire `ttftMs`
  persisté. Un tap « stop » annule la génération qu'on chronomètre.
- L'app en arrière-plan est throttlée par l'OS : un run partiellement en
  arrière-plan divise le tok/s par deux.
- **Un fichier de session mesure les cellules *utilisées*, le budget RAM couvre
  les cellules *allouées*** (`pad256(n)`). Comparer une estimation de cache à la
  taille d'un `.llamastate` est structurellement incapable de détecter une
  sous-estimation. Cette erreur a laissé passer une régression de sûreté.

## Outils

```bash
# Tirer la base avec son -wal
for f in tensai.db tensai.db-wal tensai.db-shm; do
  adb shell "run-as io.github.tensaiapp cat databases/$f" > "$f"
done

# Logcat natif uniquement
adb logcat -v time TensaiJNI:V AndroidRuntime:E "*:S"
```

L'app n'a **aucun** `android.util.Log`. Toute l'observabilité passe par le tag
natif `TensaiJNI` et par la base.

---

## 1. TEN-18 — Gemma doit sortir de `n_ctx = 256`

**C'est la vérification la plus importante de la file.** Le ticket entier repose
dessus, et le chiffre pour Gemma-4-E2B n'a jamais pu être calculé nulle part :
le fichier n'existe que sur le téléphone.

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -c
adb logcat -v time TensaiJNI:V "*:S" > qa18.txt &
# app > drawer > Models > Gemma-4-E2B-It > Load model
```

Lire dans `qa18.txt` :

```
nativeReadGgufMetadata: {... "kv_full_elements_per_token": N,
                             "kv_swa_elements_per_token": M,
                             "sliding_window": W ...}
loadModel: ... n_ctx=????  kv_cache=????
```

| Attendu | Verdict |
|---|---|
| `n_ctx` nettement > 256 | le ticket est livré |
| `n_ctx` toujours 256 | le ticket n'a rien réglé, quel que soit le nombre de tests unitaires verts |

Puis contrôle de non-régression : charger Qwen3-1.7B, `n_ctx` doit rester **4096**
en **F16**.

**Contrôle de sûreté, à ne pas sauter.** Générer jusqu'à remplir le contexte et
vérifier qu'il n'y a pas d'OOM. Une estimation trop basse ne se voit pas au
chargement, elle tue le process plus tard. Comparer la taille du `.llamastate`
produit à `kv_full_elements_per_token × tokens × coût_par_élément` : la mesure
doit rester **sous** l'estimation, jamais au-dessus.

Coûts par élément : F16 = 2 o, Q8_0 = 34 o / 32 éléments, Q4_0 = 18 o / 32.

**Backfill.** Les modèles importés avant TEN-18 doivent voir leur `n_ctx` corrigé
au démarrage suivant, sans réimport. Vérifier en base :

```sql
SELECT displayName, kvFullElementsPerToken, kvSwaElementsPerToken, slidingWindow
FROM models;
```

Aucune ligne ne doit rester à 0 si le fichier existe encore.

Référence mesurée, à ne pas re-mesurer : Qwen3-1.7B F16 pèse **114 650 o/token**
(pente sur 762 → 87 405 157 o et 2879 → 330 118 825 o). Gemma-4-E2B Q8_0 :
249 tokens → 2 435 778 o.

---

## 2. TEN-47 — rendu live du ThinkingBlock

Le fonctionnel est déjà validé sur device : Gemma rend `chat format=peg-gemma4,
parser=1638 bytes`, `content = "4"`, `thinking` peuplé, zéro marqueur `<|channel|>`
en base. Ce qui reste est le **rendu pendant le streaming**.

Le split moteur envoie des snapshots **cumulés** qui peuvent *rétracter* du texte
(des octets lus comme contenu deviennent le début d'un marqueur au token suivant).
En théorie un léger recul visuel est possible. Jamais observé.

Protocole : prompt qui déclenche vraiment un long raisonnement (les essais faits
ont produit `thinking = NULL`, Qwen3 étant allé droit à la réponse), et filmer le
bloc Reasoning pendant qu'il se remplit.

À vérifier aussi : `grep -c 'chat parse failed'` dans logcat doit rester à **0**.
Une occurrence isolée en début de génération est normale, en continu ça veut dire
que le modèle sort autre chose que ce que son template annonce.

Non couvert par manque de matériel : chemin de repli legacy jinja (aucun modèle
dont le template casse), et gpt-oss/harmony (aucun modèle de cette famille).

---

## 3. TEN-52 — le chemin `Failed`

L'arrêt volontaire est vérifié, mais **le comportement attendu a changé avec
TEN-39** : `stopReason` vaut désormais `Cancelled` et non plus null, et la rangée
porte un chip gris « stopped ». Le thinking reste conservé. Une vérification faite
avant TEN-39 est donc à refaire.

Ce qui manque est une **vraie** erreur de décode mi-stream, qui demanderait de
casser le moteur exprès.

À attraper la prochaine fois qu'une panne arrive naturellement (OOM de décode,
modèle qui casse en génération) plutôt qu'à forcer. Attendu : le partiel est
conservé, `stopReason = Failed`, chip rouge « generation failed » et l'icône ↻
dans la même rangée.

Point ouvert soulevé par le builder : confirmer qu'une vraie panne remonte bien en
`DecodeEvent.Error` et ne sort pas en exception à travers le flow. Si elle sortait
par le `collect`, `failed` resterait faux et on retomberait sur le cas « stopped ».
La lecture du code dit que non (`catch (Exception) → trySend(Error)`), mais ce
n'est pas prouvé sur une panne réelle.

---

## 4. TEN-69 — le split de budget sur un modèle à channels

La moitié comptable est démontrée en test unitaire : la réponse récupère le budget
que le raisonnement n'a pas dépensé, et le test rejoue au token près la ligne
device de 1027 tokens. Rien à revérifier là.

Ce qui reste tient entièrement au device.

**D'abord la question préalable, qui peut annuler le reste.** Charger Gemma-4 et
lire si un `ReasoningBudget` est seulement construit :

```
nativeReadGgufMetadata: {... "supports_thinking": ??? ...}
```

`ChatViewModel.generateReply` gate le split sur cette valeur. Si elle est fausse
pour gpt-oss ou Gemma, il n'y a ni budget ni split, et TEN-69 ne concerne pas ces
modèles du tout. Sur Gemma-4 elle valait `true` au 2026-09-04, mais gpt-oss n'a
jamais été testé et aucun modèle de cette famille n'est importé.

**Ensuite le comportement.** Génération longue sur Gemma-4, et vérifier que la
phase bascule bien du raisonnement à la réponse alors que le modèle n'émet jamais
`</think>`. Le signe en base : `stopReason = ResponseBudgetExhausted` plutôt que
`ReasoningBudgetExhausted`, avec `thinking` peuplé et `content` non vide.

Si c'est `ReasoningBudgetExhausted` avec une réponse vide, le pilotage par
`DecodeEvent.Parsed` ne fonctionne pas sur ce format et le ticket n'est pas livré.

**Deux choses déduites par lecture, jamais observées** : que `Parsed` arrive avec
`content` vide pendant le canal analysis puis non vide au canal final, et l'ordre
des deux callbacks (`emit` puis `emit_parse` dans la même itération du JNI). Un
ordre inverse ferait basculer la phase un token plus tôt, sans conséquence
budgétaire.

**Et une borne jamais atteinte** : qu'une réponse aille effectivement jusqu'à 2048
tokens. TEN-65 peut clipper `nPredict` sous le total quand le contexte est serré,
ce qui n'a pas été mesuré.

---

## 5. TEN-35 — le sheet de réglages de sampling

Tout ce qui est derrière l'écran est couvert en unitaire : les réglages atteignent
le `DecodeRequest`, l'aller-retour de sérialisation tient stop sequences comprises,
un blob malformé retombe sur les défauts, et la répartition du budget de
raisonnement est dérivée de `maxTokens` par construction.

**Ce qui reste est le rendu, et rien d'autre.** Ouvrir le sheet sur une
conversation et vérifier : les sliders bougent et affichent la valeur, les champs
mono acceptent la saisie, le bouton de remise aux défauts revient bien à
`SamplingConfig()`, et le sheet se ferme sans perdre ce qui vient d'être réglé.

**Le test bout en bout qui vaut la peine** : régler la température à 0 et une stop
sequence sur une conversation, envoyer, et vérifier en base que le tour suivant
porte bien ces valeurs. C'est l'acceptation littérale du ticket, et le seul moyen
de confirmer que la persistance survit à un vrai cycle d'app.

**Un point qui ne se voit que sur device** : `org.json` est un stub dans le
framework Android alors que les tests tournent contre la vraie implémentation.
`SamplingJson.decode` attrape `JSONException` et rejette NaN après coup, donc un
parseur plus strict ferait retomber sur les défauts plus souvent, pas planter.
À confirmer en ouvrant une conversation dont le `samplingJson` a été édité à la
main avec du JSON limite : elle doit s'ouvrir, jamais crasher.

---

## 6. TEN-36 — les écrans d'onboarding

La logique est couverte sans device : le portail (six états, fonction pure), la
persistance du skip et des choix, et le fait que le backend choisi atteigne
réellement le `ModelLoadConfig`. Ne pas refaire ça sur device, c'est démontré.

**Ce qui reste est le parcours complet, sur un install vraiment vierge.** C'est
l'acceptation littérale du ticket : device sans aucun modèle, onboarding, import
d'un GGUF via SAF, chargement, premier chat, sans jamais sortir du flow.

À regarder en chemin :
- le feu tricolore s'affiche bien sur le fichier importé, et dit la vérité
- le sélecteur de backend et celui de thème appliquent leur choix **immédiatement**,
  pas au redémarrage suivant
- le bouton skip mène quelque part d'utilisable, et non à un accueil mort

**Le cas qui mérite d'être provoqué**, parce qu'il n'arrive jamais par hasard :
importer un modèle, puis supprimer son fichier depuis les réglages système
d'Android (effacer les données de l'app garde la base mais pas forcément les
fichiers). Au lancement suivant, l'onboarding doit revenir comme sur un install
neuf, et non ouvrir un accueil qui ne peut rien charger. La logique est testée,
le comportement réel de ce nettoyage sur ce téléphone ne l'est pas.

**Non couvert non plus** : que le splash tienne bien jusqu'à la décision du
portail sans flash visible. Le code le garantit (`setKeepOnScreenCondition` sur
un état null), mais une frame parasite ne se voit qu'à l'oeil.

---

## 7. TEN-37 — l'écran d'accueil

La machine à états de la pill, les bandes de salutation et l'envoi depuis
l'accueil sont couverts sans device. Ne pas les refaire.

**Ce qui reste est l'acceptation littérale du ticket** : conformité au prototype
HomeScreen, **dark et light**. Salutation en Figtree large, filigrane 天 à 8 %
d'opacité décalé à droite et non interactif, quatre chips de suggestion.

Les trois faces de la pill sont à voir en vrai, chacune dans les deux thèmes :
- modèle chargé, champ normal
- modèle existant non chargé, « Load X to start… » avec le point lavande, et le
  tap qui mène bien au chargement de ce modèle-là
- aucun modèle, CTA qui mène à Models

**Le cas à provoquer**, parce qu'il ne se produit jamais seul : importer deux
modèles, supprimer le fichier du plus récent depuis les réglages système, et
vérifier que la pill propose **le second** au lieu d'abandonner ou de proposer le
mort. La logique est testée, la manipulation réelle ne l'est pas.

Et : charger un modèle puis supprimer son fichier pendant qu'il est résident. La
pill doit rester fonctionnelle — le modèle est en RAM et peut répondre.

**Non couvert non plus** : que le filigrane soit bien non interactif, c'est-à-dire
qu'un tap dessus traverse jusqu'à ce qui est dessous.

---

## 8. TEN-38 — Settings, et surtout ce que « Clear » fait en vrai

Les courses purge contre save sont couvertes par neuf tests unitaires, y compris
le chemin `Unchanged`. La logique n'est pas à revérifier.

**Ce qui reste sur device est le comportement observable**, et un cas vaut le
détour bien plus que le rendu.

### Le test qui compte : Clear pendant une génération

Lancer une génération longue sur une conversation avec du contexte, et taper
Clear dans Settings **pendant** qu'elle streame.

Attendu, et c'est délibéré :
- la réponse **continue de streamer** sans interruption
- la conversation garde son contexte à l'écran
- le dossier `files/sessions/` est vide après coup
- **aucun fichier orphelin ne reste** : `run-as … ls -l files/sessions/` ne doit
  rien montrer, ni `.llamastate` ni `.llamastate.tmp`

Le dernier point est celui qui casserait en silence. Un save en vol au moment du
purge écrit son dump puis le jette ; si un `.tmp` de plusieurs centaines de Mo
survit, la logique de rejet n'a pas fonctionné et rien dans l'app ne le
ramassera jamais.

Puis envoyer un nouveau message : une session doit être réécrite. Clear libère le
disque, il ne désactive pas la persistance.

### Le second : Clear pendant un swap de modèle

Même manipulation mais en changeant de modèle, ce qui déclenche un save par
`onBeforeModelSwap`. Vérifier que le swap aboutit et que rien ne reste sur disque.

### Les chiffres

La barre d'usage doit correspondre au réel :

```bash
adb shell 'run-as io.github.tensaiapp du -sk files/models files/sessions'
```

Et un contrôle qui a du sens vu ce qui a été construit : supprimer un fichier de
modèle depuis les réglages système, puis rouvrir Settings. La taille annoncée doit
refléter ce qui est sur le disque, pas ce que la base prétend.

### Le reste

Les quatre groupes, le thème appliqué **à chaud** (l'acceptation le demande
explicitement), et la conformité au prototype dans les deux thèmes.

---

## 9. TEN-68 — les 350 s après une longue génération

Observé **une fois**, jamais reproduit sur la tentative suivante à volume
identique. Restore de 2879 tokens rendu en 780 ms, puis 350 758 ms de TTFT.

Deux contrôles écartent déjà le restore et la re-tokenisation seule :
cache vivant sans switch = 8 120 ms, même volume au tour d'après = 7 559 ms.

Protocole : longue génération, puis tour suivant, **avec et sans switch de
conversation**, plusieurs fois. Instrumenter `find_common_prefix_length` pour
logger le `n_past` retenu contre `embd.size()` — un seul log dirait si le préfixe
s'effondre et où.

---

## 10. Tests instrumentés

**Toujours sous wrapper hôte**, et traiter le code de sortie **124 comme un échec** :

```bash
timeout 300 adb shell am instrument -w \
  io.github.tensaiapp.test/androidx.test.runner.AndroidJUnitRunner
```

À faire tourner :

- **`TensaiDbMigrationTest`** — jamais exécuté depuis la migration v6 → v7.
  Son KDoc dit « all the way to the current version » mais le test s'arrête à la
  v4 : à passer à 7 pendant qu'on y est.
- **TEN-55, résolu le 2026-10-05 : ce n'est pas notre code.** Le gel de la suite
  complète vient du freezer de ColorOS (`OplusHansManager`, service `hans`). Une
  suite instrumentée tourne sans activité visible et, tant qu'aucun test n'a
  démarré `InferenceService`, sans service de premier plan. Hans fait passer le
  process de R à M puis à F en une trentaine de secondes, et F le gèle. Toutes
  les threads restent alors dans `get_signal`, `utime`/`stime` plats, et
  `kill -3` reste sans effet (d'où les mécanismes in-process restés muets). Le
  log le dit en clair :

  ```
  adb logcat -d | grep OplusHansManager | grep tensaiapp
  ... freeze uid: 10361 io.github.tensaiapp pids: [<pid>] scene: LcdOn
  ```

  Fréquence : 2 runs sur 8 quand la suite était plus lente, 1 sur 17 le
  2026-10-05 (suite à ~40 s). Un run gelé se reconnaît à cette ligne, pas besoin
  de le caractériser autrement : `am force-stop` le libère (`unfreeze ...
  reason: UidGone`). L'app réelle n'est pas exposée : avec un modèle chargé, son
  service de premier plan bloque la transition (`cannot transition from R to M,
  importance=fg-service`, mesuré le même jour).

  **Pour avoir un verdict, lancer la suite classe par classe** (`-e class ...`) :
  chaque classe tient sous la fenêtre de Hans, la suite complète non (~45 s
  depuis JniUtf8BoundaryTest, gelée 1 fois sur 2 le 2026-10-05).

---

## 11. TEN-39 — les six états, et surtout les deux qu'on ne peut pas provoquer

La logique est unitaire (seuil de stockage, chiffres du feu rouge, absence de
jargon : 272 tests verts). Ce qui reste tient en deux états qu'aucun test ne peut
mettre en scène, et en une lecture de base.

**Le seuil de stockage (état 2).** Le prédicat est couvert sur ses bords, mais
personne n'a vu la bannière. Elle exige un vrai `StatFs` sous le seuil, donc un
téléphone réellement plein. Protocole le moins destructeur : lire d'abord

```sql
SELECT displayName, sizeBytes FROM models;
```

puis comparer à l'espace libre réel (`adb shell df /data`). La bannière doit
apparaître ssi `libre < moyenne(tailles sur disque)`. Remplir le disque avec un
fichier factice sous `run-as` plutôt qu'avec un vrai modèle — plus rapide à
défaire. **Ne pas** supprimer de modèle pour provoquer l'état.

**L'échec d'import (état 3).** Le plus simple à déclencher honnêtement : lancer un
import depuis un provider distant (Drive) et couper le réseau en cours de copie —
c'est le `ModelImportException` réel. Attendu : carte rouge, « Import didn't
finish », **aucun** mot de l'`IOException` sous-jacente, « Try again » qui rouvre
le sélecteur, et le fichier partiel supprimé (vérifier
`run-as … ls files/models/`).

**L'OOM au chargement (état 5) reste hors d'atteinte fiable.** `OutOfMemoryException`
ne se lève que sur un OOM de *heap JVM* au passage JNI ; un OOM natif tue le
process. Charger un modèle trop gros produit donc en pratique soit un kill, soit
le `GenericFailed`. Le dialogue « Not enough memory » n'a jamais été vu, et il
n'est pas prouvé qu'il soit atteignable sur ce device. **À trancher plutôt qu'à
tester** : si le chemin est inatteignable, c'est le dialogue `GenericFailed` qui
doit porter le message d'OOM.

**Le chip « stopped » (état 6).** Voir §3 : le comportement a changé, une
vérification antérieure ne vaut plus. Contrôle en base après un stop :

```sql
SELECT content, stopReason, tokensGenerated FROM messages ORDER BY createdAt DESC LIMIT 1;
```

Attendu `stopReason = 'Cancelled'`, `tokensGenerated` null, contenu partiel intact.
Vérifier aussi le cas limite couvert unitairement mais jamais vu en vrai : taper
stop **juste après** la fin d'une génération doit laisser `Complete`, pas
`Cancelled`.

**Régression à surveiller** : les lignes écrites avant TEN-39 par un stop ont
`stopReason = NULL` et resteront sans chip. C'est voulu (ne pas deviner), mais si
la base de test en contient, elles ressembleront à des réponses complètes.

---

## 12. TEN-43 — QA device pass V1

Le ticket de QA globale. À faire en dernier, une fois la phase 4 livrée.

---

## 13. TEN-66 — le thread du moteur est-il réellement rendu

Le correctif est livré (`LlamaKtEngineImpl : Closeable`, fermé au site de
construction) mais **son acceptation est structurellement invérifiable en JVM**,
ce que le ticket ne disait pas.

`LlamaEngine`'s companion object appelle `System.loadLibrary("llamakt")`
(`LlamaEngine.kt:284-287`), donc `LlamaKtEngineImpl()` lève `UnsatisfiedLinkError`
hors device. Aucun test unitaire ne peut construire un moteur, donc aucun ne peut
observer son thread mourir.

**Protocole**, une fois un device disponible. Lancer la suite instrumentée par
classes (jamais `connectedDebugAndroidTest`, voir les règles en tête de ce
document) et compter les threads entre deux classes :

```
adb shell ps -T <pid> | grep llama-kt-engine | wc -l
```

Attendu : **1 par moteur vivant au repos**, et **jamais croissant** avec le
nombre de classes exécutées. C'est la non-croissance qui prouve quelque chose, pas
la valeur absolue. Pendant un calcul, `n_threads - 1` workers s'y ajoutent (voir
plus bas) : ne compter qu'entre deux calculs.

Corrigé le 2026-10-05. Ce document attendait 2 permanents. Mesuré sur Qwen3-1.7B
(`n_threads=2`, llama.cpp `99f3dc322`) : 1 au repos, 2 pendant le decode, et le
second change de TID à chaque relevé. ggml-cpu crée ses workers à chaque calcul
de graphe, il n'y a pas de pool persistant (pas d'OpenMP dans notre build).

Ce document annonçait « 0 ou 1 », et c'était faux. Un seul moteur produit deux
threads de ce nom : `LlamaKtEngineImpl.kt:71` crée l'executor mono-thread
`llama-kt-engine`, puis `ggml-cpu.c:3283` crée `n_threads - 1` workers par
`pthread_create` **sans leur poser de nom**, et sous Linux un thread hérite du
`comm` de son créateur. Le créateur, ici, est l'executor.

Conséquence pour TEN-55 : les « deux threads observés vivants pendant le gel »
n'étaient pas la preuve de deux moteurs fuités qu'on y a lue. Un moteur suffit à
les expliquer. Voir le commentaire du 2026-09-09 sur ce ticket.

Mesuré le 2026-09-09, app lancée sans modèle chargé : **0**. Cohérent, le
threadpool ggml n'existe pas avant un chargement.

**Ce qui n'est pas couvert et qui m'inquiète plus que le compte** : `close()`
appelle `llamaEngine.interrupt()` depuis le thread appelant, alors qu'un
`free()` en vol sur le dispatcher est en train d'invalider le handle natif. La
règle « free puis close » évite la course, et les tests la respectent, mais rien
ne l'impose au compilateur. Vérifier qu'aucun crash natif ne sort d'une classe
qui échoue **avant** son unload — c'est exactement le cas où l'ordre n'est pas
tenu.

Lié : TEN-55, dont ce ticket retire une variable sans prétendre être la cause.

---

## 14. TEN-72 — la passe de relecture se déclenche une fois, et une seule

La logique est unitaire (`KvCacheShapeBackfillTest`, 9 tests) et la migration
v7 → v8 a son test (`TensaiDbMigrationTest.migrate7To8...`). Ce qui reste tient
en deux points, tous deux hors JVM.

**Le numéro annoncé par le JNI.** `LlamaEngine.kvShapeVersion()` traverse
`System.loadLibrary("llamakt")`, donc rien ne peut l'appeler hors device (même
raison qu'au §13). Le symbole est bien exporté dans le `.so` construit ici
(`nm -D … | grep nativeKvShapeVersion`), mais la valeur rendue n'a jamais été
lue par du Kotlin.

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -c
adb logcat -v time TensaiJNI:V "*:S" > qa72.txt &
# app > drawer > Models > n'importe quel modèle > Load model
```

Attendu dans `qa72.txt` : `"kv_shape_version": 2` dans le JSON de
`nativeReadGgufMetadata`. Un `0` signifierait que le champ n'est pas émis, et
alors **chaque démarrage relit tous les en-têtes**, ce qui est précisément la
panne que le ticket évite.

**Une passe, pas deux.** C'est l'acceptation qui coûte cher si elle est fausse :
une relecture qui change `n_ctx` invalide toutes les sessions du modèle, donc
re-prefill complet de chaque conversation.

```sql
SELECT displayName, kvFullElementsPerToken, kvShapeVersion FROM models;
```

Protocole : installer par-dessus une base v7 (`adb install -r`, jamais
`pm clear`), lancer l'app, lire la table. Toutes les lignes dont le fichier
existe encore doivent porter `kvShapeVersion = 2`. Puis tuer l'app, la relancer,
et vérifier qu'**aucune** ligne d'`I/TensaiJNI: nativeReadGgufMetadata:` ne sort
au second démarrage.

| Attendu | Verdict |
|---|---|
| aucun `nativeReadGgufMetadata` au 2ᵉ lancement | le ticket est livré |
| les mêmes lectures qu'au 1ᵉʳ | la version n'est pas réécrite, chaque démarrage retaxe l'utilisateur |

**Contrôle de valeur, lié au §1.** Un modèle importé entre TEN-18 et son
correctif porte un chiffre sous-compté. Après la passe, son
`kvFullElementsPerToken` doit avoir **augmenté** ou être resté identique, jamais
baissé — une baisse voudrait dire que la formule courante sous-compte encore.

Enfin, `ModelDaoTest` (nouveau, sans GGUF requis) épingle le prédicat SQL
`kvShapeVersion != :version`, que le fake de la suite JVM ne peut pas couvrir.
À passer via `am instrument` sous wrapper hôte (§10).

---

## Ce qui ne demande pas de device

Pour mémoire, afin de ne pas bloquer par erreur : TEN-71, TEN-69 (le cas
Qwen3-sans-raisonnement est unitaire), TEN-53, TEN-54, TEN-70, TEN-73, TEN-74,
TEN-75, la phase 4 (TEN-35 à 40 — mais voir §11, TEN-39 laisse deux états
invérifiables sans device) et la phase 5 hors TEN-43/44.

**TEN-66 a quitté cette liste** (voir §13) : le correctif est écrit, son
acceptation ne l'est pas.

**TEN-42 (CI/CD) monte en priorité** tant qu'il n'y a pas de device : un build et
des tests automatisés à chaque commit sont le seul filet qui reste.

## 15. Audit 2026-09-05 — le volet perf, à chiffrer

L'audit du moteur (`docs/audit-inference-2026-09-05.md`) a confirmé plusieurs
capacités llama.cpp non câblées. **Aucun gain n'est mesuré** : ce sont des
estimations par lecture, et elles ne deviendront des tickets qu'une fois
chiffrées.

**Établir d'abord une baseline**, sinon rien de ce qui suit n'est interprétable :
Qwen3-1.7B et Gemma-4-E2B, prefill et decode séparés, CPU et Vulkan, sur une
conversation courte et une longue. Lire `ttftMs` et `tokPerSec` persistés, sur des
générations **menées à terme** (voir les pièges de mesure en tête de document).

| À mesurer | Hypothèse à valider ou tuer |
|---|---|
| `n_ubatch` à 128 vs défaut (`tensai_jni.cpp:658`) | Diviserait grossièrement par quatre le budget de 300 Mo codé en dur, au prix d'un peu de débit prefill. Différence entre « ce modèle ne se charge pas » et « il se charge plus lentement » |
| Affinité CPU (`tensai_jni.cpp:664`) | Les 2 threads gros cœurs ne sont pinnés sur rien. Le profil big.LITTLE du Dimensity 900 est déjà détecté côté threads, pas côté placement |
| Flash attention (`LlamaKtEngineImpl.kt:151`) | `AUTO` est subi, jamais choisi. Touche directement la forme du cache V, donc la comptabilité de TEN-18/TEN-72 : à mesurer **avec** un contrôle de non-régression sur `n_ctx` |
| Taux de réutilisation KV (`LlamaKtEngineImpl.kt:351`) | Le compteur existe côté natif, jamais échantillonné. **Sans lui, impossible de savoir si les sessions servent** — donc à câbler avant de mesurer TEN-78 et TEN-81 |
| `DeviceProfiler.profile()` en composition (`DeviceProfiler.kt:47`) | Appelé sur le thread principal, une fois par carte et par recomposition. Coût réel inconnu |

**Contrôle de répétition** (`tensai_jni.cpp:790`, non vérifié) : `repeat`,
`frequency`, `presence`, DRY, XTC et la **seed** ne sont jamais écrits. Ce n'est
pas de la perf mais de la qualité de sortie — et sans seed, aucune mesure de
qualité n'est reproductible. À traiter avant toute comparaison A/B de sampling.

---

## 16. TEN-91 — un GGUF corrompu doit refuser l'import, pas tuer le process

Le correctif rend `nativeReadGgufMetadata` incapable de laisser une exception C++
traverser la frontière JNI. La preuve demande un fichier qui déclenche le défaut,
et ce fichier est fabriqué, pas trouvé : un `.gguf` minimal dont
`general.name` contient une séquence d'octets qui n'est pas de l'UTF-8 valide.
Le builder le laisse en fixture dans le dépôt, chemin donné dans son rapport.

**Attention avant de commencer** : ce ticket a deux chemins d'atteinte et le
second est le dangereux.

1. **Import.** Pousser la fixture sur le device et l'importer par le sélecteur
   de fichiers. Attendu : la carte d'erreur « ce n'est probablement pas un
   fichier GGUF » (`ImportFailure`, TEN-39), l'app debout, aucun `Fatal signal`
   dans logcat.

2. **Backfill au démarrage.** C'est celui qui brique l'app, et c'est celui qu'il
   ne faut **pas** provoquer sur l'installation de l'utilisateur avant d'avoir
   vérifié le point 1. `KvCacheShapeBackfill` relit l'en-tête de chaque modèle
   en base quand `TENSAI_KV_SHAPE_VERSION` a changé — un fichier corrompu en base
   tue alors le process à chaque lancement, en boucle, sans écran ni message.
   Ne le tester qu'après avoir confirmé que le point 1 tient, et savoir d'avance
   comment sortir : supprimer la ligne du modèle corrompu en base, jamais
   `pm clear` (efface les modèles, 6,5 Go).

Filtre logcat utile :

```
adb logcat -c && adb logcat | grep -E 'readMetadata|Fatal signal|type_error|terminate'
```

Attendu : zéro `Fatal signal`, zéro `terminate`, et un message d'erreur nommé
côté Kotlin.

---

## 17. TEN-92 — la session sauvegardée doit contenir ce que le cache contient

Le correctif borne `n_tokens` par `n_past` à la sauvegarde. L'écart n'apparaît
que sur une génération qui **ne finit pas par EOS** : stop word ou budget de
tokens atteint (écart de 1), ou annulation mi-graphe (écart de tout ce qui
restait à décoder).

**Protocole**, dans cet ordre — l'ordre compte, le cas EOS ne prouve rien :

1. Générer une réponse volontairement close par le budget de tokens. Le
   `stopReason` persisté en base doit dire `ResponseBudgetExhausted` ou
   `TokenBudget`, pas `Eos`. Ne pas se fier au bouton Envoyer pour savoir si la
   génération est finie — lire `stopReason` en base.
2. Mettre l'app en arrière-plan pour déclencher la sauvegarde, relever la taille
   du fichier `.llamastate`.
3. Redémarrer à froid, rouvrir la conversation, envoyer un message.
4. Lire la ligne que le natif journalise déjà :

```
adb logcat | grep -E 'nativeLoadSession|Input processed'
```

Attendu : `loaded N tokens` puis un `n_past` **stable d'une restauration à
l'autre**. Avant le correctif, chaque cycle sauvegarde/restauration décale d'un
cran de plus, donc la dérive se voit en répétant les points 1 à 4 deux fois de
suite et en comparant les deux `n_past`.

**Ce que ce test ne prouve pas** : que le préfixe réutilisé est *grand*. Il sera
petit sur un modèle qui raisonne, et c'est TEN-88, pas ce ticket. Ici on vérifie
seulement qu'il ne **glisse** pas.

## 18. TEN-90 — les blocs display, deuxième passe

Première passe : l'inline rend (`ax² + bx + c = 0`, `x₁ et x₂` lus à l'écran),
les blocs `$$…$$` restaient bruts. Cause trouvée avec un `cat -A` du message
stocké : deux espaces en fin de ligne après le label en gras font un hard line
break, donc commonmark met le `StrongEmphasis` du label et tout le bloc dans
**un seul paragraphe**, et la règle tout-ou-rien du paragraphe faisait décliner
la formule. La règle porte désormais sur la portée entre les délimiteurs.

À vérifier à l'écran, sur la conversation « Combien font 17 x 24 ? Explique » :

- les deux blocs sous leurs labels en gras sont rendus, le label restant gras ;
- l'inline n'a pas bougé (il marchait déjà, c'est le contrôle de non-régression
  qui compte le plus ici) ;
- `\quad` entre deux équations d'une même ligne.

**Le point douteux est `\quad`.** Il est rendu par U+2003 (`InlineMath.kt:368`),
et rien ne dit que TensaiSans porte ce glyphe. Le repli de police d'Android
couvre normalement les espaces sans laisser de tofu, mais c'est une hypothèse,
pas une mesure. Si le cadratin s'affiche en carré ou disparaît, la correction
est une ligne dans la table de `InlineMath.kt` — pas la peine d'aller chercher
plus loin.

Ce que cette passe ne peut pas casser : décliner laisse le LaTeX brut à
l'écran, ce qui est laid mais honnête. Le seul défaut qui justifierait un revert
serait une formule **rendue différemment de ce que le modèle a écrit**, et c'est
ce que l'assertion `x = ` + `$PATH` + ` + 1` garde en JVM.

### 17 bis. Le court-circuit `Unchanged` devient vivant pour la première fois

Trouvé en corrigeant TEN-92, et c'est un second défaut que le correctif révèle
plutôt qu'un effet de bord.

`SessionRepository.save` court-circuite quand rien n'a bougé : même
conversation, même `modelId`, même `nCtx`, même `kvCacheType`, et surtout même
compte de tokens. Le compte vivant remonte à `nativeKvCacheUsedCells`, donc à
`n_past`. Le compte stocké, lui, valait `embd.size()`.

Sur une génération close par stop word ou par `n_predict` — le cas courant — les
deux diffèrent de 1 **par construction**. L'égalité était donc systématiquement
fausse et la dedup ne s'est **jamais** déclenchée depuis qu'elle existe : chaque
sauvegarde réécrivait plusieurs centaines de Mo pour rien.

Avec le clamp, les deux côtés lisent le même nombre.

À vérifier sur device, en même temps que §17 :

- deux sauvegardes d'affilée **sans génération entre les deux** doivent donner
  un `SaveResult.Unchanged` et **aucune** réécriture du fichier. Comparer le
  `mtime` du `.llamastate` avant et après suffit.

Le seul risque connu est bénin et n'est pas traité : régénérer une réponse de
longueur identique au token près ferait sauter la sauvegarde et laisserait un
fichier qui décrit l'ancienne réponse. Le préfixe commun du prefill suivant
rattrape ça par un prefill plus long, pas par de la corruption.

## 19. Le cache de préfixe est-il vidé en entier à chaque tour ? Un grep répond

Sorti d'une recherche externe sur TEN-88, le 2026-09-09. **C'est la mesure la
moins chère de ce document et potentiellement la plus importante.**

`rn-completion.cpp:158` réutilise le préfixe commun en retirant du cache tout ce
qui suit :

```cpp
bool cache_remove_success = llama_memory_seq_rm(kv, 0, n_past, -1);

// For hybrid models (LFM-2, Granite, Mamba, etc.), partial cache removal may fail
if (!cache_remove_success) {
    LOG_WARNING("Partial cache removal failed (likely hybrid/recurrent model), doing full cache clear");
    llama_memory_clear(kv, false);
    embd.clear();
    n_past = 0;
```

Or `common/common.h:911-915` définit quatre capacités de contexte, dont
`COMMON_CONTEXT_SEQ_RM_TYPE_FULL` = « ne sait retirer que des séquences
entières ». Elle est déterminée par **sonde à l'exécution**
(`common/common.cpp:1459`), pas par métadonnée de modèle. **Rien dans notre
arbre ne consulte cette sonde** : on appelle `seq_rm` et on lit son booléen.

Si le contexte est `FULL`, alors **toute divergence, même d'un token, vide le
cache en entier** et le prefill repart de zéro. La réutilisation de préfixe ne
fonctionne alors jamais, pour aucune raison, indépendamment du raisonnement.

La famille concernée en amont est celle des modèles hybrides, récurrents et
**SWA**. Gemma-4 utilise une attention à fenêtre glissante (`llama_kv_cache_iswa`,
voir §1 et TEN-18). Ça ne prouve rien, ça désigne un suspect.

### La mesure

```
adb logcat -c
# une conversation de deux tours, même modèle, sans redémarrer l'app
adb logcat -d | grep -E "Partial cache removal|prompt ingested, n_past"
```

Deux issues, et elles ne veulent pas dire la même chose du tout :

- **le warning apparaît** : la réutilisation de préfixe n'existe pas sur ce
  modèle. C'est un défaut bien plus grave que TEN-88, ça explique TEN-68 mieux
  que la re-segmentation BPE, et ça rend le système de sessions entièrement
  inutile pour ce modèle. À ficher immédiatement.
- **le warning n'apparaît pas** et `n_past` est non nul : le retrait partiel
  marche, le seul problème est bien la divergence du raisonnement de TEN-88, et
  l'analyse de ce ticket tient.

À faire sur **Gemma-4** (suspect, SWA) **et** sur Qwen3 (témoin, attention
classique). Le contraste entre les deux est ce qui donne la réponse.

### Pourquoi ça se fait avant tout le reste

Ça coûte deux commandes et ça décide de la lecture de trois tickets, TEN-88,
TEN-68 et TEN-92. Les protocoles §17 et §17 bis mesurent des `n_past` dont
l'interprétation dépend entièrement de cette réponse.

### 18 bis. Ce que la passe device du 2026-09-09 a donné, et ce qu'elle a raté

**Rendu : vérifié bon.** Sur la conversation « Ecris la formule quadratique »,
les deux blocs display rendent sous leurs labels en gras, l'inline n'a pas bougé,
et le point que §18 donnait pour douteux est levé : `\quad` s'affiche bien en
espace cadratin et `\text{et}` rend « et ». U+2003 passe dans TensaiSans.

Le bloc de raisonnement, ouvert, rend aussi ses paragraphes et son math inline
(TEN-89).

**Ce que cette conversation ne montrait pas, et que l'utilisateur a vu ailleurs.**
Dans « What is 2+2? », ce bloc s'affichait brut, délimiteurs compris :

```
$$
\text{index} = (\text{hash}(key) + k^2) \mod \text{tableSize}
$$
```

`\mod` n'était pas dans le vocabulaire, et une seule commande inconnue fait
retomber toute la formule sur sa source. Corrigé en `10bdee2`.

**La leçon de protocole, plus importante que le correctif** : une seule
conversation ne suffit pas à valider un rendu. Le corpus entier se lit en base,
et il fallait le faire d'abord :

```sql
SELECT c.title, m.role, length(m.content)
FROM messages m JOIN conversations c ON c.id = m.conversationId
WHERE m.content LIKE '%$$%' OR m.content LIKE '%' || char(92) || 'frac%';
```

Et le recensement des commandes réellement écrites, qui dit quoi ajouter :

```bash
sqlite3 tensai.db "SELECT content FROM messages WHERE role='assistant';
                   SELECT coalesce(thinking,'') FROM messages;" > corpus.txt
grep -oE '\\[a-zA-Z]+' corpus.txt | sort | uniq -c | sort -rn
```

Au 2026-09-09, sur 129 Ko : `\times` 20, `\text` 10, `\quad` 2, `\mod` 1. Rien
d'autre. À rejouer après chaque session d'usage réel — c'est ce recensement qui
dit si le vocabulaire dérive, et non l'impression laissée par un écran.

**Piège de mesure rencontré, à ne pas repayer** : le tag logcat de la couche
`rn-*` est **`RNLlama`**, pas `TensaiJNI`. Un filtre `TensaiJNI:V "*:S"` tait
`loadPrompt`, `Input processed` et l'avertissement de §19. Prendre les deux tags.
Et `LOG_VERBOSE` est compilé out (`RNLLAMA_VERBOSE != 1`), donc `prompt ingested`
ne sortira jamais : c'est `Input processed`, en `LOG_INFO`, qu'il faut chercher.
