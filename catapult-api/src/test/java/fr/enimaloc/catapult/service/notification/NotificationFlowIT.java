package fr.enimaloc.catapult.service.notification;

import fr.enimaloc.catapult.domain.Notification;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.security.TwitchLoginSuccessHandler;
import fr.enimaloc.catapult.service.AdminCclService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("mock-web")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:catapult_notification_flow_it;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;NON_KEYWORDS=KEY,VALUE")
class NotificationFlowIT {

    @MockitoBean
    AdminCclService adminCclService;

    @MockitoBean
    TwitchLoginSuccessHandler twitchLoginSuccessHandler;

    @Autowired NotificationService service;
    @Autowired UserAccountRepository userRepo;

    @Test
    void targetedNotification_isVisibleToTargetUserOnly() {
        UserAccount admin = persistUser("admin-it");
        UserAccount target = persistUser("target-it");
        UserAccount other = persistUser("other-it");

        NotificationService.CreateRequest req = new NotificationService.CreateRequest(
                "Hello", "**body**", Notification.Severity.INFO, null, null, null, target.getId());

        service.create(req, admin);

        long unread = service.unreadCount(target.getId());
        assertThat(unread).isEqualTo(1);
        assertThat(service.unreadCount(other.getId())).isZero();
    }

    private UserAccount persistUser(String suffix) {
        UserAccount u = new UserAccount();
        u.setTwitchId(suffix + "-" + UUID.randomUUID());
        u.setTwitchUsername(suffix);
        return userRepo.save(u);
    }
}
