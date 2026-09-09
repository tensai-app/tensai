# CI

Ce que la CI garantit, ce qu'elle ne garantit pas, et pourquoi. Établi en
rejouant chaque étape à la main depuis un clone neuf : `83c57e0` / sous-module
`5324df1` pour `ci.yml`, `3a248da` / sous-module `a529bda` pour `native.yml`.

Le device de référence n'est disponible que par intermittence. Sans lui, un
build et des tests à chaque commit sont le seul filet qui reste — d'où ces
workflows, et d'où le fait que leurs trous soient documentés ici plutôt que
masqués derrière un job rouge en permanence.

---

## Ce qui tourne

Deux workflows, séparés parce qu'ils n'ont pas le même déclencheur.

### `ci.yml` — job `unit-tests`, sur `push` vers `main` et sur `pull_request`

- **`:app:testDebugUnitTest`** — 409 tests. Le cœur du filet.
- **`:app:compileDebugAndroidTestKotlin`** — compile seulement. L'instrumenté ne
  peut pas s'exécuter en CI (§ suivante), mais au moins ses sources ne pourrissent
  pas en silence.

Le rapport de tests est uploadé en artefact **uniquement en cas d'échec**.

Ce job ne touche pas au natif : il n'en a pas besoin, et un clone neuf n'en était
pas capable. Il tourne donc sur tous les commits, pour une à quatre minutes.

### `native.yml` — job `native-build`, filtré par chemins

- **`:app:assembleDebug`** — le `CMakeLists.txt` de llama.kt, les sources `cpp/`
  vendorées, les shaders SPIR-V générés et `libOpenCL.so` compilés et liés
  ensemble, jusqu'à un APK. C'est la seule chose qu'un job JVM ne pourra jamais
  dire.
- **`:app:lintDebug`** — resté hors CI jusqu'ici uniquement parce qu'il dépend de
  `:llama-kt:buildCMakeDebug`. Mesuré : 0 erreur, 49 avertissements, 3
  informations.

L'APK fait 226 Mo (215 Mio) : il n'est pas uploadé en artefact, le stockage est
facturé sur un dépôt privé et « ça compile » est tout ce qu'on attend d'ici. Seul
le rapport de lint l'est, en cas d'échec.

#### Pourquoi un filtre de chemins

Le dépôt est privé, les minutes sont comptées, et ce job coûte sept à neuf
minutes contre une à quatre pour le job JVM. Sur un commit qui ne touche que du
Kotlin il n'apporte rien : une erreur Kotlin est déjà attrapée par
`:app:testDebugUnitTest`, qui compile la même variante.

Il se déclenche donc sur les seuls chemins de **ce** dépôt qui peuvent bouger le
natif :

| Chemin | Pourquoi |
|---|---|
| `third_party/**` | le pointeur du sous-module `llama.kt`, où vivent le `CMakeLists.txt`, les sources `cpp/` et les SHA de `llama.cpp` et des deux OpenCL |
| `.gitmodules` | l'URL ou le chemin d'un sous-module |
| `gradle/**`, `gradle.properties`, `settings.gradle.kts`, `build.gradle.kts`, `app/build.gradle.kts` | version d'AGP, wrapper Gradle, configuration du module |
| `.github/workflows/native.yml` | le workflow lui-même |

Le `CMakeLists.txt` et la valeur `ndkVersion` vivent **dans le sous-module**, pas
ici : le pointeur `third_party/llama.kt` est le seul signal disponible pour eux,
et il suffit.

S'y ajoutent `workflow_dispatch` et un cron hebdomadaire (lundi 04:17 UTC). Le
cron est le rattrapage : sans lui, un mois sans commit natif voudrait dire un
mois sans savoir si l'image du runner a bougé sous nos pieds.

#### Le cache des shaders

136 fichiers `*.comp.cpp` (202 Mo) plus `ggml-vulkan-shaders.hpp`, régénérés par
la section Vulkan de `bootstrap.sh`. TEN-93 a établi que cette génération est
déterministe : le générateur trie avant d'écrire, il n'y a ni timestamp ni chemin
absolu dans la sortie, et aucun `GGML_VULKAN_*_GLSLC_SUPPORT` n'est passé au
`CMakeLists.txt` de `vulkan-shaders-gen`, donc aucune autodétection des capacités
de l'hôte. Un cache est sain.

**Clé** : `vk-shaders-<SHA llama.cpp>-<version de glslc>`.

Le SHA vient de `git ls-tree HEAD third_party/llama.cpp` dans `llama.kt`, ce qui
le lit dans l'index **sans checkouter les 155 Mo**. C'est ce qui permet de décider
du cache avant de payer : au hit, le sous-module `llama.cpp` n'est jamais cloné.

La version de `glslc` est dans la clé parce que c'est la seule entrée qui fait
varier la sortie : le NDK 27 embarque shaderc v2022.3, les paquets système
v2026.2, et 131 des 136 fichiers diffèrent entre les deux. Du SPIR-V valide dans
les deux cas, mais mélanger deux toolchains dans un même build ne serait pas faux
à l'exécution et serait impossible à diagnostiquer.

`glslc` est pris **dans le NDK**
(`$ANDROID_HOME/ndk/<ver>/shader-tools/linux-x86_64/glslc`) et non dans un paquet
système, pour que sa version soit épinglée par la même chose que le reste de la
toolchain. La version du NDK, elle, est lue dans le `build.gradle.kts` de
`llama.kt` plutôt que recopiée dans le workflow : une seule source de vérité.

Le `restore` et le `save` sont deux étapes distinctes, avec entre les deux une
vérification que le jeu compte bien 136 fichiers. Elle vaut aussi au hit : un
cache tronqué se voit là, pas trois minutes plus tard dans une erreur de link
illisible.

| | Coût |
|---|---|
| Cache au repos | ~21 Mo (202 Mo de hex ASCII, ça se comprime à 10:1) |
| Génération, au miss | ~105 s sur 2 vCPU |
| Sous-module `llama.cpp`, au miss seulement | 155 Mo, ~15 s |

`libOpenCL.so` n'est pas mis en cache : `build-opencl.sh` le produit en deux
secondes.

Ce que le cache **ne** couvre pas, et qui domine le coût du job : la compilation
CMake elle-même, quatre à cinq minutes, refaite à chaque run puisque `.cxx/`
n'est pas conservé.

### Le sous-module, et pourquoi l'URL est réécrite

`.gitmodules` pointe `llama.kt` en SSH (`git@github.com:…`). Un runner n'a pas
de clé pour ça. Les deux workflows réécrivent le schéma au vol :

```bash
git config --global url."https://github.com/".insteadOf "git@github.com:"
git submodule update --init --depth 1 third_party/llama.kt
```

Ça ne touche ni `.gitmodules` ni le dépôt : rien ne change pour un clone local.
`llama.kt` est public, donc pas de PAT. `tensai` est **privé**, mais
`actions/checkout` s'authentifie tout seul avec le `GITHUB_TOKEN` du job. La
réécriture est globale, donc elle vaut aussi pour les sous-modules imbriqués.

**Aucun des deux jobs n'utilise `submodules: recursive`**, et ils n'initialisent
pas la même chose :

| Sous-module | Taille | `unit-tests` | `native-build` |
|---|---|---|---|
| `third_party/llama.kt` | 41 Mo | oui | oui |
| `…/OpenCL-Headers` | 764 Ko | non | oui, `include_directories` du `CMakeLists.txt` |
| `…/OpenCL-ICD-Loader` | 1,2 Mo | non | oui, produit le `libOpenCL.so` contre lequel `llamakt` se lie |
| `…/llama.cpp` | 155 Mo | non | **au miss de cache seulement** |

Mesuré sur un clone neuf : les tests unitaires passent avec `third_party/llama.kt`
seul. Pour le natif, les deux OpenCL sont requis parce que le `CMakeLists.txt` les
nomme, vérifié dans la source et pas supposé.

---

## Ce qui ne tourne pas, et ce qui le débloque

### `assembleDebug` et le lint — débloqués depuis `native.yml`

Gardé ici parce que c'est la trace de pourquoi ce job est ce qu'il est. Les deux
étaient absents parce qu'**un clone neuf ne compilait pas** :

```
ggml-vulkan.cpp:96:10: fatal error: 'ggml-vulkan-shaders.hpp' file not found
```

Deux jeux d'artefacts que le build natif exige sont dans le `.gitignore` de
`llama.kt` et n'existent donc sur aucune machine qui vient de cloner :

| Artefact | Ignoré par | Produit par |
|---|---|---|
| `cpp/ggml-vulkan/shaders/*.comp.cpp` (136 fichiers, 202 Mo) + `ggml-vulkan-shaders.hpp` | règle explicite | `scripts/bootstrap.sh` § Vulkan |
| `jniLibs/arm64-v8a/libOpenCL.so` | `*.so` | `scripts/build-opencl.sh` |

Le lint tombait sur la même erreur : `:app:lintDebug` dépend de
`:llama-kt:buildCMakeDebug`. Il n'était donc pas écarté pour ses résultats mais
parce qu'il ne pouvait pas démarrer.

`native.yml` régénère les deux dans le job, met les shaders en cache et laisse
`build-opencl.sh` refaire le `.so` à chaque run. La procédure à la main est dans
le [README](../README.md#build-from-source), et l'étape « Generate Vulkan
shaders » du workflow en est la transcription : **les deux sont à garder en
phase**, il n'y a pas de script partagé qui l'imposerait.

Rien n'est committé : les 202 Mo de sources générées restent hors de
l'historique.

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
   attache l'APK à la release. Il aura besoin des mêmes prérequis natifs que
   `native.yml` : reprendre ses étapes jusqu'au cache des shaders inclus.

Sauvegarder le keystore ailleurs. Perdu, il interdit toute mise à jour d'une app
déjà publiée.

---

## Coût, mesuré et estimé

Mesuré ici, sur un clone neuf, en bornant les cœurs avec `taskset` :

| Étape | 22 cœurs | 4 cœurs | 2 cœurs |
|---|---|---|---|
| Génération des shaders | 46 s | — | 105 s |
| `assembleDebug`, `.cxx/` vide | 3 min 31 | 4 min 12 | 4 min 51 |
| `lintDebug`, après `assembleDebug` | 48 s | — | 57 s |

Le natif est dominé par le link LTO, qui est séquentiel : passer de 4 à 2 vCPU ne
coûte que 40 secondes. C'est la bonne nouvelle de la mesure, parce qu'un dépôt
privé n'a droit qu'au runner 2 vCPU.

Estimé d'abord à ~9 min au miss et ~7 min au hit, à partir des mesures locales
ci-dessus. **Le premier run réel a démenti cette estimation d'un facteur deux**,
et voici ce qu'il a coûté (run du 2026-09-09 sur `931fcfd`, miss de cache) :

| Étape | Durée |
|---|---|
| checkout + sous-modules OpenCL | 49 s |
| init du sous-module llama.cpp | 34 s |
| génération des shaders | 2 min 54 |
| cache, `libOpenCL.so`, java, `setup-gradle` | 16 s |
| **`assembleDebug`** | **11 min 38** |
| `lintDebug` | 1 min 58 |
| démontage | 35 s |
| **Total** | **18 min 19** |

Le `taskset -c 0,1` des mesures locales borne le **nombre** de cœurs mais pas
leur vitesse : ce sont deux cœurs rapides d'un CPU de bureau, avec beaucoup de
RAM et un disque véloce. Deux vCPU GitHub sont bien plus lents par cœur. L'écart
est un facteur 2,4 sur la compilation, et il ne se voyait pas autrement qu'en
lançant le job pour de vrai.

Ordre de grandeur à retenir : **~18 min au miss, ~15 min au hit** (le hit
économise la génération des shaders et l'init du sous-module de 155 Mo, soit
environ 3 min 30).

Le cache pèse donc trois minutes et demie sur dix-huit. Comme prévu, ce n'est pas
lui qui rend le job abordable, c'est le filtre de chemins — et c'est encore plus
vrai qu'estimé.

Combien de fois il se déclencherait, compté sur l'historique réel (80 commits du
2026-08-03 au 2026-09-09) :

| | Commits |
|---|---|
| Total | 80 |
| Déclencheraient `native.yml` | 24 |
| dont bumps du sous-module `llama.kt` | 13 |
| dont uniquement `app/build.gradle.kts` ou `gradle/**` | 9 |

Soit **une vingtaine de runs par mois** au rythme actuel du dépôt. Aux 15 minutes
mesurées par run au hit, ça donne **~300 min/mois**, plus ~60 pour le cron : de
l'ordre de **360 minutes**, soit **18 %** d'un quota de 2000. Le double si le
travail passe par des PR, chaque changement étant alors vu deux fois — on
approche alors des 40 %. `cancel-in-progress` limite la casse sur les pushes
rapprochés.

Ce chiffre a été annoncé à 200 minutes avant le premier run réel, sur la foi des
mesures locales. Il est faux d'un facteur deux pour la raison expliquée plus
haut, et c'est celui-ci qui fait foi.

Les 13 bumps de sous-module sont le vrai signal, et il n'est pas théorique :
TEN-91 et TEN-92 ont tous les deux modifié `tensai_jni.cpp`, qui vit dans
`llama.kt`, et sont arrivés ici comme des bumps de gitlink. Les 9 autres sont des
montées de version de dépendances Kotlin, qui ne peuvent pas casser le natif.
Elles restent dans le filtre parce que le même fichier porte la version d'AGP et
du wrapper Gradle, qui, elles, décident de la façon dont le natif est invoqué et
empaqueté, et qu'aucun chemin ne permet de distinguer les deux cas.

Si le quota devient un problème, dans l'ordre : retirer `app/build.gradle.kts` et
`gradle/**` du filtre, ce qui enlève 38 % des runs, environ **110 minutes par
mois**, au prix d'une régression AGP vue une semaine plus tard par le cron ;
puis espacer le cron. Pas le cache, qui ne pèse que trois minutes et demie sur
dix-huit.

Un levier plus gros existe et n'est pas exploré ici : **`.cxx/` n'est pas mis en
cache**, donc la compilation CMake repart de zéro à chaque run, et c'est elle
qui coûte les onze minutes. La mettre en cache demanderait une clé qui capture
tout ce dont l'objet dépend — sources vendorées, drapeaux, version du NDK — et
un cache faux à cet endroit produirait un binaire incohérent plutôt qu'un échec
visible. À ne tenter que si le quota le force, et avec un ticket à part.

---

## Ce qui reste invérifiable hors CI

Rejoué à la main depuis un clone neuf, dans l'ordre du workflow et en exécutant
ses propres blocs `run:` : réécriture d'URL, init sélectif des sous-modules,
résolution du NDK et de `glslc`, lecture du SHA `llama.cpp` par `ls-tree`,
génération des shaders, `build-opencl.sh`, `assembleDebug`, `lintDebug`. Les deux
chemins, miss et hit, avec le cache simulé par une archive. `actionlint` et
`shellcheck` sont passés sur les deux workflows.

Pas rejouable ici, et donc à surveiller au premier run réel :

- **Le comportement réel du cache Actions** : la simulation est une archive
  locale, pas le service.
- **Le filtre de chemins sur un pointeur de sous-module.** `third_party/**` est
  écrit large exprès, mais qu'un changement de gitlink apparaisse bien dans la
  liste des fichiers modifiés d'un push n'est pas vérifiable d'ici.
- **Les versions du runner `ubuntu-latest`** : présence du NDK dans l'image (le
  workflow l'installe si besoin, ce qui ajoute une à deux minutes), version de
  `cmake` et de `g++`.
- **Le temps de build sur le runner**, dont les chiffres ci-dessus sont une
  extrapolation depuis une machine locale bridée.

Le badge du README ne s'affiche que pour quelqu'un d'authentifié ayant accès au
dépôt, tant que `tensai` est privé.
