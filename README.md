# JasperReports Editor pour IntelliJ IDEA

Éditeur avancé des rapports JasperReports 7 (`.jrxml`) dans IntelliJ IDEA 2026.1+ : arborescence,
compilation avec la bibliothèque JasperReports du projet et aperçu page par page en images.

Spécification : https://claude.ai/artifact/WcjWLV7adppbVnjuZ3F4SV

## Prérequis

- JDK 21 pour construire le plugin (imposé par IntelliJ 2026.1 ; Gradle peut le télécharger grâce au plugin foojay).
- Projets ouverts : JDK 17 minimum (le runner et les rapports s'exécutent sur le JDK du module).
- Accès à Maven Central, au portail Gradle et aux dépôts JetBrains (premier build : téléchargement d'IntelliJ IDEA 2026.1).

## Commandes

```bash
./gradlew runIde        # lance un IntelliJ de test avec le plugin
./gradlew test          # tests du plugin : format, symboles, références, inspections (voir ci-dessous)
./gradlew :runner:test  # tests du moteur d'aperçu avec le vrai JasperReports 7.0.8
                        # (rapport : runner/build/reports/runner/samples-compile.txt)
./gradlew :runner:test -PjasperVersion=7.1.0   # mêmes tests avec JasperReports 7.1.0
./gradlew buildPlugin   # archive ZIP installable dans build/distributions/
./gradlew verifyPlugin  # Plugin Verifier (ENF-08)
```

Jeux de test :
- `jasperreports-master-7.1.0/demo/samples` : 171 exemples officiels au format 7 (code source décompressé
  à la racine, exclu de Git) ;
- `rapports de tests/jasper perso` : 30 rapports métier au format 7 (Studio 7.0.1 et 7.0.3), avec `$R{}`,
  `<import>` et sous-rapports `"x.jasper"`.

Les dossiers absents sont ignorés ; l'ancien format est testé avec des exemples intégrés au test.

## Organisation

| Dossier | Rôle |
| --- | --- |
| `src/main/kotlin/.../filetype` | Types de fichier `.jrxml` et `.jrtx` (EF-01) |
| `src/main/kotlin/.../format` | Détection format 6 / 7 (section 3) |
| `src/main/kotlin/.../dom` | Modèle DOM du format 7 sur le PSI XML (section 10.2) |
| `src/main/kotlin/.../structure` | Fenêtre « Jasper Report » : arborescence synchronisée (EF-04 à EF-14) |
| `src/main/kotlin/.../preview` | Éditeur composé XML + aperçu PNG (EF-03, EF-60 à EF-67) |
| `src/main/kotlin/.../project` | JDK, classpath et version JasperReports du module (section 10.3) |
| `src/main/kotlin/.../runner` | Client du runner et protocole JSON (section 10.4) |
| `src/main/kotlin/.../symbols` | Déclarations, portées et occurrences `$F{}` `$P{}` `$V{}` `$R{}`, vérifications, sans IntelliJ (section 7 et 8) |
| `src/main/kotlin/.../references` | Références, navigation, renommage, bundles `$R{}`, sous-rapports (EF-31 à EF-36, EF-38) |
| `src/main/kotlin/.../completion` | Complétion après `$F{` `$P{` `$V{` `$R{` (EF-32) |
| `src/main/kotlin/.../injection` | Expressions en Java, requêtes en SQL (EF-30, EF-37, EF-39) |
| `src/main/kotlin/.../inspections` | Inspections JasperReports (EF-40 à EF-45) |
| `src/main/kotlin/.../properties` | Panneau de propriétés : schéma, lecture / écriture du XML, interface (EF-20 à EF-28) |
| `src/main/kotlin/.../diagnostics` | Diagnostics du moteur dans l'éditeur, compilation à l'enregistrement (EF-49 à EF-51) |
| `src/main/kotlin/.../actions` | Compile Report (EF-47), New > JasperReports Report (EF-07) |
| `runner/` | Programme Java lancé dans une JVM séparée avec le classpath du module |

Le JAR du runner est copié dans `<plugin>/runner/jasper-runner.jar`, hors du classpath de l'IDE : le moteur
JasperReports, les pilotes JDBC et les classes du projet ne sont jamais chargés dans IntelliJ (ENF-04).

## État d'avancement

Fait :
- reconnaissance des `.jrxml`, détection du format 6 avec bandeau ;
- modèle DOM des déclarations, sections, bandes et éléments ;
- arborescence avec navigation dans les deux sens, filtre par saisie, reconstruction après modification ;
- compilation et aperçu PNG via le runner (adaptateurs Studio, paramètres, sous-rapports compilés à la volée) ;
- références `$F{}` `$P{}` `$V{}` `$R{}` (navigation, Find Usages, renommage, complétion), styles, groupes,
  sous-jeux de données et sous-rapports `"x.jasper"` ;
- expressions éditées comme du Java (complétion, erreurs), requêtes SQL injectées si le langage SQL existe ;
- inspections : symbole inconnu (avec création), doublon, débordement de bande, classe introuvable,
  déclaration inutilisée ;
- panneau de propriétés par catégorie, valeurs par défaut en gris, validation, propriétés personnalisées,
  attributs inconnus, sélection multiple ;
- aperçu : réglages mémorisés dans `.idea/jasperreports.xml`, zoom, numéro de page, rendu au-delà de 50 pages ;
- arborescence : ajouter, supprimer (avec liste des usages), dupliquer, monter / descendre, nœuds en erreur ;
- erreurs du moteur dans l'éditeur et la fenêtre Problems, Compile All Reports (menu Build), compilation à
  l'enregistrement (Settings › Tools › JasperReports) ;
- Plugin Verifier : compatible avec IntelliJ 2026.1, 2026.2 et 2026.3 (EAP).

À faire : sources CSV / JSON / JDBC configurées dans le plugin, complétion dans le panneau de propriétés.

## Points à vérifier

Le module doit être compilé (`mvn compile` ou Build Project) avant l'aperçu : les expressions des rapports
utilisent les classes du projet (par exemple `com.samos.api.territory.functions.*`).
