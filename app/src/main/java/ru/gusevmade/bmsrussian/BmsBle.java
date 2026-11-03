package ru.gusevmade.bmsrussian;

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
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Поиск платы Daly, подключение по BLE и опрос живых данных. Только чтение. */
public class BmsBle {
    public interface Sink { void emit(String json); }

    private static final UUID SVC = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb");
    private static final UUID CH_1 = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb");
    private static final UUID CH_2 = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final String TARGET = "D018050108BD";
    /** Варианты запроса: сначала живые данные, потом запасные, если ответа нет. */
    private static final int[][] CANDIDATES = {{0, 62}, {0, 64}, {0, 45}, {128, 41}};

    private final Context ctx;
    private final Sink sink;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final BmsFrame frames = new BmsFrame();
    private final Set<String> seen = new HashSet<>();

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic wch, nch1, nch2;
    private boolean wantConnected = false;
    private boolean connected = false;
    private int candidate = 0;
    private int polls = 0;
    private int gotFrames = 0;
    private int rxLogged = 0;

    public BmsBle(Context ctx, Sink sink) {
        this.ctx = ctx;
        this.sink = sink;
    }

    // ---------- события для страницы ----------
    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }

    private void state(String text, boolean on) {
        sink.emit("{\"type\":\"state\",\"on\":" + on + ",\"text\":\"" + esc(text) + "\"}");
    }

    private void log(String line) {
        sink.emit("{\"type\":\"log\",\"line\":\"" + esc(line) + "\"}");
    }

    // ---------- поиск ----------
    public void start() {
        wantConnected = true;
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = bm == null ? null : bm.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            state("Bluetooth выключен", false);
            return;
        }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            state("Поиск недоступен", false);
            return;
        }
        seen.clear();
        state("Ищу батарею…", false);
        log("Поиск " + TARGET);
        try {
            scanner.startScan(scanCb);
        } catch (SecurityException e) {
            state("Нет разрешения Bluetooth", false);
            return;
        }
        main.postDelayed(scanTimeout, 25000);
    }

    private final Runnable scanTimeout = new Runnable() {
        @Override public void run() {
            stopScan();
            if (!connected && wantConnected) {
                state("Батарея не найдена. Включите её, держите рядом и закройте Smart BMS.", false);
                wantConnected = false;
            }
        }
    };

    private void stopScan() {
        main.removeCallbacks(scanTimeout);
        try {
            if (scanner != null) scanner.stopScan(scanCb);
        } catch (Exception ignored) {
        }
    }

    private final ScanCallback scanCb = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult r) {
            BluetoothDevice d = r.getDevice();
            ScanRecord rec = r.getScanRecord();
            String name = rec != null ? rec.getDeviceName() : null;
            if (name == null) {
                try { name = d.getName(); } catch (SecurityException ignored) { }
            }
            boolean svc = false;
            if (rec != null && rec.getServiceUuids() != null) {
                for (ParcelUuid u : rec.getServiceUuids()) if (SVC.equals(u.getUuid())) svc = true;
            }
            String key = d.getAddress();
            if (seen.add(key) && seen.size() <= 30) {
                log("Устройство: " + (name == null ? "(без имени)" : name) + " " + key + (svc ? " [FFF0]" : ""));
            }
            boolean match = name != null
                    && (name.equals(TARGET) || name.startsWith("D018") || name.toUpperCase().startsWith("DALY"));
            if (match || svc) {
                stopScan();
                connect(d);
            }
        }

        @Override public void onScanFailed(int code) {
            state("Ошибка поиска (" + code + ")", false);
        }
    };

    // ---------- подключение ----------
    private void connect(BluetoothDevice d) {
        state("Подключаюсь…", false);
        try {
            gatt = d.connectGatt(ctx, false, gattCb, BluetoothDevice.TRANSPORT_LE);
        } catch (SecurityException e) {
            state("Нет разрешения Bluetooth", false);
        }
    }

    public void stop() {
        wantConnected = false;
        stopScan();
        main.removeCallbacks(poll);
        connected = false;
        try {
            if (gatt != null) {
                gatt.disconnect();
                gatt.close();
            }
        } catch (Exception ignored) {
        }
        gatt = null;
        state("Не подключено", false);
    }

    private final BluetoothGattCallback gattCb = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("Соединение установлено");
                try {
                    if (!g.requestMtu(247)) g.discoverServices();
                } catch (SecurityException ignored) {
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("Соединение закрыто, статус " + status);
                connected = false;
                main.removeCallbacks(poll);
                try { g.close(); } catch (Exception ignored) { }
                gatt = null;
                if (wantConnected) {
                    state("Связь потеряна, пробую снова…", false);
                    main.postDelayed(new Runnable() {
                        @Override public void run() { if (wantConnected && gatt == null) start(); }
                    }, 6000);
                } else {
                    state("Не подключено", false);
                }
            }
        }

        @Override public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            log("MTU " + mtu);
            try { g.discoverServices(); } catch (SecurityException ignored) { }
        }

        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            List<BluetoothGattService> list = g.getServices();
            for (BluetoothGattService s : list) {
                log("Служба " + s.getUuid());
                for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                    log("  " + c.getUuid().toString().substring(4, 8) + " свойства " + c.getProperties());
                }
            }
            BluetoothGattService s = g.getService(SVC);
            if (s == null) {
                state("Служба FFF0 не найдена. Откройте «Ещё», «Связь с батареей» и отправьте мне список.", false);
                wantConnected = false;
                return;
            }
            nch1 = s.getCharacteristic(CH_1);
            nch2 = s.getCharacteristic(CH_2);
            wch = nch2 != null ? nch2 : nch1;
            if (wch == null) {
                state("Не нашёл канал записи", false);
                return;
            }
            enableNotify(g, nch1, true);
        }

        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            BluetoothGattCharacteristic c = d.getCharacteristic();
            log("Подписка " + c.getUuid().toString().substring(4, 8) + " статус " + status);
            if (c.equals(nch1) && nch2 != null && nch2 != nch1) {
                enableNotify(g, nch2, false);
            } else {
                begin();
            }
        }

        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            byte[] v = c.getValue();
            if (v == null) return;
            if (rxLogged < 25) {
                rxLogged++;
                log("RX " + c.getUuid().toString().substring(4, 8) + " " + v.length + " байт: " + BmsFrame.hex(v, 40));
            }
            for (int[] regs : frames.feed(v)) {
                gotFrames++;
                String json = BmsFrame.liveJson(regs);
                if (json != null) {
                    sink.emit(json);
                    if (gotFrames <= 3) log("Кадр разобран, регистров " + regs.length);
                } else {
                    log("Кадр на " + regs.length + " регистров (не живые данные)");
                }
            }
        }

        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            if (polls <= 2) log("Запись статус " + status);
        }
    };

    private void enableNotify(BluetoothGatt g, BluetoothGattCharacteristic c, boolean first) {
        if (c == null) {
            begin();
            return;
        }
        int p = c.getProperties();
        boolean notify = (p & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0;
        boolean indicate = (p & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0;
        BluetoothGattDescriptor d = c.getDescriptor(CCCD);
        if ((!notify && !indicate) || d == null) {
            log("Канал " + c.getUuid().toString().substring(4, 8) + " без подписки");
            if (first && nch2 != null && nch2 != nch1) enableNotify(g, nch2, false);
            else begin();
            return;
        }
        try {
            g.setCharacteristicNotification(c, true);
            d.setValue(notify ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    : BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
            g.writeDescriptor(d);
        } catch (SecurityException e) {
            state("Нет разрешения Bluetooth", false);
        }
    }

    // ---------- опрос ----------
    private void begin() {
        if (connected) return;
        connected = true;
        polls = 0;
        gotFrames = 0;
        candidate = 0;
        state("Подключено, жду данные…", true);
        main.post(poll);
    }

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (gatt == null || wch == null || !connected) return;
            if (gotFrames == 0 && polls > 0 && polls % 3 == 0) {
                candidate = (candidate + 1) % CANDIDATES.length;
                log("Нет ответа, пробую запрос " + CANDIDATES[candidate][0] + "/" + CANDIDATES[candidate][1]);
            }
            byte[] req = BmsFrame.readRequest(CANDIDATES[candidate][0], CANDIDATES[candidate][1]);
            if (polls < 3) log("TX " + BmsFrame.hex(req, 16));
            polls++;
            try {
                int props = wch.getProperties();
                wch.setWriteType((props & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                        && (props & BluetoothGattCharacteristic.PROPERTY_WRITE) == 0
                        ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                wch.setValue(req);
                gatt.writeCharacteristic(wch);
            } catch (SecurityException e) {
                state("Нет разрешения Bluetooth", false);
            }
            main.postDelayed(this, 3000);
        }
    };
}
