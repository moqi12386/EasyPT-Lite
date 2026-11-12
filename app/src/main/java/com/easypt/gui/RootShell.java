package com.easypt.gui;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 本机 root 模式：用 su + dd 直接读写自己的分区 */
public class RootShell {

    public static class Result {
        public boolean ok;
        public String out = "";
    }

    public static Result run(String cmd) {
        Result r = new Result();
        try {
            ProcessBuilder pb = new ProcessBuilder("su", "-c", cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) { sb.append(line).append('\n'); }
            p.waitFor();
            r.out = sb.toString().trim();
            r.ok = p.exitValue() == 0;
        } catch (Exception e) {
            r.ok = false;
            r.out = e.getMessage() == null ? e.toString() : e.getMessage();
        }
        return r;
    }

    public static boolean hasRoot() {
        Result r = run("id");
        return r.ok && r.out.contains("uid=0");
    }

    /** 列出 /dev/block/by-name 下的分区名，尽量带上大小 */
    public static List<String[]> partitions() {
        List<String[]> out = new ArrayList<String[]>();
        Result r = run("ls /dev/block/by-name 2>/dev/null");
        if (!r.ok || r.out.length() == 0) { return out; }

        List<String> names = new ArrayList<String>();
        for (String s : r.out.split("\n")) {
            String t = s.trim();
            if (t.length() > 0) { names.add(t); }
        }
        Collections.sort(names);

        Result sz = run("for p in /dev/block/by-name/*; do printf '%s %s\\n' $(basename $p) $(blockdev --getsize64 $p 2>/dev/null); done");
        java.util.Map<String, String> sizes = new java.util.HashMap<String, String>();
        if (sz.ok) {
            for (String line : sz.out.split("\n")) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 2) { sizes.put(parts[0], parts[1]); }
            }
        }
        for (String n : names) {
            out.add(new String[]{ n, sizes.containsKey(n) ? sizes.get(n) : "", "" });
        }
        return out;
    }

    /** dd 备份到应用缓存目录 */
    public static Result backup(String part, File dest) {
        return run("dd if=/dev/block/by-name/" + part + " of='" + dest.getAbsolutePath() + "' bs=1M 2>&1");
    }

    /** 把缓存里的镜像 dd 回去 */
    public static Result flash(String part, File src) {
        return run("dd if='" + src.getAbsolutePath() + "' of=/dev/block/by-name/" + part + " bs=1M 2>&1");
    }

    public static Result reboot(String target) {
        if (target == null || target.length() == 0) { return run("reboot"); }
        if (target.equals("edl")) { return run("reboot edl"); }
        return run("reboot " + target);
    }
}
