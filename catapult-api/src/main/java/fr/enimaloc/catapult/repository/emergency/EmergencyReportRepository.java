package fr.enimaloc.catapult.repository.emergency;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GetterConfig;
import fr.enimaloc.catapult.domain.emergency.EmergencyReport;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface EmergencyReportRepository extends JpaRepository<EmergencyReport, UUID> {

    /** Reports of a provider still standing (not withdrawn) and made since the given instant. */
    @Query("select r from EmergencyReport r "
            + "where r.provider = :provider and r.withdrawnAt is null and r.createdAt >= :since")
    List<EmergencyReport> findCounting(@Param("provider") GetterConfig.Provider provider,
                                       @Param("since") Instant since);

    Page<EmergencyReport> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<EmergencyReport> findByVodIdIsNullAndStreamIdIsNotNull();

    long deleteByCreatedAtBefore(Instant cutoff);

    /** Reports of a channel still standing (not withdrawn). */
    List<EmergencyReport> findByChannelAndWithdrawnAtIsNull(UserAccount channel);
}
