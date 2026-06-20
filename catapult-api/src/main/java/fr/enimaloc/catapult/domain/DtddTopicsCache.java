package fr.enimaloc.catapult.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;

@Entity
@Table(name = "dtdd_topics_cache")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DtddTopicsCache {

    @Id
    @Column(name = "dtdd_id", nullable = false)
    private Long dtddId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "yes_topics", nullable = false, columnDefinition = "jsonb")
    private List<String> yesTopics;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "no_topics", nullable = false, columnDefinition = "jsonb")
    private List<String> noTopics;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "mostly_topics", nullable = false, columnDefinition = "jsonb")
    private List<String> mostlyTopics;

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;
}
