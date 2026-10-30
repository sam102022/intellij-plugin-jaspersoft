package com.samos.jasperreports.runner;

import com.fasterxml.jackson.databind.node.ObjectNode;
import net.sf.jasperreports.engine.JRException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParametersAndDiagnosticsTest {

    private static void put(ObjectNode parameters, String name, String type, String value) {
        ObjectNode node = parameters.putObject(name).put("type", type);
        if (value != null) node.put("value", value);
    }

    @Test
    void parametersAreConverted() {
        ObjectNode json = ReportRunner.JSON.createObjectNode();
        put(json, "S", "java.lang.String", "text");
        put(json, "I", "java.lang.Integer", " 42 ");
        put(json, "B", "java.math.BigDecimal", "12.50");
        put(json, "D", "java.time.LocalDate", "2026-10-29");
        put(json, "L", "java.util.Locale", "fr_FR");
        put(json, "Z", "java.util.TimeZone", "Europe/Paris");
        put(json, "DEFAULT", "java.lang.String", null);
        List<Map<String, Object>> diagnostics = new ArrayList<>();

        Map<String, Object> values = Parameters.convert(json, diagnostics);

        assertEquals("text", values.get("S"));
        assertEquals(42, values.get("I"));
        assertEquals(new BigDecimal("12.50"), values.get("B"));
        assertEquals(LocalDate.of(2026, 10, 29), values.get("D"));
        assertEquals(Locale.FRANCE, values.get("L"));
        assertEquals(TimeZone.getTimeZone("Europe/Paris").getID(), ((TimeZone) values.get("Z")).getID());
        assertFalse(values.containsKey("DEFAULT"), "no value: the report default applies");
        assertTrue(diagnostics.isEmpty());
    }

    @Test
    void badValueIsIgnoredWithAWarning() {
        ObjectNode json = ReportRunner.JSON.createObjectNode();
        put(json, "I", "java.lang.Integer", "abc");
        put(json, "X", "java.util.List", "a,b");
        List<Map<String, Object>> diagnostics = new ArrayList<>();

        Map<String, Object> values = Parameters.convert(json, diagnostics);

        assertTrue(values.isEmpty());
        assertEquals(2, diagnostics.size());
        assertEquals("warning", diagnostics.get(0).get("severity"));
    }

    @Test
    void missingChartExtensionNamesTheArtifact() {
        Exception e = new JRException("wrapped", new RuntimeException(
                "Could not resolve type id 'chart' as a subtype of `JRElement`\n at [Source: (File); line: 42, column: 5]"));
        Map<String, Object> diagnostic = Diagnostics.missingExtension(e);
        assertNotNull(diagnostic);
        assertTrue(diagnostic.get("message").toString().contains("jasperreports-charts"));
        assertEquals(42, diagnostic.get("line"));
    }

    @Test
    void barcode4jKindIsRecognised() {
        Map<String, Object> diagnostic = Diagnostics.missingExtension(
                new RuntimeException("Could not resolve type id 'barcode4j:QRCode'"));
        assertNotNull(diagnostic);
        assertTrue(diagnostic.get("message").toString().contains("jasperreports-barcode4j"));
    }

    @Test
    void otherErrorsAreNotMissingExtensions() {
        assertNull(Diagnostics.missingExtension(new RuntimeException("something else")));
    }

    @Test
    void failureKeepsExpressionMessageAndRootCause() {
        Throwable root = new java.io.FileNotFoundException("https://example.org/missing.png");
        Throwable eval = new RuntimeException("Error evaluating expression for source text: $F{image}", root);
        Throwable top = new RuntimeException("net.sf.jasperreports.engine.JRException: fill failed", eval);

        Map<String, Object> diagnostic = Diagnostics.fromFailure(top);

        assertEquals("Error evaluating expression for source text: $F{image}", diagnostic.get("message"));
        assertEquals("Caused by FileNotFoundException: https://example.org/missing.png", diagnostic.get("code"));
    }

    @Test
    void compileErrorsAreSplit() {
        List<Map<String, Object>> diagnostics = Diagnostics.fromCompileError(new Exception("""
                Errors were encountered when compiling report expressions class file:
                1. The method foo() is undefined
                value = "a".foo();
                2. bar cannot be resolved
                value = bar;
                2 errors
                """));
        assertEquals(2, diagnostics.size());
        assertEquals("The method foo() is undefined", diagnostics.get(0).get("message"));
        assertEquals("value = \"a\".foo();", diagnostics.get(0).get("code"));
    }

    @Test
    void javacErrorsAreSplitAndDeduplicated() {
        List<Map<String, Object>> diagnostics = Diagnostics.fromCompileError(new Exception("""
                Errors were encountered when compiling report expressions class file:
                C:\\Temp\\Bar_1.java:77: error: package com.bar does not exist
                                value = com.bar.SomeBarClass.isToPrint(); //$JR_EXPR_ID=8$
                                               ^
                C:\\Temp\\Bar_1.java:100: error: package com.bar does not exist
                                value = com.bar.SomeBarClass.isToPrint(); //$JR_EXPR_ID=8$
                                               ^
                C:\\Temp\\Bar_1.java:101: error: cannot find symbol
                                value = "a".foo(); //$JR_EXPR_ID=9$
                                           ^
                3 errors
                ."""));
        assertEquals(2, diagnostics.size());
        assertEquals("package com.bar does not exist", diagnostics.get(0).get("message"));
        assertEquals("com.bar.SomeBarClass.isToPrint()", diagnostics.get(0).get("code"));
        assertEquals("cannot find symbol", diagnostics.get(1).get("message"));
    }

    @Test
    void missingQueryLanguageAndCompilerNameTheArtifact() {
        Map<String, Object> hql = Diagnostics.missingExtension(
                new JRException("No query executer factory registered for the \"hql\" language."));
        assertTrue(hql.get("message").toString().contains("jasperreports-hibernate"), hql.toString());
        Map<String, Object> groovy = Diagnostics.missingExtension(new JRException(
                "Could not instantiate report compiler: net.sf.jasperreports.groovy.JRGroovyCompiler."));
        assertTrue(groovy.get("message").toString().contains("jasperreports-groovy"), groovy.toString());
    }
}
