package fr.enimaloc.catapult.common.dto;

public record UpdateBody(String label, String description, Integer sortOrder, Boolean enabled) {}
