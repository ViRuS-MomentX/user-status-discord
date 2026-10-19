package ru.virus.manager;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RgbAppsTest {

    static RgbApps.App app(String name) {
        return RgbApps.ALL.stream().filter(app -> app.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void findsInstalledInStartMenu() {
        var start = RgbApps.parse("""
                APP:{6D809377-6AF0-444B-8957-A3773F02200E}\\OpenRGB\\Uninstall OpenRGB.exe|Uninstall OpenRGB
                APP:{6D809377-6AF0-444B-8957-A3773F02200E}\\OpenRGB\\OpenRGB.exe|OpenRGB
                APP:9426MICRO-STARINTERNATION.MSICenter_kzh8wxbdkxb8p!App|MSI Center
                APP:steam://rungameid/431960|Wallpaper Engine
                """);

        assertEquals("{6D809377-6AF0-444B-8957-A3773F02200E}\\OpenRGB\\OpenRGB.exe", RgbApps.find(app("OpenRGB"), start));
        assertEquals("9426MICRO-STARINTERNATION.MSICenter_kzh8wxbdkxb8p!App", RgbApps.find(app("MSI Center"), start));
        assertEquals("steam://rungameid/431960", RgbApps.find(app("Wallpaper Engine"), start));
        assertNull(RgbApps.find(app("SignalRGB"), start));
        assertNull(RgbApps.find(app("AIDA64 Extreme"), start));
    }

    @Test
    void installTriesIdsThenNameThenPage() {
        var script = RgbApps.installScript(app("MSI Center"));
        assertTrue(script.contains("winget install --name \"MSI Center\" -e"), script);
        assertTrue(script.contains("start \"\" \"https://www.msi.com/Landing/MSI-Center\""), script);

        var openRgb = RgbApps.installScript(app("OpenRGB"));
        assertTrue(openRgb.indexOf("OpenRGB.OpenRGB") < openRgb.indexOf("CalcProgrammer1.OpenRGB"), openRgb);
        assertTrue(openRgb.contains("--accept-package-agreements"), openRgb);
    }
}
