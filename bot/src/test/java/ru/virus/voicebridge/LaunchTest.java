package ru.virus.voicebridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LaunchTest {

    @Test
    void plainStartKeepsOldBehaviour() {
        var launch = VoiceBridgeBot.Launch.parse(new String[0]);

        assertEquals("config.properties", launch.config());
        assertFalse(launch.parodyOnly());
        assertFalse(launch.withoutParody());
        assertFalse(launch.supervised());
    }

    @Test
    void flagsInAnyOrder() {
        var launch = VoiceBridgeBot.Launch.parse(
                new String[] { "--supervised", "D:\\bot\\config.properties", "--parody-only" });

        assertEquals("D:\\bot\\config.properties", launch.config());
        assertTrue(launch.parodyOnly());
        assertTrue(launch.supervised());
    }

    @Test
    void reasonNeverNull() {
        assertEquals("соединение не установилось", Parody.reason(new java.net.ConnectException()));
        assertEquals("IOException", Parody.reason(new java.io.IOException()));
        assertEquals("таймаут", Parody.reason(new java.io.IOException("таймаут")));
    }
}
