package com.goat.feurcalc;

import com.google.gson.JsonObject;

/** Météo, champ, gravité, coup critique, combat double, et les protections de chaque côté. */
public final class FieldState {
    public String weather = "";
    public String terrain = "";
    public boolean gravity;
    public boolean crit;
    /** Combat double (VGC) : les attaques qui touchent plusieurs cibles font 25 % de dégâts en moins. */
    public boolean doubles;
    public final Side a = new Side();
    public final Side b = new Side();

    public static final class Side {
        public boolean reflect;
        public boolean lightScreen;
        public boolean auroraVeil;
        public boolean stealthRock;
        public boolean tailwind;
        public int spikes;
        // Combat double : soutien du partenaire
        public boolean helpingHand;
        public boolean friendGuard;

        public Side copy() {
            Side s = new Side();
            s.copyFrom(this);
            return s;
        }

        public void copyFrom(Side o) {
            reflect = o.reflect;
            lightScreen = o.lightScreen;
            auroraVeil = o.auroraVeil;
            stealthRock = o.stealthRock;
            tailwind = o.tailwind;
            spikes = o.spikes;
            helpingHand = o.helpingHand;
            friendGuard = o.friendGuard;
        }

        JsonObject toRequest(boolean doubles) {
            JsonObject o = new JsonObject();
            o.addProperty("reflect", reflect);
            o.addProperty("lightScreen", lightScreen);
            o.addProperty("auroraVeil", auroraVeil);
            o.addProperty("sr", stealthRock);
            o.addProperty("spikes", spikes);
            o.addProperty("tailwind", tailwind);
            // Coup d'Main / Garde-Ami : seulement en combat double
            o.addProperty("helpingHand", doubles && helpingHand);
            o.addProperty("friendGuard", doubles && friendGuard);
            return o;
        }
    }

    JsonObject toRequest() {
        JsonObject o = new JsonObject();
        o.addProperty("weather", weather);
        o.addProperty("terrain", terrain);
        o.addProperty("gravity", gravity);
        o.addProperty("doubles", doubles);
        o.add("a", a.toRequest(doubles));
        o.add("b", b.toRequest(doubles));
        return o;
    }
}
