# Benchmark zstd + dictionnaire de code

Machine : amd64, JVM 25.0.4.1, zstd-jni 1.5.6-10.
Corpus Kotlin : 109 fichiers, 598,414 octets de corps.
Corpus PHP : 5833 fichiers, 51,010,226 octets de corps; 200 fichiers échantillonnés pour les mesures de temps.

## Kotlin : lecture de fichiers (109 fichiers)

| Codec | Octets sur le fil | Gain vs actuel | Ratio | Corps compressés | Encodage | Décodage |
|---|---:|---:|---:|---:|---:|---:|
| deflate-1 (actuel) | 585,131 | +0.0 % | 1.02× | 1/109 | 0.17 ms | 0.07 ms |
| zstd-1 sans dict | 207,738 | +64.5 % | 2.88× | 109/109 | 2.23 ms | 0.83 ms |
| zstd-3 sans dict | 201,493 | +65.6 % | 2.97× | 109/109 | 2.71 ms | 0.87 ms |
| zstd-9 sans dict | 191,061 | +67.3 % | 3.13× | 109/109 | 15.98 ms | 0.80 ms |
| zstd-1 + dict Kotlin | 199,130 | +66.0 % | 3.01× | 109/109 | 2.20 ms | 0.80 ms |
| zstd-3 + dict Kotlin | 186,340 | +68.2 % | 3.21× | 109/109 | 2.60 ms | 0.90 ms |
| zstd-9 + dict Kotlin | 168,804 | +71.2 % | 3.55× | 109/109 | 14.81 ms | 0.97 ms |

## Kotlin : effet du dictionnaire par taille de fichier

| Taille du corps | Fichiers | Actuel | zstd-3 + dict Kotlin | Gain | Ratio zstd |
|---|---:|---:|---:|---:|---:|
| 0–2 Ko | 16 | 21,511 | 8,457 | +60.7 % | 2.52× |
| 2–4 Ko | 23 | 70,869 | 24,667 | +65.2 % | 2.86× |
| 4–8 Ko | 54 | 322,229 | 101,591 | +68.5 % | 3.17× |
| 8–16 Ko | 15 | 163,979 | 45,688 | +72.1 % | 3.59× |
| ≥ 16 Ko | 1 | 6,543 | 5,937 | +9.3 % | 3.52× |

## Kotlin : petites requêtes et réponses

| Codec | Octets sur le fil | Gain vs actuel | Ratio | Corps compressés | Encodage | Décodage |
|---|---:|---:|---:|---:|---:|---:|
| deflate-1 (actuel) | 2,065 | +0.0 % | 0.95× | 0/10 | 0.00 ms | 0.00 ms |
| zstd-1 sans dict | 1,003 | +51.4 % | 1.96× | 2/10 | 0.02 ms | 0.00 ms |
| zstd-3 sans dict | 1,001 | +51.5 % | 1.96× | 2/10 | 0.02 ms | 0.00 ms |
| zstd-9 sans dict | 979 | +52.6 % | 2.01× | 2/10 | 0.03 ms | 0.00 ms |
| zstd-1 + dict Kotlin | 982 | +52.4 % | 2.00× | 2/10 | 0.01 ms | 0.00 ms |
| zstd-3 + dict Kotlin | 977 | +52.7 % | 2.01× | 2/10 | 0.01 ms | 0.00 ms |
| zstd-9 + dict Kotlin | 971 | +53.0 % | 2.02× | 2/10 | 0.04 ms | 0.00 ms |

## Kotlin : requêtes de projet

| Codec | Octets sur le fil | Gain vs actuel | Ratio | Corps compressés | Encodage | Décodage |
|---|---:|---:|---:|---:|---:|---:|
| deflate-1 (actuel) | 68,875 | +0.0 % | 5.15× | 2/7 | 1.28 ms | 0.47 ms |
| zstd-1 sans dict | 47,878 | +30.5 % | 7.41× | 7/7 | 0.53 ms | 0.19 ms |
| zstd-3 sans dict | 46,559 | +32.4 % | 7.62× | 7/7 | 0.57 ms | 0.20 ms |
| zstd-9 sans dict | 40,076 | +41.8 % | 8.86× | 7/7 | 3.13 ms | 0.18 ms |
| zstd-1 + dict Kotlin | 47,778 | +30.6 % | 7.43× | 7/7 | 0.51 ms | 0.19 ms |
| zstd-3 + dict Kotlin | 46,691 | +32.2 % | 7.60× | 7/7 | 0.76 ms | 0.20 ms |
| zstd-9 + dict Kotlin | 38,701 | +43.8 % | 9.17× | 7/7 | 3.77 ms | 0.19 ms |

## Kotlin : session réaliste

| Codec | Octets sur le fil | Gain vs actuel | Ratio | Corps compressés | Encodage | Décodage |
|---|---:|---:|---:|---:|---:|---:|
| deflate-1 (actuel) | 193,279 | +0.0 % | 2.55× | 3/37 | 1.51 ms | 0.53 ms |
| zstd-1 sans dict | 91,663 | +52.6 % | 5.38× | 29/37 | 1.02 ms | 0.37 ms |
| zstd-3 sans dict | 89,007 | +53.9 % | 5.54× | 29/37 | 1.16 ms | 0.38 ms |
| zstd-9 sans dict | 79,618 | +58.8 % | 6.20× | 29/37 | 6.95 ms | 0.34 ms |
| zstd-1 + dict Kotlin | 90,165 | +53.3 % | 5.47× | 29/37 | 1.00 ms | 0.36 ms |
| zstd-3 + dict Kotlin | 86,957 | +55.0 % | 5.67× | 29/37 | 1.42 ms | 0.39 ms |
| zstd-9 + dict Kotlin | 74,531 | +61.4 % | 6.62× | 29/37 | 7.01 ms | 0.38 ms |
| zstd-3 + dict mixte | 86,776 | +55.1 % | 5.69× | 31/37 | 1.41 ms | 0.39 ms |
| zstd-3 hybride (dict sur le code) | 87,521 | +54.7 % | 5.64× | 29/37 | 1.27 ms | 0.39 ms |

## PHP : lecture de fichiers (200 fichiers, échantillon)

| Codec | Octets sur le fil | Gain vs actuel | Ratio | Corps compressés | Encodage | Décodage |
|---|---:|---:|---:|---:|---:|---:|
| deflate-1 (actuel) | 870,142 | +0.0 % | 1.91× | 21/200 | 7.48 ms | 2.92 ms |
| zstd-1 sans dict | 458,566 | +47.3 % | 3.62× | 198/200 | 5.04 ms | 1.88 ms |
| zstd-3 sans dict | 445,371 | +48.8 % | 3.73× | 197/200 | 6.08 ms | 2.04 ms |
| zstd-9 sans dict | 396,118 | +54.5 % | 4.19× | 198/200 | 33.18 ms | 1.87 ms |
| zstd-3 + dict PHP générique | 439,717 | +49.5 % | 3.78× | 199/200 | 7.21 ms | 1.96 ms |
| zstd-3 + dict PHP projet | 417,409 | +52.0 % | 3.98× | 199/200 | 6.77 ms | 1.93 ms |
| zstd-9 + dict PHP projet | 340,822 | +60.8 % | 4.87× | 199/200 | 36.04 ms | 1.91 ms |

## PHP : corpus de test complet (1166 fichiers)

| Codec | Octets sur le fil | Gain vs actuel | Ratio | Corps compressés |
|---|---:|---:|---:|---:|
| deflate-1 (actuel) | 5,152,921 | +0.0 % | 1.97× | 123/1166 |
| zstd-1 sans dict | 2,715,536 | +47.3 % | 3.75× | 1155/1166 |
| zstd-3 sans dict | 2,645,658 | +48.7 % | 3.84× | 1149/1166 |
| zstd-9 sans dict | 2,396,672 | +53.5 % | 4.24× | 1152/1166 |
| zstd-3 + dict PHP générique | 2,532,846 | +50.8 % | 4.02× | 1164/1166 |
| zstd-3 + dict PHP projet | 2,387,272 | +53.7 % | 4.26× | 1164/1166 |
| zstd-9 + dict PHP projet | 2,053,205 | +60.2 % | 4.95× | 1164/1166 |

## PHP : petites requêtes et réponses

| Codec | Octets sur le fil | Gain vs actuel | Ratio | Corps compressés | Encodage | Décodage |
|---|---:|---:|---:|---:|---:|---:|
| deflate-1 (actuel) | 1,990 | +0.0 % | 0.95× | 0/10 | 0.00 ms | 0.00 ms |
| zstd-1 sans dict | 1,165 | +41.5 % | 1.62× | 2/10 | 0.02 ms | 0.00 ms |
| zstd-3 sans dict | 1,164 | +41.5 % | 1.62× | 2/10 | 0.02 ms | 0.00 ms |
| zstd-9 sans dict | 1,151 | +42.2 % | 1.64× | 2/10 | 0.03 ms | 0.00 ms |
| zstd-3 + dict PHP générique | 1,164 | +41.5 % | 1.62× | 2/10 | 0.01 ms | 0.00 ms |
| zstd-3 + dict PHP projet | 1,066 | +46.4 % | 1.77× | 4/10 | 0.01 ms | 0.00 ms |
| zstd-9 + dict PHP projet | 1,017 | +48.9 % | 1.86× | 7/10 | 0.03 ms | 0.00 ms |

## PHP : requêtes de projet

| Codec | Octets sur le fil | Gain vs actuel | Ratio | Corps compressés | Encodage | Décodage |
|---|---:|---:|---:|---:|---:|---:|
| deflate-1 (actuel) | 36,223 | +0.0 % | 2.70× | 1/6 | 0.34 ms | 0.14 ms |
| zstd-1 sans dict | 18,210 | +49.7 % | 5.37× | 4/6 | 0.17 ms | 0.07 ms |
| zstd-3 sans dict | 17,846 | +50.7 % | 5.48× | 4/6 | 0.19 ms | 0.07 ms |
| zstd-9 sans dict | 16,104 | +55.5 % | 6.07× | 4/6 | 1.24 ms | 0.07 ms |
| zstd-3 + dict PHP générique | 17,805 | +50.8 % | 5.49× | 4/6 | 0.27 ms | 0.07 ms |
| zstd-3 + dict PHP projet | 16,876 | +53.4 % | 5.80× | 4/6 | 0.25 ms | 0.07 ms |
| zstd-9 + dict PHP projet | 14,904 | +58.9 % | 6.56× | 4/6 | 1.36 ms | 0.08 ms |

## PHP : session réaliste

| Codec | Octets sur le fil | Gain vs actuel | Ratio | Corps compressés | Encodage | Décodage |
|---|---:|---:|---:|---:|---:|---:|
| deflate-1 (actuel) | 256,343 | +0.0 % | 3.41× | 12/36 | 5.42 ms | 2.12 ms |
| zstd-1 sans dict | 211,432 | +17.5 % | 4.14× | 25/36 | 2.16 ms | 0.76 ms |
| zstd-3 sans dict | 203,537 | +20.6 % | 4.30× | 23/36 | 2.65 ms | 0.87 ms |
| zstd-9 sans dict | 170,716 | +33.4 % | 5.12× | 24/36 | 14.29 ms | 0.78 ms |
| zstd-3 + dict PHP générique | 215,899 | +15.8 % | 4.05× | 25/36 | 3.72 ms | 0.86 ms |
| zstd-3 + dict PHP projet | 213,028 | +16.9 % | 4.11× | 27/36 | 3.58 ms | 0.87 ms |
| zstd-9 + dict PHP projet | 162,106 | +36.8 % | 5.40× | 30/36 | 17.91 ms | 0.85 ms |
| zstd-3 + dict mixte | 216,973 | +15.4 % | 4.03× | 25/36 | 3.70 ms | 0.85 ms |
| zstd-3 hybride (dict sur le code) | 199,693 | +22.1 % | 4.38× | 23/36 | 3.20 ms | 0.91 ms |

## Kotlin : fichier synthétique de 200 000 lignes

| Codec | Octets sur le fil | Gain vs actuel | Ratio | Corps compressés | Encodage | Décodage |
|---|---:|---:|---:|---:|---:|---:|
| deflate-1 (actuel) | 3,238,854 | +0.0 % | 2.59× | 1/1 | 82.35 ms | 27.34 ms |
| zstd-1 sans dict | 2,600,097 | +19.7 % | 3.23× | 1/1 | 23.53 ms | 6.23 ms |
| zstd-3 sans dict | 1,503,660 | +53.6 % | 5.59× | 1/1 | 20.28 ms | 6.01 ms |
| zstd-9 sans dict | 1,056,635 | +67.4 % | 7.95× | 1/1 | 90.38 ms | 4.68 ms |
| zstd-1 + dict Kotlin | 2,910,763 | +10.1 % | 2.89× | 1/1 | 24.85 ms | 6.72 ms |
| zstd-3 + dict Kotlin | 1,595,472 | +50.7 % | 5.27× | 1/1 | 22.00 ms | 6.26 ms |
| zstd-9 + dict Kotlin | 1,526,888 | +52.9 % | 5.50× | 1/1 | 138.40 ms | 5.43 ms |

## Kotlin : lecture de fichiers (21 fichiers de test)

| Codec | Octets sur le fil | Gain vs actuel | Ratio | Corps compressés | Encodage | Décodage |
|---|---:|---:|---:|---:|---:|---:|
| deflate-1 (actuel) | 145,118 | +0.0 % | 1.00× | 0/21 | 0.00 ms | 0.00 ms |
| zstd-3 + dict Kotlin générique | 45,010 | +69.0 % | 3.22× | 21/21 | 0.63 ms | 0.20 ms |
| zstd-3 + dict projet Kotlin | 38,353 | +73.6 % | 3.78× | 21/21 | 0.54 ms | 0.21 ms |

## Kotlin : tailles de dictionnaire

| Codec | Octets sur le fil | Gain vs actuel | Ratio | Corps compressés | Encodage | Décodage |
|---|---:|---:|---:|---:|---:|---:|
| deflate-1 (actuel) | 585,131 | +0.0 % | 1.02× | 1/109 | 0.17 ms | 0.07 ms |
| zstd-3 sans dict | 201,493 | +65.6 % | 2.97× | 109/109 | 2.71 ms | 0.86 ms |
| zstd-3 + dict 32K | 187,752 | +67.9 % | 3.19× | 109/109 | 2.66 ms | 0.86 ms |
| zstd-3 + dict 64K | 186,340 | +68.2 % | 3.21× | 109/109 | 2.60 ms | 0.89 ms |
| zstd-3 + dict 110K | 185,180 | +68.4 % | 3.23× | 109/109 | 2.58 ms | 0.92 ms |

## PHP : tailles de dictionnaire

| Codec | Octets sur le fil | Gain vs actuel | Ratio | Corps compressés | Encodage | Décodage |
|---|---:|---:|---:|---:|---:|---:|
| deflate-1 (actuel) | 870,142 | +0.0 % | 1.91× | 21/200 | 7.45 ms | 2.90 ms |
| zstd-3 sans dict | 445,371 | +48.8 % | 3.73× | 197/200 | 6.07 ms | 2.04 ms |
| zstd-3 + dict 32K | 441,257 | +49.3 % | 3.76× | 199/200 | 7.04 ms | 1.92 ms |
| zstd-3 + dict 64K | 439,717 | +49.5 % | 3.78× | 199/200 | 7.16 ms | 1.93 ms |
| zstd-3 + dict 110K | 441,265 | +49.3 % | 3.76× | 199/200 | 7.21 ms | 2.01 ms |

## Kotlin : dictionnaire d'une autre langue

| Codec | Octets sur le fil | Gain vs actuel | Ratio | Corps compressés | Encodage | Décodage |
|---|---:|---:|---:|---:|---:|---:|
| deflate-1 (actuel) | 585,131 | +0.0 % | 1.02× | 1/109 | 0.17 ms | 0.06 ms |
| zstd-3 sans dict | 201,493 | +65.6 % | 2.97× | 109/109 | 2.95 ms | 0.93 ms |
| zstd-3 + dict Kotlin | 186,340 | +68.2 % | 3.21× | 109/109 | 3.17 ms | 0.96 ms |
| zstd-3 + dict PHP | 196,352 | +66.4 % | 3.05× | 109/109 | 2.84 ms | 0.92 ms |

## PHP : dictionnaire d'une autre langue

| Codec | Octets sur le fil | Gain vs actuel | Ratio | Corps compressés | Encodage | Décodage |
|---|---:|---:|---:|---:|---:|---:|
| deflate-1 (actuel) | 870,142 | +0.0 % | 1.91× | 21/200 | 7.44 ms | 2.91 ms |
| zstd-3 sans dict | 445,371 | +48.8 % | 3.73× | 197/200 | 6.09 ms | 2.02 ms |
| zstd-3 + dict PHP | 439,717 | +49.5 % | 3.78× | 199/200 | 7.15 ms | 1.94 ms |
| zstd-3 + dict Kotlin | 447,778 | +48.5 % | 3.71× | 199/200 | 7.66 ms | 1.96 ms |
