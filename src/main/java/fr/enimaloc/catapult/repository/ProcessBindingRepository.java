package fr.enimaloc.catapult.repository;

import fr.enimaloc.catapult.domain.ProcessBinding;
import fr.enimaloc.catapult.domain.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProcessBindingRepository extends JpaRepository<ProcessBinding, UUID> {

    List<ProcessBinding> findByUserOrderByProcessNameAsc(UserAccount user);

    Optional<ProcessBinding> findFirstByUserAndProcessNameIn(UserAccount user, Collection<String> processNames);

    List<ProcessBinding> findByUserAndProcessNameIn(UserAccount user, Collection<String> processNames);

    List<ProcessBinding> findByUserIsNull();
}
