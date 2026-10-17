package ru.virus.manager;

import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatrixBackgroundTest {

    MatrixBackground background(int width, int height) {
        var background = new MatrixBackground();
        background.setSize(width, height);
        // Без окна событие изменения размера не придёт — шаг сам заведёт сетку
        background.step();
        return background;
    }

    @Test
    void clickFlipsDigitUnderCursorAndStartsRipple() {
        var background = background(400, 300);
        var before = background.digitAt(3, 2);

        // Клетка 16×20: точка (55, 45) — третий столбец, вторая строка, считая с нуля
        background.touch(new Point(55, 45));

        assertNotEquals(before, background.digitAt(3, 2));
        assertEquals(1, background.rippleCount());
    }

    @Test
    void ripplesFadeAway() throws Exception {
        var background = background(200, 200);
        background.touch(new Point(100, 100));
        background.step();
        assertEquals(1, background.rippleCount());

        Thread.sleep(2400);
        background.step();
        assertEquals(0, background.rippleCount(), "волна должна угаснуть");
    }

    @Test
    void manyClicksDoNotPileUp() {
        var background = background(300, 300);

        for (var i = 0; i < 50; i++) {
            background.touch(new Point(i * 5, i * 5));
        }

        assertTrue(background.rippleCount() <= 12);
    }

    @Test
    void clickOutsideGridStillRipples() {
        var background = background(100, 100);
        background.touch(new Point(-5, 500));
        assertEquals(1, background.rippleCount());
    }

    @Test
    void paintsWithoutWindow() {
        var background = background(320, 200);
        background.touch(new Point(160, 100));
        background.step();

        var image = new BufferedImage(320, 200, BufferedImage.TYPE_INT_ARGB);
        var g = image.createGraphics();
        background.paint(g);
        g.dispose();

        // Где-то на картинке должен быть зелёный — цифры или кольцо
        var green = false;
        for (var x = 0; x < 320 && !green; x += 2) {
            for (var y = 0; y < 200 && !green; y += 2) {
                var rgb = image.getRGB(x, y);
                green = ((rgb >> 8) & 0xFF) > 120 && ((rgb >> 16) & 0xFF) < 160;
            }
        }
        assertTrue(green);
    }

    @Test
    void iconFileHasAllSizes() {
        var ico = AppIcon.ico();
        assertEquals(0, ico[0]);
        assertEquals(1, ico[2]);
        assertEquals(4, ico[4], "16, 32, 48 и 256");
        // Первая картинка внутри — PNG
        var offset = (ico[18] & 0xFF) | (ico[19] & 0xFF) << 8;
        assertEquals((byte) 0x89, ico[offset]);
        assertEquals('P', ico[offset + 1]);
    }
}
