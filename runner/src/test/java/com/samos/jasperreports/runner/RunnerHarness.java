package com.samos.jasperreports.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Exécute une requête comme la boucle du runner, sans processus séparé, et relit les messages JSON produits. */
final class RunnerHarness {

    private final Path cacheDir;
    private long nextId = 1;

    RunnerHarness(Path cacheDir) {
        this.cacheDir = cacheDir;
    }

    ObjectNode request(String action, Path jrxml) {
        ObjectNode request = ReportRunner.JSON.createObjectNode();
        request.put("action", action);
        request.put("jrxml", jrxml.toString());
        request.put("cacheDir", cacheDir.toString());
        request.putObject("parameters");
        request.putObject("dataSource").put("type", "auto");
        request.putObject("render").put("scale", 1.0).put("maxPages", 5)
                .put("outputDir", cacheDir.resolve("pages-" + nextId).toString());
        return request;
    }

    /** Messages émis pour la requête (progress…, puis un unique result). */
    List<JsonNode> run(ObjectNode request) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ReportRunner runner = new ReportRunner(new PrintStream(bytes, true, StandardCharsets.UTF_8));
        long id = nextId++;
        request.put("id", id);
        runner.handle(id, request);
        List<JsonNode> messages = new ArrayList<>();
        for (String line : bytes.toString(StandardCharsets.UTF_8).split("\\R")) {
            if (line.isBlank()) continue;
            try {
                messages.add(ReportRunner.JSON.readTree(line));
            } catch (IOException e) {
                throw new UncheckedIOException("Invalid protocol line: " + line, e);
            }
        }
        return messages;
    }

    static JsonNode result(List<JsonNode> messages) {
        List<JsonNode> results = messages.stream().filter(m -> "result".equals(m.path("type").asText())).toList();
        if (results.size() != 1) {
            throw new AssertionError("Expected exactly one result message, got " + messages);
        }
        return results.get(0);
    }

    static String diagnostics(JsonNode result) {
        return result.path("diagnostics").toString();
    }
}
