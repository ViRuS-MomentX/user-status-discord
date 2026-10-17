package ru.virus.manager;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.awt.AWTException;
import java.awt.GraphicsEnvironment;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * V.I.R.U.S. — Versatile Instance Runner &amp; Unified Supervisor.
 *
 * <p>Окно, которое запускает ботов, следит, чтобы они работали, показывает их
 * консоль и правит их настройки. Закрытое окно прячется в трей — боты при этом
 * продолжают работать.
 */
public final class Virus {

    static final String NAME = "V.I.R.U.S.";

    /** Порт замка «одна копия». Бота голосовых каналов — 7373, этот рядом. */
    static final int PORT = 7474;

    private static TrayIcon trayIcon;

    private Virus() {
    }

    public static void main(String[] args) throws Exception {
        var background = Arrays.asList(args).contains("--background");

        if (GraphicsEnvironment.isHeadless()) {
            System.err.println(NAME + " — оконная программа, а экрана нет.");
            System.exit(1);
        }

        var port = Integer.parseInt(System.getenv().getOrDefault("VIRUS_PORT", String.valueOf(PORT)));
        var instance = SingleInstance.acquire(port);

        if (instance == null) {
            // Уже работает — он сам покажет окно по нашей просьбе
            return;
        }

        Theme.install();

        var home = home();
        var supervisor = new Supervisor(home, Supervisor.findJava());
        var autostart = new Autostart(Autostart.windowsStartupFolder(), Supervisor.findJavaw(), ownJar());

        try {
            supervisor.load();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(null, "Не прочитался processes.json: " + e.getMessage()
                    + "\nПоправь или удали его — тогда появятся процессы по умолчанию.", NAME, JOptionPane.ERROR_MESSAGE);
            System.exit(2);
            return;
        }

        // Задачи — до процессов: их запись быстрая, а выключение ПК ждать не станет
        var tasks = new TaskStore(home.resolve("tasks.json"));
        String tasksProblem = null;

        try {
            tasks.load();
        } catch (Exception e) {
            tasksProblem = e.getMessage();
        }

        var tasksWarning = tasksProblem;

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                tasks.saveNow();
            } catch (Exception e) {
                say("Задачи не сохранились: " + e.getMessage());
            }

            say("Останавливаю процессы…");
            supervisor.stopAll();
            instance.release();
        }, "virus-shutdown"));

        Runnable quit = () -> System.exit(0);
        var hasTray = SystemTray.isSupported();

        SwingUtilities.invokeAndWait(() -> {
            // Конфиг ищется там, где живёт бот: после переезда — в папке его проекта
            var window = new MainWindow(supervisor,
                    new ConfigService(() -> supervisor.botFolder().resolve("config.properties")),
                    tasks, autostart, quit, hasTray);
            instance.onShow(() -> SwingUtilities.invokeLater(window::reveal));

            if (hasTray) {
                tray(window, quit);
            }

            window.refreshMissing();

            // Без значка в трее спрятанное окно было бы не найти
            if (!background || trayIcon == null) {
                window.reveal();
            }

            if (tasksWarning != null) {
                JOptionPane.showMessageDialog(window, "Задачи не прочитались: " + tasksWarning
                        + "\nСтарый файл будет сохранён рядом как tasks.json.broken.", NAME, JOptionPane.WARNING_MESSAGE);
            }
        });

        supervisor.startAutostart();
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

    /** Значок в трее: двойной клик — окно, правый — меню. */
    private static void tray(MainWindow window, Runnable quit) {
        var menu = new PopupMenu();
        var open = new MenuItem("Открыть V.I.R.U.S.");
        open.addActionListener(event -> SwingUtilities.invokeLater(window::reveal));
        var exit = new MenuItem("Выход (остановить всё)");
        exit.addActionListener(event -> new Thread(quit, "virus-quit").start());
        menu.add(open);
        menu.addSeparator();
        menu.add(exit);

        var icon = new TrayIcon(AppIcon.image(64), NAME, menu);
        icon.setImageAutoSize(true);
        icon.addActionListener(event -> SwingUtilities.invokeLater(window::reveal));

        try {
            SystemTray.getSystemTray().add(icon);
            trayIcon = icon;
        } catch (AWTException e) {
            say("Значок в трее не встал: " + e.getMessage());
        }
    }

    /** Всплывающее уведомление у значка в трее. */
    static void notifyTray(String title, String text) {
        if (trayIcon != null) {
            trayIcon.displayMessage(title, text, TrayIcon.MessageType.INFO);
        }
    }

    static void say(String text) {
        System.out.println("[" + NAME + "] " + text);
    }
}
