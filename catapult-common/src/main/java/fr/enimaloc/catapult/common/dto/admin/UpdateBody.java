package fr.enimaloc.catapult.common.dto.admin;

public record UpdateBody(String label, String description, Integer sortOrder, Boolean enabled) {}
