package com.samos.jasperreports.runner;

import java.util.List;

/** Fabrique utilisée par les tests de l'adaptateur bean (même contrat que Jaspersoft Studio). */
public final class TestBeans {

    private TestBeans() {
    }

    public static List<Row> rows() {
        return List.of(new Row("Alpha", 10), new Row("Beta", 20), new Row("Gamma", 30));
    }

    public static Row[] rowArray() {
        return rows().toArray(new Row[0]);
    }

    public List<Row> notStatic() {
        return rows();
    }

    public static List<Row> withArgument(String filter) {
        return rows();
    }

    static List<Row> hidden() {
        return rows();
    }

    public static List<Row> failing() {
        throw new IllegalStateException("factory exploded");
    }

    public static final class Row {
        private final String name;
        private final Integer amount;

        public Row(String name, Integer amount) {
            this.name = name;
            this.amount = amount;
        }

        public String getName() {
            return name;
        }

        public Integer getAmount() {
            return amount;
        }
    }
}
