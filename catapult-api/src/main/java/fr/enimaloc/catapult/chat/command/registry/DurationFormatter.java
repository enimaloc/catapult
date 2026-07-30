package fr.enimaloc.catapult.chat.command.registry;

import java.time.Duration;

/** Shared "2h34m"-style formatting for DSL functions that surface a duration (uptime, playtime, ...). */
public final class DurationFormatter {

    private DurationFormatter() {
    }

    public static String format(Duration duration) {
        long hours = duration.toHours();
        long minutes = duration.toMinutesPart();
        return hours + "h" + minutes + "m";
    }
}
