package com.samos.jasperreports.runner;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Mise en forme des erreurs du moteur pour la fenêtre Problems (EF-50, EF-51). */
final class Diagnostics {

    /** Début d'une erreur numérotée dans le message de compilation des expressions : "1. ...". */
    private static final Pattern NUMBERED = Pattern.compile("(?m)^\\s*(\\d+)\\.\\s+");

    private Diagnostics() {
    }

    static Map<String, Object> diagnostic(String severity, String message, String source) {
        Map<String, Object> diagnostic = new LinkedHashMap<>();
        diagnostic.put("severity", severity);
        diagnostic.put("message", message);
        if (source != null) {
            diagnostic.put("source", source);
        }
        return diagnostic;
    }

    /**
     * Découpe le message « Errors were encountered when compiling report expressions class file » en une
     * erreur par expression. La ligne de code qui suit chaque message sert à retrouver la balise côté plugin.
     */
    static List<Map<String, Object>> fromCompileError(Exception e) {
        String text = e.getMessage() == null ? e.toString() : e.getMessage();
        List<Map<String, Object>> javac = fromJavacOutput(text);
        if (!javac.isEmpty()) {
            return javac;
        }
        List<Map<String, Object>> result = new ArrayList<>();
        Matcher matcher = NUMBERED.matcher(text);
        List<Integer> starts = new ArrayList<>();
        while (matcher.find()) {
            starts.add(matcher.start());
        }
        if (starts.isEmpty()) {
            result.add(diagnostic("error", text.trim(), null));
            return result;
        }
        for (int i = 0; i < starts.size(); i++) {
            int end = i + 1 < starts.size() ? starts.get(i + 1) : text.length();
            String[] lines = text.substring(starts.get(i), end).trim().split("\\R", 3);
            String message = lines[0].replaceFirst("^\\d+\\.\\s+", "");
            Map<String, Object> diagnostic = diagnostic("error", message, null);
            if (lines.length > 1) {
                diagnostic.put("code", lines[1].trim());
            }
            result.add(diagnostic);
        }
        return result;
    }

    /** Erreur du compilateur javac (JRJdk13Compiler, celui par défaut de JasperReports 7) : « X.java:77: error: msg ». */
    private static final Pattern JAVAC_ERROR = Pattern.compile("(?m)^.*?\\.java:\\d+: error: (.*)$");
    private static final Pattern EXPRESSION_ID = Pattern.compile("\\s*//\\$JR_EXPR_ID=\\d+\\$\\s*$");

    /**
     * Une erreur par expression : javac signale chaque expression jusqu'à trois fois (valeur courante, ancienne,
     * estimée), on ne garde donc qu'une occurrence par couple message + code.
     */
    static List<Map<String, Object>> fromJavacOutput(String text) {
        List<Map<String, Object>> result = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        String[] lines = text.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            Matcher matcher = JAVAC_ERROR.matcher(lines[i]);
            if (!matcher.matches()) {
                continue;
            }
            String message = matcher.group(1).trim();
            String code = i + 1 < lines.length ? EXPRESSION_ID.matcher(lines[i + 1]).replaceFirst("").trim() : "";
            if (code.startsWith("value = ")) {
                code = code.substring("value = ".length());
            }
            if (code.endsWith(";")) {
                code = code.substring(0, code.length() - 1);
            }
            if (!seen.add(message + '\n' + code)) {
                continue;
            }
            Map<String, Object> diagnostic = diagnostic("error", message, null);
            if (!code.isEmpty()) {
                diagnostic.put("code", code);
            }
            result.add(diagnostic);
        }
        return result;
    }

    /** Type d'élément ou de composant inconnu du moteur : son extension n'est pas dans le classpath du module. */
    private static final Pattern UNKNOWN_TYPE = Pattern.compile("Could not resolve type id '([^']+)'");
    private static final Pattern XML_LINE = Pattern.compile("line: (\\d+), column: (\\d+)");

    /** Langage de requête ou d'expressions fourni par une extension séparée de JasperReports 7. */
    private static final Pattern UNKNOWN_QUERY_LANGUAGE = Pattern.compile("No query executer factory registered for the \"([^\"]+)\" language");
    private static final Pattern UNKNOWN_COMPILER = Pattern.compile("Could not instantiate report compiler: net\\.sf\\.jasperreports\\.(\\w+)\\.");

    private static String languageArtifact(String language) {
        return switch (language.toLowerCase(java.util.Locale.ROOT)) {
            case "groovy" -> "jasperreports-groovy";
            case "javascript" -> "jasperreports-javascript";
            case "hql" -> "jasperreports-hibernate";
            case "ejbql" -> "jasperreports-ejbql";
            case "mdx", "olap4j", "olapnj" -> "jasperreports-olap";
            case "xls", "xlsx", "excel" -> "jasperreports-excel-poi";
            case "json", "jsonql" -> "jasperreports-json";
            case "xpath", "xpath2" -> "jasperreports-xpath";
            default -> null;
        };
    }

    static Map<String, Object> missingExtension(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            String message = String.valueOf(t.getMessage());
            Matcher language = UNKNOWN_QUERY_LANGUAGE.matcher(message);
            Matcher compiler = UNKNOWN_COMPILER.matcher(message);
            String what = null;
            String name = null;
            if (language.find()) {
                name = language.group(1);
                what = "Query language \"" + name + "\"";
            } else if (compiler.find()) {
                name = compiler.group(1);
                what = "Expression language \"" + name + "\"";
            }
            if (what != null) {
                String artifact = languageArtifact(name);
                return diagnostic("error", artifact != null
                        ? what + " needs the net.sf.jasperreports:" + artifact + " dependency, with the same version as jasperreports, in the module."
                        : what + " is provided by a JasperReports extension missing from the module.", null);
            }
            Matcher type = UNKNOWN_TYPE.matcher(message);
            if (!type.find()) {
                continue;
            }
            String kind = type.group(1);
            String artifact = kind.startsWith("barcode4j:") ? "jasperreports-barcode4j"
                    : switch (kind) {
                        case "chart", "spiderChart" -> "jasperreports-charts";
                        case "barbecue" -> "jasperreports-barbecue";
                        default -> null;
                    };
            Matcher line = XML_LINE.matcher(message);
            String where = line.find() ? " (line " + line.group(1) + ")" : "";
            String text = artifact != null
                    ? "Element kind \"" + kind + "\"" + where + " needs the net.sf.jasperreports:" + artifact
                      + " dependency, with the same version as jasperreports, in the module."
                    : "Unknown element kind \"" + kind + "\"" + where + ": a JasperReports extension is missing from the module.";
            Map<String, Object> diagnostic = diagnostic("error", text, null);
            if (line.find(0)) {
                diagnostic.put("line", Integer.parseInt(line.group(1)));
            }
            return diagnostic;
        }
        return null;
    }

    /** Préfixe « package.Classe: » ajouté par les exceptions qui en enveloppent d'autres. */
    private static final Pattern EXCEPTION_PREFIX = Pattern.compile("^(?:[\\w$]+\\.)+[\\w$]*(?:Exception|Error): ");

    /**
     * Erreur au remplissage ou au rendu : le message le plus parlant de la chaîne (souvent « Error evaluating
     * expression for source text: … ») et, à part, la cause d'origine (par exemple un fichier ou une URL introuvable).
     */
    static Map<String, Object> fromFailure(Throwable error) {
        System.err.println(stackTrace(error));
        Throwable root = error;
        String message = null;
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (message == null && t.getMessage() != null && t.getMessage().startsWith("Error evaluating expression")) {
                message = t.getMessage();
            }
            root = t;
        }
        if (message == null) {
            message = error.getMessage() == null ? error.getClass().getName() : error.getMessage();
            // Message réduit à un nom (méthode, classe, fichier) : préciser de quelle erreur il s'agit.
            if (error.getMessage() != null && !error.getMessage().contains(" ")) {
                message = error.getClass().getSimpleName() + ": " + message;
            }
        }
        message = strip(message);
        Map<String, Object> diagnostic = diagnostic("error", message, null);
        if (root != error || !message.contains(String.valueOf(root.getMessage()))) {
            String cause = root.getClass().getSimpleName() + (root.getMessage() == null ? "" : ": " + strip(root.getMessage()));
            if (!message.contains(cause)) {
                diagnostic.put("code", "Caused by " + cause);
            }
        }
        return diagnostic;
    }

    private static String strip(String message) {
        String result = message.trim();
        Matcher prefix;
        while ((prefix = EXCEPTION_PREFIX.matcher(result)).find()) {
            result = result.substring(prefix.end());
        }
        return result;
    }

    private static String stackTrace(Throwable t) {
        StringWriter writer = new StringWriter();
        t.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }

    static String describe(Throwable t) {
        StringWriter writer = new StringWriter();
        t.printStackTrace(new PrintWriter(writer));
        String message = t.getMessage() == null ? t.getClass().getName() : t.getMessage();
        System.err.println(writer);
        return message;
    }
}
