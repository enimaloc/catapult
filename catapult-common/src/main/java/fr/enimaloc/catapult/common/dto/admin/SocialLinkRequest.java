package fr.enimaloc.catapult.common.dto.admin;

/** A social link to create or update; a new link goes last, the order is changed with {@link SocialLinkOrderRequest}. */
public record SocialLinkRequest(SocialPlatform platform, String label, String url, boolean visible) {}
