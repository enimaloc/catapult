package fr.enimaloc.catapult.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "user_flags",
       uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "flag_key"}))
@Getter
@Setter
public class UserFlag {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserAccount user;

    @Column(name = "flag_key", nullable = false)
    private String flagKey;

    @Column(name = "flag_value", length = 1024)
    private String flagValue;
}
