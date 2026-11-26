package com.goat.feurleg;

/**
 * L'horloge des timers : le temps réel, comme avant la 1.0.46.
 *
 * La 1.0.46 la ralentissait quand le serveur rame (1 s du timer = 20 ticks) ; en jeu les timers se décalaient
 * par rapport à /leg, donc retour au décompte en vraies secondes. Gardée comme point d'entrée unique.
 */
public final class TickClock {
    private TickClock() {}

    public static long now() {
        return System.currentTimeMillis();
    }

    /** Fin enregistrée dans le fichier (temps réel) → fin du timer. */
    public static long fromReal(long realMillis) {
        return realMillis;
    }

    public static long toReal(long millis) {
        return millis;
    }
}
