package fr.enimaloc.catapult.common.dto.admin;

import java.util.Set;

public record SaveMappingsRequest(Set<Long> igdbCategoryIds) {}
