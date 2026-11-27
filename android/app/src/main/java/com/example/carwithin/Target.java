package com.example.carwithin;

import java.util.Locale;

public record Target(String name, String mac) {
    public Target {
        name = name.trim(); mac = mac.trim().toLowerCase(Locale.ROOT);
        if (name.isEmpty() || !mac.matches("(?:[0-9a-f]{2}:){5}[0-9a-f]{2}"))
            throw new IllegalArgumentException("请输入完整设备名称和 MAC 地址");
    }
    public boolean matches(String deviceName, String address) {
        return name.equals(deviceName) && mac.equalsIgnoreCase(address);
    }
    public boolean matchesOwner(String deviceName, String networkName, String address) {
        return mac.equalsIgnoreCase(address) && (name.equals(deviceName) || name.equals(networkName));
    }
}
