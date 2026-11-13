package fr.enimaloc.catapult.common.dto.channel;

public record BattleNetData(
        String status,
        String battleTag,
        String serviceAccountBattleTag
) {
    public boolean connected() {
        return "ACCEPTED".equalsIgnoreCase(status);
    }
}
