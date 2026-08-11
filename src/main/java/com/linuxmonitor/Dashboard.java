package com.linuxmonitor;

import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.file.Files;
import java.util.concurrent.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

public class Dashboard extends JFrame {
    private static final Color BG_DEF  = new Color(0x1e1e2e);
    private static final Color BG2     = new Color(0x313244);
    private static final Color FG      = new Color(0xcdd6f4);
    private static final Color FG_DIM  = new Color(0xa6adc8);
    private static final Color ACCENT  = new Color(0x89b4fa);
    private static final Color COL_OK  = new Color(0xa6e3a1);
    private static final Color COL_ERR = new Color(0xf38ba8);
    private static final Color COL_CPU = new Color(0xf38ba8);
    private static final Color COL_MEM = new Color(0x89b4fa);
    private static final Color GRID    = new Color(0x45475a);
    private static final Color[] PRESET = {
        new Color(0x1e1e2e), new Color(0x2e3440), new Color(0x1a1a2e),
        new Color(0x0d1117), new Color(0x282828), new Color(0x3b4252)
    };
    private static final String[] PRESET_NAMES = {
        "\u6df1\u8272", "\u53e4\u94dc", "\u7d2b\u591c",
        "\u591c\u8272", "\u7164\u7070", "\u94a2\u84dd"
    };

    private static final String FONT = "\u5fae\u8f6f\u96c5\u9ed1";

    private final Main.Config config;
    private final SSHClient ssh = new SSHClient();
    private final LinuxMonitor monitor = new LinuxMonitor(ssh);
    private final ScheduledExecutorService pool = Executors.newScheduledThreadPool(2);
    private volatile boolean running = true;

    private Color bgColor;
    private MiniBar cpuBar, memBar;
    private JLabel statusDot, statusLbl, cpuLbl, memLbl, netLbl, serverLbl;
    private JComboBox<String> serverCombo;
    private JButton btnConnect, btnDisconnect;
    private JPanel header, body, footer;
    private int refreshMs;

    public Dashboard(Main.Config config) {
        this.config = config;
        this.refreshMs = Math.max(config.refresh_interval * 1000, 1000);
        this.bgColor = parseHex(config.bg_color, BG_DEF);
        buildUi();
        updateStatus(false, "\u672a\u8fde\u63a5");
        startTimer();
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                running = false;
                pool.shutdownNow();
                ssh.disconnect();
            }
        });
    }

    private void buildUi() {
        setTitle("Linux Monitor");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(500, 350);
        setMinimumSize(new Dimension(400, 280));
        setAlwaysOnTop(true);
        getContentPane().setBackground(bgColor);
        setLayout(new BorderLayout(0, 4));

        // header
        header = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        header.setBackground(bgColor);
        JLabel title = new JLabel("Linux Monitor");
        title.setFont(new Font(FONT, Font.BOLD, 14));
        title.setForeground(FG);
        header.add(title);

        header.add(Box.createHorizontalStrut(6));
        statusDot = new JLabel("\u25CF");
        statusDot.setFont(new Font(FONT, Font.PLAIN, 12));
        statusDot.setForeground(COL_ERR);
        header.add(statusDot);

        statusLbl = new JLabel("\u672a\u8fde\u63a5");
        statusLbl.setFont(new Font(FONT, Font.PLAIN, 11));
        statusLbl.setForeground(FG_DIM);
        header.add(statusLbl);

        header.add(Box.createHorizontalGlue());

        JPanel sizeGroup = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        sizeGroup.setBackground(bgColor);
        int[][] sizeData = {{400,280},{500,350},{650,450}};
        String[] sizeLabels = {"\u5c0f","\u4e2d","\u5927"};
        for (int i = 0; i < sizeData.length; i++) {
            int w = sizeData[i][0], h = sizeData[i][1];
            JButton b = new JButton(sizeLabels[i]);
            b.setFont(new Font(FONT, Font.PLAIN, 9));
            b.setBackground(BG2); b.setForeground(FG_DIM);
            b.setFocusPainted(false); b.setBorderPainted(false);
            b.setCursor(new Cursor(Cursor.HAND_CURSOR));
            b.setMargin(new Insets(0, 4, 0, 4));
            final int fw = w, fh = h;
            b.addActionListener(e -> setSize(fw, fh));
            sizeGroup.add(b);
        }

        JCheckBox topMost = new JCheckBox("\u7f6e\u9876");
        topMost.setSelected(true);
        topMost.setBackground(bgColor);
        topMost.setForeground(FG_DIM);
        topMost.setFont(new Font(FONT, Font.PLAIN, 10));
        topMost.setFocusPainted(false);
        topMost.addActionListener(e -> setAlwaysOnTop(topMost.isSelected()));
        sizeGroup.add(topMost);

        JButton themeBtn = new JButton("\u4e3b\u9898");
        themeBtn.setFont(new Font(FONT, Font.PLAIN, 9));
        themeBtn.setBackground(BG2); themeBtn.setForeground(FG_DIM);
        themeBtn.setFocusPainted(false); themeBtn.setBorderPainted(false);
        themeBtn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        themeBtn.setMargin(new Insets(0, 4, 0, 4));
        themeBtn.addActionListener(e -> onThemePicker());
        sizeGroup.add(themeBtn);

        header.add(sizeGroup);
        add(header, BorderLayout.NORTH);

        // center
        body = new JPanel(new GridBagLayout());
        body.setBackground(bgColor);
        body.setBorder(new EmptyBorder(4, 10, 4, 10));
        GridBagConstraints g = new GridBagConstraints();
        g.fill = GridBagConstraints.HORIZONTAL;
        g.insets = new Insets(2, 0, 2, 0);

        g.gridx = 0; g.gridy = 0; g.weightx = 0; g.ipadx = 10;
        cpuLbl = new JLabel("CPU");
        cpuLbl.setFont(new Font("Consolas", Font.BOLD, 12));
        cpuLbl.setForeground(COL_CPU);
        body.add(cpuLbl, g);

        g.gridx = 1; g.weightx = 1.0; g.ipadx = 0;
        cpuBar = new MiniBar(COL_CPU);
        body.add(cpuBar, g);

        g.gridx = 0; g.gridy = 1; g.weightx = 0; g.ipadx = 10;
        memLbl = new JLabel("MEM");
        memLbl.setFont(new Font("Consolas", Font.BOLD, 12));
        memLbl.setForeground(COL_MEM);
        body.add(memLbl, g);

        g.gridx = 1; g.weightx = 1.0; g.ipadx = 0;
        memBar = new MiniBar(COL_MEM);
        body.add(memBar, g);

        g.gridx = 0; g.gridy = 2; g.weightx = 0; g.ipadx = 10;
        JLabel netLabel = new JLabel("NET");
        netLabel.setFont(new Font("Consolas", Font.BOLD, 12));
        netLabel.setForeground(FG_DIM);
        body.add(netLabel, g);

        g.gridx = 1; g.weightx = 1.0; g.ipadx = 0;
        netLbl = new JLabel("--");
        netLbl.setFont(new Font("Consolas", Font.PLAIN, 12));
        netLbl.setForeground(FG_DIM);
        body.add(netLbl, g);

        g.gridx = 0; g.gridy = 3; g.weightx = 0; g.ipadx = 10;
        JLabel svLabel = new JLabel("\u670d\u52a1\u5668");
        svLabel.setFont(new Font(FONT, Font.PLAIN, 11));
        svLabel.setForeground(FG_DIM);
        body.add(svLabel, g);

        g.gridx = 1; g.weightx = 1.0; g.ipadx = 0;
        serverLbl = new JLabel("\u672a\u9009\u62e9\u670d\u52a1\u5668");
        serverLbl.setFont(new Font(FONT, Font.PLAIN, 11));
        serverLbl.setForeground(FG_DIM);
        body.add(serverLbl, g);

        add(body, BorderLayout.CENTER);

        // footer
        footer = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        footer.setBackground(bgColor);

        serverCombo = new JComboBox<>();
        serverCombo.setBackground(BG2);
        serverCombo.setForeground(FG);
        serverCombo.setFont(new Font(FONT, Font.PLAIN, 11));
        serverCombo.setPreferredSize(new Dimension(200, 24));
        serverCombo.addActionListener(e -> {
            int idx = serverCombo.getSelectedIndex();
            if (idx >= 0 && idx < config.servers.size()) {
                config.current_server = idx;
                Main.saveConfig(config);
                updateServerLabel();
            }
        });
        refreshServerCombo();
        footer.add(serverCombo);

        JButton mgrBtn = new JButton("\u7ba1\u7406");
        mgrBtn.setBackground(BG2);
        mgrBtn.setForeground(FG);
        mgrBtn.setFont(new Font(FONT, Font.PLAIN, 11));
        mgrBtn.setFocusPainted(false);
        mgrBtn.setBorderPainted(false);
        mgrBtn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        mgrBtn.addActionListener(e -> onServerManager());
        footer.add(mgrBtn);

        footer.add(Box.createHorizontalGlue());

        btnConnect = new JButton("\u8fde\u63a5");
        btnConnect.setBackground(ACCENT);
        btnConnect.setForeground(new Color(0x11111b));
        btnConnect.setFont(new Font(FONT, Font.BOLD, 11));
        btnConnect.setFocusPainted(false);
        btnConnect.setBorderPainted(false);
        btnConnect.addActionListener(e -> onConnect());
        footer.add(btnConnect);

        btnDisconnect = new JButton("\u65ad\u5f00");
        btnDisconnect.setBackground(BG2);
        btnDisconnect.setForeground(FG);
        btnDisconnect.setFont(new Font(FONT, Font.PLAIN, 11));
        btnDisconnect.setFocusPainted(false);
        btnDisconnect.setBorderPainted(false);
        btnDisconnect.setEnabled(false);
        btnDisconnect.addActionListener(e -> onDisconnect());
        footer.add(btnDisconnect);

        add(footer, BorderLayout.SOUTH);
        updateServerLabel();
    }

    private void refreshServerCombo() {
        serverCombo.removeAllItems();
        for (Main.ServerInfo s : config.servers) {
            String label = s.name.isEmpty() ? s.host : s.name;
            if (label.isEmpty()) label = "\u65b0\u670d\u52a1\u5668";
            serverCombo.addItem(label);
        }
        int idx = Math.min(config.current_server, config.servers.size() - 1);
        if (idx >= 0) serverCombo.setSelectedIndex(idx);
    }

    private void updateServerLabel() {
        Main.ServerInfo s = config.current();
        if (s.host.isEmpty()) {
            serverLbl.setText("\u672a\u9009\u62e9\u670d\u52a1\u5668");
        } else {
            serverLbl.setText(s.name + "  " + s.username + "@" + s.host);
        }
    }

    private void startTimer() {
        pool.scheduleWithFixedDelay(() -> {
            if (!running) return;
            if (ssh.isConnected()) {
                SystemData d = monitor.collect();
                SwingUtilities.invokeLater(() -> render(d));
            }
        }, refreshMs, refreshMs, TimeUnit.MILLISECONDS);
    }

    private void render(SystemData d) {
        if (d == null) return;
        if (!d.ok) { updateStatus(false, "\u91c7\u96c6\u5931\u8d25"); return; }
        updateStatus(true, "\u5df2\u8fde\u63a5");

        cpuBar.setValue(d.cpuPercent);
        cpuLbl.setText(String.format("CPU %5.1f%%", d.cpuPercent));

        memBar.setValue(d.memPercent);
        memLbl.setText(String.format("MEM %5.1f%%  %.1f/%.1fGB",
                d.memPercent, d.memUsed / 1024, d.memTotal / 1024));

        String rx = formatSpeed(d.netRxSpeed);
        String tx = formatSpeed(d.netTxSpeed);
        netLbl.setText(String.format("\u2193%s  \u2191%s", rx, tx));
    }

    private void onConnect() {
        Main.ServerInfo s = config.current();
        if (s.host.isEmpty()) {
            JOptionPane.showMessageDialog(this, "\u8bf7\u5148\u6dfb\u52a0\u670d\u52a1\u5668");
            return;
        }
        btnConnect.setEnabled(false);
        btnConnect.setText("\u4e2d...");
        updateStatus(false, "\u8fde\u63a5\u4e2d...");

        pool.submit(() -> {
            boolean ok = ssh.connect(s.host, s.port, s.username,
                    s.password, s.key_file, s.use_key);
            SwingUtilities.invokeLater(() -> {
                btnConnect.setEnabled(true);
                btnConnect.setText("\u8fde\u63a5");
                if (ok) {
                    btnDisconnect.setEnabled(true);
                    updateStatus(true, "\u5df2\u8fde\u63a5");
                    updateServerLabel();
                } else {
                    updateStatus(false, "\u5931\u8d25: " + ssh.getLastError());
                }
            });
        });
    }

    private void onDisconnect() {
        ssh.disconnect();
        btnDisconnect.setEnabled(false);
        updateStatus(false, "\u672a\u8fde\u63a5");
        cpuBar.setValue(0);
        memBar.setValue(0);
        cpuLbl.setText("CPU");
        memLbl.setText("MEM");
        netLbl.setText("--");
    }

    private void updateStatus(boolean connected, String text) {
        statusDot.setForeground(connected ? COL_OK : COL_ERR);
        statusLbl.setText(text);
        statusLbl.setForeground(connected ? COL_OK : FG_DIM);
    }

    private void onServerManager() {
        JDialog win = new JDialog(this, "\u670d\u52a1\u5668\u7ba1\u7406", true);
        win.getContentPane().setBackground(bgColor);
        win.setLayout(new BorderLayout());
        win.setSize(520, 340);
        win.setLocationRelativeTo(this);

        DefaultListModel<String> listModel = new DefaultListModel<>();
        for (Main.ServerInfo s : config.servers)
            listModel.addElement(s.name.isEmpty() ? "\u672a\u547d\u540d" : s.name);
        JList<String> serverList = new JList<>(listModel);
        serverList.setBackground(BG2);
        serverList.setForeground(FG);
        serverList.setFont(new Font(FONT, Font.PLAIN, 11));
        serverList.setSelectionBackground(new Color(0x45475a));
        serverList.setSelectionForeground(FG);
        int si = Math.min(config.current_server, listModel.size() - 1);
        if (si >= 0) serverList.setSelectedIndex(si);

        JScrollPane listScroll = new JScrollPane(serverList);
        listScroll.setPreferredSize(new Dimension(150, 0));
        listScroll.setBorder(BorderFactory.createLineBorder(GRID));

        JPanel listBtns = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 4));
        listBtns.setBackground(bgColor);
        JButton addBtn = new JButton("+ \u6dfb\u52a0");
        addBtn.setBackground(ACCENT); addBtn.setForeground(new Color(0x11111b));
        addBtn.setFont(new Font(FONT, Font.BOLD, 10));
        addBtn.setFocusPainted(false); addBtn.setBorderPainted(false);
        listBtns.add(addBtn);

        JButton delBtn = new JButton("\u2014 \u5220\u9664");
        delBtn.setBackground(COL_ERR); delBtn.setForeground(Color.WHITE);
        delBtn.setFont(new Font(FONT, Font.BOLD, 10));
        delBtn.setFocusPainted(false); delBtn.setBorderPainted(false);
        listBtns.add(delBtn);

        JPanel leftPanel = new JPanel(new BorderLayout());
        leftPanel.setBackground(bgColor);
        leftPanel.add(listScroll, BorderLayout.CENTER);
        leftPanel.add(listBtns, BorderLayout.SOUTH);

        JPanel detail = new JPanel(new GridBagLayout());
        detail.setBackground(BG2);
        detail.setBorder(new EmptyBorder(10, 10, 10, 10));
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(3, 6, 3, 6); g.fill = GridBagConstraints.HORIZONTAL;

        String[][] fields = {{"name","\u540d\u79f0"}, {"host","\u5730\u5740"}, {"port","\u7aef\u53e3"},
                {"username","\u7528\u6237\u540d"}, {"password","\u5bc6\u7801"}, {"key_file","\u5bc6\u94a5\u6587\u4ef6"}};
        JTextField[] inputs = new JTextField[fields.length];
        for (int i = 0; i < fields.length; i++) {
            g.gridx = 0; g.gridy = i; g.weightx = 0;
            JLabel l = new JLabel(fields[i][1]);
            l.setForeground(FG); l.setFont(new Font(FONT, Font.PLAIN, 10));
            detail.add(l, g);
            g.gridx = 1; g.weightx = 1.0;
            JTextField tf = new JTextField(16);
            tf.setBackground(bgColor); tf.setForeground(FG);
            tf.setCaretColor(FG); tf.setBorder(BorderFactory.createLineBorder(GRID));
            if (fields[i][0].equals("password")) {
                tf = new JPasswordField(16);
                tf.setBackground(bgColor); tf.setForeground(FG);
                tf.setCaretColor(FG); tf.setBorder(BorderFactory.createLineBorder(GRID));
            }
            inputs[i] = tf;
            detail.add(tf, g);
        }
        JCheckBox useKey = new JCheckBox("\u4f7f\u7528\u5bc6\u94a5\u767b\u5f55");
        useKey.setBackground(BG2); useKey.setForeground(FG);
        useKey.setFont(new Font(FONT, Font.PLAIN, 10));
        g.gridx = 0; g.gridy = fields.length; g.gridwidth = 2;
        detail.add(useKey, g);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, detail);
        split.setDividerLocation(165);
        split.setBorder(null);
        split.setBackground(bgColor);
        win.add(split, BorderLayout.CENTER);

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
        btns.setBackground(bgColor);
        JButton saveBtn = new JButton("\u4fdd\u5b58");
        saveBtn.setBackground(ACCENT); saveBtn.setForeground(new Color(0x11111b));
        saveBtn.setFont(new Font(FONT, Font.BOLD, 10));
        saveBtn.setFocusPainted(false); saveBtn.setBorderPainted(false);
        btns.add(saveBtn);

        JButton closeBtn = new JButton("\u5173\u95ed");
        closeBtn.setBackground(BG2); closeBtn.setForeground(FG);
        closeBtn.setFont(new Font(FONT, Font.PLAIN, 10));
        closeBtn.setFocusPainted(false); closeBtn.setBorderPainted(false);
        closeBtn.addActionListener(e -> { refreshServerCombo(); updateServerLabel(); Main.saveConfig(config); win.dispose(); });
        btns.add(closeBtn);
        win.add(btns, BorderLayout.SOUTH);

        Runnable loadSelected = () -> {
            int idx = serverList.getSelectedIndex();
            if (idx < 0 || idx >= config.servers.size()) return;
            Main.ServerInfo s = config.servers.get(idx);
            inputs[0].setText(s.name);
            inputs[1].setText(s.host);
            inputs[2].setText(String.valueOf(s.port));
            inputs[3].setText(s.username);
            inputs[4].setText(s.password);
            inputs[5].setText(s.key_file);
            useKey.setSelected(s.use_key);
        };

        serverList.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) loadSelected.run(); });
        saveBtn.addActionListener(e -> {
            int idx = serverList.getSelectedIndex();
            if (idx < 0) return;
            Main.ServerInfo s = config.servers.get(idx);
            s.name = inputs[0].getText().trim();
            s.host = inputs[1].getText().trim();
            try { s.port = Integer.parseInt(inputs[2].getText().trim()); } catch (Exception ignored) {}
            s.username = inputs[3].getText().trim();
            s.password = inputs[4].getText().trim();
            s.key_file = inputs[5].getText().trim();
            s.use_key = useKey.isSelected();
            listModel.set(idx, s.name.isEmpty() ? "\u672a\u547d\u540d" : s.name);
            serverList.setSelectedIndex(idx);
            Main.saveConfig(config);
        });
        addBtn.addActionListener(e -> {
            Main.ServerInfo s = new Main.ServerInfo("\u65b0\u670d\u52a1\u5668", "", 22, "root");
            config.servers.add(s);
            listModel.addElement("\u65b0\u670d\u52a1\u5668");
            serverList.setSelectedIndex(listModel.size() - 1);
            loadSelected.run();
        });
        delBtn.addActionListener(e -> {
            int idx = serverList.getSelectedIndex();
            if (idx < 0 || config.servers.size() <= 1) return;
            if (JOptionPane.showConfirmDialog(win, "\u786e\u5b9a\u5220\u9664?", "\u786e\u8ba4",
                    JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
            config.servers.remove(idx);
            listModel.remove(idx);
            if (idx >= listModel.size()) idx = listModel.size() - 1;
            if (idx >= 0) serverList.setSelectedIndex(idx);
            loadSelected.run();
        });
        loadSelected.run();
        win.setVisible(true);
    }

    private static String formatSpeed(double bps) {
        if (bps >= 1024 * 1024) return String.format("%.1fMB", bps / 1024 / 1024);
        if (bps >= 1024)        return String.format("%.0fKB", bps / 1024);
        return String.format("%.0fB", bps);
    }

    // =================================================================
    // Theme
    // =================================================================
    private void onThemePicker() {
        JPopupMenu menu = new JPopupMenu();
        menu.setBackground(BG2);
        menu.setBorder(BorderFactory.createLineBorder(GRID));

        for (int i = 0; i < PRESET.length; i++) {
            Color c = PRESET[i];
            JMenuItem item = new JMenuItem(PRESET_NAMES[i]);
            item.setFont(new Font(FONT, Font.PLAIN, 11));
            item.setBackground(BG2); item.setForeground(FG);
            item.setIcon(new ColorIcon(c));
            int fi = i;
            item.addActionListener(e -> applyBg(PRESET[fi]));
            menu.add(item);
        }

        menu.addSeparator();

        JMenuItem custom = new JMenuItem("\u81ea\u5b9a\u4e49...");
        custom.setFont(new Font(FONT, Font.PLAIN, 11));
        custom.setBackground(BG2); custom.setForeground(FG);
        custom.addActionListener(e -> {
            Color picked = JColorChooser.showDialog(this, "\u9009\u62e9\u80cc\u666f\u8272", bgColor);
            if (picked != null) applyBg(picked);
        });
        menu.add(custom);

        JButton src = (JButton) ((JPanel) header.getComponent(header.getComponentCount()-1))
                .getComponent(((JPanel) header.getComponent(header.getComponentCount()-1)).getComponentCount()-1);
        menu.show(src, 0, src.getHeight());
    }

    private void applyBg(Color c) {
        bgColor = c;
        config.bg_color = String.format("#%06x", c.getRGB() & 0xffffff);
        getContentPane().setBackground(c);
        header.setBackground(c);
        body.setBackground(c);
        footer.setBackground(c);
        header.repaint(); body.repaint(); footer.repaint();
        Main.saveConfig(config);
    }

    private static Color parseHex(String hex, Color def) {
        try {
            if (hex == null || hex.isEmpty()) return def;
            return Color.decode(hex.startsWith("#") ? hex : "#" + hex);
        } catch (Exception e) { return def; }
    }

    private static class ColorIcon implements Icon {
        private final Color color;
        ColorIcon(Color c) { this.color = c; }
        public int getIconWidth() { return 12; }
        public int getIconHeight() { return 12; }
        public void paintIcon(Component c, Graphics g, int x, int y) {
            g.setColor(color);
            g.fillRect(x, y, 12, 12);
            g.setColor(Color.GRAY);
            g.drawRect(x, y, 12, 12);
        }
    }

    private static class MiniBar extends JPanel {
        private double value;
        private final Color color;
        MiniBar(Color color) { this.color = color; setBackground(BG2); setPreferredSize(new Dimension(0, 16)); }
        void setValue(double v) { this.value = Math.min(v, 100); repaint(); }
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            int bw = w - 4, bh = h - 6, bx = 2, by = 3;
            g2.setColor(new Color(0x181825));
            g2.fillRoundRect(bx, by, bw, bh, bh, bh);
            int fw = Math.max(0, (int) (bw * (value / 100.0)) - 2);
            if (fw > 0) { g2.setColor(color); g2.fillRoundRect(bx + 1, by + 1, fw, bh - 2, bh - 2, bh - 2); }
            g2.dispose();
        }
    }
}