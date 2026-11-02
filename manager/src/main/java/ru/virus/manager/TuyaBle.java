package ru.virus.manager;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.awt.Color;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Лампы Smart Life по Bluetooth (Tuya BLE) — как открытая интеграция
 * tuya_ble для Home Assistant. Характеристики 2b11 (запись) и 2b10
 * (уведомления); пакет — AES-CBC: ключ входа md5(первые 6 знаков локального
 * ключа), после «device info» — сессионный md5(6 знаков + srand лампы).
 * Пакеты режутся по 20 байт с номером и длиной в varint.
 */
final class TuyaBle {

    static final int DEVICE_INFO = 0x0000;
    static final int PAIR = 0x0001;
    static final int DPS = 0x0002;
    static final int STATUS = 0x0003;
    static final int RECEIVE_DP = 0x8001;
    static final int RECEIVE_TIME_DP = 0x8003;
    static final int RECEIVE_SIGN_DP = 0x8004;
    static final int RECEIVE_SIGN_TIME_DP = 0x8005;
    static final int TIME1 = 0x8011;
    static final int TIME2 = 0x8012;

    static final int BOOL = 1;
    static final int VALUE = 2;
    static final int STRING = 3;
    static final int ENUM = 4;
    static final int MTU = 20;

    private TuyaBle() {
    }

    static int crc16(byte[] data, int length) {
        var crc = 0xFFFF;

        for (var i = 0; i < length; i++) {
            crc ^= data[i] & 255;

            for (var bit = 0; bit < 8; bit++) {
                var low = crc & 1;
                crc >>= 1;

                if (low != 0) {
                    crc ^= 0xA001;
                }
            }
        }

        return crc;
    }

    static byte[] varint(int value) {
        var out = new ByteArrayOutputStream();

        do {
            var b = value & 0x7F;
            value >>>= 7;
            out.write(value != 0 ? b | 0x80 : b);
        } while (value != 0);

        return out.toByteArray();
    }

    /** Прочитать varint: [значение, позиция после него]. */
    static int[] unvarint(byte[] data, int pos) throws IOException {
        var result = 0;

        for (var offset = 0; offset < 5; offset++) {
            if (pos + offset >= data.length) {
                throw new IOException("обрывок пакета");
            }

            var b = data[pos + offset] & 255;
            result |= (b & 0x7F) << (offset * 7);

            if ((b & 0x80) == 0) {
                return new int[] {result, pos + offset + 1};
            }
        }

        throw new IOException("испорченный пакет");
    }

    static byte[] aes(byte[] key, byte[] iv, byte[] data, boolean encrypt) {
        try {
            var cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new IvParameterSpec(iv));
            return cipher.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Команда → пакеты по 20 байт для характеристики записи. */
    static List<byte[]> packets(int seq, int code, byte[] data, int responseTo, byte[] key, int securityFlag,
                                int protocol, byte[] iv) {
        var length = 12 + data.length + 2;
        var raw = ByteBuffer.allocate((length + 15) / 16 * 16);
        raw.putInt(seq).putInt(responseTo).putShort((short) code).putShort((short) data.length).put(data);
        var crc = crc16(raw.array(), 12 + data.length);
        raw.putShort((short) crc);
        var encrypted = Tuya.concat(new byte[] {(byte) securityFlag}, iv, aes(key, iv, raw.array(), true));

        var packets = new ArrayList<byte[]>();
        var pos = 0;

        for (var number = 0; pos < encrypted.length; number++) {
            var head = number == 0 ? Tuya.concat(varint(number), varint(encrypted.length), new byte[] {(byte) (protocol << 4)})
                    : varint(number);
            var take = Math.min(MTU - head.length, encrypted.length - pos);
            packets.add(Tuya.concat(head, Arrays.copyOfRange(encrypted, pos, pos + take)));
            pos += take;
        }

        return packets;
    }

    /** Сборка входящего сообщения из уведомлений. */
    static final class Input {
        private ByteArrayOutputStream buffer;
        private int expected;
        private int length;

        /** @return целое зашифрованное сообщение или null, если ещё не всё */
        byte[] feed(byte[] packet) throws IOException {
            var number = unvarint(packet, 0);
            var pos = number[1];

            if (number[0] == 0) {
                var total = unvarint(packet, pos);
                length = total[0];
                pos = total[1] + 1;
                buffer = new ByteArrayOutputStream();
                expected = 0;
            } else if (buffer == null || number[0] != expected) {
                buffer = null;
                return null;
            }

            buffer.write(packet, pos, packet.length - pos);
            expected++;

            if (buffer.size() >= length) {
                var whole = buffer.toByteArray();
                buffer = null;
                return whole.length == length ? whole : null;
            }

            return null;
        }
    }

    record Message(int seq, int responseTo, int code, byte[] data) {
    }

    /** Расшифровать сообщение (флаг 4 — ключом входа, 5 — сессионным). */
    static Message parse(byte[] whole, byte[] loginKey, byte[] sessionKey) throws IOException {
        var flag = whole[0];
        var key = flag == 4 ? loginKey : flag == 5 ? sessionKey : null;

        if (key == null || whole.length < 33 || (whole.length - 17) % 16 != 0) {
            throw new IOException("непонятный ответ лампы");
        }

        var raw = aes(key, Arrays.copyOfRange(whole, 1, 17), Arrays.copyOfRange(whole, 17, whole.length), false);
        var head = ByteBuffer.wrap(raw);
        var seq = head.getInt();
        var responseTo = head.getInt();
        var code = head.getShort() & 0xFFFF;
        var length = head.getShort() & 0xFFFF;

        if (12 + length + 2 > raw.length) {
            throw new IOException("ответ лампы не расшифровался — не тот ключ?");
        }

        var crc = ((raw[12 + length] & 255) << 8) | (raw[13 + length] & 255);

        if (crc != crc16(raw, 12 + length)) {
            throw new IOException("ответ лампы не сошёлся — не тот ключ?");
        }

        return new Message(seq, responseTo, code, Arrays.copyOfRange(raw, 12, 12 + length));
    }

    /** Значения (DP) из сообщения лампы: номер → тип. */
    static Map<Integer, Integer> dpTypes(byte[] data, int from) {
        var types = new LinkedHashMap<Integer, Integer>();
        var pos = from;

        while (data.length - pos >= 4) {
            var id = data[pos] & 255;
            var type = data[pos + 1] & 255;
            var length = data[pos + 2] & 255;

            if (type > 5 || pos + 3 + length > data.length) {
                break;
            }

            types.put(id, type);
            pos += 3 + length;
        }

        return types;
    }

    /** С какого места в сообщении лампы начинаются значения. */
    static int dpStart(int code, byte[] data) {
        return switch (code) {
            case RECEIVE_DP -> 0;
            case RECEIVE_SIGN_DP -> 3;
            case RECEIVE_TIME_DP -> data.length > 0 && data[0] == 0 ? 14 : 5;
            case RECEIVE_SIGN_TIME_DP -> data.length > 3 && data[3] == 0 ? 17 : 8;
            default -> -1;
        };
    }

    static byte[] dp(int id, int type, byte[] value) {
        return Tuya.concat(new byte[] {(byte) id, (byte) type, (byte) value.length}, value);
    }

    /**
     * Цвет лампе: DP 20 — вкл, 21 — режим «цвет», 24 — цвет «hhhhssssvvvv».
     * Если лампа рассказала о своих DP — берём их типы (у части ламп 24 — сырые байты).
     */
    static byte[] colour(Color color, Map<Integer, Integer> types) {
        var hsb = Color.RGBtoHSB(color.getRed(), color.getGreen(), color.getBlue(), null);
        var lit = hsb[2] > 0.01f;
        var out = new ByteArrayOutputStream();
        var power = types.containsKey(20) || !types.containsKey(1) ? 20 : 1;
        out.writeBytes(dp(power, BOOL, new byte[] {(byte) (lit ? 1 : 0)}));

        if (lit) {
            var h = Math.round(hsb[0] * 360) % 360;
            var s = Math.round(hsb[1] * 1000);
            var v = Math.max(10, Math.round(hsb[2] * 1000));
            var mode = power == 20 ? 21 : 2;
            var colourDp = power == 20 ? 24 : 5;
            var modeType = types.getOrDefault(mode, ENUM);
            out.writeBytes(dp(mode, modeType, modeType == STRING ? "colour".getBytes(StandardCharsets.US_ASCII)
                    : new byte[] {1}));

            if (types.getOrDefault(colourDp, STRING) == 0) {
                out.writeBytes(dp(colourDp, 0, ByteBuffer.allocate(6).putShort((short) h).putShort((short) s)
                        .putShort((short) v).array()));
            } else {
                out.writeBytes(dp(colourDp, STRING, String.format("%04x%04x%04x", h, s, v).getBytes(StandardCharsets.US_ASCII)));
            }
        }

        return out.toByteArray();
    }

    /** Просьба о сопряжении: uuid + 6 знаков ключа + id устройства, до 44 байт нулями. */
    static byte[] pairing(String uuid, String localKey, String deviceId) {
        var data = Tuya.concat(uuid.getBytes(StandardCharsets.UTF_8),
                localKey.substring(0, 6).getBytes(StandardCharsets.UTF_8), deviceId.getBytes(StandardCharsets.UTF_8));
        return data.length >= 44 ? data : Arrays.copyOf(data, 44);
    }

    /** MAC «dc23...» или «DC:23:...» → число для Windows. */
    static long address(String mac) {
        return Long.parseUnsignedLong(mac.replace(":", "").replace("-", "").strip(), 16);
    }

    // ---- Связь через помощника Windows ----

    /** Соединение с лампой: помощник PowerShell держит Bluetooth, шифруем здесь. */
    static final class Link implements AutoCloseable {

        private final Process process;
        private final Writer out;
        private final LinkedBlockingQueue<String> lines = new LinkedBlockingQueue<>();
        private final Input input = new Input();
        private final byte[] loginKey;
        private final String localKey;
        private byte[] sessionKey;
        private int protocol = 2;
        private int seq = 1;
        private final Map<Integer, Integer> types = new LinkedHashMap<>();
        private final SecureRandom random = new SecureRandom();

        Link(String mac, String uuid, String localKey, String deviceId) throws Exception {
            this.localKey = localKey;
            loginKey = Tuya.md5(localKey.substring(0, 6).getBytes(StandardCharsets.UTF_8));
            var encoded = java.util.Base64.getEncoder().encodeToString(script(address(mac)).getBytes(StandardCharsets.UTF_16LE));
            process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
                    "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded).redirectErrorStream(true).start();
            out = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
            var reader = new Thread(() -> {
                try (var in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;

                    while ((line = in.readLine()) != null) {
                        var clean = cleanLine(line);

                        if (clean != null) {
                            lines.add(clean);
                        }
                    }
                } catch (IOException ignored) {
                    // помощник закрылся
                } finally {
                    lines.add("closed");
                }
            }, "virus-tuya-ble-read");
            reader.setDaemon(true);
            reader.start();

            try {
                // Ждём «ready»; ошибки PowerShell запоминаем и показываем, если помощник так и не поднялся
                var until = System.currentTimeMillis() + 40_000;
                String problem = null;

                while (true) {
                    var line = next(until - System.currentTimeMillis());

                    if ("ready".equals(line)) {
                        break;
                    }

                    if (line == null || line.equals("closed") || line.equals("none") || line.equals("nobt")
                            || line.equals("nochar")) {
                        throw new IOException(helperError(problem != null && (line == null || line.equals("closed"))
                                ? "error " + problem : line));
                    }

                    if (line.startsWith("error ")) {
                        problem = line.substring(6);
                    }
                }

                send(DEVICE_INFO, new byte[0], 0);
                var info = await(DEVICE_INFO, 8000);

                if (info.data().length < 46) {
                    throw new IOException("лампа не рассказала о себе");
                }

                protocol = info.data()[2];
                sessionKey = Tuya.md5(Tuya.concat(localKey.substring(0, 6).getBytes(StandardCharsets.UTF_8),
                        Arrays.copyOfRange(info.data(), 6, 12)));
                send(PAIR, pairing(uuid, localKey, deviceId), 0);
                var paired = await(PAIR, 8000);

                if (paired.data().length != 1 || paired.data()[0] != 0 && paired.data()[0] != 2) {
                    throw new IOException("лампа не приняла ключ (сброшена или заново добавлена в Smart Life? "
                            + "Нажми «Smart Life…» ещё раз)");
                }

                send(STATUS, new byte[0], 0);
                var until = System.currentTimeMillis() + 1500;

                while (System.currentTimeMillis() < until) {
                    poll(until - System.currentTimeMillis());
                }
            } catch (Exception e) {
                close();
                throw e;
            }
        }

        int protocol() {
            return protocol;
        }

        /**
         * Строка помощника: служебный мусор PowerShell («#< CLIXML») — null,
         * ошибки из CLIXML — «error текст».
         */
        static String cleanLine(String line) {
            var text = line.strip();

            if (text.isEmpty() || text.startsWith("#< CLIXML")) {
                return null;
            }

            if (text.startsWith("<Objs")) {
                var errors = new StringBuilder();
                var matcher = java.util.regex.Pattern.compile("<S S=\"Error\">(.*?)</S>").matcher(text);

                while (matcher.find()) {
                    errors.append(matcher.group(1).replace("_x000D_", "").replace("_x000A_", " ")
                            .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")).append(' ');
                }

                var message = errors.toString().strip().replaceAll("\\s+", " ");
                return message.isEmpty() ? null : "error " + (message.length() > 200 ? message.substring(0, 200) + "…" : message);
            }

            return text;
        }

        private static String helperError(String line) {
            if (line == null || line.equals("closed")) {
                return "помощник Bluetooth закрылся";
            }

            return switch (line) {
                case "nobt" -> "на компьютере нет Bluetooth или он выключен";
                case "none" -> "лампа не отвечает по Bluetooth — она включена и рядом? Закрой Smart Life на телефоне";
                case "nochar" -> "это не Bluetooth-лампа Tuya (нет её характеристик)";
                default -> line.startsWith("error ") ? line.substring(6) : line;
            };
        }

        private String next(long millis) throws InterruptedException {
            return lines.poll(Math.max(1, millis), TimeUnit.MILLISECONDS);
        }

        private void send(int code, byte[] data, int responseTo) throws IOException {
            var iv = new byte[16];
            random.nextBytes(iv);
            var first = code == DEVICE_INFO;
            var packets = packets(seq++, code, data, responseTo, first ? loginKey : sessionKey, first ? 4 : 5,
                    protocol, iv);

            for (var packet : packets) {
                out.write("w " + HexFormat.of().formatHex(packet) + "\n");
            }

            out.flush();
        }

        /** Принять одно сообщение (и ответить лампе, если она просит время или ждёт подтверждения). */
        private Message poll(long millis) throws Exception {
            var line = next(millis);

            if (line == null) {
                return null;
            }

            if (line.equals("closed") || line.startsWith("lost")) {
                throw new IOException("связь с лампой по Bluetooth пропала");
            }

            if (!line.startsWith("n ")) {
                return null;
            }

            var whole = input.feed(HexFormat.of().parseHex(line.substring(2)));

            if (whole == null) {
                return null;
            }

            var message = parse(whole, loginKey, sessionKey);
            var start = dpStart(message.code(), message.data());

            if (start >= 0) {
                types.putAll(dpTypes(message.data(), start));
            }

            switch (message.code()) {
                case TIME1 -> send(TIME1, Tuya.concat(String.valueOf(System.currentTimeMillis()).getBytes(StandardCharsets.US_ASCII),
                        ByteBuffer.allocate(2).putShort((short) (TimeZone.getDefault().getRawOffset() / 36_000)).array()),
                        message.seq());
                case TIME2 -> {
                    var now = java.time.LocalDateTime.now();
                    send(TIME2, ByteBuffer.allocate(9).put((byte) (now.getYear() % 100)).put((byte) now.getMonthValue())
                            .put((byte) now.getDayOfMonth()).put((byte) now.getHour()).put((byte) now.getMinute())
                            .put((byte) now.getSecond()).put((byte) (now.getDayOfWeek().getValue() % 7))
                            .putShort((short) (TimeZone.getDefault().getRawOffset() / 36_000)).array(), message.seq());
                }
                case RECEIVE_DP, RECEIVE_TIME_DP -> send(message.code(), new byte[0], message.seq());
                case RECEIVE_SIGN_DP, RECEIVE_SIGN_TIME_DP -> {
                    if (message.data().length >= 3) {
                        send(message.code(), new byte[] {message.data()[0], message.data()[1], message.data()[2], 0},
                                message.seq());
                    }
                }
                default -> {
                    // ответ на нашу команду
                }
            }

            return message;
        }

        private Message await(int code, long millis) throws Exception {
            var until = System.currentTimeMillis() + millis;

            while (System.currentTimeMillis() < until) {
                var message = poll(until - System.currentTimeMillis());

                if (message != null && message.code() == code) {
                    return message;
                }
            }

            throw new IOException("лампа не ответила по Bluetooth");
        }

        /** Поставить цвет; ответы читаем, не дожидаясь. */
        void color(Color color) throws Exception {
            if (protocol != 3) {
                throw new IOException("версия Bluetooth-протокола лампы " + protocol + " пока не поддерживается");
            }

            send(DPS, colour(color, types), 0);

            while (!lines.isEmpty()) {
                poll(1);
            }
        }

        @Override
        public void close() {
            try {
                out.write("quit\n");
                out.flush();
            } catch (IOException ignored) {
                // уже закрыт
            }

            process.destroy();
        }
    }

    /**
     * Помощник: подключается к лампе по адресу, включает уведомления 2b10,
     * пишет строки «w hex» в 2b11 и печатает «n hex» на каждое уведомление.
     */
    static String script(long address) {
        return """
                $ErrorActionPreference = 'Stop'
                $ProgressPreference = 'SilentlyContinue'
                trap { [Console]::WriteLine('error ' + $_.Exception.Message); exit }
                try {
                  Add-Type -AssemblyName System.Runtime.WindowsRuntime
                  $null = [Windows.Devices.Bluetooth.BluetoothLEDevice, Windows.Devices.Bluetooth, ContentType = WindowsRuntime]
                  $null = [Windows.Devices.Bluetooth.GenericAttributeProfile.GattDeviceService, Windows.Devices.Bluetooth, ContentType = WindowsRuntime]
                } catch { [Console]::WriteLine('nobt'); exit }
                $asTask = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
                  $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and
                  $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]
                function Await($op, [Type]$type) {
                  $task = $asTask.MakeGenericMethod($type).Invoke($null, @($op))
                  $null = $task.Wait(20000)
                  $task.Result
                }
                try {
                  $dev = Await ([Windows.Devices.Bluetooth.BluetoothLEDevice]::FromBluetoothAddressAsync([uint64]%ADDR%)) ([Windows.Devices.Bluetooth.BluetoothLEDevice])
                } catch { $dev = $null }
                if ($dev -eq $null) { [Console]::WriteLine('none'); exit }
                $notify = $null; $write = $null
                try {
                  $services = Await ($dev.GetGattServicesAsync([Windows.Devices.Bluetooth.BluetoothCacheMode]::Uncached)) ([Windows.Devices.Bluetooth.GenericAttributeProfile.GattDeviceServicesResult])
                  foreach ($s in $services.Services) {
                    $chars = Await ($s.GetCharacteristicsAsync([Windows.Devices.Bluetooth.BluetoothCacheMode]::Uncached)) ([Windows.Devices.Bluetooth.GenericAttributeProfile.GattCharacteristicsResult])
                    foreach ($c in $chars.Characteristics) {
                      $u = $c.Uuid.ToString()
                      if ($u.StartsWith('00002b10')) { $notify = $c }
                      if ($u.StartsWith('00002b11')) { $write = $c }
                    }
                  }
                } catch { [Console]::WriteLine('none'); exit }
                if ($notify -eq $null -or $write -eq $null) { [Console]::WriteLine('nochar'); exit }
                $statusType = [Windows.Devices.Bluetooth.GenericAttributeProfile.GattCommunicationStatus]
                $cccd = [Windows.Devices.Bluetooth.GenericAttributeProfile.GattClientCharacteristicConfigurationDescriptorValue]::Notify
                $null = Await ($notify.WriteClientCharacteristicConfigurationDescriptorAsync($cccd)) $statusType
                Register-ObjectEvent -InputObject $notify -EventName ValueChanged -SourceIdentifier tn | Out-Null
                $option = [Windows.Devices.Bluetooth.GenericAttributeProfile.GattWriteOption]::WriteWithoutResponse
                if (($write.CharacteristicProperties -band [Windows.Devices.Bluetooth.GenericAttributeProfile.GattCharacteristicProperties]::WriteWithoutResponse) -eq 0) {
                  $option = [Windows.Devices.Bluetooth.GenericAttributeProfile.GattWriteOption]::WriteWithResponse
                }
                [Console]::WriteLine('ready')
                $reader = [Console]::In
                $pending = $reader.ReadLineAsync()
                while ($true) {
                  foreach ($e in @(Get-Event -SourceIdentifier tn -ErrorAction SilentlyContinue)) {
                    $bytes = [System.Runtime.InteropServices.WindowsRuntime.WindowsRuntimeBufferExtensions]::ToArray($e.SourceEventArgs.CharacteristicValue)
                    [Console]::WriteLine('n ' + [BitConverter]::ToString($bytes).Replace('-', ''))
                    Remove-Event -EventIdentifier $e.EventIdentifier
                  }
                  if ($pending.IsCompleted) {
                    $line = $pending.Result
                    if ($line -eq $null -or $line -eq 'quit') { break }
                    if ($line.StartsWith('w ')) {
                      $hex = $line.Substring(2)
                      $bytes = New-Object byte[] ($hex.Length / 2)
                      for ($i = 0; $i -lt $bytes.Length; $i++) { $bytes[$i] = [Convert]::ToByte($hex.Substring($i * 2, 2), 16) }
                      $buffer = [System.Runtime.InteropServices.WindowsRuntime.WindowsRuntimeBufferExtensions]::AsBuffer($bytes)
                      try {
                        $status = Await ($write.WriteValueAsync($buffer, $option)) $statusType
                        if ($status -ne [Windows.Devices.Bluetooth.GenericAttributeProfile.GattCommunicationStatus]::Success) { [Console]::WriteLine('lost ' + $status); break }
                      } catch { [Console]::WriteLine('lost'); break }
                    }
                    $pending = $reader.ReadLineAsync()
                  }
                  Start-Sleep -Milliseconds 5
                }
                $dev.Dispose()
                """.replace("%ADDR%", Long.toUnsignedString(address));
    }
}
