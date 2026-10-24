package fr.enimaloc.catapult.repository.experiment;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.experiment.Experiment;
import fr.enimaloc.catapult.domain.experiment.ExperimentOverride;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ExperimentOverrideRepository extends JpaRepository<ExperimentOverride, UUID> {

    @Query("SELECT o FROM ExperimentOverride o LEFT JOIN FETCH o.targetUser LEFT JOIN FETCH o.targetVariant WHERE o.experiment = :exp ORDER BY o.priority ASC")
    List<ExperimentOverride> findByExperimentOrderByPriorityAsc(@Param("exp") Experiment exp);

    /** Ids of the users a USER FORCE_VARIANT override pins to a variant of this experiment. */
    @Query("""
        SELECT o.targetUser.id FROM ExperimentOverride o
        WHERE o.experiment = :exp AND o.targetUser IS NOT NULL
          AND o.overrideType = fr.enimaloc.catapult.domain.experiment.ExperimentOverride.OverrideType.USER
          AND o.action = fr.enimaloc.catapult.domain.experiment.ExperimentOverride.OverrideAction.FORCE_VARIANT
        """)
    List<UUID> findForcedUserIds(@Param("exp") Experiment exp);

    /** The ATTRIBUTE FORCE_VARIANT overrides of this experiment. */
    @Query("""
        SELECT o FROM ExperimentOverride o
        WHERE o.experiment = :exp
          AND o.overrideType = fr.enimaloc.catapult.domain.experiment.ExperimentOverride.OverrideType.ATTRIBUTE
          AND o.action = fr.enimaloc.catapult.domain.experiment.ExperimentOverride.OverrideAction.FORCE_VARIANT
        """)
    List<ExperimentOverride> findAttributeForceVariants(@Param("exp") Experiment exp);

    void deleteByTargetUser(UserAccount targetUser);
}
