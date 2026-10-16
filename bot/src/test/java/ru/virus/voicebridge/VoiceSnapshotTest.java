package ru.virus.voicebridge;

import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoiceSnapshotTest {

    @Test
    void trickyNamesStayValidJson() {
        var name = "Сервер \"для своих\" \\ 🎮\n\tи\u0001всё";
        var json = DataObject.fromJson(VoiceSnapshot.inChannel(1, name, "канал «1»", null, 3, 1700)
                .toJson(true));

        assertTrue(json.getBoolean("connected"));
        assertTrue(json.getBoolean("inVoice"));
        assertEquals(name, json.getString("guild"));
        assertEquals("канал «1»", json.getString("channel"));
        assertEquals("", json.getString("icon"));
        assertEquals(3, json.getInt("members"));
        assertEquals(1700, json.getLong("since"));
    }

    @Test
    void idleSaysOnlyWhatIsKnown() {
        var json = DataObject.fromJson(VoiceSnapshot.IDLE.toJson(false));

        assertFalse(json.getBoolean("connected"));
        assertFalse(json.getBoolean("inVoice"));
        assertFalse(json.hasKey("guild"));
    }
}
