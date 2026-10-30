# Benchmark : zstd + dictionnaire de code

Ce module compare le codec que le protocole utilisait avant zstd (`deflate` niveau 1, à partir de 16 Ko) à
**zstd avec un dictionnaire entraîné sur du code**, sur les corps de trames réellement produits par les codecs du
protocole.

Le protocole de session (frames, multiplexage, reconnexion, flux) n'est pas remis en cause : seul le codec de
corps est comparé.

## Ce qui est mesuré

- **Octets sur le fil** : corps encodé + en-tête de trame de 10 octets, pour chaque codec.
- **Temps d'encodage et de décodage** : médiane de plusieurs itérations, après échauffement JIT.
- **Taux de compression** et **gain par rapport au protocole actuel**.
- Les seuils de l'ancien codec sont reproduits : `deflate` seulement si le corps fait au moins 16 Ko et si
  la compression économise au moins 10 %.
- Le codec zstd essaie à partir de 64 octets et ne garde le résultat que s'il économise au moins 10 %.

Les charges utiles couvrent :

- des réponses `Read` (contenu de fichier encodé en lignes, comme le serveur) ;
- des petites requêtes et réponses (chemins, session, projets récents) ;
- des requêtes de projet (`WalkFiles`, `FindInFiles`, `Symbols`, `GitStatus`, `GitDiff`, sortie de shell) ;
- une session réaliste qui combine le tout ;
- un fichier synthétique de 200 000 lignes pour le chemin où le protocole actuel compresse déjà.

## Dictionnaires

Deux familles :

- **`benchmark/dicts/`** (versionnés) : dictionnaires génériques, entraînés sur des sources publiques.
  - `kotlin-generic-{32,64,110}k.dict` : `kotlin-stdlib`, `kotlinx-coroutines`, `kotlinx-serialization`,
    `kotlinx-io` (679 fichiers, 6,7 Mo).
  - `php-{32,64,110}k.dict` : sources PHP de PEAR dans `/usr/share/php` (97 fichiers, 2 Mo).
  - `mixed-{32,64,110}k.dict` : Kotlin générique + PHP générique.
- **`benchmark/dicts-local/`** (ignoré par Git) : dictionnaires entraînés sur 80 % du projet mesuré, testés sur
  les 20 % restants.
  - `kotlin-project-*` : ce dépôt.
  - `php-project-*` : le projet PHP passé à `PHP_PROJECT`.

Les dictionnaires sont entraînés sur la **représentation filaire** des fichiers (lignes préfixées par leur
longueur), pas sur le texte brut : c'est ce que zstd voit réellement. Cela améliore le taux de 5 à 8 % par
rapport à un dictionnaire entraîné sur le texte brut.

Un dictionnaire de projet peut contenir des fragments du code du projet : il reste donc dans
`benchmark/dicts-local/`, jamais versionné.

## Entraîner

```bash
./benchmark/train-dictionaries.sh
# ou
PHP_CORPUS=/usr/share/php PHP_PROJECT=/chemin/vers/le/projet/php ./benchmark/train-dictionaries.sh
```

Le script extrait les sources Kotlin publiques des jars `*-sources.jar` du cache Gradle, puis entraîne les
dictionnaires avec l'algorithme *cover* de zstd (via zstd-jni).

## Mesurer

```bash
./benchmark/run.sh
# ou
ITERATIONS=50 PHP_PROJECT=/chemin/vers/le/projet/php ./benchmark/run.sh
```

Le rapport brut est écrit dans `benchmark/report.md`. L'analyse et la décision sont dans
[`benchmark/CONCLUSION.md`](CONCLUSION.md). Le codec retenu est implémenté dans le module
[`compression`](../compression) ; ce module `benchmark` ne sert qu'à le mesurer et à entraîner les
dictionnaires.

## Limites

- Ce n'est pas JMH : les temps sont des médianes d'un harnais simple, avec une variance de l'ordre de 10 à 20 %.
- On mesure le CPU et les octets, pas un vrai lien TCP. Sur un lien lent, le gain en octets compte davantage ;
  sur loopback, le CPU domine.
- Les résultats dépendent du corpus. Le corpus PHP est un ERP réel (5 833 fichiers, 51 Mo), dont 1 166 fichiers
  de test ; 200 fichiers sont échantillonnés pour les mesures de temps. Les fichiers de plus de 1 Mo et les
  répertoires ignorés par l'indexeur (`build`, `vendor` n'est pas ignoré, `node_modules`, etc.) sont exclus.
- Le dictionnaire PHP générique est entraîné sur du PHP ancien (PEAR) ; un projet PHP moderne peut donner des
  chiffres différents.
- Le fichier de 200 000 lignes est synthétique (lignes mélangées de façon déterministe), pas un vrai fichier.

## Périmètre

Le module `benchmark` n'est pas dans les sources analysées par `./lint` : c'est un harnais de mesure, pas du
code applicatif. Il est compilé et exécuté par `./kotlin build -m benchmark` et `./benchmark/run.sh`, et ne
participe ni au serveur ni au protocole.
