package ru.virus.manager;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Что играет в Spotify — из «медиа-сессий» Windows (то же, что показывает
 * всплывающий регулятор громкости): название, исполнитель, альбом, обложка,
 * позиция и длительность. Ни входа в Spotify, ни ключей не нужно — только
 * приложение Spotify на компьютере.
 */
final class MediaNow {

    /** Трек: позиция — на момент {@code at}, дальше тикает сама, если играет. */
    record Track(String title, String artist, String album, boolean playing, long positionMs, long durationMs, long at,
                 BufferedImage cover) {

        long position(long now) {
            var moved = playing ? now - at : 0;
            return Math.max(0, Math.min(durationMs > 0 ? durationMs : Long.MAX_VALUE, positionMs + moved));
        }
    }

    private volatile Track track;
    /** Почему нет обложки (для строки состояния); пусто — всё хорошо. */
    private volatile String problem = "";

    String problem() {
        return problem;
    }

    private volatile BufferedImage cover;
    private volatile boolean wanted;
    private Thread thread;

    /** Текущий трек Spotify; null — Spotify молчит, закрыт или на паузе дольше минуты. */
    Track now() {
        var current = track;

        if (current == null || !current.playing() && System.currentTimeMillis() - current.at() > 60_000) {
            return null;
        }

        return current;
    }

    synchronized void start() {
        wanted = true;

        if (!SystemInfo.isWindows() || thread != null && thread.isAlive()) {
            return;
        }

        thread = new Thread(this::run, "virus-media-now");
        thread.setDaemon(true);
        thread.start();
    }

    synchronized void stop() {
        wanted = false;
    }

    private void run() {
        while (wanted) {
            Process process = null;

            try {
                var encoded = java.util.Base64.getEncoder().encodeToString(script().getBytes(StandardCharsets.UTF_16LE));
                process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
                        "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded).redirectErrorStream(true).start();
                process.getOutputStream().close();

                try (var in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;

                    while (wanted && (line = in.readLine()) != null) {
                        accept(line.strip());
                    }
                }
            } catch (Exception ignored) {
                // помощник упал — перезапустим
            } finally {
                if (process != null) {
                    process.destroy();
                }
            }

            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** Строка помощника: «none», «cover base64» или JSON с треком. */
    void accept(String line) {
        if (line.equals("none")) {
            track = null;
            return;
        }

        if (line.startsWith("coverr ")) {
            problem = line.substring(7).strip();
            return;
        }

        if (line.startsWith("cover ")) {
            try {
                cover = ImageIO.read(new ByteArrayInputStream(java.util.Base64.getDecoder().decode(line.substring(6))));
                problem = cover == null ? "обложка в непонятном формате" : "";
            } catch (Exception e) {
                cover = null;
                problem = "обложка не прочиталась: " + e.getMessage();
            }

            var current = track;

            if (current != null) {
                track = new Track(current.title(), current.artist(), current.album(), current.playing(),
                        current.positionMs(), current.durationMs(), current.at(), cover);
            }

            return;
        }

        if (line.startsWith("{") && Json.parse(line) instanceof Map<?, ?> map) {
            var title = String.valueOf(map.get("title"));
            var previous = track;

            if (previous == null || !previous.title().equals(title)) {
                if (previous != null) {
                    cover = null;
                }

                lookup(title, text(map.get("artist")));
            }

            track = new Track(title, text(map.get("artist")), text(map.get("album")),
                    Boolean.TRUE.equals(map.get("playing")), number(map.get("position")), number(map.get("duration")),
                    System.currentTimeMillis(), cover);
        }
    }

    /**
     * Запасная обложка: если Windows не отдала свою за 4 секунды — ищем трек
     * в открытом каталоге iTunes по названию и исполнителю (без ключей).
     */
    private void lookup(String title, String artist) {
        var thread = new Thread(() -> {
            try {
                Thread.sleep(4000);
                var current = track;

                if (current == null || !current.title().equals(title) || cover != null) {
                    return;
                }

                var query = java.net.URLEncoder.encode(artist + " " + title, StandardCharsets.UTF_8);
                var http = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10))
                        .followRedirects(java.net.http.HttpClient.Redirect.NORMAL).build();
                var response = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                        "https://itunes.apple.com/search?entity=song&limit=1&term=" + query)).build(),
                        java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                var url = artwork(response.body());

                if (url == null) {
                    return;
                }

                var image = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).build(),
                        java.net.http.HttpResponse.BodyHandlers.ofByteArray());
                var picture = ImageIO.read(new ByteArrayInputStream(image.body()));
                current = track;

                if (picture != null && cover == null && current != null && current.title().equals(title)) {
                    cover = picture;
                    problem = "";
                    track = new Track(current.title(), current.artist(), current.album(), current.playing(),
                            current.positionMs(), current.durationMs(), current.at(), picture);
                }
            } catch (Exception ignored) {
                // нет сети или трека в каталоге — останется значок
            }
        }, "virus-cover-lookup");
        thread.setDaemon(true);
        thread.start();
    }

    /** Ссылка на обложку 600×600 из ответа поиска iTunes; null — не нашлось. */
    static String artwork(String json) {
        if (Json.parse(json) instanceof Map<?, ?> map && map.get("results") instanceof java.util.List<?> results
                && !results.isEmpty() && results.get(0) instanceof Map<?, ?> first && first.get("artworkUrl100") instanceof String url) {
            return url.replace("100x100bb", "600x600bb");
        }

        return null;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static long number(Object value) {
        return value instanceof Number n ? n.longValue() : 0;
    }

    /**
     * Помощник: раз в секунду — Spotify из медиа-сессий Windows; обложка —
     * отдельной строкой, только когда сменился трек.
     */
    static String script() {
        return """
                $ErrorActionPreference = 'Stop'
                $ProgressPreference = 'SilentlyContinue'
                [Console]::OutputEncoding = [Text.Encoding]::UTF8
                Add-Type -AssemblyName System.Runtime.WindowsRuntime
                $null = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType = WindowsRuntime]
                $null = [Windows.Storage.Streams.DataReader, Windows.Storage.Streams, ContentType = WindowsRuntime]
                $asTask = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
                  $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and
                  $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]
                function Await($op, [Type]$type) {
                  $task = $asTask.MakeGenericMethod($type).Invoke($null, @($op))
                  $null = $task.Wait(5000)
                  $task.Result
                }
                $manager = Await ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])
                $last = ''
                while ($true) {
                  try {
                    $session = $null
                    foreach ($s in $manager.GetSessions()) { if ($s.SourceAppUserModelId -match 'Spotify') { $session = $s } }
                    if ($session -eq $null) { [Console]::WriteLine('none'); $last = '' }
                    else {
                      $props = Await ($session.TryGetMediaPropertiesAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties])
                      $time = $session.GetTimelineProperties()
                      $info = $session.GetPlaybackInfo()
                      $playing = $info.PlaybackStatus -eq [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionPlaybackStatus]::Playing
                      $position = $time.Position.TotalMilliseconds
                      if ($playing) { $position += ([DateTimeOffset]::Now - $time.LastUpdatedTime).TotalMilliseconds }
                      $o = [ordered]@{ title = $props.Title; artist = $props.Artist; album = $props.AlbumTitle; playing = $playing;
                        position = [long]$position; duration = [long]($time.EndTime - $time.StartTime).TotalMilliseconds }
                      [Console]::WriteLine(($o | ConvertTo-Json -Compress))
                      $key = $props.Title + '|' + $props.Artist
                      if ($key -ne $last -and $props.Thumbnail -ne $null) {
                        try {
                          $stream = Await ($props.Thumbnail.OpenReadAsync()) ([Windows.Storage.Streams.IRandomAccessStreamWithContentType])
                          # Поток WinRT → обычный .NET-поток → байты (без DataReader: его LoadAsync в PowerShell капризничает)
                          $net = [System.IO.WindowsRuntimeStreamExtensions]::AsStreamForRead($stream)
                          $memory = New-Object System.IO.MemoryStream
                          $net.CopyTo($memory)
                          $bytes = $memory.ToArray()
                          if ($bytes.Length -gt 0) {
                            [Console]::WriteLine('cover ' + [Convert]::ToBase64String($bytes))
                            $last = $key
                          } else { [Console]::WriteLine('coverr пустая обложка') }
                        } catch { [Console]::WriteLine('coverr ' + $_.Exception.Message) }
                      }
                    }
                  } catch { [Console]::WriteLine('none') }
                  Start-Sleep -Seconds 1
                }
                """;
    }
}
