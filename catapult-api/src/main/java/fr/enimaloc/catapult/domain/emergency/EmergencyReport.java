package fr.enimaloc.catapult.domain.emergency;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GetterConfig;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** A viewer's report that a provider is detecting the wrong game on a channel. */
@Entity
@Table(name = "emergency_report")
@Getter
@Setter
@NoArgsConstructor
public class EmergencyReport {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "channel_user_id", nullable = false)
    private UserAccount channel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GetterConfig.Provider provider;

    @Column(name = "reported_by_twitch_id", nullable = false)
    private String reportedByTwitchId;

    @Column(name = "reported_by_login", nullable = false)
    private String reportedByLogin;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "withdrawn_at")
    private Instant withdrawnAt;

    @Column(name = "stream_id")
    private String streamId;

    @Column(name = "stream_started_at")
    private Instant streamStartedAt;

    @Column(name = "vod_id")
    private String vodId;

    /** Raw JSON snapshot of what the provider detected when the report was made. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String context;
}
