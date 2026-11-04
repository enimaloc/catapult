package fr.enimaloc.catapult.controller;

/**
 * A polling interval split for display: the message to use ("30 s", "1 min", "1 min 30") and its
 * {minutes} / {seconds} arguments.
 */
public record PremiumDuration(String key, int minutes, int seconds) {

    public static PremiumDuration of(int totalSeconds) {
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        if (minutes == 0) return new PremiumDuration("premium.duration.seconds", 0, seconds);
        if (seconds == 0) return new PremiumDuration("premium.duration.minutes", minutes, 0);
        return new PremiumDuration("premium.duration.minutes_seconds", minutes, seconds);
    }
}
