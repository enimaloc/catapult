package fr.enimaloc.catapult.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "tw_steam_content_id_mapping",
    uniqueConstraints = @UniqueConstraint(columnNames = {"tw_id", "steam_content_id"}))
@Getter
@Setter
public class TwSteamContentIdMapping {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tw_id", nullable = false, length = 40)
    private String twId;

    @Column(name = "steam_content_id", nullable = false)
    private int steamContentId;
}
