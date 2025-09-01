
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.themes.FlatMacDarkLaf;
import org.apache.commons.math3.fitting.PolynomialCurveFitter;
import org.apache.commons.math3.fitting.SimpleCurveFitter;
import org.apache.commons.math3.fitting.WeightedObservedPoint;
import org.apache.commons.math3.analysis.ParametricUnivariateFunction;
import org.knowm.xchart.*;
import org.knowm.xchart.style.lines.SeriesLines;
import org.knowm.xchart.style.markers.SeriesMarkers;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.*;

public class App {

    enum ModelType {
        LINEAR("Linear: y = a + b·x"),
        POLYNOMIAL("Polynomial (degree n)"),
        EXPONENTIAL("Exponential: y = a·e^(b·x)"),
        POWER("Power: y = a·x^b"),
        LOGARITHMIC("Logarithmic: y = a + b·ln(x)"),
        RECIPROCAL("Reciprocal: y = a + b/x");

        public final String label;
        ModelType(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    private final JFrame frame = new JFrame("CurveFit — Quick Regression Calculator");
    private final JComboBox<ModelType> modelCombo = new JComboBox<>(ModelType.values());
    private final JSpinner degreeSpinner = new JSpinner(new SpinnerNumberModel(2, 2, 6, 1));
    private final JButton fitButton = new JButton("Fit");
    private final JButton clearButton = new JButton("Clear Data");
    private final JButton deleteButton = new JButton("Delete Selected Row(s)");
    private final JTextArea pasteArea = new JTextArea(3, 30);
    private final JButton addRowsButton = new JButton("Add from Paste");
    private final JLabel statsLabel = new JLabel(" ");
    private final JLabel hintLabel = new JLabel("Paste 2 columns from Excel (X and Y). Tabs, commas, semicolons, or spaces accepted.");

    private final DefaultTableModel tableModel = new DefaultTableModel(new Object[]{"X", "Y"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return true; }
        @Override public Class<?> getColumnClass(int columnIndex) { return Double.class; }
    };
    private final JTable table = new JTable(tableModel);

    private XYChart chart;
    private XChartPanel<XYChart> chartPanel;

    public static void main(String[] args) {
        // Modern, “sexy” dark theme
        FlatLaf.setup(new FlatMacDarkLaf());
        SwingUtilities.invokeLater(() -> new App().start());
    }

    private void start() {
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setMinimumSize(new Dimension(1200, 720));
        frame.setLayout(new BorderLayout());
        frame.add(buildTopBar(), BorderLayout.NORTH);
        frame.add(buildMainSplit(), BorderLayout.CENTER);
        frame.add(buildBottomBar(), BorderLayout.SOUTH);

        modelCombo.addActionListener(e -> degreeSpinner.setVisible(getSelectedModel() == ModelType.POLYNOMIAL));
        degreeSpinner.setVisible(getSelectedModel() == ModelType.POLYNOMIAL);

        fitButton.addActionListener(e -> doFit());
        addRowsButton.addActionListener(e -> addRowsFromPaste());
        clearButton.addActionListener(e -> tableModel.setRowCount(0));
        deleteButton.addActionListener(e -> deleteSelectedRows());

        // Enable DEL key to delete selected rows
        table.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_DELETE) deleteSelectedRows();
            }
        });

        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private JPanel buildTopBar() {
        JPanel top = new JPanel(new BorderLayout());
        top.setBorder(new EmptyBorder(10, 12, 10, 12));

        JPanel left = new JPanel();
        left.setOpaque(false);
        left.setLayout(new FlowLayout(FlowLayout.LEFT, 10, 0));
        JLabel modelLbl = new JLabel("Regression type:");
        JLabel degreeLbl = new JLabel("Degree:");
        left.add(modelLbl);
        left.add(modelCombo);
        left.add(degreeLbl);
        left.add(degreeSpinner);

        JPanel right = new JPanel();
        right.setOpaque(false);
        right.setLayout(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        right.add(fitButton);
        right.add(deleteButton);
        right.add(clearButton);

        top.add(left, BorderLayout.WEST);
        top.add(right, BorderLayout.EAST);
        return top;
    }

    private JSplitPane buildMainSplit() {
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        split.setResizeWeight(0.36);
        split.setLeftComponent(buildLeftPanel());
        split.setRightComponent(buildChartPanel());
        return split;
    }

    private JPanel buildLeftPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.setBorder(new EmptyBorder(10, 12, 10, 12));

        // Paste panel
        JPanel pastePanel = new JPanel(new BorderLayout(6, 6));
        pastePanel.setBorder(BorderFactory.createTitledBorder("Input"));
        hintLabel.setForeground(new Color(180, 180, 180));
        pastePanel.add(hintLabel, BorderLayout.NORTH);
        pasteArea.setLineWrap(true);
        pasteArea.setWrapStyleWord(true);
        pastePanel.add(new JScrollPane(pasteArea), BorderLayout.CENTER);

        JPanel pasteButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        pasteButtons.add(addRowsButton);
        pastePanel.add(pasteButtons, BorderLayout.SOUTH);

        // Table panel
        JPanel tablePanel = new JPanel(new BorderLayout());
        tablePanel.setBorder(BorderFactory.createTitledBorder("Data (editable) — select rows and press Delete to remove"));
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setAutoCreateRowSorter(true);
        JScrollPane tableScroll = new JScrollPane(table);
        tablePanel.add(tableScroll, BorderLayout.CENTER);

        panel.add(pastePanel, BorderLayout.NORTH);
        panel.add(tablePanel, BorderLayout.CENTER);

        return panel;
    }

    private JPanel buildChartPanel() {
        chart = new XYChartBuilder()
                .width(800).height(600)
                .title("Curve Fit")
                .xAxisTitle("X").yAxisTitle("Y")
                .build();

        // Style
        chart.getStyler().setChartBackgroundColor(new Color(27, 27, 27));
        chart.getStyler().setPlotBackgroundColor(new Color(32, 32, 32));
        chart.getStyler().setPlotGridLinesColor(new Color(70, 70, 70));
        chart.getStyler().setXAxisTickMarkSpacingHint(60);
        chart.getStyler().setLegendVisible(true);
        chart.getStyler().setLegendBackgroundColor(new Color(45, 45, 45));
        chart.getStyler().setLegendBorderColor(new Color(80, 80, 80));
        chart.getStyler().setChartFontColor(new Color(230, 230, 230));
        chart.getStyler().setAxisTickLabelsColor(new Color(220, 220, 220));
        chart.getStyler().setMarkerSize(6);

        chartPanel = new XChartPanel<>(chart);
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBorder(new EmptyBorder(10, 12, 10, 12));
        wrapper.add(chartPanel, BorderLayout.CENTER);
        return wrapper;
    }

    private JPanel buildBottomBar() {
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setBorder(new EmptyBorder(8, 12, 12, 12));
        statsLabel.setText("Ready. Add data → choose model → Fit");
        bottom.add(statsLabel, BorderLayout.WEST);
        return bottom;
    }

    private ModelType getSelectedModel() {
        return (ModelType) modelCombo.getSelectedItem();
    }

    private void addRowsFromPaste() {
        String text = pasteArea.getText().trim();
        if (text.isEmpty()) return;

        int added = 0;
        for (String line : text.split("\\R")) {
            if (line.trim().isEmpty()) continue;

            String[] tokens = splitSmart(line.trim());
            if (tokens.length < 2) continue;

            try {
                // Replace decimal comma with dot for locales that use comma
                double x = parseLocalized(tokens[0]);
                double y = parseLocalized(tokens[1]);
                tableModel.addRow(new Object[]{x, y});
                added++;
            } catch (NumberFormatException ignored) {
            }
        }

        if (added == 0) {
            JOptionPane.showMessageDialog(frame,
                    "Could not parse any rows. Expecting two numeric columns per line.",
                    "Parse Warning", JOptionPane.WARNING_MESSAGE);
        }
    }

    private static double parseLocalized(String s) {
        s = s.trim();
        // allow decimal comma
        if (s.indexOf(',') >= 0 && s.indexOf('.') < 0) {
            s = s.replace(',', '.');
        }
        return Double.parseDouble(s);
    }

    /**
     * Splits a line robustly for Excel paste:
     * 1) Try tab/semicolon first; 2) then multiple spaces; 3) finally comma.
     * This prevents breaking decimals that use comma if tab-delimited.
     */
    private static String[] splitSmart(String line) {
        String[] t = line.split("[\\t;]+");
        if (t.length >= 2) return trimAll(t);
        t = line.trim().split("\\s+");
        if (t.length >= 2) return trimAll(t);
        t = line.split(",");
        return trimAll(t);
    }

    private static String[] trimAll(String[] arr) {
        String[] out = new String[arr.length];
        for (int i = 0; i < arr.length; i++) out[i] = arr[i].trim();
        return out;
    }

    private void deleteSelectedRows() {
        int[] selected = table.getSelectedRows();
        if (selected.length == 0) return;

        // Convert view index to model index and delete from bottom up
        Arrays.sort(selected);
        for (int i = selected.length - 1; i >= 0; i--) {
            tableModel.removeRow(table.convertRowIndexToModel(selected[i]));
        }
    }

    private List<double[]> readData() {
        int n = tableModel.getRowCount();
        List<double[]> data = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Object xo = tableModel.getValueAt(i, 0);
            Object yo = tableModel.getValueAt(i, 1);
            if (xo == null || yo == null) continue;
            try {
                double x = ((Number) xo).doubleValue();
                double y = ((Number) yo).doubleValue();
                data.add(new double[]{x, y});
            } catch (Exception ignored) { }
        }
        return data;
    }

    private void doFit() {
        List<double[]> data = readData();
        if (data.size() < 2) {
            JOptionPane.showMessageDialog(frame, "Please add at least 2 data points.", "Not enough data", JOptionPane.WARNING_MESSAGE);
            return;
        }

        // Sort by x (important for drawing a nice line)
        data.sort(Comparator.comparingDouble(o -> o[0]));
        double[] xs = data.stream().mapToDouble(a -> a[0]).toArray();
        double[] ys = data.stream().mapToDouble(a -> a[1]).toArray();

        ModelType model = getSelectedModel();

        try {
            FitResult result = fitModel(xs, ys, model, (Integer) degreeSpinner.getValue());
            updateChart(xs, ys, result);
            statsLabel.setText(result.toDisplayString());
        } catch (IllegalArgumentException ex) {
            JOptionPane.showMessageDialog(frame,
                    ex.getMessage(),
                    "Fitting Error", JOptionPane.ERROR_MESSAGE);
        } catch (Exception ex) {
            ex.printStackTrace();
            JOptionPane.showMessageDialog(frame,
                    "An unexpected error occurred: " + ex.getMessage(),
                    "Fitting Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    // === Fitting ===

    static class FitResult {
        final ModelType model;
        final int degree; // relevant for polynomial
        final double[] params; // a, b, c...
        final double r2;
        final double rmse;
        final double xmin, xmax;

        FitResult(ModelType model, int degree, double[] params, double r2, double rmse, double xmin, double xmax) {
            this.model = model;
            this.degree = degree;
            this.params = params;
            this.r2 = r2;
            this.rmse = rmse;
            this.xmin = xmin;
            this.xmax = xmax;
        }

        String toDisplayString() {
            DecimalFormat df = new DecimalFormat("0.###E0");
            StringBuilder eq = new StringBuilder("Model: ").append(model.label).append("  |  ");
            eq.append("Equation: ").append(formatEquation(df)).append("  |  ");
            eq.append("R² = ").append(new DecimalFormat("0.0000").format(r2));
            eq.append("    RMSE = ").append(new DecimalFormat("0.0000").format(rmse));
            return eq.toString();
        }

        String formatEquation(DecimalFormat df) {
            switch (model) {
                case LINEAR:
                    return "y = " + fmt(df, params[0]) + " + " + fmt(df, params[1]) + "·x";
                case POLYNOMIAL:
                    StringBuilder sb = new StringBuilder("y = ");
                    // PolynomialCurveFitter returns [a0, a1, ..., an] for a0 + a1 x + a2 x^2 ...
                    for (int i = params.length - 1; i >= 0; i--) {
                        double c = params[i];
                        if (i == params.length - 1) {
                            sb.append(fmt(df, c)).append("·x").append(superscript(i));
                        } else if (i > 1) {
                            sb.append(" + ").append(fmt(df, c)).append("·x").append(superscript(i));
                        } else if (i == 1) {
                            sb.append(" + ").append(fmt(df, c)).append("·x");
                        } else {
                            sb.append(" + ").append(fmt(df, c));
                        }
                    }
                    return sb.toString().replace("x¹", "x").replace("x⁰", "");
                case EXPONENTIAL:
                    return "y = " + fmt(df, params[0]) + "·e^(" + fmt(df, params[1]) + "·x)";
                case POWER:
                    return "y = " + fmt(df, params[0]) + "·x^(" + fmt(df, params[1]) + ")";
                case LOGARITHMIC:
                    return "y = " + fmt(df, params[0]) + " + " + fmt(df, params[1]) + "·ln(x)";
                case RECIPROCAL:
                    return "y = " + fmt(df, params[0]) + " + " + fmt(df, params[1]) + "/x";
                default:
                    return "";
            }
        }

        private static String fmt(DecimalFormat df, double v) {
            // Use fixed decimals for moderate ranges, scientific for large/small
            if (Math.abs(v) >= 1e-3 && Math.abs(v) < 1e4) {
                return String.format(Locale.US, "%.6f", v);
            }
            return df.format(v);
        }

        private static String superscript(int n) {
            String s = String.valueOf(n);
            StringBuilder sb = new StringBuilder();
            for (char c : s.toCharArray()) {
                switch (c) {
                    case '0': sb.append('⁰'); break;
                    case '1': sb.append('¹'); break;
                    case '2': sb.append('²'); break;
                    case '3': sb.append('³'); break;
                    case '4': sb.append('⁴'); break;
                    case '5': sb.append('⁵'); break;
                    case '6': sb.append('⁶'); break;
                    case '7': sb.append('⁷'); break;
                    case '8': sb.append('⁸'); break;
                    case '9': sb.append('⁹'); break;
                    case '-': sb.append('⁻'); break;
                    default: sb.append(c);
                }
            }
            return sb.toString();
        }
    }

    private FitResult fitModel(double[] xs, double[] ys, ModelType model, int degree) {
        double xmin = Arrays.stream(xs).min().orElse(0);
        double xmax = Arrays.stream(xs).max().orElse(1);

        switch (model) {
            case LINEAR: {
                // y = a + b x
                ParametricUnivariateFunction f = new ParametricUnivariateFunction() {
                    @Override public double value(double x, double[] p) { return p[0] + p[1] * x; }
                    @Override public double[] gradient(double x, double[] p) { return new double[]{1.0, x}; }
                };
                double[] guess = linearGuess(xs, ys); // [a,b]
                double[] params = fitWithFunction(xs, ys, f, guess);
                Metrics m = metrics(xs, ys, x -> params[0] + params[1] * x);
                return new FitResult(model, 1, params, m.r2, m.rmse, xmin, xmax);
            }
            case POLYNOMIAL: {
                if (degree < 2 || degree > 6) throw new IllegalArgumentException("Polynomial degree must be between 2 and 6.");
                List<WeightedObservedPoint> points = new ArrayList<>();
                for (int i = 0; i < xs.length; i++) points.add(new WeightedObservedPoint(1.0, xs[i], ys[i]));
                double[] params = PolynomialCurveFitter.create(degree).fit(points);
                Metrics m = metrics(xs, ys, x -> {
                    double y = 0;
                    for (int i = 0; i < params.length; i++) y += params[i] * Math.pow(x, i);
                    return y;
                });
                return new FitResult(model, degree, params, m.r2, m.rmse, xmin, xmax);
            }
            case EXPONENTIAL: {
                // y = a * e^(b x), y>0 ideally
                ParametricUnivariateFunction f = new ParametricUnivariateFunction() {
                    @Override public double value(double x, double[] p) { return p[0] * Math.exp(p[1] * x); }
                    @Override public double[] gradient(double x, double[] p) {
                        double e = Math.exp(p[1] * x);
                        return new double[]{e, p[0] * x * e};
                    }
                };
                double[] guess = expGuess(xs, ys);
                double[] params = fitWithFunction(xs, ys, f, guess);
                Metrics m = metrics(xs, ys, x -> params[0] * Math.exp(params[1] * x));
                return new FitResult(model, 0, params, m.r2, m.rmse, xmin, xmax);
            }
            case POWER: {
                // y = a * x^b, x>0, y>0 ideally
                if (Arrays.stream(xs).anyMatch(v -> v <= 0))
                    throw new IllegalArgumentException("Power model requires x > 0 for all points.");
                ParametricUnivariateFunction f = new ParametricUnivariateFunction() {
                    @Override public double value(double x, double[] p) { return p[0] * Math.pow(x, p[1]); }
                    @Override public double[] gradient(double x, double[] p) {
                        double xb = Math.pow(x, p[1]);
                        return new double[]{xb, p[0] * xb * Math.log(x)};
                    }
                };
                double[] guess = powerGuess(xs, ys);
                double[] params = fitWithFunction(xs, ys, f, guess);
                Metrics m = metrics(xs, ys, x -> params[0] * Math.pow(x, params[1]));
                return new FitResult(model, 0, params, m.r2, m.rmse, xmin, xmax);
            }
            case LOGARITHMIC: {
                // y = a + b ln(x), x>0
                if (Arrays.stream(xs).anyMatch(v -> v <= 0))
                    throw new IllegalArgumentException("Logarithmic model requires x > 0 for all points.");
                ParametricUnivariateFunction f = new ParametricUnivariateFunction() {
                    @Override public double value(double x, double[] p) { return p[0] + p[1] * Math.log(x); }
                    @Override public double[] gradient(double x, double[] p) { return new double[]{1.0, Math.log(x)}; }
                };
                double[] guess = logGuess(xs, ys);
                double[] params = fitWithFunction(xs, ys, f, guess);
                Metrics m = metrics(xs, ys, x -> params[0] + params[1] * Math.log(x));
                return new FitResult(model, 0, params, m.r2, m.rmse, xmin, xmax);
            }
            case RECIPROCAL: {
                // y = a + b / x, x != 0
                if (Arrays.stream(xs).anyMatch(v -> v == 0))
                    throw new IllegalArgumentException("Reciprocal model requires x ≠ 0 for all points.");
                ParametricUnivariateFunction f = new ParametricUnivariateFunction() {
                    @Override public double value(double x, double[] p) { return p[0] + p[1] / x; }
                    @Override public double[] gradient(double x, double[] p) { return new double[]{1.0, 1.0 / x}; }
                };
                double[] guess = recipGuess(xs, ys);
                double[] params = fitWithFunction(xs, ys, f, guess);
                Metrics m = metrics(xs, ys, x -> params[0] + params[1] / x);
                return new FitResult(model, 0, params, m.r2, m.rmse, xmin, xmax);
            }
        }
        throw new IllegalStateException("Unhandled model");
    }

    private static double[] fitWithFunction(double[] xs, double[] ys, ParametricUnivariateFunction f, double[] start) {
        List<WeightedObservedPoint> points = new ArrayList<>();
        for (int i = 0; i < xs.length; i++) points.add(new WeightedObservedPoint(1.0, xs[i], ys[i]));
        return SimpleCurveFitter.create(f, start).withMaxIterations(10_000).withMaxIterations(10_000).fit(points);
    }

    // Initial guesses

    private static double[] linearGuess(double[] xs, double[] ys) {
        // least-squares closed-form
        double n = xs.length;
        double sumx = 0, sumy = 0, sumxx = 0, sumxy = 0;
        for (int i = 0; i < xs.length; i++) {
            sumx += xs[i]; sumy += ys[i]; sumxx += xs[i]*xs[i]; sumxy += xs[i]*ys[i];
        }
        double denom = n*sumxx - sumx*sumx;
        double b = denom == 0 ? 0 : (n*sumxy - sumx*sumy)/denom;
        double a = (sumy - b*sumx)/n;
        return new double[]{a, b};
    }

    private static double[] expGuess(double[] xs, double[] ys) {
        // ln(y) = ln(a) + b x
        List<double[]> pairs = new ArrayList<>();
        for (int i = 0; i < xs.length; i++) if (ys[i] > 0) pairs.add(new double[]{xs[i], Math.log(ys[i])});
        if (pairs.size() < 2) return new double[]{1, 0.01};
        double[] x = pairs.stream().mapToDouble(p -> p[0]).toArray();
        double[] ly = pairs.stream().mapToDouble(p -> p[1]).toArray();
        double[] ab = linearGuess(x, ly);
        return new double[]{Math.exp(ab[0]), ab[1]};
    }

    private static double[] powerGuess(double[] xs, double[] ys) {
        // ln(y) = ln(a) + b ln(x)
        List<double[]> pairs = new ArrayList<>();
        for (int i = 0; i < xs.length; i++) if (xs[i] > 0 && ys[i] > 0) pairs.add(new double[]{Math.log(xs[i]), Math.log(ys[i])});
        if (pairs.size() < 2) return new double[]{1, 1};
        double[] lx = pairs.stream().mapToDouble(p -> p[0]).toArray();
        double[] ly = pairs.stream().mapToDouble(p -> p[1]).toArray();
        double[] ab = linearGuess(lx, ly);
        return new double[]{Math.exp(ab[0]), ab[1]};
    }

    private static double[] logGuess(double[] xs, double[] ys) {
        // y = a + b ln(x)
        List<double[]> pairs = new ArrayList<>();
        for (int i = 0; i < xs.length; i++) if (xs[i] > 0) pairs.add(new double[]{Math.log(xs[i]), ys[i]});
        if (pairs.size() < 2) return new double[]{ys[0], 1};
        double[] lx = pairs.stream().mapToDouble(p -> p[0]).toArray();
        double[] y = pairs.stream().mapToDouble(p -> p[1]).toArray();
        return linearGuess(lx, y);
    }

    private static double[] recipGuess(double[] xs, double[] ys) {
        // y = a + b*(1/x) => linear in (1/x)
        double[] zx = Arrays.stream(xs).map(v -> 1.0/v).toArray();
        return linearGuess(zx, ys);
    }

    static class Metrics {
        final double r2, rmse;
        Metrics(double r2, double rmse) { this.r2 = r2; this.rmse = rmse; }
    }

    private static Metrics metrics(double[] xs, double[] ys, java.util.function.DoubleUnaryOperator f) {
        double mean = Arrays.stream(ys).average().orElse(0);
        double ssTot = 0, ssRes = 0;
        for (int i = 0; i < ys.length; i++) {
            double yhat = f.applyAsDouble(xs[i]);
            ssRes += Math.pow(ys[i] - yhat, 2);
            ssTot += Math.pow(ys[i] - mean, 2);
        }
        double r2 = ssTot == 0 ? 1.0 : 1.0 - ssRes / ssTot;
        double rmse = Math.sqrt(ssRes / ys.length);
        return new Metrics(r2, rmse);
    }

    // === Chart update ===

    private void updateChart(double[] xs, double[] ys, FitResult fit) {
        chart.getSeriesMap().clear();

        // Data scatter
        XYSeries scatter = chart.addSeries("Data", xs, ys);
        scatter.setMarker(SeriesMarkers.CIRCLE);
        scatter.setLineStyle(SeriesLines.NONE);
        scatter.setMarkerColor(new Color(91, 207, 250));

        // Fit curve
        double min = fit.xmin, max = fit.xmax;
        if (min == max) { min -= 1; max += 1; }
        int samples = Math.min(1000, Math.max(200, xs.length * 10));
        double[] fx = new double[samples];
        double[] fy = new double[samples];
        double step = (max - min) / (samples - 1);
        for (int i = 0; i < samples; i++) {
            double x = min + i * step;
            fx[i] = x;
            fy[i] = evalFit(fit, x);
        }
        XYSeries fitSeries = chart.addSeries("Fit", fx, fy);
        fitSeries.setMarker(SeriesMarkers.NONE);
        fitSeries.setLineStyle(SeriesLines.SOLID);
        fitSeries.setLineColor(new Color(255, 109, 132));

        chartPanel.revalidate();
        chartPanel.repaint();
    }

    private double evalFit(FitResult fit, double x) {
        double[] p = fit.params;
        switch (fit.model) {
            case LINEAR: return p[0] + p[1] * x;
            case POLYNOMIAL:
                double y = 0;
                for (int i = 0; i < p.length; i++) y += p[i] * Math.pow(x, i);
                return y;
            case EXPONENTIAL: return p[0] * Math.exp(p[1] * x);
            case POWER:
                if (x <= 0) return Double.NaN;
                return p[0] * Math.pow(x, p[1]);
            case LOGARITHMIC:
                if (x <= 0) return Double.NaN;
                return p[0] + p[1] * Math.log(x);
            case RECIPROCAL:
                if (x == 0) return Double.NaN;
                return p[0] + p[1] / x;
        }
        return Double.NaN;
    }
}
