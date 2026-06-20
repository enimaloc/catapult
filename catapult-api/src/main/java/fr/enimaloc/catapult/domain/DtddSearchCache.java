package fr.enimaloc.catapult.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;

@Entity
@Table(name = "dtdd_search_cache")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DtddSearchCache {

    @Id
    @Column(name = "query_normalized", nullable = false, length = 256)
    private String queryNormalized;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "dtdd_ids", nullable = false, columnDefinition = "jsonb")
    private List<Long> dtddIds;

    @Column(name = "searched_at", nullable = false)
    private Instant searchedAt;
}
