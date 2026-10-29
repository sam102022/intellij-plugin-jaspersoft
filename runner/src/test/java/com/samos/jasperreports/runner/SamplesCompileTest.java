package com.samos.jasperreports.runner;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Compile tous les exemples officiels de JasperReports 7.1.0 (demo/samples) avec le runner.
 *
 * <p>Tous ne compilent pas ici (langages Groovy/JavaScript, extensions absentes du classpath de test, classes
 * Java propres à chaque exemple) : le test vérifie que chaque fichier produit une et une seule réponse, sans
 * plantage, et qu'une large majorité compile. Le détail est écrit dans build/reports/runner/samples-compile.txt.</p>
 */
class SamplesCompileTest {

    @TempDir
    Path cache;

    @Test
    void everySampleGetsExactlyOneAnswer() throws IOException {
        String samples = System.getProperty("jrxml.samples", "");
        assumeTrue(!samples.isEmpty() && Files.isDirectory(Path.of(samples)), "demo/samples not available: " + samples);

        List<Path> files;
        try (Stream<Path> walk = Files.walk(Path.of(samples))) {
            files = walk.filter(p -> p.toString().endsWith(".jrxml"))
                    .filter(p -> !p.toString().contains(java.io.File.separator + "target" + java.io.File.separator))
                    .sorted().toList();
        }
        assumeTrue(!files.isEmpty(), "no .jrxml under " + samples);

        RunnerHarness harness = new RunnerHarness(cache);
        TreeMap<String, String> failures = new TreeMap<>();
        List<String> crashes = new ArrayList<>();
        int ok = 0;
        for (Path file : files) {
            String name = Path.of(samples).relativize(file).toString();
            JsonNode result;
            try {
                result = RunnerHarness.result(harness.run(harness.request("compile", file)));
            } catch (Throwable t) {
                crashes.add(name + " -> " + t);
                continue;
            }
            switch (result.path("status").asText()) {
                case "ok" -> ok++;
                case "error" -> {
                    if (result.path("diagnostics").isEmpty()) {
                        crashes.add(name + " -> error without diagnostics");
                    } else {
                        failures.put(name, firstMessage(result));
                    }
                }
                default -> crashes.add(name + " -> unexpected status " + result);
            }
        }

        StringBuilder report = new StringBuilder()
                .append(files.size()).append(" samples, ").append(ok).append(" compiled, ")
                .append(failures.size()).append(" with diagnostics, ").append(crashes.size()).append(" crashes\n\n");
        failures.forEach((name, message) -> report.append(name).append("\n    ").append(message).append('\n'));
        crashes.forEach(c -> report.append("CRASH ").append(c).append('\n'));
        writeReport(report.toString());
        System.out.println(report);

        assertEquals(List.of(), crashes);
        assertTrue(ok * 10 >= files.size() * 7,
                "less than 70% of the samples compile, see build/reports/runner/samples-compile.txt\n" + report);
    }

    private static String firstMessage(JsonNode result) {
        String message = result.path("diagnostics").get(0).path("message").asText();
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }

    private static void writeReport(String text) throws IOException {
        String dir = System.getProperty("runner.reports");
        if (dir != null) {
            Files.createDirectories(Path.of(dir));
            Files.writeString(Path.of(dir, "samples-compile.txt"), text);
        }
    }
}
