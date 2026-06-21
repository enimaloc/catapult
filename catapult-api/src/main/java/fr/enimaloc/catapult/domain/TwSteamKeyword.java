package fr.enimaloc.catapult.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "tw_steam_keyword",
    uniqueConstraints = @UniqueConstraint(columnNames = {"tw_id", "keyword"}))
@Getter
@Setter
public class TwSteamKeyword {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tw_id", nullable = false, length = 40)
    private String twId;

    @Column(nullable = false, length = 50)
    private String keyword;
}
