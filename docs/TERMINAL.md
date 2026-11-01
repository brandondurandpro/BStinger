# Terminal : comparatif et recommandation

> État au 30 octobre 2026. Ce document remplace le choix « PTY via pty4j » de la feuille de route : il sépare
> **le PTY** (lancer un programme interactif) de **l'émulateur de terminal** (interpréter les séquences ANSI/VT et
> tenir l'écran), deux briques qui n'ont pas les mêmes candidats.

## 0. Lecture de la demande

La demande dit « pas py4j ». Dans un projet Kotlin/JVM, il s'agit très probablement de **pty4j**, la bibliothèque
utilisée par la feuille de route. Le comparatif ci-dessous traite donc deux cas :

- **« pas pty4j » = pas de JNA / pas l'ancienne pile pty4j** → `pty4j21`, son fork FFM (Java 22+), règle le problème ;
- **« pas pty4j » = aucune ligne issue de pty4j** → il faut écrire le PTY soi-même en FFM/JNA, ce qui est possible
  mais nettement plus risqué (fork/exec, ConPTY, signaux, redimensionnement).

`libghostty` n'est **pas** un PTY : c'est un moteur d'émulation. Il ne remplace donc pas pty4j, il le complète.
C'est le point le plus important de ce comparatif.

Le projet tourne aujourd'hui sur **JDK 25** (classes en version 69), avec **Kotlin 2.4** et **Compose 1.12.1**.
La FFM (`java.lang.foreign`) est donc disponible sans dépendance supplémentaire, ce qui rend `pty4j21` et les
bindings Java de libghostty réellement utilisables.

---

## 1. Les quatre couches à choisir séparément

| Couche | Rôle | Candidats |
|---|---|---|
| **PTY** | Ouvrir un pseudo-terminal, lancer le shell, lire/écrire des octets, redimensionner, tuer le process | pty4j, pty4j21, PTY maison FFM/JNA |
| **Émulateur** | Parser les octets VT, tenir la grille, le scrollback, le curseur, les couleurs, les modes | libghostty-vt, kmp-terminal-emulator, BossTerm, JediTerm, libvterm, alacritty_terminal, xterm.js |
| **Rendu** | Dessiner la grille et gérer clavier/souris/sélection dans Compose | Canvas Compose maison, bibliothèque Compose, SwingPanel, JavaFX |
| **Gestionnaire** | Onglets, splits, titres, répertoire courant, cycle de vie, reprise après coupure | à écrire dans le projet (aucun moteur ne le fournit) |

Le point souvent oublié : **libghostty ne fournit ni onglets, ni splits, ni session management**. Ghostling le dit
explicitement. Ces fonctions restent à notre charge, quel que soit le moteur choisi.

---

## 2. Comparatif des PTY

| | **pty4j 0.14.0** | **pty4j21 0.22.2** | **PTY maison (FFM/JNA)** |
|---|---|---|---|
| Licence | EPL-1.0 | EPL-1.0 (fork de pty4j) | la nôtre |
| Liaison | JNA 5.19.1 | **FFM**, Java 22+ | FFM (ou JNA) |
| Dépendances runtime | JNA + jna-platform + slf4j | **aucune** | aucune (hors JDK) |
| Natives | Linux, macOS, Windows, FreeBSD | les mêmes helpers, embarqués | à compiler par plateforme |
| Windows | ConPTY + winpty | ConPTY + winpty | ConPTY à implémenter |
| API | `PtyProcessBuilder`, `WinSize`, `UnixPtyFileDescriptor` | compatible pty4j | à concevoir |
| Maturité | très élevée (IntelliJ, JediTerm, BossTerm) | bonne, mais petit projet (2 ★) | faible au départ |
| Coût d'intégration | ● | ● | ●●● |
| Verdict | référence, mais JNA | **meilleur compromis** | seulement si zéro pty4j est absolu |

**Vérification locale faite sur cette machine** (Linux, JDK 25.0.4) : `pty4j21` démarre `/bin/sh` dans un PTY
100×30, les couleurs ANSI passent, `stty size` renvoie `30 100`, le code de sortie est propagé, l'extraction native
prend ~10 ms. Aucun flag `--enable-preview` n'est nécessaire, seulement `--enable-native-access=ALL-UNNAMED`.

**Pourquoi pty4j21 plutôt que pty4j** : même API, mêmes binaires natifs, mais plus de JNA, plus de `jna-platform`,
et un chemin d'appel direct FFM. C'est le choix « moderne et léger » sans réécrire le PTY. Il reste sous EPL-1.0,
donc à garder non modifié (ou à publier les modifications) si le projet est distribué.

**Si « pas pty4j » veut dire « pas une seule ligne de pty4j »** : il faut un PTY maison. Sur Unix, `posix_spawn`
avec `openpty` est faisable en FFM ; sur Windows, ConPTY demande beaucoup plus de travail (pipes, handles,
`CreatePseudoConsole`, redimensionnement). Je ne le recommande pas pour ce projet : le gain est faible par rapport
au risque.

---

## 3. Comparatif des émulateurs

### 3.1 Tableau synthétique

| Moteur | Version | Licence | Langage / liaison JVM | Poids | Protocoles modernes | Rendu fourni | Maturité | Intégration Compose | Coût |
|---|---|---|---|---|---|---|---|---|---|
| **libghostty-vt** | commit Ghostty, API C encore instable | MIT | C/Zig ; **pas de binding Java officiel** | ~1,8 Mo de `.so` Linux (ReleaseFast) | **excellent** : Kitty keyboard, Kitty graphics, OSC 52, truecolor, reflow, alt screen, souris, Unicode | non (render state) | **très élevée** (cœur de Ghostty, validé par JetBrains 2026.3) | à écrire (FFM + Canvas) | ●●● |
| **kmp-terminal-emulator** | 1.0.6 (28/10/2026) | Apache-2.0 | Kotlin Multiplatform | core ~216 Ko + UI ~260 Ko | très bon : 24 bits, 256 couleurs, alt screen, souris SGR, OSC 52, Kitty graphics/placeholders, sélection, recherche | **oui**, Compose Canvas | moyenne (projet jeune, 4 ★, mais utilisé en production par un client SSH) | **directe** | ●● |
| **BossTerm** | 1.2.183 (10/10/2026) | LGPL-3.0 / Apache-2.0 (le POM publie LGPL 3.0) | Kotlin/Compose | core ~532 Ko + compose ~9,8 Mo + Ktor, MCP, Supabase, zxing, pty4j/JNA | très bon : VT/xterm, 24 bits, images, recherche, souris, OSC 52/133/7 | oui, composable `EmbeddableTerminal` | bonne (application complète) | directe, mais lourde | ●● |
| **JediTerm** | 3.x, maintenu | Apache-2.0 / LGPL-3.0 | Java, Swing | moyen | **limité** : pas de Kitty keyboard/graphics, séquences modernes manquantes | oui, Swing | très élevée, mais **remplacé par libghostty dans IntelliJ 2026.3** | via `SwingPanel` | ●● |
| **libvterm** | 0.3.x | MIT | C ; pas de binding Java | très faible | correct pour VT100/xterm, **pas les protocoles récents** | non | bonne, mais Neovim migre vers libghostty-vt | à écrire (FFM + Canvas) | ●●● |
| **alacritty_terminal / termwiz** | suivi Rust | Apache-2.0 / MIT | Rust ; pont JNI/FFM à écrire | moyen | excellent | non | très élevée | à écrire, plus toolchain Rust | ●●● |
| **xterm.js** | 5.x | MIT | JavaScript | lourd (JCEF/Chromium) | excellent | oui (DOM/Canvas) | très élevée | WebView/JCEF, pas léger | ●●● |
| **Émulateur maison Kotlin** | — | — | Kotlin | très faible | dépend de nous | oui | nulle au départ | directe | ●●●● |

### 3.2 Détail des finalistes

#### A. libghostty-vt + pty4j21 + rendu Compose

**Ce que c'est.** `libghostty-vt` est le cœur de Ghostty extrait en bibliothèque C : parseur VT, état du terminal,
scrollback, reflow, encodage clavier/souris, palette. Le rendu n'est **pas** fourni : on reçoit un « render state »
(rows, cells, couleurs, styles, curseur) et on dessine soi-même.

**Points forts**

- c'est aujourd'hui la référence en fidélité et en performances : parseur SIMD, projet fuzzé, utilisé par Ghostty
  au quotidien ;
- **JetBrains a remplacé JediTerm par libghostty dans les IDE 2026.3** (voir sources), précisément pour les
  protocoles modernes : Kitty keyboard, `modifyOtherKeys`, OSC 4/10/11/21, thème clair/sombre, REP, marges, etc. ;
- zéro dépendance (pas même libc selon Ghostling), licence MIT, pas de GPU imposé : le render state est utilisable
  par un Canvas Compose ;
- supporte Linux, macOS et Windows (le support Windows de libghostty-vt existe ; Ghostling ne le teste pas encore).

**Points faibles**

- **API C explicitement instable** : l'en-tête dit « incomplete, work-in-progress API […] definitely going to
  change ». Il faut épingler un commit et régénérer les bindings à chaque montée de version ;
- **pas de binding Java officiel**. Les options sont :
  - écrire un petit binding FFM avec `jextract` (recommandé), ou
  - réutiliser les bindings FFM de **GhosttyFX** (`io.github.vlaaad.ghostty.bindings`, Java 25, MIT, publiés sur
    Maven Central avec les natives Linux/macOS/Windows), ou
  - utiliser **jlibghostty** (FFM, Java 25, API haut niveau, mais projet Nix-first, non publié sur Maven Central),
  - ou un shim JNI comme JetBrains ;
- il faut **compiler et packager** la bibliothèque native pour chaque plateforme (Zig 0.16 + CMake/Ninja), la signer,
  la vérifier par SHA-256 et l'embarquer dans la distribution ;
- le rendu, la sélection, le clavier, le collage, le redimensionnement et les onglets sont à écrire.

**Verdict.** Le meilleur moteur, mais le coût d'intégration est réel : ●●●. À choisir si la fidélité terminal est
une exigence (agents type Claude Code/OpenCode, Neovim, tmux, images, claviers riches).

#### B. kmp-terminal-emulator + pty4j21 + Compose

**Ce que c'est.** Une bibliothèque Kotlin Multiplatform qui fournit un moteur VT (`terminal-core`) et un rendu
Compose (`terminal-ui`) : `TerminalEmulator.processOutputSync`, `ScreenBuffer`, `TerminalRenderer`, sélection,
recherche, OSC 52, Kitty graphics, souris, alt screen.

**Points forts**

- **pur Kotlin, aucune native à packager** : le jar fait quelques centaines de Ko, avec seulement coroutines et
  kotlinx-datetime ;
- **rendu Compose prêt à l'emploi**, donc pas de Canvas à écrire pour la première version ;
- licence Apache-2.0, compatible avec un projet commercial ;
- architecture propre : le moteur est indépendant de l'UI, ce qui permet de l'exécuter côté serveur ;
- utilisé en production par un client SSH Android (d'après le README), donc le socle n'est pas théorique.

**Points faibles**

- projet jeune : version 1.0.6, 4 étoiles, un mainteneur ; il faudra suivre les releases et probablement forker
  pour les correctifs critiques ;
- pas de benchmark indépendant publié ; à mesurer sur nos charges (build logs, `htop`, `vim`, gros `cat`) ;
- la fidélité n'est pas celle de libghostty sur les protocoles les plus récents (Kitty keyboard complet,
  certaines séquences exotiques) ; à vérifier avec `vttest` et des TUI réels ;
- pas de gestion d'onglets intégrée : c'est à nous de la faire (comme avec libghostty).

**Verdict.** Le meilleur rapport effort/risque pour livrer vite : ●●. C'est mon choix par défaut pour la première
intégration, avec une interface `TerminalEngine` pour pouvoir passer à libghostty ensuite.

#### C. BossTerm compose

**Ce que c'est.** Une application terminal complète (onglets, splits, recherche, images, MCP, partage de session)
dont le cœur et le composable sont publiés sur Maven Central.

**Points forts**

- **clé en main** : `EmbeddableTerminal` fournit le rendu, le clavier, la souris, la recherche, les images, les
  notifications, l'intégration shell ;
- très riche fonctionnellement, et le rendu est performant (l'auteur annonce ~1,6 Go/s en débit brut, à prendre
  comme un chiffre marketing tant qu'on ne l'a pas reproduit) ;
- licence Apache-2.0 possible (le README est dual LGPL/Apache, mais le POM publié indique LGPL 3.0 : à clarifier
  avant usage).

**Points faibles**

- **tout sauf léger** : le jar `bossterm-compose-desktop` fait ~9,8 Mo et tire Ktor client/serveur, le SDK MCP,
  Supabase realtime, zxing, pty4j et JNA ; c'est une application, pas une petite brique ;
- conçu pour lancer des process locaux via `PlatformServices` ; pour notre architecture distante, il faudrait
  adapter ce service au RPC, ce qui n'est pas le chemin prévu ;
- licence LGPL 3.0 dans le POM : acceptable en liaison dynamique non modifiée, mais à valider si le projet est
  distribué ou modifié ;
- surface d'API large et mouvante (1.2.x), couplage fort à son écosystème.

**Verdict.** Si l'objectif était « un terminal complet en une semaine », ce serait tentant. Pour un IDE léger avec
serveur distant, le poids et le couplage sont disqualifiants.

#### D. JediTerm (+ pty4j)

**Ce que c'est.** L'émulateur Java/Swing historique de JetBrains, très mature, utilisé pendant des années dans
IntelliJ.

**Points forts** : pure Java, Swing, 256 couleurs, souris, onglets, licence Apache-2.0/LGPL-3.0, intégration rapide
via `SwingPanel`.

**Points faibles** : c'est précisément ce que JetBrains vient de remplacer. Il manque les protocoles modernes
(Kitty keyboard, séquences APC/SOS/PM, couleurs SGR à séparateurs `:`, marges, XTSAVE/XTRESTORE…). Le rendu Swing
jure avec Compose et complique le thème, le DPI et l'accessibilité. À réserver à une référence de comparaison, pas
à un nouveau développement.

#### E. Les autres

- **libvterm** : petit, propre, MIT, mais moins moderne et sans binding Java. Neovim est en train de le remplacer
  par libghostty-vt ; repartir dessus en 2026 n'a pas de sens pour ce projet.
- **alacritty_terminal / termwiz** : excellents cœurs Rust, mais il faut ajouter une chaîne Rust + un pont
  JNI/FFM + un renderer. Le coût dépasse largement le gain pour une équipe Kotlin.
- **xterm.js** : très complet, mais il faut un JCEF/WebView (Chromium), donc plusieurs dizaines de Mo et deux
  boucles d'événements. Contraire à l'objectif « léger ».
- **émulateur maison** : tente, mais un terminal correct (Unicode, graphemes, reflow, modes, souris, protocoles)
  est un projet de plusieurs mois. Le projet a déjà un lexer maison pour la coloration ; un émulateur VT est d'une
  autre ampleur.

---

## 4. Où faire tourner l'émulation ?

C'est la décision architecturale la plus importante, avant même le choix du moteur.

### Option 1 — émulation côté serveur (recommandée)

```
Client Compose                         Serveur
┌──────────────────────┐   binaire     ┌──────────────────────────────┐
│ TerminalView (Canvas)│<──────────────│ TerminalSession              │
│  - grille, curseur   │  snapshots /  │  - PtyProcess (pty4j21)      │
│  - clavier, souris   │  diffs        │  - TerminalEngine (VT)       │
│  - onglets           │──────────────>│  - scrollback, reprise       │
└──────────────────────┘  write/resize └──────────────────────────────┘
```

Avantages :

- **une seule source de vérité** : l'état du terminal vit à côté du process, comme le fichier ;
- **reprise après coupure** naturelle : le serveur renvoie un snapshot puis les diffs, sans rejouer des mégaoctets ;
- **plusieurs clients** et redimensionnements différents gérables (au moins pour l'affichage) ;
- cohérent avec la philosophie du projet : « le serveur fait le travail, le client affiche » ;
- le client reste léger, ce qui compte pour le mode distant.

Coût : le serveur doit charger le moteur (natif pour libghostty), et le protocole doit transporter des snapshots
de cellules (codepoint, fg, bg, attributs) et des diffs. C'est plus de travail que « streamer des octets », mais
c'est la bonne architecture pour un IDE distant.

### Option 2 — émulation côté client

Le serveur streame les octets bruts du PTY, le client parse et affiche.

Avantages : protocole très simple, aucun moteur côté serveur, latence minimale en local.

Inconvénients : à la reconnexion il faut rejouer tout le flux ou maintenir un état côté serveur ; plusieurs clients
avec des tailles différentes deviennent compliqués ; le client doit embarquer le moteur (natif si libghostty). C'est
acceptable pour un premier prototype local, pas pour la cible distante.

**Recommandation : émulation côté serveur, rendu côté client.** On peut commencer par un seul onglet, puis ajouter
les onglets et les splits.

---

## 5. Architecture d'intégration proposée

### 5.1 Modèle

Remplacer l'interface ligne par ligne actuelle :

```kotlin
interface ShellProcess {
    val output: Flow<String>   // aujourd'hui
    fun send(line: String)
    fun destroy()
}
```

par un modèle octets + taille :

```kotlin
interface TerminalProcess {
    val output: Flow<ByteArray>
    fun write(bytes: ByteArray)
    fun resize(cols: Int, rows: Int, widthPx: Int, heightPx: Int)
    fun destroy()
}

fun interface TerminalLauncher {
    fun start(directory: String, cols: Int, rows: Int): TerminalProcess
}
```

Le moteur VT est derrière une interface, pour rendre le choix réversible :

```kotlin
interface TerminalEngine : AutoCloseable {
    fun write(bytes: ByteArray)
    fun resize(cols: Int, rows: Int)
    fun snapshot(): TerminalSnapshot
    fun encodeKey(event: KeyEvent): ByteArray
    fun encodeMouse(event: MouseEvent): ByteArray
}
```

### 5.2 Protocole

Ajouter des méthodes dédiées, sans réutiliser `ShellSend` (qui est ligne par ligne) :

| Méthode | Sens | Charge |
|---|---|---|
| `TerminalStart` | client → serveur | répertoire, colonnes, lignes, taille en pixels |
| `TerminalWrite` | client → serveur | octets (clavier, collage, réponses du terminal) |
| `TerminalResize` | client → serveur | colonnes, lignes, pixels |
| `TerminalResume` | client → serveur | dernier numéro de séquence reçu |
| `TerminalDestroy` | client → serveur | id |
| `TerminalSnapshot` | serveur → client | état complet (cellules, curseur, modes, titre, cwd) |
| `TerminalDiff` | serveur → client | lignes/cellules modifiées, numérotées |
| `TerminalEvent` | serveur → client | titre, cwd, cloche, notification, fin de process |

Les réponses du terminal (DSR, DA, OSC 52, etc.) doivent repartir vers le PTY : c'est le moteur qui les produit,
le serveur les écrit dans le process. C'est un point à ne pas oublier, sinon `vim`/`tmux` se comportent mal.

### 5.3 Côté serveur

- `ServerTerminal` : `PtyProcessBuilder` (pty4j21), `TERM=xterm-256color`, `COLORTERM=truecolor`, `LANG`, `SHELL`,
  `setInitialColumns/Rows`, `setUseWinConPty(true)` sur Windows, `directory` ;
- lecture par **chunks d'octets** (pas `readLine`), avec un plafond et une politique de backpressure ;
- un `TerminalEngine` par session, alimenté par les octets du PTY ;
- `resize` : `WinSize` sur Windows, `UnixPtyFileDescriptor.resize` sur Unix (pty4j21 expose les deux) ;
- `destroy` : descendants puis process, comme le fait déjà `ServerShell` ;
- backlog borné + snapshot périodique pour la reprise ; ne pas garder tout le flux brut en mémoire.

### 5.4 Côté client

- `TerminalState` : liste d'onglets, un `TerminalViewState` par onglet (titre, cwd, running, sélection) ;
- rendu Compose Canvas : mesurer la cellule avec JetBrains Mono, dessiner les fonds, les glyphes, le curseur,
  la sélection ; virtualiser le scrollback (le projet sait déjà faire pour l'éditeur) ;
- clavier : traduire les événements Compose en octets via le moteur (Kitty keyboard / `modifyOtherKeys` si
  libghostty), gérer Ctrl/Alt/Shift, IME, collage bracketed ;
- souris : modes X10/SGR, molette, sélection, clic sur les liens ;
- onglets : `Ctrl+T`, `Ctrl+W`, `Ctrl+Tab`, titre = OSC 0/2 ou nom du shell, cwd = OSC 7 ;
- accessibilité : exposer le texte de la grille, pas seulement un Canvas.

### 5.5 Tests

- unitaires : parsing VT (couleurs, curseur, alt screen, reflow, Unicode) sur des séquences de référence ;
- intégration PTY : `echo`, ANSI, `stty size`, redimensionnement, `exit`, kill des enfants ;
- TUI réels : `vim`, `htop`, `less`, `tmux`, un agent qui utilise le Kitty keyboard ;
- protocole : snapshot + diffs à travers `ChaosProxy`, coupure, doublons, reprise ;
- E2E Compose : ouverture `Alt+F12`, commande, couleurs, onglets, collage, redimensionnement ;
- performance : débit de parsing (Mo/s), latence clavier, coût mémoire d'un scrollback de 10 000 lignes ;
- benchmark reproductible dans `benchmark/`, pas seulement des chiffres marketing.

---

## 6. Recommandation

### 6.1 Choix

**Choix retenu : `pty4j21` + `libghostty-vt` + rendu Compose, émulation côté serveur.** C'est la meilleure fidélité, la
meilleure performance et la meilleure pérennité ; c'est aussi ce que JetBrains vient de valider en production.

La stratégie en deux temps décrite plus bas (passer d'abord par `kmp-terminal-emulator`) a été écartée : elle aurait
demandé d'écrire deux fois le rendu et le clavier, pour un gain de temps faible. L'interface `TerminalEngine` reste en
place, mais elle est implémentée directement sur libghostty ; `kmp-terminal-emulator` reste le repli documenté ci-dessous
si les natives devenaient bloquantes.

**Implémentation livrée** (voir `docs/TERMINAL.md` §5 et le code) :

- bindings FFM `io.github.vlaaad:ghosttyfx-linux-x86_64:0.1.237` (GhosttyFX), épinglés dans le catalogue de versions ;
- `GhosttyEngine` côté serveur : `ghostty_terminal_vt_write`, lecture du render state, encodage clavier
  (`ghostty_key_event_*`) et écriture directe des octets dans le PTY ;
- `ServerTerminal` : `pty4j21`, `TERM=xterm-256color`, `COLORTERM=truecolor`, lecture par chunks, `resize`, kill des
  descendants, snapshot complet (pas de diffs) et reprise ;
- protocole : `TerminalStart/Write/Resize/Resume/Destroy` + `TerminalSnapshot` avec cellules, style, curseur et titre ;
- client : `TerminalState`/`TerminalTab` pour les onglets, `TerminalPanel`/`TerminalRenderer`/`TerminalInput`/
  `TerminalSelection` pour le Canvas, le clavier (Kitty), la souris et la sélection ;
- le terminal capture le clavier (`GlobalShortcuts` ignore tout sauf `F12`), les touches sont encodées côté serveur ;
- tests : E2E `shared/test/e2e/TerminalE2eTest.kt` (commande, couleurs, redimensionnement, onglets, texte, Entrée).

### 6.2 Ce qu'il ne faut pas faire

- **BossTerm** : trop lourd (Ktor, MCP, Supabase, JNA) et pensé comme une application, pas comme une brique
  distante ; la licence LGPL du POM ajoute une contrainte.
- **JediTerm** : c'est la technologie que JetBrains remplace ; on hériterait de ses limites modernes.
- **xterm.js / JCEF** : contraire à l'objectif de légèreté.
- **PTY maison** : le gain par rapport à pty4j21 est nul, le risque (Windows/ConPTY, fork) est élevé.

### 6.3 Découpage (état réel)

1. ✅ **Socle PTY + protocole binaire** : `TerminalStart/Write/Resize/Destroy`, `ServerTerminal` avec pty4j21,
   remplacement de l'ancien shell ligne par ligne, tests d'intégration.
2. ✅ **Moteur + rendu** : `GhosttyEngine` + libghostty-vt, `TerminalPanel`/`TerminalRenderer`, clavier Kitty,
   souris, collage, redimensionnement.
3. ✅ **Onglets et gestion de session** : `TerminalState`/`TerminalTab`, titres, arrêt propre, focus clavier.
4. ✅ **Snapshot/reprise** : snapshot complet côté serveur, `TerminalResume`, tests E2E.
5. **Reste à faire** : scrollback visible et virtualisé, recherche dans le terminal, liens cliquables, splits.

### 6.4 Risques

| Risque | Parade |
|---|---|
| API libghostty instable | épingler un commit, régénérer les bindings, tests de non-régression, garder kmp en repli |
| Natives par plateforme | CI matrix Linux x86_64/aarch64, macOS x86_64/arm64, Windows x86_64 ; SHA-256 ; embarquer dans la distribution |
| pty4j21 jeune | tests Windows/macOS en CI ; repli pty4j 0.14.0 si bloquant (même API) |
| Débit de sortie | chunks, backpressure, plafond de scrollback, benchmark |
| Sécurité | le PTY est une exécution de code par conception : auth par token déjà présente, TLS à faire, pas d'accès hors projet sans confirmation |
| Licence | libghostty MIT, kmp Apache-2.0, pty4j21 EPL-1.0 (non modifié), BossTerm LGPL à éviter |

---

## 7. Sources

- JetBrains, « The Terminal in JetBrains IDEs Now Runs on libghostty », octobre 2026 :
  <https://blog.jetbrains.com/platform/2026/10/jetbrains-terminal-now-uses-ghostty/>
- Ghostling, terminal minimal sur l'API C de libghostty (render state, pas de renderer, limites) :
  <https://github.com/ghostty-org/ghostling>
- En-tête `libghostty-vt` (avertissement API instable, `ghostty_terminal_vt_write`, render state) :
  <https://github.com/ghostty-org/ghostty/blob/main/include/ghostty/vt/terminal.h>
- jlibghostty, bindings FFM Java 25 (API haut niveau, Nix-first, non publié sur Maven Central) :
  <https://gitea.gregorlohaus.com/gregor/jlibghostty>
- GhosttyFX, terminal JavaFX sur libghostty, avec bindings FFM et natives Maven Central :
  <https://github.com/vlaaad/ghosttyfx>
- kmp-terminal-emulator, moteur Kotlin + rendu Compose, Apache-2.0 :
  <https://github.com/JohNan/kmp-terminal-emulator>
- BossTerm, terminal Compose avec `EmbeddableTerminal`, licence duale :
  <https://github.com/kshivang/BossTerm>
- pty4j (JNA) : <https://github.com/JetBrains/pty4j>
- pty4j21 (FFM, Java 22+) : <https://github.com/sshtools/pty4j21>
- JediTerm : <https://github.com/JetBrains/jediterm>
- libvterm : <https://github.com/neovim/libvterm>
- Neovim, remplacement de libvterm par libghostty-vt :
  <https://github.com/neovim/neovim/issues/33155>

---

## 8. Décision prise

**`pty4j21` + `libghostty-vt` + bindings FFM GhosttyFX + rendu Compose, émulation côté serveur.** C'est l'option
« fidélité maximale tout de suite », retenue et implémentée le 30 octobre 2026.

L'option « livrer vite sans natif » (`pty4j21` + `kmp-terminal-emulator`) reste le repli documenté : même PTY, même
protocole, mêmes onglets, seule l'implémentation de `TerminalEngine` changerait.
