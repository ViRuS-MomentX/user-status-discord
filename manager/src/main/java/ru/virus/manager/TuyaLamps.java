package ru.virus.manager;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Лампы Smart Life (Tuya) по домашней сети: свой поток держит соединение с
 * каждой лампой и шлёт цвет режима не чаще 4 раз в секунду — чаще лампы Tuya
 * не успевают и начинают пропускать команды. Одинаковый цвет повторно не шлём.
 */
final class TuyaLamps {

    /**
     * Лампа: id, имя, локальный ключ (не показываем), адрес и версия протокола
     * по Wi-Fi; для Bluetooth-ламп — uuid и MAC из облака, ble — работает по Bluetooth.
     */
    record Lamp(String id, String name, String key, String ip, String version, boolean online, String uuid, String mac,
                boolean ble) {

        Lamp(String id, String name, String key, String ip, String version) {
            this(id, name, key, ip, version, true, "", "", false);
        }

        Lamp at(String ip, String version) {
            return new Lamp(id, name, key, ip, version, online, uuid, mac, ble);
        }

        Lamp bluetooth() {
            return new Lamp(id, name, key, "", version, online, uuid, mac, true);
        }

        /** Можно ли попробовать Bluetooth: есть MAC и uuid. */
        boolean canBle() {
            return !mac.isBlank() && !uuid.isBlank();
        }

        Map<String, Object> toJson() {
            var json = new java.util.LinkedHashMap<String, Object>();
            json.put("id", id);
            json.put("name", name);
            json.put("key", key);
            json.put("ip", ip);
            json.put("version", version);
            json.put("online", online);
            json.put("uuid", uuid);
            json.put("mac", mac);
            json.put("ble", ble);
            return json;
        }

        static Lamp fromJson(Object value) {
            if (!(value instanceof Map<?, ?> map) || !(map.get("id") instanceof String id) || !(map.get("key") instanceof String key)) {
                return null;
            }

            return new Lamp(id, String.valueOf(map.get("name")), key, map.get("ip") instanceof String ip ? ip : "",
                    map.get("version") instanceof String version ? version : "3.3", !Boolean.FALSE.equals(map.get("online")),
                    map.get("uuid") instanceof String uuid ? uuid : "", map.get("mac") instanceof String mac ? mac : "",
                    Boolean.TRUE.equals(map.get("ble")));
        }
    }

    static final long PERIOD_MS = 250;

    private final Consumer<String> status;
    private volatile List<Lamp> lamps = List.of();
    private volatile Color color = Color.BLACK;
    private volatile boolean on;
    private volatile Thread thread;

    TuyaLamps(Consumer<String> status) {
        this.status = status;
    }

    /** Нашли адрес лампы — запомнить, чтобы в следующий раз не искать. */
    volatile Consumer<List<Lamp>> learned = lamps -> { };

    private void learn(Lamp lamp, java.util.function.UnaryOperator<Lamp> change) {
        var updated = new ArrayList<Lamp>();

        for (var item : lamps) {
            updated.add(item.id().equals(lamp.id()) ? change.apply(item) : item);
        }

        lamps = List.copyOf(updated);
        learned.accept(lamps);
    }

    void lamps(List<Lamp> lamps) {
        this.lamps = List.copyOf(lamps);
    }

    /** Цвет для всех ламп; чёрный — выключить. */
    void color(Color color) {
        this.color = color;
    }

    synchronized void start() {
        on = true;

        if (thread != null && thread.isAlive()) {
            return;
        }

        thread = new Thread(this::run, "virus-tuya-lamps");
        thread.setDaemon(true);
        thread.start();
    }

    synchronized void stop() {
        on = false;

        if (thread != null) {
            thread.interrupt();
        }
    }

    /** Похожи ли цвета настолько, что слать заново незачем. */
    static boolean same(Color a, Color b) {
        return a != null && b != null && Math.abs(a.getRed() - b.getRed()) + Math.abs(a.getGreen() - b.getGreen())
                + Math.abs(a.getBlue() - b.getBlue()) < 12;
    }

    private void run() {
        var sessions = new HashMap<String, Tuya.Session>();
        var links = new HashMap<String, TuyaBle.Link>();
        var styles = new HashMap<String, Boolean>();
        var sent = new HashMap<String, Color>();
        var retryAt = new HashMap<String, Long>();
        var problems = new HashMap<String, String>();
        var lastStatus = "";

        try {
            while (on && !Thread.currentThread().isInterrupted()) {
                var now = System.currentTimeMillis();
                var target = color;

                for (var lamp : lamps) {
                    if (retryAt.getOrDefault(lamp.id(), 0L) > now || same(sent.get(lamp.id()), target)) {
                        continue;
                    }

                    try {
                        if (lamp.ble() || !lamp.online() && lamp.canBle()) {
                            var link = links.get(lamp.id());

                            if (link == null) {
                                lastStatus = "";
                                status.accept("Smart Life: подключаюсь к " + lamp.name() + " по Bluetooth…");
                                link = new TuyaBle.Link(lamp.mac(), lamp.uuid(), lamp.key(), lamp.id());
                                links.put(lamp.id(), link);

                                if (!lamp.ble()) {
                                    learn(lamp, Lamp::bluetooth);
                                }
                            }

                            link.color(target);
                            sent.put(lamp.id(), target);
                            problems.remove(lamp.id());
                            continue;
                        }

                        var session = sessions.get(lamp.id());

                        if (session == null) {
                            var ip = lamp.ip();

                            var version = lamp.version();

                            if (ip.isBlank()) {
                                lastStatus = "";
                                status.accept("Smart Life: ищу " + lamp.name() + " в домашней сети…");
                                var found = Tuya.discover(6000).stream().filter(f -> f.id().equals(lamp.id())).findFirst();

                                if (found.isPresent()) {
                                    ip = found.get().ip();
                                    version = found.get().version();
                                } else {
                                    // Объявления не дошли — перебираем сеть и пробуем ключ лампы на каждом устройстве Tuya
                                    for (var host : Tuya.scanHosts()) {
                                        var probed = Tuya.probe(host, lamp.id(), lamp.key());

                                        if (probed != null) {
                                            ip = host;
                                            version = probed;
                                            break;
                                        }
                                    }
                                }

                                if (ip.isBlank() && lamp.canBle()) {
                                    // По Wi-Fi нет — пробуем как Bluetooth-лампу
                                    learn(lamp, Lamp::bluetooth);
                                    throw new java.io.IOException("по Wi-Fi не видна — пробую по Bluetooth");
                                }

                                if (ip.isBlank()) {
                                    throw new java.io.IOException("не видна в домашней сети — она подключена к Wi-Fi? "
                                            + "(в облаке: " + (lamp.online() ? "в сети" : "не в сети") + ")");
                                }

                                var where = ip;
                                var how = version;
                                learn(lamp, item -> item.at(where, how));
                            }

                            session = new Tuya.Session(ip, lamp.id(), lamp.key(), version);
                            sessions.put(lamp.id(), session);

                            if (!styles.containsKey(lamp.id())) {
                                var dps = session.query();
                                styles.put(lamp.id(), !dps.containsKey("1") || dps.containsKey("20"));
                            }
                        }

                        session.set(Tuya.light(target, true, styles.getOrDefault(lamp.id(), true)));
                        sent.put(lamp.id(), target);
                        problems.remove(lamp.id());
                    } catch (InterruptedException e) {
                        throw e;
                    } catch (Exception e) {
                        var session = sessions.remove(lamp.id());

                        if (session != null) {
                            session.close();
                        }

                        var link = links.remove(lamp.id());

                        if (link != null) {
                            link.close();
                        }

                        sent.remove(lamp.id());
                        // Сохранённый адрес не отвечает — лампа могла получить новый, в следующий раз ищем заново
                        var lost = !lamp.ip().isBlank() && e instanceof java.net.ConnectException;

                        if (lost) {
                            learn(lamp, item -> item.at("", item.version()));
                        }

                        var current = lamps.stream().filter(item -> item.id().equals(lamp.id())).findFirst().orElse(lamp);
                        retryAt.put(lamp.id(), now + (current.ble() && !lamp.ble() ? 300 : lamp.ble() ? 10_000
                                : lamp.ip().isBlank() ? 30_000 : 8000));
                        problems.put(lamp.id(), lamp.name() + ": " + (e.getMessage() == null ? e.getClass().getSimpleName()
                                : e.getMessage()));
                    }
                }

                var text = lamps.isEmpty() ? "Smart Life: ламп нет — нажми «Smart Life…»"
                        : problems.isEmpty() ? "Smart Life: на связи " + names(lamps)
                        : "Smart Life: " + String.join("; ", problems.values());

                if (!text.equals(lastStatus)) {
                    lastStatus = text;
                    status.accept(text);
                }

                Thread.sleep(PERIOD_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            sessions.values().forEach(Tuya.Session::close);
            links.values().forEach(TuyaBle.Link::close);
        }
    }

    private static String names(List<Lamp> lamps) {
        var names = new ArrayList<String>();
        lamps.forEach(lamp -> names.add(lamp.name()));
        return String.join(", ", names);
    }
}
