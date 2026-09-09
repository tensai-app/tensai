# CI

Ce que `.github/workflows/ci.yml` garantit, ce qu'il ne garantit pas, et
pourquoi. Établi en rejouant chaque étape à la main depuis un clone neuf, sur
`83c57e0` / sous-module `5324df1`.

Le device de référence n'est disponible que par intermittence. Sans lui, un
build et des tests à chaque commit sont le seul filet qui reste — d'où ce
workflow, et d'où le fait que ses trous soient documentés ici plutôt que
masqués derrière un job rouge en permanence.

---

## Ce qui tourne

Job unique `unit-tests`, sur `push` vers `main` et sur `pull_request` :

- **`:app:testDebugUnitTest`** — 409 tests. Le cœur du filet.
- **`:app:compileDebugAndroidTestKotlin`** — compile seulement. L'instrumenté ne
  peut pas s'exécuter en CI (§ suivante), mais au moins ses sources ne pourrissent
  pas en silence.

Le rapport de tests est uploadé en artefact **uniquement en cas d'échec**.

### Le sous-module, et pourquoi l'URL est réécrite

`.gitmodules` pointe `llama.kt` en SSH (`git@github.com:…`). Un runner n'a pas
de clé pour ça. Le workflow réécrit le schéma au vol :

```bash
git config --global url."https://github.com/".insteadOf "git@github.com:"
git submodule update --init --depth 1 third_party/llama.kt
```

Ça ne touche ni `.gitmodules` ni le dépôt : rien ne change pour un clone local.
`llama.kt` est public, donc pas de PAT. `tensai` est **privé**, mais
`actions/checkout` s'authentifie tout seul avec le `GITHUB_TOKEN` du job.

**Un seul sous-module est initialisé**, et volontairement pas en `recursive`.
Mesuré sur un clone neuf : les tests unitaires passent avec `third_party/llama.kt`
seul, sans aucun des trois sous-modules imbriqués (`llama.cpp` à 155 Mo,
`OpenCL-Headers`, `OpenCL-ICD-Loader`). `submodules: recursive` tirerait 155 Mo
par run pour rien.

---

## Ce qui ne tourne pas, et ce qui le débloque

### `assembleDebug` et le lint — bloqués par les artefacts générés

Les deux sont absents du workflow parce qu'**un clone neuf ne compile pas**.
Vérifié, ce n'est pas une hypothèse :

```
ggml-vulkan.cpp:96:10: fatal error: 'ggml-vulkan-shaders.hpp' file not found
```

Deux jeux d'artefacts que le build natif exige sont dans le `.gitignore` de
`llama.kt` et n'existent donc sur aucune machine qui vient de cloner :

| Artefact | Ignoré par | Produit par |
|---|---|---|
| `cpp/ggml-vulkan/shaders/*.comp.cpp` (136 fichiers, 202 Mo) + `ggml-vulkan-shaders.hpp` | règle explicite | `scripts/bootstrap.sh` § Vulkan |
| `jniLibs/arm64-v8a/libOpenCL.so` | `*.so` | `scripts/build-opencl.sh` |

Le lint tombe sur la même erreur : `:app:lintDebug` dépend de
`:llama-kt:buildCMakeDebug`. Il n'est donc pas écarté pour ses résultats — une
fois le natif disponible, il **passe** (0 erreur, 49 avertissements, 3
informations) — mais parce qu'il ne peut pas démarrer.

Preuve que c'est bien la seule cause : en recopiant ces deux artefacts dans le
clone neuf, sans rien d'autre, `:app:assembleDebug` et `:app:lintDebug`
réussissent tous les deux.

**Ce qu'il faudrait pour débloquer**, par ordre de coût :

1. Committer les artefacts dans `llama.kt` — 202 Mo de sources générées dans
   l'historique. Non.
2. Un job CI qui les régénère. La partie Vulkan de `bootstrap.sh` est
   **reproductible** : elle ne lit que le sous-module `third_party/llama.cpp`,
   épinglé par SHA, et n'a rien à voir avec le `git clone --depth 1` non épinglé
   de `mybigday/llama.rn` dans `/tmp` que TEN-87 pointe (celui-là ne sert qu'à la
   glue `rn-*`, déjà vendorée). Il faut le sous-module `llama.cpp` (155 Mo),
   `glslc`, `cmake`, `g++`, plus le sous-module `OpenCL-ICD-Loader` et
   `build-opencl.sh` pour le `.so`. Puis mettre tout ça en cache, sinon chaque run
   repart de zéro.

Tant que ce n'est pas fait, la compilation du natif n'est vérifiée que
localement, à la main.

### Tests instrumentés — hors CI, définitivement

Pas de runner ARM. Et surtout : **jamais** `./gradlew connectedDebugAndroidTest`
ni `connectedCheck`, où que ce soit, même désactivé ou commenté. AGP désinstalle
l'app au nettoyage et **efface les modèles**, plusieurs Go sur le téléphone de
l'utilisateur.

Le lancement correct, à la main, sous wrapper hôte, avec le **code de sortie 124
traité comme un échec** — trois mécanismes de timeout internes au process se sont
révélés muets là-dessus (TEN-55) :

```bash
timeout 300 adb shell am instrument -w \
  io.github.hokanosekai.tensai.test/androidx.test.runner.AndroidJUnitRunner
```

Détails et suites à faire tourner : `docs/device-qa-queue.md` §10.

### Release signée sur tag — à mettre en place par l'utilisateur

Pas de workflow livré : il échouerait faute de secrets, que seul l'utilisateur
peut créer. Ce qu'il faudra, dans l'ordre :

1. Générer le keystore, **hors du dépôt** :
   ```bash
   keytool -genkeypair -v -keystore tensai-release.jks \
     -keyalg RSA -keysize 4096 -validity 10000 -alias tensai
   ```
2. Créer quatre secrets de dépôt (Settings → Secrets and variables → Actions) :

   | Secret | Contenu |
   |---|---|
   | `KEYSTORE_BASE64` | `base64 -w0 tensai-release.jks` |
   | `KEYSTORE_PASSWORD` | mot de passe du keystore |
   | `KEY_ALIAS` | `tensai` |
   | `KEY_PASSWORD` | mot de passe de la clé |

3. Ajouter un `signingConfigs` release dans `app/build.gradle.kts`, alimenté par
   des variables d'environnement, sans valeur en dur.
4. Le workflow décode le keystore dans le workspace, lance `:app:assembleRelease`,
   attache l'APK à la release. **Il dépend de `assembleDebug`**, donc du blocage
   ci-dessus : inutile de le brancher avant.

Sauvegarder le keystore ailleurs. Perdu, il interdit toute mise à jour d'une app
déjà publiée.

---

## Ce qui reste invérifiable hors CI

Rejoué à la main depuis un clone neuf : la réécriture d'URL, l'init sélectif des
sous-modules, les deux commandes Gradle. Pas rejouable ici, et donc à surveiller
au premier run réel : le cache Actions, les versions exactes du runner
`ubuntu-latest`, et le temps de build.

Le badge du README ne s'affiche que pour quelqu'un d'authentifié ayant accès au
dépôt, tant que `tensai` est privé.
