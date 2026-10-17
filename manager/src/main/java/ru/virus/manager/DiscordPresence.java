package ru.virus.manager;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * «Играет в V.I.R.U.S.» в профиле Discord — со своей картинкой.
 *
 * <p>Игре, добавленной в Discord вручную, картинку задать нельзя — отсюда
 * знак вопроса. А программа, которая сама рассказывает о себе через Rich
 * Presence, показывается с картинкой своего приложения Discord. Разговор идёт
 * с Discord на этом же компьютере через его канал discord-ipc: никаких
 * токенов, только номер приложения из Developer Portal.
 */
final class DiscordPresence implements Closeable {

    /** Что показать: две строки под названием и картинка. */
    record Activity(String details, String state, String image, String imageText, long startedAt) {
    }

    private static final int HANDSHAKE = 0;
    private static final int FRAME = 1;
    private static final int CLOSE = 2;

    /** Discord принимает пять обновлений за 20 с — чаще не шлём. */
    private static final long UPDATE_EVERY_MS = 15_000;
    private static final long RETRY_MS = 20_000;

    private final Supplier<String> appId;
    private final Supplier<Activity> activity;
    private final Supplier<List<Path>> unixSockets;
    private final Consumer<String> status;

    private volatile boolean running;
    private volatile Thread thread;
    private volatile Pipe pipe;

    /**
     * @param appId номер приложения Discord; пустой — показывать нечего
     * @param activity что показывать сейчас — спрашивается при каждом обновлении
     * @param status куда сообщать, что со связью: «показывается», «Discord не запущен»…
     */
    DiscordPresence(Supplier<String> appId, Supplier<Activity> activity, Consumer<String> status) {
        this(appId, activity, DiscordPresence::defaultSockets, status);
    }

    DiscordPresence(Supplier<String> appId, Supplier<Activity> activity, Supplier<List<Path>> unixSockets,
                    Consumer<String> status) {
        this.appId = appId;
        this.activity = activity;
        this.unixSockets = unixSockets;
        this.status = status;
    }

    synchronized void start() {
        if (running) {
            return;
        }

        running = true;
        var worker = new Thread(this::loop, "virus-discord-presence");
        worker.setDaemon(true);
        thread = worker;
        worker.start();
    }

    /** Перечитать настройки сейчас, а не через 15 секунд. */
    void poke() {
        var worker = thread;

        if (worker != null) {
            worker.interrupt();
        }
    }

    @Override
    public synchronized void close() {
        running = false;
        var worker = thread;
        thread = null;
        closePipe();

        if (worker != null) {
            worker.interrupt();
        }
    }

    private void loop() {
        String connectedFor = null;
        Activity shown = null;

        while (running) {
            var id = appId.get().strip();

            try {
                if (!id.matches("\\d{15,25}")) {
                    closePipe();
                    connectedFor = null;
                    status.accept(id.isEmpty() ? "выключено" : "номер приложения — только цифры");
                    pause(RETRY_MS);
                    continue;
                }

                // Сменили номер приложения — переподключаемся под новым
                if (pipe == null || !id.equals(connectedFor)) {
                    closePipe();
                    pipe = connect(id);
                    connectedFor = id;
                    shown = null;
                }

                var next = activity.get();

                if (!next.equals(shown)) {
                    send(pipe, FRAME, setActivity(next));
                    var answer = read(pipe);

                    if (answer.contains("\"evt\":\"ERROR\"")) {
                        status.accept("Discord отказал: " + message(answer));
                    } else {
                        status.accept("показывается в Discord");
                    }

                    shown = next;
                }

                pause(UPDATE_EVERY_MS);
            } catch (IOException e) {
                closePipe();
                connectedFor = null;
                status.accept(e.getMessage());
                pause(RETRY_MS);
            }
        }
    }

    private void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            // разбудили — пора обновить
        }
    }

    private Pipe connect(String id) throws IOException {
        var candidates = new ArrayList<String>();
        var windows = System.getProperty("os.name", "").toLowerCase().contains("win");

        if (windows) {
            for (var i = 0; i < 10; i++) {
                candidates.add("\\\\.\\pipe\\discord-ipc-" + i);
            }
        } else {
            unixSockets.get().forEach(path -> candidates.add(path.toString()));
        }

        for (var candidate : candidates) {
            Pipe opened;

            try {
                opened = windows ? Pipe.windows(candidate) : Pipe.unix(Path.of(candidate));
            } catch (IOException e) {
                continue;
            }

            try {
                send(opened, HANDSHAKE, Json.write(Map.of("v", 1, "client_id", id)));
                var ready = read(opened);

                if (!ready.contains("\"READY\"")) {
                    opened.close();
                    throw new IOException("Discord не принял номер приложения: " + message(ready));
                }

                return opened;
            } catch (IOException e) {
                opened.close();
                throw e;
            }
        }

        throw new IOException("Discord не запущен");
    }

    private void closePipe() {
        var open = pipe;
        pipe = null;

        if (open != null) {
            try {
                send(open, CLOSE, "{}");
            } catch (IOException ignored) {
                // закрываем всё равно
            }

            open.close();
        }
    }

    static String setActivity(Activity activity) {
        var body = new LinkedHashMap<String, Object>();

        if (!activity.details().isBlank()) {
            body.put("details", cut(activity.details()));
        }

        if (!activity.state().isBlank()) {
            body.put("state", cut(activity.state()));
        }

        if (activity.startedAt() > 0) {
            body.put("timestamps", Map.of("start", activity.startedAt() / 1000));
        }

        if (!activity.image().isBlank()) {
            var assets = new LinkedHashMap<String, Object>();
            assets.put("large_image", activity.image());

            if (!activity.imageText().isBlank()) {
                assets.put("large_text", cut(activity.imageText()));
            }

            body.put("assets", assets);
        }

        var args = new LinkedHashMap<String, Object>();
        args.put("pid", ProcessHandle.current().pid());
        args.put("activity", body);

        var command = new LinkedHashMap<String, Object>();
        command.put("cmd", "SET_ACTIVITY");
        command.put("args", args);
        command.put("nonce", UUID.randomUUID().toString());
        return Json.write(command);
    }

    /** Строки Discord длиннее 128 знаков не принимает вовсе. */
    private static String cut(String text) {
        return text.length() <= 128 ? text : text.substring(0, 127) + "…";
    }

    private static String message(String answer) {
        try {
            var json = Json.object(answer);

            if (json.get("data") instanceof Map<?, ?> data && data.get("message") != null) {
                return String.valueOf(data.get("message"));
            }

            if (json.get("message") != null) {
                return String.valueOf(json.get("message"));
            }
        } catch (RuntimeException ignored) {
            // ответ не JSON — покажем как есть
        }

        return answer.length() > 200 ? answer.substring(0, 200) : answer;
    }

    static void send(Pipe pipe, int op, String json) throws IOException {
        var payload = json.getBytes(StandardCharsets.UTF_8);
        var frame = ByteBuffer.allocate(8 + payload.length).order(ByteOrder.LITTLE_ENDIAN);
        frame.putInt(op).putInt(payload.length).put(payload);
        pipe.out().write(frame.array());
        pipe.out().flush();
    }

    static String read(Pipe pipe) throws IOException {
        var header = ByteBuffer.wrap(pipe.in().readNBytes(8)).order(ByteOrder.LITTLE_ENDIAN);

        if (header.remaining() < 8) {
            throw new IOException("Discord закрыл связь");
        }

        var op = header.getInt();
        var length = header.getInt();

        if (length < 0 || length > 1 << 20) {
            throw new IOException("Discord прислал непонятное");
        }

        var text = new String(pipe.in().readNBytes(length), StandardCharsets.UTF_8);

        if (op == CLOSE) {
            throw new IOException("Discord закрыл связь: " + message(text));
        }

        return text;
    }

    /** Где Discord слушает на Linux и macOS. */
    private static List<Path> defaultSockets() {
        var folders = new ArrayList<String>();

        for (var variable : List.of("XDG_RUNTIME_DIR", "TMPDIR", "TMP", "TEMP")) {
            var value = System.getenv(variable);

            if (value != null && !value.isBlank()) {
                folders.add(value);
            }
        }

        folders.add("/tmp");
        var result = new ArrayList<Path>();

        for (var folder : folders) {
            for (var sub : List.of("", "app/com.discordapp.Discord", "snap.discord")) {
                for (var i = 0; i < 10; i++) {
                    var path = Path.of(folder, sub, "discord-ipc-" + i);

                    if (Files.exists(path)) {
                        result.add(path);
                    }
                }
            }
        }

        return result;
    }

    /** Канал до Discord: именованный канал Windows или сокет на Linux и macOS. */
    record Pipe(InputStream in, OutputStream out, Closeable handle) implements Closeable {

        static Pipe windows(String name) throws IOException {
            var file = new RandomAccessFile(name, "rw");
            return new Pipe(new InputStream() {
                @Override
                public int read() throws IOException {
                    return file.read();
                }

                @Override
                public int read(byte[] buffer, int offset, int length) throws IOException {
                    return file.read(buffer, offset, length);
                }
            }, new OutputStream() {
                @Override
                public void write(int value) throws IOException {
                    file.write(value);
                }

                @Override
                public void write(byte[] buffer, int offset, int length) throws IOException {
                    file.write(buffer, offset, length);
                }
            }, file);
        }

        static Pipe unix(Path path) throws IOException {
            var channel = SocketChannel.open(StandardProtocolFamily.UNIX);

            try {
                channel.connect(UnixDomainSocketAddress.of(path));
            } catch (IOException e) {
                channel.close();
                throw e;
            }

            return new Pipe(Channels.newInputStream(channel), Channels.newOutputStream(channel), channel);
        }

        @Override
        public void close() {
            try {
                Objects.requireNonNull(handle).close();
            } catch (IOException ignored) {
                // уже закрыт
            }
        }
    }
}
