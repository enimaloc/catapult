package fr.enimaloc.catapult.dto.index;

public record PlatformDto(IconType iconType, String icon, String titleKey, String descriptionKey) {
    public PlatformDto(String icon, String baseKey) {
        this(IconType.MDUI_ICON, icon, baseKey);
    }

    public PlatformDto(IconType iconType, String icon, String baseKey) {
        this(iconType, icon, baseKey+".title", baseKey+".description");
    }

    public enum IconType {
        MDUI_ICON,
        TEXT
    }
}
