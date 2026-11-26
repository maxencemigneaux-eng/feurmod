package com.goat.feurleg;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Lecture des timers du menu /leg : chaque chrono va au nom de zone écrit juste au-dessus. */
class TextCaptureTest {
    private static TextCapture.Entry e(String text, float x, float y) {
        return new TextCapture.Entry(text, x, y, 1f);
    }

    @Test
    void timersAssociesAuNomAuDessus() {
        Map<String, Long> found = TextCapture.findTimers(List.of(
                e("Johto", 10, 10), e("12:34", 12, 25),
                e("Hoenn", 100, 10), e("1:02:03", 100, 25),
                e("Unys", 200, 10), e("0:00", 200, 25)), new FeurlegConfig());
        assertEquals(Map.of("Johto", 754L, "Hoenn", 3723L, "Unys", 0L), found);
    }

    @Test
    void nomTropLoinOuEnDessousIgnore() {
        FeurlegConfig cfg = new FeurlegConfig();
        Map<String, Long> found = TextCapture.findTimers(List.of(
                e("Loin", 10, 10), e("05:00", 10, 10 + cfg.labelMaxDistanceY + 20),
                e("Dessous", 100, 40), e("05:00", 100, 25)), cfg);
        assertTrue(found.isEmpty(), found.toString());
    }

    @Test
    void textesQuiNeSontPasDesNomsIgnores() {
        Map<String, Long> found = TextCapture.findTimers(List.of(
                e("50%", 10, 10), e("03:00", 10, 25)), new FeurlegConfig());
        assertTrue(found.isEmpty());
    }

    @Test
    void formatsDeChrono() {
        FeurlegConfig cfg = new FeurlegConfig();
        assertEquals(125L, TextCapture.toSeconds("2:05", cfg));
        assertEquals(3723L, TextCapture.toSeconds("1:02:03", cfg));
        assertNull(TextCapture.toSeconds("abc", cfg));
        cfg.timerFormat = "h:mm";
        assertEquals(7500L, TextCapture.toSeconds("2:05", cfg));
    }
}
