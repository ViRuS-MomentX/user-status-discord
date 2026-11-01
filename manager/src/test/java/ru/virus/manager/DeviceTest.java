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
        return controller(name, type, leds, 0);
    }

    /** Описание устройства в формате нужной версии протокола: с 1-й — производитель, с 3-й — яркость режимов. */
    static byte[] controller(String name, int type, int leds, int protocol) {
        var out = new Out();
        out.i32(type);
        out.str(name);
        if (protocol >= 1) {
            out.str("Vendor");
        }
        out.str("описание").str("1.0").str("SN").str("USB");
        out.u16(2).i32(0);                              // два режима, активный 0
        out.str("Direct");
        for (var i = 0; i < (protocol >= 3 ? 12 : 9); i++) {
            out.i32(i);
        }
        out.u16(2).i32(0xFF).i32(0xFF00);               // у режима два цвета
        out.str("Static");
        // значение 7, флаги: ручное сохранение; скорость мин/макс; [яркость мин/макс]; цветов 1/1; скорость;
        // [яркость]; направление; цвет режима
        out.i32(7).i32(OpenRgb.FLAG_MANUAL_SAVE).i32(0).i32(0);
        if (protocol >= 3) {
            out.i32(0).i32(100);
        }
        out.i32(1).i32(1).i32(0);
        if (protocol >= 3) {
            out.i32(100);
        }
        out.i32(0).i32(OpenRgb.COLORS_MODE_SPECIFIC);
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
    /** Сколько устройств «видит» поддельный OpenRGB — в проверке растёт, как при поиске устройств. */
    final java.util.concurrent.atomic.AtomicInteger fakeCount = new java.util.concurrent.atomic.AtomicInteger(3);
    /** Сколько раз к нему подключались. */
    final java.util.concurrent.atomic.AtomicInteger connections = new java.util.concurrent.atomic.AtomicInteger();
    /** Версия протокола поддельного OpenRGB; 0 — старый, на вопрос о версии молчит. */
    volatile int fakeProtocol = 3;

    List<Packet> fakeOpenRgb() throws IOException {
        var got = new CopyOnWriteArrayList<Packet>();
        rgbServer = new ServerSocket(0);
        var acceptor = new Thread(() -> {
            while (!rgbServer.isClosed()) {
                try {
                    var client = rgbServer.accept();
                    connections.incrementAndGet();
                    var serve = new Thread(() -> serve(client, got));
                    serve.setDaemon(true);
                    serve.start();
                } catch (IOException e) {
                    return;
                }
            }
        });
        acceptor.setDaemon(true);
        acceptor.start();
        return got;
    }

    private void serve(java.net.Socket client, List<Packet> got) {
        {
            try (var socket = client) {
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
                        case OpenRgb.CONTROLLER_COUNT -> ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                                .putInt(fakeCount.get()).array();
                        case OpenRgb.PROTOCOL_VERSION -> fakeProtocol == 0 ? null
                                : ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(fakeProtocol).array();
                        case OpenRgb.CONTROLLER_DATA -> device == 0 ? controller("JONSBO CR-1000", 3, 12, protocolOf(body))
                                : device == 1 ? controller("Attack Shark X98", 5, 98, protocolOf(body))
                                : controller("MSI MYSTIC LIGHT", 0, 0, protocolOf(body));
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
        }
    }

    /** Клиент просит описание в той версии, о которой договорились: 4 байта с номером, пусто — версия 0. */
    static int protocolOf(byte[] body) {
        return body.length >= 4 ? ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN).getInt() : 0;
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
        // На светодиод уходит цвет с гамма-коррекцией: экранные 10/20/30 — это почти темнота
        var led = LedColor.correct(new Color(10, 20, 30));
        assertEquals(led.getRed(), body.get());
        assertEquals(led.getGreen(), body.get());
        assertEquals(led.getBlue(), body.get());

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
        m.position(m.position() + 11 * 4);           // флаги … цвет режима — 11 полей в версии 3
        assertEquals(1, m.getShort());
        assertEquals((byte) LedColor.correct(new Color(200, 0, 100)).getRed(), m.get());
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

    @Test
    void redStaysRedOnLeds() {
        LedColor.gamma = 2.2;
        LedColor.red = LedColor.green = LedColor.blue = 1;

        // Красный пресет: без поправки зелёный и синий горели на 1/8 — розовый
        var red = LedColor.correct(new Color(0xFF, 0x20, 0x20));
        assertEquals(255, red.getRed());
        assertTrue(red.getGreen() <= 3 && red.getBlue() <= 3, red.toString());

        // Белый и чёрный не меняются, середина темнеет (так глаз и видит)
        assertEquals(Color.WHITE, LedColor.correct(Color.WHITE));
        assertEquals(Color.BLACK, LedColor.correct(Color.BLACK));
        assertTrue(LedColor.correct(new Color(128, 128, 128)).getRed() < 64);

        // Баланс белого: синий приглушён
        LedColor.blue = 0.8;
        assertEquals(204, LedColor.correct(Color.WHITE).getBlue());
        LedColor.blue = 1;

        LedColor.gamma = 1;
        assertEquals(new Color(0xFF, 0x20, 0x20), LedColor.correct(new Color(0xFF, 0x20, 0x20)), "гамма 1 — как есть");
        LedColor.gamma = 2.2;
    }

    @Test
    void argbHeaderZonesAreResizable() throws Exception {
        var got = fakeOpenRgb();

        try (var rgb = new OpenRgb("127.0.0.1", rgbServer.getLocalPort())) {
            var devices = rgb.devices();
            var board = devices.get(2);
            assertEquals(1, board.zones().size());
            var zone = board.zones().getFirst();
            assertEquals("Keys", zone.name());
            assertTrue(devices.get(1).resizable().isEmpty(), "у клавиатуры зону не растянуть");

            // Разъём с 0 светодиодов, можно до 120
            var header = new OpenRgb.Zone(0, "JRAINBOW1", 1, 0, 120, 0);
            var argb = new OpenRgb.Device(2, "MSI MYSTIC LIGHT", "материнская плата", 0, List.of(), List.of(header));
            assertEquals(List.of(header), argb.resizable());
            assertTrue(OpenRgb.applySizes(rgb, List.of(argb), java.util.Map.of("MSI MYSTIC LIGHT|JRAINBOW1", 500)));
            assertFalse(OpenRgb.applySizes(rgb, List.of(argb), java.util.Map.of()), "размер не задан — не трогаем");
        }

        var deadline = System.currentTimeMillis() + 3000;
        while (got.stream().noneMatch(p -> p.id() == OpenRgb.RESIZE_ZONE) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }

        var resize = got.stream().filter(p -> p.id() == OpenRgb.RESIZE_ZONE).findFirst().orElseThrow();
        assertEquals(2, resize.device());
        var body = ByteBuffer.wrap(resize.body()).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0, body.getInt(), "номер зоны");
        assertEquals(120, body.getInt(), "больше, чем умеет разъём, не задаём");
    }

    @Test
    void framesAreSentOnlyWhenNeeded() {
        var red = new Color[] {Color.RED, Color.RED};
        var same = new Color[] {Color.RED, Color.RED};
        var blue = new Color[] {Color.BLUE, Color.RED};

        assertTrue(RgbAnimator.due(red, null, 0, false), "первый кадр — всегда");
        assertFalse(RgbAnimator.due(same, red, 33, false), "статичный режим не шлёт одно и то же 30 раз в секунду");
        assertTrue(RgbAnimator.due(blue, red, 33, false));
        assertFalse(RgbAnimator.due(blue, red, 33, true), "память — не чаще 10 раз в секунду");
        assertTrue(RgbAnimator.due(blue, red, RgbAnimator.SLOW_BUS_MS, true));
        assertTrue(RgbAnimator.due(same, red, RgbAnimator.KEEPALIVE_MS, false), "раз в 5 с — для надёжности");
    }

    @Test
    void untouchedDeviceIsLeftToItsOwnApp() throws Exception {
        var got = fakeOpenRgb();
        var settings = new SignalRgb.Live(() -> 0);
        settings.mode = 3;
        var animator = new RgbAnimator(settings, text -> { });
        animator.port = rgbServer.getLocalPort();
        animator.levels = java.util.Map.of("Attack Shark X98", 0, "JONSBO CR-1000", 50);
        animator.start("127.0.0.1");

        try {
            var deadline = System.currentTimeMillis() + 5000;

            while (got.stream().filter(p -> p.id() == OpenRgb.UPDATE_LEDS && p.device() == 0).count() < 5
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
        } finally {
            animator.stop();
        }

        assertTrue(got.stream().anyMatch(p -> p.id() == OpenRgb.UPDATE_LEDS && p.device() == 0), "JONSBO играет");
        assertTrue(got.stream().noneMatch(p -> p.device() == 1
                        && (p.id() == OpenRgb.UPDATE_LEDS || p.id() == OpenRgb.SET_CUSTOM_MODE)),
                "клавиатура с «не трогать» осталась родной программе");
    }

    @Test
    void ledsArePlacedLikeOnACanvas() {
        // Клавиатура 2×3: в ячейках номера клавиш, одна ячейка пустая
        var keys = new OpenRgb.Zone(0, "Keys", 2, 5, 5, 5, 2, 3, new int[] {0, 1, 2, 4, 3, -1});
        var keyboard = new OpenRgb.Device(0, "KB", "клавиатура", 5, List.of(), List.of(keys));
        var places = keyboard.places();
        assertEquals(5, places.size());
        assertTrue(places.get(0).grid());
        assertEquals(0.5 / 3, places.get(0).x(), 1e-9, "клавиша 0 — слева сверху");
        assertEquals(0.25, places.get(0).y(), 1e-9);
        assertEquals(2.5 / 3, places.get(2).x(), 1e-9, "клавиша 2 — справа сверху");
        assertEquals(1.5 / 3, places.get(3).x(), 1e-9, "клавиша 3 — во втором ряду посередине");
        assertEquals(0.75, places.get(3).y(), 1e-9);

        // Плата с двумя разъёмами: каждый вентилятор получает эффект целиком
        var fans = new OpenRgb.Device(1, "MB", "материнская плата", 6, List.of(), List.of(
                new OpenRgb.Zone(0, "JRAINBOW1", 1, 0, 120, 3), new OpenRgb.Zone(1, "JRAINBOW2", 1, 0, 120, 3)));
        var line = fans.places();
        assertEquals(0.0, line.get(0).x(), 1e-9);
        assertEquals(0.0, line.get(3).x(), 1e-9, "второй разъём начинается заново");
        assertFalse(line.get(0).grid());

        // На клавиатуре «матрица» падает по столбцам — кадр не пустой и разный во времени
        var a = Glow.frame(5, 1.0, places, new Color(0x39FF8A), Color.BLUE, 0, 100, true, 0);
        var b = Glow.frame(5, 1.7, places, new Color(0x39FF8A), Color.BLUE, 0, 100, true, 0);
        assertFalse(java.util.Arrays.equals(a, b));
    }

    @Test
    void lateDevicesArePicked() throws Exception {
        fakeCount.set(2);
        fakeOpenRgb();
        var settings = new SignalRgb.Live(() -> 0);
        var animator = new RgbAnimator(settings, text -> { });
        animator.port = rgbServer.getLocalPort();
        animator.recheckMs = 200;
        animator.start("127.0.0.1");

        try {
            Thread.sleep(600);
            assertEquals(1, connections.get(), "пока устройств столько же — одно подключение");

            // OpenRGB нашёл плату позже остальных
            fakeCount.set(3);
            var deadline = System.currentTimeMillis() + 3000;

            while (connections.get() < 2 && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }

            assertEquals(2, connections.get(), "режим переподключился и взял новое устройство");
        } finally {
            animator.stop();
        }
    }

    @Test
    void modeChangeFadesInsteadOfJumping() {
        var from = new Color[] {Color.RED};
        var to = new Color[] {Color.BLUE};

        assertEquals(Color.RED, RgbAnimator.blend(from, to, 0)[0]);
        assertEquals(Color.BLUE, RgbAnimator.blend(from, to, 1)[0]);
        var middle = RgbAnimator.blend(from, to, 0.5)[0];
        assertTrue(middle.getRed() > 0 && middle.getBlue() > 0, "посередине — смесь: " + middle);
        assertTrue(middle.getBlue() > middle.getRed(), "переход быстрый в начале — к середине ближе к новому");
        assertEquals(to, RgbAnimator.blend(new Color[2], to, 0.5), "число светодиодов поменялось — сразу новое");
    }

    @Test
    void screenModeFollowsThePicture() {
        // Экран: левая половина красная, правая синяя
        var image = new java.awt.image.BufferedImage(320, 180, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        g.setColor(new Color(200, 40, 40));
        g.fillRect(0, 0, 160, 180);
        g.setColor(new Color(40, 40, 200));
        g.fillRect(160, 0, 160, 180);
        g.dispose();
        ScreenSampler.take(image);

        var left = ScreenSampler.at(0.1, 0.5, true);
        var right = ScreenSampler.at(0.9, 0.5, false);
        assertTrue(left.getRed() > 150 && left.getBlue() < 60, "слева красный: " + left);
        assertTrue(right.getBlue() > 150 && right.getRed() < 60, "справа синий: " + right);

        // Насыщенность усилена: серовато-красный становится ярче красным, чёрный остаётся чёрным
        var vivid = ScreenSampler.vivid(new Color(180, 120, 120));
        assertTrue(vivid.getRed() - vivid.getGreen() > 60, vivid.toString());
        assertEquals(Color.BLACK, ScreenSampler.vivid(Color.BLACK));

        var places = List.of(new OpenRgb.Place(0.1, 0.5, false), new OpenRgb.Place(0.9, 0.5, false));
        var frame = Glow.frame(12, 0, places, Color.GREEN, Color.GREEN, 0, 100, true, 0);
        assertTrue(frame[0].getRed() > frame[0].getBlue() && frame[1].getBlue() > frame[1].getRed());
    }

    @Test
    void soundModeReactsToLevels() {
        assertTrue(AudioMeter.feed("0.800 0.200"));
        assertFalse(AudioMeter.feed("Exception: no audio device"), "ошибка — не уровни");
        assertEquals(1.0, AudioMeter.smooth(0.2, 1.0), "удар — сразу");
        assertTrue(AudioMeter.smooth(1.0, 0.0) > 0.7, "спад — плавный");

        // Лента: левый канал громкий — горит левый край, правый тихий — правый почти тёмный
        var dim = Glow.scale(Color.GREEN, 0.04);
        assertFalse(Glow.sound(0.05, 0.5, false, 0, Color.GREEN, Color.RED).equals(dim), "левый край горит");
        assertEquals(dim, Glow.sound(0.6, 0.5, false, 0, Color.GREEN, Color.RED), "правая середина — тихо");
        assertTrue(AudioMeter.script().contains("GetChannelsPeakValues"));
    }

    @Test
    void colorOrderLikeWled() {
        var red = Color.RED;
        assertEquals(red, LedColor.reorder(red, null));
        assertEquals(red, LedColor.reorder(red, "RGB"));
        // Устройство GRB читает первый байт как зелёный — значит, красное значение уходит вторым
        assertEquals(new Color(0, 255, 0), LedColor.reorder(red, "GRB"));
        assertEquals(new Color(30, 10, 20), LedColor.reorder(new Color(10, 20, 30), "BRG"));
        assertEquals(6, LedColor.ORDERS.size());
    }

    @Test
    void nightAndIdleDimTheLights() {
        var settings = new DevicePage.Settings();
        assertEquals(100, DevicePage.dimFor(settings, 3, 0), "ничего не включено — как есть");

        settings.nightOn = true;
        settings.nightFrom = 23;
        settings.nightTo = 8;
        settings.nightLevel = 20;
        assertEquals(20, DevicePage.dimFor(settings, 3, 0), "3 часа ночи — тусклее");
        assertEquals(20, DevicePage.dimFor(settings, 23, 0));
        assertEquals(100, DevicePage.dimFor(settings, 12, 0), "днём — полная");

        settings.idleMinutes = 10;
        assertEquals(0, DevicePage.dimFor(settings, 12, 11 * 60_000L), "хозяина нет 11 минут — темно");
        assertEquals(100, DevicePage.dimFor(settings, 12, 60_000L));

        var live = new SignalRgb.Live(() -> 0);
        live.brightness = 80;
        live.dim = 50;
        assertEquals(40, live.effective());
    }

    @Test
    void openRgbStartsAtLogonWithoutAskingAgain() {
        var command = OpenRgbTask.command("C:\\Program Files\\OpenRGB\\OpenRGB.exe");
        assertEquals("\"C:\\Program Files\\OpenRGB\\OpenRGB.exe\" --server --startminimized", command);

        var script = OpenRgbTask.elevated(List.of("/Create", "/TN", OpenRgbTask.NAME, "/TR", command, "/RL", "HIGHEST"));
        assertTrue(script.startsWith("Start-Process -FilePath 'schtasks.exe'"), script);
        assertTrue(script.contains("-Verb RunAs"), "создание задачи — от администратора");
        // Кавычки вокруг пути с пробелом доходят до schtasks как \"…\"
        assertTrue(script.contains("'\"\\\"C:\\Program Files\\OpenRGB\\OpenRGB.exe\\\" --server --startminimized\"'"), script);
        assertTrue(script.contains("'\"V.I.R.U.S. OpenRGB\"'"), script);
    }

    @Test
    void eachDeviceCanHaveItsOwnMode() throws Exception {
        var got = fakeOpenRgb();
        var settings = new SignalRgb.Live(() -> 0);
        settings.mode = 3;                       // у всех — радужная волна
        settings.color = Color.RED;
        var animator = new RgbAnimator(settings, text -> { });
        animator.port = rgbServer.getLocalPort();
        animator.modes = java.util.Map.of("JONSBO CR-1000", 0);   // а у JONSBO — статичный красный
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

        var jonsbo = got.stream().filter(p -> p.id() == OpenRgb.UPDATE_LEDS && p.device() == 0).toList();
        var keyboard = got.stream().filter(p -> p.id() == OpenRgb.UPDATE_LEDS && p.device() == 1).toList();
        assertEquals(1, jonsbo.size(), "статичный режим — один кадр, без повторов");
        var body = ByteBuffer.wrap(jonsbo.getFirst().body()).order(ByteOrder.LITTLE_ENDIAN);
        body.getInt();
        body.getShort();
        assertEquals((byte) 255, body.get(), "JONSBO красный");
        assertTrue(keyboard.size() >= 5, "клавиатура играет волну");
    }

    @Test
    void solidOnlyDevicesFollowTheEffect() throws Exception {
        var got = fakeOpenRgb();
        var settings = new SignalRgb.Live(() -> 0);
        settings.mode = 2;                       // радуга: цвет меняется во времени
        settings.speed = 100;
        var animator = new RgbAnimator(settings, text -> { });
        animator.port = rgbServer.getLocalPort();
        animator.start("127.0.0.1");

        try {
            var deadline = System.currentTimeMillis() + 5000;

            while (got.stream().filter(p -> p.id() == OpenRgb.UPDATE_MODE && p.device() == 2).count() < 3
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
        } finally {
            animator.stop();
        }

        var modes = got.stream().filter(p -> p.id() == OpenRgb.UPDATE_MODE && p.device() == 2).toList();
        assertTrue(modes.size() >= 3, "плата без светодиодов переливается вместе со всеми: " + modes.size());
        assertFalse(java.util.Arrays.equals(modes.getFirst().body(), modes.getLast().body()), "цвет меняется");
    }

    @Test
    void sharedCanvasLaysDevicesSideBySide() {
        var fan = new OpenRgb.Device(0, "Fan", "охлаждение", 2);
        var ram = new OpenRgb.Device(1, "RAM", "память", 2);

        var apart = RgbAnimator.canvas(List.of(fan, ram), false, List.of());
        assertEquals(0.0, apart.get(1).getFirst().x(), 1e-9, "без холста каждое устройство — с начала");

        var shared = RgbAnimator.canvas(List.of(fan, ram), true, List.of());
        assertEquals(0.0, shared.get(0).getFirst().x(), 1e-9, "вентилятор — первая половина");
        assertEquals(0.5, shared.get(1).getFirst().x(), 1e-9, "память — вторая половина");

        var swapped = RgbAnimator.canvas(List.of(fan, ram), true, List.of("RAM", "Fan"));
        assertEquals(0.5, swapped.get(0).getFirst().x(), 1e-9, "порядок хозяина: вентилятор правее");
        assertEquals(0.0, swapped.get(1).getFirst().x(), 1e-9);
    }

    @Test
    void newEffectsMove() {
        var line = new java.util.ArrayList<OpenRgb.Place>();
        for (var i = 0; i < 40; i++) {
            line.add(new OpenRgb.Place(i / 40.0, 0.5, false));
        }

        for (var mode : List.of(14, 15, 16, 17)) {
            var a = Glow.frame(mode, 1.0, line, new Color(0x39FF8A), Color.BLUE, 0, 100, true, 0);
            var b = Glow.frame(mode, 1.9, line, new Color(0x39FF8A), Color.BLUE, 0, 100, true, 0);
            assertFalse(java.util.Arrays.equals(a, b), "режим " + mode + " движется");
        }

        assertEquals(new Color(0, 255, 0), Glow.shiftHue(Color.RED, 120));
    }

    @Test
    void temperatureColors() {
        assertEquals(62, GpuSensor.parse("62\r\n"));
        assertEquals(-1, GpuSensor.parse("NVIDIA-SMI has failed"));
        var cold = GpuSensor.color(35);
        var hot = GpuSensor.color(90);
        assertTrue(cold.getGreen() > 200 && cold.getRed() < 50, "холодная — зелёная: " + cold);
        assertEquals(Color.RED, hot, "горячая — красная");
        var warm = GpuSensor.color(65);
        assertTrue(warm.getRed() > 200 && warm.getGreen() > 150, "тёплая — жёлтая: " + warm);
    }

    @Test
    void everyModeIsInExactlyOneGroup() {
        var grouped = DevicePage.MODE_GROUPS.values().stream().flatMap(List::stream).toList();
        assertEquals(SignalRgb.MODES.size(), grouped.size(), "каждый режим — в группе, без повторов");
        assertEquals(SignalRgb.MODES.stream().map(SignalRgb.Mode::id).collect(java.util.stream.Collectors.toSet()),
                new java.util.HashSet<>(grouped));
    }

    @Test
    void windowsAccentIsReadFromRegistry() {
        var out = "\r\nHKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\DWM\r\n    AccentColor    REG_DWORD    0xff3a2bd4\r\n";
        // 0xAABBGGRR: красный d4, зелёный 2b, синий 3a
        assertEquals(new Color(0xd4, 0x2b, 0x3a), WindowsAccent.parse(out));
        assertEquals(null, WindowsAccent.parse("ERROR: The system was unable to find the specified registry key"));
    }

    @Test
    void idleIsCountedByAnyInput() {
        assertEquals(125, IdleWatch.parse("125\r"));
        assertEquals(-1, IdleWatch.parse("Add-Type : ошибка"));
        assertTrue(IdleWatch.script().contains("GetLastInputInfo"));
    }

    @Test
    void patternCanRunBackwardsAndRepeat() {
        var places = List.of(new OpenRgb.Place(0.25, 0.5, false), new OpenRgb.Place(0.75, 0.5, false));
        assertEquals(places, Glow.shape(places, false, 1), "без настроек — как есть");
        assertEquals(0.75, Glow.shape(places, true, 1).getFirst().x(), 1e-9, "обратно — зеркально");
        assertEquals(0.5, Glow.shape(places, false, 2).getFirst().x(), 1e-9, "масштаб 2 — узор вдвое чаще");
        assertTrue(Glow.positional(12) && Glow.positional(13), "экран и звук не трогаем");
        assertFalse(Glow.positional(3));
    }

    @Test
    void colorIsWrittenIntoDeviceMemory() throws Exception {
        var got = fakeOpenRgb();

        try (var rgb = new OpenRgb("127.0.0.1", rgbServer.getLocalPort())) {
            assertEquals(3, rgb.protocol(), "договорились о версии 3");
            var board = rgb.devices().get(2);
            assertTrue(board.solid().saveable());
            assertEquals(100, board.solid().brightnessMax(), "яркость режима прочитана");
            assertTrue(rgb.save(board, Color.RED));
        }

        var deadline = System.currentTimeMillis() + 3000;
        while (got.stream().noneMatch(p -> p.id() == OpenRgb.SAVE_MODE) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }

        var save = got.stream().filter(p -> p.id() == OpenRgb.SAVE_MODE).findFirst().orElseThrow();
        assertEquals(2, save.device());
    }

    @Test
    void oldOpenRgbStillWorks() throws Exception {
        fakeProtocol = 0;
        fakeOpenRgb();

        try (var rgb = new OpenRgb("127.0.0.1", rgbServer.getLocalPort())) {
            assertEquals(0, rgb.protocol(), "старый OpenRGB молчит — версия 0");
            var devices = rgb.devices();
            assertEquals(98, devices.get(1).leds());
            assertFalse(rgb.save(devices.get(2), Color.RED), "в версии 0 записи в память нет");
        }
    }
}
