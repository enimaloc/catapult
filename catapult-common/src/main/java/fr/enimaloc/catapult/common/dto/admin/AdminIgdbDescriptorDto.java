package fr.enimaloc.catapult.common.dto.admin;

/** An IGDB content rating descriptor (e.g. ESRB "Violence"), as GET /api/admin/ccl lists it. */
public record AdminIgdbDescriptorDto(Long id, String description, Integer organizationId, String displayName) {

    /** Its display name, prefixed by its rating organization; its bare description when it has none. */
    public String label() {
        return displayName == null || displayName.isBlank() ? description : displayName;
    }
}
