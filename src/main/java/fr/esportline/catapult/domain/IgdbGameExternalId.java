package fr.esportline.catapult.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "igdb_game_external_id")
@IdClass(IgdbGameExternalIdPk.class)
@Getter
@NoArgsConstructor
public class IgdbGameExternalId {

    @Id
    @Column(name = "igdb_id")
    private String igdbId;

    @Id
    @Column(name = "source_id")
    private long sourceId;

    @Column(nullable = false)
    private String uid;

    public IgdbGameExternalId(String igdbId, long sourceId, String uid) {
        this.igdbId   = igdbId;
        this.sourceId = sourceId;
        this.uid      = uid;
    }
}
