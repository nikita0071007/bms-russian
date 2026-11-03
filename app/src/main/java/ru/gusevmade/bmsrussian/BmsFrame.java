package ru.gusevmade.bmsrussian;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Кадры Daly Modbus RTU (адрес 0xD2) поверх BLE: запрос, сборка ответа из кусков, разбор. */
public final class BmsFrame {
    private final byte[] buf = new byte[1024];
    private int n = 0;

    public static int crc16(byte[] d, int off, int len) {
        int crc = 0xFFFF;
        for (int i = off; i < off + len; i++) {
            crc ^= (d[i] & 0xFF);
            for (int j = 0; j < 8; j++) {
                if ((crc & 1) != 0) crc = (crc >> 1) ^ 0xA001;
                else crc >>= 1;
            }
        }
        return crc & 0xFFFF;
    }

    /** Запрос чтения регистров: D2 03 <старт> <число> <CRC младшим байтом вперёд>. */
    public static byte[] readRequest(int start, int count) {
        byte[] f = new byte[8];
        f[0] = (byte) 0xD2;
        f[1] = 0x03;
        f[2] = (byte) (start >> 8);
        f[3] = (byte) start;
        f[4] = (byte) (count >> 8);
        f[5] = (byte) count;
        int c = crc16(f, 0, 6);
        f[6] = (byte) (c & 0xFF);
        f[7] = (byte) (c >> 8);
        return f;
    }

    private void shift(int k) {
        System.arraycopy(buf, k, buf, 0, n - k);
        n -= k;
    }

    /** Добавляет принятые байты, возвращает все полностью собранные ответы (массивы регистров). */
    public synchronized List<int[]> feed(byte[] data) {
        List<int[]> out = new ArrayList<>();
        if (n + data.length > buf.length) n = 0;
        System.arraycopy(data, 0, buf, n, data.length);
        n += data.length;
        while (true) {
            int i = 0;
            while (i < n && (buf[i] & 0xFF) != 0xD2) i++;
            if (i > 0) shift(i);
            if (n < 3) break;
            int fn = buf[1] & 0xFF;
            int len = buf[2] & 0xFF;
            if (fn != 0x03 || len == 0 || len % 2 != 0) {
                shift(1);
                continue;
            }
            int total = 3 + len + 2;
            if (n < total) break;
            int crc = crc16(buf, 0, total - 2);
            int lo = (buf[total - 2] & 0xFF) | ((buf[total - 1] & 0xFF) << 8);
            int hi = ((buf[total - 2] & 0xFF) << 8) | (buf[total - 1] & 0xFF);
            if (crc == lo || crc == hi) {
                int[] regs = new int[len / 2];
                for (int k = 0; k < regs.length; k++) {
                    regs[k] = ((buf[3 + 2 * k] & 0xFF) << 8) | (buf[4 + 2 * k] & 0xFF);
                }
                out.add(regs);
                shift(total);
            } else {
                shift(1);
            }
        }
        return out;
    }

    /** Живые данные в виде JSON для страницы. Возвращает null, если регистров слишком мало. */
    public static String liveJson(int[] r) {
        if (r.length < 43) return null;
        StringBuilder cells = new StringBuilder();
        for (int i = 0; i < 32; i++) {
            int v = r[i];
            if (v > 0 && v < 5000) {
                if (cells.length() > 0) cells.append(',');
                cells.append(String.format(Locale.US, "%.3f", v / 1000.0));
            }
        }
        StringBuilder temps = new StringBuilder();
        for (int i = 32; i < 40; i++) {
            int v = r[i];
            int t = v - 40;
            if (v > 0 && t <= 120) {
                if (temps.length() > 0) temps.append(',');
                temps.append(t);
            }
        }
        double vpack = r[40] * 0.1;
        double cur = (r[41] - 30000) * 0.1;
        double soc = r[42] * 0.1;
        return String.format(Locale.US,
                "{\"type\":\"data\",\"cells\":[%s],\"T\":[%s],\"vpack\":%.1f,\"I\":%.1f,\"soc\":%.1f}",
                cells, temps, vpack, cur, soc);
    }

    public static String hex(byte[] d, int max) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < d.length && i < max; i++) sb.append(String.format("%02X ", d[i] & 0xFF));
        if (d.length > max) sb.append("...");
        return sb.toString().trim();
    }
}
