package fr.enimaloc.catapult.common.dto.admin;

public record InviteSettingsRequest(Integer globalMaxMembers, Integer defaultMaxUses,
                                    boolean defaultCanReinvite) {}
