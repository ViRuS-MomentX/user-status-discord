package ru.virus.manager;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Значок V.I.R.U.S. — тот же, что favicon.svg: зелёное «V» в круге с тремя
 * точками. Рисуется линиями, а не картинкой, поэтому чёткий в любом размере:
 * в трее, на панели задач и на ярлыке.
 */
final class AppIcon {

    static final Color DARK = new Color(0x0B, 0x0F, 0x0D);
    static final Color ACCENT = new Color(0x39, 0xFF, 0x8A);

    private AppIcon() {
    }

    static BufferedImage image(int size) {
        var image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        var g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        // Рисуем в координатах 64×64, как в favicon.svg, и масштабируем
        var scale = size / 64.0;
        g.scale(scale, scale);

        g.setColor(DARK);
        g.fill(new Ellipse2D.Double(3, 3, 58, 58));
        g.setColor(ACCENT);
        g.setStroke(new BasicStroke(4f));
        g.draw(new Ellipse2D.Double(3, 3, 58, 58));

        var v = new Path2D.Double();
        v.moveTo(19, 20);
        v.lineTo(32, 46);
        v.lineTo(45, 20);
        g.setStroke(new BasicStroke(6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(v);

        for (var dot : new double[][] { { 32, 12 }, { 52, 32 }, { 12, 32 } }) {
            g.fill(new Ellipse2D.Double(dot[0] - 3, dot[1] - 3, 6, 6));
        }

        g.dispose();
        return image;
    }

    static byte[] png(int size) {
        var out = new ByteArrayOutputStream();

        try {
            ImageIO.write(image(size), "png", out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        return out.toByteArray();
    }

    /**
     * Файл .ico для ярлыка Windows. Внутри — картинки PNG нескольких размеров:
     * так ico умеет с Windows Vista, и Проводник сам берёт подходящую.
     */
    static byte[] ico() {
        int[] sizes = { 16, 32, 48, 256 };
        var images = new byte[sizes.length][];
        var total = 6 + 16 * sizes.length;

        for (var i = 0; i < sizes.length; i++) {
            images[i] = png(sizes[i]);
            total += images[i].length;
        }

        var buffer = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putShort((short) 0);            // зарезервировано
        buffer.putShort((short) 1);            // 1 — значок, а не курсор
        buffer.putShort((short) sizes.length);

        var offset = 6 + 16 * sizes.length;

        for (var i = 0; i < sizes.length; i++) {
            // 256 в одном байте не помещается — формат пишет его нулём
            buffer.put((byte) (sizes[i] >= 256 ? 0 : sizes[i]));
            buffer.put((byte) (sizes[i] >= 256 ? 0 : sizes[i]));
            buffer.put((byte) 0);              // палитры нет
            buffer.put((byte) 0);
            buffer.putShort((short) 1);        // плоскостей
            buffer.putShort((short) 32);       // бит на точку
            buffer.putInt(images[i].length);
            buffer.putInt(offset);
            offset += images[i].length;
        }

        for (var image : images) {
            buffer.put(image);
        }

        return buffer.array();
    }
}
