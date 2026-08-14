# AX3000T 刷机助手

原生 Android 应用，用于 Xiaomi AX3000T **RD03/RD23（MediaTek MT7981B）** 安装 OpenWrt 25.12.0。

> **不支持 RD03v2。** RD03v2 是 Qualcomm 硬件，应用会在识别阶段阻止操作。

## 重要限制

- 没有电脑仍需要 **USB-C 转千兆网卡 + 网线**。刷机期间网线必须插在路由器 LAN 中间口；手机 Wi-Fi 不能作为可靠的重启后连接方式。
- 应用不写 BL2/FIP/U-Boot，只写原厂非活动系统槽位；因此保留原厂 bootloader 和当前槽位作为回退路径。
- 任何软件都不能抵御刷写时断电、网线松动或硬件故障。刷写期间禁止断电。
- 应用会在任何闪存写入前自动备份 BL2、Nvram、Bdata、Factory、FIP、KF 及原厂 mtd8/mtd9 两个系统槽位，计算 SHA-256，并导出到 `Download/AX3000T-Backups`。
- 回退只接受本机自动生成的备份，且会严格检查 mtd8 尺寸；不会把其他路由器的 Factory/MAC/校准数据写入本机。

## 构建

```sh
./gradlew assembleDebug
```

Release 签名由 GitHub Actions 使用私有仓库内置的 PKCS12 keystore 完成。这个 keystore 来自用户指定的其他 GitHub 项目；请勿把本仓库改为公开，也不要把密码写入代码。

## CI

`Build APK` workflow 会：

1. 安装 JDK 17、Android SDK 35；
2. 从 `AX3000T_KEYSTORE_B64` 解码签名文件；
3. 构建签名 Release APK；
4. 上传 Actions artifact；
5. 将最新 APK 和 SHA-256 归档到 `apk/` 目录并提交回 `main`。

需要配置的 Secrets：

- `AX3000T_STORE_PASSWORD`
- `AX3000T_KEY_ALIAS`
- `AX3000T_KEY_PASSWORD`

## 来源

固件来自 OpenWrt 官方下载站，APK 内置并在运行时再次 SHA-256 校验。安装流程依据 OpenWrt AX3000T 官方设备页；请以官方页面和设备实际硬件为准。
