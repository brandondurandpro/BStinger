# Codec de corps : zstd + dictionnaire de code

> État au 30 octobre 2026. Ce document compare le codec de corps que le protocole utilisait (`deflate` niveau 1,
> à partir de 16 Ko) à **zstd avec un dictionnaire entraîné sur du code**, puis décrit ce qui a été retenu et
> implémenté. Le protocole de session — trames, multiplexage, reprise, flux — n'est pas remis en cause : seule la
> compression du corps change.
> Les chiffres bruts sont dans [`report.md`](report.md), la méthode dans [`README.md`](README.md).

## 0. Recommandation

**zstd niveau 3, dictionnaire de 64 Ko, appliqué uniquement aux corps de code (« mode hybride »).**

| Décision | Valeur | Pourquoi |
|---|---|---|
| Algorithme | zstd | Meilleur ratio **et** plus rapide que `deflate` dès qu'il compresse vraiment |
| Niveau | **3** | Le niveau 9 ne gagne que 2 à 8 points pour 4 à 6× plus de CPU |
| Dictionnaire | **64 Ko** générique embarqué, dictionnaire de projet ensuite | 32 Ko perd 0,2 à 0,3 point ; 110 Ko n'apporte rien sur PHP |
| Portée | corps de code : réponses `Read`, requêtes `Write` / `CreateFile` | Sur les métadonnées le dictionnaire est neutre ou nuisible |
| Seuil | essayer dès 64 octets, garder si ≥ 10 % d'économie | Le gain vient des fichiers < 16 Ko, que l'actuel ignore |

Cette recommandation est **implémentée** dans le module [`compression`](../compression) ; le protocole passe en
version 3 et le détail du branchement est au §7.

Gains mesurés sur une session réaliste (médiane, 30 itérations) :

| Session | Actuel | zstd-3 + dict 64 Ko, hybride | Gain | Encodage | Décodage |
|---|---:|---:|---:|---:|---:|
| Kotlin (37 corps) | 193 279 o | **87 521 o** | **−54,7 %** | 1,51 → 1,27 ms | 0,53 → 0,39 ms |
| PHP ERP (36 corps) | 256 343 o | **199 693 o** | **−22,1 %** | 5,42 → 3,20 ms | 2,12 → 0,91 ms |

Sur la lecture de fichiers seule, le gain est bien plus net : **−68,2 %** en Kotlin (109 fichiers) et
**−52,0 %** sur l'échantillon PHP de 200 fichiers.

---

## 1. Pourquoi le codec actuel ne compresse presque rien

Le protocole actuel ne compresse un corps que s'il fait **au moins 16 Ko** et si `deflate` économise au moins
10 %. Or un fichier de code fait en moyenne 5,5 Ko en Kotlin et 8,7 Ko en PHP : le seuil n'est presque jamais
atteint.

| Corpus | Fichiers | Corps compressés par l'actuel | Corps compressés par zstd |
|---|---:|---:|---:|
| Kotlin (ce dépôt) | 109 | **1** | **109** |
| PHP (ERP, jeu de test) | 1 166 | **123** | **1 164** |

C'est là que se trouve l'essentiel du gain : **le seuil de 16 Ko, pas l'algorithme**. zstd-3 sans aucun
dictionnaire passe déjà de 585 131 à 201 493 octets sur Kotlin (−65,6 %) et de 5 152 921 à 2 645 658 sur le
corpus PHP complet (−48,7 %). Le dictionnaire ajoute ensuite 2 à 5 points.

---

## 2. Méthode

- **Corpus Kotlin** : les 109 fichiers `.kt` du dépôt, soit 598 414 octets de corps.
- **Corpus PHP** : `/var/www/dev_new_erp`, 5 833 fichiers `.php` (51 Mo), dont 1 166 servent de jeu de test ;
  200 fichiers échantillonnés uniformément par taille pour les mesures de temps. Fichiers > 1 Mo et
  répertoires ignorés par l'indexeur exclus.
- **Représentation mesurée** : les octets réellement produits par `WireWriter.content`, c'est-à-dire les lignes
  préfixées par leur longueur, plus l'en-tête de trame de 10 octets. Pas le texte brut du fichier.
- **Dictionnaires** : entraînés avec l'algorithme *cover* de zstd (`Zstd.trainFromBuffer`) sur la même
  représentation filaire.
  - génériques, versionnés : `kotlin-generic-*` (679 fichiers de `kotlin-stdlib`, coroutines, serialization, io),
    `php-*` (PEAR dans `/usr/share/php`), `mixed-*` ;
  - projet, non versionnés (`benchmark/dicts-local/`) : 80 % du projet pour l'entraînement, 20 % pour le test.
- **Temps** : médiane de 30 itérations après échauffement JIT, contextes zstd réutilisés (comme le ferait une
  vraie implémentation). Ce n'est pas JMH : compter 10 à 20 % de variance.

---

## 3. Kotlin

### Lecture de fichiers (109 fichiers, 598 Ko de corps)

| Codec | Octets sur le fil | Gain | Ratio | Encodage | Décodage |
|---|---:|---:|---:|---:|---:|
| deflate-1 (actuel) | 585 131 | — | 1,02× | 0,17 ms | 0,07 ms |
| zstd-3 sans dict | 201 493 | −65,6 % | 2,97× | 2,71 ms | 0,87 ms |
| zstd-3 + dict Kotlin 64 Ko | **186 340** | **−68,2 %** | **3,21×** | 2,60 ms | 0,90 ms |
| zstd-9 + dict Kotlin 64 Ko | 168 804 | −71,2 % | 3,55× | 14,81 ms | 0,97 ms |

Les 0,17 ms du codec actuel ne sont pas une victoire de vitesse : elles correspondent au coût de **ne rien
faire** sur 108 fichiers sur 109. zstd compresse 598 Ko en 2,60 ms, soit environ 230 Mo/s, ou ~24 µs par
fichier moyen.

### Où le dictionnaire agit : par taille de corps

| Taille du corps | Fichiers | Actuel | zstd-3 + dict | Gain |
|---|---:|---:|---:|---:|
| 0–2 Ko | 16 | 21 511 | 8 457 | −60,7 % |
| 2–4 Ko | 23 | 70 869 | 24 667 | −65,2 % |
| 4–8 Ko | 54 | 322 229 | 101 591 | −68,5 % |
| 8–16 Ko | 15 | 163 979 | 45 688 | −72,1 % |
| ≥ 16 Ko | 1 | 6 543 | 5 937 | −9,3 % |

Le gain culmine sur les fichiers de 4 à 16 Ko, exactement la zone que le seuil actuel ignore. Le seul
fichier ≥ 16 Ko ne gagne que 9 % : c'est déjà là que `deflate` travaillait.

### Petites charges et métadonnées

| Charge utile | Actuel | zstd-3 sans dict | zstd-3 + dict Kotlin |
|---|---:|---:|---:|
| Petites requêtes/réponses (10 corps) | 2 065 | 1 001 | **977** (−52,7 %) |
| Requêtes de projet (7 corps) | 68 875 | **46 559** (−32,4 %) | 46 691 (−32,2 %) |

Sur les métadonnées (chemins, symboles, diffs, sortie de shell), le dictionnaire de code **n'aide pas** : il
fait même 132 octets de plus ici. D'où le mode hybride.

### Dictionnaire générique contre dictionnaire de projet

Sur les 21 fichiers de test (jamais vus à l'entraînement) :

| Codec | Octets | Gain |
|---|---:|---:|
| deflate-1 (actuel) | 145 118 | — |
| zstd-3 + dict Kotlin générique | 45 010 | −69,0 % |
| zstd-3 + dict projet Kotlin | **38 353** | **−73,6 %** |

Un dictionnaire entraîné sur le projet vaut environ **4,6 points** de plus qu'un dictionnaire générique.

---

## 4. PHP (ERP réel)

### Lecture de fichiers, échantillon de 200 fichiers

| Codec | Octets sur le fil | Gain | Ratio | Encodage | Décodage |
|---|---:|---:|---:|---:|---:|
| deflate-1 (actuel) | 870 142 | — | 1,91× | 7,48 ms | 2,92 ms |
| zstd-3 sans dict | 445 371 | −48,8 % | 3,73× | 6,08 ms | 2,04 ms |
| zstd-3 + dict PHP générique | 439 717 | −49,5 % | 3,78× | 7,21 ms | 1,96 ms |
| zstd-3 + dict PHP projet | **417 409** | **−52,0 %** | **3,98×** | 6,77 ms | 1,93 ms |
| zstd-9 + dict PHP projet | 340 822 | −60,8 % | 4,87× | 36,04 ms | 1,91 ms |

Ici zstd est **plus rapide que l'actuel tout en compressant deux fois plus** : `deflate` passe 7,48 ms à
comprimer 21 fichiers sur 200, zstd en passe 6,77 à en comprimer 199.

### Corpus de test complet (1 166 fichiers, 5,15 Mo sur le fil)

| Codec | Octets sur le fil | Gain | Corps compressés |
|---|---:|---:|---:|
| deflate-1 (actuel) | 5 152 921 | — | 123/1 166 |
| zstd-3 sans dict | 2 645 658 | −48,7 % | 1 149/1 166 |
| zstd-3 + dict PHP générique | 2 532 846 | −50,8 % | 1 164/1 166 |
| zstd-3 + dict PHP projet | **2 387 272** | **−53,7 %** | 1 164/1 166 |
| zstd-9 + dict PHP projet | 2 053 205 | −60,2 % | 1 164/1 166 |

### Session réaliste

| Codec | Octets | Gain | Encodage | Décodage |
|---|---:|---:|---:|---:|
| deflate-1 (actuel) | 256 343 | — | 5,42 ms | 2,12 ms |
| zstd-3 sans dict | 203 537 | −20,6 % | 2,65 ms | 0,87 ms |
| zstd-3 + dict PHP projet partout | 213 028 | −16,9 % | 3,58 ms | 0,87 ms |
| **zstd-3 hybride (dict sur le code)** | **199 693** | **−22,1 %** | 3,20 ms | 0,91 ms |

C'est le résultat le plus important pour PHP : appliquer le dictionnaire **partout** est *moins bon* que ne
pas en mettre du tout (213 028 contre 203 537), parce que la session contient beaucoup de petites
métadonnées. Le mode hybride récupère le gain du code sans payer ce coût.

### Petites charges et requêtes de projet

| Charge utile | Actuel | zstd-3 sans dict | zstd-3 + dict PHP projet |
|---|---:|---:|---:|
| Petites requêtes/réponses (10 corps) | 1 990 | 1 164 | **1 066** (−46,4 %) |
| Requêtes de projet (6 corps) | 36 223 | 17 846 (−50,7 %) | **16 876** (−53,4 %) |

---

## 5. Ce que le dictionnaire apporte, et ce qu'il coûte

### Générique, projet, autre langage

| Corpus | Sans dict | Dict générique | Dict projet | Dict d'une autre langue |
|---|---:|---:|---:|---:|
| Kotlin (109 fichiers) | 201 493 | **186 340** | — | 196 352 (dict PHP) |
| PHP (200 fichiers) | 445 371 | **439 717** | **417 409** | 447 778 (dict Kotlin) |

Deux enseignements :

1. Le dictionnaire doit correspondre au langage : un dictionnaire PHP sur du Kotlin perd 10 Ko du gain, un
   dictionnaire Kotlin sur du PHP est **pire que pas de dictionnaire**.
2. Le dictionnaire de projet reste le meilleur choix, mais il ne se transporte pas : il doit être entraîné
   par projet.

### Taille du dictionnaire

| Taille | Kotlin | PHP (200 fichiers) |
|---|---:|---:|
| 32 Ko | 187 752 | 441 257 |
| **64 Ko** | **186 340** | **439 717** |
| 110 Ko | 185 180 | 441 265 |

**64 Ko** est le bon compromis : 32 Ko perd 0,2 à 0,3 point, 110 Ko n'apporte rien sur PHP et seulement
0,2 point sur Kotlin. C'est aussi 64 Ko à envoyer une fois par projet et par version de dictionnaire.

### Gros fichier

Fichier synthétique de 200 000 lignes (~8,4 Mo de corps, lignes mélangées de façon déterministe) :

| Codec | Octets | Gain | Encodage | Décodage |
|---|---:|---:|---:|---:|
| deflate-1 (actuel) | 3 238 854 | — | 82,35 ms | 27,34 ms |
| zstd-3 sans dict | **1 503 660** | **−53,6 %** | **20,28 ms** | **6,01 ms** |
| zstd-3 + dict Kotlin | 1 595 472 | −50,7 % | 22,00 ms | 6,26 ms |
| zstd-9 sans dict | 1 056 635 | −67,4 % | 90,38 ms | 4,68 ms |

Trois remarques :

- zstd-3 est **4× plus rapide** que `deflate-1` en encodage et 4,5× en décodage, tout en étant 2× plus petit.
- Le dictionnaire **dégrade** les très gros corps (+6 %) : il n'aide que les petits fichiers. Il faut donc
  aussi le désactiver au-delà d'un seuil (proposé : 1 Mo).
- zstd-1 est mauvais sur ce fichier (2,6 Mo) : sa fenêtre est trop petite. Le niveau 3 est le minimum utile.

---

## 6. Coût CPU

| Situation | Actuel | zstd-3 + dict | Commentaire |
|---|---:|---:|---|
| Kotlin, 109 fichiers (598 Ko) | 0,17 ms | 2,60 ms | l'actuel ne compresse qu'un fichier ; ~24 µs par fichier pour zstd |
| PHP, 200 fichiers (870 Ko) | 7,48 ms | 6,77 ms | zstd compresse 199 fichiers au lieu de 21 |
| Gros fichier 8,4 Mo | 82,35 ms | 20,28 ms | zstd-3 ~4× plus rapide |
| PHP, niveau 9 | — | 36,04 ms | +6 à 9 points de gain pour 5× le CPU |

Le surcoût Kotlin (2,4 ms sur 598 Ko) est invisible à l'échelle d'une session : il correspond à ~24 µs
d'encodage et ~8 µs de décodage par fichier moyen. Sur un lien lent, les octets économisés comptent bien plus
que ces microsecondes ; sur loopback, le CPU reste dominé par le reste du serveur.

Le niveau 9 n'est pas un bon défaut pour de l'interactif. Il peut rester une option pour les très gros corps
(> 1 Mo), où 30 % de 1,5 Mo sur le fil valent 90 ms d'encodage.

---

## 7. Mise en œuvre

La décision retenue est **zstd niveau 3, dictionnaire générique de 64 Ko, mode hybride**. Elle est
implémentée dans le module [`compression`](../compression) :

- `BodyCodec` : l'interface que le protocole appelle avant d'écrire et après avoir lu une trame.
- `ZstdBodyCodec` : zstd-3, seuil de 64 octets, économie minimale de 10 %, dictionnaire essayé uniquement
  pour les corps de code et seulement jusqu'à 1 Mo. Le plus petit des deux résultats est conservé.
- `Dictionaries` : chargement du dictionnaire embarqué `/zstd/mixed-64k.dict`, entraîné sur la représentation
  filaire de sources Kotlin et PHP (`benchmark/train-dictionaries.sh`).

Côté protocole :

1. **`Protocol.VERSION` passe à 3** : un client ou un serveur plus ancien est refusé au handshake, ce qui
   évite de lire des trames zstd avec un décodeur deflate.
2. **Le drapeau `FLAG_COMPRESSED` remplace `FLAG_DEFLATED`** ; `flags & 1` signifie désormais « corps zstd ».
   Il n'y a **pas de drapeau « dictionnaire »** : zstd inscrit l'identifiant du dictionnaire dans chaque trame,
   et le décodeur vérifie qu'il possède bien celui-là (`Zstd.getDictIdFromFrame`). Un identifiant inconnu
   échoue proprement au lieu de produire du bruit.
3. **`Frame.code`** porte le hint « ce corps est du code ». Il n'est pas transmis : c'est une indication pour
   l'encodeur local. Le serveur le pose sur les réponses `Read` ; le client le pose sur les requêtes `Write` et
   `CreateFile`. Les métadonnées (`WalkFiles`, `FindInFiles`, `Symbols`, `GitStatus`, `GitDiff`, terminaux)
   restent sans dictionnaire.
4. **Un codec par connexion** (`Protocol.newCodec()` dans `Link` et `ServerConnection`) : les contextes zstd ne
   sont pas thread-safe et gardent le dictionnaire chargé. Le codec est fermé avec la connexion.
5. **Le dictionnaire de projet reste l'étape suivante** : le dictionnaire générique est embarqué dans
   l'application, identique des deux côtés. Un dictionnaire par projet pourra le remplacer sans changer le
   format de trame, puisque l'identifiant voyage déjà dans le flux. Il devra rester hors du dépôt Git, comme
   `benchmark/dicts-local/`.

Les tests du codec sont dans [`compression/test`](../compression/test) ; ceux du protocole, dont un
aller-retour de corps `code = true`, dans [`server/test/ProtocolTest.kt`](../server/test/ProtocolTest.kt).

---

## 8. Limites

- **Ce n'est pas JMH** : médianes d'un harnais simple, variance de 10 à 20 %. Les écarts de 0,5 point ne sont
  pas significatifs ; ceux de 5 points le sont.
- **Pas de vrai lien TCP** : on mesure des octets et du CPU, pas de latence réseau. Le gain en octets est le
  chiffre qui compte pour un lien distant.
- **Le corpus Kotlin est le dépôt lui-même** : les 109 fichiers mesurés sont ceux du projet, mais le
  dictionnaire générique vient de bibliothèques publiques ; le −68,2 % est donc honnête. Le dictionnaire de
  projet, lui, n'est évalué que sur les 21 fichiers de test.
- **Le dictionnaire PHP générique vient de PEAR**, du PHP ancien. Un projet PHP moderne donnerait des chiffres
  un peu différents, probablement meilleurs avec un dictionnaire de projet.
- **Le fichier de 200 000 lignes est synthétique**, pas un vrai fichier : il sert à exercer le chemin où
  l'actuel compresse déjà, pas à prédire un cas réel.
- **JS, HTML, XML et CSS ne sont pas encore mesurés**, alors que l'ERP en contient beaucoup (8 834 fichiers JS,
  1 496 HTML, 723 XML, 1 323 CSS). Ce sont les prochains corpus à ajouter : un dictionnaire par famille de
  langage, ou un dictionnaire « front » séparé.

---

## 9. Reproduire

```bash
# Entraîner les dictionnaires (génériques + projet)
./benchmark/train-dictionaries.sh

# Mesurer et écrire benchmark/report.md
./benchmark/run.sh

# Variantes
PHP_PROJECT=/chemin/vers/le/projet/php ITERATIONS=50 ./benchmark/run.sh
```

Le module `benchmark` est déclaré dans `project.yaml` et dépend de `zstd-jni 1.5.6-10`. Il ne fait pas partie
des sources de l'application : il ne change ni le protocole ni le serveur. Le codec de production, lui, vit
dans le module `compression`, avec son propre test :

```bash
./kotlin test -m compression
./kotlin test -m server      # protocole, reprise de session, terminaux
```

---

## Annexe : autres algorithmes (mesure indicative)

Mesure hors harnais JVM, sur la même représentation filaire, avec les liaisons Python (`brotli`, `lzma`) et les
outils en ligne de commande (`zstd`, et un petit outil C pour Brotli avec dictionnaire brut). Les corpus ont
légèrement changé depuis le rapport (114 fichiers Kotlin, 646 Ko ; 5 860 fichiers PHP) et les temps des
variantes CLI incluent le lancement d'un processus : **seuls les octets sont comparables au reste du document**.
Les temps Python, eux, sont représentatifs de l'ordre de grandeur.

| Codec | Kotlin | Gain | PHP (200 corps) | Gain |
|---|---:|---:|---:|---:|
| deflate-1 (actuel) | 615 250 | — | 820 017 | — |
| brotli q5 sans dict | 193 680 | −68,5 % | 357 542 | −56,4 % |
| **brotli q5 + dict** | **179 726** | **−70,8 %** | **311 528** | **−62,0 %** |
| zstd-3 + dict | 199 389 | −67,6 % | 359 814 | −56,1 % |
| zstd-9 + dict | 180 519 | −70,7 % | 311 561 | −62,0 % |
| lzma2 preset 6 | 198 275 | −67,8 % | 342 000 | −58,3 % |

Sur les dix plus gros fichiers PHP (5,3 Mo de corps) : brotli q5 + dict **884 951** octets, zstd-9 + dict
**880 668**, zstd-3 + dict **1 087 111**. Brotli q5 et zstd-9 font jeu égal ; zstd-3 est 19 % plus gros.

Ce que cela dit :

- **Brotli q5 + dictionnaire est le seul concurrent crédible, mais il ne bat pas zstd-9.** Il atteint le ratio
  de zstd-9 avec un dictionnaire *brut* (préfixe LZ77, sans tables d'entropie entraînées), soit 10 à 19 % de
  moins que zstd-3 + dict. C'est donc le même compromis que zstd-9 : 4 à 5× le CPU de zstd-3, ~150 µs par
  fichier PHP moyen, et un décodage 2 à 3× plus lent que zstd. Or zstd-9 est déjà dans `zstd-jni` : sur
  Kotlin/PHP, Brotli n'apporte rien de plus qu'un niveau 9.
- **LZMA2 n'est pas intéressant ici** : moins bon que brotli q5 + dict, et 50× plus lent à l'encodage. PPMd
  ferait un peu mieux sur du texte, mais pour un coût et une complexité d'intégration bien pires.
- **LZ4 et Snappy** sont des choix de latence, pas de ratio : zstd-3 encode déjà à 130–230 Mo/s, ils
  n'apporteraient rien.
- **Deflate + dictionnaire préréglé** (`zlib.setDictionary`) est trivial à mettre en place, mais reste loin
  derrière zstd.
- Le dictionnaire de Brotli est un simple historique : on ne peut pas y entraîner des tables comme avec
  `Zstd.trainFromBuffer`. On peut néanmoins y mettre les mêmes fragments de code, et `brotli4j` expose bien
  `prepareDictionary` / `attachDictionary` côté encodeur et décodeur.

**Verdict** : aucun algorithme ne domine zstd pour ce besoin. Brotli q5 + dictionnaire fait jeu égal avec
zstd-9 + dictionnaire (même ratio, même ordre de CPU, décodage plus lent) ; comme zstd-9 est déjà disponible
dans `zstd-jni`, il n'apporte rien sur Kotlin/PHP. Son seul avantage potentiel est son **dictionnaire statique
intégré**, utile sur HTML/JS/CSS : c'est le test à faire sur les corpus web de l'ERP avant d'envisager une
seconde dépendance native. LZMA2 et PPMd sont hors jeu pour de l'interactif.

Donc, en pratique : **zstd-3 par défaut, zstd-9 en profil « ratio »** (gros fichiers, lien lent, export), et
Brotli seulement si le test web le justifie. Un profil par type de corps peut se négocier dans la trame, mais
chaque codec ajouté coûte un drapeau et une dépendance.

Les gains les plus importants ne sont de toute façon pas dans le choix de l'algorithme :

1. **dictionnaire par projet** (+4 à 5 points par rapport au générique) ;
2. **delta contre la version que le client possède déjà** (`zstd --patch-from` ou équivalent) : sur une
   relecture ou une modification, on n'envoie que les différences, pas le fichier entier ;
3. **ne pas renvoyer les fichiers inchangés** (cache par version/hash), qui rend le codec presque secondaire ;
4. **fenêtre plus grande pour les corps > 1 Mo** : le niveau 3 de zstd utilise une fenêtre de 2 Mo, ce qui
   explique une partie de l'écart avec le niveau 9 sur le fichier de 8,4 Mo.
