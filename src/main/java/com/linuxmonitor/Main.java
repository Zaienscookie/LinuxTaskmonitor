package com.linuxmonitor;

import com.google.gson.*;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;

public class Main {
    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}

        SwingUtilities.invokeLater(() -> {
            Config cfg = loadConfig();
            new Dashboard(cfg).setVisible(true);
        });
    }

    public static Path appDir() {
        try {
            Path loc = Paths.get(Main.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            return Files.isDirectory(loc) ? loc : loc.getParent();
        } catch (Exception e) {
            return Paths.get(".");
        }
    }

    public static Path configPath() {
        Path inApp = appDir().resolve("config.json");
        if (Files.exists(inApp)) return inApp;
        Path parent = appDir().getParent();
        if (parent != null) {
            Path inParent = parent.resolve("config.json");
            if (Files.exists(inParent)) return inParent;
        }
        return inApp;
    }

    // =================================================================
    // Server info
    // =================================================================
    public static class ServerInfo {
        public String name = "新服务器";
        public String host = "";
        public int port = 22;
        public String username = "root";
        public String password = "";
        public String key_file = "";
        public boolean use_key = false;

        public ServerInfo() {}
        public ServerInfo(String name, String host, int port, String username) {
            this.name = name; this.host = host; this.port = port; this.username = username;
        }

        public String displayLabel() {
            return name + "  (" + username + "@" + host + ":" + port + ")";
        }
    }

    // =================================================================
    // Config
    // =================================================================
    public static class Config {
        public List<ServerInfo> servers = new ArrayList<>();
        public int current_server = 0;
        public int refresh_interval = 2;
        public String bg_color = "#1e1e2e";

        public ServerInfo current() {
            if (servers == null || servers.isEmpty()) {
                servers = new ArrayList<>();
                servers.add(new ServerInfo());
            }
            if (current_server < 0 || current_server >= servers.size()) {
                current_server = 0;
            }
            return servers.get(current_server);
        }
    }

    // =================================================================
    // Load / save
    // =================================================================
    private static Config loadConfig() {
        try (Reader r = Files.newBufferedReader(configPath())) {
            JsonObject obj = JsonParser.parseReader(r).getAsJsonObject();
            Config cfg;
            if (obj.has("servers")) {
                cfg = new Gson().fromJson(obj, Config.class);
            } else {
                cfg = new Config();
                ServerInfo si = new ServerInfo();
                if (obj.has("host"))        si.host     = obj.get("host").getAsString();
                if (obj.has("port"))        si.port     = obj.get("port").getAsInt();
                if (obj.has("username"))    si.username = obj.get("username").getAsString();
                if (obj.has("password"))    si.password = obj.get("password").getAsString();
                if (obj.has("key_file"))    si.key_file = obj.get("key_file").getAsString();
                if (obj.has("use_key"))     si.use_key  = obj.get("use_key").getAsBoolean();
                if (obj.has("refresh_interval")) cfg.refresh_interval = obj.get("refresh_interval").getAsInt();
                si.name = si.host.isEmpty() ? "服务器" : si.host;
                cfg.servers.add(si);
            }
            if (cfg.servers.isEmpty()) {
                cfg.servers.add(new ServerInfo());
            }
            saveConfig(cfg);
            return cfg;
        } catch (Exception e) {
            Config cfg = new Config();
            cfg.servers.add(new ServerInfo());
            return cfg;
        }
    }

    public static void saveConfig(Config cfg) {
        try (java.io.Writer w = Files.newBufferedWriter(configPath())) {
            new GsonBuilder().setPrettyPrinting().create().toJson(cfg, w);
        } catch (Exception ignored) {}
    }
}