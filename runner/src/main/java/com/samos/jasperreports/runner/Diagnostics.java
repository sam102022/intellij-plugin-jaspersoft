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

    static String describe(Throwable t) {
        StringWriter writer = new StringWriter();
        t.printStackTrace(new PrintWriter(writer));
        String message = t.getMessage() == null ? t.getClass().getName() : t.getMessage();
        System.err.println(writer);
        return message;
    }
}
