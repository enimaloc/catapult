package fr.enimaloc.catapult.experiment.targeting.resolver;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.domain.UserGroup;
import fr.enimaloc.catapult.experiment.targeting.AttributeValue;
import fr.enimaloc.catapult.repository.UserGroupRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GroupResolverTest {

    @Mock UserGroupRepository groupRepository;
    @InjectMocks GroupResolver resolver;

    @Test
    void resolvesGroupKeysAsMulti() {
        UserAccount user = new UserAccount();
        user.setId(java.util.UUID.randomUUID());
        UserGroup g1 = new UserGroup(); g1.setKey("beta");
        UserGroup g2 = new UserGroup(); g2.setKey("fr");
        g1.setMembers(Set.of(user));
        g2.setMembers(Set.of());
        when(groupRepository.findAll()).thenReturn(List.of(g1, g2));

        AttributeValue value = resolver.resolve(user, "group");
        assertThat(value).isInstanceOf(AttributeValue.Multi.class);
        assertThat(((AttributeValue.Multi) value).values()).containsExactly("beta");
    }

    @Test
    void supportsOnlyGroupKey() {
        assertThat(resolver.supports("group")).isTrue();
        assertThat(resolver.supports("flag:x")).isFalse();
    }
}
