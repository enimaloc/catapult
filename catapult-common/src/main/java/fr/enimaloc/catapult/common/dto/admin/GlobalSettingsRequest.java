package fr.enimaloc.catapult.common.dto.admin;

public record GlobalSettingsRequest(Integer globalMaxMembers, Integer defaultMaxUses,
                                    boolean defaultCanReinvite) {}
