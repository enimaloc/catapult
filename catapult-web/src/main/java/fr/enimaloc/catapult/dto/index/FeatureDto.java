package fr.enimaloc.catapult.dto.index;

public record FeatureDto(String icon, String titleKey, String descriptionKey) {
    public FeatureDto(String icon, String baseKey) {
        this(icon, baseKey+".title", baseKey+".description");
    }
}
