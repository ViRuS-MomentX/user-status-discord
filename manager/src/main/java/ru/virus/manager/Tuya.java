package ru.virus.manager;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * Локальный протокол Tuya (устройства из Smart Life / Tuya Smart) — по
 * описанию и коду проекта tinytuya (PROTOCOL.md): кадры 55AA (3.3, 3.4) и
 * 6699 (3.5), AES с «локальным ключом» устройства, для 3.4/3.5 — сессионный
 * ключ через три сообщения. Порт 6668 по домашней сети, без облака.
 */
final class Tuya {

    static final int PORT = 6668;
    static final int CONTROL = 0x07;
    static final int DP_QUERY = 0x0a;
    static final int CONTROL_NEW = 0x0d;
    static final int DP_QUERY_NEW = 0x10;
    static final int NEG_START = 0x03;
    static final int NEG_RESP = 0x04;
    static final int NEG_FINISH = 0x05;

    /** Ключ широковещательных объявлений устройств: MD5("yGAdlopoPVldABfn"). */
    static final byte[] UDP_KEY = md5("yGAdlopoPVldABfn".getBytes(StandardCharsets.US_ASCII));

    private static final SecureRandom RANDOM = new SecureRandom();

    private Tuya() {
    }

    // ---- Шифры ----

    static byte[] md5(byte[] data) {
        try {
            return MessageDigest.getInstance("MD5").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] ecb(byte[] key, byte[] data, boolean encrypt, boolean pad) {
        try {
            var cipher = Cipher.getInstance(pad ? "AES/ECB/PKCS5Padding" : "AES/ECB/NoPadding");
            cipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
            return cipher.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("AES: " + e.getMessage(), e);
        }
    }

    static byte[] hmac(byte[] key, byte[] data) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** AES-GCM: на выходе шифртекст и 16 байт метки (как отдаёт Java). */
    static byte[] gcm(byte[] key, byte[] iv, byte[] aad, byte[] data, boolean encrypt) throws Exception {
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(128, iv));

        if (aad != null) {
            cipher.updateAAD(aad);
        }

        return cipher.doFinal(data);
    }

    static byte[] concat(byte[]... parts) {
        var size = 0;

        for (var part : parts) {
            size += part.length;
        }

        var out = new byte[size];
        var at = 0;

        for (var part : parts) {
            System.arraycopy(part, 0, out, at, part.length);
            at += part.length;
        }

        return out;
    }

    /** «3.x» и 12 нулей — заголовок версии внутри сообщения. */
    static byte[] versionHeader(String version) {
        return concat(version.getBytes(StandardCharsets.US_ASCII), new byte[12]);
    }

    // ---- Кадры ----

    /** Кадр 55AA: префикс, номер, команда, длина, данные, CRC32 или HMAC, хвост. */
    static byte[] pack55(int seq, int cmd, byte[] payload, byte[] hmacKey) {
        var end = hmacKey == null ? 8 : 36;
        var head = ByteBuffer.allocate(16).putInt(0x000055AA).putInt(seq).putInt(cmd).putInt(payload.length + end).array();
        var data = concat(head, payload);
        byte[] check;

        if (hmacKey == null) {
            var crc = new CRC32();
            crc.update(data);
            check = ByteBuffer.allocate(4).putInt((int) crc.getValue()).array();
        } else {
            check = hmac(hmacKey, data);
        }

        return concat(data, check, new byte[] {0, 0, (byte) 0xAA, 0x55});
    }

    /** Данные из кадра 55AA от устройства (без кода возврата и проверки). */
    static byte[] unpack55(byte[] frame, boolean hmac, boolean retcode) {
        var length = ByteBuffer.wrap(frame, 12, 4).getInt();
        var end = hmac ? 36 : 8;
        var from = 16 + (retcode ? 4 : 0);
        var to = 16 + length - end;
        return from >= to ? new byte[0] : Arrays.copyOfRange(frame, from, to);
    }

    /** Кадр 6699 (3.5): заголовок как AAD, данные в AES-GCM. */
    static byte[] pack6699(int seq, int cmd, byte[] payload, byte[] key) throws Exception {
        var iv = new byte[12];
        RANDOM.nextBytes(iv);
        return pack6699(seq, cmd, payload, key, iv);
    }

    static byte[] pack6699(int seq, int cmd, byte[] payload, byte[] key, byte[] iv) throws Exception {
        var head = ByteBuffer.allocate(18).putInt(0x00006699).putShort((short) 0).putInt(seq).putInt(cmd)
                .putInt(payload.length + 12 + 16).array();
        var sealed = gcm(key, iv, Arrays.copyOfRange(head, 4, 18), payload, true);
        return concat(head, iv, sealed, new byte[] {0, 0, (byte) 0x99, 0x66});
    }

    /** Расшифровать кадр 6699; код возврата (4 байта) — убрать, если он есть. */
    static byte[] unpack6699(byte[] frame, byte[] key) throws Exception {
        var length = ByteBuffer.wrap(frame, 14, 4).getInt();
        var iv = Arrays.copyOfRange(frame, 18, 30);
        var sealed = Arrays.copyOfRange(frame, 30, 18 + length);
        var plain = gcm(key, iv, Arrays.copyOfRange(frame, 4, 18), sealed, false);
        return stripRetcode(plain);
    }

    private static byte[] stripRetcode(byte[] plain) {
        // Код возврата — 4 байта перед JSON, версией или ответом рукопожатия (48 байт)
        if (plain.length >= 4 && (plain.length == 52 || plain.length > 4 && (plain[4] == '{' || plain[4] == '3'))) {
            return Arrays.copyOfRange(plain, 4, plain.length);
        }

        return plain;
    }

    /** Убрать заголовок версии (15 байт), если сообщение с него начинается. */
    static byte[] stripVersion(byte[] data) {
        return data.length >= 15 && data[0] == '3' && data[1] == '.' ? Arrays.copyOfRange(data, 15, data.length) : data;
    }

    // ---- Обнаружение в сети ----

    /** Устройство, объявившее себя в сети. */
    record Found(String id, String ip, String version, String product) {
    }

    /** Разобрать широковещательное объявление (порты 6666/6667/7000). */
    static Found parseBroadcast(byte[] data) {
        try {
            String json;

            if (data.length > 20 && ByteBuffer.wrap(data, 0, 4).getInt() == 0x000055AA) {
                var payload = unpack55(data, false, true);
                json = payload.length > 0 && payload[0] == '{' ? new String(payload, StandardCharsets.UTF_8)
                        : new String(ecb(UDP_KEY, payload, false, true), StandardCharsets.UTF_8);
            } else if (data.length > 30 && ByteBuffer.wrap(data, 0, 4).getInt() == 0x00006699) {
                json = new String(unpack6699(data, UDP_KEY), StandardCharsets.UTF_8).replace("\0", "");
            } else {
                json = new String(ecb(UDP_KEY, data, false, true), StandardCharsets.UTF_8);
            }

            var start = json.indexOf('{');

            if (start < 0 || !(Json.parse(json.substring(start)) instanceof Map<?, ?> map)) {
                return null;
            }

            var id = String.valueOf(map.get("gwId"));
            return id.equals("null") ? null : new Found(id, String.valueOf(map.get("ip")),
                    String.valueOf(map.get("version")), String.valueOf(map.get("productKey")));
        } catch (Exception e) {
            return null;
        }
    }

    /** Послушать объявления устройств несколько секунд. */
    static List<Found> discover(long millis) {
        var found = new LinkedHashMap<String, Found>();
        var threads = new ArrayList<Thread>();

        for (var port : List.of(6666, 6667, 7000)) {
            var thread = new Thread(() -> listen(port, millis, found), "virus-tuya-" + port);
            thread.setDaemon(true);
            thread.start();
            threads.add(thread);
        }

        for (var thread : threads) {
            try {
                thread.join(millis + 1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        synchronized (found) {
            return List.copyOf(found.values());
        }
    }

    private static void listen(int port, long millis, Map<String, Found> found) {
        try (var socket = new DatagramSocket(null)) {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(port));
            socket.setSoTimeout(500);
            var deadline = System.currentTimeMillis() + millis;
            var buffer = new byte[2048];

            while (System.currentTimeMillis() < deadline) {
                try {
                    var packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);
                    var device = parseBroadcast(Arrays.copyOf(packet.getData(), packet.getLength()));

                    if (device != null) {
                        synchronized (found) {
                            found.put(device.id(), device);
                        }
                    }
                } catch (SocketTimeoutException ignored) {
                    // ждём дальше
                }
            }
        } catch (IOException ignored) {
            // порт занят другой программой — слушаем остальные
        }
    }

    // ---- Подключение и команды ----

    /**
     * Подключение к одному устройству. Для 3.4/3.5 сразу договаривается о
     * сессионном ключе; дальше — команды «поставить значения».
     */
    static final class Session implements AutoCloseable {

        private final String id;
        private final String version;
        private final byte[] localKey;
        private final Socket socket;
        private final DataInputStream in;
        private final OutputStream out;
        private byte[] key;
        private int seq = 1;

        Session(String ip, String id, String localKey, String version) throws Exception {
            this.id = id;
            this.version = version.startsWith("3.") ? version : "3.3";
            this.localKey = localKey.getBytes(StandardCharsets.UTF_8);

            if (this.localKey.length != 16) {
                throw new IOException("локальный ключ должен быть 16 символов");
            }

            socket = new Socket();
            socket.connect(new InetSocketAddress(ip, PORT), 4000);
            socket.setSoTimeout(5000);
            socket.setTcpNoDelay(true);
            in = new DataInputStream(socket.getInputStream());
            out = socket.getOutputStream();
            key = this.localKey;

            if (this.version.compareTo("3.4") >= 0) {
                negotiate();
            }
        }

        String version() {
            return version;
        }

        private boolean v35() {
            return version.compareTo("3.5") >= 0;
        }

        private void negotiate() throws Exception {
            var nonce = new byte[16];
            RANDOM.nextBytes(nonce);
            byte[] reply;

            if (v35()) {
                send(pack6699(seq++, NEG_START, nonce, localKey));
                reply = unpack6699(read(), localKey);
            } else {
                send(pack55(seq++, NEG_START, ecb(localKey, nonce, true, true), localKey));
                reply = ecb(localKey, unpack55(read(), true, true), false, true);
            }

            if (reply.length < 48 || !Arrays.equals(Arrays.copyOfRange(reply, 16, 48), hmac(localKey, nonce))) {
                throw new IOException("устройство не приняло ключ — проверь локальный ключ");
            }

            var remote = Arrays.copyOfRange(reply, 0, 16);
            var proof = hmac(localKey, remote);

            if (v35()) {
                send(pack6699(seq++, NEG_FINISH, proof, localKey));
            } else {
                send(pack55(seq++, NEG_FINISH, ecb(localKey, proof, true, true), localKey));
            }

            var mixed = new byte[16];

            for (var i = 0; i < 16; i++) {
                mixed[i] = (byte) (nonce[i] ^ remote[i]);
            }

            key = sessionKey(localKey, nonce, mixed, v35());
        }

        /** Сессионный ключ: 3.4 — AES-ECB смеси, 3.5 — первые 16 байт AES-GCM с IV из своего nonce. */
        static byte[] sessionKey(byte[] localKey, byte[] nonce, byte[] mixed, boolean gcm) throws Exception {
            if (!gcm) {
                return ecb(localKey, mixed, true, false);
            }

            return Arrays.copyOf(Tuya.gcm(localKey, Arrays.copyOf(nonce, 12), null, mixed, true), 16);
        }

        private void send(byte[] frame) throws IOException {
            out.write(frame);
            out.flush();
        }

        /** Прочитать один кадр целиком. */
        private byte[] read() throws IOException {
            var prefix = in.readInt();

            if (prefix == 0x00006699) {
                var head = new byte[14];
                in.readFully(head);
                var length = ByteBuffer.wrap(head, 10, 4).getInt();
                var rest = new byte[length + 4];
                in.readFully(rest);
                return concat(ByteBuffer.allocate(4).putInt(prefix).array(), head, rest);
            }

            var head = new byte[12];
            in.readFully(head);
            var length = ByteBuffer.wrap(head, 8, 4).getInt();
            var rest = new byte[length];
            in.readFully(rest);
            return concat(ByteBuffer.allocate(4).putInt(prefix).array(), head, rest);
        }

        /** Сообщение с данными по правилам своей версии. */
        private byte[] frame(int cmd, String json, boolean header) throws Exception {
            var body = json.getBytes(StandardCharsets.UTF_8);

            if (v35()) {
                return pack6699(seq++, cmd, header ? concat(versionHeader(version), body) : body, key);
            }

            if (version.equals("3.4")) {
                return pack55(seq++, cmd, ecb(key, header ? concat(versionHeader(version), body) : body, true, true), key);
            }

            var sealed = ecb(key, body, true, true);
            return pack55(seq++, cmd, header ? concat(versionHeader(version), sealed) : sealed, null);
        }

        /** Поставить значения (DP): {"20": true, "24": "…"}. */
        void set(Map<String, Object> dps) throws Exception {
            var t = System.currentTimeMillis() / 1000;

            if (version.compareTo("3.4") >= 0) {
                send(frame(CONTROL_NEW, "{\"protocol\":5,\"t\":" + t + ",\"data\":{\"dps\":" + Json.write(dps) + "}}", true));
            } else {
                send(frame(CONTROL, "{\"devId\":\"" + id + "\",\"uid\":\"" + id + "\",\"t\":\"" + t + "\",\"dps\":"
                        + Json.write(dps) + "}", true));
            }

            drain();
        }

        /** Какие значения есть у устройства — чтобы понять, лампа «старая» (DP 1–5) или «новая» (DP 20–27). */
        Map<?, ?> query() throws Exception {
            var t = System.currentTimeMillis() / 1000;

            if (version.compareTo("3.4") >= 0) {
                send(frame(DP_QUERY_NEW, "{}", false));
            } else {
                send(frame(DP_QUERY, "{\"gwId\":\"" + id + "\",\"devId\":\"" + id + "\",\"uid\":\"" + id
                        + "\",\"t\":\"" + t + "\"}", false));
            }

            for (var attempt = 0; attempt < 3; attempt++) {
                var json = decode(read());
                var start = json.indexOf('{');

                if (start >= 0 && Json.parse(json.substring(start)) instanceof Map<?, ?> map && map.get("dps") instanceof Map<?, ?> dps) {
                    return dps;
                }
            }

            return Map.of();
        }

        private String decode(byte[] frame) throws Exception {
            byte[] data;

            if (v35()) {
                data = stripVersion(unpack6699(frame, key));
            } else if (version.equals("3.4")) {
                data = stripVersion(ecb(key, unpack55(frame, true, true), false, true));
            } else {
                var payload = stripVersion(unpack55(frame, false, true));
                data = payload.length == 0 || payload[0] == '{' ? payload : ecb(key, payload, false, true);
            }

            return new String(data, StandardCharsets.UTF_8);
        }

        /** Ответы устройства не нужны — прочитать, что пришло, чтобы буфер не переполнился. */
        private void drain() throws IOException {
            socket.setSoTimeout(30);

            try {
                while (in.available() > 0) {
                    read();
                }
            } catch (SocketTimeoutException ignored) {
                // больше ничего нет
            } finally {
                socket.setSoTimeout(5000);
            }
        }

        @Override
        public void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
                // уже закрыт
            }
        }
    }

    // ---- Значения ламп ----

    /**
     * Значения для лампы: цвет и вкл. «Новые» лампы (DP 20–27): 20 — вкл,
     * 21 — режим "colour", 24 — цвет «hhhhssssvvvv» (оттенок 0–360,
     * насыщенность и яркость 0–1000). «Старые» (DP 1–5): 1, 2, 5 — «rrggbb0hhhssvv».
     */
    static Map<String, Object> light(java.awt.Color color, boolean on, boolean newStyle) {
        var hsb = java.awt.Color.RGBtoHSB(color.getRed(), color.getGreen(), color.getBlue(), null);
        var dps = new LinkedHashMap<String, Object>();
        var lit = on && hsb[2] > 0.01f;

        if (newStyle) {
            dps.put("20", lit);

            if (lit) {
                dps.put("21", "colour");
                dps.put("24", String.format("%04x%04x%04x", Math.round(hsb[0] * 360) % 360, Math.round(hsb[1] * 1000),
                        Math.max(10, Math.round(hsb[2] * 1000))));
            }
        } else {
            dps.put("1", lit);

            if (lit) {
                dps.put("2", "colour");
                dps.put("5", String.format("%02x%02x%02x%04x%02x%02x", color.getRed(), color.getGreen(), color.getBlue(),
                        Math.round(hsb[0] * 360) % 360, Math.round(hsb[1] * 255), Math.max(1, Math.round(hsb[2] * 255))));
            }
        }

        return dps;
    }

    static String hex(byte[] data) {
        return HexFormat.of().formatHex(data);
    }
}
