package com.minis.ax3000t;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;

/** Copies the pinned official OpenWrt images from the APK and verifies SHA-256. */
public final class FirmwareAssets {
    public static final String INITRAMFS_ASSET = "firmware/openwrt-25.12.0-ax3000t-initramfs-factory.ubi";
    public static final String SYSUPGRADE_ASSET = "firmware/openwrt-25.12.0-ax3000t-sysupgrade.bin";
    public static final String INITRAMFS_SHA256 = "782d9519598e1d562e1ea150e369ac19c9121ddef20038a0417d272728eb3a89";
    public static final String SYSUPGRADE_SHA256 = "5fb1aa6ca19ee3d2b45cf159089b9a9f2f7bc479bb9ef83a6e0ac5a17d665ef3";
    public static final String RELEASE = "OpenWrt 25.12.0";

    private FirmwareAssets() {
    }

    public static File copyVerified(Context context, String assetPath, String expectedSha256) throws Exception {
        String name = new File(assetPath).getName();
        File directory = new File(context.getFilesDir(), "verified-firmware");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("无法创建固件缓存目录");
        }
        File destination = new File(directory, name);
        if (destination.isFile() && expectedSha256.equalsIgnoreCase(sha256(destination))) {
            return destination;
        }
        File temporary = new File(directory, name + ".part");
        if (temporary.exists() && !temporary.delete()) {
            throw new IOException("无法清理固件临时文件");
        }
        try (InputStream input = context.getAssets().open(assetPath);
             FileOutputStream output = new FileOutputStream(temporary)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            output.flush();
        }
        String actual = sha256(temporary);
        if (!expectedSha256.equalsIgnoreCase(actual)) {
            temporary.delete();
            throw new IOException("内置固件校验失败：" + name);
        }
        if (destination.exists() && !destination.delete()) {
            throw new IOException("无法替换固件缓存");
        }
        if (!temporary.renameTo(destination)) {
            throw new IOException("无法保存固件缓存");
        }
        return destination;
    }

    public static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
        }
        StringBuilder out = new StringBuilder(64);
        for (byte value : digest.digest()) {
            out.append(String.format(Locale.US, "%02x", value & 0xff));
        }
        return out.toString();
    }
}
