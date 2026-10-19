package ru.virus.manager;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeviceTest {

    HttpServer http;
    ServerSocket rgbServer;

    @AfterEach
    void down() throws IOException {
        if (http != null) {
            http.stop(0);
        }
        if (rgbServer != null) {
            rgbServer.close();
        }
    }

    // ----------------------------------------------------------------- OpenRGB

    /** Описание устройства в протоколе 0 — так, как его шлёт OpenRGB. */
    static byte[] controller(String name, int type, int leds) {
        var out = new Out();
        out.i32(type);
        out.str(name).str("описание").str("1.0").str("SN").str("USB");
        out.u16(2).i32(0);                              // два режима, активный 0
        out.str("Direct");
        for (var i = 0; i < 9; i++) {
            out.i32(i);
        }
        out.u16(2).i32(0xFF).i32(0xFF00);               // у режима два цвета
        out.str("Static");
        out.i32(7).i32(0).i32(0).i32(0).i32(1).i32(1).i32(0).i32(0).i32(OpenRgb.COLORS_MODE_SPECIFIC);
        out.u16(1).i32(0);
        out.u16(1);                                     // одна зона
        out.str("Keys").i32(1).i32(leds).i32(leds).i32(leds);
        out.u16(8).i32(1).i32(1);                       // матрица 1×1 без ячеек — 8 байт
        out.u16(leds);
        for (var i = 0; i < leds; i++) {
            out.str("Key " + i).i32(i);
        }
        out.u16(leds);
        for (var i = 0; i < leds; i++) {
            out.i32(0);
        }
        var body = out.bytes();
        var full = ByteBuffer.allocate(4 + body.length).order(ByteOrder.LITTLE_ENDIAN);
        full.putInt(4 + body.length).put(body);
        return full.array();
    }

    static final class Out {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        Out i32(int value) {
            var b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
            bytes.writeBytes(b);
            return this;
        }

        Out u16(int value) {
            bytes.writeBytes(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort((short) value).array());
            return this;
        }

        Out str(String text) {
            var raw = (text + "\0").getBytes(StandardCharsets.UTF_8);
            u16(raw.length);
            bytes.writeBytes(raw);
            return this;
        }

        byte[] bytes() {
            return bytes.toByteArray();
        }
    }

    record Packet(int device, int id, byte[] body) {
    }

    /** Поддельный OpenRGB: два устройства, запоминает, что ему прислали. */
    List<Packet> fakeOpenRgb() throws IOException {
        var got = new CopyOnWriteArrayList<Packet>();
        rgbServer = new ServerSocket(0);
        var thread = new Thread(() -> {
            try (var socket = rgbServer.accept()) {
                var in = new DataInputStream(socket.getInputStream());
                var out = socket.getOutputStream();

                while (true) {
                    var header = new byte[16];
                    in.readFully(header);
                    var h = ByteBuffer.wrap(header, 4, 12).order(ByteOrder.LITTLE_ENDIAN);
                    var device = h.getInt();
                    var id = h.getInt();
                    var body = new byte[h.getInt()];
                    in.readFully(body);
                    got.add(new Packet(device, id, body));

                    byte[] reply = switch (id) {
                        case OpenRgb.CONTROLLER_COUNT -> ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(3).array();
                        case OpenRgb.CONTROLLER_DATA -> device == 0 ? controller("JONSBO CR-1000", 3, 12)
                                : device == 1 ? controller("Attack Shark X98", 5, 98)
                                : controller("MSI MYSTIC LIGHT", 0, 0);
                        default -> null;
                    };

                    if (reply != null) {
                        // Сперва непрошеное «список изменился» — клиент должен его пропустить
                        if (id == OpenRgb.CONTROLLER_COUNT) {
                            out.write(header(0, 100, 0));
                        }
                        out.write(header(device, id, reply.length));
                        out.write(reply);
                        out.flush();
                    }
                }
            } catch (IOException ignored) {
                // клиент ушёл
            }
        });
        thread.setDaemon(true);
        thread.start();
        return got;
    }

    static byte[] header(int device, int id, int size) {
        var b = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        b.put("ORGB".getBytes(StandardCharsets.US_ASCII)).putInt(device).putInt(id).putInt(size);
        return b.array();
    }

    @Test
    void openRgbListsDevicesAndPaints() throws Exception {
        var got = fakeOpenRgb();

        try (var rgb = new OpenRgb("127.0.0.1", rgbServer.getLocalPort())) {
            var devices = rgb.devices();
            assertEquals(3, devices.size());
            assertEquals("JONSBO CR-1000", devices.get(0).name());
            assertEquals("охлаждение", devices.get(0).kind());
            assertEquals(98, devices.get(1).leds());
            assertEquals("клавиатура", devices.get(1).kind());

            rgb.paint(devices.get(1), new Color(10, 20, 30));

            // Светодиодов нет — красится режимом «Static»
            var board = devices.get(2);
            assertEquals(0, board.leds());
            assertEquals("Static", board.solid().name());
            rgb.paint(board, new Color(200, 0, 100));
        }

        var deadline = System.currentTimeMillis() + 3000;
        while (got.stream().noneMatch(p -> p.id() == OpenRgb.UPDATE_LEDS) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }

        assertEquals(OpenRgb.SET_CLIENT_NAME, got.getFirst().id());
        var custom = got.stream().filter(p -> p.id() == OpenRgb.SET_CUSTOM_MODE).findFirst().orElseThrow();
        assertEquals(1, custom.device());

        var leds = got.stream().filter(p -> p.id() == OpenRgb.UPDATE_LEDS).findFirst().orElseThrow();
        var body = ByteBuffer.wrap(leds.body()).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(leds.body().length, body.getInt());
        assertEquals(98, body.getShort());
        assertEquals(10, body.get());
        assertEquals(20, body.get());
        assertEquals(30, body.get());

        deadline = System.currentTimeMillis() + 3000;
        while (got.stream().noneMatch(p -> p.id() == OpenRgb.UPDATE_MODE) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }

        var mode = got.stream().filter(p -> p.id() == OpenRgb.UPDATE_MODE).findFirst().orElseThrow();
        assertEquals(2, mode.device());
        var m = ByteBuffer.wrap(mode.body()).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(mode.body().length, m.getInt());
        assertEquals(1, m.getInt(), "номер режима Static");
        var nameLength = m.getShort();
        m.position(m.position() + nameLength);
        assertEquals(7, m.getInt(), "значение режима — как прислал OpenRGB");
        m.position(m.position() + 8 * 4);
        assertEquals(1, m.getShort());
        assertEquals((byte) 200, m.get());
    }

    @Test
    void openRgbNotRunningIsExplained() throws Exception {
        var free = new ServerSocket(0);
        var port = free.getLocalPort();
        free.close();

        var error = org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () -> new OpenRgb("127.0.0.1", port));
        assertTrue(error.getMessage().contains("SDK Server"), error.getMessage());
    }

    // ----------------------------------------------------------------- сеть

    String serveSpeed() throws IOException {
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/__down", exchange -> {
            var query = exchange.getRequestURI().getQuery();
            var bytes = Long.parseLong(query.substring(query.indexOf('=') + 1));
            bytes = Math.min(bytes, 3_000_000);
            exchange.sendResponseHeaders(200, bytes == 0 ? -1 : bytes);
            var chunk = new byte[64 * 1024];
            for (long left = bytes; left > 0; left -= chunk.length) {
                exchange.getResponseBody().write(chunk, 0, (int) Math.min(chunk.length, left));
            }
            exchange.close();
        });
        http.createContext("/__up", exchange -> {
            exchange.getRequestBody().transferTo(java.io.OutputStream.nullOutputStream());
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        http.createContext("/", exchange -> {
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        http.start();
        return "http://127.0.0.1:" + http.getAddress().getPort();
    }

    @Test
    void speedTestMeasuresBothWays() throws Exception {
        var probe = new NetProbe(serveSpeed());
        var reports = new CopyOnWriteArrayList<Double>();

        assertTrue(probe.ping() >= 0);
        var down = probe.download(reports::add);
        var up = probe.upload(reports::add);

        assertTrue(down > 0, "загрузка: " + down);
        assertTrue(up > 0, "отдача: " + up);
        assertFalse(reports.isEmpty());
    }

    @Test
    void anyAnswerMeansReachable() throws Exception {
        var base = serveSpeed();
        var result = new NetProbe(base).check(base + "/api");
        assertTrue(result.ok(), "401 — сервер ответил, значит связь есть");

        http.stop(0);
        http = null;
        var dead = new NetProbe(base).check(base + "/api");
        assertFalse(dead.ok());
        assertEquals("соединение не установилось", dead.note());
    }

    @Test
    void blockingIsExplained() {
        assertTrue(NetProbe.explain(new IOException("Remote host terminated the handshake")).contains("блокировку"));
        assertTrue(NetProbe.explain(new java.net.http.HttpTimeoutException("request timed out")).contains("не отвечает"));
    }

    @Test
    void mathAndLook() {
        assertEquals(8.0, NetProbe.mbps(1_000_000, 1000), 1e-9);
        assertEquals(new Color(50, 0, 128), DevicePage.dim(new Color(100, 0, 255), 50));
        assertEquals("16 ГБ", SystemInfo.size(16L * 1024 * 1024 * 1024));

        var gauge = new Gauge();
        gauge.set(320, "");
        assertEquals(500, gauge.scale());
    }

    @Test
    void loadIsReadable() {
        var load = SystemInfo.load();
        assertTrue(load.memoryTotal() > 0);
    }

    @Test
    void modesPlayFrameByFrameThroughOpenRgb() throws Exception {
        var got = fakeOpenRgb();
        var settings = new SignalRgb.Live(() -> 0.5);
        settings.mode = 3; // радужная волна
        var animator = new RgbAnimator(settings, text -> { });
        animator.port = rgbServer.getLocalPort();
        animator.start("127.0.0.1");

        try {
            var deadline = System.currentTimeMillis() + 5000;

            while (got.stream().filter(p -> p.id() == OpenRgb.UPDATE_LEDS && p.device() == 1).count() < 5
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
        } finally {
            animator.stop();
        }

        var frames = got.stream().filter(p -> p.id() == OpenRgb.UPDATE_LEDS && p.device() == 1).toList();
        assertTrue(frames.size() >= 5, "кадры идут: " + frames.size());
        assertTrue(got.stream().anyMatch(p -> p.id() == OpenRgb.SET_CUSTOM_MODE && p.device() == 1), "свой режим включён");
        // 98 светодиодов клавиатуры, и волна правда двигается: соседние кадры разные
        assertEquals(4 + 2 + 4 * 98, frames.getFirst().body().length);
        assertFalse(java.util.Arrays.equals(frames.getFirst().body(), frames.getLast().body()), "кадры меняются");
    }

    @Test
    void everyModeLights() {
        for (var mode : SignalRgb.MODES) {
            var lit = false;

            for (var t = 0.0; t < 3 && !lit; t += 0.1) {
                for (var color : Glow.frame(mode.id(), t, 30, new Color(0x39FF8A), new Color(0x8A2BE2), 0.3, 100, true, 0)) {
                    lit |= color.getRed() + color.getGreen() + color.getBlue() > 60;
                }
            }

            assertTrue(lit, "режим «" + mode.name() + "» хоть раз светится");
        }

        // Выключено и яркость — работают в любом режиме
        for (var color : Glow.frame(2, 1, 10, Color.RED, Color.BLUE, 0, 100, false, 0)) {
            assertEquals(Color.BLACK, color);
        }

        assertEquals(new Color(128, 0, 0), Glow.frame(0, 0, 1, Color.RED, Color.BLUE, 0, 50, true, 0)[0]);
    }
}
