package fr.enimaloc.catapult.common.dto.admin;

import java.util.UUID;

/** The start of an Ankama login: the admin opens {@code authorizeUrl}, then sends back the URL it redirects to. */
public record AnkamaLoginStartDto(UUID loginId, String authorizeUrl) {}
