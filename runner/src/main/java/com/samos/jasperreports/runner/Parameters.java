package com.samos.jasperreports.runner;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Conversion des valeurs de paramètres saisies dans le formulaire d'aperçu (EF-74). */
final class Parameters {

    private Parameters() {
    }

    static Map<String, Object> convert(JsonNode parameters, List<Map<String, Object>> diagnostics) {
        Map<String, Object> result = new HashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = parameters.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String type = entry.getValue().path("type").asText("java.lang.String");
            JsonNode value = entry.getValue().path("value");
            if (value.isMissingNode() || value.isNull()) {
                continue; // la defaultValueExpression du rapport s'applique (EF-75)
            }
            try {
                result.put(entry.getKey(), convert(type, value.asText()));
            } catch (RuntimeException e) {
                diagnostics.add(Diagnostics.diagnostic("warning",
                        "Parameter " + entry.getKey() + " ignored: cannot convert '" + value.asText() + "' to " + type, null));
            }
        }
        return result;
    }

    private static Object convert(String type, String text) {
        return switch (type) {
            case "java.lang.String" -> text;
            case "java.lang.Integer" -> Integer.valueOf(text.trim());
            case "java.lang.Long" -> Long.valueOf(text.trim());
            case "java.lang.Short" -> Short.valueOf(text.trim());
            case "java.lang.Double" -> Double.valueOf(text.trim());
            case "java.lang.Float" -> Float.valueOf(text.trim());
            case "java.math.BigDecimal" -> new BigDecimal(text.trim());
            case "java.lang.Boolean" -> Boolean.valueOf(text.trim());
            case "java.time.LocalDate" -> LocalDate.parse(text.trim());
            case "java.time.LocalDateTime" -> LocalDateTime.parse(text.trim());
            case "java.util.Date" -> Date.from(LocalDate.parse(text.trim()).atStartOfDay(ZoneId.systemDefault()).toInstant());
            case "java.sql.Timestamp" -> Timestamp.valueOf(LocalDateTime.parse(text.trim()));
            default -> throw new IllegalArgumentException("Unsupported parameter type " + type);
        };
    }
}
