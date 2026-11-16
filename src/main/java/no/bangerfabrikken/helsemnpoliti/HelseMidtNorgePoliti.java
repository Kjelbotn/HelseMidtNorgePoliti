package no.bangerfabrikken.helsemnpoliti;

import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;

@Mod(HelseMidtNorgePoliti.MOD_ID)
public final class HelseMidtNorgePoliti {
    public static final String MOD_ID = "helsemnpoliti";

    public HelseMidtNorgePoliti() {
        NeoForge.EVENT_BUS.register(PoliceEvents.class);
    }
}
