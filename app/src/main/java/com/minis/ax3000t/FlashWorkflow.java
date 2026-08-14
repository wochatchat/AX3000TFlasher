package com.minis.ax3000t;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Staged AX3000T installation. It intentionally never writes BL2/FIP/U-Boot:
 * only the inactive OEM UBI slot is used for the initial boot, which leaves the
 * original bootloader and the currently running stock slot available.
 */
public final class FlashWorkflow {
    public interface Listener {
        void log(String message);
        void progress(int percent, String stage);
        void finished(String message);
        void failed(String message, Throwable cause);
    }

    private final Context context;
    private final Listener listener;
    private volatile boolean cancelled;

    public FlashWorkflow(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public void cancel() {
        cancelled = true;
    }

    public void install(String host, String webPassword) {
        SshClient stockSsh = null;
        try {
            requireNotCancelled();
            NetworkUtils.Status network = NetworkUtils.bindBestNetwork(context);
            if (!network.ethernet) {
                throw new IOException("未检测到 USB-C 有线网卡。刷机重启后原厂 Wi-Fi 会消失，必须使用有线网卡连接路由器 LAN 中间口");
            }
            if (network.address.isEmpty()) {
                throw new IOException("有线网卡尚未获得 IP，请确认路由器已开机并连接 LAN 口");
            }
            log("网络：" + network.description);

            progress(5, "识别原厂路由器");
            RouterApi api = new RouterApi(host);
            RouterApi.DeviceInfo device = api.detectAndLogin(webPassword);
            log("已识别：" + device.hardware + "，原厂固件 " + device.firmware);
            if (device.likelyNewUnsupportedRevision) {
                throw new IOException("RD03v2 不支持本流程");
            }

            progress(12, "启用临时 SSH");
            String localIp = NetworkUtils.findAddress(true).address;
            if (!api.unlockSsh(listener::log, context, localIp)) {
                throw new IOException("没有找到当前固件可用的 SSH 解锁接口，未写入任何闪存");
            }
            requireNotCancelled();
            Thread.sleep(1200);

            progress(18, "连接原厂 SSH");
            stockSsh = connectStock(host);
            SshClient.Result mtdResult = stockSsh.exec("cat /proc/mtd", 10000);
            SshClient.Result cmdlineResult = stockSsh.exec("cat /proc/cmdline", 10000);
            if (!mtdResult.ok() || !cmdlineResult.ok()) {
                throw new IOException("无法读取原厂分区信息，已停止");
            }
            requireAx3000t(stockSsh, device);
            String slot = parseFirmwareSlot(cmdlineResult.stdout);
            if (slot.equals("unknown")) {
                throw new IOException("无法确定当前原厂启动槽位，已停止以避免刷错分区");
            }
            log("当前原厂启动槽位：" + slot + "；将只写入另一槽位");

            progress(25, "自动备份官方分区");
            RouterBackup.Result backup = RouterBackup.create(context, stockSsh, listener::log);
            // Do not allow the flash to proceed if the user-visible export failed.
            RouterBackup.exportArchive(context, backup.archive);
            log("备份已自动导出到 Download/AX3000T-Backups：" + backup.archive.getName());
            saveLastBackup(backup.directory);

            progress(38, "校验内置 OpenWrt 固件");
            File initramfs = FirmwareAssets.copyVerified(
                    context, FirmwareAssets.INITRAMFS_ASSET, FirmwareAssets.INITRAMFS_SHA256);
            File sysupgrade = FirmwareAssets.copyVerified(
                    context, FirmwareAssets.SYSUPGRADE_ASSET, FirmwareAssets.SYSUPGRADE_SHA256);
            log(FirmwareAssets.RELEASE + " 两个镜像 SHA-256 校验通过");

            MtdPartition target = targetSlot(parseMtd(mtdResult.stdout), slot);
            if (target == null) {
                throw new IOException("没有安全识别到非活动 UBI 槽位，未开始写入");
            }
            if (target.size < initramfs.length() * 2L) {
                throw new IOException("目标分区尺寸异常（" + target + "），拒绝写入");
            }

            progress(45, "上传临时固件");
            String remoteInitramfs = "/tmp/ax3000t-initramfs-factory.ubi";
            stockSsh.upload(initramfs, remoteInitramfs, 120000);
            verifyRemoteHash(stockSsh, remoteInitramfs, FirmwareAssets.INITRAMFS_SHA256);
            requireNotCancelled();

            progress(55, "写入非活动启动槽位");
            String flash = "set -e; "
                    + "ubidetach -m " + target.index + " >/dev/null 2>&1 || true; "
                    + "ubiformat /dev/mtd" + target.index + " -y -f " + remoteInitramfs + "; "
                    + "nvram set boot_wait=on; nvram set uart_en=1; "
                    + "nvram set flag_boot_rootfs=" + (slot.equals("0") ? "1" : "0") + "; "
                    + "nvram set flag_last_success=" + (slot.equals("0") ? "1" : "0") + "; "
                    + "nvram set flag_boot_success=1; nvram set flag_try_sys1_failed=0; "
                    + "nvram set flag_try_sys2_failed=0; nvram commit; sync";
            SshClient.Result flashResult = stockSsh.exec("sh -c '" + flash + "'", 240000);
            if (!flashResult.ok()) {
                throw new IOException("临时固件写入失败：" + flashResult.stderr);
            }
            log("非活动槽位写入完成，原厂当前槽位仍保留");
            safeReboot(stockSsh);
            stockSsh.close();
            stockSsh = null;

            progress(65, "等待临时 OpenWrt 启动");
            SshClient initramfsSsh = waitForOpenWrt(100000);
            try {
                requireOpenWrtBoard(initramfsSsh);
                log("临时 OpenWrt 已启动，原厂 bootloader 未改动");
                progress(73, "上传正式系统镜像");
                String remoteSysupgrade = "/tmp/ax3000t-sysupgrade.bin";
                initramfsSsh.upload(sysupgrade, remoteSysupgrade, 120000);
                verifyRemoteHash(initramfsSsh, remoteSysupgrade, FirmwareAssets.SYSUPGRADE_SHA256);
                requireNotCancelled();

                progress(82, "安装正式 OpenWrt");
                SshClient.Result upgrade = initramfsSsh.exec(
                        "sysupgrade -n '" + remoteSysupgrade + "'", 30000);
                // sysupgrade normally closes/replaces SSH; an exit status of -1
                // after the command was accepted is expected, so verify by reboot.
                if (upgrade.exitCode != 0 && upgrade.exitCode != -1
                        && !upgrade.stdout.toLowerCase(Locale.US).contains("reboot")) {
                    throw new IOException("sysupgrade 未接受镜像：" + upgrade.stderr + upgrade.stdout);
                }
            } finally {
                initramfsSsh.close();
            }

            progress(90, "等待正式 OpenWrt 启动");
            SshClient finalSsh = waitForOpenWrt(140000);
            try {
                requireOpenWrtBoard(finalSsh);
                SshClient.Result release = finalSsh.exec("cat /etc/openwrt_release", 10000);
                if (!release.ok() || !release.stdout.contains("25.12.0")) {
                    throw new IOException("正式系统版本校验失败：" + release.stdout);
                }
                finalSsh.exec("rm -f /tmp/ax3000t-initramfs-factory.ubi /tmp/ax3000t-sysupgrade.bin", 10000);
            } finally {
                finalSsh.close();
            }
            progress(100, "刷机完成");
            listener.finished("OpenWrt 已安装。请访问 http://192.168.1.1 完成首次设置；官方备份已保存在 Download/AX3000T-Backups");
        } catch (Throwable error) {
            if (stockSsh != null) stockSsh.close();
            listener.failed(error.getMessage() == null ? "流程失败，未能确定设备状态" : error.getMessage(), error);
        }
    }

    /**
     * Conservative rollback: in initramfs only boot flags are changed; on a
     * permanent stock-layout OpenWrt install, matching OEM UBI partitions are
     * restored only when their labels and sizes are unambiguous.
     */
    public void rollbackLatest(String hostHint) {
        SshClient ssh = null;
        try {
            requireNotCancelled();
            NetworkUtils.Status network = NetworkUtils.bindBestNetwork(context);
            if (!network.ethernet) throw new IOException("回退也需要 USB-C 有线网卡");
            File directory = latestBackupDirectory();
            if (directory == null) throw new IOException("找不到自动备份，请不要盲目恢复其他路由器的备份");
            JSONObject metadata = new JSONObject(readText(new File(directory, "manifest.json")));
            String originalSlot = metadata.optString("firmwareSlot", "unknown");
            if (!originalSlot.equals("0") && !originalSlot.equals("1")) {
                throw new IOException("备份没有记录原厂槽位，无法安全回退");
            }

            progress(10, "连接设备并识别系统");
            String[] hosts = uniqueHosts(hostHint, "192.168.1.1", "192.168.31.1");
            Exception last = null;
            for (String host : hosts) {
                try {
                    ssh = SshClient.connectOpenWrtOrStock(host, 12000);
                    break;
                } catch (Exception e) {
                    last = e;
                }
            }
            if (ssh == null) throw new IOException("无法连接设备进行回退", last);
            requireAx3000t(ssh, null);
            SshClient.Result board = ssh.exec("cat /tmp/sysinfo/board_name 2>/dev/null || true", 8000);
            if (board.stdout.contains("ubootmod")) {
                throw new IOException("检测到 OpenWrt U-Boot layout；本应用没有改写 U-Boot，不能用错误布局强行恢复");
            }
            SshClient.Result root = ssh.exec("rootfs_type 2>/dev/null || mount | head -1", 8000);
            boolean initramfs = root.stdout.contains("tmpfs") || board.stdout.trim().isEmpty();
            if (initramfs) {
                progress(50, "恢复原厂启动槽位");
                setBootSlot(ssh, originalSlot);
                safeReboot(ssh);
                ssh.close();
                progress(100, "已回退到未被改写的原厂槽位");
                listener.finished("回退命令已发送。请等待路由器重启 3 分钟；不要断电");
                return;
            }

            progress(25, "校验原厂 UBI 备份与当前布局");
            String currentSlot = readBootSlot(ssh);
            String overwrittenSlot = originalSlot.equals("0") ? "1" : "0";
            if (currentSlot.equals(originalSlot)) {
                progress(100, "当前已经是原厂槽位");
                listener.finished("设备当前已经从原厂槽位启动，没有继续写 NAND");
                return;
            }
            if (!currentSlot.equals(overwrittenSlot)) {
                throw new IOException("无法确认当前 OpenWrt 槽位，已停止以避免写入活动分区");
            }

            // First boot the untouched OEM slot. Only after that do we restore
            // the slot that OpenWrt occupied, so ubiformat never touches the
            // root filesystem currently running the rollback command.
            setBootSlot(ssh, originalSlot);
            safeReboot(ssh);
            ssh.close();
            ssh = null;
            progress(42, "等待原厂槽位启动");
            ssh = waitForStock(hostHint, 120000);
            requireAx3000t(ssh, null);

            List<MtdPartition> current = parseMtd(ssh.exec("cat /proc/mtd", 10000).stdout);
            int targetIndex = overwrittenSlot.equals("0") ? 8 : 9;
            String backupAlias = overwrittenSlot.equals("0") ? "ubi" : "ubi1";
            File backupFile = findBackupFile(directory, backupAlias + ".bin");
            MtdPartition target = findPartitionByIndex(current, targetIndex);
            long expectedSize = manifestExpectedSize(metadata, backupAlias);
            if (backupFile == null || target == null || expectedSize <= 0
                    || backupFile.length() < expectedSize * 0.80
                    || target.size < expectedSize) {
                throw new IOException("当前 mtd" + targetIndex + " 与本机 " + backupAlias
                        + " 备份尺寸不匹配，已停止（避免写错 NAND）");
            }
            log("按 OpenWrt 官方 AX3000T 回退步骤恢复 mtd" + targetIndex + "/" + backupAlias
                    + "；不改写 BL2/FIP");
            String remote = "/tmp/ax3000t-stock-" + backupAlias + ".bin";
            ssh.upload(backupFile, remote, 180000);
            verifyRemoteHash(ssh, remote, FirmwareAssets.sha256(backupFile));
            SshClient.Result restored = ssh.exec(
                    "set -e; ubidetach -m " + targetIndex + " >/dev/null 2>&1 || true; "
                            + "ubiformat /dev/mtd" + targetIndex + " -y -f " + remote + "; sync", 300000);
            if (!restored.ok()) throw new IOException("恢复 mtd" + targetIndex + " 失败：" + restored.stderr);
            setBootSlot(ssh, originalSlot);
            progress(88, "重启并返回原厂");
            safeReboot(ssh);
            ssh.close();
            progress(100, "回退命令已完成");
            listener.finished("原厂两个系统槽位已恢复，等待路由器重启 3 分钟；不要断电");
        } catch (Throwable error) {
            if (ssh != null) ssh.close();
            listener.failed(error.getMessage() == null ? "回退失败，请保留备份并使用 UART/TFTP 恢复" : error.getMessage(), error);
        }
    }

    private SshClient connectStock(String host) throws Exception {
        return SshClient.connectWithFallback(host, 15000);
    }

    private SshClient waitForOpenWrt(long timeoutMs) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        Exception last = null;
        while (System.nanoTime() < deadline) {
            requireNotCancelled();
            NetworkUtils.bindBestNetwork(context);
            try {
                return SshClient.connectOpenWrtOrStock("192.168.1.1", 7000);
            } catch (Exception e) {
                last = e;
            }
            Thread.sleep(2000);
        }
        throw new IOException("等待 192.168.1.1 超时。请确认网线仍插在 LAN 中间口且路由器没有断电", last);
    }

    private SshClient waitForStock(String hostHint, long timeoutMs) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        Exception last = null;
        while (System.nanoTime() < deadline) {
            requireNotCancelled();
            NetworkUtils.bindBestNetwork(context);
            for (String host : uniqueHosts(hostHint, "192.168.31.1", "192.168.1.1")) {
                try {
                    return SshClient.connectWithFallback(host, 7000);
                } catch (Exception e) {
                    last = e;
                }
            }
            Thread.sleep(2000);
        }
        throw new IOException("等待原厂系统启动超时，请确认网线仍插在 LAN 中间口且路由器没有断电", last);
    }

    private static String readBootSlot(SshClient ssh) throws Exception {
        SshClient.Result result = ssh.exec(
                "if command -v fw_printenv >/dev/null 2>&1; then fw_printenv flag_boot_rootfs; "
                        + "elif command -v nvram >/dev/null 2>&1; then nvram get flag_boot_rootfs; fi", 10000);
        String output = result.stdout == null ? "" : result.stdout;
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?:flag_boot_rootfs=)?([01])(?:\\r?\\n|\\s|$)")
                .matcher(output);
        return matcher.find() ? matcher.group(1) : "unknown";
    }

    private static void requireOpenWrtBoard(SshClient ssh) throws Exception {
        SshClient.Result board = ssh.exec("cat /tmp/sysinfo/board_name 2>/dev/null || true", 10000);
        String value = board.stdout.trim();
        if (!value.contains("xiaomi") || !value.contains("ax3000t")) {
            throw new IOException("OpenWrt 设备型号校验失败：" + value);
        }
    }

    private static void requireAx3000t(SshClient ssh, RouterApi.DeviceInfo device) throws Exception {
        SshClient.Result identity = ssh.exec(
                "cat /proc/device-tree/model /tmp/sysinfo/board_name 2>/dev/null; "
                        + "grep -hiE 'MT7981|AX3000T|xiaomi' /proc/cpuinfo 2>/dev/null || true", 10000);
        String value = identity.stdout.toLowerCase(Locale.US);
        if (!value.contains("mt7981") && !value.contains("ax3000t") && !value.contains("xiaomi")) {
            if (device == null) throw new IOException("SSH 设备不是 MT7981/AX3000T，已停止");
        }
    }

    private static void verifyRemoteHash(SshClient ssh, String remote, String expected) throws Exception {
        SshClient.Result hash = ssh.exec("sha256sum '" + remote + "'", 30000);
        if (!hash.ok() || !hash.stdout.toLowerCase(Locale.US).contains(expected.toLowerCase(Locale.US))) {
            throw new IOException("远程固件校验失败：" + hash.stdout + hash.stderr);
        }
    }

    private static void safeReboot(SshClient ssh) {
        try {
            ssh.exec("sync; reboot", 8000);
        } catch (Exception ignored) {
            // A successful reboot normally tears down the SSH channel.
        }
    }

    private static void setBootSlot(SshClient ssh, String slot) throws Exception {
        String command = "if command -v fw_setenv >/dev/null 2>&1; then "
                + "fw_setenv boot_wait on; fw_setenv uart_en 1; fw_setenv flag_boot_rootfs " + slot + "; "
                + "fw_setenv flag_last_success 1; fw_setenv flag_boot_success 1; "
                + "fw_setenv flag_try_sys1_failed 0; fw_setenv flag_try_sys2_failed 0; "
                + "elif command -v nvram >/dev/null 2>&1; then "
                + "nvram set boot_wait=on; nvram set uart_en=1; nvram set flag_boot_rootfs=" + slot + "; "
                + "nvram set flag_last_success=1; nvram set flag_boot_success=1; "
                + "nvram set flag_try_sys1_failed=0; nvram set flag_try_sys2_failed=0; nvram commit; "
                + "else exit 3; fi; sync";
        SshClient.Result result = ssh.exec("sh -c '" + command + "'", 30000);
        if (!result.ok()) throw new IOException("设置原厂启动槽位失败：" + result.stderr);
    }

    private static MtdPartition targetSlot(List<MtdPartition> partitions, String currentSlot) {
        // The official AX3000T instructions define the inactive targets by
        // numeric mtd index: firmware=0 -> mtd9, firmware=1 -> mtd8.
        int targetIndex = currentSlot.equals("0") ? 9 : 8;
        return findPartitionByIndex(partitions, targetIndex);
    }

    private static MtdPartition findPartition(List<MtdPartition> partitions, String label) {
        for (MtdPartition partition : partitions) {
            if (partition.label.equalsIgnoreCase(label)) return partition;
        }
        return null;
    }

    private static MtdPartition findPartitionByIndex(List<MtdPartition> partitions, int index) {
        for (MtdPartition partition : partitions) {
            if (partition.index == index) return partition;
        }
        return null;
    }

    private static long manifestExpectedSize(JSONObject metadata, String label) throws Exception {
        org.json.JSONArray partitions = metadata.optJSONArray("partitions");
        if (partitions == null) return -1;
        for (int i = 0; i < partitions.length(); i++) {
            JSONObject item = partitions.optJSONObject(i);
            if (item != null && label.equalsIgnoreCase(item.optString("label", ""))) {
                return item.optLong("expectedSize", -1);
            }
        }
        return -1;
    }

    private static List<MtdPartition> parseMtd(String text) {
        List<MtdPartition> result = new ArrayList<>();
        for (String line : (text == null ? "" : text).split("\\r?\\n")) {
            MtdPartition partition = MtdPartition.parseLine(line);
            if (partition != null && !partition.isAggregate()) result.add(partition);
        }
        return result;
    }

    private static String parseFirmwareSlot(String cmdline) {
        String text = cmdline == null ? "" : cmdline;
        if (text.matches("(?s).*firmware=0([\\s\\u0000].*)?")) return "0";
        if (text.matches("(?s).*firmware=1([\\s\\u0000].*)?")) return "1";
        return "unknown";
    }

    private File latestBackupDirectory() {
        String saved = context.getSharedPreferences("flash", Context.MODE_PRIVATE)
                .getString("lastBackupDir", "");
        File preferred = saved.isEmpty() ? null : new File(saved);
        if (preferred != null && new File(preferred, "manifest.json").isFile()) return preferred;
        File root = new File(context.getFilesDir(), "backups");
        File[] directories = root.listFiles(File::isDirectory);
        if (directories == null || directories.length == 0) return null;
        File latest = null;
        for (File directory : directories) {
            if (!new File(directory, "manifest.json").isFile()) continue;
            if (latest == null || directory.lastModified() > latest.lastModified()) latest = directory;
        }
        return latest;
    }

    private void saveLastBackup(File directory) {
        context.getSharedPreferences("flash", Context.MODE_PRIVATE).edit()
                .putString("lastBackupDir", directory.getAbsolutePath()).apply();
    }

    private static File findBackupFile(File directory, String name) {
        File exact = new File(directory, name);
        if (exact.isFile()) return exact;
        File[] files = directory.listFiles();
        if (files == null) return null;
        for (File file : files) if (file.getName().equalsIgnoreCase(name)) return file;
        return null;
    }

    private static String readText(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String[] uniqueHosts(String first, String second, String third) {
        List<String> values = new ArrayList<>();
        for (String value : new String[]{first, second, third}) {
            if (value != null && !value.trim().isEmpty() && !values.contains(value.trim())) values.add(value.trim());
        }
        return values.toArray(new String[0]);
    }

    private void requireNotCancelled() throws IOException {
        if (cancelled) throw new IOException("用户取消了流程");
    }

    private void log(String message) {
        listener.log(message);
    }

    private void progress(int percent, String stage) {
        listener.progress(percent, stage);
        listener.log(stage);
    }
}
