package fr.enimaloc.catapult.common.dto.admin;

public record AdminWhitelistQuotaRequest(Integer maxUses, Boolean canReinvite) {}
