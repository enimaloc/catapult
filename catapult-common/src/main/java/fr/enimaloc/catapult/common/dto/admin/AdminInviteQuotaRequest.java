package fr.enimaloc.catapult.common.dto.admin;

public record AdminInviteQuotaRequest(Integer maxUses, Boolean canReinvite) {}
