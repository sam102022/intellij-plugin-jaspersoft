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
import net.sf.jasperreports.engine.DefaultJasperReportsContext;
import net.sf.jasperreports.repo.FileRepositoryPersistenceServiceFactory;
import net.sf.jasperreports.repo.FileRepositoryService;
import net.sf.jasperreports.repo.PersistenceServiceFactory;
import net.sf.jasperreports.repo.RepositoryService;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.EnumSet;
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
        context.setExtensions(RepositoryService.class, repositories(context, compiledDir, reportDir));
        // Sans cette fabrique, un FileRepositoryService trouve le fichier mais ne sait pas le charger.
        context.setExtensions(PersistenceServiceFactory.class, persistenceFactories());

        JasperReport report;
        try {
            report = JasperCompileManager.getInstance(context).compile(jrxml.toString());
        } catch (JRValidationException e) {
            for (JRValidationFault fault : e.getFaults()) {
                diagnostics.add(Diagnostics.diagnostic("error", fault.getMessage(), describeSource(fault.getSource())));
            }
            finish("error", 0);
            return;
        } catch (JRException | RuntimeException e) {
            // Élément d'une extension absente (chart, codes-barres…) : message dédié avec l'artefact à ajouter.
            Map<String, Object> missing = Diagnostics.missingExtension(e);
            if (missing != null) {
                diagnostics.add(missing);
            } else if (e instanceof JRException) {
                diagnostics.addAll(Diagnostics.fromCompileError(e));
            } else {
                throw e;
            }
            finish("error", 0);
            return;
        }

        if ("compile".equals(request.path("action").asText())) {
            finish("ok", 0);
            return;
        }

        List<Path> roots = new ArrayList<>();
        request.path("dataSource").path("searchRoots").forEach(root -> roots.add(Path.of(root.asText())));
        compileSubreports(context, jrxml, reportDir, compiledDir, new SourceIndex(roots), new HashSet<>());
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

    /**
     * Compile à la volée les .jrxml des sous-rapports référencés par un chemin littéral ("x.jasper"), récursivement.
     * Le moteur cherche "x.jasper" dans le cache puis dans le dossier du rapport principal : on ne compile donc que si
     * ce dossier n'a pas de x.jasper plus récent que sa source. La source est cherchée à côté du rapport qui
     * l'appelle, à côté du rapport principal, puis dans tout le module.
     */
    private void compileSubreports(SimpleJasperReportsContext context, Path jrxml, Path mainDir, Path compiledDir,
                                   SourceIndex index, Set<Path> visited) throws IOException, InterruptedException {
        if (!visited.add(jrxml.normalize())) {
            return;
        }
        Matcher matcher = SUBREPORT_LITERAL.matcher(Files.readString(jrxml));
        Set<String> seen = new HashSet<>();
        while (matcher.find()) {
            checkInterrupted();
            String name = matcher.group(1);
            if (!seen.add(name)) {
                continue;
            }
            Path besideMain = mainDir.resolve(name + ".jasper");
            Path cached = compiledDir.resolve(name + ".jasper");
            Path source = index.find(name, jrxml.getParent(), mainDir);
            if (source == null) {
                if (!Files.isRegularFile(besideMain) && !Files.isRegularFile(cached)) {
                    diagnostics.add(Diagnostics.diagnostic("warning",
                            "Subreport " + name + ".jasper is used by " + jrxml.getFileName()
                                    + " but neither " + name + ".jasper nor " + name + ".jrxml was found in the module.",
                            jrxml.toString()));
                }
                continue;
            }
            if (isUpToDate(besideMain, source)) {
                Files.deleteIfExists(cached);
            } else if (!isUpToDate(cached, source)) {
                try {
                    Files.createDirectories(cached.getParent());
                    JasperCompileManager.getInstance(context).compileToFile(source.toString(), cached.toString());
                } catch (JRException | RuntimeException e) {
                    Map<String, Object> missing = Diagnostics.missingExtension(e);
                    diagnostics.add(missing != null ? missing : Diagnostics.diagnostic("warning",
                            "Subreport " + source.getFileName() + " could not be compiled: " + e.getMessage(), source.toString()));
                    continue;
                }
            }
            compileSubreports(context, source, mainDir, compiledDir, index, visited);
        }
    }

    /** Dépôts : sous-rapports compilés, dossier du rapport, puis ceux par défaut (classpath, URL). */
    private static List<RepositoryService> repositories(SimpleJasperReportsContext context, Path compiledDir, Path reportDir) {
        List<RepositoryService> services = new ArrayList<>();
        services.add(new FileRepositoryService(context, compiledDir.toString(), false));
        services.add(new FileRepositoryService(context, reportDir.toString(), false));
        services.addAll(DefaultJasperReportsContext.getInstance().getExtensions(RepositoryService.class));
        return services;
    }

    private static List<PersistenceServiceFactory> persistenceFactories() {
        List<PersistenceServiceFactory> factories = new ArrayList<>();
        factories.add(FileRepositoryPersistenceServiceFactory.getInstance());
        factories.addAll(DefaultJasperReportsContext.getInstance().getExtensions(PersistenceServiceFactory.class));
        return factories;
    }

    /** Avertissements accumulés avant une erreur (adaptateur introuvable, sous-rapport non compilé…). */
    List<Map<String, Object>> diagnostics() {
        return diagnostics;
    }

    /** Index paresseux des .jrxml du module, par nom de fichier, hors dossiers de build. */
    private static final class SourceIndex {
        private static final Set<String> SKIPPED = Set.of("target", "build", "out", "bin", "node_modules", ".git", ".idea", ".gradle");
        private final List<Path> roots;
        private Map<String, Path> byName;

        SourceIndex(List<Path> roots) {
            this.roots = roots;
        }

        Path find(String name, Path callerDir, Path mainDir) throws IOException {
            for (Path dir : List.of(callerDir, mainDir)) {
                Path candidate = dir.resolve(name + ".jrxml");
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
            if (byName == null) {
                byName = new HashMap<>();
                for (Path root : roots) {
                    if (!Files.isDirectory(root)) {
                        continue;
                    }
                    Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), 20, new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            return !dir.equals(root) && SKIPPED.contains(String.valueOf(dir.getFileName()))
                                    ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            String fileName = file.getFileName().toString();
                            if (fileName.endsWith(".jrxml")) {
                                byName.putIfAbsent(fileName, file);
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException e) {
                            return FileVisitResult.CONTINUE;
                        }
                    });
                }
            }
            return byName.get(Path.of(name + ".jrxml").getFileName().toString());
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
