package fr.enimaloc.catapult.repository;

import fr.enimaloc.catapult.domain.Experiment;
import fr.enimaloc.catapult.domain.ExperimentAssignment;
import fr.enimaloc.catapult.domain.ExperimentVariant;
import fr.enimaloc.catapult.domain.UserAccount;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExperimentAssignmentRepository extends JpaRepository<ExperimentAssignment, UUID> {
    Optional<ExperimentAssignment> findByExperimentAndUser(Experiment experiment, UserAccount user);

    @Query("""
        SELECT a FROM ExperimentAssignment a
        JOIN FETCH a.experiment e
        JOIN FETCH a.variant
        WHERE a.user = :user AND e.status = fr.enimaloc.catapult.domain.Experiment.Status.ACTIVE
        """)
    List<ExperimentAssignment> findActiveByUser(@Param("user") UserAccount user);

    long countByExperimentAndVariant(Experiment experiment, ExperimentVariant variant);

    @Query(value = """
        SELECT a FROM ExperimentAssignment a
        JOIN FETCH a.user
        JOIN FETCH a.variant
        WHERE a.experiment = :experiment
        """,
        countQuery = "SELECT COUNT(a) FROM ExperimentAssignment a WHERE a.experiment = :experiment")
    Page<ExperimentAssignment> findByExperiment(@Param("experiment") Experiment experiment, Pageable pageable);

    List<ExperimentAssignment> findAllByExperiment(Experiment experiment);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM ExperimentAssignment a WHERE a.experiment = :experiment")
    void deleteAllByExperiment(@Param("experiment") Experiment experiment);
}
