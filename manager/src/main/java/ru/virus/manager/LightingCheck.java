package ru.virus.manager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Почему подсветка горит не на всём — как предупреждения у устройств в iCUE
 * и Armoury Crate. После подключения к OpenRGB проверяет известные причины
 * и для каждой говорит, что сделать.
 */
final class LightingCheck {

    /**
     * @param action что предложить: «admin» — перезапустить OpenRGB от
     *               администратора, «close-signal» — закрыть SignalRGB, пусто — только совет
     */
    record Finding(String text, String action) {
    }

    /**
     * Программы производителей, которые сами держат шину подсветки, — тот же
     * список, что OpenRGB и SignalRGB приводят в разделе про конфликты.
     * Название → признаки процесса (начало имени файла в нижнем регистре).
     */
    static final Map<String, List<String>> RIVALS = rivalsTable();

    private LightingCheck() {
    }

    private static Map<String, List<String>> rivalsTable() {
        var table = new java.util.LinkedHashMap<String, List<String>>();
        table.put("MSI Center (Mystic Light)", List.of("mysticlight", "msi center.exe", "msi.centralserver",
                "ledkeeper"));
        table.put("Armoury Crate / Aura", List.of("armourycrate", "lightingservice.exe", "aurawallpaperservice",
                "asus_framework.exe"));
        table.put("iCUE", List.of("icue.exe", "corsair.service", "icue"));
        table.put("GIGABYTE RGB Fusion", List.of("rgbfusion", "gcc.exe", "gigabytecontrolcenter"));
        table.put("NZXT CAM", List.of("nzxt cam.exe", "cam.exe"));
        table.put("Razer Synapse", List.of("razer synapse", "razercentralservice", "razerappengine"));
        table.put("ASRock Polychrome", List.of("polychrome", "asrpolychrome"));
        table.put("Logitech G HUB", List.of("lghub"));
        table.put("Thermaltake TT RGB Plus", List.of("tt rgb plus", "ttrgbplus"));
        table.put("Lian Li L-Connect", List.of("l-connect", "lconnect"));
        return java.util.Collections.unmodifiableMap(table);
    }

    /** Какие из конфликтующих программ запущены — по названию, без повторов. */
    static List<String> rivals(Set<String> running) {
        return RIVALS.entrySet().stream()
                .filter(entry -> running.stream().anyMatch(name -> entry.getValue().stream().anyMatch(name::startsWith)))
                .map(Map.Entry::getKey).toList();
    }

    static List<Finding> analyze(List<OpenRgb.Device> devices, Map<String, Integer> sizes, Set<String> running,
                                 boolean openRgbEngine) {
        var findings = new ArrayList<Finding>();

        var board = devices.stream().anyMatch(device -> device.kind().equals("материнская плата"));
        var memory = devices.stream().anyMatch(device -> device.kind().equals("память"));

        if (!devices.isEmpty() && !board && !memory) {
            findings.add(new Finding("OpenRGB не видит ни плату, ни память — так бывает, когда он запущен без прав "
                    + "администратора: шина SMBus, на которой они висят, без них закрыта.", "admin"));
        }

        var empty = new ArrayList<String>();

        for (var device : devices) {
            for (var zone : device.resizable()) {
                if (zone.count() == 0 && !sizes.containsKey(OpenRgb.key(device, zone))) {
                    empty.add(zone.name());
                }
            }
        }

        if (!empty.isEmpty()) {
            findings.add(new Finding("Разъёмы без числа светодиодов: " + String.join(", ", empty) + " — то, что к ним "
                    + "подключено (обычно вентиляторы), не горит. Впиши число ниже у разъёма и нажми «Задать».", ""));
        }

        var stuck = devices.stream().filter(device -> !device.paintable()).map(OpenRgb.Device::name).toList();

        if (!stuck.isEmpty()) {
            findings.add(new Finding("OpenRGB не умеет красить: " + String.join(", ", stuck) + ". Для них — программа "
                    + "производителя, а здесь поставь им «не трогать».", ""));
        }

        if (openRgbEngine && running.stream().anyMatch(name -> name.startsWith("signalrgb"))) {
            findings.add(new Finding("Запущен SignalRGB — он перебивает цвета OpenRGB, подсветка мигает.",
                    "close-signal"));
        }

        if (openRgbEngine) {
            var rivals = rivals(running);

            if (!rivals.isEmpty()) {
                findings.add(new Finding("Работает " + String.join(", ", rivals) + " — программа производителя тоже "
                        + "управляет подсветкой и перебивает цвета, отсюда мигание и «чужие» цвета. Выключи в ней "
                        + "подсветку или закрой её (и убери из автозагрузки).", ""));
            }
        }

        return findings;
    }
}
