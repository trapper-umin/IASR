package com.iasr.core.resource;

/**
 * One way a policy may move a resource in a single decision: a change
 * proportional to the current capacity, never smaller than {@code minStep}.
 * <p>
 * Relative levels keep the action space meaningful across scales. A step of
 * one is a tenth of a pool of ten and a hundredth of a limit of a hundred; a
 * policy restricted to absolute steps is either too coarse for the first or
 * too slow for the second.
 *
 * @param relative signed fraction of the current capacity; zero means hold
 * @param minStep  smallest absolute change when {@code relative} is not zero
 */
public record ActionLevel(double relative, int minStep) {

    public static final ActionLevel HOLD = new ActionLevel(0, 0);

    public ActionLevel {
        if (!Double.isFinite(relative) || relative <= -1 || relative > 4) {
            throw new IllegalArgumentException("relative change must be in (-1, 4]");
        }
        if (relative == 0 ? minStep != 0 : minStep < 1) {
            throw new IllegalArgumentException("a non-hold level needs minStep >= 1, hold needs 0");
        }
    }

    /** Signed change this level asks for at the given capacity, before any clamping. */
    public int delta(int current) {
        if (relative == 0) return 0;
        int magnitude = (int) Math.max(minStep, Math.floor(Math.abs(relative) * current + 0.5));
        return relative > 0 ? magnitude : -magnitude;
    }
}
