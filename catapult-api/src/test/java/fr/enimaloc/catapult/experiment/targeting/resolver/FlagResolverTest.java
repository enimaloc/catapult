package fr.enimaloc.catapult.experiment.targeting.resolver;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.domain.UserFlag;
import fr.enimaloc.catapult.experiment.targeting.AttributeValue;
import fr.enimaloc.catapult.repository.UserFlagRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FlagResolverTest {

    @Mock UserFlagRepository flagRepository;
    @InjectMocks FlagResolver resolver;

    @Test
    void resolvesFlagValueAsText() {
        UserAccount user = new UserAccount();
        UserFlag flag = new UserFlag();
        flag.setFlagKey("tier");
        flag.setFlagValue("vip");
        when(flagRepository.findByUserAndFlagKey(user, "tier")).thenReturn(Optional.of(flag));

        assertThat(resolver.supports("flag:tier")).isTrue();
        assertThat(resolver.resolve(user, "flag:tier")).isEqualTo(AttributeValue.text("vip"));
    }

    @Test
    void missingFlagReturnsMissing() {
        UserAccount user = new UserAccount();
        when(flagRepository.findByUserAndFlagKey(user, "tier")).thenReturn(Optional.empty());
        assertThat(resolver.resolve(user, "flag:tier")).isEqualTo(AttributeValue.missing());
    }
}
