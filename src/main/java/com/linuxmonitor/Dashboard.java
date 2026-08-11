package com.linuxmonitor;

import java.awt.*;
import java.awt.event.*;
import java.awt.geom.RoundRectangle2D;
import java.text.SimpleDateFormat;
import java.util.Date;
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
    private static final Color TITLE_BG= new Color(0x181825);

    private static final Color[] PRESET = { new Color(0x1e1e2e), new Color(0x2e3440),
        new Color(0x1a1a2e), new Color(0x0d1117), new Color(0x282828), new Color(0x3b4252) };
    private static final String[] PRESET_NAMES = { "\u6df1\u8272", "\u53e4\u94dc",
        "\u7d2b\u591c", "\u591c\u8272", "\u7164\u7070", "\u94a2\u84dd" };
    private static final String FONT = "\u5fae\u8f6f\u96c5\u9ed1";
    private static final int ARC = 14;

    private final Main.Config config;
    private final SSHClient ssh = new SSHClient();
    private final LinuxMonitor monitor = new LinuxMonitor(ssh);
    private final ScheduledExecutorService pool = Executors.newScheduledThreadPool(2);
    private volatile boolean running = true;

    private Color bgColor;
    private AnimatedBar cpuBar, memBar;
    private JLabel statusDot, statusLbl, cpuLbl, memLbl, netLbl, serverLbl, updateTimeLbl;
    private JComboBox<String> serverCombo;
    private JButton btnConnect, btnDisconnect;
    private JPanel body, footer;
    private int refreshMs;
    private int px, py;
    private Timer pulseTimer;
    private boolean pulseOn = true;

    public Dashboard(Main.Config config) {
        this.config = config;
        this.refreshMs = Math.max(config.refresh_interval * 1000, 1000);
        this.bgColor = parseHex(config.bg_color, BG_DEF);
        setUndecorated(true);
        buildUi();
        updateStatus(false, "\u672a\u8fde\u63a5");
        addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) { updateShape(); }
        });
        startTimer();
        startPulse();
        // fade-in
        setOpacity(0f);
        Timer fade = new Timer(20, null);
        fade.addActionListener(e -> {
            float o = getOpacity() + 0.08f;
            if (o >= 1f) { o = 1f; fade.stop(); }
            setOpacity(o);
        });
        fade.start();
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                running = false; pool.shutdownNow(); ssh.disconnect();
                if (pulseTimer != null) pulseTimer.stop();
            }
        });
    }

    private void updateShape() {
        try { setShape(new RoundRectangle2D.Double(0, 0, getWidth(), getHeight(), ARC, ARC)); } catch (Exception ignored) {}
    }

    // =================================================================
    // UI
    // =================================================================
    private void buildUi() {
        setTitle("Linux Monitor");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(540, 400);
        setMinimumSize(new Dimension(420, 320));
        setAlwaysOnTop(true);
        setBackground(new Color(0,0,0,0));
        getContentPane().setBackground(bgColor);
        setLayout(new BorderLayout());
        // title bar
        add(titleBar(), BorderLayout.NORTH);
        // body
        body = new RoundedPanel(bgColor);
        body.setLayout(new GridBagLayout());
        body.setBorder(new EmptyBorder(8, 16, 6, 16));
        buildBody();
        add(body, BorderLayout.CENTER);
        // footer
        footer = new RoundedPanel(bgColor);
        footer.setLayout(new FlowLayout(FlowLayout.LEFT, 4, 6));
        buildFooter();
        add(footer, BorderLayout.SOUTH);
        updateServerLabel();
        updateShape();
    }

    private JPanel titleBar() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(TITLE_BG);
        p.setPreferredSize(new Dimension(0, 32));
        // drag
        MouseAdapter drag = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { px = e.getXOnScreen() - getX(); py = e.getYOnScreen() - getY(); }
            @Override public void mouseDragged(MouseEvent e) { setLocation(e.getXOnScreen() - px, e.getYOnScreen() - py); }
        };
        p.addMouseListener(drag); p.addMouseMotionListener(drag);

        JLabel t = new JLabel("  \u25CF  Linux Monitor");
        t.setFont(new Font(FONT, Font.BOLD, 12));
        t.setForeground(FG);
        p.add(t, BorderLayout.WEST);

        statusDot = new JLabel("\u25CF");
        statusDot.setFont(new Font(FONT, Font.PLAIN, 10));
        statusDot.setForeground(COL_ERR);
        p.add(statusDot);

        statusLbl = new JLabel("\u672a\u8fde\u63a5");
        statusLbl.setFont(new Font(FONT, Font.PLAIN, 9));
        statusLbl.setForeground(FG_DIM);
        p.add(statusLbl);

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 4));
        btns.setOpaque(false);
        JButton min = new JButton("\u2014");
        min.setFont(new Font(FONT, Font.PLAIN, 10)); min.setForeground(FG_DIM);
        min.setBackground(TITLE_BG); min.setFocusPainted(false); min.setBorderPainted(false);
        min.setCursor(new Cursor(Cursor.HAND_CURSOR));
        min.addActionListener(e -> setState(Frame.ICONIFIED));
        btns.add(min);

        JButton close = new JButton("\u00d7");
        close.setFont(new Font(FONT, Font.PLAIN, 13)); close.setForeground(COL_ERR);
        close.setBackground(TITLE_BG); close.setFocusPainted(false); close.setBorderPainted(false);
        close.setCursor(new Cursor(Cursor.HAND_CURSOR));
        close.addActionListener(e -> dispatchEvent(new WindowEvent(this, WindowEvent.WINDOW_CLOSING)));
        btns.add(close);

        p.add(btns, BorderLayout.EAST);
        return p;
    }

    private void buildBody() {
        GridBagConstraints g = new GridBagConstraints();
        g.fill = GridBagConstraints.HORIZONTAL;
        g.insets = new Insets(4, 0, 4, 0);

        g.gridx = 0; g.gridy = 0; g.weightx = 0; g.ipadx = 12;
        cpuLbl = new JLabel("CPU  0.0%");
        cpuLbl.setFont(new Font("Consolas", Font.BOLD, 18));
        cpuLbl.setForeground(COL_CPU);
        body.add(cpuLbl, g);

        g.gridx = 1; g.weightx = 1.0; g.ipadx = 0;
        cpuBar = new AnimatedBar(COL_CPU);
        body.add(cpuBar, g);

        g.gridx = 0; g.gridy = 1; g.weightx = 0; g.ipadx = 12;
        memLbl = new JLabel("MEM 0.0%");
        memLbl.setFont(new Font("Consolas", Font.BOLD, 18));
        memLbl.setForeground(COL_MEM);
        body.add(memLbl, g);

        g.gridx = 1; g.weightx = 1.0; g.ipadx = 0;
        memBar = new AnimatedBar(COL_MEM);
        body.add(memBar, g);

        g.gridx = 0; g.gridy = 2; g.weightx = 0; g.ipadx = 12;
        JLabel nl = new JLabel("NET");
        nl.setFont(new Font("Consolas", Font.BOLD, 14));
        nl.setForeground(FG_DIM);
        body.add(nl, g);

        g.gridx = 1; g.weightx = 1.0; g.ipadx = 0;
        netLbl = new JLabel("\u2193--  \u2191--");
        netLbl.setFont(new Font("Consolas", Font.PLAIN, 14));
        netLbl.setForeground(FG_DIM);
        body.add(netLbl, g);

        g.gridx = 0; g.gridy = 3; g.weightx = 0; g.ipadx = 12;
        JLabel sl = new JLabel("\u670d\u52a1\u5668");
        sl.setFont(new Font(FONT, Font.PLAIN, 12));
        sl.setForeground(FG_DIM);
        body.add(sl, g);

        g.gridx = 1; g.weightx = 1.0; g.ipadx = 0;
        serverLbl = new JLabel("\u672a\u9009\u62e9");
        serverLbl.setFont(new Font(FONT, Font.PLAIN, 12));
        serverLbl.setForeground(FG_DIM);
        body.add(serverLbl, g);

        g.gridx = 0; g.gridy = 4; g.weightx = 0; g.ipadx = 12;
        JLabel ul = new JLabel("\u66f4\u65b0");
        ul.setFont(new Font(FONT, Font.PLAIN, 11));
        ul.setForeground(FG_DIM);
        body.add(ul, g);

        g.gridx = 1; g.weightx = 1.0; g.ipadx = 0;
        updateTimeLbl = new JLabel("--");
        updateTimeLbl.setFont(new Font(FONT, Font.PLAIN, 11));
        updateTimeLbl.setForeground(FG_DIM);
        body.add(updateTimeLbl, g);
    }

    private void buildFooter() {
        serverCombo = new JComboBox<>();
        serverCombo.setBackground(BG2); serverCombo.setForeground(FG);
        serverCombo.setFont(new Font(FONT, Font.PLAIN, 11));
        serverCombo.setPreferredSize(new Dimension(200, 24));
        serverCombo.addActionListener(e -> {
            int idx = serverCombo.getSelectedIndex();
            if (idx >= 0 && idx < config.servers.size()) {
                config.current_server = idx; Main.saveConfig(config); updateServerLabel();
            }
        });
        refreshServerCombo();
        footer.add(serverCombo);

        JButton mgr = btn("\u7ba1\u7406", BG2, FG, this::onServerManager);
        footer.add(mgr);

        // size buttons
        int[][] sd = {{420,320},{540,400},{680,500}};
        String[] sdl = {"\u5c0f","\u4e2d","\u5927"};
        for (int i = 0; i < sd.length; i++) {
            final int fw = sd[i][0], fh = sd[i][1];
            JButton b = btn(sdl[i], BG2, FG_DIM, () -> { setSize(fw, fh); updateShape(); });
            b.setFont(new Font(FONT, Font.PLAIN, 9));
            b.setMargin(new Insets(0,4,0,4));
            footer.add(b);
        }

        JCheckBox top = new JCheckBox("\u7f6e\u9876");
        top.setSelected(true); top.setOpaque(false); top.setForeground(FG_DIM);
        top.setFont(new Font(FONT, Font.PLAIN, 10)); top.setFocusPainted(false);
        top.addActionListener(e -> setAlwaysOnTop(top.isSelected()));
        footer.add(top);

        footer.add(Box.createHorizontalGlue());

        btnConnect = btn("\u8fde\u63a5", ACCENT, new Color(0x11111b), this::onConnect);
        footer.add(btnConnect);

        btnDisconnect = btn("\u65ad\u5f00", BG2, FG, this::onDisconnect);
        btnDisconnect.setEnabled(false);
        footer.add(btnDisconnect);

        JButton theme = btn("\u4e3b\u9898", BG2, FG_DIM, this::onThemePicker);
        theme.setFont(new Font(FONT, Font.PLAIN, 9));
        footer.add(theme);
    }

    private JButton btn(String text, Color bg, Color fg, Runnable a) {
        JButton b = new JButton(text);
        b.setBackground(bg); b.setForeground(fg);
        b.setFont(new Font(FONT, Font.BOLD, 11));
        b.setFocusPainted(false); b.setBorderPainted(false);
        b.setCursor(new Cursor(Cursor.HAND_CURSOR));
        b.addActionListener(e -> a.run());
        return b;
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
        serverLbl.setText(s.host.isEmpty() ? "\u672a\u9009\u62e9" : s.name + "  " + s.username + "@" + s.host);
    }

    // =================================================================
    // Pulse
    // =================================================================
    private void startPulse() {
        pulseTimer = new Timer(600, e -> {
            pulseOn = !pulseOn;
            statusDot.setForeground(pulseOn ? statusDot.getForeground() : FG_DIM);
        });
        pulseTimer.start();
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
        String now = new SimpleDateFormat("HH:mm:ss").format(new Date());
        updateTimeLbl.setText(now);

        if (!d.ok) {
            updateStatus(false, d.error.isEmpty() ? "\u91c7\u96c6\u5931\u8d25" : "\u9519\u8bef: " + d.error);
            return;
        }
        updateStatus(true, "\u5df2\u8fde\u63a5");
        if (!d.hostname.isEmpty()) setTitle("Linux Monitor - " + d.hostname);

        cpuBar.animateTo(d.cpuPercent);
        cpuLbl.setText(String.format("CPU %5.1f%%", d.cpuPercent));

        memBar.animateTo(d.memPercent);
        memLbl.setText(String.format("MEM %5.1f%%  %.1f/%.1fGB",
                d.memPercent, d.memUsed / 1024, d.memTotal / 1024));

        netLbl.setText(String.format("\u2193%s  \u2191%s", formatSpeed(d.netRxSpeed), formatSpeed(d.netTxSpeed)));
    }

    // =================================================================
    // Connection
    // =================================================================
    private void onConnect() {
        Main.ServerInfo s = config.current();
        if (s.host.isEmpty()) { JOptionPane.showMessageDialog(this, "\u8bf7\u5148\u6dfb\u52a0\u670d\u52a1\u5668"); return; }
        btnConnect.setEnabled(false); btnConnect.setText("\u4e2d...");
        updateStatus(false, "\u8fde\u63a5\u4e2d...");
        pool.submit(() -> {
            boolean ok = ssh.connect(s.host, s.port, s.username, s.password, s.key_file, s.use_key);
            SwingUtilities.invokeLater(() -> {
                btnConnect.setEnabled(true); btnConnect.setText("\u8fde\u63a5");
                if (ok) { btnDisconnect.setEnabled(true); updateStatus(true, ""); updateServerLabel(); }
                else { updateStatus(false, "\u5931\u8d25: " + ssh.getLastError()); }
            });
        });
    }

    private void onDisconnect() {
        ssh.disconnect(); btnDisconnect.setEnabled(false);
        updateStatus(false, "\u672a\u8fde\u63a5"); setTitle("Linux Monitor");
        cpuBar.animateTo(0); memBar.animateTo(0);
        cpuLbl.setText("CPU  0.0%"); memLbl.setText("MEM 0.0%");
        netLbl.setText("\u2193--  \u2191--");
    }

    private void updateStatus(boolean connected, String text) {
        Color c = connected ? COL_OK : COL_ERR;
        statusDot.setForeground(c);
        statusLbl.setText(text.isEmpty() ? "\u5df2\u8fde\u63a5" : text);
        statusLbl.setForeground(connected ? COL_OK : FG_DIM);
        if (connected) { pulseTimer.stop(); statusDot.setForeground(COL_OK); }
        else { if (!pulseTimer.isRunning()) pulseTimer.start(); }
    }

    // =================================================================
    // Server Manager
    // =================================================================
    private void onServerManager() {
        JDialog win = new JDialog(this, "\u670d\u52a1\u5668\u7ba1\u7406", true);
        win.getContentPane().setBackground(bgColor); win.setLayout(new BorderLayout());
        win.setSize(520, 340); win.setLocationRelativeTo(this);
        DefaultListModel<String> lm = new DefaultListModel<>();
        for (Main.ServerInfo s : config.servers) lm.addElement(s.name.isEmpty() ? "\u672a\u547d\u540d" : s.name);
        JList<String> list = new JList<>(lm);
        list.setBackground(BG2); list.setForeground(FG);
        list.setFont(new Font(FONT, Font.PLAIN, 11));
        list.setSelectionBackground(new Color(0x45475a)); list.setSelectionForeground(FG);
        int si = Math.min(config.current_server, lm.size() - 1);
        if (si >= 0) list.setSelectedIndex(si);
        JScrollPane ls = new JScrollPane(list);
        ls.setPreferredSize(new Dimension(150, 0)); ls.setBorder(BorderFactory.createLineBorder(GRID));
        JPanel lb = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 4));
        lb.setBackground(bgColor);
        JButton addB = new JButton("+ \u6dfb\u52a0"); addB.setBackground(ACCENT); addB.setForeground(new Color(0x11111b));
        addB.setFont(new Font(FONT, Font.BOLD, 10)); addB.setFocusPainted(false); addB.setBorderPainted(false);
        lb.add(addB);
        JButton delB = new JButton("\u2014 \u5220\u9664"); delB.setBackground(COL_ERR); delB.setForeground(Color.WHITE);
        delB.setFont(new Font(FONT, Font.BOLD, 10)); delB.setFocusPainted(false); delB.setBorderPainted(false);
        lb.add(delB);
        JPanel lp = new JPanel(new BorderLayout()); lp.setBackground(bgColor);
        lp.add(ls, BorderLayout.CENTER); lp.add(lb, BorderLayout.SOUTH);

        JPanel det = new JPanel(new GridBagLayout());
        det.setBackground(BG2); det.setBorder(new EmptyBorder(10,10,10,10));
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(3,6,3,6); g.fill = GridBagConstraints.HORIZONTAL;
        String[][] fields = {{"name","\u540d\u79f0"},{"host","\u5730\u5740"},{"port","\u7aef\u53e3"},
                {"username","\u7528\u6237\u540d"},{"password","\u5bc6\u7801"},{"key_file","\u5bc6\u94a5\u6587\u4ef6"}};
        JTextField[] in = new JTextField[fields.length];
        for (int i = 0; i < fields.length; i++) {
            g.gridx=0; g.gridy=i; g.weightx=0;
            JLabel l = new JLabel(fields[i][1]); l.setForeground(FG); l.setFont(new Font(FONT,Font.PLAIN,10));
            det.add(l,g);
            g.gridx=1; g.weightx=1.0;
            JTextField tf = new JTextField(16); tf.setBackground(bgColor); tf.setForeground(FG);
            tf.setCaretColor(FG); tf.setBorder(BorderFactory.createLineBorder(GRID));
            if (fields[i][0].equals("password")) { tf = new JPasswordField(16); tf.setBackground(bgColor); tf.setForeground(FG); tf.setCaretColor(FG); tf.setBorder(BorderFactory.createLineBorder(GRID)); }
            in[i]=tf; det.add(tf,g);
        }
        JCheckBox uk = new JCheckBox("\u4f7f\u7528\u5bc6\u94a5\u767b\u5f55");
        uk.setBackground(BG2); uk.setForeground(FG); uk.setFont(new Font(FONT,Font.PLAIN,10));
        g.gridx=0; g.gridy=fields.length; g.gridwidth=2; det.add(uk,g);

        JSplitPane sp = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, lp, det);
        sp.setDividerLocation(165); sp.setBorder(null); sp.setBackground(bgColor);
        win.add(sp, BorderLayout.CENTER);
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT,6,6));
        btns.setBackground(bgColor);
        JButton sv = new JButton("\u4fdd\u5b58"); sv.setBackground(ACCENT); sv.setForeground(new Color(0x11111b));
        sv.setFont(new Font(FONT,Font.BOLD,10)); sv.setFocusPainted(false); sv.setBorderPainted(false);
        btns.add(sv);
        JButton cl = new JButton("\u5173\u95ed"); cl.setBackground(BG2); cl.setForeground(FG);
        cl.setFont(new Font(FONT,Font.PLAIN,10)); cl.setFocusPainted(false); cl.setBorderPainted(false);
        cl.addActionListener(e -> { refreshServerCombo(); updateServerLabel(); Main.saveConfig(config); win.dispose(); });
        btns.add(cl);
        win.add(btns, BorderLayout.SOUTH);

        Runnable load = () -> {
            int idx = list.getSelectedIndex();
            if (idx < 0 || idx >= config.servers.size()) return;
            Main.ServerInfo s = config.servers.get(idx);
            in[0].setText(s.name); in[1].setText(s.host); in[2].setText(String.valueOf(s.port));
            in[3].setText(s.username); in[4].setText(s.password); in[5].setText(s.key_file);
            uk.setSelected(s.use_key);
        };
        list.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) load.run(); });
        sv.addActionListener(e -> {
            int idx = list.getSelectedIndex(); if (idx < 0) return;
            Main.ServerInfo s = config.servers.get(idx);
            s.name=in[0].getText().trim(); s.host=in[1].getText().trim();
            try { s.port=Integer.parseInt(in[2].getText().trim()); } catch (Exception ignored) {}
            s.username=in[3].getText().trim(); s.password=in[4].getText().trim(); s.key_file=in[5].getText().trim();
            s.use_key=uk.isSelected();
            lm.set(idx, s.name.isEmpty() ? "\u672a\u547d\u540d" : s.name); list.setSelectedIndex(idx);
            Main.saveConfig(config);
        });
        addB.addActionListener(e -> {
            Main.ServerInfo s = new Main.ServerInfo("\u65b0\u670d\u52a1\u5668","",22,"root");
            config.servers.add(s); lm.addElement("\u65b0\u670d\u52a1\u5668");
            list.setSelectedIndex(lm.size()-1); load.run();
        });
        delB.addActionListener(e -> {
            int idx = list.getSelectedIndex();
            if (idx < 0 || config.servers.size() <= 1) return;
            if (JOptionPane.showConfirmDialog(win, "\u786e\u5b9a\u5220\u9664?", "\u786e\u8ba4", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
            config.servers.remove(idx); lm.remove(idx);
            if (idx >= lm.size()) idx = lm.size()-1;
            if (idx >= 0) list.setSelectedIndex(idx);
            load.run();
        });
        load.run(); win.setVisible(true);
    }

    // =================================================================
    // Theme
    // =================================================================
    private void onThemePicker() {
        JPopupMenu menu = new JPopupMenu(); menu.setBackground(BG2); menu.setBorder(BorderFactory.createLineBorder(GRID));
        for (int i = 0; i < PRESET.length; i++) {
            Color c = PRESET[i];
            JMenuItem item = new JMenuItem(PRESET_NAMES[i]);
            item.setFont(new Font(FONT,Font.PLAIN,11)); item.setBackground(BG2); item.setForeground(FG);
            item.setIcon(new ColorIcon(c));
            int fi = i; item.addActionListener(e -> applyBg(PRESET[fi]));
            menu.add(item);
        }
        menu.addSeparator();
        JMenuItem custom = new JMenuItem("\u81ea\u5b9a\u4e49...");
        custom.setFont(new Font(FONT,Font.PLAIN,11)); custom.setBackground(BG2); custom.setForeground(FG);
        custom.addActionListener(e -> { Color p = JColorChooser.showDialog(this, "\u9009\u62e9\u80cc\u666f\u8272", bgColor); if (p != null) applyBg(p); });
        menu.add(custom);
        menu.show(footer, footer.getWidth()/2, 0);
    }

    private void applyBg(Color c) {
        bgColor = c; config.bg_color = String.format("#%06x", c.getRGB() & 0xffffff);
        getContentPane().setBackground(c); body.setBackground(c); footer.setBackground(c);
        body.repaint(); footer.repaint(); Main.saveConfig(config);
    }

    private static Color parseHex(String hex, Color def) {
        try { return (hex == null || hex.isEmpty()) ? def : Color.decode(hex.startsWith("#")?hex:"#"+hex); }
        catch (Exception e) { return def; }
    }

    private static String formatSpeed(double bps) {
        if (bps >= 1024*1024) return String.format("%.1fMB", bps/1024/1024);
        if (bps >= 1024) return String.format("%.0fKB", bps/1024);
        return String.format("%.0fB", bps);
    }

    // =================================================================
    // RoundedPanel
    // =================================================================
    private static class RoundedPanel extends JPanel {
        private Color bg;
        RoundedPanel(Color bg) { this.bg = bg; setOpaque(false); }
        void setBg(Color c) { this.bg = c; repaint(); }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(bg);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), ARC, ARC);
            g2.dispose();
        }
    }

    // =================================================================
    // AnimatedBar
    // =================================================================
    private static class AnimatedBar extends JPanel {
        private final Color color;
        private double current = 0, target = 0;
        private Timer animTimer;
        AnimatedBar(Color color) {
            this.color = color; setOpaque(false);
            setPreferredSize(new Dimension(0, 20));
        }
        void animateTo(double v) {
            target = Math.min(v, 100);
            if (animTimer == null || !animTimer.isRunning()) {
                animTimer = new Timer(16, null);
                animTimer.addActionListener(e -> {
                    double diff = target - current;
                    if (Math.abs(diff) < 0.3) { current = target; animTimer.stop(); }
                    else current += diff * 0.25;
                    repaint();
                });
                animTimer.start();
            }
        }
        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            int bw = w - 4, bh = h - 8, bx = 2, by = 4, r = bh;
            g2.setColor(new Color(0x181825));
            g2.fillRoundRect(bx, by, bw, bh, r, r);
            int fw = Math.max(0, (int) (bw * (current / 100.0)) - 2);
            if (fw > 0) {
                g2.setColor(color);
                g2.fillRoundRect(bx + 1, by + 1, fw, bh - 2, r - 1, r - 1);
            }
            g2.dispose();
        }
    }

    // =================================================================
    // ColorIcon
    // =================================================================
    private static class ColorIcon implements Icon {
        private final Color color;
        ColorIcon(Color c) { this.color = c; }
        public int getIconWidth() { return 12; }
        public int getIconHeight() { return 12; }
        public void paintIcon(Component c, Graphics g, int x, int y) {
            g.setColor(color); g.fillRect(x, y, 12, 12);
            g.setColor(Color.GRAY); g.drawRect(x, y, 12, 12);
        }
    }
}