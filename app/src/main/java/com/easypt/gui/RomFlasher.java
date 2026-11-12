package com.easypt.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 线刷包解析：把 flash_all.bat / flash_all.sh 里的 fastboot 行翻译成可执行步骤。
 * 没有脚本时，按目录里的 *.img 文件名自动生成“刷到同名分区”的步骤。
 */
public class RomFlasher {

    public static class Step {
        public String kind = "cmd";   // flash / erase / reboot / oem / cmd / wipe
        public String part = "";
        public String file = "";      // 仅文件名（小写），用于在所选目录里查 URI
        public String arg = "";
        public String raw = "";
        public boolean missing = false;

        public String label() {
            switch (kind) {
                case "flash": return "flash " + part + "  " + file;
                case "erase": return "erase " + part;
                case "reboot": return "reboot " + arg;
                case "oem": return "oem " + arg;
                case "wipe": return "erase userdata / metadata";
                default: return raw;
            }
        }
    }

    private static String base(String path) {
        String p = path.replace('\\', '/');
        int i = p.lastIndexOf('/');
        if (i >= 0) { p = p.substring(i + 1); }
        // 去掉可能的引号
        while (p.startsWith("\"") || p.startsWith("'")) { p = p.substring(1); }
        while (p.endsWith("\"") || p.endsWith("'")) { p = p.substring(0, p.length() - 1); }
        return p;
    }

    /** 从 flash_all 脚本里取出它要求的机型（`getvar product ... grep "product: *polaris"`） */
    public static String expectedProduct(String text) {
        if (text == null) { return ""; }
        for (String line : text.split("\n")) {
            String low = line.toLowerCase(Locale.US);
            if (!low.contains("getvar") || !low.contains("product")) { continue; }
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("product:\\s*\\*?([A-Za-z0-9_\\-]+)")
                    .matcher(line);
            if (m.find()) { return m.group(1); }
        }
        return "";
    }

    /** 从一行 token 里找出镜像文件名：像路径的、带后缀的那个（.sh 里是 `dirname $0`/images/xbl.img 这种） */
    private static String findFile(String[] toks) {
        for (int i = toks.length - 1; i >= 0; i--) {
            String t = toks[i];
            if (t == null) { continue; }
            String b = base(t);
            String l = b.toLowerCase(Locale.US);
            if (l.endsWith(".img") || l.endsWith(".bin") || l.endsWith(".elf") || l.endsWith(".mbn")) {
                return l;
            }
        }
        // 兜底：最后一个参数
        return toks.length > 0 ? base(toks[toks.length - 1]).toLowerCase(Locale.US) : "";
    }

    public static List<Step> fromScript(String text) {
        List<Step> steps = new ArrayList<Step>();
        if (text == null) { return steps; }
        for (String line : text.split("\n")) {
            String s = line.replace("\r", "").trim();
            if (s.length() == 0) { continue; }
            String low = s.toLowerCase(Locale.US);
            if (low.startsWith("::") || low.startsWith("rem ") || low.startsWith("#")
                    || low.startsWith("@echo") || low.startsWith("echo ")
                    || low.startsWith("set ") || low.startsWith("title ")) { continue; }
            int idx = low.indexOf("fastboot");
            if (idx < 0) { continue; }
            String rest = s.substring(idx + 8).trim();
            if (rest.length() == 0) { continue; }

            String[] rawTokens = rest.split("\\s+");
            List<String> tokens = new ArrayList<String>();
            boolean wipe = false;
            for (int i = 0; i < rawTokens.length; i++) {
                String tk = rawTokens[i];
                if (tk.length() == 0) { continue; }
                if (tk.equals("-w")) { wipe = true; continue; }
                if (tk.equals("-s") || tk.equals("-i") || tk.equals("--slot")) {
                    i++;                          // 跳过参数值
                    continue;
                }
                // $* / $1 / %* 是各平台脚本里转发参数的占位符，不是命令
                if (tk.startsWith("-") || tk.startsWith("%") || tk.startsWith("$")) { continue; }
                tokens.add(tk);
            }
            if (wipe) {
                Step w = new Step();
                w.kind = "wipe";
                w.raw = "fastboot -w";
                steps.add(w);
            }
            if (tokens.isEmpty()) { continue; }

            String cmd = tokens.get(0).toLowerCase(Locale.US);
            // 预检行（getvar product / getvar anti）不执行，由 App 自己在连接时校验
            if (cmd.equals("getvar") || cmd.equals("devices") || cmd.equals("version")) { continue; }
            Step st = new Step();
            st.raw = "fastboot " + rest;
            if (cmd.equals("flash") && tokens.size() >= 2) {
                st.kind = "flash";
                st.part = tokens.get(1);
                st.file = findFile(rawTokens);
            } else if (cmd.equals("erase") && tokens.size() >= 2) {
                st.kind = "erase";
                st.part = tokens.get(1);
            } else if (cmd.equals("format") && tokens.size() >= 2) {
                // fastboot format userdata：本质就是清掉这个分区
                st.kind = "erase";
                st.part = tokens.get(1);
            } else if (cmd.equals("reboot")) {
                st.kind = "reboot";
                st.arg = tokens.size() >= 2 ? tokens.get(1) : "";
            } else if (cmd.equals("oem")) {
                st.kind = "oem";
                st.arg = tokens.size() >= 2 ? tokens.get(1) : "";
            } else {
                st.kind = "cmd";
            }
            steps.add(st);
        }
        return steps;
    }

    /** 没有脚本时：每个 img/bin 刷到与文件同名的分区 */
    public static List<Step> fromImages(List<String> names) {
        List<Step> steps = new ArrayList<Step>();
        if (names == null) { return steps; }
        List<String> sorted = new ArrayList<String>(names);
        java.util.Collections.sort(sorted, new java.util.Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                int wa = weight(partOf(a));
                int wb = weight(partOf(b));
                if (wa != wb) { return wa < wb ? -1 : 1; }
                return a.compareToIgnoreCase(b);
            }
        });
        for (String n : sorted) {
            String low = n.toLowerCase(Locale.US);
            if (!low.endsWith(".img") && !low.endsWith(".bin")) { continue; }
            String part = low.substring(0, low.lastIndexOf('.'));
            if (part.length() == 0) { continue; }
            Step st = new Step();
            st.kind = "flash";
            st.part = part;
            st.file = low;
            st.raw = "fastboot flash " + part + " " + n;
            steps.add(st);
        }
        return steps;
    }

    /** 文件名 -> 分区名 */
    public static String partOf(String fileName) {
        String low = fileName.toLowerCase(Locale.US);
        int dot = low.lastIndexOf('.');
        return dot > 0 ? low.substring(0, dot) : low;
    }

    /** 常见分区顺序：boot/vbmeta 之类先在前面，super 放最后 */
    public static int weight(String part) {
        String p = part.toLowerCase(Locale.US);
        if (p.equals("super")) { return 90; }
        if (p.startsWith("vbmeta")) { return 10; }
        if (p.equals("boot") || p.equals("init_boot")) { return 12; }
        if (p.equals("dtbo")) { return 14; }
        if (p.startsWith("system") || p.startsWith("product") || p.startsWith("vendor")) { return 80; }
        if (p.startsWith("userdata")) { return 95; }
        return 50;
    }
}
