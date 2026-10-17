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
        out.u16(1).i32(0);                              // один режим, активный 0
        out.str("Direct");
        for (var i = 0; i < 9; i++) {
            out.i32(i);
        }
        out.u16(2).i32(0xFF).i32(0xFF00);               // у режима два цвета
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
                        case OpenRgb.CONTROLLER_COUNT -> ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(2).array();
                        case OpenRgb.CONTROLLER_DATA -> device == 0 ? controller("JONSBO CR-1000", 3, 12)
                                : controller("Attack Shark X98", 5, 98);
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
            assertEquals(2, devices.size());
            assertEquals("JONSBO CR-1000", devices.get(0).name());
            assertEquals("охлаждение", devices.get(0).kind());
            assertEquals(98, devices.get(1).leds());
            assertEquals("клавиатура", devices.get(1).kind());

            rgb.paint(devices.get(1), new Color(10, 20, 30));
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
}
