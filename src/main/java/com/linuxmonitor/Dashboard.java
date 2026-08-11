package com.linuxmonitor;

import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.file.Files;
import java.util.concurrent.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

public class Dashboard extends JFrame {
    private static final Color BG      = new Color(0x1e1e2e);
    private static final Color BG2     = new Color(0x313244);
    private static final Color FG      = new Color(0xcdd6f4);
    private static final Color FG_DIM  = new Color(0xa6adc8);
    private static final Color ACCENT  = new Color(0x89b4fa);
    private static final Color COL_OK  = new Color(0xa6e3a1);
    private static final Color COL_ERR = new Color(0xf38ba8);
    private static final Color COL_CPU = new Color(0xf38ba8);
    private static final Color COL_MEM = new Color(0x89b4fa);
    private static final Color GRID    = new Color(0x45475a);

    private final Main.Config config;
    private final SSHClient ssh = new SSHClient();
    private final LinuxMonitor monitor = new LinuxMonitor(ssh);
    private final ScheduledExecutorService pool = Executors.newScheduledThreadPool(2);
    private volatile boolean running = true;

    private MiniBar cpuBar, memBar;
    private JLabel statusDot, statusLbl, cpuLbl, memLbl, netLbl, serverLbl;
    private JComboBox<String> serverCombo;
    private JButton btnConnect, btnDisconnect;
    private int refreshMs;

    public Dashboard(Main.Config config) {
        this.config = config;
        this.refreshMs = Math.max(config.refresh_interval * 1000, 1000);
        buildUi();
        updateStatus(false, "未连接");
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
        setSize(340, 220);
        setMinimumSize(new Dimension(300, 200));
        setAlwaysOnTop(true);
        getContentPane().setBackground(BG);
        setLayout(new BorderLayout(0, 0));

        // header
        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 3));
        header.setBackground(BG);
        JLabel title = new JLabel("Linux Monitor");
        title.setFont(new Font("Segoe UI", Font.BOLD, 12));
        title.setForeground(FG);
        header.add(title);

        header.add(Box.createHorizontalStrut(4));
        statusDot = new JLabel("\u25CF");
        statusDot.setFont(new Font("Segoe UI", Font.PLAIN, 10));
        statusDot.setForeground(COL_ERR);
        header.add(statusDot);

        statusLbl = new JLabel("未连接");
        statusLbl.setFont(new Font("Segoe UI", Font.PLAIN, 9));
        statusLbl.setForeground(FG_DIM);
        header.add(statusLbl);

        header.add(Box.createHorizontalGlue());

        JCheckBox topMost = new JCheckBox("置顶");
        topMost.setSelected(true);
        topMost.setBackground(BG);
        topMost.setForeground(FG_DIM);
        topMost.setFont(new Font("Segoe UI", Font.PLAIN, 9));
        topMost.setFocusPainted(false);
        topMost.addActionListener(e -> setAlwaysOnTop(topMost.isSelected()));
        header.add(topMost);

        add(header, BorderLayout.NORTH);

        // center
        JPanel body = new JPanel(new GridBagLayout());
        body.setBackground(BG);
        body.setBorder(new EmptyBorder(2, 6, 2, 6));
        GridBagConstraints g = new GridBagConstraints();
        g.fill = GridBagConstraints.HORIZONTAL;
        g.insets = new Insets(1, 0, 1, 0);

        g.gridx = 0; g.gridy = 0; g.weightx = 0;
        cpuLbl = new JLabel("CPU");
        cpuLbl.setFont(new Font("Consolas", Font.PLAIN, 10));
        cpuLbl.setForeground(COL_CPU);
        body.add(cpuLbl, g);

        g.gridx = 1; g.weightx = 1.0;
        cpuBar = new MiniBar(COL_CPU);
        body.add(cpuBar, g);

        g.gridx = 0; g.gridy = 1; g.weightx = 0;
        memLbl = new JLabel("MEM");
        memLbl.setFont(new Font("Consolas", Font.PLAIN, 10));
        memLbl.setForeground(COL_MEM);
        body.add(memLbl, g);

        g.gridx = 1; g.weightx = 1.0;
        memBar = new MiniBar(COL_MEM);
        body.add(memBar, g);

        g.gridx = 0; g.gridy = 2; g.weightx = 0;
        JLabel netLabel = new JLabel("NET");
        netLabel.setFont(new Font("Consolas", Font.PLAIN, 10));
        netLabel.setForeground(FG_DIM);
        body.add(netLabel, g);

        g.gridx = 1; g.weightx = 1.0;
        netLbl = new JLabel("--");
        netLbl.setFont(new Font("Consolas", Font.PLAIN, 10));
        netLbl.setForeground(FG_DIM);
        body.add(netLbl, g);

        g.gridx = 0; g.gridy = 3; g.weightx = 0;
        JLabel svLabel = new JLabel("SV");
        svLabel.setFont(new Font("Consolas", Font.PLAIN, 10));
        svLabel.setForeground(FG_DIM);
        body.add(svLabel, g);

        g.gridx = 1; g.weightx = 1.0;
        serverLbl = new JLabel("未选择服务器");
        serverLbl.setFont(new Font("Segoe UI", Font.PLAIN, 9));
        serverLbl.setForeground(FG_DIM);
        body.add(serverLbl, g);

        add(body, BorderLayout.CENTER);

        // footer
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        footer.setBackground(BG);

        serverCombo = new JComboBox<>();
        serverCombo.setBackground(BG2);
        serverCombo.setForeground(FG);
        serverCombo.setFont(new Font("Segoe UI", Font.PLAIN, 9));
        serverCombo.setPreferredSize(new Dimension(140, 20));
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

        JButton mgrBtn = new JButton("管理");
        mgrBtn.setBackground(BG2);
        mgrBtn.setForeground(FG);
        mgrBtn.setFont(new Font("Segoe UI", Font.PLAIN, 9));
        mgrBtn.setFocusPainted(false);
        mgrBtn.setBorderPainted(false);
        mgrBtn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        mgrBtn.addActionListener(e -> onServerManager());
        footer.add(mgrBtn);

        footer.add(Box.createHorizontalGlue());

        btnConnect = new JButton("连接");
        btnConnect.setBackground(ACCENT);
        btnConnect.setForeground(new Color(0x11111b));
        btnConnect.setFont(new Font("Segoe UI", Font.BOLD, 9));
        btnConnect.setFocusPainted(false);
        btnConnect.setBorderPainted(false);
        btnConnect.addActionListener(e -> onConnect());
        footer.add(btnConnect);

        btnDisconnect = new JButton("断开");
        btnDisconnect.setBackground(BG2);
        btnDisconnect.setForeground(FG);
        btnDisconnect.setFont(new Font("Segoe UI", Font.PLAIN, 9));
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
            if (label.isEmpty()) label = "新服务器";
            serverCombo.addItem(label);
        }
        int idx = Math.min(config.current_server, config.servers.size() - 1);
        if (idx >= 0) serverCombo.setSelectedIndex(idx);
    }

    private void updateServerLabel() {
        Main.ServerInfo s = config.current();
        if (s.host.isEmpty()) {
            serverLbl.setText("未选择服务器");
        } else {
            serverLbl.setText(s.name + "  " + s.username + "@" + s.host);
        }
    }

    // =================================================================
    // Timer
    // =================================================================
    private void startTimer() {
        pool.scheduleWithFixedDelay(() -> {
            if (!running) return;
            if (ssh.isConnected()) {
                SystemData d = monitor.collect();
                SwingUtilities.invokeLater(() -> render(d));
            }
        }, refreshMs, refreshMs, TimeUnit.MILLISECONDS);
    }

    // =================================================================
    // Render
    // =================================================================
    private void render(SystemData d) {
        if (d == null) return;
        if (!d.ok) { updateStatus(false, "采集失败"); return; }
        updateStatus(true, "已连接");

        cpuBar.setValue(d.cpuPercent);
        cpuLbl.setText(String.format("CPU %5.1f%%", d.cpuPercent));

        memBar.setValue(d.memPercent);
        memLbl.setText(String.format("MEM %5.1f%%  %.1f/%.1fGB",
                d.memPercent, d.memUsed / 1024, d.memTotal / 1024));

        String rx = formatSpeed(d.netRxSpeed);
        String tx = formatSpeed(d.netTxSpeed);
        netLbl.setText(String.format("\u2193%s  \u2191%s", rx, tx));
    }

    // =================================================================
    // Connection
    // =================================================================
    private void onConnect() {
        Main.ServerInfo s = config.current();
        if (s.host.isEmpty()) {
            JOptionPane.showMessageDialog(this, "请先添加服务器");
            return;
        }
        btnConnect.setEnabled(false);
        btnConnect.setText("中...");
        updateStatus(false, "连接中...");

        pool.submit(() -> {
            boolean ok = ssh.connect(s.host, s.port, s.username,
                    s.password, s.key_file, s.use_key);
            SwingUtilities.invokeLater(() -> {
                btnConnect.setEnabled(true);
                btnConnect.setText("连接");
                if (ok) {
                    btnDisconnect.setEnabled(true);
                    updateStatus(true, "已连接");
                    updateServerLabel();
                } else {
                    updateStatus(false, "失败: " + ssh.getLastError());
                }
            });
        });
    }

    private void onDisconnect() {
        ssh.disconnect();
        btnDisconnect.setEnabled(false);
        updateStatus(false, "未连接");
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

    // =================================================================
    // Server Manager
    // =================================================================
    private void onServerManager() {
        JDialog win = new JDialog(this, "服务器管理", true);
        win.getContentPane().setBackground(BG);
        win.setLayout(new BorderLayout());
        win.setSize(520, 340);
        win.setLocationRelativeTo(this);

        DefaultListModel<String> listModel = new DefaultListModel<>();
        for (Main.ServerInfo s : config.servers)
            listModel.addElement(s.name.isEmpty() ? "未命名" : s.name);
        JList<String> serverList = new JList<>(listModel);
        serverList.setBackground(BG2);
        serverList.setForeground(FG);
        serverList.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        serverList.setSelectionBackground(new Color(0x45475a));
        serverList.setSelectionForeground(FG);
        int si = Math.min(config.current_server, listModel.size() - 1);
        if (si >= 0) serverList.setSelectedIndex(si);

        JScrollPane listScroll = new JScrollPane(serverList);
        listScroll.setPreferredSize(new Dimension(150, 0));
        listScroll.setBorder(BorderFactory.createLineBorder(GRID));

        JPanel listBtns = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 4));
        listBtns.setBackground(BG);
        JButton addBtn = new JButton("+ 添加");
        addBtn.setBackground(ACCENT); addBtn.setForeground(new Color(0x11111b));
        addBtn.setFont(new Font("Segoe UI", Font.BOLD, 10));
        addBtn.setFocusPainted(false); addBtn.setBorderPainted(false);
        listBtns.add(addBtn);

        JButton delBtn = new JButton("\u2014 删除");
        delBtn.setBackground(COL_ERR); delBtn.setForeground(Color.WHITE);
        delBtn.setFont(new Font("Segoe UI", Font.BOLD, 10));
        delBtn.setFocusPainted(false); delBtn.setBorderPainted(false);
        listBtns.add(delBtn);

        JPanel leftPanel = new JPanel(new BorderLayout());
        leftPanel.setBackground(BG);
        leftPanel.add(listScroll, BorderLayout.CENTER);
        leftPanel.add(listBtns, BorderLayout.SOUTH);

        JPanel detail = new JPanel(new GridBagLayout());
        detail.setBackground(BG2);
        detail.setBorder(new EmptyBorder(10, 10, 10, 10));
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(3, 6, 3, 6); g.fill = GridBagConstraints.HORIZONTAL;

        String[][] fields = {{"name","名称"}, {"host","地址"}, {"port","端口"},
                {"username","用户名"}, {"password","密码"}, {"key_file","密钥文件"}};
        JTextField[] inputs = new JTextField[fields.length];
        for (int i = 0; i < fields.length; i++) {
            g.gridx = 0; g.gridy = i; g.weightx = 0;
            JLabel l = new JLabel(fields[i][1]);
            l.setForeground(FG); l.setFont(new Font("Segoe UI", Font.PLAIN, 10));
            detail.add(l, g);
            g.gridx = 1; g.weightx = 1.0;
            JTextField tf = new JTextField(16);
            tf.setBackground(BG); tf.setForeground(FG);
            tf.setCaretColor(FG); tf.setBorder(BorderFactory.createLineBorder(GRID));
            if (fields[i][0].equals("password")) {
                tf = new JPasswordField(16);
                tf.setBackground(BG); tf.setForeground(FG);
                tf.setCaretColor(FG); tf.setBorder(BorderFactory.createLineBorder(GRID));
            }
            inputs[i] = tf;
            detail.add(tf, g);
        }
        JCheckBox useKey = new JCheckBox("使用密钥登录");
        useKey.setBackground(BG2); useKey.setForeground(FG);
        useKey.setFont(new Font("Segoe UI", Font.PLAIN, 10));
        g.gridx = 0; g.gridy = fields.length; g.gridwidth = 2;
        detail.add(useKey, g);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, detail);
        split.setDividerLocation(165);
        split.setBorder(null);
        split.setBackground(BG);
        win.add(split, BorderLayout.CENTER);

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
        btns.setBackground(BG);
        JButton saveBtn = new JButton("保存");
        saveBtn.setBackground(ACCENT); saveBtn.setForeground(new Color(0x11111b));
        saveBtn.setFont(new Font("Segoe UI", Font.BOLD, 10));
        saveBtn.setFocusPainted(false); saveBtn.setBorderPainted(false);
        btns.add(saveBtn);

        JButton closeBtn = new JButton("关闭");
        closeBtn.setBackground(BG2); closeBtn.setForeground(FG);
        closeBtn.setFont(new Font("Segoe UI", Font.PLAIN, 10));
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
            listModel.set(idx, s.name.isEmpty() ? "未命名" : s.name);
            serverList.setSelectedIndex(idx);
            Main.saveConfig(config);
        });
        addBtn.addActionListener(e -> {
            Main.ServerInfo s = new Main.ServerInfo("新服务器", "", 22, "root");
            config.servers.add(s);
            listModel.addElement("新服务器");
            serverList.setSelectedIndex(listModel.size() - 1);
            loadSelected.run();
        });
        delBtn.addActionListener(e -> {
            int idx = serverList.getSelectedIndex();
            if (idx < 0 || config.servers.size() <= 1) return;
            if (JOptionPane.showConfirmDialog(win, "确定删除?", "确认",
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

    // =================================================================
    // Helpers
    // =================================================================
    private static String formatSpeed(double bps) {
        if (bps >= 1024 * 1024) return String.format("%.1fMB", bps / 1024 / 1024);
        if (bps >= 1024)        return String.format("%.0fKB", bps / 1024);
        return String.format("%.0fB", bps);
    }

    // =================================================================
    // MiniBar
    // =================================================================
    private static class MiniBar extends JPanel {
        private double value;
        private final Color color;
        MiniBar(Color color) { this.color = color; setBackground(BG2); setPreferredSize(new Dimension(0, 12)); }
        void setValue(double v) { this.value = Math.min(v, 100); repaint(); }
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            int bw = w - 4, bh = h - 4, bx = 2, by = 2;
            g2.setColor(new Color(0x181825));
            g2.fillRoundRect(bx, by, bw, bh, bh, bh);
            int fw = Math.max(0, (int) (bw * (value / 100.0)) - 2);
            if (fw > 0) { g2.setColor(color); g2.fillRoundRect(bx + 1, by + 1, fw, bh - 2, bh - 2, bh - 2); }
            g2.dispose();
        }
    }
}