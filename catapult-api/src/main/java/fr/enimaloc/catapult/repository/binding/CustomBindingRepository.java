package fr.enimaloc.catapult.repository.binding;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.CustomBinding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CustomBindingRepository extends JpaRepository<CustomBinding, UUID> {

    List<CustomBinding> findByUserOrderByCreatedAtAsc(UserAccount user);

    Optional<CustomBinding> findByIdAndUser(UUID id, UserAccount user);

    Optional<CustomBinding> findByUserAndNameIgnoreCase(UserAccount user, String name);

    long countByUser(UserAccount user);
}
