package ru.virus.manager;

import org.usb4java.ConfigDescriptor;
import org.usb4java.Context;
import org.usb4java.Device;
import org.usb4java.DeviceDescriptor;
import org.usb4java.DeviceHandle;
import org.usb4java.DeviceList;
import org.usb4java.LibUsb;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Экраны в корпусе — TURZX (Turing Smart Screen), которые JONSBO продаёт под
 * своим именем. Протокол открытый: его разобрали проекты turzx-linux и
 * turing-smart-screen-cli. Bulk-USB, интерфейс 0, выход 0x01, вход 0x81;
 * каждая команда — 512 байт: 500 байт заголовка, зашифрованные DES-CBC с
 * ключом и IV «slv3tuzx», и хвост a1 1a. Картинка (JPEG) идёт сразу за
 * заголовком в той же передаче.
 *
 * <p>Шлём только безопасное: приветствие, яркость, «остановить свой ролик»
 * и картинку. Перезагрузку (11) и запись настроек в память (125) — никогда.
 */
final class CaseScreens {

    static final short VID_TURZX = 0x1cbe;
    static final short VID_TURZX_R = 0x43a8;

    static final int SYNC = 10;
    static final int BRIGHTNESS = 14;
    static final int SHOW_JPEG = 101;
    static final int SHOW_PNG = 102;

    /** Модель экрана: название и разрешение (портрет, ширина × высота). */
    record Model(String name, int width, int height, boolean exact) {
    }

    /**
     * Известные модели TURZX по коду устройства — из turing-smart-screen-python
     * и turzx-linux. Код совпадает с размером экрана в дюймах (0x0088 — 8,8″),
     * поэтому неизвестный код, похожий на размер, угадываем по ближайшему.
     */
    static final java.util.Map<Integer, Model> MODELS = java.util.Map.ofEntries(
            java.util.Map.entry(0x0005, new Model("Turing 1.6″", 400, 400, true)),
            java.util.Map.entry(0x0016, new Model("Turing 2.8″ квадратный", 400, 400, true)),
            java.util.Map.entry(0x0021, new Model("Turing 2.1″ круглый", 480, 480, true)),
            java.util.Map.entry(0x0028, new Model("Turing 2.8″ круглый", 480, 480, true)),
            java.util.Map.entry(0x0034, new Model("Turing 3.4″", 480, 480, true)),
            java.util.Map.entry(0x0040, new Model("Turing 4″", 720, 720, true)),
            java.util.Map.entry(0x0046, new Model("Turing 4.6″", 320, 960, true)),
            java.util.Map.entry(0x0050, new Model("Turing 5.2″", 720, 1280, true)),
            java.util.Map.entry(0x0080, new Model("Turing 8″", 800, 1280, true)),
            java.util.Map.entry(0x0088, new Model("Turing 8.8″", 480, 1920, true)),
            java.util.Map.entry(0x0092, new Model("Turing 9.2″", 462, 1920, true)),
            java.util.Map.entry(0x0123, new Model("Turing 12.3″", 720, 1920, true)),
            java.util.Map.entry(0x0288, new Model("Turing 2.88″ круглый", 480, 480, true)));

    /** Модель по коду; null — не знаем и не угадать. */
    static Model model(int vendor, int product) {
        if (strip(vendor, product)) {
            return new Model("TURZX 3.38″ полоса", 640, 180, true);
        }

        if (vendor != (VID_TURZX & 0xffff)) {
            return null;
        }

        var known = MODELS.get(product);

        if (known != null) {
            return known;
        }

        // 0x0035 → 3.5″: у Turing 3.5″ экран 320×480
        if (product == 0x0035) {
            // По фото: картинка 320×480 в PNG встала ровно, но на левую половину — экран лёжа 640×480
            return new Model("Turing 3.5″", 640, 480, false);
        }

        return null;
    }
    static final int STOP_PLAYBACK = 111;
    static final int PLAYBACK_BUSY = 112;

    private static final byte[] KEY = "slv3tuzx".getBytes(StandardCharsets.US_ASCII);
    private static final byte OUT = 0x01;
    private static final byte IN = (byte) 0x81;

    /**
     * Через что говорить с экраном: интерфейс и адреса передачи из описания
     * USB-устройства. У TURZX это обычно интерфейс 0, bulk 0x01/0x81, но у
     * других моделей (полоски 338) могут быть свои — их берём из описания.
     */
    record Link(DeviceHandle handle, int iface, byte out, byte in, boolean interrupt, String text) {

        static Link of(Device device, DeviceHandle handle) {
            var config = new ConfigDescriptor();
            var parts = new ArrayList<String>();
            Link bulk = null;
            Link interruptLink = null;

            if (LibUsb.getActiveConfigDescriptor(device, config) == LibUsb.SUCCESS) {
                try {
                    for (var iface : config.iface()) {
                        for (var alt : iface.altsetting()) {
                            int out = -1;
                            int in = -1;
                            var type = 0;
                            var eps = new ArrayList<String>();

                            for (var ep : alt.endpoint()) {
                                var address = ep.bEndpointAddress() & 0xff;
                                var kind = ep.bmAttributes() & 3;
                                eps.add(String.format("%s 0x%02x %s", (address & 0x80) != 0 ? "IN" : "OUT", address,
                                        kind == 2 ? "bulk" : kind == 3 ? "interrupt" : kind == 1 ? "iso" : "control"));

                                if (kind == 2 || kind == 3) {
                                    if ((address & 0x80) != 0 && in < 0) {
                                        in = address;
                                    } else if ((address & 0x80) == 0 && out < 0) {
                                        out = address;
                                    }

                                    type = kind;
                                }
                            }

                            parts.add(String.format("интерфейс %d, класс 0x%02x: %s", alt.bInterfaceNumber() & 0xff,
                                    alt.bInterfaceClass() & 0xff, eps.isEmpty() ? "без адресов" : String.join(", ", eps)));

                            if (out >= 0 && in >= 0) {
                                var link = new Link(handle, alt.bInterfaceNumber() & 0xff, (byte) out, (byte) in, type == 3, "");

                                if (type == 2 && bulk == null) {
                                    bulk = link;
                                } else if (type == 3 && interruptLink == null) {
                                    interruptLink = link;
                                }
                            }
                        }
                    }
                } finally {
                    LibUsb.freeConfigDescriptor(config);
                }
            }

            var text = String.join("; ", parts);
            var chosen = bulk != null ? bulk : interruptLink != null ? interruptLink : new Link(handle, 0, OUT, IN, false, "");
            return new Link(handle, chosen.iface(), chosen.out(), chosen.in(), chosen.interrupt(), text);
        }

        int transfer(byte endpoint, ByteBuffer buffer, IntBuffer done, long timeout) {
            return interrupt ? LibUsb.interruptTransfer(handle, endpoint, buffer, done, timeout)
                    : LibUsb.bulkTransfer(handle, endpoint, buffer, done, timeout);
        }
    }
    private static final int MAX_JPEG = 1024 * 1024;

    /** Разрешения, которые бывают у экранов TURZX (портрет: ширина × высота). */
    static final List<int[]> SIZES = List.of(new int[] {640, 480}, new int[] {480, 640}, new int[] {320, 480},
            new int[] {480, 320}, new int[] {800, 600}, new int[] {800, 480}, new int[] {480, 800}, new int[] {480, 480},
            new int[] {1024, 768}, new int[] {1280, 720}, new int[] {1280, 960}, new int[] {1600, 1200},
            new int[] {1920, 1080}, new int[] {1080, 1920}, new int[] {1920, 1440}, new int[] {2048, 1536}, new int[] {400, 400},
            new int[] {320, 960}, new int[] {480, 1920}, new int[] {462, 1920}, new int[] {720, 1280},
            new int[] {800, 1280}, new int[] {720, 720}, new int[] {720, 1920});

    /** Найденный экран: где он и что ответил на приветствие. */
    /**
     * @param details описание USB: интерфейсы и адреса передачи
     * @param reply   ответ на приветствие целиком (hex) — там бывает и разрешение
     */
    record Screen(int bus, int address, int vendor, int product, String name, String firmware, String error,
                  String details, String reply) {

        Screen(int bus, int address, int vendor, int product, String name, String firmware, String error) {
            this(bus, address, vendor, product, name, firmware, error, "", "");
        }


        String id() {
            return String.format("%04x:%04x", vendor, product);
        }

        boolean answers() {
            return error == null;
        }
    }

    private CaseScreens() {
    }

    /**
     * Заголовок команды: 500 байт открытого текста ([0] команда, [2..3] 1a 6d,
     * [4..7] мс от полуночи, [8..] аргументы), шифр DES-CBC по первым 504
     * байтам (с четырьмя нулями), потом нули и хвост a1 1a — всего 512.
     */
    static byte[] header(int command, byte[] args, int millisOfDay) {
        if (args.length > 488) {
            throw new IllegalArgumentException("аргументы длиннее 488 байт");
        }

        var plain = new byte[504];
        plain[0] = (byte) command;
        plain[2] = 0x1a;
        plain[3] = 0x6d;
        ByteBuffer.wrap(plain, 4, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(millisOfDay);
        System.arraycopy(args, 0, plain, 8, args.length);

        try {
            var cipher = Cipher.getInstance("DES/CBC/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(KEY, "DES"), new IvParameterSpec(KEY));
            var packet = new byte[512];
            System.arraycopy(cipher.doFinal(plain), 0, packet, 0, 504);
            packet[510] = (byte) 0xa1;
            packet[511] = 0x1a;
            return packet;
        } catch (Exception e) {
            throw new IllegalStateException("DES недоступен: " + e.getMessage(), e);
        }
    }

    /** Расшифровать заголовок — для проверки, что он собран правильно. */
    static byte[] open(byte[] packet) throws Exception {
        var cipher = Cipher.getInstance("DES/CBC/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(KEY, "DES"), new IvParameterSpec(KEY));
        return cipher.doFinal(packet, 0, 504);
    }

    static byte[] be32(int value) {
        return ByteBuffer.allocate(4).putInt(value).array();
    }

    /** Строка прошивки из ответа на приветствие: байты 8..40 до нуля. */
    static String firmware(byte[] reply) {
        var end = 8;

        while (end < Math.min(40, reply.length) && reply[end] != 0) {
            end++;
        }

        return new String(reply, 8, Math.max(0, end - 8), StandardCharsets.US_ASCII).strip();
    }

    /**
     * Тестовая картинка: рамка по самому краю, сетка, стрелка «верх» и
     * размер крупно. Правильное разрешение — когда рамка видна со всех
     * четырёх сторон, а надпись не поехала.
     */
    static BufferedImage testPattern(int width, int height, String label) {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(10, 18, 14));
        g.fillRect(0, 0, width, height);
        g.setColor(new Color(40, 70, 55));

        for (var x = 0; x < width; x += 40) {
            g.drawLine(x, 0, x, height);
        }

        for (var y = 0; y < height; y += 40) {
            g.drawLine(0, y, width, y);
        }

        g.setStroke(new BasicStroke(6));
        g.setColor(new Color(0x39, 0xff, 0x8a));
        g.drawRect(3, 3, width - 7, height - 7);
        g.setColor(Color.RED);
        g.fillRect(0, 0, 24, 24);
        g.setColor(Color.BLUE);
        g.fillRect(width - 24, height - 24, 24, 24);
        g.setColor(Color.WHITE);
        var size = Math.max(14, Math.min(width, height) / 8);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, size));
        var text = width + "×" + height;
        var metrics = g.getFontMetrics();
        g.drawString(text, (width - metrics.stringWidth(text)) / 2, height / 2);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, Math.max(10, size / 2)));
        metrics = g.getFontMetrics();
        g.drawString(label, (width - metrics.stringWidth(label)) / 2, height / 2 + size);
        g.drawString("▲ верх", 30, 20 + metrics.getAscent());
        g.dispose();
        return image;
    }

    /** JPEG не больше 1 МБ: качество снижаем, пока не влезет. */
    static byte[] jpeg(BufferedImage image) throws IOException {
        for (var quality = 0.9f; quality > 0.25f; quality -= 0.1f) {
            var writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            var params = writer.getDefaultWriteParam();
            params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            params.setCompressionQuality(quality);
            var out = new ByteArrayOutputStream();

            try (var stream = ImageIO.createImageOutputStream(out)) {
                writer.setOutput(stream);
                writer.write(null, new IIOImage(image, null, fullColor(writer, image, params)), params);
            } finally {
                writer.dispose();
            }

            if (out.size() <= MAX_JPEG) {
                return out.toByteArray();
            }
        }

        throw new IOException("картинка не сжимается до 1 МБ");
    }

    /**
     * Метаданные JPEG без прореживания цвета (4:4:4), как шлют turzx-linux и
     * turing-smart-screen: Java по умолчанию делает 4:2:0, и прошивка экрана
     * рисует такое с полосами и не тем фоном.
     */
    private static javax.imageio.metadata.IIOMetadata fullColor(javax.imageio.ImageWriter writer, BufferedImage image,
                                                                ImageWriteParam params) {
        try {
            var metadata = writer.getDefaultImageMetadata(new javax.imageio.ImageTypeSpecifier(image), params);
            var format = "javax_imageio_jpeg_image_1.0";
            var root = (javax.imageio.metadata.IIOMetadataNode) metadata.getAsTree(format);
            var components = root.getElementsByTagName("componentSpec");

            for (var i = 0; i < components.getLength(); i++) {
                var component = (javax.imageio.metadata.IIOMetadataNode) components.item(i);
                component.setAttribute("HsamplingFactor", "1");
                component.setAttribute("VsamplingFactor", "1");
            }

            metadata.setFromTree(format, root);
            return metadata;
        } catch (Exception e) {
            return null;
        }
    }

    /** Прореживание цвета в готовом JPEG: множители первой компоненты (для проверки). */
    static int sampling(byte[] jpeg) {
        for (var i = 0; i + 11 < jpeg.length; i++) {
            // Маркер SOF0 (ff c0): ... число компонент, затем id и байт множителей
            if ((jpeg[i] & 0xff) == 0xff && (jpeg[i + 1] & 0xff) == 0xc0) {
                return jpeg[i + 11] & 0xff;
            }
        }

        return -1;
    }

    private static int now() {
        return (int) (LocalTime.now().toNanoOfDay() / 1_000_000);
    }

    // ---- Полоски TURZX-338 (43a8:0e61) ----
    //
    // Протокол разобран по записи USB программы JONSBO: экран 640×180, кадр —
    // 720 блоков по 512 байт в bulk 0x02 (по 8 блоков в передаче). Блок:
    // aa 55, 8 байт нуля, 02, номер блока с 1 (2 байта, старший первым),
    // нули, bb на 31-м байте и 480 байт = 160 точек BGR. Точки идут столбцами
    // по 180 снизу вверх, столбцы слева направо. После кадра экран отвечает
    // 32 байтами cc 55 … 00 dd в 0x82. Команда 01 с 8 случайными байтами —
    // «кто ты» (так делает JONSBO при подключении), ответ тоже cc 55.

    static final int STRIP_WIDTH = 640;
    static final int STRIP_HEIGHT = 180;
    static final int STRIP_BLOCKS = STRIP_WIDTH * STRIP_HEIGHT / 160;

    static boolean strip(int vendor, int product) {
        return vendor == (VID_TURZX_R & 0xffff) && product == 0x0e61;
    }

    /** Заголовок блока или служебной команды (32 байта). */
    static byte[] stripHeader(byte[] nonce, int command, int index) {
        var header = new byte[32];
        header[0] = (byte) 0xaa;
        header[1] = 0x55;
        System.arraycopy(nonce, 0, header, 2, Math.min(8, nonce.length));
        header[10] = (byte) command;
        header[11] = (byte) (index >> 8);
        header[12] = (byte) index;
        header[31] = (byte) 0xbb;
        return header;
    }

    /** Кадр для полоски: 720 блоков по 512 байт; яркость — программно (команды яркости у полоски не знаем). */
    static byte[] stripFrame(BufferedImage source, int brightness) {
        var image = new BufferedImage(STRIP_WIDTH, STRIP_HEIGHT, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(source, 0, 0, STRIP_WIDTH, STRIP_HEIGHT, null);
        g.dispose();
        var level = Math.max(0, Math.min(100, brightness)) / 100.0;
        var frame = new byte[STRIP_BLOCKS * 512];
        var zero = new byte[8];
        var k = 0;

        for (var block = 0; block < STRIP_BLOCKS; block++) {
            var base = block * 512;
            System.arraycopy(stripHeader(zero, 2, block + 1), 0, frame, base, 32);

            for (var i = 0; i < 160; i++, k++) {
                var x = k / STRIP_HEIGHT;
                var y = STRIP_HEIGHT - 1 - k % STRIP_HEIGHT;
                var rgb = image.getRGB(x, y);
                var at = base + 32 + i * 3;
                frame[at] = (byte) Math.round((rgb & 0xff) * level);
                frame[at + 1] = (byte) Math.round(((rgb >> 8) & 0xff) * level);
                frame[at + 2] = (byte) Math.round(((rgb >> 16) & 0xff) * level);
            }
        }

        return frame;
    }

    /** Ответ полоски: 32 байта cc 55 … dd; null — не ответила. */
    private static byte[] stripReply(Link link, long timeout) {
        var in = ByteBuffer.allocateDirect(512);
        var got = IntBuffer.allocate(1);

        if (link.transfer(link.in(), in, got, timeout) != LibUsb.SUCCESS || got.get(0) < 2) {
            return null;
        }

        var reply = new byte[got.get(0)];
        in.get(reply);
        return (reply[0] & 0xff) == 0xcc && reply[1] == 0x55 ? reply : null;
    }

    /** «Кто ты» — как JONSBO при подключении. */
    static byte[] stripHello(Link link) {
        var nonce = new byte[8];
        new java.security.SecureRandom().nextBytes(nonce);
        var packet = ByteBuffer.allocateDirect(32);
        packet.put(stripHeader(nonce, 1, 0));
        packet.flip();

        if (link.transfer(link.out(), packet, IntBuffer.allocate(1), 2000) != LibUsb.SUCCESS) {
            return null;
        }

        return stripReply(link, 1500);
    }

    /** Кадр на полоску; true — экран подтвердил. */
    static boolean stripSend(Link link, BufferedImage image, int brightness) {
        var frame = stripFrame(image, brightness);
        var sent = IntBuffer.allocate(1);

        for (var at = 0; at < frame.length; at += 4096) {
            var chunk = ByteBuffer.allocateDirect(Math.min(4096, frame.length - at));
            chunk.put(frame, at, chunk.capacity());
            chunk.flip();

            if (link.transfer(link.out(), chunk, sent, 3000) != LibUsb.SUCCESS) {
                return false;
            }
        }

        return stripReply(link, 1500) != null;
    }

    private static boolean ours(DeviceDescriptor descriptor) {
        return descriptor.idVendor() == VID_TURZX || descriptor.idVendor() == VID_TURZX_R;
    }

    /** Найти экраны и поздороваться с каждым. Программу JONSBO на это время надо закрыть. */
    static List<Screen> probe() {
        var found = new ArrayList<Screen>();
        var context = new Context();
        var result = LibUsb.init(context);

        if (result != LibUsb.SUCCESS) {
            found.add(new Screen(0, 0, 0, 0, "USB", "", "libusb не запустилась: " + LibUsb.errorName(result)));
            return found;
        }

        var list = new DeviceList();

        try {
            if (LibUsb.getDeviceList(context, list) < 0) {
                return found;
            }

            for (var device : list) {
                var descriptor = new DeviceDescriptor();

                if (LibUsb.getDeviceDescriptor(device, descriptor) != LibUsb.SUCCESS || !ours(descriptor)) {
                    continue;
                }

                var bus = LibUsb.getBusNumber(device);
                var address = LibUsb.getDeviceAddress(device);
                var vendor = descriptor.idVendor() & 0xffff;
                var product = descriptor.idProduct() & 0xffff;
                var handle = new DeviceHandle();
                var opened = LibUsb.open(device, handle);

                if (opened != LibUsb.SUCCESS) {
                    found.add(new Screen(bus, address, vendor, product, "", "", explain(opened)));
                    continue;
                }

                try {
                    var name = String.valueOf(LibUsb.getStringDescriptor(handle, descriptor.iProduct()));
                    var link = Link.of(device, handle);
                    var claimed = LibUsb.claimInterface(handle, link.iface());

                    if (claimed != LibUsb.SUCCESS) {
                        found.add(new Screen(bus, address, vendor, product, name, "", explain(claimed), link.text(), ""));
                        continue;
                    }

                    try {
                        drain(link);

                        if (strip(vendor, product)) {
                            var hello = stripHello(link);
                            found.add(hello == null
                                    ? new Screen(bus, address, vendor, product, name, "", "полоска не ответила — "
                                    + "программа JONSBO закрыта?", link.text(), "")
                                    : new Screen(bus, address, vendor, product, name, "полоска 640×180", null, link.text(),
                                    hex(hello, 64)));
                            continue;
                        }

                        var reply = command(link, SYNC, new byte[0], null);
                        // Непонятный ответ тоже ценен: по сырым байтам видно, на что похож их протокол
                        var raw = reply == null ? rawRead(link) : null;
                        found.add(reply == null
                                ? new Screen(bus, address, vendor, product, name, "", raw == null || raw.length == 0
                                ? "не ответил на приветствие — похоже, у него другой протокол"
                                : "ответил не по протоколу TURZX — сырые байты ниже", link.text(),
                                raw == null ? "" : hex(raw, 64))
                                : new Screen(bus, address, vendor, product, name, firmware(reply), null, link.text(),
                                hex(reply, 64)));
                    } finally {
                        LibUsb.releaseInterface(handle, link.iface());
                    }
                } finally {
                    LibUsb.close(handle);
                }
            }
        } finally {
            LibUsb.freeDeviceList(list, true);
            LibUsb.exit(context);
        }

        return found;
    }

    /** Показать картинку на экране (найденном по шине и адресу); вернёт, что пошло не так, или null. */
    static String show(Screen screen, BufferedImage image, int brightness) {
        var context = new Context();

        if (LibUsb.init(context) != LibUsb.SUCCESS) {
            return "libusb не запустилась";
        }

        var list = new DeviceList();

        try {
            LibUsb.getDeviceList(context, list);

            for (var device : list) {
                if (LibUsb.getBusNumber(device) != screen.bus() || LibUsb.getDeviceAddress(device) != screen.address()) {
                    continue;
                }

                var handle = new DeviceHandle();
                var opened = LibUsb.open(device, handle);

                if (opened != LibUsb.SUCCESS) {
                    return explain(opened);
                }

                var link = Link.of(device, handle);

                try {
                    var claimed = LibUsb.claimInterface(handle, link.iface());

                    if (claimed != LibUsb.SUCCESS) {
                        return explain(claimed);
                    }

                    try {
                        drain(link);

                        if (strip(screen.vendor(), screen.product())) {
                            stripHello(link);
                            return stripSend(link, image, brightness) ? null : "полоска не подтвердила картинку";
                        }

                        command(link, SYNC, new byte[0], null);
                        // Свой ролик или обои экрана — остановить, иначе он перерисует нашу картинку
                        command(link, STOP_PLAYBACK, new byte[0], null);

                        for (var i = 0; i < 10; i++) {
                            var busy = command(link, PLAYBACK_BUSY, new byte[0], null);

                            if (busy == null || busy[8] == 0) {
                                break;
                            }

                            Thread.sleep(100);
                        }

                        command(link, BRIGHTNESS, new byte[] {(byte) Math.round(brightness * 102 / 100.0)}, null);
                        var reply = send(link, image);

                        if (reply == null) {
                            return "экран не ответил на картинку";
                        }

                        return (reply[1] & 0xff) == 0xc8 ? null : "экран не принял картинку (код " + (reply[1] & 0xff) + ")";
                    } finally {
                        LibUsb.releaseInterface(handle, link.iface());
                    }
                } finally {
                    LibUsb.close(handle);
                }
            }

            return "экран не найден — переподключили?";
        } catch (Exception e) {
            return e.getMessage();
        } finally {
            LibUsb.freeDeviceList(list, true);
            LibUsb.exit(context);
        }
    }

    /**
     * Постоянное подключение к одному экрану — для живого табло: открыли
     * один раз, остановили его собственный ролик и дальше только шлём кадры.
     */
    static final class Session implements AutoCloseable {

        private final Context context = new Context();
        private final DeviceList list = new DeviceList();
        private DeviceHandle handle;
        private Link link;

        private final boolean strip;
        private int level;

        Session(Screen screen, int brightness) throws IOException {
            this(screen, brightness, 0);
        }

        /** @param nth какой по счёту из одинаковых экранов (две полоски — одна модель) */
        Session(Screen screen, int brightness, int nth) throws IOException {
            strip = strip(screen.vendor(), screen.product());
            level = brightness;
            var skip = nth;

            if (LibUsb.init(context) != LibUsb.SUCCESS) {
                throw new IOException("libusb не запустилась");
            }

            LibUsb.getDeviceList(context, list);

            for (var device : list) {
                var descriptor = new DeviceDescriptor();

                if (LibUsb.getDeviceDescriptor(device, descriptor) != LibUsb.SUCCESS
                        || (descriptor.idVendor() & 0xffff) != screen.vendor()
                        || (descriptor.idProduct() & 0xffff) != screen.product()) {
                    continue;
                }

                if (skip-- > 0) {
                    continue;
                }

                var candidate = new DeviceHandle();
                var opened = LibUsb.open(device, candidate);

                if (opened != LibUsb.SUCCESS) {
                    close();
                    throw new IOException(explain(opened));
                }

                var candidateLink = Link.of(device, candidate);
                var claimed = LibUsb.claimInterface(candidate, candidateLink.iface());

                if (claimed != LibUsb.SUCCESS) {
                    LibUsb.close(candidate);
                    close();
                    throw new IOException(explain(claimed));
                }

                handle = candidate;
                link = candidateLink;
                break;
            }

            if (handle == null) {
                close();
                throw new IOException("экран не найден — отключён?");
            }

            drain(link);

            if (strip) {
                stripHello(link);
                return;
            }

            command(link, SYNC, new byte[0], null);
            command(link, STOP_PLAYBACK, new byte[0], null);

            for (var i = 0; i < 10; i++) {
                var busy = command(link, PLAYBACK_BUSY, new byte[0], null);

                if (busy == null || busy.length < 9 || busy[8] == 0) {
                    break;
                }

                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            brightness(brightness);
        }

        void brightness(int percent) {
            level = percent;

            if (strip) {
                return;
            }

            command(link, BRIGHTNESS, new byte[] {(byte) Math.round(Math.max(0, Math.min(100, percent)) * 102 / 100.0)},
                    null);
        }

        /** Кадр на экран; false — экран не принял (отключили или сбой). */
        boolean show(BufferedImage image) throws IOException {
            if (strip) {
                return stripSend(link, image, level);
            }

            var reply = send(link, image);
            return reply != null && (reply[1] & 0xff) == 0xc8;
        }

        @Override
        public void close() {
            if (handle != null) {
                LibUsb.releaseInterface(handle, link.iface());
                LibUsb.close(handle);
                handle = null;
            }

            LibUsb.freeDeviceList(list, true);
            LibUsb.exit(context);
        }
    }

    /** Повернуть картинку на 90/180/270° по часовой — экран бывает поставлен боком. */
    static BufferedImage rotate(BufferedImage image, int degrees) {
        var turns = ((degrees / 90) % 4 + 4) % 4;

        if (turns == 0) {
            return image;
        }

        var width = turns % 2 == 0 ? image.getWidth() : image.getHeight();
        var height = turns % 2 == 0 ? image.getHeight() : image.getWidth();
        var out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var g = out.createGraphics();
        g.translate(width / 2.0, height / 2.0);
        g.rotate(Math.toRadians(turns * 90));
        g.translate(-image.getWidth() / 2.0, -image.getHeight() / 2.0);
        g.drawImage(image, 0, 0, null);
        g.dispose();
        return out;
    }

    /** Прочитать накопившиеся старые ответы, чтобы не путать их с новыми. */
    private static void drain(Link link) {
        for (var i = 0; i < 16; i++) {
            var in = ByteBuffer.allocateDirect(1024);
            var got = IntBuffer.allocate(1);

            if (link.transfer(link.in(), in, got, 50) != LibUsb.SUCCESS) {
                return;
            }
        }
    }

    /**
     * Команда и ответ. Ответ — 512 байт и пустой пакет, поэтому читаем
     * сразу 1024: иначе ответы съезжают на одну команду. Чужие ответы
     * (с другим номером команды) пропускаем.
     */
    private static byte[] command(Link link, int command, byte[] args, byte[] payload) {
        var packet = header(command, args, now());
        var out = ByteBuffer.allocateDirect(packet.length + (payload == null ? 0 : payload.length));
        out.put(packet);

        if (payload != null) {
            out.put(payload);
        }

        out.flip();
        var sent = IntBuffer.allocate(1);

        if (link.transfer(link.out(), out, sent, 5000) != LibUsb.SUCCESS) {
            return null;
        }

        for (var attempt = 0; attempt < 4; attempt++) {
            var in = ByteBuffer.allocateDirect(1024);
            var got = IntBuffer.allocate(1);

            if (link.transfer(link.in(), in, got, 1500) != LibUsb.SUCCESS) {
                return null;
            }

            var reply = new byte[Math.max(0, got.get(0))];
            in.get(reply);

            if (reply.length >= 2 && (reply[0] & 0xff) == command) {
                return reply;
            }
        }

        return null;
    }

    /**
     * Кадр на экран: PNG с прозрачностью (команда 102), как шлёт
     * turing-smart-screen-python, — старые прошивки раскладывают JPEG косо.
     * Не влез PNG в 1 МБ — тогда JPEG.
     */
    private static byte[] send(Link link, BufferedImage image) throws IOException {
        var png = png(image);

        if (png.length <= MAX_JPEG) {
            return command(link, SHOW_PNG, be32(png.length), png);
        }

        var data = jpeg(image);
        return command(link, SHOW_JPEG, be32(data.length), data);
    }

    /** PNG с альфа-каналом: экран неправильно читает PNG без прозрачности (turzx-linux). */
    static byte[] png(BufferedImage image) throws IOException {
        var argb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        var g = argb.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.dispose();
        var out = new ByteArrayOutputStream();
        ImageIO.write(argb, "png", out);
        return out.toByteArray();
    }

    /** Прочитать всё, что устройство прислало само, без разбора; null — ничего. */
    private static byte[] rawRead(Link link) {
        var in = ByteBuffer.allocateDirect(1024);
        var got = IntBuffer.allocate(1);

        if (link.transfer(link.in(), in, got, 800) != LibUsb.SUCCESS || got.get(0) <= 0) {
            return null;
        }

        var data = new byte[got.get(0)];
        in.get(data);
        return data;
    }

    /** Первые байты ответа в hex — по ним видно, что ещё сообщает экран. */
    static String hex(byte[] data, int limit) {
        var text = new StringBuilder();

        for (var i = 0; i < Math.min(limit, data.length); i++) {
            text.append(String.format("%02x", data[i] & 0xff)).append(i % 16 == 15 ? "  " : " ");
        }

        return text.toString().strip();
    }

    /** Ошибка libusb → что сделать. */
    static String explain(int code) {
        if (code == LibUsb.ERROR_ACCESS || code == LibUsb.ERROR_BUSY) {
            return "экран занят — закрой программу JONSBO (и в трее тоже) и попробуй снова";
        }

        if (code == LibUsb.ERROR_NOT_SUPPORTED || code == LibUsb.ERROR_NOT_FOUND) {
            return "у экрана в Windows драйвер, через который нельзя говорить напрямую (" + LibUsb.errorName(code) + ")";
        }

        return LibUsb.errorName(code);
    }
}
