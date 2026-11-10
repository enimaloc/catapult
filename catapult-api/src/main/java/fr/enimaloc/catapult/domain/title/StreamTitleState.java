package fr.enimaloc.catapult.domain.title;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/** What Catapult knows of a channel's title, for {@code {{ORIGINAL_TITLE}}}, see V101. */
@Entity
@Table(name = "stream_title_state")
@Getter
@Setter
@NoArgsConstructor
public class StreamTitleState {
    @Id
    @Column(name = "user_id")
    private UUID userId;

    /** The title the streamer set themselves; null until one was read. */
    @Column(name = "original_title", length = 140)
    private String originalTitle;

    /** The last title Catapult sent; a title found different from it was changed by hand. */
    @Column(name = "last_sent_title", length = 140)
    private String lastSentTitle;

    public StreamTitleState(UUID userId) {
        this.userId = userId;
    }
}
