package com.minis.ax3000t;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Locale;

public final class NetworkUtils {
    public static final class Status {
        public final boolean ethernet;
        public final String address;
        public final String interfaceName;
        public final String description;

        Status(boolean ethernet, String address, String interfaceName, String description) {
            this.ethernet = ethernet;
            this.address = address;
            this.interfaceName = interfaceName;
            this.description = description;
        }
    }

    private NetworkUtils() {
    }

    public static Status bindBestNetwork(Context context) {
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network ethernetNetwork = null;
        Network fallbackNetwork = null;
        if (manager != null) {
            for (Network network : manager.getAllNetworks()) {
                NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
                if (capabilities == null) continue;
                if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    // A directly connected router may not advertise Internet; it is
                    // still the network we need. Keep scanning, but don't discard it.
                }
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    ethernetNetwork = network;
                    break;
                }
                if (fallbackNetwork == null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    fallbackNetwork = network;
                }
            }
            Network selected = ethernetNetwork != null ? ethernetNetwork : fallbackNetwork;
            if (selected != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                manager.bindProcessToNetwork(selected);
            }
        }
        Status status = findAddress(ethernetNetwork != null);
        if (status.address.isEmpty() && fallbackNetwork != null) {
            status = findAddress(false);
        }
        return status;
    }

    public static Status findAddress(boolean preferEthernet) {
        String fallbackAddress = "";
        String fallbackName = "";
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return new Status(false, "", "", "未找到网络接口");
            for (NetworkInterface networkInterface : Collections.list(interfaces)) {
                if (!networkInterface.isUp() || networkInterface.isLoopback()) continue;
                String name = networkInterface.getName();
                boolean ethernet = looksLikeEthernet(name);
                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                for (InetAddress address : Collections.list(addresses)) {
                    if (!(address instanceof Inet4Address) || address.isLoopbackAddress() || address.isLinkLocalAddress()) continue;
                    String value = address.getHostAddress();
                    if (ethernet) return new Status(true, value, name, "已检测到有线网络 " + name + "：" + value);
                    if (fallbackAddress.isEmpty()) {
                        fallbackAddress = value;
                        fallbackName = name;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        if (!fallbackAddress.isEmpty()) {
            return new Status(false, fallbackAddress, fallbackName, "当前使用无线网络 " + fallbackName + "：" + fallbackAddress);
        }
        return new Status(false, "", "", "手机尚未获得 IP 地址");
    }

    private static boolean looksLikeEthernet(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.US);
        return lower.startsWith("eth") || lower.startsWith("usb") || lower.startsWith("rndis")
                || lower.startsWith("en") || lower.contains("ether");
    }
}
