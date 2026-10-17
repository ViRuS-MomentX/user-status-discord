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
    static final int UPDATE_MODE = 1101;

    /** У режима цвета свои — задаются вместе с режимом, а не по светодиодам. */
    static final int COLORS_MODE_SPECIFIC = 2;

    /** Устройство: номер у OpenRGB, имя, сколько светодиодов, его режимы. */
    record Device(int index, String name, String kind, int leds, List<Mode> modes) {

        Device(int index, String name, String kind, int leds) {
            this(index, name, kind, leds, List.of());
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

        /** Можно ли покрасить хоть как-то. */
        boolean paintable() {
            return leds > 0 || solid() != null;
        }
    }

    /** Режим подсветки — все поля, потому что OpenRGB ждёт его обратно целиком. */
    record Mode(int index, String name, int value, int flags, int speedMin, int speedMax,
                int colorsMin, int colorsMax, int speed, int direction, int colorMode) {
    }

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;

    OpenRgb(String host, int port) throws IOException {
        socket = new Socket();

        try {
            socket.connect(new InetSocketAddress(host, port), 2000);
        } catch (IOException e) {
            socket.close();
            throw new IOException("OpenRGB не отвечает на " + host + ":" + port
                    + " — запусти OpenRGB и включи в нём SDK Server (вкладка SDK Server → Start Server)");
        }

        socket.setSoTimeout(5000);
        in = new DataInputStream(socket.getInputStream());
        out = socket.getOutputStream();
        send(0, SET_CLIENT_NAME, (Virus.NAME + "\0").getBytes(StandardCharsets.UTF_8));
    }

    List<Device> devices() throws IOException {
        send(0, CONTROLLER_COUNT, new byte[0]);
        var count = le(ByteBuffer.wrap(receive(CONTROLLER_COUNT))).getInt();
        var devices = new ArrayList<Device>();

        for (var i = 0; i < count; i++) {
            send(i, CONTROLLER_DATA, new byte[0]);
            devices.add(parse(i, receive(CONTROLLER_DATA)));
        }

        return devices;
    }

    /** Красит все светодиоды устройства в один цвет. */
    void paint(Device device, Color color) throws IOException {
        if (device.leds() == 0) {
            paintMode(device, color);
            return;
        }

        // Свой режим: иначе поверх нашего цвета продолжит играть встроенный эффект
        send(device.index(), SET_CUSTOM_MODE, new byte[0]);

        var body = le(ByteBuffer.allocate(4 + 2 + 4 * device.leds()));
        body.putInt(body.capacity());
        body.putShort((short) device.leds());

        for (var i = 0; i < device.leds(); i++) {
            body.put((byte) color.getRed()).put((byte) color.getGreen()).put((byte) color.getBlue()).put((byte) 0);
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

        var count = Math.max(1, mode.colorsMin());
        var name = (mode.name() + "\0").getBytes(StandardCharsets.UTF_8);
        var body = le(ByteBuffer.allocate(4 + 4 + 2 + name.length + 9 * 4 + 2 + 4 * count));
        body.putInt(body.capacity());
        body.putInt(mode.index());
        body.putShort((short) name.length).put(name);
        body.putInt(mode.value()).putInt(mode.flags()).putInt(mode.speedMin()).putInt(mode.speedMax())
                .putInt(mode.colorsMin()).putInt(mode.colorsMax()).putInt(mode.speed()).putInt(mode.direction())
                .putInt(mode.colorMode());
        body.putShort((short) count);

        for (var i = 0; i < count; i++) {
            body.put((byte) color.getRed()).put((byte) color.getGreen()).put((byte) color.getBlue()).put((byte) 0);
        }

        send(device.index(), UPDATE_MODE, body.array());
    }

    /**
     * Описание устройства в версии протокола 0. Нужны имя, тип и число
     * светодиодов — но они разбросаны по всему описанию, поэтому читаем его
     * целиком по порядку.
     */
    static Device parse(int index, byte[] data) {
        var buffer = le(ByteBuffer.wrap(data));
        buffer.getInt(); // размер
        var type = buffer.getInt();
        var name = string(buffer);
        string(buffer); // описание
        string(buffer); // версия
        string(buffer); // серийный номер
        string(buffer); // где подключено

        var modes = Short.toUnsignedInt(buffer.getShort());
        buffer.getInt(); // активный режим

        var modeList = new ArrayList<Mode>();

        for (var i = 0; i < modes; i++) {
            var modeName = string(buffer);
            // значение, флаги, скорость мин/макс, цветов мин/макс, скорость, направление, режим цвета
            modeList.add(new Mode(i, modeName, buffer.getInt(), buffer.getInt(), buffer.getInt(), buffer.getInt(),
                    buffer.getInt(), buffer.getInt(), buffer.getInt(), buffer.getInt(), buffer.getInt()));
            var colors = Short.toUnsignedInt(buffer.getShort());
            buffer.position(buffer.position() + colors * 4);
        }

        var zones = Short.toUnsignedInt(buffer.getShort());

        for (var i = 0; i < zones; i++) {
            string(buffer);
            buffer.position(buffer.position() + 4 * 4); // тип, светодиодов мин/макс/сколько
            var matrix = Short.toUnsignedInt(buffer.getShort());
            buffer.position(buffer.position() + matrix);
        }

        var leds = Short.toUnsignedInt(buffer.getShort());

        for (var i = 0; i < leds; i++) {
            string(buffer);
            buffer.getInt();
        }

        var colors = Short.toUnsignedInt(buffer.getShort());
        return new Device(index, name, kind(type), colors, List.copyOf(modeList));
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
