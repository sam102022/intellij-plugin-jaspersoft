package com.samos.jasperreports.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.sf.jasperreports.engine.JasperReport;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Lecture de l'adaptateur de données Jaspersoft Studio déclaré par le rapport
 * (`com.jaspersoft.studio.data.defaultdataadapter`), pour que l'aperçu utilise les mêmes données que Studio.
 *
 * <p>Adaptateurs pris en charge : beanDataAdapter, csvDataAdapter, jsonDataAdapter, jdbcDataAdapter.
 * Le résultat est une configuration de source de données du protocole (types bean, csv, json, jdbc).</p>
 */
final class DataAdapters {

    static final String STUDIO_ADAPTER_PROPERTY = "com.jaspersoft.studio.data.defaultdataadapter";

    private DataAdapters() {
    }

    /** @return la configuration équivalente, ou {@code null} si le rapport ne déclare pas d'adaptateur utilisable. */
    static ObjectNode resolve(JasperReport report, Path jrxml, JsonNode searchRoots, List<Map<String, Object>> diagnostics) {
        String declared = report.getProperty(STUDIO_ADAPTER_PROPERTY);
        if (declared == null || declared.isBlank()) {
            return null;
        }
        Path adapterFile = locate(declared, jrxml, searchRoots);
        if (adapterFile == null) {
            diagnostics.add(Diagnostics.diagnostic("warning",
                    "Data adapter " + declared + " not found; preview uses an empty data source.", null));
            return null;
        }
        try {
            return parse(adapterFile, jrxml, searchRoots);
        } catch (Exception e) {
            diagnostics.add(Diagnostics.diagnostic("warning",
                    "Data adapter " + adapterFile + " could not be read (" + e.getMessage() + "); preview uses an empty data source.",
                    adapterFile.toString()));
            return null;
        }
    }

    /** Studio enregistre des chemins relatifs au projet, souvent avec des « \ » : on essaie les racines du module, puis le dossier du rapport. */
    static Path locate(String declared, Path jrxml, JsonNode searchRoots) {
        String normalized = declared.trim().replace('\\', '/');
        Path direct = Path.of(normalized);
        if (direct.isAbsolute()) {
            return Files.isRegularFile(direct) ? direct : null;
        }
        List<Path> candidates = new ArrayList<>();
        for (JsonNode root : searchRoots) {
            candidates.add(Path.of(root.asText()).resolve(normalized));
        }
        // En remontant depuis le dossier du rapport, jusqu'à la racine du disque.
        for (Path dir = jrxml.getParent(); dir != null; dir = dir.getParent()) {
            candidates.add(dir.resolve(normalized));
        }
        candidates.add(jrxml.resolveSibling(direct.getFileName()));
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate.normalize();
            }
        }
        return null;
    }

    private static ObjectNode parse(Path adapterFile, Path jrxml, JsonNode searchRoots) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document document = factory.newDocumentBuilder().parse(adapterFile.toFile());
        Element root = document.getDocumentElement();
        String name = text(root, "name");
        ObjectNode config = ReportRunner.JSON.createObjectNode();

        switch (root.getTagName()) {
            case "beanDataAdapter" -> {
                config.put("type", "bean");
                config.put("factoryClass", required(root, "factoryClass"));
                config.put("methodName", required(root, "methodName"));
                config.put("useFieldDescription", Boolean.parseBoolean(text(root, "useFieldDescription")));
                config.put("description", describe(name, text(root, "factoryClass") + "." + text(root, "methodName") + "()"));
            }
            case "csvDataAdapter" -> {
                config.put("type", "csv");
                config.put("path", dataFile(root, adapterFile, jrxml, searchRoots).toString());
                String delimiter = text(root, "fieldDelimiter");
                if (delimiter != null && !delimiter.isEmpty()) {
                    config.put("delimiter", delimiter);
                }
                String encoding = text(root, "encoding");
                if (encoding != null && !encoding.isEmpty()) {
                    config.put("charset", encoding);
                }
                config.put("description", describe(name, config.get("path").asText()));
            }
            case "jsonDataAdapter" -> {
                config.put("type", "json");
                config.put("path", dataFile(root, adapterFile, jrxml, searchRoots).toString());
                String select = text(root, "selectExpression");
                if (select != null && !select.isEmpty()) {
                    config.put("selectExpression", select);
                }
                config.put("description", describe(name, config.get("path").asText()));
            }
            case "jdbcDataAdapter" -> {
                config.put("type", "jdbc");
                config.put("url", required(root, "url"));
                putIfPresent(config, "driver", text(root, "driver"));
                putIfPresent(config, "user", text(root, "username"));
                putIfPresent(config, "password", text(root, "password"));
                config.put("description", describe(name, config.get("url").asText()));
            }
            default -> throw new IllegalArgumentException("unsupported adapter type <" + root.getTagName() + ">");
        }
        return config;
    }

    /** Fichier de données : `<fileName>` ou `<dataFile><location>`, relatif à l'adaptateur ou au projet. */
    private static Path dataFile(Element root, Path adapterFile, Path jrxml, JsonNode searchRoots) {
        String location = text(root, "fileName");
        if (location == null || location.isEmpty()) {
            location = text(root, "location");
        }
        if (location == null || location.isEmpty()) {
            throw new IllegalArgumentException("no data file");
        }
        location = location.replaceFirst("^repo:", "");
        Path besideAdapter = adapterFile.resolveSibling(location.replace('\\', '/'));
        if (Files.isRegularFile(besideAdapter)) {
            return besideAdapter.normalize();
        }
        Path found = locate(location, jrxml, searchRoots);
        if (found == null) {
            throw new IllegalArgumentException("data file " + location + " not found");
        }
        return found;
    }

    private static String describe(String name, String detail) {
        return (name == null || name.isEmpty() ? "" : name + ": ") + detail;
    }

    private static void putIfPresent(ObjectNode node, String key, String value) {
        if (value != null && !value.isEmpty()) {
            node.put(key, value);
        }
    }

    private static String required(Element root, String tag) {
        String value = text(root, tag);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("missing <" + tag + ">");
        }
        return value;
    }

    /** Texte du premier élément descendant portant ce nom. */
    private static String text(Element root, String tag) {
        NodeList nodes = root.getElementsByTagName(tag);
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                return node.getTextContent().trim();
            }
        }
        return null;
    }
}
