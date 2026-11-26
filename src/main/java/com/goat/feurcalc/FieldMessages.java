package com.goat.feurcalc;

/**
 * Les messages de terrain des combats Cobblemon (clés cobblemon.battle.weather.*, fieldstart.*, sidestart.*...)
 * appliqués à un FieldState. Sans rien de Minecraft : testé par FieldMessagesTest.
 */
public final class FieldMessages {
    /** Message reconnu. */
    public static final int HANDLED = 1;
    /** Le terrain a changé. */
    public static final int CHANGED = 2;
    /** Le message parlait de la météo / du champ. */
    public static final int WEATHER = 4;
    public static final int TERRAIN = 8;

    private FieldMessages() {}

    /** k : la clé sans "cobblemon.battle.", ex. "weather.sunnyday.start". */
    public static int apply(FieldState field, String k) {
        String[] p = k.split("\\.");
        boolean changed = true;
        int seen = 0;
        if (p.length == 3 && p[0].equals("weather")) {
            String w = switch (p[1]) {
                case "raindance" -> "Rain";
                case "sunnyday" -> "Sun";
                case "sandstorm" -> "Sand";
                case "hail", "snow" -> "Snow"; // shortcut: la grêle compte comme la neige (pas de Grêle dans le calc gen 9)
                default -> null;
            };
            if (w == null) return 0;
            seen |= WEATHER;
            if (p[2].equals("end")) {
                if (w.equals(field.weather)) field.weather = "";
            } else {
                changed = !w.equals(field.weather); // "upkeep" : la météo continue
                field.weather = w;
            }
        } else if (p.length == 2 && (p[0].equals("fieldstart") || p[0].equals("fieldend"))) {
            boolean start = p[0].equals("fieldstart");
            String t = switch (p[1]) {
                case "electricterrain" -> "Electric";
                case "grassyterrain" -> "Grassy";
                case "psychicterrain" -> "Psychic";
                case "mistyterrain" -> "Misty";
                default -> null;
            };
            if (t != null) {
                seen |= TERRAIN;
                if (start) field.terrain = t;
                else if (t.equals(field.terrain)) field.terrain = "";
            } else if (p[1].equals("gravity")) {
                field.gravity = start;
            } else if (p[1].equals("trickroom")) {
                field.trickRoom = start;
            } else {
                return 0;
            }
        } else if (p.length == 3 && (p[0].equals("sidestart") || p[0].equals("sideend"))) {
            boolean start = p[0].equals("sidestart");
            FieldState.Side side = p[1].equals("ally") ? field.a : p[1].equals("opponent") ? field.b : null;
            if (side == null) return 0;
            switch (p[2]) {
                case "reflect" -> side.reflect = start;
                case "lightscreen" -> side.lightScreen = start;
                case "auroraveil" -> side.auroraVeil = start;
                case "stealthrock" -> side.stealthRock = start;
                case "tailwind" -> side.tailwind = start;
                case "spikes" -> side.spikes = start ? Math.min(3, side.spikes + 1) : 0;
                default -> {
                    return 0;
                }
            }
        } else {
            return 0;
        }
        return HANDLED | seen | (changed ? CHANGED : 0);
    }
}
