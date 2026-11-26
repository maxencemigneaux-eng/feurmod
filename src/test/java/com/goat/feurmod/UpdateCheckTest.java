package com.goat.feurmod;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UpdateCheckTest {
    @Test
    void versionsCompareesChiffreParChiffre() {
        assertTrue(UpdateCheck.compare("1.0.56", "1.0.9") > 0);
        assertTrue(UpdateCheck.compare("1.0.55", "1.0.56") < 0);
        assertEquals(0, UpdateCheck.compare("1.0.55", "1.0.55"));
        assertTrue(UpdateCheck.compare("1.1", "1.0.99") > 0);
        assertEquals(0, UpdateCheck.compare("1.0", "1.0.0"));
    }
}
