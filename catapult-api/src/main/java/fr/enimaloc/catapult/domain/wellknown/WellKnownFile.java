package fr.enimaloc.catapult.domain.wellknown;

import fr.enimaloc.catapult.common.dto.admin.WellKnownLocation;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** A file catapult-web serves publicly, configured from the admin page (see V80). */
@Entity
@Table(name = "well_known_file")
@Getter
@Setter
@NoArgsConstructor
public class WellKnownFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private WellKnownLocation location;

    @Column(nullable = false)
    private String path;

    @Column(name = "status_code", nullable = false)
    private int status = 200;

    /** JSON array of {"name","value"}; see WellKnownHeaders. */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String headers = "[]";

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
