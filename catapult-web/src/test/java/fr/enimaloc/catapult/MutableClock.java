package fr.enimaloc.catapult;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A UTC clock the test moves by hand, through {@link #now} or {@link #advance(Duration)}. */
public final class MutableClock extends Clock {
    public Instant now;

    public MutableClock(Instant now) {
        this.now = now;
    }

    public void advance(Duration d) {
        now = now.plus(d);
    }

    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return now; }
}
