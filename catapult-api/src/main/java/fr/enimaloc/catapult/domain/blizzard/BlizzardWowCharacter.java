package fr.enimaloc.catapult.domain.blizzard;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.UUID;

/** One World of Warcraft character of a member, as the import listed it. {@code name} is lowercase. */
@Entity
@Table(name = "blizzard_wow_character")
@IdClass(BlizzardWowCharacter.Key.class)
@Getter
@Setter
public class BlizzardWowCharacter {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    /** Lowercase region code, {@code eu|us|kr|tw}. */
    @Id
    @Column(name = "region")
    private String region;

    @Id
    @Column(name = "character_id")
    private long characterId;

    @Column(name = "realm_slug", nullable = false)
    private String realmSlug;

    @Column(name = "realm_name", nullable = false)
    private String realmName;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "level", nullable = false)
    private int level;

    @Column(name = "class_name", nullable = false)
    private String className;

    @Column(name = "faction", nullable = false)
    private String faction;

    @Getter
    @Setter
    @NoArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        private UUID userId;
        private String region;
        private long characterId;
    }
}
