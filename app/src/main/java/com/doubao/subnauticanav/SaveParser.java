package com.doubao.subnauticanav;

/**
 * 从 Subnautica 手游存档 scene-objects.bin 里解析玩家世界坐标。
 * 文件结构：一个 varint 长度前缀 + 一串 protobuf f2 对象记录；
 * 每条对象 = f1 名字串 + f2 体，体内含组件块（UnityEngine.Transform 的位置在
 * 0x0a 0x15 "UnityEngine.Transform" 0x10 0x01 之后：0x0f 标记 + 15 字节 = 3 个带 tag 的 float）。
 */
public final class SaveParser {

    private static final byte[] TRANSFORM_PATTERN = {
            0x0a, 0x15,
            'U', 'n', 'i', 't', 'y', 'E', 'n', 'g', 'i', 'n', 'e',
            '.', 'T', 'r', 'a', 'n', 's', 'f', 'o', 'r', 'm',
            0x10, 0x01
    };

    public static final class Pos {
        public final float x, y, z;
        public Pos(float x, float y, float z) { this.x = x; this.y = y; this.z = z; }
        public float depth() { return y < 0 ? -y : 0f; }
        @Override public String toString() {
            return String.format("X %.1f  Y %.1f  Z %.1f", x, y, z);
        }
    }

    private SaveParser() {}

    public static Pos parsePlayer(byte[] d) {
        int[] cur = new int[]{0};
        int ignoredPrefix = readVarint(d, cur);
        if (ignoredPrefix <= 0) return null;
        int p = cur[0];

        while (p < d.length) {
            int tag = readVarint(d, cur);
            p = cur[0];
            if (tag <= 0) break;
            int field = tag >> 3;
            int wire = tag & 7;
            if (wire == 2) {
                int len = readVarint(d, cur);
                p = cur[0];
                if (len <= 0 || p + len > d.length) break;
                if (field == 2 && isName(d, p, p + len, "Player")) {
                    Pos hit = findTransform(d, p, p + len);
                    if (hit != null) return hit;
                }
                p += len;
                cur[0] = p;
            } else if (wire == 0) {
                readVarint(d, cur);
                p = cur[0];
            } else if (wire == 5) {
                p += 4; cur[0] = p;
            } else if (wire == 1) {
                p += 8; cur[0] = p;
            } else {
                break;
            }
        }
        return null;
    }

    /** 记录开头是不是 0x0a + varint 长度 + 指定名字 */
    private static boolean isName(byte[] d, int lo, int hi, String name) {
        if (lo >= hi || d[lo] != 0x0a) return false;
        int[] cur = new int[]{lo + 1};
        int len = readVarint(d, cur);
        int s = cur[0];
        if (len != name.length() || s + len > hi) return false;
        for (int i = 0; i < len; i++) {
            if (d[s + i] != (byte) name.charAt(i)) return false;
        }
        return true;
    }

    private static Pos findTransform(byte[] d, int lo, int hi) {
        for (int i = lo; i + TRANSFORM_PATTERN.length + 4 < hi; i++) {
            if (!match(d, i, TRANSFORM_PATTERN)) continue;
            int[] cur = new int[]{i + TRANSFORM_PATTERN.length};
            if (d[cur[0]] == 0x38) {           // 可选字段，跳过
                cur[0]++;
                readVarint(d, cur);
            }
            int q = cur[0];
            if (q + 16 > hi || d[q] != 0x0f) continue;
            byte[] raw = new byte[16];
            System.arraycopy(d, q + 1, raw, 0, 15);
            if (raw[0] != 0x0d || raw[5] != 0x15 || raw[10] != 0x1d) continue;
            float x = f(raw, 1), y = f(raw, 6), z = f(raw, 11);
            if (Float.isNaN(x) || Float.isNaN(y) || Float.isNaN(z)) continue;
            if (Math.abs(x) > 5000 || Math.abs(z) > 5000) continue;
            return new Pos(x, y, z);
        }
        return null;
    }

    private static boolean match(byte[] d, int off, byte[] pat) {
        for (int i = 0; i < pat.length; i++) if (d[off + i] != pat[i]) return false;
        return true;
    }

    private static float f(byte[] b, int off) {
        return Float.intBitsToFloat((b[off] & 0xff)
                | ((b[off + 1] & 0xff) << 8)
                | ((b[off + 2] & 0xff) << 16)
                | ((b[off + 3] & 0xff) << 24));
    }

    /** 读 protobuf varint，返回 0 表示数据坏了 */
    private static int readVarint(byte[] d, int[] cur) {
        int i = cur[0], r = 0, s = 0;
        while (i < d.length && s < 32) {
            int b = d[i++] & 0xff;
            r |= (b & 0x7f) << s;
            if ((b & 0x80) == 0) { cur[0] = i; return r; }
            s += 7;
        }
        return 0;
    }
}
