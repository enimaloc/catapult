package fr.enimaloc.catapult.repository.feedback;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.feedback.FeedbackSubmission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface FeedbackSubmissionRepository extends JpaRepository<FeedbackSubmission, UUID> {

    List<FeedbackSubmission> findByUserOrderByCreatedAtDesc(UserAccount user);

    List<FeedbackSubmission> findBySubscribedTrue();

    void deleteByUser(UserAccount user);
}
