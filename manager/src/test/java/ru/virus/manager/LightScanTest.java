package ru.virus.manager;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LightScanTest {

    @Test
    void oneRowPerDeviceWithTheBestName() {
        var found = LightScan.parse("""
                шум PowerShell
                DEV:USB\\VID_1A86&PID_E316\\5&2B|Составное USB устройство
                DEV:HID\\VID_1A86&PID_E316&MI_00\\7&1|HID-совместимое устройство, определенное поставщиком
                DEV:HID\\VID_1A86&PID_E316&MI_01\\7&2|ATTACK SHARK X98
                DEV:USB\\VID_1022&PID_15B6\\6&1|Корневой USB-концентратор (USB 3.0)
                DEV:HID\\VID_046D&PID_C52B\\7&3|HID-совместимая мышь
                LAMP:\\\\?\\HID#VID_046D&PID_C52B&MI_02#8&1|G502
                """);

        assertEquals(2, found.size(), found.toString());
        assertEquals("046D:C52B", found.getFirst().id(), "кого Windows красит сама — первыми");
        assertTrue(found.getFirst().lamps());
        assertEquals("ATTACK SHARK X98", found.get(1).name());
        assertFalse(found.get(1).lamps());
        assertTrue(found.get(1).likelyLit());
    }

    @Test
    void hardwareDetailsReadable() {
        assertEquals("2 × 16 ГБ DDR5 6000 МГц", SystemInfo.ram(List.of(
                Map.of("size", 17179869184L, "speed", 6000, "type", 34),
                Map.of("size", 17179869184L, "speed", 6000, "type", 34))));
        assertEquals("16 ГБ DDR4", SystemInfo.ram(Map.of("size", 17179869184L, "type", 26)));

        assertEquals(List.of("AMD Radeon(TM) Graphics", "NVIDIA GeForce RTX 5080 · 16 ГБ"), SystemInfo.gpus(List.of(
                Map.of("name", "AMD Radeon(TM) Graphics", "memory", 2147483648L),
                Map.of("name", "NVIDIA GeForce RTX 5080", "memory", 17179869184L))));

        assertEquals(List.of("Samsung SSD 990 PRO 2TB · 1.8 ТБ · NVMe SSD"), SystemInfo.drives(Map.of(
                "name", "Samsung SSD 990 PRO 2TB", "size", 2000398934016L, "media", "SSD", "bus", "NVMe")));
    }
}
