package fr.enimaloc.catapult.common.dto.admin;

public record KeyStatus(String id, String masked, String owner, boolean blocked, long blockedForSeconds) {}
