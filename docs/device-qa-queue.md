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
  Pour l'instrumenté, passer par `am instrument` sous wrapper hôte (voir §8).
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
  adb shell "run-as io.github.hokanosekai.tensai cat databases/$f" > "$f"
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

L'arrêt volontaire est vérifié : `stopReason` null, aucun chip, thinking conservé.
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

## 7. TEN-68 — les 350 s après une longue génération

Observé **une fois**, jamais reproduit sur la tentative suivante à volume
identique. Restore de 2879 tokens rendu en 780 ms, puis 350 758 ms de TTFT.

Deux contrôles écartent déjà le restore et la re-tokenisation seule :
cache vivant sans switch = 8 120 ms, même volume au tour d'après = 7 559 ms.

Protocole : longue génération, puis tour suivant, **avec et sans switch de
conversation**, plusieurs fois. Instrumenter `find_common_prefix_length` pour
logger le `n_past` retenu contre `embd.size()` — un seul log dirait si le préfixe
s'effondre et où.

---

## 8. Tests instrumentés

**Toujours sous wrapper hôte**, et traiter le code de sortie **124 comme un échec** :

```bash
timeout 300 adb shell am instrument -w \
  io.github.hokanosekai.tensai.test/androidx.test.runner.AndroidJUnitRunner
```

À faire tourner :

- **`TensaiDbMigrationTest`** — jamais exécuté depuis la migration v6 → v7.
  Son KDoc dit « all the way to the current version » mais le test s'arrête à la
  v4 : à passer à 7 pendant qu'on y est.
- **TEN-55**, le gel de la suite complète. Intermittent, **2 runs sur 8**, toujours
  sur `InferenceQueueTest.loadThenGenerate`, repéré via le tag `TestRunner`
  (`started:` sans `finished:`). Aucun mécanisme in-process ne rend de verdict :
  `withTimeout`, règle JUnit et `kill -3` sont restés muets avec `utime` **et**
  `stime` strictement plats. Le runtime ART entier gèle. Compter 8 runs pour
  caractériser quoi que ce soit.

---

## 9. TEN-43 — QA device pass V1

Le ticket de QA globale. À faire en dernier, une fois la phase 4 livrée.

---

## Ce qui ne demande pas de device

Pour mémoire, afin de ne pas bloquer par erreur : TEN-71, TEN-69 (le cas
Qwen3-sans-raisonnement est unitaire), TEN-66, TEN-53, TEN-54, TEN-70, la phase 4
(TEN-35 à 40) et la phase 5 hors TEN-43/44.

**TEN-42 (CI/CD) monte en priorité** tant qu'il n'y a pas de device : un build et
des tests automatisés à chaque commit sont le seul filet qui reste.
