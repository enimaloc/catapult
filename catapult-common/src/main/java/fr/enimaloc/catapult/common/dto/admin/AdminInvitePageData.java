package fr.enimaloc.catapult.common.dto.admin;

import java.util.List;

public record AdminInvitePageData(List<AdminInviteRow> invites, List<MemberDto> membersWithoutInvite,
                                  Integer globalMaxMembers, Integer defaultMaxUses,
                                  boolean defaultCanReinvite, boolean globalCapReached) {}
