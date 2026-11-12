package com.easypt.gui;

import android.content.Context;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 在 USB 主机模式下直接说 fastboot 协议（不依赖 adb / 不依赖 root）。
 * 协议：64 字节命令包 "FASTBOOT" + 命令，64 字节响应包 "OKAY"/"FAIL"/"DATA"/"INFO"。
 */
public class FastbootUsb {

    private UsbDevice device;
    private UsbDeviceConnection conn;
    private UsbInterface intf;
    private UsbEndpoint epIn;
    private UsbEndpoint epOut;

    public interface Logger { void log(String line); }

    private final Logger logger;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());

    public FastbootUsb(Logger logger) { this.logger = logger; }

    private void say(String s) { if (logger != null) { logger.log(s); } }

    private static String hex(int v) { return "0x" + Integer.toHexString(v); }

    private static String hex16(int v) { return String.format("%04x", v); }

    /** 规范里的 fastboot 接口：厂商自定义类 + subclass 0x42 + protocol 0x03 */
    private static boolean isFastboot(UsbInterface it) {
        return it.getInterfaceClass() == 0xFF
                && it.getInterfaceSubclass() == 0x42
                && it.getInterfaceProtocol() == 0x03;
    }

    /** 接口里是否同时有 bulk 进和 bulk 出端点 */
    private static boolean hasBulkPair(UsbInterface it) {
        boolean in = false, out = false;
        for (int i = 0; i < it.getEndpointCount(); i++) {
            UsbEndpoint ep = it.getEndpoint(i);
            if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) { continue; }
            if (ep.getDirection() == UsbConstants.USB_DIR_IN) { in = true; } else { out = true; }
        }
        return in && out;
    }

    /** 明显不是 fastboot 的接口：存储(0x08)、音频(0x01)、HID(0x03)、集线器(0x09)、打印机(0x07)、视频(0x0E) */
    private static boolean isObviousNonFastboot(UsbInterface it) {
        int c = it.getInterfaceClass();
        return c == 0x08 || c == 0x01 || c == 0x03 || c == 0x09 || c == 0x07 || c == 0x0E;
    }

    /**
     * 挑接口：先按规范找 fastboot 接口，再退到任意厂商自定义接口，
     * 最后退到任意带 bulk 进/出端点的接口（有的机器 descriptor 不按规范写）。
     */
    private static UsbInterface pickInterface(UsbDevice d) {
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            if (isFastboot(d.getInterface(i))) { return d.getInterface(i); }
        }
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            if (d.getInterface(i).getInterfaceClass() == 0xFF) { return d.getInterface(i); }
        }
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface it = d.getInterface(i);
            if (!isObviousNonFastboot(it) && hasBulkPair(it)) { return it; }
        }
        return null;
    }

    /** 找一个处于 fastboot 模式的 USB 设备（interface 0xFF/0x42/0x03） */
    public static UsbDevice findFastbootDevice(UsbManager um) {
        return findFastbootDevice(um, null);
    }

    /**
     * 带日志的三轮匹配：
     * 1) 标准 fastboot 接口 0xFF/0x42/0x03；
     * 2) 任意厂商自定义接口（class 0xFF）；
     * 3) 任意带 bulk 进/出端点的接口。
     * 第二轮是本版本新加的：有些机型 / 某些 fastbootd 的 descriptor 不是 0xFF/0x42/0x03，
     * 以前直接判定「没找到」，现在能认出来并且日志里写明是按哪一轮命中的。
     */
    public static UsbDevice findFastbootDevice(UsbManager um, Logger logger) {
        HashMap<String, UsbDevice> list = um.getDeviceList();
        for (Map.Entry<String, UsbDevice> e : list.entrySet()) {
            UsbDevice d = e.getValue();
            for (int i = 0; i < d.getInterfaceCount(); i++) {
                if (isFastboot(d.getInterface(i))) {
                    if (logger != null) { logger.log("命中 fastboot 接口：" + d.getDeviceName()); }
                    return d;
                }
            }
        }
        for (Map.Entry<String, UsbDevice> e : list.entrySet()) {
            UsbDevice d = e.getValue();
            for (int i = 0; i < d.getInterfaceCount(); i++) {
                UsbInterface it = d.getInterface(i);
                if (it.getInterfaceClass() == 0xFF) {
                    if (logger != null) {
                        logger.log("接口不是标准 fastboot，按厂商自定义接口使用：" + d.getDeviceName()
                                + "（" + hex(it.getInterfaceClass()) + "/" + hex(it.getInterfaceSubclass())
                                + "/" + hex(it.getInterfaceProtocol()) + "）");
                    }
                    return d;
                }
            }
        }
        for (Map.Entry<String, UsbDevice> e : list.entrySet()) {
            UsbDevice d = e.getValue();
            for (int i = 0; i < d.getInterfaceCount(); i++) {
                UsbInterface it = d.getInterface(i);
                if (!isObviousNonFastboot(it) && hasBulkPair(it)) {
                    if (logger != null) {
                        logger.log("没认出 fastboot 特征，仍然尝试打开：" + d.getDeviceName());
                    }
                    return d;
                }
            }
        }
        return null;
    }

    /** 把当前所有 USB 设备（接口 / 端点 / 权限）写进日志，排查「插上了但读不到」用 */
    public static void dump(UsbManager um, Logger logger) {
        if (logger == null) { return; }
        HashMap<String, UsbDevice> list = um.getDeviceList();
        logger.log("USB 设备数：" + list.size());
        if (list.size() == 0) {
            logger.log("系统一个 USB 设备都没枚举到，不是 app 的匹配问题，是物理层没连上：");
            logger.log("① 主机手机的 OTG 开关（小米/红米：设置 → 更多设置 → OTG）");
            logger.log("② 换一根确定能传数据的线，中间不要接 HUB / 转接头");
            logger.log("③ 对方必须停在 FASTBOOT 黑底白字界面，普通开机状态不会出现 fastboot 接口");
            logger.log("④ 两口都是 Type-C 时，C-to-C 线有可能把主机判成设备；换成 A-to-C 线 + OTG 转接头再试");
            return;
        }
        for (Map.Entry<String, UsbDevice> e : list.entrySet()) {
            UsbDevice d = e.getValue();
            logger.log("• " + d.getDeviceName()
                    + "  VID:PID " + hex16(d.getVendorId()) + ":" + hex16(d.getProductId())
                    + "  设备类 " + hex(d.getDeviceClass())
                    + "  接口 " + d.getInterfaceCount()
                    + (um.hasPermission(d) ? "  [已授权]" : "  [未授权]"));
            for (int i = 0; i < d.getInterfaceCount(); i++) {
                UsbInterface it = d.getInterface(i);
                logger.log("    [#" + i + "] " + hex(it.getInterfaceClass()) + "/" + hex(it.getInterfaceSubclass())
                        + "/" + hex(it.getInterfaceProtocol())
                        + (isFastboot(it) ? "  ← 标准 fastboot 接口" : "")
                        + "   端点 " + it.getEndpointCount());
                for (int j = 0; j < it.getEndpointCount(); j++) {
                    UsbEndpoint ep = it.getEndpoint(j);
                    logger.log("        ep " + hex(ep.getAddress()) + "  类型 " + ep.getType()
                            + "  包大小 " + ep.getMaxPacketSize());
                }
            }
        }
    }

    public String deviceName() {
        return device == null ? "-" : (device.getManufacturerName() == null ? device.getDeviceName() : device.getManufacturerName() + " " + device.getDeviceName());
    }

    private Context appCtx;

    public synchronized boolean reopen() {
        if (appCtx == null) { return false; }
        close();
        // 重新扫描一遍：设备很可能已经重新枚举过，旧 UsbDevice 对象已经失效
        UsbDevice fresh = null;
        try {
            android.hardware.usb.UsbManager um =
                    (android.hardware.usb.UsbManager) appCtx.getSystemService(Context.USB_SERVICE);
            fresh = findFastbootDevice(um);
        } catch (Exception ignored) { }
        if (fresh == null) { fresh = device; }
        if (fresh == null) { return false; }
        boolean ok = open(appCtx, fresh);
        say(ok ? "USB 已重新打开" : "USB 重新打开失败");
        return ok;
    }

    public synchronized boolean open(Context ctx, UsbDevice d) {
        appCtx = ctx;
        maxCache = -1;
        close();
        if (d == null) { say("没有找到处于 fastboot 模式的 USB 设备"); return false; }
        UsbManager um = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
        if (!um.hasPermission(d)) { say("缺少 USB 权限，请重新插拔并授权"); return false; }

        UsbInterface target = pickInterface(d);
        if (target == null) {
            say("设备的接口里没有可用端点，读不到 fastboot");
            return false;
        }

        conn = um.openDevice(d);
        if (conn == null) { say("打不开 USB 设备"); return false; }
        if (!conn.claimInterface(target, true)) { say("claimInterface 失败"); conn.close(); conn = null; return false; }

        UsbEndpoint in = null, out = null;
        for (int i = 0; i < target.getEndpointCount(); i++) {
            UsbEndpoint ep = target.getEndpoint(i);
            if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) { continue; }
            if (ep.getDirection() == UsbConstants.USB_DIR_IN) { in = ep; } else { out = ep; }
        }
        if (in == null || out == null) { say("没找到 bulk 端点"); return false; }

        device = d;
        intf = target;
        epIn = in;
        epOut = out;
        say("已连接：" + d.getDeviceName() + "（VID " + Integer.toHexString(d.getVendorId()) + "）");
        return true;
    }

    public synchronized void close() {
        try { if (conn != null) { conn.releaseInterface(intf); conn.close(); } } catch (Exception ignored) { }
        conn = null;
        device = null;
    }

    public boolean isOpen() { return conn != null; }

    // ---------------- 协议 ----------------

    private static byte[] packet(String text) {
        byte[] buf = new byte[64];
        byte[] raw = text.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(raw, 0, buf, 0, Math.min(raw.length, 64));
        return buf;
    }

    /**
     * 一次 bulk 传完，不要按 maxPacket 切成 512 字节一个个发：
     * 一是慢，二是大镜像传完后端点容易卡死（实测 728 MB 之后连 64 字节命令都写不进去）。
     * 写失败时先清 HALT 再重试一次。
     */
    private boolean bulkOut(byte[] data, int len) {
        int off = 0;
        // 一次发 64 KB 会被这台设备直接 -1 拒绝（USB 层），必须分片。
        // 16 KB 比最早的 512 字节少 32 倍请求，实测不会触发该限制。
        final int MAX_XFER = 16384;
        while (off < len) {
            int chunk = Math.min(MAX_XFER, len - off);
            int n = conn.bulkTransfer(epOut, slice(data, off, chunk), chunk, 8000);
            if (n <= 0) {
                android.util.Log.w("EasyFB", "bulkOut 失败 off=" + off + "/" + len + " ret=" + n + "，清 HALT 重试");
                clearHalt();
                n = conn.bulkTransfer(epOut, slice(data, off, chunk), chunk, 8000);
                if (n <= 0) {
                    android.util.Log.w("EasyFB", "bulkOut 重试仍失败 ret=" + n);
                    return false;
                }
            }
            off += n;
        }
        return true;
    }

    /** 清端点 HALT（CLEAR_FEATURE ENDPOINT_HALT），大传输后端点会被卡住 */
    private void clearHalt() {
        try {
            if (conn != null && epOut != null) {
                conn.controlTransfer(0x02, 0x01, 0x00, epOut.getAddress(), null, 0, 1000);
            }
        } catch (Exception ignored) { }
        try {
            if (conn != null && epIn != null) {
                conn.controlTransfer(0x02, 0x01, 0x00, epIn.getAddress(), null, 0, 1000);
            }
        } catch (Exception ignored) { }
    }

    private static byte[] slice(byte[] src, int off, int len) {
        byte[] out = new byte[len];
        System.arraycopy(src, off, out, 0, len);
        return out;
    }

    /** 当前读超时：flash / erase 要等设备擦写，几十秒很正常，不能再用 5 秒 */
    private int readTimeout = 30000;

    private String readResponse() {
        byte[] buf = new byte[64];
        int n = conn.bulkTransfer(epIn, buf, 64, readTimeout);
        if (verbose) { android.util.Log.d("EasyFB", "IN  ret=" + n); }
        if (n <= 0) { return null; }
        String s = new String(buf, 0, n, StandardCharsets.US_ASCII);
        return s.trim();
    }

    /** 只有跑协议自测时才把每一包都打进 logcat，平时安静 */
    private static boolean verbose = false;

    /** 发一条命令，收集所有 INFO，返回最后的状态（OKAY/FAIL...） */
    public String command(String cmd) {
        return command(cmd, null, false);
    }

    /**
     * 有的 bootloader（实测 MIX 2S）不认 "FASTBOOT" 前缀，直接吃裸命令，
     * 并且用 "OKAY<值>" 而不是标准的 "INFO..." + "OKAY" 回包。
     * 这里做自动探测：标准帧被回 unknown command 时，自动换成裸帧重发一次。
     */
    private synchronized String command(String cmd, StringBuilder sink, boolean quiet) {
        if (!isOpen()) { return "FAIL:not-open"; }
        String r = commandOnce(cmd, sink, quiet, useRawFrame);
        if ("FAIL:write".equals(r) || "FAIL:timeout".equals(r)) {
            // 端点卡住、或者设备已经重新枚举（旧句柄失效）时，重开一次再试
            clearHalt();
            if (reopen()) {
                r = commandOnce(cmd, sink, quiet, useRawFrame);
            }
        }
        if (useRawFrame) { return r; }
        if (r != null && r.startsWith("FAIL") && r.contains("unknown command")) {
            // 第一次没被识别：改用裸命令（不带 FASTBOOT 前缀）重发一次
            StringBuilder sink2 = new StringBuilder();
            String r2 = commandOnce(cmd, sink2, quiet, true);
            if (r2 != null && !r2.startsWith("FAIL")) {
                useRawFrame = true;
                say("bootloader 不接受 FASTBOOT 前缀，已切换为裸命令模式");
                if (sink != null && sink2.length() > 0) { sink.setLength(0); sink.append(sink2); }
                return r2;
            }
            if (sink != null && sink2.length() > 0) { sink.setLength(0); sink.append(sink2); }
            return r2 != null ? r2 : r;
        }
        return r;
    }

    /** 裸命令模式：false = 标准 "FASTBOOT"+cmd；true = 只发 cmd（实测 MIX 2S 需要） */
    private boolean useRawFrame = false;

    /** 测试钩子：强制分块大小（字节），0 = 不启用 */
    public static long forceChunk = 0;   // 测试钩子：0 = 关闭

    private String commandOnce(String cmd, StringBuilder sink, boolean quiet, boolean raw) {
        String wire = raw ? cmd : ("FASTBOOT" + cmd);
        // 刷写/擦除要等设备真的写完，超时给足；其它命令保持较短，出错能快速暴露
        boolean slow = cmd.startsWith("flash:") || cmd.startsWith("erase:") || cmd.startsWith("format:");
        readTimeout = slow ? 180000 : 30000;
        if (verbose) { android.util.Log.d("EasyFB", "CMD [" + wire + "] timeout=" + readTimeout); }
        byte[] pkt = new byte[64];
        byte[] bytes = wire.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, pkt, 0, Math.min(bytes.length, 64));
        if (!bulkOut(pkt, 64)) { return "FAIL:write"; }

        for (int guard = 0; guard < 2000; guard++) {
            String resp = readResponse();
            if (resp == null) { return "FAIL:timeout"; }
            if (resp.startsWith("INFO")) {
                String t = resp.length() > 4 ? resp.substring(4).trim() : "";
                if (t.length() > 0) {
                    if (sink != null) { sink.append(t).append('\n'); }
                    if (!quiet) { say(t); }
                }
                continue;
            }
            if (resp.startsWith("OKAY")) {
                // 兼容 "OKAY<值>" 这种把数据塞在 OKAY 后面的固件
                String t = resp.length() > 4 ? resp.substring(4).trim() : "";
                if (t.length() > 0) {
                    if (sink != null) { sink.append(t).append('\n'); }
                    if (!quiet) { say(t); }
                }
                return "OKAY";
            }
            if (resp.startsWith("FAIL")) {
                String t = resp.length() > 4 ? resp.substring(4).trim() : "";
                if (!quiet) { say("失败：" + t); }
                return "FAIL:" + t;
            }
            if (resp.startsWith("DATA")) { return resp; }
        }
        return "FAIL:loop";
    }

    /** 把数据 download 到设备 */
    public boolean download(byte[] data) {
        String head = command(String.format("download:%08x", data.length));
        if (!head.startsWith("DATA")) { return false; }
        if (!bulkOut(data, data.length)) { return false; }
        String resp = readResponse();
        return resp != null && resp.startsWith("OKAY");
    }

    public boolean flash(String partition, byte[] data) {
        say("上传 " + partition + "（" + (data.length / 1048576.0) + " MB）…");
        if (!download(data)) { say("上传失败"); return false; }
        String r = command("flash:" + partition);
        boolean ok = r.startsWith("OKAY");
        say(ok ? ("刷入成功：" + partition) : ("刷入失败：" + partition + " " + r));
        return ok;
    }

    /** 从文件流式上传（大镜像不占内存） */
    public boolean downloadStream(java.io.InputStream in, long size, Progress p) {
        String head = command(String.format(Locale.US, "download:%08x", size));
        if (!head.startsWith("DATA")) {
            say("download 被拒（" + size + " 字节）：" + head);
            return false;
        }
        byte[] buf = new byte[65536];
        long sent = 0;
        try {
            int n;
            while ((n = in.read(buf)) > 0) {
                if (!bulkOut(buf, n)) { return false; }
                sent += n;
                if (p != null) { p.onProgress(sent, size); }
            }
        } catch (Exception e) {
            say("读取镜像失败：" + e.getMessage());
            return false;
        }
        String resp = readResponse();
        return resp != null && resp.startsWith("OKAY");
    }

    public boolean flashFile(String partition, java.io.File img, Progress p) {
        java.io.InputStream in = null;
        try {
            in = new java.io.FileInputStream(img);
            return flashStream(partition, in, img.length(), p);
        } catch (Exception e) {
            say("打开镜像失败：" + e.getMessage());
            return false;
        } finally {
            try { if (in != null) { in.close(); } } catch (Exception ignored) { }
        }
    }

    /** 直接从输入流刷入，不落盘（大镜像省一次完整读写）。超过设备 max-download-size 时自动 sparse 分块 */
    public synchronized boolean flashStream(String partition, java.io.InputStream in, long size, Progress p) {
        long max = maxDownloadSize();
        long limit = (max > 0) ? max : 256L * 1024 * 1024;
        if (forceChunk > 0) { limit = forceChunk; }
        say("镜像 " + fmtMb(size) + " MB，设备单次上限 " + fmtMb(limit) + " MB" + (max > 0 ? "" : "（未取到，按 256 MB 处理）"));

        // 先看前 28 字节：ROM 里的 system / system_ext / product / vendor 本身就是 Android
        // sparse 文件，必须按它的块边界重新打包；按字节硬切会把块头劈开，设备收到的是垃圾。
        byte[] head = new byte[28];
        int got = readFully(in, head, 0, 28);
        java.io.InputStream full = new java.io.SequenceInputStream(
                new java.io.ByteArrayInputStream(head, 0, Math.max(got, 0)), in);
        // 能整包发就整包发，不做任何重打包（和电脑端 fastboot 行为一致）
        if (size <= limit) {
            return flashStreamPlain(partition, full, size, p);
        }
        if (got == 28 && rd32(head, 0) == 0xED26FF3AL) {
            return flashSparseImage(partition, in, head, limit, p);
        }
        return flashRawImage(partition, full, size, limit, p);
    }

    private String fmtMb(long v) { return String.format(Locale.US, "%.0f", v / 1048576.0); }

    /** getvar:max-download-size（每次连接只查一次，减少命令数） */
    private long maxCache = -1;

    public long maxDownloadSize() {
        if (maxCache > 0) { return maxCache; }
        try {
            StringBuilder sb = new StringBuilder();
            command("getvar:max-download-size", sb, true);
            String v = sb.toString().trim();
            int c = v.indexOf(':');
            if (c >= 0) { v = v.substring(c + 1).trim(); }
            if (v.length() == 0) { return 0; }
            long parsed = v.startsWith("0x") || v.startsWith("0X")
                    ? Long.parseLong(v.substring(2), 16) : Long.parseLong(v);
            if (parsed > 0) { maxCache = parsed; }
            return parsed;
        } catch (Exception e) {
            return 0;
        }
    }

    private boolean flashStreamPlain(String partition, java.io.InputStream in, long size, Progress p) {
        say("上传 " + partition + "（" + fmtMb(size) + " MB）…");
        if (!downloadStream(in, size, p)) { say("上传失败"); return false; }
        String r = command("flash:" + partition);
        boolean ok = r.startsWith("OKAY");
        say(ok ? ("刷入成功：" + partition) : ("刷入失败：" + partition + " " + r));
        return ok;
    }

    /** 裸镜像（不是 sparse）的分块：按块边界切，用 DONTCARE 定位绝对偏移 */
    private boolean flashRawImage(String partition, java.io.InputStream in, long size, long maxDownload, Progress p) {
        final int BLK = 4096;
        long totalBlocks = (size + BLK - 1) / BLK;
        // 按 libsparse 的 resparse 规则：每块的文件头里 total_blks 是「整个镜像」的块数，
        // 前面用 DONTCARE 跳过，结尾再补 DONTCARE 到镜像总长。
        // 留 10% 余量：bootloader 上报的 max-download-size 往往比真实缓冲区略大，
        // 贴着上限发会溢出（设备仍回 OKAY，但内容已错）。官方客户端也留了上百 KB 余量。
        long safe = maxDownload - maxDownload / 10;
        long chunkBytes = (safe / BLK) * BLK;
        if (chunkBytes <= 0) { chunkBytes = 64L * 1048576; }
        int chunks = (int) ((size + chunkBytes - 1) / chunkBytes);
        say("裸镜像分 " + chunks + " 块刷 " + partition + "，每块 " + fmtMb(chunkBytes) + " MB");

        long sent = 0;
        for (int idx = 0; idx < chunks; idx++) {
            long off = (long) idx * chunkBytes;
            long len = Math.min(chunkBytes, size - off);
            long blocks = (len + BLK - 1) / BLK;
            // libsparse 写 RAW 块时 total_sz = chunk_hdr_sz + 真实长度，不补齐到整块
            long dataSize = len;
            long pad = 0;
            long skipBefore = off / BLK;
            long skipAfter = totalBlocks - skipBefore - blocks;
            int nChunks = (skipBefore > 0 ? 1 : 0) + 1 + (skipAfter > 0 ? 1 : 0);
            long sparseSize = 28 + 12L * nChunks + dataSize;

            String head = command(String.format(Locale.US, "download:%08x", sparseSize));
            if (!head.startsWith("DATA")) {
                say("第 " + (idx + 1) + "/" + chunks + " 块 download 被拒：" + head);
                return false;
            }
            if (!writeSparseChunkHeader(totalBlocks, nChunks, skipBefore, blocks, dataSize, BLK)
                    || !pipeExactly(in, len, new long[]{sent}, size, p)
                    || (pad > 0 && !writeZeros(pad))
                    || (skipAfter > 0 && !writeSkipChunk(skipAfter))) {
                say("第 " + (idx + 1) + "/" + chunks + " 块上传失败");
                return false;
            }
            sent += len;
            String ok = readResponse();
            if (ok == null || !ok.startsWith("OKAY")) {
                say("第 " + (idx + 1) + "/" + chunks + " 块数据未被确认：" + ok);
                return false;
            }
            String r = command("flash:" + partition);
            if (!r.startsWith("OKAY")) {
                say("第 " + (idx + 1) + "/" + chunks + " 块刷入失败：" + r);
                return false;
            }
            say("  第 " + (idx + 1) + "/" + chunks + " 块完成");
        }
        say("刷入成功：" + partition);
        return true;
    }

    /**
     * 真正给 sparse 镜像用的分块：解析输入 sparse 的块结构，按「包」重新打包，
     * 每包 = 新 sparse 头（total_blks 仍为整镜像）+ 前置 DONTCARE 定位 + 若干原始块 + 结尾 DONTCARE。
     * 这和 libsparse 的 sparse_file_resparse 行为一致，是官方 fastboot 刷大分区的做法。
     */
    private boolean flashSparseImage(String partition, java.io.InputStream in, byte[] head, long limit, Progress p) {
        int blk = (int) rd32(head, 12);
        if (blk <= 0) { blk = 4096; }
        long totalBlocks = rd32(head, 16);
        if (totalBlocks <= 0) { say("sparse 头异常：total_blks=0"); return false; }
        say("sparse 镜像：块 " + blk + " 字节 / " + totalBlocks + " 块，按块边界重新打包");

        long pieceCap = Math.min(limit, 12L * 1048576);
        long bodyLimit = pieceCap - 128;

        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        int chunkCount = 0;
        long pieceStart = -1;
        long curBlock = 0;
        int pieces = 0;
        byte[] tmp = new byte[65536];

        byte[] ch = new byte[12];
        while (true) {
            int got = readFully(in, ch, 0, 12);
            if (got < 12) { break; }
            int type = rd16(ch, 0);
            long blocks = rd32(ch, 4);
            long tsz = rd32(ch, 8);
            long payload = tsz - 12;
            if (payload < 0) { say("sparse 块头异常：total_sz=" + tsz); return false; }

            if (type != 0xCAC1) {
                // DONTCARE / FILL：整块搬
                if (body.size() + 12 + payload > bodyLimit) {
                    if (!flushPiece(partition, body, chunkCount, pieceStart, curBlock, totalBlocks, blk, limit, ++pieces)) return false;
                    body.reset(); chunkCount = 0; pieceStart = -1;
                }
                if (pieceStart < 0) { pieceStart = curBlock; }
                body.write(ch, 0, 12);
                long left = payload;
                while (left > 0) {
                    int k = (int) Math.min(tmp.length, left);
                    if (readFully(in, tmp, 0, k) < k) { say("sparse 数据提前结束"); return false; }
                    body.write(tmp, 0, k);
                    left -= k;
                }
                chunkCount++;
                curBlock += blocks;
                continue;
            }

            // RAW：必要时拆成多个包
            long remain = blocks;
            while (remain > 0) {
                long space = bodyLimit - body.size() - 12;
                long take = (space > 0) ? Math.min(remain, space / blk) : 0;
                if (take <= 0) {
                    if (!flushPiece(partition, body, chunkCount, pieceStart, curBlock, totalBlocks, blk, limit, ++pieces)) return false;
                    body.reset(); chunkCount = 0; pieceStart = -1;
                    continue;
                }
                if (pieceStart < 0) { pieceStart = curBlock; }
                byte[] sh = new byte[12];
                put16(sh, 0, 0xCAC1);
                put32(sh, 4, take);
                put32(sh, 8, 12 + take * blk);
                body.write(sh, 0, 12);
                long left = take * blk;
                while (left > 0) {
                    int k = (int) Math.min(tmp.length, left);
                    if (readFully(in, tmp, 0, k) < k) { say("sparse 数据提前结束"); return false; }
                    body.write(tmp, 0, k);
                    left -= k;
                }
                chunkCount++;
                curBlock += take;
                remain -= take;
            }
        }
        if (chunkCount > 0) {
            if (!flushPiece(partition, body, chunkCount, pieceStart, curBlock, totalBlocks, blk, limit, ++pieces)) return false;
        }
        say("刷入成功：" + partition + "（共 " + pieces + " 包）");
        return true;
    }

    /** 打一个包发出去：sparse 头 + [前置 DONTCARE] + 块 + [结尾 DONTCARE] */
    private boolean flushPiece(String partition, java.io.ByteArrayOutputStream body, int chunkCount,
                               long pieceStart, long curBlock, long totalBlocks, int blk,
                               long limit, int idx) {
        int lead = pieceStart > 0 ? 1 : 0;
        int trail = curBlock < totalBlocks ? 1 : 0;
        int nChunks = chunkCount + lead + trail;
        // 先把整包拼好，再用它的真实长度去声明 download 大小。
        // 之前这里用 28 + 12*nChunks + body.size() 算 size，而 body 里已经含了每块的 12 字节头，
        // 等于把块头算了两遍 → 声明的字节数偏大 → 设备一直等剩下的数据，最后超时。
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(body.size() + 64);
        le32(out, 0xED26FF3AL); le16(out, 1); le16(out, 0); le16(out, 28); le16(out, 12);
        le32(out, blk); le32(out, totalBlocks); le32(out, nChunks); le32(out, 0);
        if (lead > 0) { le16(out, 0xCAC3); le16(out, 0); le32(out, pieceStart); le32(out, 12); }
        try { body.writeTo(out); } catch (java.io.IOException e) { say("打包失败：" + e.getMessage()); return false; }
        if (trail > 0) { le16(out, 0xCAC3); le16(out, 0); le32(out, totalBlocks - curBlock); le32(out, 12); }

        byte[] all = out.toByteArray();
        long size = all.length;
        if (size > limit) { say("第 " + idx + " 包 " + fmtMb(size) + " MB 超过上限"); return false; }

        String dl = command(String.format(Locale.US, "download:%08x", size));
        if (!dl.startsWith("DATA")) { say("第 " + idx + " 包 download 被拒：" + dl); return false; }
        if (!bulkOut(all, all.length)) { say("第 " + idx + " 包上传失败"); return false; }
        String ok = readResponse();
        if (ok == null || !ok.startsWith("OKAY")) { say("第 " + idx + " 包数据未被确认：" + ok); return false; }
        String r = command("flash:" + partition);
        if (!r.startsWith("OKAY")) { say("第 " + idx + " 包刷入失败：" + r); return false; }
        say("  第 " + idx + " 包完成（" + fmtMb(size) + " MB）");
        return true;
    }

    private static int readFully(java.io.InputStream in, byte[] buf, int off, int len) {
        int total = 0;
        try {
            while (total < len) {
                int n = in.read(buf, off + total, len - total);
                if (n < 0) { break; }
                total += n;
            }
        } catch (Exception e) {
            return total;
        }
        return total;
    }

    private static int rd16(byte[] b, int o) { return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8); }

    private static long rd32(byte[] b, int o) {
        return (b[o] & 0xFFL) | ((b[o + 1] & 0xFFL) << 8) | ((b[o + 2] & 0xFFL) << 16) | ((b[o + 3] & 0xFFL) << 24);
    }

    private static void put16(byte[] b, int o, int v) { b[o] = (byte) (v & 0xFF); b[o + 1] = (byte) ((v >> 8) & 0xFF); }

    private static void put32(byte[] b, int o, long v) {
        b[o] = (byte) (v & 0xFF); b[o + 1] = (byte) ((v >> 8) & 0xFF);
        b[o + 2] = (byte) ((v >> 16) & 0xFF); b[o + 3] = (byte) ((v >> 24) & 0xFF);
    }
    /** sparse 头 + 前置 DONTCARE + 一个 RAW 块头 */
    private boolean writeSparseChunkHeader(long totalBlocks, int nChunks, long skipBefore,
                                           long rawBlocks, long dataSize, int blkSize) {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        le32(b, 0xED26FF3AL); le16(b, 1); le16(b, 0); le16(b, 28); le16(b, 12);
        le32(b, blkSize); le32(b, totalBlocks); le32(b, nChunks); le32(b, 0);
        if (skipBefore > 0) { le16(b, 0xCAC3); le16(b, 0); le32(b, skipBefore); le32(b, 12); }
        le16(b, 0xCAC1); le16(b, 0); le32(b, rawBlocks); le32(b, 12 + dataSize);
        return bulkOut(b.toByteArray(), b.size());
    }

    /** 结尾的 DONTCARE 块 */
    private boolean writeSkipChunk(long blocks) {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        le16(b, 0xCAC3); le16(b, 0); le32(b, blocks); le32(b, 12);
        return bulkOut(b.toByteArray(), b.size());
    }

    private boolean writeZeros(long n) {
        byte[] z = new byte[65536];
        while (n > 0) {
            int k = (int) Math.min(z.length, n);
            if (!bulkOut(z, k)) { return false; }
            n -= k;
        }
        return true;
    }

    private static void le16(java.io.ByteArrayOutputStream b, int v) {
        b.write(v & 0xFF); b.write((v >> 8) & 0xFF);
    }
    private static void le32(java.io.ByteArrayOutputStream b, long v) {
        b.write((int) (v & 0xFF)); b.write((int) ((v >> 8) & 0xFF));
        b.write((int) ((v >> 16) & 0xFF)); b.write((int) ((v >> 24) & 0xFF));
    }

    /** 从流里读固定长度直接下发 */
    private boolean pipeExactly(java.io.InputStream in, long len, long[] sent, long total, Progress p) {
        byte[] buf = new byte[65536];
        long left = len;
        while (left > 0) {
            int want = (int) Math.min(buf.length, left);
            int n;
            try {
                n = in.read(buf, 0, want);
            } catch (Exception e) {
                say("读取镜像失败：" + e.getMessage());
                return false;
            }
            if (n <= 0) { say("镜像提前结束（还差 " + left + " 字节）"); return false; }
            if (!bulkOut(buf, n)) { return false; }
            left -= n;
            sent[0] += n;
            if (p != null) { p.onProgress(sent[0], total); }
        }
        return true;
    }

    public interface Progress { void onProgress(long done, long total); }

    /** 取单个 getvar 值：返回设备 INFO 回来的那行内容（以前返回的是 "OKAY"，界面上全是 OKAY） */
    public String getvar(String name) {
        StringBuilder sb = new StringBuilder();
        String r = command("getvar:" + name, sb, true);
        String v = sb.toString().trim();
        if (v.length() > 0) {
            // 设备常回 "product: xxx"，把前缀去掉只留值
            int c = v.indexOf(':');
            if (c > 0) { v = v.substring(c + 1).trim(); }
            return v;
        }
        return r;
    }
    public synchronized boolean erase(String partition) {
        String r = command("erase:" + partition);
        boolean ok = r.startsWith("OKAY");
        say(ok ? ("已擦除：" + partition) : ("擦除失败：" + partition + " " + r));
        return ok;
    }

    public synchronized boolean reboot(String target) {
        return command(target == null || target.length() == 0 ? "reboot" : "reboot-" + target).startsWith("OKAY");
    }

    public synchronized boolean oem(String cmd) { return command("oem " + cmd).startsWith("OKAY"); }

    /** 协议自测：用几种不同的帧格式打同一条命令，看设备对哪种有正确回复 */
    public synchronized String protoTest() {
        StringBuilder out = new StringBuilder();
        out.append("== fastboot 协议自测 ==\n");
        verbose = true;
        try {
            out.append(probeRaw("A 标准 FASTBOOT+cmd /64", "FASTBOOTgetvar:product", 64));
            out.append(probeRaw("B 不带前缀 cmd /64", "getvar:product", 64));
            out.append(probeRaw("C 标准 /512 补齐", "FASTBOOTgetvar:product", 512));
            out.append(probeRaw("D 大写命令 /64", "FASTBOOTGETVAR:product", 64));
        } finally {
            verbose = false;
        }
        return out.toString();
    }

    private String probeRaw(String label, String text, int len) {
        byte[] buf = new byte[len];
        byte[] raw = text.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(raw, 0, buf, 0, Math.min(raw.length, len));
        StringBuilder r = new StringBuilder(label).append(" → ");
        if (!bulkOut(buf, len)) {
            return r.append("写失败\n").toString();
        }
        for (int i = 0; i < 3; i++) {
            String resp = readResponse();
            if (resp == null) { r.append("读超时"); break; }
            r.append('[').append(resp).append(']');
            if (resp.startsWith("OKAY") || resp.startsWith("FAIL")) { break; }
        }
        return r.append('\n').toString();
    }

    /** getvar:all → 解析 partition-size / partition-type */
    public synchronized List<String[]> getPartitions() {
        List<String[]> out = new ArrayList<String[]>();
        if (!isOpen()) { return out; }

        // 走统一的 command 通道，这样自动适配 "FASTBOOT 前缀 / 裸命令" 两种固件
        final StringBuilder raw = new StringBuilder();
        command("getvar:all", raw, true);

        Map<String, String> sizes = new HashMap<String, String>();
        Map<String, String> types = new HashMap<String, String>();
        for (String line : raw.toString().split("\n")) {
            String t = line.trim();
            int i = t.indexOf("partition-size:");
            boolean isSize = i >= 0;
            if (!isSize) { i = t.indexOf("partition-type:"); }
            if (i < 0) { continue; }
            String rest = t.substring(i + (isSize ? "partition-size:".length() : "partition-type:".length()));
            int c = rest.indexOf(':');
            if (c <= 0) { continue; }
            String name = rest.substring(0, c).trim();
            String val = rest.substring(c + 1).trim();
            if (isSize) { sizes.put(name, val); } else { types.put(name, val); }
        }

        List<String> names = new ArrayList<String>();
        for (String k : sizes.keySet()) { if (!names.contains(k)) { names.add(k); } }
        for (String k : types.keySet()) { if (!names.contains(k)) { names.add(k); } }
        java.util.Collections.sort(names);
        for (String n : names) {
            out.add(new String[]{ n, sizes.containsKey(n) ? sizes.get(n) : "", types.containsKey(n) ? types.get(n) : "" });
        }
        return out;
    }
}
