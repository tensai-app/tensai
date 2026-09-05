# Audit du moteur d'inférence — 2026-09-05

Portée : du JNI (`tensai_jni.cpp`) aux consommateurs (`ChatViewModel`, `SessionRepository`),
sur `baecdf9` / sous-module `02d1370`. Aucune mesure sur device : tout ce qui suit
est établi par lecture.

## Ce que vaut cet audit

54 constats produits, puis soumis à des vérificateurs adverses dont la consigne
était de **réfuter**, pas de confirmer.

| | Nombre |
|---|---|
| Confirmés après réfutation | 28 |
| Réfutés — à ne pas rouvrir | 10 |
| **Non vérifiés** (limite de session atteinte) | **16** |

Les 16 non vérifiés sont **tous dans la couche C++/JNI**, plus deux dans
`LlamaKtEngineImpl` et deux dans `LlamaEngine.kt`. C'est donc la couche la moins
fiable de ce rapport, et c'est aussi celle où une erreur coûte le plus cher. Ils
sont listés en fin de document avec ce statut, pas mélangés aux autres.

La synthèse automatique n'a jamais tourné : ce document est écrit à la main
depuis le journal du workflow.

## Le verdict

Le moteur lui-même est correct : le décodage, le parsing de chat et le budget de
raisonnement font ce qu'ils annoncent. **Le risque n'est pas dans l'inférence, il
est autour d'elle** — dans l'identité des modèles, la persistance des sessions, et
la fidélité de ce qui est rapporté à l'utilisateur.

Trois familles de défauts reviennent :

1. **Un modèle est identifié par son nom d'affichage** plutôt que par son id, à
   quatre endroits. C'est ce qui permet de supprimer un `.gguf` en cours
   d'utilisation.
2. **La sauvegarde de session ne se déclenche jamais dans les cas où elle
   compte** : quitter l'app, la mettre en arrière-plan, la balayer des Recents.
   Le gain de 23,5x sur le TTFT que le code documente ne se matérialise pas.
3. **Les raisons d'arrêt mentent**, par trois chemins indépendants. Une réponse
   tronquée se relit comme une réponse terminée, et repart telle quelle dans le
   prompt suivant.

## Bloquants confirmés

### `RuntimeGraph.kt:164` — supprimer un modèle pendant qu'il génère

Le garde « modèle en cours d'utilisation » de `deleteModel` lit
`state.value as? QueueState.ModelReady`, qui rend `null` pendant `Generating`.
Pendant qu'une réponse streame, l'écran Models n'affiche aucune puce « loaded »,
la suppression n'est pas bloquée, le `.gguf` de plusieurs Go est unlink et sa
ligne Room supprimée — alors que llama.cpp le mmappe encore.

Perte irréversible, sans avertissement. Le correctif est une substitution :
`state.value.residentModel?.name`, l'extension existe déjà. Trois autres sites ont
le même `as?` : `ModelsScreen.kt:80`, `TensaiDrawerContent.kt:71` (un « New chat »
pendant une génération crée une conversation à `modelId` null).

### `LoadPolicy.kt:68` — le chat muet

`LoadPolicy` budgète sur `availMem` et retombe sur un plancher de 256 tokens.
Sur un appareil où `availMem` est inférieur à la taille du modèle — cas courant
pour un 4B+ Q4 sur 8 Go — le modèle charge, le chat s'ouvre, et chaque envoi rend
un tour assistant vide immédiatement supprimé (TEN-64). **L'utilisateur voit une
app qui ne répond jamais, sans un seul message d'erreur.**

`CompatibilityChecker` et `LoadPolicy` ne budgètent pas sur la même base mémoire.
Un plan doit pouvoir échouer : sous un minimum utilisable, rendre un
`LoadPlan.Infeasible` que `LoadModelViewModel` traite comme `Blocked`.

### `InferenceQueue.kt:293` — un chargement annulé fuit le modèle entier *(latent)*

`engine.loadModel()` et les trois affectations qui l'enregistrent ne sont pas sous
`NonCancellable`, contrairement au `free` de la ligne 333. Le jour où un appelant
de `loadModel` devient annulable, une annulation en cours de chargement fait fuir
plusieurs Go sans aucune trace, la file se croyant `Idle`. L'utilisateur retente,
le second chargement s'ajoute au premier.

Latent aujourd'hui — tout tourne sur `applicationScope` — mais c'est exactement le
motif qui a produit trois tickets aujourd'hui (TEN-53, TEN-73, TEN-74).

## Sérieux confirmés, par famille

### Identité des modèles

| Site | Défaut |
|---|---|
| `ChatViewModel.kt:452` | `resolveSessionKey` retrouve le modèle par nom d'affichage sur une table triée `importedAt DESC`. Deux quantisations du même modèle (garder le Q4 et le Q8 est le cas normal) font pointer la clé de session sur le mauvais `modelId`, et le contrôle anti-mélange de `restore` — dont c'est la raison d'être entière — passe entre les deux fichiers |

Le correctif est le même que pour le bloquant ci-dessus : faire porter l'id du
`ModelEntity` par `LoadedModelInfo`, que `LoadModelViewModel` connaît déjà.

### Persistance de session — trois chemins, aucun ne sauvegarde

| Site | Défaut |
|---|---|
| `MainActivity.kt:86` | La sauvegarde tourne sur `lifecycleScope` : elle meurt avec l'Activity. Sortir par le bouton retour ne persiste jamais le KV cache |
| `InferenceService.kt:89` | `onTaskRemoved` appelle `stopSelf()` **avant** l'unload qu'il vient de lancer, retirant la protection foreground pendant l'écriture |
| `InferenceQueue.kt:507` | `saveSession` rend `-1` quand l'état n'est pas `ModelReady`, donc mettre l'app en arrière-plan pendant une génération ne sauvegarde jamais — et le même `-1` devient `SaveResult.Failed`, que `ChatViewModel` lit comme « l'état sortant n'est pas en sécurité » |

Le scénario le plus courant du produit — lancer une longue réponse et poser le
téléphone — est exactement celui où la persistance ne s'active pas.

### `SessionRepository.kt:343` — la restauration détruit sur un état transitoire

`restore` efface fichier **et** ligne dès que `restoreOnEngine` rend `false`, alors
que `InferenceQueue.restoreSession` rend `false` pour « la file n'était pas
prête », qui ne dit rien de la session. Un chargement concurrent suffit à détruire
une session valide de plusieurs centaines de Mo.

Variante plus grave : si `loadModel` gagne la course, l'état redevient `ModelReady`
avec un **autre** modèle, et `restoreSession` ne revalide rien — le contrôle de
compatibilité a été fait avant, contre l'ancien modèle. `loadSession` reçoit alors
l'état KV d'un modèle différent.

Suite directe de TEN-73 : le même `false` sert à dire deux choses.

### Les raisons d'arrêt mentent

| Site | Défaut |
|---|---|
| `LlamaKtEngineImpl.kt:397` | `tokenCount` compte les invocations du callback, pas les tokens échantillonnés. Un décode coupé au budget est rapporté `Complete`. Se déclenche dès qu'un caractère non-ASCII est scindé sur deux tokens — **les accents français suffisent**. C'est le bug que TEN-60 prétend avoir corrigé |
| `LlamaKtEngineImpl.kt:337` | Le clipping de contexte (TEN-65) est rapporté comme un dépassement de budget de tokens. L'utilisateur augmente `maxTokens`, ce qui ne change rien, et aucune bannière ne mentionne la fenêtre de contexte |
| `ChatViewModel.kt:607` | `stopRequested` est un champ de ViewModel alors que `cancelActive()` est global. Arrêter depuis une autre conversation persiste la génération sans `stopReason` : elle se relit comme une réponse complète et repart dans tous les prompts suivants |

### Annulation perdue avant `rewind()`

`LlamaKtEngineImpl.kt:384` et `tensai_jni.cpp:800` : un `stop()` qui arrive entre
le lancement du décodage et `comp->rewind()` est effacé. Le bouton ne fait rien de
visible, la génération va jusqu'au bout, et un chargement lancé derrière attend des
minutes sur un `join()`. Aucune retentative n'aide, l'unique interrupt a été
consommé.

Correctif côté Kotlin : `coroutineContext.ensureActive()` juste avant
`llamaEngine.completion(...)`. Le côté natif serait une modification vendored, donc
une entrée `patches/`.

### Divers confirmés

| Site | Défaut |
|---|---|
| `LoadPolicy.kt:74` | `nCtx` dérive d'`availMem`, donc bouge d'un chargement à l'autre : toute session devient `Mismatch` et n'est jamais restaurée. Le gain de 23,5x documenté ne se matérialise jamais |
| `ChatViewModel.kt:639` | Le bandeau d'erreur n'est jamais remis à zéro : il s'affiche au-dessus d'une réponse réussie, et son bouton Retry la détruit |
| `InferenceQueue.kt:227` | `loadModel` tue une génération en cours pour un chargement qui va immédiatement no-op via le dedup |
| `CompatibilityChecker.kt:110` | Verdict identique CPU et GPU alors que sa propre thèse est que le plafond GPU est plus strict |
| `InferenceQueue.kt:331` | `releaseLoadedLocked` protège le `free` mais pas la sauvegarde qui doit le précéder *(latent)* |
| `tensai_jni.cpp:489` | Allocation d'après un champ d'en-tête non validé : fuite de `gctx` et abort du process sur un GGUF corrompu |
| `SessionRepository.kt:228` | Le raccourci `Unchanged` ne compare qu'un nombre de tokens : après un regenerate de même longueur, la session sur disque reste l'ancienne |
| `LlamaKtEngineImpl.kt:217` | `readMetadata` est sérialisé derrière l'inférence sur le dispatcher mono-thread alors qu'il ne touche aucun état du moteur |
| `InferenceQueue.kt:275` | `performLoadLocked` bascule en `Loading` avant le hook de swap, ce qui aveugle le court-circuit « rien n'a changé » |
| `LlamaKtEngineImpl.kt:388` | `tokPerSec` divise les tokens générés par le temps total format+tokenize+prefill+génération |
| `LlamaKtEngineImpl.kt:414` | Le contrat « exactement un événement terminal » est rompu par jet d'exception, pas par double émission *(latent)* |
| `tensai_jni.cpp:836` | `endCompletion()` n'est jamais appelé : `is_predicting` reste vrai à vie après la première génération *(latent)* |
| `DeviceProfiler.kt:47` | `profile()` est appelé en composition, sur le thread principal, une fois par carte et par recomposition *(device)* |

## Ce qu'on n'utilise pas

C'était l'axe le plus attendu. Résultat en demi-teinte : **quatre des huit
capacités inventoriées ont été réfutées** comme non câblables ou déjà closes en
amont — voir la section suivante.

Ce qui reste, et qui demande une mesure sur device pour chiffrer le gain :

| Capacité | État | Enjeu estimé, non mesuré |
|---|---|---|
| `n_batch` / `n_ubatch` (`tensai_jni.cpp:658`) | Jamais exposés, garde mort dans le JNI, budget de 300 Mo codé en dur en conséquence | Un `n_ubatch` à 128 diviserait grossièrement ce budget par quatre. C'est la différence entre « ce modèle ne se charge pas » et « il se charge un peu plus lentement » |
| Affinité CPU (`tensai_jni.cpp:664`) | Les 2 threads gros cœurs ne sont pinnés sur rien, le threadpool ggml n'est jamais attaché | Le profil big.LITTLE du Dimensity 900 est déjà détecté et exploité côté threads, pas côté placement |
| Contrôle de répétition (`tensai_jni.cpp:790`) | `repeat`/`frequency`/`presence` penalty, DRY, XTC et la **seed** jamais écrits | Pas de perf : qualité de sortie, et reproductibilité impossible sans seed |
| Flash attention (`LlamaKtEngineImpl.kt:151`) | Traverse tout le wrapper, l'app ne le passe jamais : `AUTO` est subi, pas choisi | Touche directement la forme du cache V, donc la comptabilité de TEN-18/TEN-72 |
| Multimodal mtmd (`tensai_jni.cpp:823`) | Entièrement compilé, zéro binding, chemin média câblé à vide en dur | Fonctionnalité, pas optimisation |
| Réutilisation du cache KV (`LlamaKtEngineImpl.kt:351`) | Le taux existe côté natif, jamais échantillonné : **un re-prefill complet est invisible** *(non vérifié)* | Sans cette mesure, impossible de savoir si les sessions servent |

## Réfutés — ne pas rouvrir

Ces dix constats paraissent justes et ne le sont pas. Ils sont listés pour éviter
qu'un audit ultérieur les redécouvre.

- `tensai_jni.cpp:657` — context shift
- `tensai_jni.cpp:663` — pool de threads prefill
- `tensai_jni.cpp:1023` — sortie structurée / grammaires
- `rn-completion.cpp:510` — décodage spéculatif n-gram
- `rn-completion.cpp:779` — embeddings, pooling, rerank
- `LlamaKtEngineImpl.kt:242` — offload GPU partiel
- `CompatibilityChecker.kt:92` — `bench()` non bindé
- `KvCacheShapeBackfill.kt:79` — annulation avalée
- `ReplyStreamSplitter.kt:42` — régression du split vers content vide
- `TensaiApp.kt:29` — notification par tenseur chargé

## Non vérifiés — statut inconnu

La limite de session a coupé la vérification adverse de ces 16 constats. **Aucun
n'a été confirmé ni réfuté.** Ils sont plausibles et non fiables ; les traiter
comme des pistes, pas comme des défauts.

Un bloquant présumé s'y trouve :

- `tensai_jni.cpp:595` — sérialisation de chaînes GGUF non validées ; `j.dump()`
  lève, l'exception traverse la frontière JNI et tue le process

Les autres :

| Site | Sévérité annoncée |
|---|---|
| `LlamaEngine.kt:178` — `load()` écrase `handle` sans libérer le contexte précédent | sérieux |
| `tensai_jni.cpp:1068` — `nativeFormatChat` rend une chaîne vide indiscernable d'un prompt valide | sérieux |
| `tensai_jni.cpp:1190` — `nativeSaveSession` écrit `embd.size()` contre un KV qui n'en contient que `n_past` | sérieux |
| `tensai_jni.cpp:521` — la dérivation KV ignore `n_layer_kv_from_start` pour gemma3n : 35 couches facturées au lieu de 20 | sérieux |
| `tensai_jni.cpp:920` — ni `context_full` ni `has_next_token` résiduel n'est lu | sérieux |
| `tensai_jni.cpp:905` — aucun `ExceptionCheck` après `CallVoidMethod` dans la boucle de tokens | sérieux |
| `tensai_jni.cpp:800` — annulation effacée avant `rewind()` | sérieux |
| `tensai_jni.cpp:790` — aucun contrôle de répétition | sérieux |
| `tensai_jni.cpp:664` — aucune affinité CPU | sérieux |
| `LlamaKtEngineImpl.kt:351` — taux de réutilisation KV jamais échantillonné | sérieux |
| `LlamaEngine.kt:109` — `handle` non volatile sans garde de durée de vie | mineur |
| `LlamaKtEngineImpl.kt:151` — second `loadModel` sur la même instance | mineur |
| `tensai_jni.cpp:823` — multimodal non bindé | mineur |
| `tensai_jni.cpp:867` — `parseChatOutput` re-désérialise l'arène PEG par token | mineur |
| `llama.h:360` — défragmentation KV : rien à câbler | mineur |

`tensai_jni.cpp:521` mérite une attention particulière : s'il est juste, il
contredit la dérivation que TEN-18 et TEN-72 viennent de livrer, sur gemma3n
précisément.

## Ce qu'il faudrait pour trancher le reste

- **Un device.** Tout le volet perf est une estimation. Aucun chiffre de ce
  document n'est mesuré, à l'exception de ceux déjà consignés dans
  `docs/device-qa-queue.md`.
- **Une seconde passe de vérification** sur les 16 constats non vérifiés,
  prioritairement `tensai_jni.cpp:595` et `:521`.
- Le critique de complétude n'a pas tourné non plus : **personne n'a vérifié ce
  que cet audit lui-même a manqué.**
