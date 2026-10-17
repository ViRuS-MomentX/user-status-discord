package ru.virus.manager;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.function.DoubleConsumer;

/**
 * Связь с серверами и скорость интернета.
 *
 * <p>Скорость меряем у Cloudflare (speed.cloudflare.com): он открыт из России и
 * отдаёт сколько попросишь. Сервер «достучались» — если ответил хоть чем-то:
 * ошибка 401 от Discord значит, что связь есть, просто без токена.
 */
final class NetProbe {

    static final String SPEED = "https://speed.cloudflare.com";

    /** Дольше меряем зря: скорость к этому времени уже понятна. */
    static final long TEST_MS = 8_000;

    record Result(boolean ok, long ms, String note) {
    }

    private final HttpClient http;
    private final String speedBase;

    NetProbe() {
        this(SPEED);
    }

    NetProbe(String speedBase) {
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(6))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.speedBase = speedBase;
    }

    /** Дотягиваемся ли до адреса и как быстро: лучшее из двух попыток. */
    Result check(String url) {
        long best = Long.MAX_VALUE;
        Exception failure = null;

        for (var attempt = 0; attempt < 2; attempt++) {
            var started = System.nanoTime();

            try {
                http.send(HttpRequest.newBuilder(URI.create(url))
                                .timeout(Duration.ofSeconds(8))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.discarding());
                best = Math.min(best, (System.nanoTime() - started) / 1_000_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Result(false, 0, "прервано");
            } catch (Exception e) {
                failure = e;
            }
        }

        if (best != Long.MAX_VALUE) {
            return new Result(true, best, "");
        }

        return new Result(false, 0, explain(failure));
    }

    /** Почему не достучались — словами, которые подсказывают, что делать. */
    static String explain(Throwable failure) {
        var text = failure == null ? "" : String.valueOf(failure.getMessage()).toLowerCase();

        if (failure instanceof HttpTimeoutException || text.contains("timed out")) {
            return "не отвечает — похоже на блокировку или нет интернета";
        }

        if (text.contains("handshake") || text.contains("reset")) {
            return "соединение обрывают — похоже на блокировку провайдером";
        }

        if (failure instanceof UnknownHostException || hasCause(failure, UnknownHostException.class)) {
            return "адрес не находится — проблема с DNS или нет интернета";
        }

        if (failure instanceof ConnectException || hasCause(failure, ConnectException.class)) {
            return "соединение не установилось";
        }

        return failure == null ? "нет ответа" : String.valueOf(failure.getMessage());
    }

    private static boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
        for (var cause = failure; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return true;
            }
        }

        return false;
    }

    /** Задержка до сервера скорости, мс: лучшее из пяти пустых запросов. */
    long ping() throws IOException {
        long best = Long.MAX_VALUE;

        for (var i = 0; i < 5; i++) {
            var started = System.nanoTime();
            send(HttpRequest.newBuilder(URI.create(speedBase + "/__down?bytes=0")).timeout(Duration.ofSeconds(8))
                    .GET().build(), HttpResponse.BodyHandlers.discarding());
            best = Math.min(best, (System.nanoTime() - started) / 1_000_000);
        }

        return best;
    }

    /**
     * Скорость скачивания, Мбит/с. Качаем, пока не кончится время или файл.
     *
     * @param progress текущая скорость по ходу замера
     */
    double download(DoubleConsumer progress) throws IOException {
        var response = send(HttpRequest.newBuilder(URI.create(speedBase + "/__down?bytes=200000000"))
                .timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());

        var buffer = new byte[64 * 1024];
        var started = System.nanoTime();
        long bytes = 0;
        long lastReport = 0;

        try (var in = response.body()) {
            int read;

            while ((read = in.read(buffer)) > 0) {
                bytes += read;
                var elapsed = (System.nanoTime() - started) / 1_000_000;

                if (elapsed - lastReport > 150) {
                    lastReport = elapsed;
                    progress.accept(mbps(bytes, elapsed));
                }

                if (elapsed > TEST_MS) {
                    break;
                }
            }
        }

        var result = mbps(bytes, (System.nanoTime() - started) / 1_000_000);
        progress.accept(result);
        return result;
    }

    /** Скорость отдачи, Мбит/с. */
    double upload(DoubleConsumer progress) throws IOException {
        var started = new long[] { 0 };
        var sent = new long[] { 0 };
        var limit = 100_000_000L;

        // Тело «рождается» на лету и обрывается по времени — держать 100 МБ в памяти незачем
        InputStream body = new InputStream() {
            private long lastReport;

            @Override
            public int read() {
                var buffer = new byte[1];
                return read(buffer, 0, 1) < 0 ? -1 : buffer[0] & 0xFF;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) {
                if (started[0] == 0) {
                    started[0] = System.nanoTime();
                }

                var elapsed = (System.nanoTime() - started[0]) / 1_000_000;

                if (sent[0] >= limit || elapsed > TEST_MS) {
                    return -1;
                }

                var count = (int) Math.min(length, limit - sent[0]);
                java.util.Arrays.fill(buffer, offset, offset + count, (byte) 'x');
                sent[0] += count;

                if (elapsed - lastReport > 150) {
                    lastReport = elapsed;
                    progress.accept(mbps(sent[0], elapsed));
                }

                return count;
            }
        };

        send(HttpRequest.newBuilder(URI.create(speedBase + "/__up"))
                .timeout(Duration.ofSeconds(40))
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> body))
                .build(), HttpResponse.BodyHandlers.discarding());

        var result = mbps(sent[0], (System.nanoTime() - started[0]) / 1_000_000);
        progress.accept(result);
        return result;
    }

    static double mbps(long bytes, long millis) {
        return millis <= 0 ? 0 : bytes * 8.0 / 1000 / millis;
    }

    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws IOException {
        try {
            return http.send(request, handler);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("прервано");
        } catch (IOException e) {
            throw new IOException(explain(e), e);
        }
    }
}
