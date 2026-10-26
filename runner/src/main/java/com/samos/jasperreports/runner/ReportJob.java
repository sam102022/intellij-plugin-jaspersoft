package com.samos.jasperreports.runner;

import com.fasterxml.jackson.databind.JsonNode;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JasperCompileManager;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.JasperPrintManager;
import net.sf.jasperreports.engine.JasperReport;
import net.sf.jasperreports.engine.SimpleJasperReportsContext;
import net.sf.jasperreports.engine.design.JRValidationException;
import net.sf.jasperreports.engine.design.JRValidationFault;
import net.sf.jasperreports.repo.FileRepositoryService;
import net.sf.jasperreports.repo.RepositoryService;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Une requête {@code compile} ou {@code preview}. */
final class ReportJob {

    /** Chemins littéraux de sous-rapports, par exemple {@code "subBookings2024.jasper"}. */
    private static final Pattern SUBREPORT_LITERAL = Pattern.compile("\"([^\"\\\\]+)\\.jasper\"");

    private final ReportRunner runner;
    private final long id;
    private final JsonNode request;
    private final List<Map<String, Object>> diagnostics = new ArrayList<>();
    /** Source de données réellement utilisée, affichée dans l'aperçu. */
    private String dataSourceDescription;

    ReportJob(ReportRunner runner, long id, JsonNode request) {
        this.runner = runner;
        this.id = id;
        this.request = request;
    }

    void run() throws Exception {
        Path jrxml = Path.of(request.path("jrxml").asText()).toAbsolutePath();
        Path reportDir = jrxml.getParent();
        Path cacheDir = Path.of(request.path("cacheDir").asText(
                Path.of(System.getProperty("java.io.tmpdir"), "jasper-runner").toString()));
        Path compiledDir = cacheDir.resolve("compiled");
        Files.createDirectories(compiledDir);

        // Les sous-rapports compilés à la volée sont cherchés avant ceux du dossier du rapport (EF-76).
        SimpleJasperReportsContext context = new SimpleJasperReportsContext();
        context.setExtensions(RepositoryService.class, List.of(
                new FileRepositoryService(context, compiledDir.toString(), false),
                new FileRepositoryService(context, reportDir.toString(), false)));

        JasperReport report;
        try {
            report = JasperCompileManager.getInstance(context).compile(jrxml.toString());
        } catch (JRValidationException e) {
            for (JRValidationFault fault : e.getFaults()) {
                diagnostics.add(Diagnostics.diagnostic("error", fault.getMessage(), describeSource(fault.getSource())));
            }
            finish("error", 0);
            return;
        } catch (JRException e) {
            diagnostics.addAll(Diagnostics.fromCompileError(e));
            finish("error", 0);
            return;
        }

        if ("compile".equals(request.path("action").asText())) {
            finish("ok", 0);
            return;
        }

        compileSubreports(context, jrxml, compiledDir, new HashSet<>());
        checkInterrupted();

        Map<String, Object> parameters = Parameters.convert(request.path("parameters"), diagnostics);
        JasperPrint print = DataSources.fill(context, report, parameters, request.path("dataSource"), jrxml,
                diagnostics, description -> dataSourceDescription = description);
        checkInterrupted();

        JsonNode render = request.path("render");
        Path outputDir = Path.of(render.path("outputDir").asText(cacheDir.resolve("preview-" + id).toString()));
        Files.createDirectories(outputDir);
        float scale = (float) render.path("scale").asDouble(2.0);
        int maxPages = render.path("maxPages").asInt(50);
        int total = print.getPages().size();

        JasperPrintManager printManager = JasperPrintManager.getInstance(context);
        for (int page = 0; page < Math.min(total, maxPages); page++) {
            checkInterrupted();
            Image image = printManager.printToImage(print, page, scale);
            Path png = outputDir.resolve("page-" + (page + 1) + ".png");
            ImageIO.write(toRendered(image), "png", png.toFile());

            Map<String, Object> progress = new LinkedHashMap<>();
            progress.put("id", id);
            progress.put("type", "progress");
            progress.put("page", page);
            progress.put("total", total);
            progress.put("image", png.toString());
            runner.send(progress);
        }
        finish("ok", total);
    }

    /** Compile les .jrxml des sous-rapports référencés par un chemin littéral, s'ils n'ont pas de .jasper à jour. */
    private void compileSubreports(SimpleJasperReportsContext context, Path jrxml, Path compiledDir, Set<Path> visited)
            throws IOException, InterruptedException {
        if (!visited.add(jrxml)) {
            return;
        }
        Matcher matcher = SUBREPORT_LITERAL.matcher(Files.readString(jrxml));
        while (matcher.find()) {
            checkInterrupted();
            String name = matcher.group(1);
            Path source = jrxml.resolveSibling(name + ".jrxml");
            if (!Files.isRegularFile(source)) {
                continue;
            }
            Path besideSource = jrxml.resolveSibling(name + ".jasper");
            Path cached = compiledDir.resolve(name + ".jasper");
            if (isUpToDate(besideSource, source)) {
                Files.deleteIfExists(cached);
            } else if (!isUpToDate(cached, source)) {
                try {
                    Files.createDirectories(cached.getParent());
                    JasperCompileManager.getInstance(context).compileToFile(source.toString(), cached.toString());
                } catch (JRException e) {
                    diagnostics.add(Diagnostics.diagnostic("warning",
                            "Subreport " + source.getFileName() + " could not be compiled: " + e.getMessage(), source.toString()));
                    continue;
                }
            }
            compileSubreports(context, source, compiledDir, visited);
        }
    }

    private static boolean isUpToDate(Path compiled, Path source) throws IOException {
        return Files.isRegularFile(compiled)
                && Files.getLastModifiedTime(compiled).compareTo(Files.getLastModifiedTime(source)) >= 0;
    }

    private void finish(String status, int pages) {
        Map<String, Object> result = ReportRunner.result(id, status, pages, diagnostics);
        if (dataSourceDescription != null) {
            result.put("dataSource", dataSourceDescription);
        }
        runner.send(result);
    }

    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException();
        }
    }

    private static String describeSource(Object source) {
        return source == null ? null : source.toString();
    }

    private static RenderedImage toRendered(Image image) {
        if (image instanceof RenderedImage rendered) {
            return rendered;
        }
        BufferedImage buffered = new BufferedImage(image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = buffered.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.dispose();
        return buffered;
    }
}
