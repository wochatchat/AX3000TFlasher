package com.minis.ax3000t;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Creates an auditable, portable OEM backup before any NAND write. */
public final class RouterBackup {
    public interface Log {
        void log(String message);
    }

    public static final class Result {
        public final File directory;
        public final File archive;
        public final List<MtdPartition> partitions;
        public final String firmwareSlot;

        Result(File directory, File archive, List<MtdPartition> partitions, String firmwareSlot) {
            this.directory = directory;
            this.archive = archive;
            this.partitions = partitions;
            this.firmwareSlot = firmwareSlot;
        }
    }

    private static final String[] CRITICAL = {"BL2", "Nvram", "Bdata", "Factory", "FIP", "KF"};

    private static final class BackupSpec {
        final String alias;
        final MtdPartition partition;

        BackupSpec(String alias, MtdPartition partition) {
            this.alias = alias;
            this.partition = partition;
        }
    }

    private RouterBackup() {
    }

    public static Result create(Context context, SshClient ssh, Log log) throws Exception {
        File root = new File(context.getFilesDir(), "backups");
        if (!root.exists() && !root.mkdirs()) throw new IOException("无法创建备份目录");
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        File directory = new File(root, "AX3000T-stock-" + stamp);
        if (!directory.mkdirs()) throw new IOException("无法创建本次备份目录");

        SshClient.Result mtdResult = ssh.exec("cat /proc/mtd", 10000);
        if (!mtdResult.ok()) throw new IOException("读取 /proc/mtd 失败：" + mtdResult.stderr);
        List<MtdPartition> all = parse(mtdResult.stdout);
        Map<String, MtdPartition> byLabel = new LinkedHashMap<>();
        for (MtdPartition partition : all) {
            if (!partition.isAggregate()) byLabel.put(partition.label, partition);
        }
        List<BackupSpec> selected = new ArrayList<>();
        for (String label : CRITICAL) {
            MtdPartition partition = find(byLabel, label);
            if (partition != null) selected.add(new BackupSpec(label, partition));
        }
        // The stock image commonly exposes the two system slots as unlabeled
        // mtd8/mtd9. The official AX3000T recovery procedure identifies them by
        // those numbers, so preserve both raw NAND images under stable aliases.
        MtdPartition slot0 = findByIndex(all, 8);
        MtdPartition slot1 = findByIndex(all, 9);
        if (slot0 == null || slot1 == null || slot0.size < 16L * 1024 * 1024 || slot1.size < 16L * 1024 * 1024) {
            throw new IOException("没有发现尺寸正常的原厂 mtd8/mtd9 系统槽位，已停止以避免错误备份");
        }
        selected.add(new BackupSpec("ubi", slot0));
        selected.add(new BackupSpec("ubi1", slot1));

        // At least the identity/calibration and boot partitions must be present.
        requireLabels(byLabel, "BL2", "Nvram", "Bdata", "Factory", "FIP");

        String cmdline = ssh.exec("cat /proc/cmdline", 10000).stdout;
        String slot = cmdline.contains("firmware=1") ? "1" : cmdline.contains("firmware=0") ? "0" : "unknown";
        writeText(new File(directory, "proc-mtd.txt"), mtdResult.stdout);
        writeText(new File(directory, "proc-cmdline.txt"), cmdline);
        log.log("已读取分区表，当前 OEM 槽位：" + slot);

        JSONArray manifest = new JSONArray();
        for (BackupSpec spec : selected) {
            MtdPartition partition = spec.partition;
            File local = new File(directory, spec.alias + ".bin");
            log.log("备份 " + spec.alias + " / mtd" + partition.index + "（" + humanBytes(partition.size) + "）");
            long bytes = ssh.streamCommandToFile(
                    "nanddump -p -f - /dev/mtd" + partition.index,
                    local,
                    180000);
            if (bytes < partition.size * 0.80) {
                throw new IOException("分区 " + partition.label + " 备份大小异常：" + bytes + "/" + partition.size);
            }
            JSONObject item = new JSONObject();
            item.put("label", spec.alias);
            item.put("sourceLabel", partition.label);
            item.put("mtd", partition.index);
            item.put("expectedSize", partition.size);
            item.put("actualSize", local.length());
            item.put("sha256", FirmwareAssets.sha256(local));
            manifest.put(item);
        }
        JSONObject metadata = new JSONObject();
        metadata.put("format", "AX3000T OEM backup v1");
        metadata.put("createdAt", new Date().toString());
        metadata.put("firmwareSlot", slot);
        metadata.put("warning", "仅恢复到制作此备份的同一台路由器；Factory 含本机 MAC/校准数据");
        metadata.put("partitions", manifest);
        writeText(new File(directory, "manifest.json"), metadata.toString(2));

        File archive = new File(root, directory.getName() + ".zip");
        zipDirectory(directory, archive);
        if (archive.length() < 1024) throw new IOException("备份归档生成失败");
        List<MtdPartition> selectedPartitions = new ArrayList<>();
        for (BackupSpec spec : selected) selectedPartitions.add(spec.partition);
        log.log("官方分区备份完成：" + humanBytes(archive.length()));
        return new Result(directory, archive, selectedPartitions, slot);
    }

    private static List<MtdPartition> parse(String text) {
        List<MtdPartition> result = new ArrayList<>();
        String[] lines = (text == null ? "" : text).split("\\r?\\n");
        for (String line : lines) {
            MtdPartition partition = MtdPartition.parseLine(line);
            if (partition != null) result.add(partition);
        }
        return result;
    }

    private static MtdPartition findByIndex(List<MtdPartition> partitions, int index) {
        for (MtdPartition partition : partitions) {
            if (partition.index == index) return partition;
        }
        return null;
    }

    private static MtdPartition find(Map<String, MtdPartition> map, String label) {
        MtdPartition exact = map.get(label);
        if (exact != null) return exact;
        for (Map.Entry<String, MtdPartition> entry : map.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(label)) return entry.getValue();
        }
        return null;
    }

    private static void requireLabels(Map<String, MtdPartition> map, String... labels) throws IOException {
        List<String> missing = new ArrayList<>();
        for (String label : labels) if (find(map, label) == null) missing.add(label);
        if (!missing.isEmpty()) throw new IOException("缺少关键分区：" + missing);
    }

    private static void writeText(File file, String text) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(file), StandardCharsets.UTF_8))) {
            writer.write(text == null ? "" : text);
        }
    }

    private static void zipDirectory(File directory, File archive) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(archive))) {
            File[] files = directory.listFiles();
            if (files == null) throw new IOException("备份目录为空");
            byte[] buffer = new byte[64 * 1024];
            for (File file : files) {
                if (!file.isFile()) continue;
                zip.putNextEntry(new ZipEntry(file.getName()));
                try (FileInputStream input = new FileInputStream(file)) {
                    int count;
                    while ((count = input.read(buffer)) != -1) zip.write(buffer, 0, count);
                }
                zip.closeEntry();
            }
        }
    }

    public static Uri exportArchive(Context context, File archive) throws IOException {
        android.content.ContentValues values = new android.content.ContentValues();
        values.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, archive.getName());
        values.put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/zip");
        values.put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/AX3000T-Backups");
        Uri uri = context.getContentResolver().insert(
                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("无法创建 Downloads 文件");
        try (OutputStream output = context.getContentResolver().openOutputStream(uri);
             FileInputStream input = new FileInputStream(archive)) {
            if (output == null) throw new IOException("无法打开 Downloads 文件");
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        } catch (Exception e) {
            context.getContentResolver().delete(uri, null, null);
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException(e);
        }
        return uri;
    }

    public static String humanBytes(long value) {
        if (value >= 1024L * 1024L) return String.format(Locale.US, "%.1f MB", value / 1048576.0);
        if (value >= 1024L) return String.format(Locale.US, "%.1f KB", value / 1024.0);
        return value + " B";
    }
}
