package com.hg.vibmeter;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.UUID;

/**
 * HG 진동 캡슐(M5Capsule) 블루투스 연결.
 * 캡슐이 보내는 가속도 원본(약 800Hz)을 받아 폰 센서와 같은 형태(시각 ns, m/s²)로 넘겨준다.
 */
@SuppressLint("MissingPermission")
final class CapsuleLink {

    static final UUID SVC = UUID.fromString("4e8f0001-6b2a-4c1e-9d3a-5a17c0de0001");
    static final UUID RAW = UUID.fromString("4e8f0002-6b2a-4c1e-9d3a-5a17c0de0001");
    static final UUID CTL = UUID.fromString("4e8f0003-6b2a-4c1e-9d3a-5a17c0de0001");
    static final UUID STA = UUID.fromString("4e8f0004-6b2a-4c1e-9d3a-5a17c0de0001");
    static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    static final double G = 9.80665, LSB = 4096.0;   // ±8g

    interface Listener {
        /** 다른 스레드에서 불림 */
        void onCapsuleSamples(long[] tNs, float[] x, float[] y, float[] z, int n);
        /** 메인 스레드 */
        void onCapsuleState(int state, String msg);
    }
    static final int OFF = 0, SCAN = 1, CONNECTING = 2, ON = 3, NOPERM = 4, NOBT = 5;

    private final Activity a;
    private final Listener l;
    private final Handler h = new Handler(Looper.getMainLooper());
    private BluetoothAdapter ad;
    private BluetoothLeScanner sc;
    private BluetoothGatt gatt;
    private boolean running, scanning;
    int state = OFF;
    String name = "", status = "";
    int battery = -1;
    double fs = Double.NaN;
    long packets, lost;
    private long lastIdx = -1, anIdx = -1; private double anT, anFs;
    private final ArrayDeque<Runnable> ops = new ArrayDeque<>();
    private boolean opBusy;

    // 축 맞추기: 중력이 가장 큰 축 = Z(설비 위아래)
    private int zAxis = -1, xAxis = 0, yAxis = 1;
    private final double[] gsum = new double[3];
    private int gcount;

    CapsuleLink(Activity a, Listener l) {
        this.a = a; this.l = l;
        BluetoothManager bm = (BluetoothManager) a.getSystemService(Context.BLUETOOTH_SERVICE);
        ad = bm != null ? bm.getAdapter() : null;
    }

    static String[] perms() {
        return Build.VERSION.SDK_INT >= 31
                ? new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}
                : new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }
    boolean hasPerm() {
        for (String p : perms()) if (a.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }

    void start() {
        running = true;
        if (!hasPerm()) { a.requestPermissions(perms(), 41); set(NOPERM, "블루투스 권한이 필요합니다"); return; }
        if (ad == null || !ad.isEnabled()) { set(NOBT, "폰 블루투스를 켜 주세요"); h.postDelayed(retry, 3000); return; }
        if (gatt != null) return;
        String addr = Store.getStr(a, "capAddr", null);
        if (addr != null) connect(ad.getRemoteDevice(addr));
        else scan();
    }

    void stop() {
        running = false;
        h.removeCallbacksAndMessages(null);
        stopScan();
        if (gatt != null) {
            try { BluetoothGattCharacteristic c = ch(CTL); if (c != null) { c.setValue("S0".getBytes()); gatt.writeCharacteristic(c); } } catch (Exception ignored) {}
            final BluetoothGatt g = gatt;
            gatt = null;
            h.postDelayed(() -> { try { g.disconnect(); g.close(); } catch (Exception ignored) {} }, 150);
        }
        ops.clear(); opBusy = false;
        set(OFF, "");
    }

    /** 다른 캡슐로 바꾸기 */
    void forget() {
        Store.putStr(a, "capAddr", null);
        boolean r = running;
        stop();
        if (r) start();
    }

    void beep() { BluetoothGattCharacteristic c = ch(CTL); if (c != null) op(() -> { c.setValue("B".getBytes()); gatt.writeCharacteristic(c); }); }

    /** 측정 화면이 다시 시작할 때 축·번호 초기화 */
    void resetAxes() { zAxis = -1; gsum[0] = gsum[1] = gsum[2] = 0; gcount = 0; lastIdx = -1; anIdx = -1; }

    private final Runnable retry = () -> { if (running && gatt == null) start(); };

    private void scan() {
        if (scanning) return;
        sc = ad.getBluetoothLeScanner();
        if (sc == null) { set(NOBT, "블루투스를 켜 주세요"); h.postDelayed(retry, 3000); return; }
        set(SCAN, "캡슐 찾는 중… (캡슐 전원을 켜 두세요)");
        ScanFilter f = new ScanFilter.Builder().setServiceUuid(new ParcelUuid(SVC)).build();
        ScanSettings s = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        scanning = true;
        sc.startScan(Collections.singletonList(f), s, scb);
        h.postDelayed(() -> { if (scanning) { stopScan(); set(SCAN, "캡슐을 찾지 못했습니다 · 다시 찾는 중"); h.postDelayed(retry, 2000); } }, 15000);
    }
    private void stopScan() { if (scanning && sc != null) { try { sc.stopScan(scb); } catch (Exception ignored) {} } scanning = false; }

    private final ScanCallback scb = new ScanCallback() {
        @Override public void onScanResult(int t, ScanResult r) {
            if (!scanning) return;
            stopScan();
            h.post(() -> connect(r.getDevice()));
        }
    };

    private void connect(BluetoothDevice d) {
        if (!running) return;
        String n = null; try { n = d.getName(); } catch (Exception ignored) {}
        name = n != null ? n : d.getAddress();
        set(CONNECTING, name + " 연결 중…");
        gatt = d.connectGatt(a, false, cb, BluetoothDevice.TRANSPORT_LE);
        // 12초 안에 안 되면 다시
        h.postDelayed(() -> { if (running && state == CONNECTING) { drop(); } }, 12000);
    }

    private void drop() {
        if (gatt != null) { try { gatt.close(); } catch (Exception ignored) {} gatt = null; }
        ops.clear(); opBusy = false;
        if (running) { set(SCAN, "연결 끊김 · 다시 연결 중…"); h.postDelayed(retry, 2000); }
    }

    private BluetoothGattCharacteristic ch(UUID u) {
        if (gatt == null) return null;
        BluetoothGattService s = gatt.getService(SVC);
        return s != null ? s.getCharacteristic(u) : null;
    }

    // GATT 작업은 하나씩
    private void op(Runnable r) { h.post(() -> { ops.add(r); next(); }); }
    private void next() { if (opBusy || ops.isEmpty() || gatt == null) return; opBusy = true; try { ops.poll().run(); } catch (Exception e) { opBusy = false; next(); } }
    private void done() { h.post(() -> { opBusy = false; next(); }); }

    private void notifyOn(UUID u) {
        op(() -> {
            BluetoothGattCharacteristic c = ch(u);
            if (c == null) { done(); return; }
            gatt.setCharacteristicNotification(c, true);
            BluetoothGattDescriptor d = c.getDescriptor(CCCD);
            if (d == null) { done(); return; }
            d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            gatt.writeDescriptor(d);
        });
    }

    private final BluetoothGattCallback cb = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int st, int ns) {
            if (g != gatt) { try { g.close(); } catch (Exception ignored) {} return; }
            if (ns == BluetoothProfile.STATE_CONNECTED) {
                h.post(() -> {
                    g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
                    g.requestMtu(247);
                });
            } else if (ns == BluetoothProfile.STATE_DISCONNECTED) {
                h.post(CapsuleLink.this::drop);
            }
        }
        @Override public void onMtuChanged(BluetoothGatt g, int mtu, int st) { h.post(g::discoverServices); }
        @Override public void onServicesDiscovered(BluetoothGatt g, int st) {
            if (g.getService(SVC) == null) { h.post(() -> { set(SCAN, "진동 캡슐이 아닙니다"); Store.putStr(a, "capAddr", null); drop(); }); return; }
            Store.putStr(a, "capAddr", g.getDevice().getAddress());
            notifyOn(STA);
            notifyOn(RAW);
            op(() -> { BluetoothGattCharacteristic c = ch(STA); if (c != null) gatt.readCharacteristic(c); else done(); });
            op(() -> { BluetoothGattCharacteristic c = ch(CTL); if (c == null) { done(); return; } c.setValue("S1".getBytes()); c.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT); gatt.writeCharacteristic(c); });
            h.post(() -> { resetAxes(); set(ON, name + " 연결됨"); });
        }
        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int st) { done(); }
        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int st) { done(); }
        @Override public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int st) { handle(c.getUuid(), c.getValue()); done(); }
        @Override public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] v, int st) { handle(c.getUuid(), v); done(); }
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) { handle(c.getUuid(), c.getValue()); }
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] v) { handle(c.getUuid(), v); }
    };

    private void handle(UUID u, byte[] v) {
        if (v == null) return;
        if (STA.equals(u)) {
            String s = new String(v, StandardCharsets.UTF_8);
            for (String kv : s.split(";")) {
                String[] p = kv.split("=");
                if (p.length != 2) continue;
                try {
                    if (p[0].equals("bat")) battery = Integer.parseInt(p[1].trim());
                    else if (p[0].equals("fs")) { double f = Double.parseDouble(p[1].trim()); if (f > 100) fs = f; }
                } catch (Exception ignored) {}
            }
            status = s;
            h.post(() -> { if (state == ON) set(ON, name + " 연결됨"); });
            return;
        }
        if (!RAW.equals(u) || v.length < 10 || (v[0] & 0xff) != 0xA1) return;
        ByteBuffer b = ByteBuffer.wrap(v).order(ByteOrder.LITTLE_ENDIAN);
        b.get();
        int n = b.get() & 0xff;
        long idx = b.getInt() & 0xffffffffL;
        float f = b.getFloat();
        if (f > 100 && f < 4000) fs = f;
        if (v.length < 10 + n * 6) return;
        packets++;
        if (lastIdx >= 0 && idx > lastIdx) lost += (idx - lastIdx) / Math.max(1, n);
        if (lastIdx >= 0 && idx < lastIdx) resetAxes();   // 캡슐이 다시 시작됨
        lastIdx = idx + n;
        double rate = Double.isNaN(fs) ? 800 : fs;
        if (anIdx < 0) { anIdx = idx; anT = 0; anFs = rate; }
        else if (rate != anFs) { anT += (idx - anIdx) * 1e9 / anFs; anIdx = idx; anFs = rate; }
        long[] t = new long[n];
        float[] x = new float[n], y = new float[n], z = new float[n];
        float[] raw = new float[3];
        for (int i = 0; i < n; i++) {
            raw[0] = (float) (b.getShort() / LSB * G);
            raw[1] = (float) (b.getShort() / LSB * G);
            raw[2] = (float) (b.getShort() / LSB * G);
            if (zAxis < 0) {
                gsum[0] += raw[0]; gsum[1] += raw[1]; gsum[2] += raw[2]; gcount++;
                if (gcount >= 400) {   // 약 0.5초 평균으로 중력 방향 판단
                    int k = 0;
                    for (int j = 1; j < 3; j++) if (Math.abs(gsum[j]) > Math.abs(gsum[k])) k = j;
                    zAxis = k; xAxis = k == 0 ? 1 : 0; yAxis = k == 2 ? 1 : 2;
                }
            }
            int zi = zAxis < 0 ? 2 : zAxis, xi = zAxis < 0 ? 0 : xAxis, yi = zAxis < 0 ? 1 : yAxis;
            t[i] = (long) (anT + (idx + i - anIdx) * 1e9 / anFs);
            x[i] = raw[xi]; y[i] = raw[yi]; z[i] = raw[zi];
        }
        l.onCapsuleSamples(t, x, y, z, n);
    }

    private void set(int st, String msg) {
        state = st;
        h.removeCallbacks(notifyState);
        stMsg = msg;
        h.post(notifyState);
    }
    private String stMsg = "";
    private final Runnable notifyState = () -> l.onCapsuleState(state, stMsg);

    String axisName() { return zAxis < 0 ? "판단 중" : "XYZ".charAt(zAxis) + "축"; }
}
