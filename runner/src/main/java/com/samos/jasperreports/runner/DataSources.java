package com.samos.jasperreports.runner;

import com.fasterxml.jackson.databind.JsonNode;
import net.sf.jasperreports.engine.JRDataSource;
import net.sf.jasperreports.engine.JREmptyDataSource;
import net.sf.jasperreports.engine.JasperFillManager;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.JasperReport;
import net.sf.jasperreports.engine.JasperReportsContext;
import net.sf.jasperreports.engine.data.JRBeanArrayDataSource;
import net.sf.jasperreports.engine.data.JRBeanCollectionDataSource;
import net.sf.jasperreports.engine.data.JRCsvDataSource;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Sources de données de l'aperçu (EF-71) : auto (adaptateur Studio du rapport, sinon vide), vide, beans,
 * CSV, JSON, JDBC.
 */
final class DataSources {

    private static final String JSON_DATA_SOURCE = "net.sf.jasperreports.json.data.JsonDataSource";

    private DataSources() {
    }

    /**
     * @param describe reçoit une description lisible de la source réellement utilisée (affichée dans l'aperçu)
     */
    static JasperPrint fill(JasperReportsContext context, JasperReport report, Map<String, Object> parameters,
                            JsonNode config, Path jrxml, List<Map<String, Object>> diagnostics,
                            Consumer<String> describe) throws Exception {
        JasperFillManager fillManager = JasperFillManager.getInstance(context);
        String type = config.path("type").asText("auto");
        switch (type) {
            case "auto" -> {
                JsonNode adapter = DataAdapters.resolve(report, jrxml, config.path("searchRoots"), diagnostics);
                if (adapter != null) {
                    return fill(context, report, parameters, adapter, jrxml, diagnostics, describe);
                }
                describe.accept("empty data source (1 record)");
                return fillManager.fill(report, parameters, new JREmptyDataSource(1));
            }
            case "empty" -> {
                int records = config.path("records").asInt(1);
                describe.accept("empty data source (" + records + " record(s))");
                return fillManager.fill(report, parameters, new JREmptyDataSource(records));
            }
            case "bean" -> {
                describe.accept(config.path("description").asText(config.path("factoryClass").asText()));
                return fillManager.fill(report, parameters, beans(config));
            }
            case "csv" -> {
                describe.accept(config.path("description").asText(config.path("path").asText()));
                try (InputStream in = Files.newInputStream(Path.of(config.path("path").asText()))) {
                    JRCsvDataSource dataSource = new JRCsvDataSource(in, config.path("charset").asText("UTF-8"));
                    dataSource.setUseFirstRowAsHeader(true);
                    String delimiter = config.path("delimiter").asText(",");
                    if (!delimiter.isEmpty()) {
                        dataSource.setFieldDelimiter(delimiter.charAt(0));
                    }
                    return fillManager.fill(report, parameters, dataSource);
                }
            }
            case "json" -> {
                describe.accept(config.path("description").asText(config.path("path").asText()));
                // JsonDataSource appartient à l'extension jasperreports-json, chargée par réflexion.
                Class<?> jsonClass;
                try {
                    jsonClass = Class.forName(JSON_DATA_SOURCE);
                } catch (ClassNotFoundException e) {
                    throw new IllegalStateException("JSON data source needs the jasperreports-json dependency in the module.");
                }
                File file = new File(config.path("path").asText());
                String select = config.path("selectExpression").asText("");
                JRDataSource dataSource = select.isEmpty()
                        ? (JRDataSource) jsonClass.getConstructor(File.class).newInstance(file)
                        : (JRDataSource) jsonClass.getConstructor(File.class, String.class).newInstance(file, select);
                return fillManager.fill(report, parameters, dataSource);
            }
            case "jdbc" -> {
                describe.accept(config.path("description").asText(config.path("url").asText()));
                String driver = config.path("driver").asText("");
                if (!driver.isEmpty()) {
                    Class.forName(driver);
                }
                try (Connection connection = DriverManager.getConnection(
                        config.path("url").asText(),
                        config.path("user").asText(null),
                        config.path("password").asText(null))) {
                    return fillManager.fill(report, parameters, connection);
                }
            }
            default -> throw new IllegalArgumentException("Unknown data source type: " + type);
        }
    }

    /**
     * Même comportement que le BeanDataAdapterService de JasperReports : méthode statique sans argument de la
     * classe fabrique, qui renvoie une collection ou un tableau de beans.
     */
    private static JRDataSource beans(JsonNode config) throws Exception {
        String factoryClass = config.path("factoryClass").asText();
        String methodName = config.path("methodName").asText();
        boolean useFieldDescription = config.path("useFieldDescription").asBoolean(false);
        Class<?> factory;
        try {
            factory = Class.forName(factoryClass, true, Thread.currentThread().getContextClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Data adapter factory " + factoryClass
                    + " not found on the module classpath. Build the project (test classes included) and retry.");
        }
        Method method = factory.getMethod(methodName);
        if (!Modifier.isStatic(method.getModifiers())) {
            throw new IllegalStateException(factoryClass + "." + methodName + "() must be static.");
        }
        Object result;
        try {
            result = method.invoke(null);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new IllegalStateException(factoryClass + "." + methodName + "() failed: " + cause, cause);
        }
        if (result instanceof Collection<?> collection) {
            return new JRBeanCollectionDataSource(collection, useFieldDescription);
        }
        if (result instanceof Object[] array) {
            return new JRBeanArrayDataSource(array, useFieldDescription);
        }
        throw new IllegalStateException(factoryClass + "." + methodName
                + "() must return a Collection or an array, not " + (result == null ? "null" : result.getClass().getName()));
    }
}
