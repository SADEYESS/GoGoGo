package com.zcshou.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.LruCache;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.baidu.mapapi.model.LatLng;
import com.zcshou.gogogo.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 免 API Key 的原生地图视图（基于 OpenStreetMap / CartoDB 街道瓦片 与 Esri 全球卫星瓦片）
 * 内部接口使用项目的 LatLng (BD09 经纬度)，在渲染瓦片时自动转换为标准 WGS84 Web Mercator 投影，
 * 从而与项目现有的历史记录、收藏位置、坐标换算 100% 无缝兼容，且无需任何 API Key。
 */
public class KeylessMapView extends FrameLayout {
    public static final int MAP_TYPE_NORMAL = 1;
    public static final int MAP_TYPE_SATELLITE = 2;

    private static final int TILE_SIZE = 256;
    private static final int MIN_ZOOM = 3;
    private static final int MAX_ZOOM = 18;
    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36";

    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build();

    private static final LruCache<String, Bitmap> MEMORY_CACHE = new LruCache<String, Bitmap>(64 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private final Set<String> mInFlightRequests = Collections.synchronizedSet(new HashSet<>());
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private File mDiskCacheDir;

    // 视图中心点（WGS84 经纬度）
    private double mCenterWgsLat = 39.908823;
    private double mCenterWgsLon = 116.397470;
    private int mZoom = 15;
    private int mMapType = MAP_TYPE_NORMAL;

    // 当前定位蓝点（BD09）
    private LatLng mMyLocationBd = null;
    private float mMyDirection = 0f;

    // 选点标记（BD09）
    private LatLng mMarkerBd = null;
    private Drawable mMarkerDrawable;

    // InfoWindow 弹窗视图
    private View mInfoWindowView = null;
    private LatLng mInfoWindowBd = null;

    // 手势检测
    private GestureDetector mGestureDetector;
    private ScaleGestureDetector mScaleGestureDetector;
    private boolean mIsScaling = false;

    // 画笔
    private final Paint mTilePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint mGridBgPaint = new Paint();
    private final Paint mGridLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mMyLocCirclePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mMyLocDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mMyLocBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mHudBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mHudTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect mSrcRect = new Rect();
    private final RectF mDstRect = new RectF();

    public interface OnMapActionListener {
        void onMapClick(LatLng pointBd);
        void onMapLongClick(LatLng pointBd);
    }

    public interface OnMapClickListener {
        void onMapClick(LatLng pointBd);
    }

    public interface OnMapLongClickListener {
        void onMapLongClick(LatLng pointBd);
    }

    private OnMapActionListener mListener;
    private OnMapClickListener mClickListener;
    private OnMapLongClickListener mLongClickListener;

    public KeylessMapView(@NonNull Context context) {
        super(context);
        init(context);
    }

    public KeylessMapView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context, attrs);
    }

    public KeylessMapView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context, AttributeSet attrs) {
        init(context);
    }

    private void init(Context context) {
        setWillNotDraw(false);
        setClipChildren(false);
        setClipToPadding(false);

        mDiskCacheDir = new File(context.getCacheDir(), "map_tiles_v2");
        if (!mDiskCacheDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            mDiskCacheDir.mkdirs();
        }

        mMarkerDrawable = ContextCompat.getDrawable(context, R.drawable.icon_gcoding);

        float density = context.getResources().getDisplayMetrics().density;

        mGridBgPaint.setColor(Color.parseColor("#EEF2F6"));
        mGridLinePaint.setColor(Color.parseColor("#D5DEE8"));
        mGridLinePaint.setStrokeWidth(1f * density);

        mMyLocCirclePaint.setColor(Color.parseColor("#332563EB"));
        mMyLocCirclePaint.setStyle(Paint.Style.FILL);

        mMyLocDotPaint.setColor(Color.parseColor("#2563EB"));
        mMyLocDotPaint.setStyle(Paint.Style.FILL);

        mMyLocBorderPaint.setColor(Color.WHITE);
        mMyLocBorderPaint.setStyle(Paint.Style.STROKE);
        mMyLocBorderPaint.setStrokeWidth(2.5f * density);

        mHudBgPaint.setColor(Color.parseColor("#CC0F172A"));
        mHudBgPaint.setStyle(Paint.Style.FILL);

        mHudTextPaint.setColor(Color.WHITE);
        mHudTextPaint.setTextSize(11f * density);

        mGestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(@NonNull MotionEvent e) {
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, @NonNull MotionEvent e2, float distanceX, float distanceY) {
                if (mIsScaling) {
                    return false;
                }
                panByPixels(distanceX, distanceY);
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(@NonNull MotionEvent e) {
                LatLng clickedBd = screenToBd09(e.getX(), e.getY());
                if (mListener != null) {
                    mListener.onMapClick(clickedBd);
                }
                if (mClickListener != null) {
                    mClickListener.onMapClick(clickedBd);
                }
                return true;
            }

            @Override
            public void onLongPress(@NonNull MotionEvent e) {
                LatLng longClickedBd = screenToBd09(e.getX(), e.getY());
                if (mListener != null) {
                    mListener.onMapLongClick(longClickedBd);
                }
                if (mLongClickListener != null) {
                    mLongClickListener.onMapLongClick(longClickedBd);
                }
            }

            @Override
            public boolean onDoubleTap(@NonNull MotionEvent e) {
                zoomIn();
                return true;
            }
        });

        mScaleGestureDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            private float mAccumulatedScale = 1f;

            @Override
            public boolean onScaleBegin(@NonNull ScaleGestureDetector detector) {
                mIsScaling = true;
                mAccumulatedScale = 1f;
                return true;
            }

            @Override
            public boolean onScale(@NonNull ScaleGestureDetector detector) {
                mAccumulatedScale *= detector.getScaleFactor();
                if (mAccumulatedScale > 1.35f) {
                    zoomIn();
                    mAccumulatedScale = 1f;
                } else if (mAccumulatedScale < 0.72f) {
                    zoomOut();
                    mAccumulatedScale = 1f;
                }
                return true;
            }

            @Override
            public void onScaleEnd(@NonNull ScaleGestureDetector detector) {
                mIsScaling = false;
            }
        });
    }

    public void setOnMapActionListener(OnMapActionListener listener) {
        this.mListener = listener;
    }

    public void setOnMapClickListener(OnMapClickListener listener) {
        this.mClickListener = listener;
    }

    public void setOnMapLongClickListener(OnMapLongClickListener listener) {
        this.mLongClickListener = listener;
    }

    public void setMapType(int mapType) {
        if (this.mMapType != mapType) {
            this.mMapType = mapType;
            invalidate();
        }
    }

    public void zoomIn() {
        if (mZoom < MAX_ZOOM) {
            mZoom++;
            updateInfoWindowPosition();
            invalidate();
        }
    }

    public void zoomOut() {
        if (mZoom > MIN_ZOOM) {
            mZoom--;
            updateInfoWindowPosition();
            invalidate();
        }
    }

    public void setZoom(int zoom) {
        mZoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom));
        updateInfoWindowPosition();
        invalidate();
    }

    public void setCenterBd09(LatLng bdLatLng) {
        if (bdLatLng == null) {
            return;
        }
        double[] wgs = MapUtils.bd2wgs(bdLatLng.longitude, bdLatLng.latitude);
        mCenterWgsLon = clampLon(wgs[0]);
        mCenterWgsLat = clampLat(wgs[1]);
        updateInfoWindowPosition();
        invalidate();
    }

    public void setTargetAndZoomBd09(LatLng bdLatLng, double zoom) {
        mZoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, (int) Math.round(zoom)));
        setCenterBd09(bdLatLng);
    }

    public LatLng getCenterBd09() {
        double[] bd = MapUtils.wgs2bd09(mCenterWgsLon, mCenterWgsLat);
        return new LatLng(bd[1], bd[0]);
    }

    public LatLng getMapCenterBd09() {
        return getCenterBd09();
    }

    public void setMyLocationBd09(double bdLat, double bdLon, float direction) {
        if (bdLat == 0.0 && bdLon == 0.0) {
            return;
        }
        mMyLocationBd = new LatLng(bdLat, bdLon);
        mMyDirection = direction;
        invalidate();
    }

    public void setMarkerBd09(LatLng markerBd) {
        this.mMarkerBd = markerBd;
        updateInfoWindowPosition();
        invalidate();
    }

    public void clearOverlays() {
        mMarkerBd = null;
        hideInfoWindow();
        invalidate();
    }

    public void clear() {
        clearOverlays();
    }

    public void showInfoWindow(View view, LatLng locationBd) {
        if (view == null || locationBd == null) {
            return;
        }
        mInfoWindowBd = locationBd;
        if (mInfoWindowView != view) {
            if (mInfoWindowView != null && mInfoWindowView.getParent() == this) {
                removeView(mInfoWindowView);
            }
            if (view.getParent() instanceof ViewGroup) {
                ((ViewGroup) view.getParent()).removeView(view);
            }
            mInfoWindowView = view;
            LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            addView(mInfoWindowView, lp);
        }
        mInfoWindowView.setVisibility(VISIBLE);
        post(this::updateInfoWindowPosition);
        invalidate();
    }

    public void hideInfoWindow() {
        if (mInfoWindowView != null) {
            mInfoWindowView.setVisibility(GONE);
        }
        mInfoWindowBd = null;
    }

    private void updateInfoWindowPosition() {
        if (mInfoWindowView == null || mInfoWindowView.getVisibility() != VISIBLE || mInfoWindowBd == null) {
            return;
        }
        if (getWidth() == 0 || getHeight() == 0) {
            return;
        }
        float[] xy = bd09ToScreen(mInfoWindowBd);
        int vw = mInfoWindowView.getMeasuredWidth();
        int vh = mInfoWindowView.getMeasuredHeight();
        if (vw == 0 || vh == 0) {
            mInfoWindowView.measure(
                    MeasureSpec.makeMeasureSpec(getWidth(), MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(getHeight(), MeasureSpec.AT_MOST)
            );
            vw = mInfoWindowView.getMeasuredWidth();
            vh = mInfoWindowView.getMeasuredHeight();
        }
        float markerHeight = mMarkerDrawable != null ? mMarkerDrawable.getIntrinsicHeight() : 48f;
        float tx = xy[0] - vw / 2f;
        float ty = xy[1] - vh - markerHeight - 8f;
        mInfoWindowView.setTranslationX(tx);
        mInfoWindowView.setTranslationY(ty);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        post(this::updateInfoWindowPosition);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        mScaleGestureDetector.onTouchEvent(event);
        mGestureDetector.onTouchEvent(event);
        return true;
    }

    private void panByPixels(float dx, float dy) {
        double worldSize = (double) TILE_SIZE * (1 << mZoom);
        double centerPx = lonToNormalizedX(mCenterWgsLon) * worldSize + dx;
        double centerPy = latToNormalizedY(mCenterWgsLat) * worldSize + dy;

        mCenterWgsLon = clampLon(normalizedXToLon(centerPx / worldSize));
        mCenterWgsLat = clampLat(normalizedYToLat(centerPy / worldSize));
        updateInfoWindowPosition();
        invalidate();
    }

    private LatLng screenToBd09(float screenX, float screenY) {
        double worldSize = (double) TILE_SIZE * (1 << mZoom);
        double centerPx = lonToNormalizedX(mCenterWgsLon) * worldSize;
        double centerPy = latToNormalizedY(mCenterWgsLat) * worldSize;

        double targetPx = centerPx + (screenX - getWidth() / 2.0);
        double targetPy = centerPy + (screenY - getHeight() / 2.0);

        double wgsLon = clampLon(normalizedXToLon(targetPx / worldSize));
        double wgsLat = clampLat(normalizedYToLat(targetPy / worldSize));

        double[] bd = MapUtils.wgs2bd09(wgsLon, wgsLat);
        return new LatLng(bd[1], bd[0]);
    }

    private float[] bd09ToScreen(LatLng bdLatLng) {
        double[] wgs = MapUtils.bd2wgs(bdLatLng.longitude, bdLatLng.latitude);
        return wgs84ToScreen(wgs[1], wgs[0]);
    }

    private float[] wgs84ToScreen(double wgsLat, double wgsLon) {
        double worldSize = (double) TILE_SIZE * (1 << mZoom);
        double centerPx = lonToNormalizedX(mCenterWgsLon) * worldSize;
        double centerPy = latToNormalizedY(mCenterWgsLat) * worldSize;

        double ptPx = lonToNormalizedX(wgsLon) * worldSize;
        double ptPy = latToNormalizedY(wgsLat) * worldSize;

        float sx = (float) (getWidth() / 2.0 + (ptPx - centerPx));
        float sy = (float) (getHeight() / 2.0 + (ptPy - centerPy));
        return new float[]{sx, sy};
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }

        // 1. 绘制底色与网格（在瓦片加载中也清晰可见）
        canvas.drawRect(0, 0, width, height, mGridBgPaint);

        int numTiles = 1 << mZoom;
        double worldSize = (double) TILE_SIZE * numTiles;
        double centerPx = lonToNormalizedX(mCenterWgsLon) * worldSize;
        double centerPy = latToNormalizedY(mCenterWgsLat) * worldSize;

        double leftPx = centerPx - width / 2.0;
        double topPx = centerPy - height / 2.0;
        double rightPx = centerPx + width / 2.0;
        double bottomPx = centerPy + height / 2.0;

        int minTileX = (int) Math.floor(leftPx / TILE_SIZE);
        int maxTileX = (int) Math.floor(rightPx / TILE_SIZE);
        int minTileY = (int) Math.max(0, Math.floor(topPx / TILE_SIZE));
        int maxTileY = (int) Math.min(numTiles - 1, Math.floor(bottomPx / TILE_SIZE));

        // 2. 绘制地图瓦片
        for (int tx = minTileX; tx <= maxTileX; tx++) {
            int wrappedTx = ((tx % numTiles) + numTiles) % numTiles;
            for (int ty = minTileY; ty <= maxTileY; ty++) {
                float dstLeft = (float) (tx * TILE_SIZE - leftPx);
                float dstTop = (float) (ty * TILE_SIZE - topPx);
                mDstRect.set(dstLeft, dstTop, dstLeft + TILE_SIZE, dstTop + TILE_SIZE);

                Bitmap tileBmp = getTileBitmap(mMapType, mZoom, wrappedTx, ty);
                if (tileBmp != null && !tileBmp.isRecycled()) {
                    mSrcRect.set(0, 0, tileBmp.getWidth(), tileBmp.getHeight());
                    canvas.drawBitmap(tileBmp, mSrcRect, mDstRect, mTilePaint);
                } else {
                    // 尝试使用上一级缩放瓦片进行平滑占位过渡
                    boolean drewFallback = drawParentTileFallback(canvas, mMapType, mZoom, wrappedTx, ty, mDstRect);
                    if (!drewFallback) {
                        canvas.drawRect(mDstRect, mGridLinePaint);
                    }
                }
            }
        }

        // 3. 绘制当前位置蓝点
        if (mMyLocationBd != null) {
            float[] locXY = bd09ToScreen(mMyLocationBd);
            float density = getResources().getDisplayMetrics().density;
            canvas.drawCircle(locXY[0], locXY[1], 22f * density, mMyLocCirclePaint);
            canvas.drawCircle(locXY[0], locXY[1], 7.5f * density, mMyLocDotPaint);
            canvas.drawCircle(locXY[0], locXY[1], 7.5f * density, mMyLocBorderPaint);
        }

        // 4. 绘制选点图标
        if (mMarkerBd != null && mMarkerDrawable != null) {
            float[] markXY = bd09ToScreen(mMarkerBd);
            int mw = mMarkerDrawable.getIntrinsicWidth();
            int mh = mMarkerDrawable.getIntrinsicHeight();
            if (mw <= 0) mw = 64;
            if (mh <= 0) mh = 64;
            int left = Math.round(markXY[0] - mw / 2f);
            int top = Math.round(markXY[1] - mh);
            mMarkerDrawable.setBounds(left, top, left + mw, top + mh);
            mMarkerDrawable.draw(canvas);
        }

        // 5. 绘制左下角免 Key 地图状态栏（经纬度 & 缩放级别）
        float density = getResources().getDisplayMetrics().density;
        String hudText = String.format(Locale.US, "OSM 免Key地图 | Z:%d | %.4f, %.4f", mZoom, mCenterWgsLon, mCenterWgsLat);
        float textW = mHudTextPaint.measureText(hudText);
        float padH = 8f * density;
        float padV = 5f * density;
        float boxLeft = 10f * density;
        float boxBottom = height - 24f * density;
        float boxTop = boxBottom - 16f * density - padV * 2;
        RectF hudRect = new RectF(boxLeft, boxTop, boxLeft + textW + padH * 2, boxBottom);
        canvas.drawRoundRect(hudRect, 6f * density, 6f * density, mHudBgPaint);
        canvas.drawText(hudText, boxLeft + padH, boxBottom - padV - 3f * density, mHudTextPaint);
    }

    private boolean drawParentTileFallback(Canvas canvas, int mapType, int z, int x, int y, RectF dst) {
        if (z <= MIN_ZOOM) {
            return false;
        }
        int parentZ = z - 1;
        int parentX = x / 2;
        int parentY = y / 2;
        String key = getCacheKey(mapType, parentZ, parentX, parentY);
        Bitmap parentBmp = MEMORY_CACHE.get(key);
        if (parentBmp != null && !parentBmp.isRecycled()) {
            int subX = (x % 2) * (TILE_SIZE / 2);
            int subY = (y % 2) * (TILE_SIZE / 2);
            mSrcRect.set(subX, subY, subX + TILE_SIZE / 2, subY + TILE_SIZE / 2);
            canvas.drawBitmap(parentBmp, mSrcRect, dst, mTilePaint);
            return true;
        }
        return false;
    }

    private String getCacheKey(int mapType, int z, int x, int y) {
        return mapType + "_" + z + "_" + x + "_" + y;
    }

    private Bitmap getTileBitmap(int mapType, int z, int x, int y) {
        final String key = getCacheKey(mapType, z, x, y);
        Bitmap cached = MEMORY_CACHE.get(key);
        if (cached != null && !cached.isRecycled()) {
            return cached;
        }

        if (mInFlightRequests.contains(key)) {
            return null;
        }
        mInFlightRequests.add(key);

        // 先查本地磁盘缓存，再发起网络请求
        final File diskFile = new File(mDiskCacheDir, key + ".png");
        final String[] urls;
        if (mapType == MAP_TYPE_SATELLITE) {
            urls = new String[]{
                    String.format(Locale.US,
                            "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/%d/%d/%d",
                            z, y, x),
                    String.format(Locale.US,
                            "https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/%d/%d/%d",
                            z, y, x)
            };
        } else {
            urls = new String[]{
                    String.format(Locale.US,
                            "https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/%d/%d/%d",
                            z, y, x),
                    String.format(Locale.US,
                            "https://server.arcgisonline.com/ArcGIS/rest/services/World_Topo_Map/MapServer/tile/%d/%d/%d",
                            z, y, x),
                    String.format(Locale.US,
                            "https://webrd01.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x=%d&y=%d&z=%d",
                            x, y, z)
            };
        }

        fetchTileAsync(key, diskFile, urls, 0);
        return null;
    }

    private void fetchTileAsync(final String key, final File diskFile, final String[] urls, final int urlIndex) {
        if (urlIndex == 0 && diskFile.exists() && diskFile.length() > 0) {
            Bitmap diskBmp = BitmapFactory.decodeFile(diskFile.getAbsolutePath());
            if (diskBmp != null) {
                MEMORY_CACHE.put(key, diskBmp);
                mInFlightRequests.remove(key);
                mMainHandler.post(this::invalidate);
                return;
            }
        }

        if (urlIndex >= urls.length) {
            mInFlightRequests.remove(key);
            return;
        }

        Request request = new Request.Builder()
                .url(urls[urlIndex])
                .header("User-Agent", USER_AGENT)
                .get()
                .build();

        HTTP_CLIENT.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                if (urlIndex + 1 < urls.length) {
                    fetchTileAsync(key, diskFile, urls, urlIndex + 1);
                } else {
                    mInFlightRequests.remove(key);
                }
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try (ResponseBody body = response.body()) {
                    if (!response.isSuccessful() || body == null) {
                        if (urlIndex + 1 < urls.length) {
                            fetchTileAsync(key, diskFile, urls, urlIndex + 1);
                        } else {
                            mInFlightRequests.remove(key);
                        }
                        return;
                    }
                    byte[] bytes = body.bytes();
                    Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    if (bmp != null) {
                        MEMORY_CACHE.put(key, bmp);
                        try (FileOutputStream fos = new FileOutputStream(diskFile)) {
                            fos.write(bytes);
                        } catch (Exception ignored) {
                        }
                        mInFlightRequests.remove(key);
                        mMainHandler.post(KeylessMapView.this::invalidate);
                    } else if (urlIndex + 1 < urls.length) {
                        fetchTileAsync(key, diskFile, urls, urlIndex + 1);
                    } else {
                        mInFlightRequests.remove(key);
                    }
                }
            }
        });
    }

    private static double lonToNormalizedX(double lon) {
        return (lon + 180.0) / 360.0;
    }

    private static double latToNormalizedY(double lat) {
        double sinLat = Math.sin(Math.toRadians(clampLat(lat)));
        return 0.5 - Math.log((1.0 + sinLat) / (1.0 - sinLat)) / (4.0 * Math.PI);
    }

    private static double normalizedXToLon(double x) {
        return x * 360.0 - 180.0;
    }

    private static double normalizedYToLat(double y) {
        double n = Math.PI - 2.0 * Math.PI * y;
        return Math.toDegrees(Math.atan(0.5 * (Math.exp(n) - Math.exp(-n))));
    }

    private static double clampLat(double lat) {
        return Math.max(-85.05112878, Math.min(85.05112878, lat));
    }

    private static double clampLon(double lon) {
        while (lon > 180.0) lon -= 360.0;
        while (lon < -180.0) lon += 360.0;
        return lon;
    }

    public void onResume() {
        invalidate();
    }

    public void onPause() {
    }

    public void onDestroy() {
        mInFlightRequests.clear();
    }
}
