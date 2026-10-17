package ru.virus.manager;

import java.awt.AWTException;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.BindException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * V.I.R.U.S. — Versatile Instance Runner &amp; Unified Supervisor.
 *
 * <p>Запускает ботов, следит, чтобы они работали, показывает их консоль и
 * правит их настройки. Интерфейс — страница в браузере на 127.0.0.1, рядом
 * значок в трее.
 */
public final class Virus {

    static final String NAME = "V.I.R.U.S.";

    /** Порт панели. Свой у бота голосовых каналов — 7373, этот рядом. */
    static final int PORT = 7474;

    private Virus() {
    }

    public static void main(String[] args) throws Exception {
        var background = java.util.Arrays.asList(args).contains("--background");
        var home = home();
        var port = Integer.parseInt(System.getenv().getOrDefault("VIRUS_PORT", String.valueOf(PORT)));

        var supervisor = new Supervisor(home, Supervisor.findJava());
        var autostart = new Autostart(Autostart.windowsStartupFolder(), Supervisor.findJavaw(), ownJar());

        WebServer web;

        try {
            web = new WebServer(port, supervisor, home.resolve("config.properties"), autostart,
                    () -> System.exit(0));
        } catch (BindException e) {
            // Уже работает — второй копии не нужно, просто покажем первую
            say("Уже запущен — открываю панель.");
            open("http://127.0.0.1:" + port + "/");
            return;
        }

        supervisor.load();
        web.start();
        say("Панель: " + web.url() + "  (папка " + home + ")");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            say("Останавливаю процессы…");
            supervisor.stopAll();
            web.stop();
        }, "virus-shutdown"));

        tray(web.url());
        supervisor.startAutostart();

        if (!background) {
            open(web.url());
        }
    }

    /** Папка V.I.R.U.S.: где лежит virus.jar, а рядом с ним — боты и их настройки. */
    static Path home() {
        var forced = System.getenv("VIRUS_HOME");

        if (forced != null && !forced.isBlank()) {
            return Path.of(forced).toAbsolutePath();
        }

        var jar = ownJar();
        return jar != null ? jar.getParent() : Path.of("").toAbsolutePath();
    }

    /** Путь к virus.jar; {@code null}, если запущены не из jar. */
    static Path ownJar() {
        try {
            var location = Path.of(Virus.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return Files.isRegularFile(location) ? location.toAbsolutePath() : null;
        } catch (URISyntaxException | SecurityException | NullPointerException e) {
            return null;
        }
    }

    private static void open(String url) {
        try {
            if (!GraphicsEnvironment.isHeadless() && Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
        } catch (IOException | UnsupportedOperationException e) {
            // ниже — запасной путь
        }

        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            try {
                new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
                return;
            } catch (IOException e) {
                // остаётся только подсказать адрес
            }
        }

        say("Открой в браузере: " + url);
    }

    /** Значок в трее: открыть панель и выйти. */
    private static void tray(String url) {
        if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) {
            return;
        }

        var menu = new PopupMenu();
        var openItem = new MenuItem("Открыть панель");
        openItem.addActionListener(event -> open(url));
        var quitItem = new MenuItem("Выход (остановить всё)");
        quitItem.addActionListener(event -> new Thread(() -> System.exit(0), "virus-quit").start());
        menu.add(openItem);
        menu.addSeparator();
        menu.add(quitItem);

        var icon = new TrayIcon(icon(), NAME, menu);
        icon.setImageAutoSize(true);
        icon.addActionListener(event -> open(url));

        try {
            SystemTray.getSystemTray().add(icon);
        } catch (AWTException e) {
            say("Значок в трее не встал: " + e.getMessage());
        }
    }

    /** Рисуем значок сами: зелёное «V» в круге — чтобы не таскать картинку. */
    static BufferedImage icon() {
        var size = 64;
        var image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        var g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(0x0B, 0x0F, 0x0D));
        g.fillOval(2, 2, size - 4, size - 4);
        g.setColor(new Color(0x39, 0xFF, 0x8A));
        g.setStroke(new BasicStroke(4f));
        g.drawOval(4, 4, size - 8, size - 8);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 38));
        var metrics = g.getFontMetrics();
        g.drawString("V", (size - metrics.stringWidth("V")) / 2, (size + metrics.getAscent() - metrics.getDescent()) / 2);
        g.dispose();
        return image;
    }

    static void say(String text) {
        System.out.println("[" + NAME + "] " + text);
    }
}
