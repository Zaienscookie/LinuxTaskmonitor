package com.linuxmonitor;

import com.jcraft.jsch.*;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

public class SSHClient {
    private Session session;
    private boolean connected = false;
    private String lastError = "";

    public boolean connect(String host, int port, String username,
                           String password, String keyFile, boolean useKey) {
        disconnect();
        lastError = "";
        try {
            JSch jsch = new JSch();
            if (useKey && keyFile != null && !keyFile.isEmpty()) {
                jsch.addIdentity(keyFile);
            }
            session = jsch.getSession(username, host, port);
            if (!useKey && password != null && !password.isEmpty()) {
                session.setPassword(password);
            }
            session.setConfig("StrictHostKeyChecking", "no");
            session.setConfig("PreferredAuthentications",
                    "publickey,password,keyboard-interactive");
            session.connect(8000);
            connected = true;
            return true;
        } catch (Exception e) {
            lastError = e.getMessage();
            connected = false;
            return false;
        }
    }

    public CmdResult execute(String command, int timeoutSec) {
        if (!connected || session == null) {
            return new CmdResult(-1, "", "not connected");
        }
        try {
            ChannelExec channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(command);
            channel.setInputStream(null);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            channel.setOutputStream(out);
            channel.setErrStream(err);

            channel.connect(timeoutSec * 1000);

            long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
            while (!channel.isClosed() && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }

            // drain any remaining data
            try (InputStream in = channel.getInputStream()) {
                byte[] buf = new byte[4096];
                while (in.available() > 0) {
                    in.read(buf);
                }
            }

            int exitCode = channel.getExitStatus();
            channel.disconnect();
            return new CmdResult(exitCode,
                    out.toString("UTF-8"), err.toString("UTF-8"));
        } catch (Exception e) {
            connected = false;
            lastError = e.getMessage();
            return new CmdResult(-1, "", lastError);
        }
    }

    public void disconnect() {
        if (session != null) {
            try { session.disconnect(); } catch (Exception ignored) {}
            session = null;
        }
        connected = false;
    }

    public boolean isConnected() { return connected; }
    public String getLastError() { return lastError; }

    public static class CmdResult {
        public final int exitCode;
        public final String stdout;
        public final String stderr;
        public CmdResult(int c, String o, String e) {
            exitCode = c; stdout = o; stderr = e;
        }
    }
}