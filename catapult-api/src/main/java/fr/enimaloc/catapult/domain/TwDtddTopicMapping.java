package fr.enimaloc.catapult.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "tw_dtdd_topic_mapping",
    uniqueConstraints = @UniqueConstraint(columnNames = {"tw_id", "dtdd_topic_name"}))
@Getter
@Setter
public class TwDtddTopicMapping {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tw_id", nullable = false, length = 40)
    private String twId;

    @Column(name = "dtdd_topic_name", nullable = false, length = 200)
    private String dtddTopicName;
}
