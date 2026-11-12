package com.easypt.gui;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** 遥控 PC 模式：和电脑上的 Easy-platform-tools-GUI 内置服务通信 */
public class RemoteClient {

    private final String base;
    private final String token;

    public RemoteClient(String host, String token) {
        String h = host == null ? "" : host.trim();
        if (h.length() > 0 && !h.startsWith("http")) { h = "http://" + h; }
        if (h.endsWith("/")) { h = h.substring(0, h.length() - 1); }
        this.base = h;
        this.token = token == null ? "" : token.trim();
    }

    private String request(String method, String path, byte[] body, int timeoutMs) throws Exception {
        URL u = new URL(base + path);
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        c.setRequestProperty("X-Token", token);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/octet-stream");
            c.setFixedLengthStreamingMode(body.length);
            OutputStream os = c.getOutputStream();
            os.write(body);
            os.flush();
            os.close();
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        if (in != null) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) { bos.write(buf, 0, n); }
            in.close();
        }
        String text = new String(bos.toByteArray(), StandardCharsets.UTF_8);
        if (code >= 400) { throw new Exception("HTTP " + code + " " + text); }
        return text;
    }

    public String ping() throws Exception { return request("GET", "/api/status", null, 4000); }

    public String run(String cmd) throws Exception {
        return request("POST", "/api/run", cmd.getBytes(StandardCharsets.UTF_8), 60000);
    }

    public String partitions() throws Exception { return request("GET", "/api/partitions", null, 15000); }

    public String flash(String part, byte[] image) throws Exception {
        return request("POST", "/api/flash?partition=" + part, image, 300000);
    }

    /** 从文件流式上传，避免把 GB 级镜像读进内存 */
    public String flashFile(String part, java.io.File file) throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(base + "/api/flash?partition=" + part).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(15000);
        c.setReadTimeout(900000);
        c.setRequestProperty("X-Token", token);
        c.setRequestProperty("Content-Type", "application/octet-stream");
        c.setFixedLengthStreamingMode((int) file.length());
        c.setDoOutput(true);
        OutputStream os = c.getOutputStream();
        java.io.FileInputStream in = new java.io.FileInputStream(file);
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) { os.write(buf, 0, n); }
        in.close();
        os.close();
        int code = c.getResponseCode();
        InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        if (is != null) {
            byte[] b = new byte[8192];
            int k;
            while ((k = is.read(b)) > 0) { bos.write(b, 0, k); }
            is.close();
        }
        String text = new String(bos.toByteArray(), StandardCharsets.UTF_8);
        if (code >= 400) { throw new Exception("HTTP " + code + " " + text); }
        return text;
    }

    public String reboot(String target) throws Exception {
        return request("POST", "/api/reboot?target=" + (target == null ? "" : target), null, 15000);
    }
}
