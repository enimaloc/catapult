package fr.enimaloc.catapult.common.dto;

public record MinecraftData(
        String status,
        String minecraftName,
        String serviceAccountUsername
) {
    public boolean connected() {
        return "ACCEPTED".equalsIgnoreCase(status);
    }
}
