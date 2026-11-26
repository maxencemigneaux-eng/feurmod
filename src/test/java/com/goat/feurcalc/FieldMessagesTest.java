package com.goat.feurcalc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Les messages de terrain des combats Cobblemon → le terrain du calculateur. */
class FieldMessagesTest {

    @Test
    void meteoCommenceContinueEtFinit() {
        FieldState f = new FieldState();
        int r = FieldMessages.apply(f, "weather.sunnyday.start");
        assertEquals("Sun", f.weather);
        assertEquals(FieldMessages.HANDLED | FieldMessages.CHANGED | FieldMessages.WEATHER, r);
        // "upkeep" : la météo continue, rien ne change
        r = FieldMessages.apply(f, "weather.sunnyday.upkeep");
        assertEquals(0, r & FieldMessages.CHANGED);
        assertEquals("Sun", f.weather);
        FieldMessages.apply(f, "weather.sunnyday.end");
        assertEquals("", f.weather);
    }

    @Test
    void laFinDuneAutreMeteoNEffacePasLaMeteoActuelle() {
        FieldState f = new FieldState();
        FieldMessages.apply(f, "weather.raindance.start");
        FieldMessages.apply(f, "weather.snow.end");
        assertEquals("Rain", f.weather);
    }

    @Test
    void greleEtNeigeDonnentNeige() {
        FieldState f = new FieldState();
        FieldMessages.apply(f, "weather.hail.start");
        assertEquals("Snow", f.weather);
    }

    @Test
    void champsGraviteDistorsion() {
        FieldState f = new FieldState();
        assertEquals(FieldMessages.HANDLED | FieldMessages.CHANGED | FieldMessages.TERRAIN,
                FieldMessages.apply(f, "fieldstart.electricterrain"));
        assertEquals("Electric", f.terrain);
        FieldMessages.apply(f, "fieldend.grassyterrain");
        assertEquals("Electric", f.terrain, "la fin d'un autre champ ne touche pas au champ actuel");
        FieldMessages.apply(f, "fieldend.electricterrain");
        assertEquals("", f.terrain);
        FieldMessages.apply(f, "fieldstart.gravity");
        assertTrue(f.gravity);
        FieldMessages.apply(f, "fieldstart.trickroom");
        assertTrue(f.trickRoom);
        FieldMessages.apply(f, "fieldend.trickroom");
        assertFalse(f.trickRoom);
    }

    @Test
    void chaqueCoteASesProtectionsEtSesPieges() {
        FieldState f = new FieldState();
        FieldMessages.apply(f, "sidestart.ally.reflect");
        FieldMessages.apply(f, "sidestart.opponent.stealthrock");
        assertTrue(f.a.reflect);
        assertFalse(f.b.reflect);
        assertTrue(f.b.stealthRock);
        for (int i = 0; i < 4; i++) {
            FieldMessages.apply(f, "sidestart.opponent.spikes");
        }
        assertEquals(3, f.b.spikes, "3 couches de Picots au maximum");
        FieldMessages.apply(f, "sideend.opponent.spikes");
        assertEquals(0, f.b.spikes);
        FieldMessages.apply(f, "sideend.ally.reflect");
        assertFalse(f.a.reflect);
    }

    @Test
    void messagesInconnusIgnores() {
        FieldState f = new FieldState();
        assertEquals(0, FieldMessages.apply(f, "used_move"));
        assertEquals(0, FieldMessages.apply(f, "weather.desolateland.start"));
        assertEquals(0, FieldMessages.apply(f, "sidestart.ally.safeguard"));
        assertEquals(0, FieldMessages.apply(f, "fieldstart.magicroom"));
        assertEquals("", f.weather);
    }
}
