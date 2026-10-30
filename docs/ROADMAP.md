# Feuille de route

Ce document liste ce qui reste à faire. La partie 1 est le prochain chantier, décidé et détaillé ; la partie 2 rassemble les
fonctionnalités intéressantes à planifier ensuite.

---

## 1. Chantier « Find in Files avec ripgrep » (décidé, à faire)

### Décision

Faire comme VS Code : la recherche plein texte est confiée à **ripgrep**, exécuté **côté serveur** (là où sont les
fichiers), sans index. Pourquoi :

- ripgrep est natif, parallèle, vectorisé : c'est le grep le plus rapide reconnu ;
- il respecte `.gitignore`, `.ignore`, `.rgignore` et les exclusions globales de Git ;
- il gère les expressions régulières, les mots entiers et les filtres de fichiers sans effort de notre part ;
- pas d'index à construire ni à maintenir (pas de phase « indexing… », pas de mémoire consommée).

Mesures de référence (projet généré de 10 000 fichiers / 42 Mo, machine 20 cœurs) à battre ou égaler :
recherche actuelle ≈ 80 à 95 ms (320 ms à froid), `grep -rni` ≈ 65 ms.

Alternatives écartées pour l'instant, gardées en réserve : moteur Kotlin en mémoire (≈ 6 à 11 ms mais coût mémoire et
cache à tenir à jour), index de trigrammes à la IntelliJ ou Zoekt (pour les très gros dépôts).

### Étapes

1. **Fourniture du binaire ripgrep**
   - Version épinglée (par exemple 14.1.1) et **somme SHA-256 vérifiée**, comme detekt dans `./lint`.
   - Binaires officiels (GitHub `BurntSushi/ripgrep`) par plateforme : Linux x86_64/aarch64 (musl, statique),
     macOS x86_64/arm64, Windows x86_64.
   - Embarqué dans la distribution du serveur au packaging ; à défaut, téléchargé au premier usage dans
     `~/.kotlin-editor/tools/ripgrep/<version>/` (avec vérification).
   - Surcharge possible : `--ripgrep <chemin>` / variable `KOTLIN_EDITOR_RIPGREP`.

2. **Moteur `RipgrepSearch`** (module `server`, implémente `ProjectSearch.findInFiles`)
   - Lancement sans shell : `rg --json --fixed-strings (--ignore-case | --case-sensitive) --max-columns 500
     --max-columns-preview -- <requête> <racine>` (`--` avant la requête : pas d'injection d'option).
   - Lecture du flux JSON ligne par ligne (`begin`, `match`, `end`, `summary`) : chemin, numéro de ligne, texte,
     sous-correspondances.
   - **Attention** : les positions de ripgrep sont en **octets UTF-8** ; les convertir en colonnes de caractères.
   - Limite de résultats : arrêter et tuer le processus une fois atteinte.
   - Annulation : l'annulation de la coroutine (déjà propagée par le protocole) tue le processus et ses enfants.
   - Garde-fous : délai maximal, taille maximale d'une ligne de résultat, chemins confinés à la racine du projet.

3. **Résultats en continu** (le gain le plus visible)
   - Nouvelle méthode de protocole `FindInFilesStream` : l'id de requête sert d'id de flux (comme `ShellStart`), le
     serveur pousse des lots de résultats numérotés, puis une fin avec le total.
   - Généraliser les événements de flux du protocole (aujourd'hui `List<String>`) en charge utile binaire typée ;
     la reprise après coupure et le dédoublonnage restent les mêmes.
   - Côté client : `Flow<TextMatch>` ; le popup Find in Files affiche les résultats dès le premier lot, avec
     « N résultats… » pendant la recherche.

4. **Liste des fichiers par `rg --files`**
   - `walkFiles` (Go to File, symboles) passe aussi par ripgrep, donc respecte `.gitignore`.
   - Mise en cache par projet, invalidée par le bouton de rafraîchissement, la synchronisation au retour du focus
     et les opérations sur les fichiers.

5. **Options de recherche dans l'UI** (comme IntelliJ et VS Code)
   - Respect de la casse (existe déjà), **mot entier** (`-w`), **expression régulière** (retirer `--fixed-strings` ;
     afficher l'erreur si l'expression est invalide), **filtre de fichiers** (`-g "*.kt"`), **portée** (un dossier
     du projet), case « inclure les fichiers ignorés » (`--no-ignore`).

6. **Fichiers ouverts non enregistrés**
   - Le client cherche dans les documents modifiés en mémoire et ces résultats **remplacent** ceux du disque pour
     les mêmes fichiers (comme VS Code et Zed).

7. **Repli**
   - Si ripgrep est absent ou échoue : moteur Kotlin actuel (`LocalSearch`), avec une mention dans le popup.

8. **Tests**
   - Unitaires : analyse du JSON de ripgrep (jeux d'exemples, y compris caractères multi-octets et lignes tronquées).
   - Intégration avec le vrai binaire : casse, mot entier, regex (valide et invalide), filtre de fichiers,
     `.gitignore` respecté, limite, annulation qui tue le processus.
   - Protocole : flux de résultats repris sans trou ni doublon à travers le proxy de pannes (`ChaosProxy`).
   - E2E UI : résultats affichés progressivement, options du popup, fichier non enregistré trouvé, repli quand
     ripgrep est absent.
   - Banc de mesure sur le jeu de 10 000 fichiers, comparé aux chiffres ci-dessus.

9. **Finitions** : `./lint` à zéro, README mis à jour (fourniture de ripgrep, options).

### Questions à trancher au démarrage

- Embarquer ripgrep dans la distribution ou le télécharger au premier usage (serveur sans accès Internet ?).
- Regex désactivée par défaut ? (proposition : oui, comme IntelliJ.)

---

## 2. Fonctionnalités intéressantes (à prioriser)

Légende de l'effort : ● petit · ●● moyen · ●●● gros.

### Déjà commencées, à finir

- **Éditeur scindé (Split Right)** : le modèle à deux panneaux existe, l'UI n'affiche encore qu'un panneau ;
  ajouter le menu des onglets (Close, Close Others, Close All, Split Right, Unsplit) et les tests. ●●
- **Mémoriser les préférences** (option « Always Select Opened File », taille de police, panneaux) : aujourd'hui
  elles reviennent aux valeurs par défaut à chaque ouverture. ●

### Recherche et navigation

- **Classement des résultats** à la Zoekt et GitHub : définitions de symboles d'abord, puis fichiers récents ou
  proches. ●
- **Search Everywhere** (double Shift) unifié : fichiers, symboles, actions, réglages dans une seule fenêtre. ●●
- **Recent Locations** (Ctrl+Shift+E) et historique avant/arrière complet (Ctrl+Alt+→). ●
- **Fenêtre TODO** : liste des `TODO`/`FIXME` du projet (via ripgrep). ●
- **Marque-pages** (F11) et liste des marque-pages. ●
- **Recherche structurelle** (motifs de code sur l'arbre syntaxique, comme Structural Search ou ast-grep). ●●●
- **Recherche sémantique** en langage naturel (index vectoriel, comme Cursor ou Copilot). ●●●
- **Index de trigrammes** pour les très gros dépôts, derrière l'interface `ProjectSearch`. ●●●

### Intelligence du code

- **Intégration LSP** (Language Server Protocol), la plus forte valeur ajoutée : diagnostics (erreurs et warnings
  soulignés), complétion, Go to Definition exact, Find Usages, renommage, formatage, infobulles. Pour Kotlin,
  `kotlin-lsp` (JetBrains) ; puis d'autres langages gratuitement. À exécuter côté serveur. ●●●
- **Coloration par tree-sitter** : plus juste que l'analyseur actuel à base d'expressions, et multi-langage. ●●
- Complétion simple sans LSP (mots du fichier et du projet). ●

### Éditeur

- **Curseurs multiples** : Alt+clic, Alt+J (occurrence suivante), sélection en colonne (Alt+Shift+glisser). ●●
- **Marqueurs dans la barre de défilement** : résultats de recherche, erreurs, modifications Git. ●
- Guides d'indentation, espaces visibles, retour à la ligne automatique (soft wrap). ●
- **Sticky lines** : la déclaration englobante reste affichée en haut de l'éditeur. ●●
- Parenthèses colorées par niveau (rainbow brackets). ●
- Mode « gros fichiers » (lecture seule, sans coloration, au-delà d'une taille). ●●

### Git

- ✅ **Panneau Git** (`Alt+9`) : liste des fichiers modifiés par rapport à HEAD, lettres et couleurs à la IntelliJ,
  clic pour ouvrir la différence du fichier dans un onglet.
- ✅ **Visionneuse de différences** : diff unifié avec anciens/nouveaux numéros de ligne, ajouts en vert et suppressions
  en rouge ; les fichiers non suivis s'affichent en ajouts.
- ✅ **Visionneuse de différences côte à côte** : bascule depuis la barre de la visionneuse, ancien et nouveau fichier
  alignés ligne à ligne.
- ✅ **Marques de modification dans la gouttière** : lignes ajoutées (vert), modifiées (bleu) et supprimées (triangle)
  par rapport à HEAD, à côté des numéros de ligne. ●●
- ✅ **Commit, changement de branche, pull/push depuis l'IDE** : champ de message et bouton Commit (indexe tout),
  menu des branches locales, boutons Pull et Push dans le panneau Git, avec le résultat ou l'erreur affiché. ●●●
- ✅ **Blame** : bouton *Annotate* dans le panneau Git, auteur et date de chaque ligne affichés dans la gouttière. ●●
- **Historique local** (à la IntelliJ) : instantanés automatiques des fichiers, filet de sécurité indépendant de
  Git. ●●

### Exécution et terminal

- ✅ **Vrai terminal (PTY)** (`Alt+F12`) : PTY `pty4j21` côté serveur, émulation `libghostty-vt`, rendu Canvas
  Compose, onglets, couleurs ANSI, clavier Kitty, sélection et reprise après coupure. Voir `docs/TERMINAL.md`.
- **Configurations d'exécution** (tâches Amper ou Gradle, `main`) avec sortie cliquable vers les erreurs. ●●●
- Lanceur de tests avec arbre des résultats. ●●●

### Serveur, distant et sécurité

- **Chiffrement TLS** des connexions distantes : important, aujourd'hui le jeton et le code circulent en clair
  hors du réseau local. ●●
- **Notifications du serveur** : surveillance des fichiers côté serveur poussée aux clients (rechargement immédiat
  plutôt qu'au retour du focus). ●●
- Plusieurs clients sur le même serveur, puis édition collaborative. ●●●

### Réglages et ergonomie

- **Fenêtre de réglages** persistés (police, thème, raccourcis, options). ●●
- **Thème clair** et choix du thème. ●
- Raccourcis personnalisables (le clavier est déjà décrit par des tables, ça s'y prête bien). ●●

### Qualité et distribution

- **Intégration continue** (GitHub Actions) : `./kotlin test` et `./lint` à chaque push. ●
- **Installeurs** (jpackage : .deb, .msi, .dmg) avec le serveur et ripgrep embarqués. ●●
- Journalisation et rapport d'erreurs. ●
- Tests de captures d'écran (non-régression visuelle). ●●
