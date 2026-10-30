# Journal des modifications

Toutes les évolutions notables de JasperReports Editor sont consignées dans ce fichier.
Le format suit [Keep a Changelog](https://keepachangelog.com/fr/1.1.0/) et le projet respecte le
[versionnage sémantique](https://semver.org/lang/fr/). La section de la version en cours est publiée comme
notes de version du plugin.

## [Unreleased]

## [1.0.0] - 2026-10-30

Première version, pour IntelliJ IDEA 2026.1 et suivants (Community et Ultimate) et les modèles de rapports
JasperReports 7.0.x / 7.1.0.

### Ajouté

- Type de fichier `.jrxml` pour les modèles JasperReports 7, avec un bandeau sur les fichiers JasperReports 6
  (ancien format).
- Fenêtre **Jasper Report** : arborescence du rapport (déclarations, sections, bandes, éléments), navigation
  dans les deux sens avec l'éditeur, filtrage à la frappe, nœuds en erreur mis en évidence avec le problème
  en infobulle.
- Édition de la structure depuis l'arborescence : ajouter, supprimer (avec la liste des utilisations),
  dupliquer, monter / descendre.
- Panneau de propriétés par catégorie : valeurs par défaut du moteur et valeurs héritées en gris, validation
  des valeurs, énumérations de JasperReports 7.1.0, propriétés personnalisées, attributs inconnus, sélection
  multiple.
- Références pour `$F{}`, `$P{}`, `$V{}` et `$R{}` : navigation, recherche des utilisations, renommage,
  complétion ; également pour les styles, groupes, sous-datasets, clés de ressources et sous-rapports
  `"x.jasper"` (vers `x.jrxml`).
- Expressions éditées en Java : complétion, symboles et méthodes introuvables, imports du rapport et
  bibliothèques de fonctions JasperReports ; SQL injecté dans les requêtes quand le langage SQL est
  disponible (Ultimate).
- Inspections : symbole inconnu (avec correctif rapide qui crée la déclaration), déclaration en double,
  élément qui dépasse de sa bande, classe introuvable, déclaration inutilisée.
- Compilation avec la bibliothèque JasperReports du projet (classpath du module Maven, classes de test
  incluses) : erreurs du moteur affichées dans l'éditeur et la fenêtre Problems, **Build › Compile All
  Reports**, compilation à l'enregistrement en option (**Settings › Tools › JasperReports**).
- Aperçu PNG page par page : adaptateurs de données Jaspersoft Studio (y compris les adaptateurs de beans),
  paramètres du rapport, sous-rapports compilés à la volée, zoom, numéro de page, rendu au-delà de 50 pages
  à la demande, journal du runner ; réglages d'aperçu mémorisés par rapport dans `.idea/jasperreports.xml`.
