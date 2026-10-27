package fr.enimaloc.catapult.dto.index;

public record PlatformDto(IconType iconType, String icon, String id, String titleKey, String descriptionKey) {
    public PlatformDto(String icon, String baseKey) {
        this(IconType.MDUI_ICON, icon, baseKey);
    }

    public PlatformDto(IconType iconType, String icon, String baseKey) {
        this(iconType, icon, baseKey.replaceFirst("platforms\\.", ""), baseKey+".title", baseKey+".description");
    }

    public enum IconType {
        MDUI_ICON,
        TEXT
    }
}
