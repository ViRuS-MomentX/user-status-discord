package ru.virus.manager;

import java.awt.Color;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Подсветка через OpenRGB (https://openrgb.org).
 *
 * <p>У каждой фирмы своя программа и свой закрытый протокол — JONSBO свой,
 * клавиатуры свой. OpenRGB знает сотни устройств разом и открывает к ним
 * «SDK-сервер» на localhost:6742. V.I.R.U.S. говорит с ним по его протоколу:
 * узнаёт устройства и красит их.
 *
 * <p>Протоколом называемся версии 0 — самой простой: так сервер любой версии
 * описывает устройства одинаково.
 */
final class OpenRgb implements AutoCloseable {

    static final int PORT = 6742;

    static final int CONTROLLER_COUNT = 0;
    static final int CONTROLLER_DATA = 1;
    static final int SET_CLIENT_NAME = 50;
    static final int UPDATE_LEDS = 1050;
    static final int SET_CUSTOM_MODE = 1100;
    static final int RESIZE_ZONE = 1000;
    static final int UPDATE_MODE = 1101;
    static final int SAVE_MODE = 1102;
    static final int PROTOCOL_VERSION = 40;
    /** Версия протокола, которую знает V.I.R.U.S.: с третьей есть запись в память устройства и яркость режимов. */
    static final int PROTOCOL = 3;
    /** Режим можно записать в память устройства — вручную или он сам туда пишется. */
    static final int FLAG_MANUAL_SAVE = 1 << 8;
    static final int FLAG_AUTOMATIC_SAVE = 1 << 9;

    /** У режима цвета свои — задаются вместе с режимом, а не по светодиодам. */
    static final int COLORS_MODE_SPECIFIC = 2;
    static final int COLORS_PER_LED = 1;

    /** Устройство: номер у OpenRGB, имя, сколько светодиодов, его режимы. */
    record Device(int index, String name, String kind, int leds, List<Mode> modes, List<Zone> zones) {

        Device(int index, String name, String kind, int leds) {
            this(index, name, kind, leds, List.of(), List.of());
        }

        Device(int index, String name, String kind, int leds, List<Mode> modes) {
            this(index, name, kind, leds, modes, List.of());
        }

        /**
         * Где каждый светодиод — для эффектов, как холст у SignalRGB. У
         * клавиатуры — по карте клавиш (волна идёт слева направо по
         * клавиатуре, а не по номерам клавиш), у ленты и вентилятора — вдоль
         * своей зоны: каждый вентилятор получает весь эффект целиком.
         */
        List<Place> places() {
            var result = new ArrayList<Place>();
            var offset = 0;

            for (var zone : zones) {
                var zoneLeds = new Place[zone.count()];

                if (zone.grid()) {
                    for (var row = 0; row < zone.rows(); row++) {
                        for (var column = 0; column < zone.columns(); column++) {
                            var at = row * zone.columns() + column;
                            var led = at < zone.matrix().length ? zone.matrix()[at] : -1;

                            if (led >= 0 && led < zoneLeds.length) {
                                zoneLeds[led] = new Place((column + 0.5) / zone.columns(),
                                        (row + 0.5) / zone.rows(), true);
                            }
                        }
                    }
                }

                for (var i = 0; i < zoneLeds.length; i++) {
                    if (zoneLeds[i] == null) {
                        zoneLeds[i] = new Place(zoneLeds.length == 1 ? 0.5 : (double) i / zoneLeds.length, 0.5, false);
                    }
                }

                result.addAll(List.of(zoneLeds));
                offset += zone.count();
            }

            // Описание зон не сошлось со светодиодами — тогда просто вдоль устройства
            if (offset != leds) {
                result.clear();

                for (var i = 0; i < leds; i++) {
                    result.add(new Place(leds == 1 ? 0.5 : (double) i / leds, 0.5, false));
                }
            }

            return result;
        }

        /** Зоны, у которых можно задать число светодиодов, — разъёмы ARGB на плате. */
        List<Zone> resizable() {
            return zones.stream().filter(Zone::resizable).toList();
        }

        /**
         * Режим «один цвет», если покрасить по светодиодам нельзя: у MSI Mystic
         * Light и подобных светодиоды не перечислены, а цвет задаётся режимом.
         */
        Mode solid() {
            for (var wanted : List.of("static", "steady", "direct", "fixed", "solid")) {
                for (var mode : modes) {
                    if (mode.name().toLowerCase().contains(wanted) && mode.colorMode() == COLORS_MODE_SPECIFIC
                            && mode.colorsMax() >= 1) {
                        return mode;
                    }
                }
            }

            return null;
        }

        /**
         * Понимает ли устройство цвет каждого светодиода (кадры анимации).
         * Свой режим OpenRGB ищет так: Direct, потом Custom, а если их нет —
         * берёт Static, где цвета по светодиодам молча игнорируются. Так у
         * многих плат и планок памяти: кадры уходят, а горит заводской цвет.
         * Таких красим целиком — режимом «один цвет».
         */
        boolean streams() {
            if (modes.isEmpty()) {
                return true;
            }

            return modes.stream().anyMatch(mode -> mode.colorMode() == COLORS_PER_LED
                    || mode.name().equalsIgnoreCase("direct") || mode.name().equalsIgnoreCase("custom"));
        }

        /** Можно ли покрасить хоть как-то. */
        boolean paintable() {
            return leds > 0 && streams() || solid() != null;
        }
    }

    /**
     * Зона устройства. У разъёмов ARGB на плате OpenRGB не знает, сколько
     * светодиодов подключено, и ставит 0 — тогда вентиляторы на нём не горят,
     * пока не задать число (ResizeZone, как кнопка Resize в самом OpenRGB).
     */
    record Zone(int index, String name, int type, int min, int max, int count, int rows, int columns, int[] matrix) {

        Zone(int index, String name, int type, int min, int max, int count) {
            this(index, name, type, min, max, count, 0, 0, null);
        }

        boolean resizable() {
            return max > min;
        }

        /** Есть ли карта: где на клавиатуре какая клавиша. */
        boolean grid() {
            return matrix != null && rows > 0 && columns > 0;
        }
    }

    /** Где светодиод: x и y от 0 до 1. grid — у клавиатуры, где есть строки и столбцы. */
    record Place(double x, double y, boolean grid) {
    }

    /** Режим подсветки — все поля, потому что OpenRGB ждёт его обратно целиком. */
    record Mode(int index, String name, int value, int flags, int speedMin, int speedMax,
                int colorsMin, int colorsMax, int speed, int direction, int colorMode,
                int brightnessMin, int brightnessMax, int brightness) {

        Mode(int index, String name, int value, int flags, int speedMin, int speedMax,
             int colorsMin, int colorsMax, int speed, int direction, int colorMode) {
            this(index, name, value, flags, speedMin, speedMax, colorsMin, colorsMax, speed, direction, colorMode, 0, 0, 0);
        }

        /** Запоминается ли в устройстве: тогда цвет горит и без V.I.R.U.S. и OpenRGB. */
        boolean saveable() {
            return (flags & (FLAG_MANUAL_SAVE | FLAG_AUTOMATIC_SAVE)) != 0;
        }
    }

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    /** О какой версии протокола договорились: 0 — старый OpenRGB, который о версиях не знает. */
    private int protocol;

    OpenRgb(String host, int port) throws IOException {
        socket = open(host, port);

        socket.setSoTimeout(5000);
        in = new DataInputStream(socket.getInputStream());
        out = socket.getOutputStream();
        send(0, SET_CLIENT_NAME, (Virus.NAME + "\0").getBytes(StandardCharsets.UTF_8));
        protocol = negotiate();
    }

    /** Где на самом деле слушает OpenRGB этого компьютера (адрес и порт могли поменять в его настройках). */
    static volatile InetSocketAddress found;
    private static volatile long lookedAt;
    private static volatile List<InetSocketAddress> looked = List.of();

    private static Socket dial(InetSocketAddress address) throws IOException {
        var socket = new Socket();

        try {
            socket.connect(address, 2000);
            return socket;
        } catch (IOException e) {
            socket.close();
            throw e;
        }
    }

    private static boolean local(String host) {
        return host.equals("127.0.0.1") || host.equalsIgnoreCase("localhost") || host.equals("::1");
    }

    private static Socket open(String host, int port) throws IOException {
        var standard = new InetSocketAddress(host, port);
        var known = found;

        if (known != null && local(host) && port == PORT) {
            try {
                return dial(known);
            } catch (IOException ignored) {
                found = null;
            }
        }

        try {
            return dial(standard);
        } catch (IOException e) {
            if (local(host) && port == PORT) {
                for (var other : listening()) {
                    try {
                        var socket = dial(other);
                        found = other;
                        return socket;
                    } catch (IOException ignored) {
                        // дальше
                    }
                }
            }

            throw new IOException("OpenRGB не отвечает на " + host + ":" + port
                    + " — запусти OpenRGB и включи в нём SDK Server (вкладка SDK Server → Start Server)");
        }
    }

    /**
     * На каких адресах и портах слушает процесс OpenRGB (Windows). Пусто —
     * сервер SDK в нём не включён. Не чаще раза в 20 секунд.
     */
    static synchronized List<InetSocketAddress> listening() {
        if (!SystemInfo.isWindows()) {
            return List.of();
        }

        if (System.currentTimeMillis() - lookedAt < 20_000) {
            return looked;
        }

        lookedAt = System.currentTimeMillis();
        var result = new java.util.ArrayList<InetSocketAddress>();

        try {
            var script = "$ids = (Get-Process OpenRGB -ErrorAction SilentlyContinue).Id; if ($ids) { "
                    + "Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object { $ids -contains $_.OwningProcess } "
                    + "| ForEach-Object { 'listen ' + $_.LocalAddress + ' ' + $_.LocalPort } }";
            var process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script)
                    .redirectErrorStream(true).start();
            process.getOutputStream().close();
            var out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
            result.addAll(parseListening(out));
        } catch (Exception ignored) {
            // не узнали — значит, не узнали
        }

        looked = List.copyOf(result);
        return looked;
    }

    /** «listen 0.0.0.0 6742» → 127.0.0.1:6742 (слушает на всех адресах — стучимся на свой). */
    static List<InetSocketAddress> parseListening(String output) {
        var result = new java.util.ArrayList<InetSocketAddress>();

        for (var line : output.lines().toList()) {
            var parts = line.strip().split("\\s+");

            if (parts.length == 3 && parts[0].equals("listen") && parts[2].matches("\\d{1,5}")) {
                var address = parts[1].equals("0.0.0.0") ? "127.0.0.1" : parts[1].equals("::") ? "::1" : parts[1];
                result.add(InetSocketAddress.createUnresolved(address, Integer.parseInt(parts[2])));
            }
        }

        return result.stream().map(a -> new InetSocketAddress(a.getHostString(), a.getPort())).distinct().toList();
    }

    /**
     * Договориться о версии протокола: шлём свою, OpenRGB отвечает своей,
     * берём меньшую. Старый OpenRGB (версия 0) не отвечает вовсе — ждём недолго.
     */
    private int negotiate() throws IOException {
        send(0, PROTOCOL_VERSION, le(ByteBuffer.allocate(4)).putInt(PROTOCOL).array());
        socket.setSoTimeout(1500);

        try {
            var server = le(ByteBuffer.wrap(receive(PROTOCOL_VERSION))).getInt();
            return Math.max(0, Math.min(PROTOCOL, server));
        } catch (java.net.SocketTimeoutException e) {
            return 0;
        } finally {
            socket.setSoTimeout(5000);
        }
    }

    int protocol() {
        return protocol;
    }

    /** Сколько устройств видит OpenRGB сейчас: он находит их постепенно, а иные подключают на ходу. */
    int count() throws IOException {
        send(0, CONTROLLER_COUNT, new byte[0]);
        return le(ByteBuffer.wrap(receive(CONTROLLER_COUNT))).getInt();
    }

    List<Device> devices() throws IOException {
        var count = count();
        var devices = new ArrayList<Device>();

        for (var i = 0; i < count; i++) {
            send(i, CONTROLLER_DATA, protocol == 0 ? new byte[0] : le(ByteBuffer.allocate(4)).putInt(protocol).array());
            devices.add(parse(i, receive(CONTROLLER_DATA), protocol));
        }

        return devices;
    }

    /** Красит все светодиоды устройства в один цвет. */
    void paint(Device device, Color color) throws IOException {
        if (device.leds() == 0 || !device.streams() && device.solid() != null) {
            paintMode(device, color);
            return;
        }

        // Свой режим: иначе поверх нашего цвета продолжит играть встроенный эффект
        send(device.index(), SET_CUSTOM_MODE, new byte[0]);

        var body = le(ByteBuffer.allocate(4 + 2 + 4 * device.leds()));
        body.putInt(body.capacity());
        body.putShort((short) device.leds());

        for (var i = 0; i < device.leds(); i++) {
            put(body, color, device);
        }

        send(device.index(), UPDATE_LEDS, body.array());
    }

    /** Задать число светодиодов на разъёме — после этого устройство надо перечитать. */
    void resize(Device device, Zone zone, int size) throws IOException {
        var body = le(ByteBuffer.allocate(8));
        body.putInt(zone.index()).putInt(Math.max(zone.min(), Math.min(zone.max(), size)));
        send(device.index(), RESIZE_ZONE, body.array());
    }

    /** Ключ размера зоны в настройках: «устройство|зона». */
    static String key(Device device, Zone zone) {
        return device.name() + "|" + zone.name();
    }

    /**
     * Задать сохранённые размеры разъёмов ARGB. OpenRGB сам их не помнит,
     * пока в нём не сохранили профиль, — поэтому ставим при каждом подключении.
     *
     * @return что-то поменяли — список устройств надо перечитать
     */
    static boolean applySizes(OpenRgb rgb, List<Device> devices, java.util.Map<String, Integer> sizes)
            throws IOException {
        var changed = false;

        for (var device : devices) {
            for (var zone : device.resizable()) {
                var wanted = sizes.get(key(device, zone));

                if (wanted != null && wanted != zone.count()) {
                    rgb.resize(device, zone, wanted);
                    changed = true;
                }
            }
        }

        return changed;
    }

    /** Цвет для светодиода — с гамма-коррекцией и балансом белого. */
    private static void put(ByteBuffer body, Color color, Device device) {
        var led = LedColor.reorder(LedColor.correct(color), LedColor.orders.get(device.name()));
        body.put((byte) led.getRed()).put((byte) led.getGreen()).put((byte) led.getBlue()).put((byte) 0);
    }

    /** Включить «свой» режим: без него поверх наших кадров играет встроенный эффект. */
    void customMode(Device device) throws IOException {
        send(device.index(), SET_CUSTOM_MODE, new byte[0]);
    }

    /** Каждому светодиоду свой цвет — кадр анимации. */
    void leds(Device device, Color[] colors) throws IOException {
        var count = Math.min(device.leds(), colors.length);
        var body = le(ByteBuffer.allocate(4 + 2 + 4 * count));
        body.putInt(body.capacity());
        body.putShort((short) count);

        for (var i = 0; i < count; i++) {
            put(body, colors[i], device);
        }

        send(device.index(), UPDATE_LEDS, body.array());
    }

    /** Красит режимом «один цвет» — для устройств без перечисленных светодиодов. */
    private void paintMode(Device device, Color color) throws IOException {
        var mode = device.solid();

        if (mode == null) {
            throw new IOException(device.name() + ": OpenRGB не даёт задать ему цвет — "
                    + "проверь в самом OpenRGB, можно ли его покрасить там");
        }

        send(device.index(), UPDATE_MODE, modeBody(mode, color, device));
    }

    /**
     * Записать цвет в память устройства, как Hardware Lighting в iCUE: тогда
     * он горит и при загрузке, и без V.I.R.U.S. с OpenRGB. Нужен протокол 3.
     *
     * @return записалось ли (устройство умеет и протокол позволяет)
     */
    boolean save(Device device, Color color) throws IOException {
        var mode = device.solid();

        if (protocol < 3 || mode == null || !mode.saveable()) {
            return false;
        }

        var body = modeBody(mode, color, device);
        send(device.index(), UPDATE_MODE, body);

        // Сам пишется в память — второй раз незачем
        if ((mode.flags() & FLAG_MANUAL_SAVE) != 0) {
            send(device.index(), SAVE_MODE, body);
        }

        return true;
    }

    /** Режим целиком, как OpenRGB его прислал, но с нашим цветом. */
    private byte[] modeBody(Mode mode, Color color, Device device) {
        var count = Math.max(1, mode.colorsMin());
        var name = (mode.name() + "\0").getBytes(StandardCharsets.UTF_8);
        var fields = protocol >= 3 ? 12 : 9;
        var body = le(ByteBuffer.allocate(4 + 4 + 2 + name.length + fields * 4 + 2 + 4 * count));
        body.putInt(body.capacity());
        body.putInt(mode.index());
        body.putShort((short) name.length).put(name);
        body.putInt(mode.value()).putInt(mode.flags()).putInt(mode.speedMin()).putInt(mode.speedMax());

        if (protocol >= 3) {
            body.putInt(mode.brightnessMin()).putInt(mode.brightnessMax());
        }

        body.putInt(mode.colorsMin()).putInt(mode.colorsMax()).putInt(mode.speed());

        if (protocol >= 3) {
            body.putInt(mode.brightness());
        }

        body.putInt(mode.direction()).putInt(mode.colorMode());
        body.putShort((short) count);

        for (var i = 0; i < count; i++) {
            put(body, color, device);
        }

        return body.array();
    }

    /**
     * Описание устройства в версии протокола 0. Нужны имя, тип и число
     * светодиодов — но они разбросаны по всему описанию, поэтому читаем его
     * целиком по порядку.
     */
    static Device parse(int index, byte[] data) {
        return parse(index, data, 0);
    }

    /** То же для версии протокола: с первой есть производитель, с третьей — яркость у режимов. */
    static Device parse(int index, byte[] data, int protocol) {
        var buffer = le(ByteBuffer.wrap(data));
        buffer.getInt(); // размер
        var type = buffer.getInt();
        var name = string(buffer);

        if (protocol >= 1) {
            string(buffer); // производитель
        }

        string(buffer); // описание
        string(buffer); // версия
        string(buffer); // серийный номер
        string(buffer); // где подключено

        var modes = Short.toUnsignedInt(buffer.getShort());
        buffer.getInt(); // активный режим

        var modeList = new ArrayList<Mode>();

        for (var i = 0; i < modes; i++) {
            var modeName = string(buffer);
            var value = buffer.getInt();
            var flags = buffer.getInt();
            var speedMin = buffer.getInt();
            var speedMax = buffer.getInt();
            var brightnessMin = protocol >= 3 ? buffer.getInt() : 0;
            var brightnessMax = protocol >= 3 ? buffer.getInt() : 0;
            var colorsMin = buffer.getInt();
            var colorsMax = buffer.getInt();
            var speed = buffer.getInt();
            var brightness = protocol >= 3 ? buffer.getInt() : 0;
            var direction = buffer.getInt();
            var colorMode = buffer.getInt();
            modeList.add(new Mode(i, modeName, value, flags, speedMin, speedMax, colorsMin, colorsMax, speed, direction,
                    colorMode, brightnessMin, brightnessMax, brightness));
            var colors = Short.toUnsignedInt(buffer.getShort());
            buffer.position(buffer.position() + colors * 4);
        }

        var zones = Short.toUnsignedInt(buffer.getShort());
        var zoneList = new ArrayList<Zone>();

        for (var i = 0; i < zones; i++) {
            var zoneName = string(buffer);
            var zoneType = buffer.getInt();
            var min = buffer.getInt();
            var max = buffer.getInt();
            var count = buffer.getInt();
            var matrix = Short.toUnsignedInt(buffer.getShort());
            var end = buffer.position() + matrix;
            var rows = 0;
            var columns = 0;
            int[] map = null;

            // Карта клавиш: высота, ширина и номер светодиода в каждой ячейке (пусто — 0xFFFFFFFF)
            if (matrix >= 8) {
                rows = buffer.getInt();
                columns = buffer.getInt();
                var cells = Math.min((matrix - 8) / 4, rows * columns);
                map = new int[Math.max(0, cells)];

                for (var c = 0; c < map.length; c++) {
                    map[c] = buffer.getInt();
                }
            }

            buffer.position(end);
            zoneList.add(new Zone(i, zoneName, zoneType, min, max, count, rows, columns, map));
        }

        var leds = Short.toUnsignedInt(buffer.getShort());

        for (var i = 0; i < leds; i++) {
            string(buffer);
            buffer.getInt();
        }

        var colors = Short.toUnsignedInt(buffer.getShort());
        return new Device(index, name, kind(type), colors, List.copyOf(modeList), List.copyOf(zoneList));
    }

    private static String kind(int type) {
        return switch (type) {
            case 0 -> "материнская плата";
            case 1 -> "память";
            case 2 -> "видеокарта";
            case 3 -> "охлаждение";
            case 4 -> "лента";
            case 5 -> "клавиатура";
            case 6 -> "мышь";
            case 7 -> "коврик";
            case 8 -> "гарнитура";
            case 9 -> "подставка для гарнитуры";
            case 10 -> "геймпад";
            case 11 -> "лампа";
            case 12 -> "колонки";
            case 13 -> "виртуальное";
            default -> "устройство";
        };
    }

    private static String string(ByteBuffer buffer) {
        var length = Short.toUnsignedInt(buffer.getShort());
        var bytes = new byte[length];
        buffer.get(bytes);
        // Строки приходят с нулём на конце
        var end = length > 0 && bytes[length - 1] == 0 ? length - 1 : length;
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }

    private void send(int device, int packet, byte[] body) throws IOException {
        var header = le(ByteBuffer.allocate(16));
        header.put("ORGB".getBytes(StandardCharsets.US_ASCII)).putInt(device).putInt(packet).putInt(body.length);
        out.write(header.array());
        out.write(body);
        out.flush();
    }

    /** Ответ на запрос; заодно пропускает непрошеные «список устройств изменился». */
    private byte[] receive(int expected) throws IOException {
        while (true) {
            var header = new byte[16];
            in.readFully(header);

            if (header[0] != 'O' || header[1] != 'R' || header[2] != 'G' || header[3] != 'B') {
                throw new IOException("OpenRGB ответил непонятно");
            }

            var parsed = le(ByteBuffer.wrap(header, 4, 12));
            parsed.getInt();
            var packet = parsed.getInt();
            var size = parsed.getInt();

            if (size < 0 || size > 16 * 1024 * 1024) {
                throw new IOException("OpenRGB прислал странный размер: " + size);
            }

            var body = new byte[size];
            in.readFully(body);

            if (packet == expected) {
                return body;
            }
        }
    }

    private static ByteBuffer le(ByteBuffer buffer) {
        return buffer.order(ByteOrder.LITTLE_ENDIAN);
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
