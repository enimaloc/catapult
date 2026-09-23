package fr.enimaloc.catapult.common.dto;

public record GlobalSettingsRequest(Integer globalMaxMembers, Integer defaultMaxUses,
                                    boolean defaultCanReinvite) {}
