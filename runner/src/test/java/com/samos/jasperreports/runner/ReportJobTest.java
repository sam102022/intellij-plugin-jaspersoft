package com.samos.jasperreports.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Compilation, remplissage et rendu avec le vrai moteur JasperReports 7. */
class ReportJobTest {

    @TempDir
    Path work;

    private RunnerHarness harness;

    @BeforeEach
    void setUp() throws IOException {
        harness = new RunnerHarness(Files.createDirectories(work.resolve("cache")));
    }

    private Path copy(String resource) throws IOException {
        return copy(resource, work);
    }

    private Path copy(String resource, Path dir) throws IOException {
        Path target = dir.resolve(resource);
        try (InputStream in = getClass().getResourceAsStream("/reports/" + resource)) {
            assertNotNull(in, "missing test resource " + resource);
            Files.createDirectories(target.getParent());
            Files.copy(in, target);
        }
        return target;
    }

    @Test
    void compileValidReport() throws IOException {
        JsonNode result = RunnerHarness.result(harness.run(harness.request("compile", copy("Synthetic.jrxml"))));
        assertEquals("ok", result.path("status").asText(), RunnerHarness.diagnostics(result));
        assertEquals(0, result.path("diagnostics").size());
    }

    @Test
    void previewWithBeanDataSourceRendersPng() throws IOException {
        ObjectNode request = harness.request("preview", copy("Synthetic.jrxml"));
        request.putObject("dataSource").put("type", "bean")
                .put("factoryClass", TestBeans.class.getName()).put("methodName", "rows");
        request.putObject("parameters").putObject("TITLE").put("type", "java.lang.String").put("value", "Hello");

        List<JsonNode> messages = harness.run(request);
        JsonNode result = RunnerHarness.result(messages);
        assertEquals("ok", result.path("status").asText(), RunnerHarness.diagnostics(result));
        assertEquals(1, result.path("pages").asInt());

        JsonNode progress = messages.stream().filter(m -> "progress".equals(m.path("type").asText())).findFirst().orElseThrow();
        Path png = Path.of(progress.path("image").asText());
        BufferedImage image = ImageIO.read(png.toFile());
        assertNotNull(image, "page is not a readable PNG");
        assertEquals(595, image.getWidth()); // scale 1.0
        assertTrue(hasDarkPixel(image), "page is blank");
    }

    @Test
    void beanArrayFactoryIsAccepted() throws IOException {
        ObjectNode request = harness.request("preview", copy("Synthetic.jrxml"));
        request.putObject("dataSource").put("type", "bean")
                .put("factoryClass", TestBeans.class.getName()).put("methodName", "rowArray");
        assertEquals("ok", RunnerHarness.result(harness.run(request)).path("status").asText());
    }

    @Test
    void nonStaticFactoryMethodIsReported() throws IOException {
        JsonNode result = previewWithFactory("notStatic");
        assertEquals("error", result.path("status").asText());
        assertTrue(RunnerHarness.diagnostics(result).contains("must be static"), RunnerHarness.diagnostics(result));
    }

    @Test
    void missingFactoryMethodIsExplained() throws IOException {
        String diagnostics = RunnerHarness.diagnostics(previewWithFactory("getJeuxDeDonnees"));
        assertTrue(diagnostics.contains("has no method named getJeuxDeDonnees"), diagnostics);
        assertTrue(diagnostics.contains("rowArray(), rows()"), diagnostics);
    }

    @Test
    void factoryMethodWithParametersIsExplained() throws IOException {
        String diagnostics = RunnerHarness.diagnostics(previewWithFactory("withArgument"));
        assertTrue(diagnostics.contains("it takes parameters (String)"), diagnostics);
    }

    @Test
    void nonPublicFactoryMethodIsExplained() throws IOException {
        String diagnostics = RunnerHarness.diagnostics(previewWithFactory("hidden"));
        assertTrue(diagnostics.contains("it is not public"), diagnostics);
    }

    @Test
    void failingFactoryShowsTheOriginalCause() throws IOException {
        JsonNode result = previewWithFactory("failing");
        assertEquals("error", result.path("status").asText());
        assertTrue(RunnerHarness.diagnostics(result).contains("factory exploded"), RunnerHarness.diagnostics(result));
    }

    @Test
    void unknownFactoryClassIsReported() throws IOException {
        ObjectNode request = harness.request("preview", copy("Synthetic.jrxml"));
        request.putObject("dataSource").put("type", "bean").put("factoryClass", "com.example.Missing").put("methodName", "rows");
        JsonNode result = RunnerHarness.result(harness.run(request));
        assertEquals("error", result.path("status").asText());
        assertTrue(RunnerHarness.diagnostics(result).contains("not found on the module classpath"));
    }

    private JsonNode previewWithFactory(String method) throws IOException {
        ObjectNode request = harness.request("preview", copy("Synthetic.jrxml"));
        request.putObject("dataSource").put("type", "bean")
                .put("factoryClass", TestBeans.class.getName()).put("methodName", method);
        return RunnerHarness.result(harness.run(request));
    }

    @Test
    void studioAdapterDeclaredInTheReportIsUsed() throws IOException {
        Path module = work.resolve("module");
        Path jrxml = copy("Synthetic.jrxml", module.resolve("src/main/resources/reports"));
        // Studio enregistre un chemin relatif au projet, avec des « \ » sous Windows.
        String report = Files.readString(jrxml).replaceFirst("(<jasperReport[^>]*>)",
                "$1\n\t<property name=\"com.jaspersoft.studio.data.defaultdataadapter\" value=\"adapters\\\\beans.xml\"/>");
        Files.writeString(jrxml, report);
        Files.createDirectories(module.resolve("adapters"));
        Files.writeString(module.resolve("adapters/beans.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <beanDataAdapter class="net.sf.jasperreports.data.bean.BeanDataAdapterImpl">
                  <name>Test beans</name>
                  <factoryClass>%s</factoryClass>
                  <methodName>rows</methodName>
                  <useFieldDescription>false</useFieldDescription>
                </beanDataAdapter>
                """.formatted(TestBeans.class.getName()));

        ObjectNode request = harness.request("preview", jrxml);
        request.putObject("dataSource").put("type", "auto").putArray("searchRoots").add(module.toString());
        JsonNode result = RunnerHarness.result(harness.run(request));
        assertEquals("ok", result.path("status").asText(), RunnerHarness.diagnostics(result));
        assertTrue(result.path("dataSource").asText().startsWith("Test beans"), result.toString());
    }

    @Test
    void missingStudioAdapterFallsBackToEmptyDataSource() throws IOException {
        Path jrxml = copy("Synthetic.jrxml");
        Files.writeString(jrxml, Files.readString(jrxml).replaceFirst("(<jasperReport[^>]*>)",
                "$1\n\t<property name=\"com.jaspersoft.studio.data.defaultdataadapter\" value=\"nowhere.xml\"/>"));
        JsonNode result = RunnerHarness.result(harness.run(harness.request("preview", jrxml)));
        assertEquals("ok", result.path("status").asText());
        assertTrue(RunnerHarness.diagnostics(result).contains("nowhere.xml"), RunnerHarness.diagnostics(result));
    }

    @Test
    void validationErrorsAreListed() throws IOException {
        JsonNode result = RunnerHarness.result(harness.run(harness.request("compile", copy("BrokenExpression.jrxml"))));
        assertEquals("error", result.path("status").asText());
        assertTrue(RunnerHarness.diagnostics(result).contains("UNKNOWN_PARAMETER"), RunnerHarness.diagnostics(result));
    }

    @Test
    void javaCompileErrorsAreSplitPerExpression() throws IOException {
        JsonNode result = RunnerHarness.result(harness.run(harness.request("compile", copy("JavaError.jrxml"))));
        assertEquals("error", result.path("status").asText());
        JsonNode first = result.path("diagnostics").get(0);
        assertEquals(1, result.path("diagnostics").size(), RunnerHarness.diagnostics(result));
        assertEquals("cannot find symbol", first.path("message").asText(), RunnerHarness.diagnostics(result));
        assertEquals("\"abc\".noSuchMethod()", first.path("code").asText());
    }

    @Test
    void unknownElementKindNamesTheMissingExtension() throws IOException {
        JsonNode result = RunnerHarness.result(harness.run(harness.request("compile", copy("UnknownKind.jrxml"))));
        assertEquals("error", result.path("status").asText());
        assertTrue(RunnerHarness.diagnostics(result).contains("unknownWidget"), RunnerHarness.diagnostics(result));
    }

    @Test
    void fillErrorKeepsTheRootCause() throws IOException {
        ObjectNode request = harness.request("preview", copy("FillError.jrxml"));
        request.putObject("dataSource").put("type", "empty");
        JsonNode result = RunnerHarness.result(harness.run(request));
        assertEquals("error", result.path("status").asText());
        String diagnostics = RunnerHarness.diagnostics(result);
        assertTrue(diagnostics.contains("ArithmeticException"), diagnostics);
    }

    @Test
    void subreportSourceIsCompiledOnTheFly() throws IOException {
        Path module = work.resolve("module");
        Path child = copy("Synthetic.jrxml", module.resolve("sub"));
        Files.move(child, child.resolveSibling("Child.jrxml"));
        Files.writeString(child.resolveSibling("Child.jrxml"),
                Files.readString(child.resolveSibling("Child.jrxml")).replace("name=\"Synthetic\"", "name=\"Child\""));
        Path parent = module.resolve("main/Parent.jrxml");
        Files.createDirectories(parent.getParent());
        Files.writeString(parent, """
                <jasperReport name="Parent" pageWidth="595" pageHeight="842" columnWidth="555" leftMargin="20" rightMargin="20" topMargin="20" bottomMargin="20">
                	<title height="100">
                		<element kind="subreport" x="0" y="0" width="555" height="100">
                			<dataSourceExpression><![CDATA[new net.sf.jasperreports.engine.data.JRBeanCollectionDataSource(com.samos.jasperreports.runner.TestBeans.rows())]]></dataSourceExpression>
                			<expression><![CDATA["Child.jasper"]]></expression>
                		</element>
                	</title>
                </jasperReport>
                """);
        ObjectNode request = harness.request("preview", parent);
        request.putObject("dataSource").put("type", "empty").putArray("searchRoots").add(module.toString());
        JsonNode result = RunnerHarness.result(harness.run(request));
        assertEquals("ok", result.path("status").asText(), RunnerHarness.diagnostics(result));
        assertTrue(Files.isRegularFile(work.resolve("cache/compiled/Child.jasper")));
    }

    private static boolean hasDarkPixel(BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y += 2) {
            for (int x = 0; x < image.getWidth(); x += 2) {
                int rgb = image.getRGB(x, y);
                int luminance = ((rgb >> 16 & 0xff) + (rgb >> 8 & 0xff) + (rgb & 0xff)) / 3;
                if ((rgb >>> 24) > 0 && luminance < 128) {
                    return true;
                }
            }
        }
        return false;
    }
}
