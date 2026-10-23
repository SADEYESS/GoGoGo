package com.zcshou.gogogo;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SimpleAdapter;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SearchView;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.preference.PreferenceManager;

import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import com.baidu.mapapi.model.LatLng;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.navigation.NavigationView;
import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.zcshou.service.ServiceGo;
import com.zcshou.database.DataBaseHistoryLocation;
import com.zcshou.database.DataBaseHistorySearch;
import com.zcshou.utils.ShareUtils;
import com.zcshou.utils.GoUtils;
import com.zcshou.utils.MapUtils;
import com.zcshou.utils.KeylessMapView;
import com.zcshou.utils.NominatimHelper;

import com.elvishew.xlog.XLog;

public class MainActivity extends BaseActivity implements SensorEventListener {
    /* 对外 */
    public static final String LAT_MSG_ID = "LAT_VALUE";
    public static final String LNG_MSG_ID = "LNG_VALUE";
    public static final String ALT_MSG_ID = "ALT_VALUE";

    public static final String POI_NAME = "POI_NAME";
    public static final String POI_ADDRESS = "POI_ADDRESS";
    public static final String POI_LONGITUDE = "POI_LONGITUDE";
    public static final String POI_LATITUDE = "POI_LATITUDE";

    private SharedPreferences sharedPreferences;

    /*============================== 主界面地图 相关 ==============================*/
    /************** 地图 *****************/
    public static String mCurrentCity = null;
    private static KeylessMapView mMapView = null;
    private static LatLng mMarkLatLngMap = new LatLng(36.547743718042415, 117.07018449827267); // 当前标记的地图点
    private static String mMarkName = null;
    private static View mPoiView = null;
    private static TextView mPoiAddress = null;
    private static TextView mPoiLongitude = null;
    private static TextView mPoiLatitude = null;
    private SensorManager mSensorManager;
    private Sensor mSensorAccelerometer;
    private Sensor mSensorMagnetic;
    private float[] mAccValues = new float[3];//加速度传感器数据
    private float[] mMagValues = new float[3];//地磁传感器数据
    private final float[] mR = new float[9];//旋转矩阵，用来保存磁场和加速度的数据
    private final float[] mDirectionValues = new float[3];//模拟方向传感器的数据（原始数据为弧度）
    /************** 定位 *****************/
    private LocationManager mLocationManager = null;
    private LocationListener mLocationListener = null;
    private double mCurrentLat = 39.915119;       // 当前位置的百度纬度
    private double mCurrentLon = 116.403963;      // 当前位置的百度经度
    private float mCurrentDirection = 0.0f;
    private boolean isFirstLoc = true; // 是否首次定位
    private boolean isMockServStart = false;
    private ServiceGo.ServiceGoBinder mServiceBinder;
    private ServiceConnection mConnection;
    private FloatingActionButton mButtonStart;
    /*============================== 历史记录 相关 ==============================*/
    private SQLiteDatabase mLocationHistoryDB;
    private SQLiteDatabase mSearchHistoryDB;
    /*============================== SearchView 相关 ==============================*/
    private SearchView searchView;
    private ListView mSearchList;
    private LinearLayout mSearchLayout;
    private ListView mSearchHistoryList;
    private LinearLayout mHistoryLayout;
    private MenuItem searchItem;

    private static final int REQ_LOCATION_PERMISSION = 127;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PreferenceManager.setDefaultValues(this, R.xml.preferences_main, false);
        setContentView(R.layout.activity_main);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        DrawerLayout drawer = findViewById(R.id.drawer_layout);
        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
                this, drawer, toolbar, R.string.nav_drawer_open, R.string.nav_drawer_close);
        drawer.addDrawerListener(toggle);
        toggle.syncState();

        XLog.i("MainActivity: onCreate");

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);

        checkAndRequestPermissions();

        initNavigationView();

        initMap();

        initMapLocation();

        initMapButton();

        initGoBtn();

        mConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                mServiceBinder = (ServiceGo.ServiceGoBinder)service;
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {

            }
        };

        initStoreHistory();

        initSearchView();
    }

    private void checkAndRequestPermissions() {
        List<String> reqList = new ArrayList<>();
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            reqList.add(android.Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            reqList.add(android.Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (!reqList.isEmpty()) {
            requestPermissions(reqList.toArray(new String[0]), REQ_LOCATION_PERMISSION);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOCATION_PERMISSION) {
            requestNativeLocationUpdates();
        }
    }

    @Override
    protected void onPause() {
        XLog.i("MainActivity: onPause");
        if (mMapView != null) {
            mMapView.onPause();
        }
        mSensorManager.unregisterListener(this);
        super.onPause();
    }

    @Override
    protected void onResume() {
        XLog.i("MainActivity: onResume");
        if (mMapView != null) {
            mMapView.onResume();
        }
        mSensorManager.registerListener(this, mSensorAccelerometer, SensorManager.SENSOR_DELAY_UI);
        mSensorManager.registerListener(this, mSensorMagnetic, SensorManager.SENSOR_DELAY_UI);
        super.onResume();
    }

    @Override
    protected void onStop() {
        XLog.i("MainActivity: onStop");
        //取消注册传感器监听
        mSensorManager.unregisterListener(this);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        XLog.i("MainActivity: onDestroy");

        if (isMockServStart) {
            unbindService(mConnection); // 解绑服务，服务要记得解绑，不要造成内存泄漏
            Intent serviceGoIntent = new Intent(MainActivity.this, ServiceGo.class);
            stopService(serviceGoIntent);
        }

        mSensorManager.unregisterListener(this);

        // 退出时销毁定位
        if (mLocationManager != null && mLocationListener != null) {
            try {
                mLocationManager.removeUpdates(mLocationListener);
            } catch (Exception ignored) {
            }
        }
        if (mMapView != null) {
            mMapView.onDestroy();
        }

        //close db
        mLocationHistoryDB.close();
        mSearchHistoryDB.close();

        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        moveTaskToBack(false);
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        //找到searchView
        searchItem = menu.findItem(R.id.action_search);
        searchItem.setOnActionExpandListener(new  MenuItem.OnActionExpandListener() {
            @Override
            public boolean onMenuItemActionCollapse(MenuItem item) {
                mSearchLayout.setVisibility(View.INVISIBLE);
                mHistoryLayout.setVisibility(View.INVISIBLE);
                return true;  // Return true to collapse action view
            }
            @Override
            public boolean onMenuItemActionExpand(MenuItem item) {
                mSearchLayout.setVisibility(View.INVISIBLE);
                //展示搜索历史
                List<Map<String, Object>> data = getSearchHistory();

                if (!data.isEmpty()) {
                    SimpleAdapter simAdapt = new SimpleAdapter(
                            MainActivity.this,
                            data,
                            R.layout.search_item,
                            new String[] {DataBaseHistorySearch.DB_COLUMN_KEY,
                                    DataBaseHistorySearch.DB_COLUMN_DESCRIPTION,
                                    DataBaseHistorySearch.DB_COLUMN_TIMESTAMP,
                                    DataBaseHistorySearch.DB_COLUMN_IS_LOCATION,
                                    DataBaseHistorySearch.DB_COLUMN_LONGITUDE_CUSTOM,
                                    DataBaseHistorySearch.DB_COLUMN_LATITUDE_CUSTOM},
                            new int[] {R.id.search_key,
                                    R.id.search_description,
                                    R.id.search_timestamp,
                                    R.id.search_isLoc,
                                    R.id.search_longitude,
                                    R.id.search_latitude});
                    mSearchHistoryList.setAdapter(simAdapt);
                    mHistoryLayout.setVisibility(View.VISIBLE);
                }

                return true;  // Return true to expand action view
            }
        });

        searchView = (SearchView) searchItem.getActionView();
        searchView.setIconified(false);// 设置searchView处于展开状态
        searchView.onActionViewExpanded();// 当展开无输入内容的时候，没有关闭的图标
        searchView.setIconifiedByDefault(true);//默认为true在框内，设置false则在框外
        searchView.setSubmitButtonEnabled(false);//显示提交按钮
        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                try {
                    performPlaceSearch(query);
                    //搜索历史 插表参数
                    ContentValues contentValues = new ContentValues();
                    contentValues.put(DataBaseHistorySearch.DB_COLUMN_KEY, query);
                    contentValues.put(DataBaseHistorySearch.DB_COLUMN_DESCRIPTION, "搜索关键字");
                    contentValues.put(DataBaseHistorySearch.DB_COLUMN_IS_LOCATION, DataBaseHistorySearch.DB_SEARCH_TYPE_KEY);
                    contentValues.put(DataBaseHistorySearch.DB_COLUMN_TIMESTAMP, System.currentTimeMillis() / 1000);

                    DataBaseHistorySearch.saveHistorySearch(mSearchHistoryDB, contentValues);
                    if (mMapView != null) {
                        mMapView.clear();
                    }
                    mSearchLayout.setVisibility(View.INVISIBLE);
                } catch (Exception e) {
                    GoUtils.DisplayToast(MainActivity.this, getResources().getString(R.string.app_error_search));
                    XLog.d(getResources().getString(R.string.app_error_search));
                }

                return true;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                //当输入框内容改变的时候回调
                //搜索历史置为不可见
                mHistoryLayout.setVisibility(View.INVISIBLE);

                if (newText != null && !newText.isEmpty()) {
                    try {
                        performPlaceSearch(newText);
                    } catch (Exception e) {
                        GoUtils.DisplayToast(MainActivity.this, getResources().getString(R.string.app_error_search));
                        XLog.d(getResources().getString(R.string.app_error_search));
                    }
                }

                return true;
            }
        });

        // 搜索框的清除按钮(该按钮属于安卓系统图标)
        ImageView closeButton = searchView.findViewById(androidx.appcompat.R.id.search_close_btn);
        closeButton.setOnClickListener(v -> {
            EditText et = findViewById(androidx.appcompat.R.id.search_src_text);
            et.setText("");
            searchView.setQuery("", false);
            mSearchLayout.setVisibility(View.INVISIBLE);
            mHistoryLayout.setVisibility(View.VISIBLE);
        });

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_save_location) {
            openSaveCurrentLocationDialog();
            return true;
        } else if (id == R.id.action_saved_locations) {
            showSavedLocationsDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onSensorChanged(SensorEvent sensorEvent) {
        if(sensorEvent.sensor.getType() == Sensor.TYPE_ACCELEROMETER){
            mAccValues = sensorEvent.values;
        }
        else if(sensorEvent.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD){
            mMagValues = sensorEvent.values;
        }

        SensorManager.getRotationMatrix(mR, null, mAccValues, mMagValues);
        SensorManager.getOrientation(mR, mDirectionValues);
        mCurrentDirection = (float) Math.toDegrees(mDirectionValues[0]);    // 弧度转角度
        if (mCurrentDirection < 0) {    // 由 -180 ~ + 180 转为 0 ~ 360
            mCurrentDirection += 360;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {

    }

    /*============================== NavigationView 相关 ==============================*/
    private void initNavigationView() {
        NavigationView mNavigationView = findViewById(R.id.nav_view);
        mNavigationView.setNavigationItemSelectedListener(item -> {
            int id = item.getItemId();

            if (id == R.id.nav_history) {
                Intent intent = new Intent(MainActivity.this, HistoryActivity.class);
                startActivity(intent);
            } else if (id == R.id.nav_settings) {
                Intent intent = new Intent(MainActivity.this, SettingsActivity.class);
                startActivity(intent);
            } else if (id == R.id.nav_dev) {
                if (!GoUtils.isDeveloperOptionsEnabled(this)) {
                    GoUtils.DisplayToast(this, getResources().getString(R.string.app_error_dev));
                } else {
                    try {
                        Intent intent = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
                        startActivity(intent);
                    } catch (Exception e) {
                        GoUtils.DisplayToast(this, getResources().getString(R.string.app_error_dev));
                    }
                }
            }

            DrawerLayout drawer = findViewById(R.id.drawer_layout);
            drawer.closeDrawer(GravityCompat.START);

            return true;
        });
    }

    /*============================== 主界面地图 相关 ==============================*/
    private void initMap() {
        // 免 Key 地图初始化 (OpenStreetMap / CartoDB + Esri Satellite)
        mMapView = findViewById(R.id.bdMapView);
        mMapView.setMapType(KeylessMapView.MAP_TYPE_NORMAL);
        mMapView.setOnMapClickListener(point -> {
            mMarkLatLngMap = point;
            mMarkName = String.format(java.util.Locale.US, "地图选点 (%.4f, %.4f)", point.longitude, point.latitude);
            markMap();
            reverseGeocodePoint(point);
        });
        mMapView.setOnMapLongClickListener(point -> {
            mMarkLatLngMap = point;
            mMarkName = String.format(java.util.Locale.US, "地图选点 (%.4f, %.4f)", point.longitude, point.latitude);
            markMap();
            reverseGeocodePoint(point);
        });

        mPoiView = View.inflate(MainActivity.this, R.layout.location_poi_info, null);
        mPoiAddress = mPoiView.findViewById(R.id.poi_address);
        mPoiLongitude = mPoiView.findViewById(R.id.poi_longitude);
        mPoiLatitude = mPoiView.findViewById(R.id.poi_latitude);
        ImageButton ibSave = mPoiView.findViewById(R.id.poi_save);
        ibSave.setOnClickListener(v -> openSaveCurrentLocationDialog());
        ImageButton ibCopy = mPoiView.findViewById(R.id.poi_copy);
        ibCopy.setOnClickListener(v -> {
            if (mMarkLatLngMap == null) {
                return;
            }
            //获取剪贴板管理器：
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            // 创建普通字符型ClipData
            ClipData mClipData = ClipData.newPlainText("Label", mMarkLatLngMap.toString());
            // 将 ClipData内容放到系统剪贴板里。
            cm.setPrimaryClip(mClipData);

            GoUtils.DisplayToast(this,  getResources().getString(R.string.app_location_copy));
        });
        ImageButton ibShare = mPoiView.findViewById(R.id.poi_share);
        ibShare.setOnClickListener(v -> ShareUtils.shareText(MainActivity.this, "分享位置", mPoiLongitude.getText()+","+mPoiLatitude.getText()));
        ImageButton ibFly = mPoiView.findViewById(R.id.poi_fly);
        ibFly.setOnClickListener(this::doGoLocation);

        mSensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);// 获取传感器管理服务
        if (mSensorManager != null) {
            mSensorAccelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (mSensorAccelerometer != null) {
                mSensorManager.registerListener(this, mSensorAccelerometer, SensorManager.SENSOR_DELAY_UI);
            }
            mSensorMagnetic = mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
            if (mSensorMagnetic != null) {
                mSensorManager.registerListener(this, mSensorMagnetic, SensorManager.SENSOR_DELAY_UI);
            }
        }
    }

    private void reverseGeocodePoint(final LatLng point) {
        if (point == null) return;
        NominatimHelper.reverseGeocodeBd09(point.longitude, point.latitude, address -> {
            if (!TextUtils.isEmpty(address)) {
                mMarkName = address;
                showPoiInfoWindow();
            }
        });
    }

    //开启地图的定位图层（使用系统原生定位，免百度 Key）
    private void initMapLocation() {
        try {
            mLocationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            mLocationListener = new LocationListener() {
                @Override
                public void onLocationChanged(@NonNull Location location) {
                    onReceiveNativeLocation(location);
                }

                @Override
                public void onStatusChanged(String provider, int status, Bundle extras) {
                }

                @Override
                public void onProviderEnabled(@NonNull String provider) {
                }

                @Override
                public void onProviderDisabled(@NonNull String provider) {
                }
            };
            if (mMapView != null) {
                mMapView.setMyLocationBd09(mCurrentLat, mCurrentLon, mCurrentDirection);
            }
            requestNativeLocationUpdates();
        } catch (Exception e) {
            XLog.e("ERROR: initMapLocation");
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private void requestNativeLocationUpdates() {
        if (mLocationManager == null || mLocationListener == null) return;
        boolean hasFine = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED;
        boolean hasCoarse = checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED;
        if (!hasFine && !hasCoarse) return;

        try {
            Location lastLoc = null;
            if (hasFine && mLocationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                mLocationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 1.0f, mLocationListener);
                lastLoc = mLocationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            }
            if (mLocationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                mLocationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 1.0f, mLocationListener);
                if (lastLoc == null) {
                    lastLoc = mLocationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                }
            }
            if (lastLoc == null && mLocationManager.isProviderEnabled(LocationManager.PASSIVE_PROVIDER)) {
                lastLoc = mLocationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER);
            }
            if (lastLoc != null) {
                onReceiveNativeLocation(lastLoc);
            }
        } catch (Exception e) {
            XLog.e("ERROR: requestNativeLocationUpdates " + e.getMessage());
        }
    }

    private void onReceiveNativeLocation(Location loc) {
        if (loc == null || mMapView == null) return;
        double[] bdLonLat = MapUtils.wgs2bd09(loc.getLongitude(), loc.getLatitude());
        mCurrentLon = bdLonLat[0];
        mCurrentLat = bdLonLat[1];
        mMapView.setMyLocationBd09(mCurrentLat, mCurrentLon, mCurrentDirection);
        if (isFirstLoc) {
            isFirstLoc = false;
            mMarkLatLngMap = new LatLng(mCurrentLat, mCurrentLon);
            mMapView.setTargetAndZoomBd09(mMarkLatLngMap, 16.0);
            XLog.i("First Native Location (BD09): " + mMarkLatLngMap);
        }
    }

    //地图上各按键的监听
    private void initMapButton() {
        RadioGroup mGroupMapType = this.findViewById(R.id.RadioGroupMapType);
        mGroupMapType.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.mapNormal) {
                mMapView.setMapType(KeylessMapView.MAP_TYPE_NORMAL);
            }

            if (checkedId == R.id.mapSatellite) {
                mMapView.setMapType(KeylessMapView.MAP_TYPE_SATELLITE);
            }
        });

        ImageButton savePosBtn = this.findViewById(R.id.save_pos);
        if (savePosBtn != null) {
            savePosBtn.setOnClickListener(v -> openSaveCurrentLocationDialog());
        }

        ImageButton savedPosListBtn = this.findViewById(R.id.saved_pos_list);
        if (savedPosListBtn != null) {
            savedPosListBtn.setOnClickListener(v -> showSavedLocationsDialog());
        }

        ImageButton curPosBtn = this.findViewById(R.id.cur_position);
        curPosBtn.setOnClickListener(v -> resetMap());

        ImageButton zoomInBtn = this.findViewById(R.id.zoom_in);
        zoomInBtn.setOnClickListener(v -> mMapView.zoomIn());

        ImageButton zoomOutBtn = this.findViewById(R.id.zoom_out);
        zoomOutBtn.setOnClickListener(v -> mMapView.zoomOut());

        ImageButton inputPosBtn = this.findViewById(R.id.input_pos);
        inputPosBtn.setOnClickListener(v -> {
            AlertDialog dialog;
            AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
            builder.setTitle("请输入经度和纬度");
            View view = LayoutInflater.from(MainActivity.this).inflate(R.layout.location_input, null);
            builder.setView(view);
            dialog = builder.show();

            EditText dialog_lng = view.findViewById(R.id.joystick_longitude);
            EditText dialog_lat = view.findViewById(R.id.joystick_latitude);
            RadioButton rbBD = view.findViewById(R.id.pos_type_bd);

            Button btnGo = view.findViewById(R.id.input_position_ok);
            btnGo.setOnClickListener(v2 -> {
                String dialog_lng_str = dialog_lng.getText().toString();
                String dialog_lat_str = dialog_lat.getText().toString();

                if (TextUtils.isEmpty(dialog_lng_str) || TextUtils.isEmpty(dialog_lat_str)) {
                    GoUtils.DisplayToast(MainActivity.this,getResources().getString(R.string.app_error_input));
                } else {
                    double dialog_lng_double = Double.parseDouble(dialog_lng_str);
                    double dialog_lat_double = Double.parseDouble(dialog_lat_str);

                    if (dialog_lng_double > 180.0 || dialog_lng_double < -180.0) {
                        GoUtils.DisplayToast(MainActivity.this,  getResources().getString(R.string.app_error_longitude));
                    } else {
                        if (dialog_lat_double > 90.0 || dialog_lat_double < -90.0) {
                            GoUtils.DisplayToast(MainActivity.this,  getResources().getString(R.string.app_error_latitude));
                        } else {
                            if (rbBD.isChecked()) {
                                mMarkLatLngMap = new LatLng(dialog_lat_double, dialog_lng_double);
                            } else {
                                double[] bdLonLat = MapUtils.wgs2bd09(dialog_lng_double, dialog_lat_double);
                                mMarkLatLngMap = new LatLng(bdLonLat[1], bdLonLat[0]);
                            }
                            mMarkName = "手动输入的坐标";

                            markMap();
                            mMapView.setCenterBd09(mMarkLatLngMap);

                            dialog.dismiss();
                        }
                    }
                }
            });

            Button btnCancel = view.findViewById(R.id.input_position_cancel);
            btnCancel.setOnClickListener(v1 -> dialog.dismiss());
        });
    }

    private static void showPoiInfoWindow() {
        if (mMapView != null && mMarkLatLngMap != null && mPoiView != null) {
            if (mPoiLatitude != null) {
                mPoiLatitude.setText(String.valueOf(mMarkLatLngMap.latitude));
            }
            if (mPoiLongitude != null) {
                mPoiLongitude.setText(String.valueOf(mMarkLatLngMap.longitude));
            }
            if (mPoiAddress != null) {
                mPoiAddress.setText(TextUtils.isEmpty(mMarkName) ? "已选位置" : mMarkName);
            }
            mMapView.showInfoWindow(mPoiView, mMarkLatLngMap);
        }
    }

    // 弹出保存位置对话框（支持自定义保存名称并保存多个位置）
    private void openSaveCurrentLocationDialog() {
        if (mMarkLatLngMap == null && mMapView != null) {
            mMarkLatLngMap = mMapView.getMapCenterBd09();
            markMap();
        }
        final double defaultLng = mMarkLatLngMap != null ? mMarkLatLngMap.longitude : (mCurrentLon != 0.0 ? mCurrentLon : 116.404);
        final double defaultLat = mMarkLatLngMap != null ? mMarkLatLngMap.latitude : (mCurrentLat != 0.0 ? mCurrentLat : 39.915);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (18 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad / 2, pad, 0);

        TextView nameLabel = new TextView(this);
        nameLabel.setText(R.string.label_location_name);
        layout.addView(nameLabel);

        final EditText nameInput = new EditText(this);
        nameInput.setHint(R.string.hint_location_name);
        nameInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        if (!TextUtils.isEmpty(mMarkName) && !mMarkName.startsWith("地图选点")) {
            nameInput.setText(mMarkName);
            nameInput.setSelection(mMarkName.length());
        }
        layout.addView(nameInput);

        TextView coordLabel = new TextView(this);
        coordLabel.setText("经纬度坐标 (BD09)");
        coordLabel.setPadding(0, pad / 2, 0, 0);
        layout.addView(coordLabel);

        final EditText lngInput = new EditText(this);
        lngInput.setHint(R.string.label_longitude);
        lngInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
        lngInput.setText(String.valueOf(defaultLng));
        layout.addView(lngInput);

        final EditText latInput = new EditText(this);
        latInput.setHint(R.string.label_latitude);
        latInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
        latInput.setText(String.valueOf(defaultLat));
        layout.addView(latInput);

        new AlertDialog.Builder(this)
                .setTitle(R.string.save_location_title)
                .setView(layout)
                .setPositiveButton("保存", (dialog, which) -> {
                    String customName = nameInput.getText().toString().trim();
                    String lngStr = lngInput.getText().toString().trim();
                    String latStr = latInput.getText().toString().trim();
                    if (TextUtils.isEmpty(lngStr) || TextUtils.isEmpty(latStr)) {
                        GoUtils.DisplayToast(MainActivity.this, getResources().getString(R.string.app_error_input));
                        return;
                    }
                    try {
                        double bdLng = Double.parseDouble(lngStr);
                        double bdLat = Double.parseDouble(latStr);
                        if (bdLng > 180.0 || bdLng < -180.0) {
                            GoUtils.DisplayToast(MainActivity.this, getResources().getString(R.string.app_error_longitude));
                            return;
                        }
                        if (bdLat > 90.0 || bdLat < -90.0) {
                            GoUtils.DisplayToast(MainActivity.this, getResources().getString(R.string.app_error_latitude));
                            return;
                        }
                        if (TextUtils.isEmpty(customName)) {
                            customName = String.format(java.util.Locale.US, "收藏位置 (%.4f, %.4f)", bdLng, bdLat);
                        }
                        double[] wgs = MapUtils.bd2wgs(bdLng, bdLat);
                        DataBaseHistoryLocation.saveNamedLocation(mLocationHistoryDB, customName, wgs[0], wgs[1], bdLng, bdLat);
                        mMarkName = customName;
                        mMarkLatLngMap = new LatLng(bdLat, bdLng);
                        markMap();
                        mMapView.setCenterBd09(mMarkLatLngMap);
                        GoUtils.DisplayToast(MainActivity.this, "位置已保存：" + customName);
                    } catch (NumberFormatException e) {
                        GoUtils.DisplayToast(MainActivity.this, getResources().getString(R.string.app_error_input));
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // 弹出已保存位置列表对话框，可直接点击读取任意保存的位置，或长按重命名/删除
    private void showSavedLocationsDialog() {
        final List<Map<String, Object>> savedList = new ArrayList<>();
        try {
            Cursor cursor = mLocationHistoryDB.query(
                    DataBaseHistoryLocation.TABLE_NAME,
                    null,
                    DataBaseHistoryLocation.DB_COLUMN_ID + " > ?",
                    new String[]{"0"},
                    null, null,
                    DataBaseHistoryLocation.DB_COLUMN_ID + " DESC",
                    null
            );
            while (cursor.moveToNext()) {
                Map<String, Object> item = new HashMap<>();
                int id = cursor.getInt(0);
                String name = cursor.getString(1);
                String wgsLng = cursor.getString(2);
                String wgsLat = cursor.getString(3);
                long timestamp = cursor.getLong(4);
                String bdLng = cursor.getString(5);
                String bdLat = cursor.getString(6);

                double dWgsLng = java.math.BigDecimal.valueOf(Double.parseDouble(wgsLng)).setScale(6, java.math.RoundingMode.HALF_UP).doubleValue();
                double dWgsLat = java.math.BigDecimal.valueOf(Double.parseDouble(wgsLat)).setScale(6, java.math.RoundingMode.HALF_UP).doubleValue();

                item.put(HistoryActivity.KEY_ID, Integer.toString(id));
                item.put(HistoryActivity.KEY_LOCATION, TextUtils.isEmpty(name) ? getResources().getString(R.string.history_location_default_name) : name);
                item.put(HistoryActivity.KEY_TIME, GoUtils.timeStamp2Date(Long.toString(timestamp)));
                item.put(HistoryActivity.KEY_LNG_LAT_WGS, "[经度:" + dWgsLng + " 纬度:" + dWgsLat + "]");
                item.put(HistoryActivity.KEY_LNG_LAT_CUSTOM, bdLng + "," + bdLat);
                savedList.add(item);
            }
            cursor.close();
        } catch (Exception e) {
            XLog.e("ERROR: query saved locations");
        }

        if (savedList.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.saved_locations_title)
                    .setMessage(R.string.saved_locations_empty)
                    .setPositiveButton("保存当前位置", (d, w) -> openSaveCurrentLocationDialog())
                    .setNegativeButton("关闭", null)
                    .show();
            return;
        }

        ListView listView = new ListView(this);
        SimpleAdapter adapter = new SimpleAdapter(
                this,
                savedList,
                R.layout.history_item,
                new String[]{HistoryActivity.KEY_ID, HistoryActivity.KEY_LOCATION, HistoryActivity.KEY_TIME, HistoryActivity.KEY_LNG_LAT_WGS, HistoryActivity.KEY_LNG_LAT_CUSTOM},
                new int[]{R.id.LocationID, R.id.LocationText, R.id.TimeText, R.id.WGSLatLngText, R.id.BDLatLngText}
        );
        listView.setAdapter(adapter);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("点击读取已保存位置（长按可改名/删除）")
                .setView(listView)
                .setPositiveButton("新增保存", (d, w) -> openSaveCurrentLocationDialog())
                .setNeutralButton("管理全部", (d, w) -> {
                    Intent intent = new Intent(MainActivity.this, HistoryActivity.class);
                    startActivity(intent);
                })
                .setNegativeButton("关闭", null)
                .create();

        listView.setOnItemClickListener((parent, view, position, id) -> {
            Map<String, Object> selected = savedList.get(position);
            String name = String.valueOf(selected.get(HistoryActivity.KEY_LOCATION));
            String bdCoord = String.valueOf(selected.get(HistoryActivity.KEY_LNG_LAT_CUSTOM));
            String[] parts = bdCoord.split(",");
            if (parts.length == 2) {
                String bdLng = parts[0];
                String bdLat = parts[1];
                if (showLocation(name, bdLng, bdLat)) {
                    if (isMockServStart && mServiceBinder != null) {
                        double[] wgs = MapUtils.bd2wgs(Double.parseDouble(bdLng), Double.parseDouble(bdLat));
                        double alt = Double.parseDouble(sharedPreferences.getString("setting_altitude", "55.0"));
                        mServiceBinder.setPosition(wgs[0], wgs[1], alt);
                        GoUtils.DisplayToast(MainActivity.this, "已读取并传送至：" + name);
                    } else {
                        GoUtils.DisplayToast(MainActivity.this, "已读取位置：" + name);
                    }
                }
            }
            dialog.dismiss();
        });

        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            Map<String, Object> selected = savedList.get(position);
            final String locId = String.valueOf(selected.get(HistoryActivity.KEY_ID));
            final String curName = String.valueOf(selected.get(HistoryActivity.KEY_LOCATION));
            String[] actions = new String[]{"修改名称", "删除该位置"};
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle(curName)
                    .setItems(actions, (actionDialog, whichAction) -> {
                        if (whichAction == 0) {
                            final EditText input = new EditText(MainActivity.this);
                            input.setText(curName);
                            input.setSelection(curName.length());
                            new AlertDialog.Builder(MainActivity.this)
                                    .setTitle("修改保存名称")
                                    .setView(input)
                                    .setPositiveButton("保存", (d2, w2) -> {
                                        String newName = input.getText().toString().trim();
                                        if (!TextUtils.isEmpty(newName)) {
                                            DataBaseHistoryLocation.updateHistoryLocation(mLocationHistoryDB, locId, newName);
                                            dialog.dismiss();
                                            showSavedLocationsDialog();
                                        }
                                    })
                                    .setNegativeButton("取消", null)
                                    .show();
                        } else if (whichAction == 1) {
                            mLocationHistoryDB.delete(DataBaseHistoryLocation.TABLE_NAME, DataBaseHistoryLocation.DB_COLUMN_ID + " = ?", new String[]{locId});
                            GoUtils.DisplayToast(MainActivity.this, getResources().getString(R.string.history_delete_ok));
                            dialog.dismiss();
                            showSavedLocationsDialog();
                        }
                    })
                    .show();
            return true;
        });

        dialog.show();
    }

    //标定选择的位置
    private void markMap() {
        if (mMarkLatLngMap != null && mMapView != null) {
            mMapView.clear();
            mMapView.setMarkerBd09(mMarkLatLngMap);
            showPoiInfoWindow();
        }
    }

    private void resetMap() {
        if (mMapView != null) {
            mMapView.clear();
            mMarkLatLngMap = null;
            mMapView.setMyLocationBd09(mCurrentLat, mCurrentLon, mCurrentDirection);
            mMapView.setTargetAndZoomBd09(new LatLng(mCurrentLat, mCurrentLon), 16.0);
        }
    }

    // 在地图上显示位置
    public static boolean showLocation(String name, String bd09Longitude, String bd09Latitude) {
        boolean ret = true;

        try {
            if (!bd09Longitude.isEmpty() && !bd09Latitude.isEmpty() && mMapView != null) {
                mMarkName = name;
                mMarkLatLngMap = new LatLng(Double.parseDouble(bd09Latitude), Double.parseDouble(bd09Longitude));
                mMapView.clear();
                mMapView.setMarkerBd09(mMarkLatLngMap);
                mMapView.setCenterBd09(mMarkLatLngMap);
                showPoiInfoWindow();
            }
        } catch (Exception e) {
            ret = false;
            XLog.e("ERROR: showHistoryLocation");
        }

        return ret;
    }

    private void initGoBtn() {
        mButtonStart = findViewById(R.id.faBtnStart);
        mButtonStart.setOnClickListener(this::doGoLocation);
    }

    private void startGoLocation() {
        Intent serviceGoIntent = new Intent(MainActivity.this, ServiceGo.class);
        bindService(serviceGoIntent, mConnection, BIND_AUTO_CREATE);    // 绑定服务和活动，之后活动就可以去调服务的方法了
        double[] latLng = MapUtils.bd2wgs(mMarkLatLngMap.longitude, mMarkLatLngMap.latitude);
        serviceGoIntent.putExtra(LNG_MSG_ID, latLng[0]);
        serviceGoIntent.putExtra(LAT_MSG_ID, latLng[1]);
        double alt = Double.parseDouble(sharedPreferences.getString("setting_altitude", "55.0"));
        serviceGoIntent.putExtra(ALT_MSG_ID, alt);

        startForegroundService(serviceGoIntent);
        XLog.d("startForegroundService: ServiceGo");

        isMockServStart = true;
    }

    private void stopGoLocation() {
        unbindService(mConnection); // 解绑服务，服务要记得解绑，不要造成内存泄漏
        Intent serviceGoIntent = new Intent(MainActivity.this, ServiceGo.class);
        stopService(serviceGoIntent);
        isMockServStart = false;
    }

    private void doGoLocation(View v) {
        if (!GoUtils.isNetworkAvailable(this)) {
            GoUtils.DisplayToast(this, getResources().getString(R.string.app_error_network));
            return;
        }

        if (!GoUtils.isGpsOpened(this)) {
            GoUtils.showEnableGpsDialog(this);
            return;
        }

        if (!Settings.canDrawOverlays(getApplicationContext())) {//悬浮窗权限判断
            GoUtils.showEnableFloatWindowDialog(this);
            XLog.e("无悬浮窗权限!");
            return;
        }

        if (isMockServStart) {
            if (mMarkLatLngMap == null) {
                stopGoLocation();
                Snackbar.make(v, "模拟位置已终止", Snackbar.LENGTH_LONG)
                        .setAction("Action", null).show();
                mButtonStart.setImageResource(R.drawable.ic_position);
            } else {
                double[] latLng = MapUtils.bd2wgs(mMarkLatLngMap.longitude, mMarkLatLngMap.latitude);
                double alt = Double.parseDouble(sharedPreferences.getString("setting_altitude", "55.0"));
                mServiceBinder.setPosition(latLng[0], latLng[1], alt);
                Snackbar.make(v, "已传送到新位置", Snackbar.LENGTH_LONG)
                        .setAction("Action", null).show();

                recordCurrentLocation(mMarkLatLngMap.longitude, mMarkLatLngMap.latitude);

                if (mMapView != null) {
                    mMapView.clear();
                }
                mMarkLatLngMap = null;

                if (GoUtils.isWifiEnabled(MainActivity.this)) {
                    GoUtils.showDisableWifiDialog(MainActivity.this);
                }
            }
        } else {
            if (!GoUtils.isAllowMockLocation(this)) {
                GoUtils.showEnableMockLocationDialog(this);
                XLog.e("无模拟位置权限!");
            } else {
                if (mMarkLatLngMap == null) {
                    Snackbar.make(v, "请先点击地图位置或者搜索位置", Snackbar.LENGTH_LONG)
                            .setAction("Action", null).show();
                } else {
                    startGoLocation();
                    mButtonStart.setImageResource(R.drawable.ic_fly);
                    Snackbar.make(v, "模拟位置已启动", Snackbar.LENGTH_LONG)
                            .setAction("Action", null).show();

                    recordCurrentLocation(mMarkLatLngMap.longitude, mMarkLatLngMap.latitude);
                    if (mMapView != null) {
                        mMapView.clear();
                    }
                    mMarkLatLngMap = null;

                    if (GoUtils.isWifiEnabled(MainActivity.this)) {
                        GoUtils.showDisableWifiDialog(MainActivity.this);
                    }
                }
            }
        }
    }

    /*============================== 历史记录 相关 ==============================*/
    private void initStoreHistory() {
        try {
            // 定位历史
            DataBaseHistoryLocation dbLocation = new DataBaseHistoryLocation(getApplicationContext());
            mLocationHistoryDB = dbLocation.getWritableDatabase();
            // 搜索历史
            DataBaseHistorySearch dbHistory = new DataBaseHistorySearch(getApplicationContext());
            mSearchHistoryDB = dbHistory.getWritableDatabase();
        } catch (Exception e) {
            XLog.e("ERROR: sqlite init error");
        }
    }

    //获取查询历史
    private List<Map<String, Object>> getSearchHistory() {
        List<Map<String, Object>> data = new ArrayList<>();

        try {
            Cursor cursor = mSearchHistoryDB.query(DataBaseHistorySearch.TABLE_NAME, null,
                    DataBaseHistorySearch.DB_COLUMN_ID + " > ?", new String[] {"0"},
                    null, null, DataBaseHistorySearch.DB_COLUMN_TIMESTAMP + " DESC", null);

            while (cursor.moveToNext()) {
                Map<String, Object> searchHistoryItem = new HashMap<>();
                searchHistoryItem.put(DataBaseHistorySearch.DB_COLUMN_KEY, cursor.getString(1));
                searchHistoryItem.put(DataBaseHistorySearch.DB_COLUMN_DESCRIPTION, cursor.getString(2));
                searchHistoryItem.put(DataBaseHistorySearch.DB_COLUMN_TIMESTAMP, "" + cursor.getInt(3));
                searchHistoryItem.put(DataBaseHistorySearch.DB_COLUMN_IS_LOCATION, "" + cursor.getInt(4));
                searchHistoryItem.put(DataBaseHistorySearch.DB_COLUMN_LONGITUDE_CUSTOM, cursor.getString(7));
                searchHistoryItem.put(DataBaseHistorySearch.DB_COLUMN_LATITUDE_CUSTOM, cursor.getString(8));
                data.add(searchHistoryItem);
            }
            cursor.close();
        } catch (Exception e) {
            XLog.e("ERROR: getSearchHistory");
        }

        return data;
    }

    // 记录请求的位置信息（免 Key 逆地理编码）
    private void recordCurrentLocation(double lng, double lat) {
        final double[] latLng = MapUtils.bd2wgs(lng, lat);
        final String fallbackName = !TextUtils.isEmpty(mMarkName) ? mMarkName : getResources().getString(R.string.history_location_default_name);
        NominatimHelper.reverseGeocodeBd09(lng, lat, address -> {
            String resolvedName = !TextUtils.isEmpty(address) ? address : fallbackName;
            ContentValues contentValues = new ContentValues();
            contentValues.put(DataBaseHistoryLocation.DB_COLUMN_LOCATION, resolvedName);
            contentValues.put(DataBaseHistoryLocation.DB_COLUMN_LONGITUDE_WGS84, String.valueOf(latLng[0]));
            contentValues.put(DataBaseHistoryLocation.DB_COLUMN_LATITUDE_WGS84, String.valueOf(latLng[1]));
            contentValues.put(DataBaseHistoryLocation.DB_COLUMN_TIMESTAMP, System.currentTimeMillis() / 1000);
            contentValues.put(DataBaseHistoryLocation.DB_COLUMN_LONGITUDE_CUSTOM, Double.toString(lng));
            contentValues.put(DataBaseHistoryLocation.DB_COLUMN_LATITUDE_CUSTOM, Double.toString(lat));
            DataBaseHistoryLocation.saveHistoryLocation(mLocationHistoryDB, contentValues);
        });
    }

    /*============================== SearchView 相关 ==============================*/
    private void initSearchView() {
        mSearchLayout = findViewById(R.id.search_linear);
        mHistoryLayout = findViewById(R.id.search_history_linear);

        mSearchList = findViewById(R.id.search_list_view);
        mSearchList.setOnItemClickListener((parent, view, position, id) -> {
            String lng = ((TextView) view.findViewById(R.id.poi_longitude)).getText().toString();
            String lat = ((TextView) view.findViewById(R.id.poi_latitude)).getText().toString();
            mMarkName = ((TextView) view.findViewById(R.id.poi_name)).getText().toString();
            mMarkLatLngMap = new LatLng(Double.parseDouble(lat), Double.parseDouble(lng));
            if (mMapView != null) {
                mMapView.setCenterBd09(mMarkLatLngMap);
            }

            markMap();

            double[] latLng = MapUtils.bd2wgs(mMarkLatLngMap.longitude, mMarkLatLngMap.latitude);

            //搜索历史 插表参数
            ContentValues contentValues = new ContentValues();
            contentValues.put(DataBaseHistorySearch.DB_COLUMN_KEY, mMarkName);
            contentValues.put(DataBaseHistorySearch.DB_COLUMN_DESCRIPTION, ((TextView) view.findViewById(R.id.poi_address)).getText().toString());
            contentValues.put(DataBaseHistorySearch.DB_COLUMN_IS_LOCATION, DataBaseHistorySearch.DB_SEARCH_TYPE_RESULT);
            contentValues.put(DataBaseHistorySearch.DB_COLUMN_LONGITUDE_CUSTOM, lng);
            contentValues.put(DataBaseHistorySearch.DB_COLUMN_LATITUDE_CUSTOM, lat);
            contentValues.put(DataBaseHistorySearch.DB_COLUMN_LONGITUDE_WGS84, String.valueOf(latLng[0]));
            contentValues.put(DataBaseHistorySearch.DB_COLUMN_LATITUDE_WGS84, String.valueOf(latLng[1]));
            contentValues.put(DataBaseHistorySearch.DB_COLUMN_TIMESTAMP, System.currentTimeMillis() / 1000);

            DataBaseHistorySearch.saveHistorySearch(mSearchHistoryDB, contentValues);
            mSearchLayout.setVisibility(View.INVISIBLE);
            searchItem.collapseActionView();
        });
        //搜索历史列表的点击监听
        mSearchHistoryList = findViewById(R.id.search_history_list_view);
        mSearchHistoryList.setOnItemClickListener((parent, view, position, id) -> {
            String searchDescription = ((TextView) view.findViewById(R.id.search_description)).getText().toString();
            String searchKey = ((TextView) view.findViewById(R.id.search_key)).getText().toString();
            String searchIsLoc = ((TextView) view.findViewById(R.id.search_isLoc)).getText().toString();

            //如果是定位搜索
            if (searchIsLoc.equals("1")) {
                String lng = ((TextView) view.findViewById(R.id.search_longitude)).getText().toString();
                String lat = ((TextView) view.findViewById(R.id.search_latitude)).getText().toString();
                mMarkName = searchKey;
                mMarkLatLngMap = new LatLng(Double.parseDouble(lat), Double.parseDouble(lng));
                if (mMapView != null) {
                    mMapView.setCenterBd09(mMarkLatLngMap);
                }

                markMap();

                double[] latLng = MapUtils.bd2wgs(mMarkLatLngMap.longitude, mMarkLatLngMap.latitude);

                //设置列表不可见
                mHistoryLayout.setVisibility(View.INVISIBLE);
                searchItem.collapseActionView();
                //更新表
                ContentValues contentValues = new ContentValues();
                contentValues.put(DataBaseHistorySearch.DB_COLUMN_KEY, searchKey);
                contentValues.put(DataBaseHistorySearch.DB_COLUMN_DESCRIPTION, searchDescription);
                contentValues.put(DataBaseHistorySearch.DB_COLUMN_IS_LOCATION, DataBaseHistorySearch.DB_SEARCH_TYPE_RESULT);
                contentValues.put(DataBaseHistorySearch.DB_COLUMN_LONGITUDE_CUSTOM, lng);
                contentValues.put(DataBaseHistorySearch.DB_COLUMN_LATITUDE_CUSTOM, lat);
                contentValues.put(DataBaseHistorySearch.DB_COLUMN_LONGITUDE_WGS84, String.valueOf(latLng[0]));
                contentValues.put(DataBaseHistorySearch.DB_COLUMN_LATITUDE_WGS84, String.valueOf(latLng[1]));
                contentValues.put(DataBaseHistorySearch.DB_COLUMN_TIMESTAMP, System.currentTimeMillis() / 1000);

                DataBaseHistorySearch.saveHistorySearch(mSearchHistoryDB, contentValues);
            } else if (searchIsLoc.equals("0")) { //如果仅仅是搜索
                try {
                    searchView.setQuery(searchKey, true);
                } catch (Exception e) {
                    GoUtils.DisplayToast(this, getResources().getString(R.string.app_error_search));
                    XLog.e(getResources().getString(R.string.app_error_search));
                }
            } else {
                XLog.e(getResources().getString(R.string.app_error_param));
            }
        });
        mSearchHistoryList.setOnItemLongClickListener((parent, view, position, id) -> {
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle("警告")//这里是表头的内容
                    .setMessage("确定要删除该项搜索记录吗?")//这里是中间显示的具体信息
                    .setPositiveButton("确定",(dialog, which) -> {
                        String searchKey = ((TextView) view.findViewById(R.id.search_key)).getText().toString();

                        try {
                            mSearchHistoryDB.delete(DataBaseHistorySearch.TABLE_NAME, DataBaseHistorySearch.DB_COLUMN_KEY + " = ?", new String[] {searchKey});
                            //删除成功
                            //展示搜索历史
                            List<Map<String, Object>> data = getSearchHistory();

                            if (!data.isEmpty()) {
                                SimpleAdapter simAdapt = new SimpleAdapter(
                                        MainActivity.this,
                                        data,
                                        R.layout.search_item,
                                        new String[] {DataBaseHistorySearch.DB_COLUMN_KEY,
                                                DataBaseHistorySearch.DB_COLUMN_DESCRIPTION,
                                                DataBaseHistorySearch.DB_COLUMN_TIMESTAMP,
                                                DataBaseHistorySearch.DB_COLUMN_IS_LOCATION,
                                                DataBaseHistorySearch.DB_COLUMN_LONGITUDE_CUSTOM,
                                                DataBaseHistorySearch.DB_COLUMN_LATITUDE_CUSTOM}, // 与下面数组元素要一一对应
                                        new int[] {R.id.search_key, R.id.search_description, R.id.search_timestamp, R.id.search_isLoc, R.id.search_longitude, R.id.search_latitude});
                                mSearchHistoryList.setAdapter(simAdapt);
                                mHistoryLayout.setVisibility(View.VISIBLE);
                            }
                        } catch (Exception e) {
                            XLog.e("ERROR: delete database error");
                            GoUtils.DisplayToast(MainActivity.this,getResources().getString(R.string.history_delete_error));
                        }
                    })
                    .setNegativeButton("取消",
                            (dialog, which) -> {
                            })
                    .show();
            return true;
        });
    }

    private void performPlaceSearch(String keyword) {
        NominatimHelper.searchPlaces(keyword, results -> {
            if (results == null || results.isEmpty()) {
                return;
            }
            SimpleAdapter simAdapt = new SimpleAdapter(
                    MainActivity.this,
                    results,
                    R.layout.search_poi_item,
                    new String[] {POI_NAME, POI_ADDRESS, POI_LONGITUDE, POI_LATITUDE},
                    new int[] {R.id.poi_name, R.id.poi_address, R.id.poi_longitude, R.id.poi_latitude});
            mSearchList.setAdapter(simAdapt);
            mSearchLayout.setVisibility(View.VISIBLE);
        });
    }
}
