package fr.enimaloc.catapult.common.dto.admin;

import java.time.Instant;

/** A meta tag catapult-web adds to the head of every page of the site, configured from the admin page. */
public record MetaTagDto(long id, MetaTagAttribute attribute, String key, String content, Instant updatedAt) {}
