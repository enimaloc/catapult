package fr.enimaloc.catapult.domain.meta;

import fr.enimaloc.catapult.common.dto.admin.MetaTagAttribute;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** A meta tag catapult-web adds to every page, configured from the admin page (see V82). */
@Entity
@Table(name = "meta_tag")
@Getter
@Setter
@NoArgsConstructor
public class MetaTag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private MetaTagAttribute attribute;

    @Column(name = "tag_key", nullable = false, length = 100)
    private String key;

    @Column(nullable = false, length = 1000)
    private String content;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
