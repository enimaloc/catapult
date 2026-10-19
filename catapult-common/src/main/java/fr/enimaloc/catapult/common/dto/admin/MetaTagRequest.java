package fr.enimaloc.catapult.common.dto.admin;

/** A meta tag to create or update: {@code <meta [attribute]="[key]" content="[content]">}. */
public record MetaTagRequest(MetaTagAttribute attribute, String key, String content) {}
