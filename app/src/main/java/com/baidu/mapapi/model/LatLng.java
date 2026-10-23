package com.baidu.mapapi.model;

import java.util.Locale;

/**
 * 轻量级纯 Java 经纬度数据模型，替代旧版百度 SDK，彻底移除未 16KB 对齐的原生 .so 库。
 */
public class LatLng {
    public final double latitude;
    public final double longitude;

    public LatLng(double latitude, double longitude) {
        this.latitude = latitude;
        this.longitude = longitude;
    }

    @Override
    public String toString() {
        return String.format(Locale.US, "latitude: %.6f, longitude: %.6f", latitude, longitude);
    }
}
