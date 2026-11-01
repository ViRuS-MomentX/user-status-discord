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

    private LightingCheck() {
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

        if (openRgbEngine && running.stream().anyMatch(name -> name.contains("mystic") || name.equals("msi center.exe")
                || name.startsWith("msi.centralserver"))) {
            findings.add(new Finding("Работает MSI Center (Mystic Light) — он тоже управляет платой и может перебивать "
                    + "цвета. Выключи в нём Mystic Light или закрой его.", ""));
        }

        return findings;
    }
}
