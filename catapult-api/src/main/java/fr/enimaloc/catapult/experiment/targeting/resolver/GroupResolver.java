package fr.enimaloc.catapult.experiment.targeting.resolver;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.domain.UserGroup;
import fr.enimaloc.catapult.experiment.targeting.AttributeResolver;
import fr.enimaloc.catapult.experiment.targeting.AttributeValue;
import fr.enimaloc.catapult.repository.UserGroupRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class GroupResolver implements AttributeResolver {

    private final UserGroupRepository groupRepository;

    @Override
    public boolean supports(String key) {
        return key.equals("group");
    }

    @Override
    public AttributeValue resolve(UserAccount user, String key) {
        Set<String> keys = groupRepository.findAll().stream()
            .filter(g -> g.getMembers().stream().anyMatch(m -> m.getId() != null && m.getId().equals(user.getId())))
            .map(UserGroup::getKey)
            .collect(Collectors.toSet());
        return AttributeValue.multi(keys);
    }
}
