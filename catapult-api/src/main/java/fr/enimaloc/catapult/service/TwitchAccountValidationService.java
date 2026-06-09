package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TwitchAccountValidationService {

    private static final String TWITCH_USERS_URL = "https://api.twitch.tv/helix/users";
    private static final int BATCH_SIZE = 100;

    private final UserAccountRepository userAccountRepository;
    private final TwitchTokenService twitchTokenService;
    private final RestClient restClient;

    @Value("${twitch.client-id:}")
    private String twitchClientId;

    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void validateAccounts() {
        List<UserAccount> accounts = userAccountRepository
            .findByStatusAndTwitchIdNotNull(UserAccount.Status.ACTIVE);
        if (accounts.isEmpty()) return;

        log.info("Validating {} Twitch accounts", accounts.size());
        String appToken;
        try {
            appToken = twitchTokenService.getAppAccessToken();
        } catch (Exception e) {
            log.error("Failed to obtain app access token for Twitch validation", e);
            return;
        }

        for (int i = 0; i < accounts.size(); i += BATCH_SIZE) {
            List<UserAccount> batch = accounts.subList(i, Math.min(i + BATCH_SIZE, accounts.size()));
            processBatch(batch, appToken);
        }
    }

    @SuppressWarnings("unchecked")
    private void processBatch(List<UserAccount> batch, String appToken) {
        String ids = batch.stream().map(UserAccount::getTwitchId).collect(Collectors.joining("&id="));
        String uri = TWITCH_USERS_URL + "?id=" + ids;

        Map<String, Object> response;
        try {
            response = restClient.get()
                .uri(uri)
                .header("Authorization", "Bearer " + appToken)
                .header("Client-Id", twitchClientId)
                .retrieve()
                .body(Map.class);
        } catch (Exception e) {
            log.error("Twitch API call failed for batch of {} accounts, skipping", batch.size(), e);
            return;
        }

        if (response == null) {
            log.warn("Empty response from Twitch users API for batch of {} accounts, skipping", batch.size());
            return;
        }

        List<Map<String, Object>> data = (List<Map<String, Object>>) response.getOrDefault("data", List.of());
        Set<String> validIds = data.stream()
            .map(u -> (String) u.get("id"))
            .collect(Collectors.toSet());
        Map<String, Map<String, Object>> byId = data.stream()
            .collect(Collectors.toMap(u -> (String) u.get("id"), u -> u));

        List<UserAccount> modified = new ArrayList<>();
        for (UserAccount account : batch) {
            if (!validIds.contains(account.getTwitchId())) {
                account.setStatus(UserAccount.Status.INACTIVE);
                log.warn("Twitch account {} (twitchId={}) no longer exists — marking INACTIVE",
                    account.getId(), account.getTwitchId());
                modified.add(account);
            } else {
                Map<String, Object> twitchData = byId.get(account.getTwitchId());
                boolean changed = false;
                String newLogin = (String) twitchData.get("login");
                String newAvatar = (String) twitchData.get("profile_image_url");
                if (newLogin != null && !newLogin.isEmpty() && !newLogin.equals(account.getTwitchUsername())) {
                    account.setTwitchUsername(newLogin);
                    changed = true;
                }
                if (newAvatar != null && !newAvatar.isEmpty() && !newAvatar.equals(account.getProfileImageUrl())) {
                    account.setProfileImageUrl(newAvatar);
                    changed = true;
                }
                if (changed) modified.add(account);
            }
        }
        if (!modified.isEmpty()) {
            userAccountRepository.saveAll(modified);
        }
    }
}
