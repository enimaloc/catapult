package fr.enimaloc.catapult.repository.dryrun;

import fr.enimaloc.catapult.domain.dryrun.MemberDryRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface MemberDryRunRepository extends JpaRepository<MemberDryRun, UUID> {
}
