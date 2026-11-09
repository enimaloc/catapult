package fr.enimaloc.catapult.domain.social;

import fr.enimaloc.catapult.common.dto.admin.SocialPlatform;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A social link catapult-web shows in the footer of every page, configured from the admin page (see V93). */
@Entity
@Table(name = "social_link")
@Getter
@Setter
@NoArgsConstructor
public class SocialLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SocialPlatform platform;

    @Column(length = 64)
    private String label;

    @Column(nullable = false, length = 2048)
    private String url;

    @Column(nullable = false)
    private boolean visible;

    @Column(nullable = false)
    private int position;
}
