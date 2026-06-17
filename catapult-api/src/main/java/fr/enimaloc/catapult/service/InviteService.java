package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.AlphaInvite;
import fr.enimaloc.catapult.domain.AlphaInviteRedemption;
import fr.enimaloc.catapult.domain.SystemSetting;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.AlphaInviteRedemptionRepository;
import fr.enimaloc.catapult.repository.AlphaInviteRepository;
import fr.enimaloc.catapult.repository.SystemSettingRepository;
import fr.enimaloc.catapult.repository.WhitelistEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class InviteService {

    static final String KEY_INVITE_ENABLED       = "invite.enabled";
    static final String KEY_GLOBAL_MAX_MEMBERS   = "invite.global_max_members";
    static final String KEY_DEFAULT_MAX_USES     = "invite.default_max_uses";
    static final String KEY_DEFAULT_CAN_REINVITE = "invite.default_can_reinvite";

    private static final String CODE_CHARS  = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int    CODE_LENGTH = 10;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AlphaInviteRepository           inviteRepository;
    private final AlphaInviteRedemptionRepository redemptionRepository;
    private final SystemSettingRepository         systemSettingRepository;
    private final WhitelistEntryRepository        whitelistEntryRepository;
    private final WhitelistService                whitelistService;

    // ── User-facing ──────────────────────────────────────────────────────────

    /**
     * Returns the user's invite, auto-creating one if the invite system is globally enabled.
     * Admin can effectively block a user from inviting by setting maxUses=0 on their invite.
     */
    @Transactional
    public Optional<AlphaInvite> getInvite(UserAccount owner) {
        Optional<AlphaInvite> existing = inviteRepository.findByOwner(owner);
        if (existing.isPresent()) return existing;
        if (isInviteEnabled() && whitelistService.isEnabled()) {
            return Optional.of(createAndSaveInvite(owner));
        }
        return Optional.empty();
    }

    public boolean isInviteEnabled() {
        return systemSettingRepository.findById(KEY_INVITE_ENABLED)
            .map(s -> Boolean.parseBoolean(s.getValue()))
            .orElse(false);
    }

    @Transactional
    public void setInviteEnabled(boolean enabled) {
        saveSetting(KEY_INVITE_ENABLED, String.valueOf(enabled));
    }

    @Transactional
    public Optional<AlphaInvite> regenerateCode(UserAccount owner) {
        Optional<AlphaInvite> inviteOpt = inviteRepository.findByOwner(owner);
        inviteOpt.ifPresent(invite -> {
            invite.setCode(generateUniqueCode());
            invite.setRegeneratedAt(Instant.now());
            inviteRepository.save(invite);
        });
        return inviteOpt;
    }

    /** Creates an invite for a user (admin operation or canReinvite grant). */
    @Transactional
    public AlphaInvite grantInvite(UserAccount owner) {
        return inviteRepository.findByOwner(owner).orElseGet(() -> createAndSaveInvite(owner));
    }

    public List<AlphaInviteRedemption> getRedemptions(AlphaInvite invite) {
        return redemptionRepository.findByInvite(invite);
    }

    // ── Redemption during OAuth login ────────────────────────────────────────

    /**
     * Validates and redeems an invite code for a not-yet-whitelisted user.
     * Adds the user to the whitelist on success.
     * Returns true if the invitee should also receive an invite (canReinvite=true).
     * Throws OAuth2AuthenticationException on any failure.
     */
    @Transactional
    public boolean redeem(String code, String inviteeTwitchId) {
        if (isGlobalCapReached()) {
            log.warn("Alpha cap reached, rejecting invite redemption for {}", inviteeTwitchId);
            throw new OAuth2AuthenticationException(new OAuth2Error("alpha_full"), "Alpha is full.");
        }

        AlphaInvite invite = inviteRepository.findByCode(code.toUpperCase())
            .orElseThrow(() -> {
                log.warn("Invalid invite code '{}' for {}", code, inviteeTwitchId);
                return new OAuth2AuthenticationException(new OAuth2Error("invalid_invite"), "Invalid invite code.");
            });

        int effectiveMax = resolveMaxUses(invite);
        if (effectiveMax >= 0 && invite.getUseCount() >= effectiveMax) {
            log.warn("Invite '{}' exhausted ({}/{}), rejecting {}", code, invite.getUseCount(), effectiveMax, inviteeTwitchId);
            throw new OAuth2AuthenticationException(new OAuth2Error("invalid_invite"), "Invite code exhausted.");
        }

        whitelistService.add(inviteeTwitchId);

        invite.setUseCount(invite.getUseCount() + 1);
        inviteRepository.save(invite);

        AlphaInviteRedemption redemption = new AlphaInviteRedemption();
        redemption.setInvite(invite);
        redemption.setInviteeTwitchId(inviteeTwitchId);
        redemptionRepository.save(redemption);

        boolean canReinvite = effectiveCanReinvite(invite);
        log.info("Invite '{}' redeemed by {} ({}/{}), canReinvite={}", code, inviteeTwitchId,
            invite.getUseCount(), effectiveMax < 0 ? "∞" : effectiveMax, canReinvite);
        return canReinvite;
    }

    // ── Admin ────────────────────────────────────────────────────────────────

    public Optional<Integer> getGlobalMaxMembers() {
        return systemSettingRepository.findById(KEY_GLOBAL_MAX_MEMBERS)
            .map(s -> parseIntOrNull(s.getValue()));
    }

    public Optional<Integer> getDefaultMaxUses() {
        return systemSettingRepository.findById(KEY_DEFAULT_MAX_USES)
            .map(s -> parseIntOrNull(s.getValue()));
    }

    public boolean getDefaultCanReinvite() {
        return systemSettingRepository.findById(KEY_DEFAULT_CAN_REINVITE)
            .map(s -> Boolean.parseBoolean(s.getValue()))
            .orElse(false);
    }

    @Transactional
    public void setGlobalMaxMembers(Integer value) {
        saveOrDelete(KEY_GLOBAL_MAX_MEMBERS, value == null ? null : String.valueOf(value));
    }

    @Transactional
    public void setDefaultMaxUses(Integer value) {
        saveOrDelete(KEY_DEFAULT_MAX_USES, value == null ? null : String.valueOf(value));
    }

    @Transactional
    public void setDefaultCanReinvite(boolean value) {
        saveSetting(KEY_DEFAULT_CAN_REINVITE, String.valueOf(value));
    }

    @Transactional
    public void updateInviteQuota(UUID inviteId, Integer maxUses, Boolean canReinvite) {
        AlphaInvite invite = inviteRepository.findById(inviteId)
            .orElseThrow(() -> new IllegalArgumentException("Invite not found: " + inviteId));
        invite.setMaxUses(maxUses);
        invite.setCanReinvite(canReinvite);
        inviteRepository.save(invite);
    }

    public List<AlphaInvite> findAll() {
        return inviteRepository.findAll();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    public boolean isGlobalCapReached() {
        return getGlobalMaxMembers()
            .map(max -> whitelistEntryRepository.count() >= max)
            .orElse(false);
    }

    private boolean effectiveCanReinvite(AlphaInvite invite) {
        return invite.getCanReinvite() != null ? invite.getCanReinvite() : getDefaultCanReinvite();
    }

    /** Returns -1 if unlimited. */
    private int resolveMaxUses(AlphaInvite invite) {
        if (invite.getMaxUses() != null) return invite.getMaxUses();
        return getDefaultMaxUses().orElse(-1);
    }

    private AlphaInvite createAndSaveInvite(UserAccount owner) {
        AlphaInvite invite = new AlphaInvite();
        invite.setOwner(owner);
        invite.setCode(generateUniqueCode());
        return inviteRepository.save(invite);
    }

    private String generateUniqueCode() {
        String code;
        int attempts = 0;
        do {
            code = generateCode();
            attempts++;
            if (attempts > 100) throw new IllegalStateException("Cannot generate unique invite code");
        } while (inviteRepository.findByCode(code).isPresent());
        return code;
    }

    private static String generateCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(CODE_CHARS.charAt(RANDOM.nextInt(CODE_CHARS.length())));
        }
        return sb.toString();
    }

    private void saveSetting(String key, String value) {
        SystemSetting s = systemSettingRepository.findById(key).orElseGet(() -> {
            SystemSetting ns = new SystemSetting();
            ns.setKey(key);
            return ns;
        });
        s.setValue(value);
        systemSettingRepository.save(s);
    }

    private void saveOrDelete(String key, String value) {
        if (value == null) {
            systemSettingRepository.deleteById(key);
        } else {
            saveSetting(key, value);
        }
    }

    private static Integer parseIntOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return null; }
    }
}
