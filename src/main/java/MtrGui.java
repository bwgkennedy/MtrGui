import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.HashMap;

public class MtrGui extends JFrame {

    private final JTextField hostField = new JTextField("8.8.8.8", 25);
    private final JButton toggleButton = new JButton("Start MTR");
    private final JProgressBar progressBar = new JProgressBar();
    private final JLabel statusLabel = new JLabel(" Ready");
    private final JLabel summaryLabel = new JLabel(" ");
    private final JLabel stabilityLabel = new JLabel(" ");
    private final DefaultTableModel tableModel;
    private final HashMap<Integer, Integer> hopToRow = new HashMap<>();

    private Process mtrProcess = null;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean running = false;

    private static final Pattern LINE_PATTERN = Pattern.compile(
        "\\s*(\\d+)\\.\\|\\s*--\\s+([\\w\\.\\:\\?\\[\\]]+)\\s+([\\d.]+)%\\s+(\\d+)\\s+([\\d.]+)\\s+([\\d.]+)\\s+([\\d.]+)\\s+([\\d.]+)\\s+([\\d.]+)"
    );

    public MtrGui() {
        setTitle("MTR GUI Pro – 10-Ping Diagnostic with Performance & Stability Verdict");
        setSize(1200, 780);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        // Top Panel
        JPanel topPanel = new JPanel(new BorderLayout(10, 10));
        topPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        topPanel.setBackground(new Color(245, 247, 250));

        JPanel inputPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        inputPanel.setOpaque(false);
        inputPanel.add(new JLabel("Target:"));
        inputPanel.add(hostField);

        toggleButton.setBackground(new Color(0, 122, 255));
        toggleButton.setForeground(Color.WHITE);
        toggleButton.setFocusPainted(false);
        toggleButton.setFont(new Font("SansSerif", Font.BOLD, 13));

        topPanel.add(inputPanel, BorderLayout.CENTER);
        topPanel.add(toggleButton, BorderLayout.EAST);

        // Table
        String[] cols = {"Hop", "Host / IP", "Loss %", "Sent", "Last", "Avg", "Best", "Worst", "StDev"};
        tableModel = new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };

        JTable table = new JTable(tableModel);
        table.setFont(new Font("Menlo", Font.PLAIN, 13));
        table.setRowHeight(28);
        table.setShowGrid(true);
        table.setGridColor(new Color(220, 220, 220));
        table.setDefaultRenderer(Object.class, new ColorRenderer());
        table.getTableHeader().setFont(new Font("SansSerif", Font.BOLD, 13));
        table.getTableHeader().setBackground(new Color(240, 240, 240));

        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.setBorder(BorderFactory.createLineBorder(new Color(200, 200, 200)));

        // Legend
        JPanel legend = createLegendPanel();

        // Verdict Labels
        summaryLabel.setFont(new Font("SansSerif", Font.BOLD, 18));
        summaryLabel.setHorizontalAlignment(SwingConstants.CENTER);
        summaryLabel.setOpaque(true);
        summaryLabel.setBackground(new Color(240, 248, 255));
        summaryLabel.setBorder(BorderFactory.createEmptyBorder(10, 0, 5, 0));

        stabilityLabel.setFont(new Font("SansSerif", Font.BOLD, 16));
        stabilityLabel.setHorizontalAlignment(SwingConstants.CENTER);
        stabilityLabel.setOpaque(true);
        stabilityLabel.setBackground(new Color(245, 245, 255));
        stabilityLabel.setBorder(BorderFactory.createEmptyBorder(5, 0, 10, 0));

        JPanel verdictPanel = new JPanel(new GridLayout(2, 1, 0, 4));
        verdictPanel.add(summaryLabel);
        verdictPanel.add(stabilityLabel);

        // Bottom Status Bar
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, Color.LIGHT_GRAY),
            BorderFactory.createEmptyBorder(5, 10, 5, 10)
        ));

        progressBar.setIndeterminate(true);
        progressBar.setVisible(false);
        progressBar.setPreferredSize(new Dimension(150, 20));

        statusLabel.setFont(new Font("SansSerif", Font.PLAIN, 12));
        bottom.add(statusLabel, BorderLayout.CENTER);
        bottom.add(progressBar, BorderLayout.EAST);

        // Layout
        add(topPanel, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);

        JPanel southPanel = new JPanel(new BorderLayout());
        southPanel.add(legend, BorderLayout.NORTH);
        southPanel.add(verdictPanel, BorderLayout.CENTER);
        southPanel.add(bottom, BorderLayout.SOUTH);
        add(southPanel, BorderLayout.SOUTH);

        toggleButton.addActionListener(e -> {
            if (!running) startMtr();
            else stopMtr();
        });
    }

    private JPanel createLegendPanel() {
        JPanel legend = new JPanel(new FlowLayout(FlowLayout.LEFT, 20, 8));
        legend.setBorder(BorderFactory.createTitledBorder(" Color Legend "));
        legend.setBackground(new Color(250, 250, 250));
        legend.add(createLegendItem("Loss > 50%", new Color(255,100,100)));
        legend.add(createLegendItem("Loss > 10%", new Color(255,180,100)));
        legend.add(createLegendItem("Loss > 0%", new Color(255,255,150)));
        legend.add(createLegendItem("Latency > 300ms", new Color(255,100,100)));
        legend.add(createLegendItem("Latency > 100ms", new Color(255,200,100)));
        legend.add(createLegendItem("Latency > 50ms", new Color(200,255,200)));
        return legend;
    }

    private JPanel createLegendItem(String text, Color color) {
        JPanel item = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        item.setOpaque(false);
        JLabel box = new JLabel("■■■");
        box.setForeground(color);
        box.setFont(new Font("SansSerif", Font.BOLD, 16));
        item.add(box);
        item.add(new JLabel(text));
        return item;
    }

    private void startMtr() {
        String host = hostField.getText().trim();
        if (host.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please enter a host or IP address.");
            return;
        }

        tableModel.setRowCount(0);
        hopToRow.clear();
        summaryLabel.setText(" ");
        stabilityLabel.setText(" ");
        statusLabel.setText(" Running 10-ping test to " + host + "...");
        progressBar.setVisible(true);
        toggleButton.setText("Stop MTR");

        try {
            mtrProcess = new ProcessBuilder("mtr", "-n", "-c", "10", "--report-wide", host)
                    .redirectErrorStream(true)
                    .start();

            running = true;
            hostField.setEnabled(false);

            executor.execute(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(mtrProcess.getInputStream()))) {
                    String line;
                    while (running && (line = reader.readLine()) != null) {
                        if (line.contains("|--")) {
                            parseLine(line);
                        }
                    }
                } catch (Exception ignored) {}
                SwingUtilities.invokeLater(this::finishAndSummarize);
            });

        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this,
                "Failed to start mtr. Install with: sudo apt install mtr",
                "mtr not found", JOptionPane.ERROR_MESSAGE);
            progressBar.setVisible(false);
            statusLabel.setText(" Error: mtr not available");
        }
    }

    private void parseLine(String line) {
        Matcher m = LINE_PATTERN.matcher(line);
        if (!m.find()) return;

        int hop = Integer.parseInt(m.group(1));
        String host = "???".equals(m.group(2).trim()) ? "???" : m.group(2).trim();

        String[] data = {
            String.valueOf(hop), host,
            m.group(3), m.group(4), m.group(5),
            m.group(6), m.group(7), m.group(8), m.group(9)
        };

        SwingUtilities.invokeLater(() -> {
            Integer rowIndex = hopToRow.get(hop);
            if (rowIndex == null) {
                tableModel.addRow(data);
                hopToRow.put(hop, tableModel.getRowCount() - 1);
            } else {
                for (int i = 1; i < data.length; i++) {
                    tableModel.setValueAt(data[i], rowIndex, i);
                }
            }
            updateSummary();
        });
    }

    private void finishAndSummarize() {
        progressBar.setVisible(false);
        toggleButton.setText("Start MTR");
        hostField.setEnabled(true);
        running = false;
        updateSummary();
        statusLabel.setText(" Test complete – 10 pings sent");
    }

    private void updateSummary() {
        if (tableModel.getRowCount() == 0) return;

        double maxLoss = 0;
        double maxAvgLatency = 0;
        double maxStDev = 0;

        for (int i = 0; i < tableModel.getRowCount(); i++) {
            try {
                double loss = Double.parseDouble(tableModel.getValueAt(i, 2).toString().replace("%", ""));
                double avg  = Double.parseDouble(tableModel.getValueAt(i, 5).toString());
                double stdev = Double.parseDouble(tableModel.getValueAt(i, 8).toString());

                maxLoss = Math.max(maxLoss, loss);
                maxAvgLatency = Math.max(maxAvgLatency, avg);
                maxStDev = Math.max(maxStDev, stdev);
            } catch (Exception ignored) {}
        }

        // Overall Performance
        String verdict;
        Color bgPerf;
        if (maxLoss == 0 && maxAvgLatency <= 50) {
            verdict = "EXCELLENT CONNECTION";
            bgPerf = new Color(0, 160, 0);
        } else if (maxLoss <= 1 && maxAvgLatency <= 80) {
            verdict = "GOOD CONNECTION";
            bgPerf = new Color(0, 140, 0);
        } else if (maxLoss <= 5 && maxAvgLatency <= 150) {
            verdict = "ACCEPTABLE – Minor Issues";
            bgPerf = new Color(200, 160, 0);
        } else if (maxLoss <= 20 && maxAvgLatency <= 300) {
            verdict = "DEGRADED – Noticeable Lag/Loss";
            bgPerf = new Color(220, 100, 0);
        } else {
            verdict = "POOR – High Loss or Latency";
            bgPerf = new Color(200, 0, 0);
        }
        summaryLabel.setText("Overall: " + verdict);
        summaryLabel.setForeground(Color.WHITE);
        summaryLabel.setBackground(bgPerf);

        // Stability / Variability
        String stability;
        Color bgStab;
        if (maxStDev <= 5) {
            stability = "EXTREMELY STABLE – Rock-solid";
            bgStab = new Color(0, 140, 0);
        } else if (maxStDev <= 15) {
            stability = "VERY STABLE – Negligible jitter";
            bgStab = new Color(0, 160, 0);
        } else if (maxStDev <= 30) {
            stability = "MODERATELY STABLE – Light jitter";
            bgStab = new Color(180, 180, 0);
        } else if (maxStDev <= 80) {
            stability = "UNSTABLE – Noticeable jitter";
            bgStab = new Color(220, 120, 0);
        } else {
            stability = "HIGHLY UNSTABLE – Severe jitter";
            bgStab = new Color(200, 0, 0);
        }
        stabilityLabel.setText("Stability: " + stability);
        stabilityLabel.setForeground(Color.WHITE);
        stabilityLabel.setBackground(bgStab);
    }

    private void stopMtr() {
        running = false;
        if (mtrProcess != null && mtrProcess.isAlive()) {
            mtrProcess.destroyForcibly();
        }
        finishAndSummarize();
        statusLabel.setText(" Stopped by user");
    }

    @Override
    public void dispose() {
        stopMtr();
        executor.shutdownNow();
        super.dispose();
    }

    static class ColorRenderer extends JLabel implements javax.swing.table.TableCellRenderer {
        public ColorRenderer() { setOpaque(true); setHorizontalAlignment(CENTER); }
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                boolean isSelected, boolean hasFocus, int row, int col) {
            setText(value == null ? "" : value.toString());
            if (isSelected) {
                setBackground(table.getSelectionBackground());
                setForeground(table.getSelectionForeground());
                return this;
            }
            setBackground(Color.WHITE);
            setForeground(Color.BLACK);
            if (value == null) return this;
            try {
                double v;
                if (col == 2) {
                    v = Double.parseDouble(value.toString().replace("%", ""));
                    if (v > 50) setBackground(new Color(255,100,100));
                    else if (v > 10) setBackground(new Color(255,180,100));
                    else if (v > 0) setBackground(new Color(255,255,150));
                } else if (col >= 4) {
                    v = Double.parseDouble(value.toString());
                    if (v > 300) setBackground(new Color(255,100,100));
                    else if (v > 100) setBackground(new Color(255,200,100));
                    else if (v > 50) setBackground(new Color(200,255,200));
                }
            } catch (Exception ignored) {}
            return this;
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            
            new MtrGui().setVisible(true);
        });
    }
}
