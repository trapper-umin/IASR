package com.iasr.benchmark;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** A seeded permutation with rotating positions, balanced over each cycle of modes. */
public final class RunOrder {
    private RunOrder() { }

    public static List<String> forRepetition(List<String> modes, String scenario, long seed, int repetition) {
        if (modes.isEmpty() || repetition < 0) throw new IllegalArgumentException("Invalid run order");
        List<String> ordered = new ArrayList<>(modes);
        Collections.shuffle(ordered, new Random(seed ^ scenario.hashCode()));
        Collections.rotate(ordered, -(repetition % modes.size()));
        return List.copyOf(ordered);
    }
}
