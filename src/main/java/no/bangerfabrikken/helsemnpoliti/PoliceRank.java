package no.bangerfabrikken.helsemnpoliti;

import java.util.Locale;
import java.util.Optional;

public enum PoliceRank {
    POLITI("politi", "Politi"),
    OVERBETJENT("overbetjent", "Overbetjent"),
    POLITISJEF("politisjef", "Politisjef");

    private final String id;
    private final String displayName;

    PoliceRank(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public String id() { return id; }
    public String displayName() { return displayName; }

    public static Optional<PoliceRank> byId(String id) {
        String normalized = id.toLowerCase(Locale.ROOT);
        for (PoliceRank rank : values()) {
            if (rank.id.equals(normalized)) return Optional.of(rank);
        }
        return Optional.empty();
    }
}
