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

    /** Лампа: id, имя, локальный ключ (не показываем), адрес и версия протокола. */
    record Lamp(String id, String name, String key, String ip, String version) {

        Map<String, Object> toJson() {
            var json = new java.util.LinkedHashMap<String, Object>();
            json.put("id", id);
            json.put("name", name);
            json.put("key", key);
            json.put("ip", ip);
            json.put("version", version);
            return json;
        }

        static Lamp fromJson(Object value) {
            if (!(value instanceof Map<?, ?> map) || !(map.get("id") instanceof String id) || !(map.get("key") instanceof String key)) {
                return null;
            }

            return new Lamp(id, String.valueOf(map.get("name")), key, map.get("ip") instanceof String ip ? ip : "",
                    map.get("version") instanceof String version ? version : "3.3");
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
                        var session = sessions.get(lamp.id());

                        if (session == null) {
                            var ip = lamp.ip();

                            if (ip.isBlank()) {
                                var found = Tuya.discover(6000).stream().filter(f -> f.id().equals(lamp.id())).findFirst();

                                if (found.isEmpty()) {
                                    throw new java.io.IOException("не видна в сети (включена? тот же Wi-Fi?)");
                                }

                                ip = found.get().ip();
                            }

                            session = new Tuya.Session(ip, lamp.id(), lamp.key(), lamp.version());
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

                        sent.remove(lamp.id());
                        retryAt.put(lamp.id(), now + 8000);
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
        }
    }

    private static String names(List<Lamp> lamps) {
        var names = new ArrayList<String>();
        lamps.forEach(lamp -> names.add(lamp.name()));
        return String.join(", ", names);
    }
}
