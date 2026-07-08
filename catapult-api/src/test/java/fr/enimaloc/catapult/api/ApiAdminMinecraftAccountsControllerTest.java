package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.domain.MinecraftServiceAccount;
import fr.enimaloc.catapult.repository.MinecraftFriendLinkRepository;
import fr.enimaloc.catapult.repository.MinecraftServiceAccountRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import fr.enimaloc.catapult.service.MinecraftService;
import fr.enimaloc.catapult.service.MinecraftTokenService;
import fr.enimaloc.catapult.service.MsaAuthClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ApiAdminMinecraftAccountsControllerTest {

    @Mock private MsaAuthClient msaAuthClient;
    @Mock private MinecraftTokenService tokenService;
    @Mock private MinecraftService minecraftService;
    @Mock private MinecraftServiceAccountRepository accountRepository;
    @Mock private MinecraftFriendLinkRepository linkRepository;
    @Mock private TokenEncryptionService encryption;

    @InjectMocks private ApiAdminMinecraftAccountsController controller;

    @BeforeEach
    void setup() {
        when(encryption.encrypt(anyString())).thenAnswer(inv -> "enc:" + inv.getArgument(0));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void create_pendingDeviceCode_returns202() {
        when(msaAuthClient.pollDeviceCode("device-1")).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.create(Map.of("deviceCode", "device-1", "label", "Bot1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    void create_validatedDeviceCode_createsAccountWithEncryptedRefreshAndUsername() {
        when(msaAuthClient.pollDeviceCode("device-1"))
                .thenReturn(Optional.of(new MsaAuthClient.MsaTokens("access", "refresh-1", 3600)));
        when(tokenService.getToken(any(MinecraftServiceAccount.class))).thenReturn(Optional.of("mc-token"));
        when(minecraftService.getMinecraftProfileName("mc-token")).thenReturn("CatapultBot1");

        ResponseEntity<?> response = controller.create(Map.of("deviceCode", "device-1", "label", "Bot1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(accountRepository).save(org.mockito.ArgumentMatchers.argThat(account ->
                account.getMsaRefreshToken().equals("enc:refresh-1")
                        && account.getMinecraftUsername().equals("CatapultBot1")
                        && account.getLabel().equals("Bot1")));
    }

    @Test
    void delete_accountWithLinks_returns409() {
        var account = new MinecraftServiceAccount();
        account.setId(UUID.randomUUID());
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));
        when(linkRepository.findByServiceAccount(account)).thenReturn(java.util.List.of(new fr.enimaloc.catapult.domain.MinecraftFriendLink()));

        ResponseEntity<?> response = controller.delete(account.getId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
}
