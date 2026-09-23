package fr.enimaloc.catapult.common.dto;

public record KeyStatus(String id, String masked, String owner, boolean blocked, long blockedForSeconds) {}
