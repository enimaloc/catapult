package fr.enimaloc.catapult.common.dto.admin;

import java.util.List;

/** Every social link id, in the order the links must be shown. */
public record SocialLinkOrderRequest(List<Long> ids) {}
