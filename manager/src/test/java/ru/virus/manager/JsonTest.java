package ru.virus.manager;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JsonTest {

    @Test
    void roundTrip() {
        var map = new LinkedHashMap<String, Object>();
        map.put("текст", "кавычка \" и \\ и\nперевод");
        map.put("число", 42L);
        map.put("дробь", 1.5);
        map.put("да", true);
        map.put("пусто", null);
        map.put("список", List.of("a", 1L, Map.of("k", "v")));

        assertEquals(map, Json.parse(Json.write(map)));
    }

    @Test
    void scriptTagCannotBreakOut() {
        assertEquals("\"\\u003c/script>\"", Json.write("</script>"));
    }

    @Test
    void readsEscapesAndWhitespace() {
        assertEquals(Map.of("a", "Ж\t"), Json.parse(" { \"a\" : \"\\u0416\\t\" } "));
        assertEquals(List.of(), Json.parse("[]"));
        assertEquals(-3L, Json.parse("-3"));
    }

    @Test
    void rejectsGarbage() {
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\":}"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("[1,2"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{} лишнее"));
        assertThrows(IllegalArgumentException.class, () -> Json.object("[1]"));
    }
}
