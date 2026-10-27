package fr.enimaloc.catapult.common.dto.admin;

/** A social link shown in the footer of the site, configured from the admin page. */
public record SocialLinkDto(long id, SocialPlatform platform, String label, String url, boolean visible, int position) {}
