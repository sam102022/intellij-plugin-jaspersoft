package com.samos.jasperreports.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.sf.jasperreports.engine.JasperReport;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Point d'entrée de la JVM séparée (section 10.4 de la spécification).
 *
 * <p>Protocole : une requête JSON par ligne sur l'entrée standard, une réponse JSON par ligne sur la
 * sortie standard. Tout ce que le moteur ou les bibliothèques écrivent sur {@code System.out} est
 * redirigé vers la sortie d'erreur, qui alimente le journal du plugin.</p>
 *
 * <p>Actions : {@code compile}, {@code preview}, {@code cancel}, {@code shutdown}.</p>
 */
public final class ReportRunner {

    static final int PROTOCOL_VERSION = 1;
    static final ObjectMapper JSON = new ObjectMapper();

    private final PrintStream protocolOut;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "jasper-runner-worker");
        t.setDaemon(true);
        return t;
    });
    private volatile Future<?> current;

    ReportRunner(PrintStream protocolOut) { // accessible au paquet pour les tests
        this.protocolOut = protocolOut;
    }

    public static void main(String[] args) throws IOException {
        System.setProperty("java.awt.headless", "true");
        PrintStream protocolOut = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        System.setOut(System.err);
        ReportRunner runner = new ReportRunner(protocolOut);
        runner.loop(new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)));
        System.exit(0);
    }

    private void loop(BufferedReader in) throws IOException {
        Map<String, Object> ready = new LinkedHashMap<>();
        ready.put("type", "ready");
        ready.put("protocol", PROTOCOL_VERSION);
        ready.put("jasperVersion", jasperVersion());
        ready.put("javaVersion", System.getProperty("java.version"));
        send(ready);

        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode request;
            try {
                request = JSON.readTree(line);
            } catch (IOException e) {
                send(errorResult(-1, "Unreadable request: " + e.getMessage()));
                continue;
            }
            long id = request.path("id").asLong(-1);
            switch (request.path("action").asText()) {
                case "shutdown" -> {
                    worker.shutdownNow();
                    return;
                }
                case "cancel" -> {
                    Future<?> running = current;
                    if (running != null) {
                        running.cancel(true);
                    }
                }
                case "compile", "preview" -> current = worker.submit(() -> handle(id, request));
                default -> send(errorResult(id, "Unknown action: " + request.path("action").asText()));
            }
        }
        worker.shutdownNow();
    }

    void handle(long id, JsonNode request) {
        ReportJob job = new ReportJob(this, id, request);
        try {
            job.run();
        } catch (InterruptedException e) {
            send(result(id, "cancelled", 0, List.of()));
        } catch (Throwable t) {
            // L'erreur d'abord, puis les avertissements qui l'expliquent souvent.
            List<Map<String, Object>> diagnostics = new ArrayList<>();
            diagnostics.add(Diagnostics.fromFailure(t));
            diagnostics.addAll(job.diagnostics());
            send(result(id, "error", 0, diagnostics));
        }
    }

    synchronized void send(Map<String, Object> message) {
        try {
            protocolOut.println(JSON.writeValueAsString(message));
        } catch (IOException e) {
            e.printStackTrace(System.err);
        }
    }

    static Map<String, Object> result(long id, String status, int pages, List<Map<String, Object>> diagnostics) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("id", id);
        message.put("type", "result");
        message.put("status", status);
        message.put("pages", pages);
        message.put("diagnostics", diagnostics);
        return message;
    }

    static Map<String, Object> errorResult(long id, String message) {
        return result(id, "error", 0, List.of(Diagnostics.diagnostic("error", message, null)));
    }

    private static String jasperVersion() {
        Package p = JasperReport.class.getPackage();
        String version = p == null ? null : p.getImplementationVersion();
        return version == null ? "unknown" : version;
    }
}
