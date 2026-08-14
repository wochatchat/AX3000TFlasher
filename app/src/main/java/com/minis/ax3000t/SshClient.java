package com.minis.ax3000t;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/** Small, deliberately synchronous SSH/SCP-like client used by the worker thread. */
public final class SshClient implements Closeable {
    public static final class Result {
        public final int exitCode;
        public final String stdout;
        public final String stderr;

        Result(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }

        public boolean ok() {
            return exitCode == 0;
        }
    }

    private final Session session;

    private SshClient(Session session) {
        this.session = session;
    }

    public static SshClient connectWithFallback(String host, int timeoutMs) throws Exception {
        Exception last = null;
        String[] passwords = {"root", null};
        for (String password : passwords) {
            try {
                return connect(host, password, timeoutMs);
            } catch (Exception e) {
                last = e;
            }
        }
        throw new IOException("SSH 登录失败：请确认路由器已启动 SSH，且网络已切换到当前网段", last);
    }

    public static SshClient connectOpenWrtOrStock(String host, int timeoutMs) throws Exception {
        Exception last = null;
        for (String password : new String[]{null, "root"}) {
            try {
                return connect(host, password, timeoutMs);
            } catch (Exception e) {
                last = e;
            }
        }
        throw new IOException("SSH 服务未响应：" + host, last);
    }

    public static SshClient connect(String host, String password, int timeoutMs) throws Exception {
        JSch jsch = new JSch();
        Session session = jsch.getSession("root", host, 22);
        if (password != null) {
            session.setPassword(password);
        }
        Properties config = new Properties();
        config.put("StrictHostKeyChecking", "no");
        config.put("PreferredAuthentications", "password,none");
        // Stock Xiaomi Dropbear versions may only advertise the legacy RSA host key.
        config.put("server_host_key", "ssh-ed25519,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521,rsa-sha2-512,rsa-sha2-256,ssh-rsa");
        session.setConfig(config);
        session.connect(timeoutMs);
        return new SshClient(session);
    }

    public Result exec(String command, long timeoutMs) throws Exception {
        ChannelExec channel = (ChannelExec) session.openChannel("exec");
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        channel.setCommand(command);
        channel.setErrStream(error);
        InputStream input = channel.getInputStream();
        channel.connect(8000);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        readUntilClosed(channel, input, output, timeoutMs);
        int code = channel.getExitStatus();
        channel.disconnect();
        return new Result(code, output.toString(StandardCharsets.UTF_8.name()), error.toString(StandardCharsets.UTF_8.name()));
    }

    public void upload(File local, String remote, long timeoutMs) throws Exception {
        if (!local.isFile()) {
            throw new IOException("本地文件不存在：" + local);
        }
        // The stock image does not always ship an SFTP subsystem.  A plain stdin
        // stream into cat works with the built-in Dropbear exec server instead.
        String safeRemote = remote.replace("'", "");
        ChannelExec channel = (ChannelExec) session.openChannel("exec");
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        channel.setCommand("cat > '" + safeRemote + "'");
        channel.setErrStream(error);
        OutputStream output = channel.getOutputStream();
        InputStream input = channel.getInputStream();
        channel.connect(8000);
        byte[] buffer = new byte[64 * 1024];
        try (FileInputStream file = new FileInputStream(local)) {
            int count;
            while ((count = file.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            output.flush();
        }
        output.close();
        drainUntilClosed(channel, input, timeoutMs);
        int code = channel.getExitStatus();
        channel.disconnect();
        if (code != 0) {
            throw new IOException("上传失败：" + error.toString(StandardCharsets.UTF_8.name()));
        }
    }

    public long streamCommandToFile(String command, File destination, long timeoutMs) throws Exception {
        if (destination.getParentFile() != null && !destination.getParentFile().exists()
                && !destination.getParentFile().mkdirs()) {
            throw new IOException("无法创建备份目录");
        }
        ChannelExec channel = (ChannelExec) session.openChannel("exec");
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        channel.setCommand(command);
        channel.setErrStream(error);
        InputStream input = channel.getInputStream();
        channel.connect(8000);
        long bytes = 0;
        try (FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[64 * 1024];
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            while (true) {
                while (input.available() > 0) {
                    int count = input.read(buffer);
                    if (count < 0) {
                        break;
                    }
                    output.write(buffer, 0, count);
                    bytes += count;
                    deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
                }
                if (channel.isClosed()) {
                    while (input.available() > 0) {
                        int count = input.read(buffer);
                        if (count <= 0) break;
                        output.write(buffer, 0, count);
                        bytes += count;
                    }
                    break;
                }
                if (System.nanoTime() > deadline) {
                    channel.disconnect();
                    throw new IOException("远程备份超时");
                }
                Thread.sleep(25);
            }
        }
        int code = channel.getExitStatus();
        channel.disconnect();
        if (code != 0) {
            throw new IOException("远程备份失败：" + error.toString(StandardCharsets.UTF_8.name()));
        }
        return bytes;
    }

    private static void readUntilClosed(ChannelExec channel, InputStream input, ByteArrayOutputStream output,
                                        long timeoutMs) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        byte[] buffer = new byte[16 * 1024];
        while (true) {
            while (input.available() > 0) {
                int count = input.read(buffer);
                if (count <= 0) break;
                output.write(buffer, 0, count);
                deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            }
            if (channel.isClosed()) {
                while (input.available() > 0) {
                    int count = input.read(buffer);
                    if (count <= 0) break;
                    output.write(buffer, 0, count);
                }
                return;
            }
            if (System.nanoTime() > deadline) {
                channel.disconnect();
                throw new IOException("SSH 命令超时");
            }
            Thread.sleep(25);
        }
    }

    private static void drainUntilClosed(ChannelExec channel, InputStream input, long timeoutMs) throws Exception {
        ByteArrayOutputStream ignored = new ByteArrayOutputStream();
        readUntilClosed(channel, input, ignored, timeoutMs);
    }

    @Override
    public void close() {
        if (session != null && session.isConnected()) {
            session.disconnect();
        }
    }
}
