package com.zcshou.utils;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import com.zcshou.gogogo.MainActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 免 Key 地理编码与地点搜索工具类（基于 OpenStreetMap Nominatim 公共接口）
 */
public class NominatimHelper {
    private static final String USER_AGENT = "EverywhereLocationApp/1.2 (Android; OpenStreetMap)";
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    public interface SearchCallback {
        void onSuccess(List<Map<String, Object>> results);
        default void onError(String message) {}
    }

    public interface ReverseCallback {
        void onAddressResolved(String address);
    }

    public static void searchPlaces(final String query, final SearchCallback callback) {
        if (TextUtils.isEmpty(query)) {
            MAIN_HANDLER.post(() -> callback.onSuccess(new ArrayList<>()));
            return;
        }

        // 支持直接输入 "经度,纬度" 或 "纬度,经度"
        String trimmed = query.trim().replace("，", ",");
        if (trimmed.contains(",")) {
            String[] parts = trimmed.split(",");
            if (parts.length == 2) {
                try {
                    double v1 = Double.parseDouble(parts[0].trim());
                    double v2 = Double.parseDouble(parts[1].trim());
                    double lon = Math.abs(v1) > 90 ? v1 : v2;
                    double lat = Math.abs(v1) > 90 ? v2 : v1;
                    if (Math.abs(lon) <= 180 && Math.abs(lat) <= 90) {
                        double[] bd = MapUtils.wgs2bd09(lon, lat);
                        List<Map<String, Object>> list = new ArrayList<>();
                        Map<String, Object> item = new HashMap<>();
                        item.put(MainActivity.POI_NAME, String.format(Locale.US, "坐标点 (%.5f, %.5f)", lon, lat));
                        item.put(MainActivity.POI_ADDRESS, "WGS84 坐标直达");
                        item.put(MainActivity.POI_LONGITUDE, String.valueOf(bd[0]));
                        item.put(MainActivity.POI_LATITUDE, String.valueOf(bd[1]));
                        list.add(item);
                        MAIN_HANDLER.post(() -> callback.onSuccess(list));
                        return;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }

        try {
            String encoded = URLEncoder.encode(trimmed, "UTF-8");
            String url = "https://nominatim.openstreetmap.org/search?format=json&addressdetails=1&limit=12&q=" + encoded;
            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .get()
                    .build();

            CLIENT.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    MAIN_HANDLER.post(() -> callback.onError(e.getMessage()));
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                    ResponseBody body = response.body();
                    if (!response.isSuccessful() || body == null) {
                        MAIN_HANDLER.post(() -> callback.onError("HTTP " + response.code()));
                        return;
                    }
                    String jsonStr = body.string();
                    try {
                        JSONArray arr = new JSONArray(jsonStr);
                        final List<Map<String, Object>> data = new ArrayList<>();
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject obj = arr.getJSONObject(i);
                            double wgsLat = obj.optDouble("lat", Double.NaN);
                            double wgsLon = obj.optDouble("lon", Double.NaN);
                            if (Double.isNaN(wgsLat) || Double.isNaN(wgsLon)) {
                                continue;
                            }
                            String displayName = obj.optString("display_name", "");
                            String name = obj.optString("name", "");
                            if (TextUtils.isEmpty(name)) {
                                int comma = displayName.indexOf(',');
                                name = comma > 0 ? displayName.substring(0, comma) : displayName;
                            }

                            // 转换为内部统一使用的 BD09 坐标存储，保证与历史记录/坐标转换完全兼容
                            double[] bd = MapUtils.wgs2bd09(wgsLon, wgsLat);
                            Map<String, Object> poiItem = new HashMap<>();
                            poiItem.put(MainActivity.POI_NAME, name);
                            poiItem.put(MainActivity.POI_ADDRESS, displayName);
                            poiItem.put(MainActivity.POI_LONGITUDE, String.valueOf(bd[0]));
                            poiItem.put(MainActivity.POI_LATITUDE, String.valueOf(bd[1]));
                            data.add(poiItem);
                        }
                        MAIN_HANDLER.post(() -> callback.onSuccess(data));
                    } catch (Exception e) {
                        MAIN_HANDLER.post(() -> callback.onError(e.getMessage()));
                    }
                }
            });
        } catch (Exception e) {
            MAIN_HANDLER.post(() -> callback.onError(e.getMessage()));
        }
    }

    public static void reverseGeocodeBd09(double bdLng, double bdLat, final ReverseCallback callback) {
        double[] wgs = MapUtils.bd2wgs(bdLng, bdLat);
        reverseGeocodeWgs84(wgs[1], wgs[0], callback);
    }

    public static void reverseGeocodeWgs84(double wgsLat, double wgsLon, final ReverseCallback callback) {
        String url = String.format(Locale.US,
                "https://nominatim.openstreetmap.org/reverse?format=json&zoom=18&addressdetails=1&lat=%.6f&lon=%.6f",
                wgsLat, wgsLon);
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .get()
                .build();

        CLIENT.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                String fallback = String.format(Locale.US, "选定位置 (%.4f, %.4f)", wgsLon, wgsLat);
                MAIN_HANDLER.post(() -> callback.onAddressResolved(fallback));
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                ResponseBody body = response.body();
                if (!response.isSuccessful() || body == null) {
                    String fallback = String.format(Locale.US, "选定位置 (%.4f, %.4f)", wgsLon, wgsLat);
                    MAIN_HANDLER.post(() -> callback.onAddressResolved(fallback));
                    return;
                }
                try {
                    JSONObject obj = new JSONObject(body.string());
                    String displayName = obj.optString("display_name", "");
                    if (!TextUtils.isEmpty(displayName)) {
                        MAIN_HANDLER.post(() -> callback.onAddressResolved(displayName));
                    } else {
                        String fallback = String.format(Locale.US, "选定位置 (%.4f, %.4f)", wgsLon, wgsLat);
                        MAIN_HANDLER.post(() -> callback.onAddressResolved(fallback));
                    }
                } catch (Exception e) {
                    String fallback = String.format(Locale.US, "选定位置 (%.4f, %.4f)", wgsLon, wgsLat);
                    MAIN_HANDLER.post(() -> callback.onAddressResolved(fallback));
                }
            }
        });
    }
}
