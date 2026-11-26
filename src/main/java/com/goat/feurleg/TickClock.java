package com.goat.feurleg;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * Horloge "temps serveur" : des millisecondes qui avancent à la vitesse des ticks du serveur (50 ms par tick).
 *
 * Les timers du menu /leg sont décomptés par le serveur en ticks : si le serveur tourne à 19 TPS au lieu de 20,
 * une "seconde" du timer dure plus d'une vraie seconde. Décompter en temps réel faisait donc arriver le compteur à 0
 * trop tôt (et décaler les timers à chaque ouverture de /leg), et l'heure Minecraft prévue était fausse au moment du
 * spawn. Avec cette horloge, 1 seconde du timer = 20 ticks du serveur, comme sur le serveur.
 *
 * La vitesse du serveur est mesurée sur la dernière minute grâce à l'heure du monde (getGameTime), que le serveur
 * renvoie au client chaque seconde. Hors d'un monde, l'horloge avance en temps réel.
 */
public final class TickClock {

    /** Mesures (temps réel, gameTime) prises chaque seconde ; la vitesse se calcule sur la plus ancienne et la dernière. */
    private static final int SAMPLES = 61;
    private static final long[] sampleReal = new long[SAMPLES];
    private static final long[] sampleGame = new long[SAMPLES];
    private static int sampleCount, sampleHead;

    private static double virtualMs = System.currentTimeMillis();
    private static long lastReal = System.currentTimeMillis();
    /** Vitesse du serveur : 1.0 = 20 TPS, 0.95 = 19 TPS. */
    private static double speed = 1.0;

    private static ClientLevel lastLevel;
    private static long lastGame;
    private static int tickCounter;

    private TickClock() {}

    /** Maintenant, en millisecondes "temps serveur". */
    public static long now() {
        long real = System.currentTimeMillis();
        return (long) (virtualMs + Math.max(0, real - lastReal) * speed);
    }

    /** Vitesse actuelle du serveur (1.0 = 20 TPS). */
    public static double speed() {
        return speed;
    }

    /** Convertit une fin en temps réel (fichier) en fin "temps serveur", et l'inverse. */
    public static long fromReal(long realMillis) {
        return now() + (realMillis - System.currentTimeMillis());
    }

    public static long toReal(long virtualMillis) {
        return System.currentTimeMillis() + (virtualMillis - now());
    }

    /** Appelé à chaque tick du client. */
    public static void tick(Minecraft client) {
        long real = System.currentTimeMillis();
        virtualMs += Math.max(0, real - lastReal) * speed;
        lastReal = real;

        ClientLevel level = client.level;
        if (level == null) {
            // Pas de monde : temps réel, et on repartira de zéro pour la mesure
            speed = 1.0;
            lastLevel = null;
            sampleCount = 0;
            return;
        }
        long game = level.getGameTime();
        if (level != lastLevel || game < lastGame - 200 || game > lastGame + 2000) {
            // Changement de monde ou saut de temps : nouvelle série de mesures (on garde la vitesse connue)
            lastLevel = level;
            sampleCount = 0;
            tickCounter = 0;
        }
        lastGame = game;
        if (tickCounter++ % 20 != 0) return;

        sampleReal[sampleHead] = real;
        sampleGame[sampleHead] = game;
        sampleHead = (sampleHead + 1) % SAMPLES;
        if (sampleCount < SAMPLES) sampleCount++;
        if (sampleCount < 11) return; // au moins 10 s de mesure

        int oldest = Math.floorMod(sampleHead - sampleCount, SAMPLES);
        long dReal = real - sampleReal[oldest];
        long dGame = game - sampleGame[oldest];
        if (dReal <= 0) return;
        double measured = dGame * 50.0 / dReal;
        // Un serveur ne va jamais plus vite que 20 TPS ; en dessous de 0,2, c'est une mesure ratée
        if (measured >= 0.2) {
            speed = Math.min(1.0, measured);
        }
    }
}
