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
./gradlew test          # tests de détection du format (voir ci-dessous)
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
| `src/main/kotlin/.../actions` | Compile Report (EF-47), New > JasperReports Report (EF-07) |
| `runner/` | Programme Java lancé dans une JVM séparée avec le classpath du module |

Le JAR du runner est copié dans `<plugin>/runner/jasper-runner.jar`, hors du classpath de l'IDE : le moteur
JasperReports, les pilotes JDBC et les classes du projet ne sont jamais chargés dans IntelliJ (ENF-04).

## État d'avancement (sprint 0)

Fait :
- reconnaissance des `.jrxml`, détection du format 6 avec bandeau ;
- modèle DOM des déclarations, sections, bandes et éléments ;
- arborescence avec navigation dans les deux sens, filtre par saisie, reconstruction après modification ;
- compilation et aperçu PNG via le runner, avec compilation à la volée des sous-rapports référencés
  par un chemin littéral (`"sub.jasper"`).

À faire :
- panneau de propriétés (sprint 3) ;
- références `$F{}`, `$P{}`, `$V{}`, `$R{}`, injection Java et inspections ;
- diagnostics dans la fenêtre Problems ;
- configuration des données de test et des paramètres (l'aperçu utilise pour l'instant une source vide
  d'un enregistrement).

## Points à vérifier au premier build

Ce squelette a été écrit sans accès aux dépôts JetBrains : le runner a été compilé contre les signatures
de JasperReports 7.1.0, et la détection de format testée sur les 30 rapports, mais le plugin Kotlin n'a pas
encore été compilé contre le SDK IntelliJ 2026.1. Corriger d'éventuelles API dépréciées au premier
`./gradlew build`.

Le module doit être compilé (`mvn compile` ou Build Project) avant l'aperçu : les expressions des rapports
utilisent les classes du projet (par exemple `com.samos.api.territory.functions.*`).
