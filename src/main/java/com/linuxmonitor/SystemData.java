package com.linuxmonitor;

import java.util.ArrayList;
import java.util.List;

public class SystemData {
    public double cpuPercent;
    public double load1, load5, load15;
    public double memTotal, memUsed, memPercent;
    public double swapTotal, swapUsed, swapPercent;
    public List<DiskInfo> disks = new ArrayList<>();
    public double netRxSpeed, netTxSpeed;
    public long netRxTotal, netTxTotal;
    public List<ProcInfo> processes = new ArrayList<>();
    public int uptimeDays, uptimeHours, uptimeMin;
    public int totalProcs, runningProcs;
    public boolean ok = true;
    public String error = "";

    public static class DiskInfo {
        public String mount, size, used, avail, percent;
    }

    public static class ProcInfo {
        public String user, name;
        public String pid, cpu, mem;
    }
}