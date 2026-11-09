package ru.virus.manager;

import javax.swing.JComponent;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Плитка-картинка для меню экранов: живая миниатюра (что будет на экране)
 * и подпись снизу. Выбранная — в зелёной рамке. Без подписи и без щелчка —
 * это просто «окошко» экрана, где видно, что на нём сейчас.
 */
final class PreviewTile extends JComponent {

    /** Что нарисовать: ширина, высота, момент времени. */
    interface Picture {
        BufferedImage render(int width, int height, long now);
    }

    private static final Set<PreviewTile> ALL = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private static final Timer TIMER = new Timer(120, e -> {
        PreviewTile[] tiles;

        synchronized (ALL) {
            tiles = ALL.toArray(PreviewTile[]::new);
        }

        for (var tile : tiles) {
            if (tile.isShowing()) {
                tile.repaint();
            }
        }
    });

    private final Picture picture;
    private final String caption;
    private final int pictureW;
    private final int pictureH;
    private boolean selected;
    private boolean hover;
    private Runnable onClick;

    PreviewTile(Picture picture, String caption, int width, int height) {
        this.picture = picture;
        this.caption = caption;
        this.pictureW = width;
        this.pictureH = height;
        setOpaque(false);
        setPreferredSize(new Dimension(width + 8, height + 8 + (caption == null ? 0 : 22)));
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                hover = onClick != null;
                repaint();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hover = false;
                repaint();
            }

            @Override
            public void mousePressed(MouseEvent e) {
                if (onClick != null) {
                    onClick.run();
                }
            }
        });
        ALL.add(this);

        if (!TIMER.isRunning()) {
            TIMER.start();
        }
    }

    PreviewTile onClick(Runnable action) {
        onClick = action;
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return this;
    }

    void setSelected(boolean value) {
        selected = value;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        var g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        var x = 4;
        var y = 4;
        BufferedImage image;

        try {
            image = picture.render(pictureW, pictureH, System.currentTimeMillis());
        } catch (RuntimeException e) {
            image = null;
        }

        // Рамка «экрана»: тёмная, со скруглением, как у настоящего дисплея
        g.setColor(new Color(8, 10, 9));
        g.fill(new RoundRectangle2D.Float(x - 3, y - 3, pictureW + 6, pictureH + 6, 12, 12));

        if (image != null) {
            var clip = g.getClip();
            g.clip(new RoundRectangle2D.Float(x, y, pictureW, pictureH, 8, 8));
            g.drawImage(image, x, y, pictureW, pictureH, null);
            g.setClip(clip);
        } else {
            g.setColor(Theme.MUTED);
            g.setFont(Theme.ui.deriveFont(12f));
            g.drawString("нет картинки", x + 10, y + pictureH / 2);
        }

        if (selected || hover) {
            g.setStroke(new BasicStroke(selected ? 2.5f : 1.5f));
            g.setColor(selected ? Theme.ACCENT : Theme.LINE_2);
            g.draw(new RoundRectangle2D.Float(x - 3, y - 3, pictureW + 6, pictureH + 6, 12, 12));
        }

        if (caption != null) {
            g.setFont(Theme.ui.deriveFont(selected ? Font.BOLD : Font.PLAIN, 12f));
            g.setColor(selected ? Theme.ACCENT : Theme.TEXT);
            var text = caption;
            var metrics = g.getFontMetrics();

            while (metrics.stringWidth(text) > pictureW + 4 && text.length() > 3) {
                text = text.substring(0, text.length() - 2) + "…";
            }

            g.drawString(text, x + (pictureW - metrics.stringWidth(text)) / 2f, y + pictureH + 18);
        }

        g.dispose();
    }
}
