package fr.enimaloc.catapult.common.dto.channel;

public record AnkamaData(
        String status,
        String nameTag,
        String serviceAccountTag
) {
    public boolean connected() {
        return "ACCEPTED".equalsIgnoreCase(status);
    }
}
