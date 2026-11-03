package com.goat.feurcalc;

import com.google.gson.JsonObject;

/** Météo, champ, gravité, coup critique, et les protections de chaque côté. */
public final class FieldState {
    public String weather = "";
    public String terrain = "";
    public boolean gravity;
    public boolean crit;
    public final Side a = new Side();
    public final Side b = new Side();

    public static final class Side {
        public boolean reflect;
        public boolean lightScreen;
        public boolean auroraVeil;
        public boolean stealthRock;
        public boolean tailwind;
        public int spikes;

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
        }

        JsonObject toRequest() {
            JsonObject o = new JsonObject();
            o.addProperty("reflect", reflect);
            o.addProperty("lightScreen", lightScreen);
            o.addProperty("auroraVeil", auroraVeil);
            o.addProperty("sr", stealthRock);
            o.addProperty("spikes", spikes);
            o.addProperty("tailwind", tailwind);
            return o;
        }
    }

    JsonObject toRequest() {
        JsonObject o = new JsonObject();
        o.addProperty("weather", weather);
        o.addProperty("terrain", terrain);
        o.addProperty("gravity", gravity);
        o.add("a", a.toRequest());
        o.add("b", b.toRequest());
        return o;
    }
}
