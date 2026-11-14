package fr.enimaloc.catapult.common.dto;

import java.util.List;

/**
 * The gated platforms open to every member, by id ({@code battlenet}, {@code ankama}): configured, and their experiment
 * fully enabled. The landing page lists them next to the ones it always shows.
 */
public record LaunchedPlatformsResponse(List<String> platforms) {
    public LaunchedPlatformsResponse {
        platforms = platforms == null ? List.of() : List.copyOf(platforms);
    }
}
