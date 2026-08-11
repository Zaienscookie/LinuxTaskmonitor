package com.linuxmonitor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LinuxMonitor {
    private final SSHClient ssh;
    private long prevRx = -1, prevTx = -1;
    private long prevTime = System.currentTimeMillis();

    public LinuxMonitor(SSHClient ssh) {
        this.ssh = ssh;
    }

    public SystemData collect() {
        SystemData d = new SystemData();
        if (!ssh.isConnected()) {
            d.ok = false;
            d.error = ssh.getLastError().isEmpty() ? "not connected" : ssh.getLastError();
            return d;
        }
        try {
            getCpu(d);
            getMemory(d);
            getDisk(d);
            getNetwork(d);
            getProcesses(d);
            getSystemInfo(d);
            d.ok = true;
        } catch (Exception e) {
            d.ok = false;
            d.error = e.getMessage();
        }
        return d;
    }

    private void getCpu(SystemData d) {
        SSHClient.CmdResult r = ssh.execute("top -bn1 | head -5", 8);
        if (r.exitCode != 0) return;
        Matcher m = Pattern.compile("(\\d+[.,]\\d+)\\s*id").matcher(r.stdout);
        if (m.find()) {
            double idle = Double.parseDouble(m.group(1).replace(",", "."));
            d.cpuPercent = Math.round((100.0 - idle) * 10.0) / 10.0;
        }
        m = Pattern.compile("load average:\\s*([\\d.,]+)[,\\s]+([\\d.,]+)[,\\s]+([\\d.,]+)")
                .matcher(r.stdout);
        if (m.find()) {
            d.load1 = Double.parseDouble(m.group(1).replace(",", "."));
            d.load5 = Double.parseDouble(m.group(2).replace(",", "."));
            d.load15 = Double.parseDouble(m.group(3).replace(",", "."));
        }
    }

    private void getMemory(SystemData d) {
        SSHClient.CmdResult r = ssh.execute("free -m", 6);
        if (r.exitCode != 0) return;
        for (String line : r.stdout.split("\\R")) {
            if (line.startsWith("Mem:")) {
                String[] p = line.trim().split("\\s+");
                if (p.length >= 7) {
                    double total = Double.parseDouble(p[1]);
                    double used = Double.parseDouble(p[2]);
                    double avail = Double.parseDouble(p[6]);
                    d.memTotal = total;
                    d.memUsed = total - avail;
                    d.memPercent = total > 0 ? Math.round((d.memUsed / total * 100) * 10.0) / 10.0 : 0;
                }
            } else if (line.startsWith("Swap:")) {
                String[] p = line.trim().split("\\s+");
                if (p.length >= 3) {
                    d.swapTotal = Double.parseDouble(p[1]);
                    d.swapUsed = Double.parseDouble(p[2]);
                    d.swapPercent = d.swapTotal > 0 ? Math.round((d.swapUsed / d.swapTotal * 100) * 10.0) / 10.0 : 0;
                }
            }
        }
    }

    private void getDisk(SystemData d) {
        SSHClient.CmdResult r = ssh.execute("df -h 2>/dev/null | grep '^/'", 6);
        if (r.exitCode != 0) return;
        d.disks.clear();
        for (String line : r.stdout.split("\\R")) {
            String[] p = line.trim().split("\\s+");
            if (p.length >= 6) {
                SystemData.DiskInfo di = new SystemData.DiskInfo();
                di.mount = p[5];
                di.size = p[1];
                di.used = p[2];
                di.avail = p[3];
                di.percent = p[4].replace("%", "");
                d.disks.add(di);
            }
        }
    }

    private void getNetwork(SystemData d) {
        SSHClient.CmdResult r = ssh.execute("cat /proc/net/dev | tail -n +3", 6);
        if (r.exitCode != 0) return;
        long now = System.currentTimeMillis();
        long elapsed = now - prevTime;
        prevTime = now;
        long rx = 0, tx = 0;
        for (String line : r.stdout.split("\\R")) {
            String[] p = line.trim().split("\\s+");
            if (p.length >= 10) {
                String name = p[0].replace(":", "");
                if (name.equals("lo")) continue;
                rx += Long.parseLong(p[1]);
                tx += Long.parseLong(p[9]);
            }
        }
        d.netRxTotal = rx;
        d.netTxTotal = tx;
        if (prevRx >= 0 && elapsed > 0) {
            d.netRxSpeed = (rx - prevRx) * 1000.0 / elapsed;
            d.netTxSpeed = (tx - prevTx) * 1000.0 / elapsed;
        }
        prevRx = rx;
        prevTx = tx;
    }

    private void getProcesses(SystemData d) {
        SSHClient.CmdResult r = ssh.execute(
                "ps aux --sort=-%cpu 2>/dev/null | head -15", 8);
        if (r.exitCode != 0) return;
        d.processes.clear();
        String[] lines = r.stdout.split("\\R");
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            String[] p = line.split("\\s+", 11);
            if (p.length >= 11) {
                SystemData.ProcInfo pi = new SystemData.ProcInfo();
                pi.user = p[0];
                pi.pid = p[1];
                pi.cpu = p[2];
                pi.mem = p[3];
                pi.name = p[10].substring(0, Math.min(50, p[10].length()));
                d.processes.add(pi);
            }
        }
    }

    private void getSystemInfo(SystemData d) {
        SSHClient.CmdResult r = ssh.execute("cat /proc/uptime", 5);
        if (r.exitCode == 0) {
            try {
                double secs = Double.parseDouble(r.stdout.trim().split("\\s+")[0]);
                d.uptimeDays = (int) (secs / 86400);
                d.uptimeHours = (int) ((secs % 86400) / 3600);
                d.uptimeMin = (int) ((secs % 3600) / 60);
            } catch (Exception ignored) {}
        }
        r = ssh.execute("ps -e | wc -l", 5);
        if (r.exitCode == 0) {
            try { d.totalProcs = Integer.parseInt(r.stdout.trim()); } catch (Exception ignored) {}
        }
        r = ssh.execute("ps -e -o stat= | grep -c 'R'", 5);
        if (r.exitCode == 0) {
            try { d.runningProcs = Integer.parseInt(r.stdout.trim()); } catch (Exception ignored) {}
        }
    }
}