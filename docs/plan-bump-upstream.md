# Plan du bump llama.cpp + llama.rn

Établi le 2026-09-09. Cible de TEN-13, préalable possible de TEN-88.

Ce document existe parce que le bump n'est plus une opération sur un sous-module :
depuis TEN-87, les deux amonts sont couplés et doivent bouger ensemble.

---

## Ce que le bump apporte

| | |
|---|---|
| **TEN-13** | récupère le fix upstream `#25245` et permet de dropper `patches/0001` |
| **TEN-88** | rien : les templates de Gemma-4 et Qwen3 retirent le raisonnement des tours passés (vérifié, voir plus bas) |
| Général | 213 commits de llama.cpp, dont les corrections de la période |

---

## Faits vérifiés

Tout ce qui suit a été mesuré le 2026-09-09, pas supposé.

**Notre état actuel :**

```
third_party/llama.cpp        c926ad098   2026-06-23
scripts/llama.rn.rev         c96ec2d36   llama.rn v0.12.5, 2026-06-24
```

Les deux coïncident : llama.rn v0.12.5 épingle exactement `c926ad098`. C'est
ce couplage qui interdit de bumper l'un sans l'autre, les patches hérités de
`scripts/patches` étant écrits contre le llama.cpp de llama.rn.

**Le fix de TEN-13 :** PR `ggml-org/llama.cpp#25245`, mergée le 2026-07-06,
commit `86961efd5675279a4ec2f3d1a3c7d6e3803c1935`. Notre pin lui est **antérieur
de deux semaines**, ce qui est exactement pourquoi `patches/0001` s'applique
encore.

**Les candidats, avec le llama.cpp qu'ils épinglent :**

| llama.rn | date | llama.cpp épinglé | date | saut depuis notre pin |
|---|---|---|---|---|
| v0.12.6 | 2026-07-13 | `99f3dc322` | 2026-07-12 | **213 commits** |
| v0.12.7 | 2026-07-22 | `ac2557cb2` | 2026-07-16 | — |
| v0.12.8 | 2026-07-28 | `91f8c9c5f` | 2026-07-27 | — |
| v0.12.9 | 2026-08-04 | `6c8dcaa7a` | 2026-08-04 | 487 commits |
| v0.13.0-rc.3 | 2026-09-08 | — | — | release candidate |

**Le fix est bien dans la cible :** `86961efd` est ancêtre de `99f3dc322`, qui
est 98 commits devant lui et 0 derrière. Vérifié par l'API de comparaison, pas
déduit des dates.

---

## Cible recommandée : llama.rn v0.12.6

**Le saut minimal qui contient le fix.** 213 commits contre 487 pour v0.12.9.

La raison n'est pas la prudence en général mais celle-ci en particulier : c'est
le **premier bump sous le régime épinglé** de TEN-87, et `bootstrap.sh` n'a
jamais été exécuté de bout en bout depuis qu'il vérifie ses entrées. Un saut
court laisse moins de chances à `patches/0002` de ne plus s'appliquer et moins de
comportements à revalider sur device.

Rien n'empêche de refaire le geste ensuite vers v0.12.9, une fois qu'on saura
que la chaîne fonctionne. Un bump raté au milieu de 487 commits est beaucoup plus
coûteux à diagnostiquer qu'un bump raté au milieu de 213.

**Pas de release candidate.** v0.13.0-rc.3 est un candidat, pas une release.

---

## Question ouverte, à trancher AVANT de bumper

`preserve_thinking` **n'est pas un drapeau global de llama.cpp.** C'est une
capacité détectée **par template**, dans `common/jinja/caps.cpp` : le moteur
exécute le template du modèle avec un `reasoning_content` factice et regarde s'il
ressort dans le rendu.

```cpp
ctx.set_val("preserve_thinking", mk_val<value_bool>(enabled));
```

Conséquence : **le bump ne débloque TEN-88 que si les templates de Gemma-4 et
Qwen3 rejouent le raisonnement de l'historique.** Ce n'est pas acquis.

Les deux seuls templates du dépôt llama.cpp qui le mentionnent explicitement sont
`LFM2.5-8B-A1B.jinja` et `poolside-Laguna-S-2.1.jinja`, aucun des nôtres.

**Vérification à faire avant, elle est bon marché :** extraire le template de
chaque GGUF du parc (il est dans les métadonnées, `chat_template`) et regarder
s'il émet `reasoning_content` pour les tours antérieurs. Si aucun ne le fait, le
bump reste justifié par TEN-13 seul, et TEN-88 doit chercher ailleurs.

**Ne pas vendre ce bump comme la solution de TEN-88 tant que ce point n'est pas
tranché.**

**Tranché le 2026-10-05 : non.** Templates extraits des GGUF du device.
Qwen3 ne rejoue `reasoning_content` que pour les messages assistant situés après
le dernier vrai message utilisateur (`loop.index0 > ns.last_query_index`), Gemma-4
que sur un tour d'appel d'outil après le dernier message utilisateur. Un tour de
conversation antérieur est rendu sans raisonnement, par construction, et la
détection de `caps.cpp` répondrait faux pour les deux. **Le bump se justifie par
TEN-13 seul.**

---

## La séquence

Chaque étape est vérifiable, et l'ordre compte.

### 0. Prérequis

- Device disponible : les étapes 7 et 8 en dépendent.
- `glslc`, `cmake`, `g++` présents (vérifiés le 2026-09-09).
- Arbre propre, CI verte, et une branche dédiée.

### 1. Bumper les deux pins ensemble

```bash
cd third_party/llama.kt
git -C third_party/llama.cpp fetch origin
git -C third_party/llama.cpp checkout 99f3dc32296f825fec94f202da1e9fede1e78cf9
# et dans scripts/llama.rn.rev, le sha de llama.rn v0.12.6
```

Le SHA de llama.rn v0.12.6 se lit par `gh api repos/mybigday/llama.rn/git/refs/tags/v0.12.6`.

**Commiter le gitlink de llama.cpp dans llama.kt avant l'étape 2.** `bootstrap.sh`
lit le pin voulu dans `HEAD` : un checkout non commité le fait échouer (TEN-103),
alors qu'avant il remettait l'ancien llama.cpp en silence.

### 2. Lancer `bootstrap.sh`

C'est la première exécution complète depuis TEN-87. Le script vérifie désormais
ses entrées et **échoue** au lieu d'avertir :

- pin llama.rn injoignable ou checkout sale sous `cpp/` ou `scripts/patches/` ;
- répertoire de patches hérités absent ;
- patch hérité qui n'applique plus, hors des trois exemptions déclarées ;
- patch local qui n'applique plus.

**Chacun de ces échecs est une information, pas un incident.** Le noter et le
traiter, pas le contourner.

### 3. Re-porter les patches locaux sur les sources vendorées

`0002` et `0004` visent `rn-*` (`0004` : `rn-completion.cpp`), `0005` vise
`common/common.{h,cpp}` (copiés depuis llama.cpp puis réécrits `lm_` et patchés par les
patches hérités avant les nôtres). Ordre d'application : copie, réécriture `lm_`, patches
hérités, puis `patches/*.patch` par ordre alphabétique. Chacun doit appliquer sur le
résultat du précédent.

Détail de `0002` :

Il vise `rn-llama.cpp`, `rn-completion.h` et `rn-completion.cpp`. Si ses hunks ont
bougé entre v0.12.5 et v0.12.6, le regénérer contre les nouvelles sources.

Rappel de TEN-87 : `v0.12.5 + 0002 = cpp/` **byte à byte**. Cette égalité est ce
qui prouve qu'aucun fichier n'a été édité à la main, et elle doit rester vraie
après le bump avec la nouvelle version.

### 4. Dropper `patches/0001` — c'est TEN-13

Le supprimer, retirer son entrée du README de `patches/`, et **vérifier que le
fix est bien présent dans les sources régénérées** plutôt que de se fier au
numéro de commit. Chercher le `CEIL_DIV` 64 bits dans `ggml-vulkan.cpp`.

Si `bootstrap.sh` accepte encore d'appliquer `0001`, c'est que le fix n'est pas
là et que la cible est mauvaise.

### 5. Régénérer les shaders

Script `third_party/llama.kt/scripts/gen-vulkan-shaders.sh <glslc>` (voir le README). La sortie est déterministe pour un
couple (commit llama.cpp, version de glslc) donné, donc les fichiers (132 aujourd'hui) vont
changer, puisque le commit change.

### 6. Vérifications hors device

```
./gradlew :app:testDebugUnitTest      414 tests
./gradlew :app:assembleDebug
./gradlew :app:lintDebug
```

Le job `native.yml` se déclenchera sur le bump de gitlink, donc la CI construira
le natif automatiquement. C'est le premier bump qui bénéficie de ça.

### 7. Revalidation device, obligatoire

Le bump réécrit **tout** `cpp/`, y compris `tensai_jni.cpp`... non : ce fichier
est propre à llama.kt et n'est pas régénéré. Mais tout ce qu'il appelle l'est.

Le travail récent qui doit être revérifié :

| Ticket | Protocole | Pourquoi |
|---|---|---|
| TEN-91 | §16 | la barrière d'exceptions entoure des appels dont le code change |
| TEN-92 | §17, §17 bis | le clamp lit `n_past` de `rn-completion.cpp`, régénéré |
| TEN-85 | §14 | le budget de raisonnement passe par `common/sampling.cpp`, régénéré |
| TEN-18 | §1 | la dérivation KV lit des métadonnées dont le lecteur change |
| — | §19 | le retrait partiel, refaire le grep : c'est du code régénéré |

### 8. Le contrôle qui justifie tout

Charger Gemma-4 avec l'accélération Vulkan et vérifier que **le fix UMA tient
sans notre patch**. C'est l'objet de TEN-13 : si le rendu casse ou si l'assert du
pool se déclenche, le fix upstream ne couvre pas notre cas et `patches/0001` doit
revenir.

---

## Ce qui peut mal tourner, et le signe

| Symptôme | Cause probable | Réaction |
|---|---|---|
| `bootstrap.sh` échoue sur un patch hérité | les sources ont bougé sous lui | lire lequel, décider s'il est encore pertinent |
| `patches/0002`, `0004` ou `0005` n'applique plus | `rn-completion.cpp` ou `common.*` ont changé | le regénérer, ne pas le forcer |
| `assembleDebug` casse au link | API llama.cpp changée | c'est le coût normal d'un bump, lire l'erreur |
| Les shaders ne se régénèrent pas | `vulkan-shaders-gen` a changé d'arguments | corriger `gen-vulkan-shaders.sh` (copie unique, TEN-96) |
| `n_ctx` change sur un modèle | la dérivation KV a bougé | c'est grave, TEN-18 et TEN-72 en dépendent |

---

## Retour en arrière

Le bump vit dans le sous-module plus un pointeur côté parent. Revenir se fait en
remettant le pointeur, sans rien reconstruire, **à condition d'avoir fait le
bump sur une branche**.

Les shaders régénérés ne sont pas commités (gitignorés), donc un retour en
arrière laisse sur le disque des shaders de la mauvaise version. Les régénérer,
ou supprimer `ggml-vulkan/shaders/` et le `.hpp` pour forcer.

---

## Lié

TEN-13 (l'objet), TEN-87 (le couplage des pins et les vérifications de
`bootstrap.sh`), TEN-93 (la procédure de génération des shaders et le job CI
natif), TEN-88 (la question ouverte), TEN-96 (le triplement de la procédure).
