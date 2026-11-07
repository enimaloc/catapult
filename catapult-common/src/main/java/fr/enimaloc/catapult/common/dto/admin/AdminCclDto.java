package fr.enimaloc.catapult.common.dto.admin;

import java.util.List;
import java.util.stream.Collectors;

/** A Twitch content classification label and the IGDB descriptors that imply it. */
public record AdminCclDto(String id, String name, String description, List<AdminIgdbDescriptorDto> igdbMappings) {

    /** Its mapped descriptors' ids, sorted and joined by commas: what its card starts with selected. */
    public String mappedIds() {
        return igdbMappings == null ? "" : igdbMappings.stream()
                .map(AdminIgdbDescriptorDto::id).sorted().map(String::valueOf)
                .collect(Collectors.joining(","));
    }
}
