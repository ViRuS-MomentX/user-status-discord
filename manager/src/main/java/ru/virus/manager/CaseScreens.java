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
                        var reply = command(link, SYNC, new byte[0], null);
                        found.add(reply == null
                                ? new Screen(bus, address, vendor, product, name, "", "не ответил на приветствие — "
                                + "похоже, у него другой протокол", link.text(), "")
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
                        var data = jpeg(image);
                        var reply = command(link, SHOW_JPEG, be32(data.length), data);

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

        Session(Screen screen, int brightness) throws IOException {
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
            command(link, BRIGHTNESS, new byte[] {(byte) Math.round(Math.max(0, Math.min(100, percent)) * 102 / 100.0)},
                    null);
        }

        /** Кадр на экран; false — экран не принял (отключили или сбой). */
        boolean show(BufferedImage image) throws IOException {
            var data = jpeg(image);
            var reply = command(link, SHOW_JPEG, be32(data.length), data);
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
