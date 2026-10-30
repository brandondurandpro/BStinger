# compression

Codec de corps du protocole : **zstd niveau 3**, avec un dictionnaire de code de 64 Ko appliqué uniquement aux
corps qui contiennent du code.

Le protocole de session (trames, multiplexage, reprise après reconnexion, flux de terminaux) ne dépend pas de ce
module : `Protocol` lui délègue seulement la compression et la décompression du corps.

## API

```kotlin
val codec: BodyCodec = Protocol.newCodec()          // un par connexion
val packed = codec.compress(body, code = true)      // null si cela n'économise pas assez
val body = codec.decompress(packed)
codec.close()
```

- `compress` renvoie `null` quand il vaut mieux envoyer le corps tel quel.
- `code = true` autorise le dictionnaire ; `code = false` le désactive. Le hint n'est pas transmis : c'est
  l'encodeur qui décide, et zstd inscrit de toute façon l'identifiant du dictionnaire dans la trame.
- Un codec appartient à **une seule connexion** : les contextes zstd ne sont pas thread-safe et gardent le
  dictionnaire chargé.

## Réglages

| Réglage | Valeur | Raison |
|---|---|---|
| Niveau | 3 | Le niveau 9 ne gagne que 2 à 8 points pour 4 à 6× plus de CPU |
| Seuil | 64 octets | En dessous, une trame zstd ne bat pas le corps brut |
| Économie minimale | 10 % | Même règle que l'ancien codec `deflate` |
| Dictionnaire | `mixed-64k.dict` | Entraîné sur la représentation filaire de sources Kotlin et PHP |
| Limite du dictionnaire | 1 Mo | Au-delà, le dictionnaire n'aide plus et coûte une seconde compression |

Pour un corps de code, le codec compresse deux fois — avec et sans dictionnaire — et garde le plus petit
résultat. C'est ce qui évite que le dictionnaire dégrade les gros fichiers ou les corps atypiques.

Le décodeur lit l'identifiant du dictionnaire dans la trame (`Zstd.getDictIdFromFrame`). S'il ne possède pas ce
dictionnaire, il échoue avec une `IOException` au lieu de produire des octets faux.

## Mettre à jour le dictionnaire

Le dictionnaire est entraîné par le module `benchmark`, puis copié dans les ressources :

```bash
./benchmark/train-dictionaries.sh
./compression/scripts/update-dictionary.sh
./kotlin test -m compression
```

`update-dictionary.sh` accepte un autre fichier en argument, par exemple un dictionnaire de projet :

```bash
./compression/scripts/update-dictionary.sh /chemin/vers/mon-projet-64k.dict
```

Un dictionnaire de projet contient des fragments du code : il ne doit pas être versionné dans un dépôt public.
Le format de trame n'a pas besoin de changer, puisque l'identifiant du dictionnaire voyage déjà dans chaque
trame zstd ; il faudra en revanche que le client et le serveur utilisent le même. Le dictionnaire doit être
entraîné par zstd (il porte alors un identifiant) : un dictionnaire brut sans identifiant est refusé au
démarrage, car les trames ne pourraient pas dire lequel elles utilisent.

## Tests

```bash
./kotlin test -m compression
```

Les tests couvrent l'aller-retour du code avec dictionnaire, l'absence de dictionnaire sur les métadonnées, les
corps trop petits ou incompressibles, le rejet d'un dictionnaire inconnu et les corps au-delà de la limite.
