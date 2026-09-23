package fr.enimaloc.catapult.common.dto;

public record InviteSettingsRequest(Integer globalMaxMembers, Integer defaultMaxUses,
                                    boolean defaultCanReinvite) {}
