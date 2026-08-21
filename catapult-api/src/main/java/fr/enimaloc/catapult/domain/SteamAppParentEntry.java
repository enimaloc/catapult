package fr.enimaloc.catapult.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "steam_app_parent")
@Getter
@Setter
@NoArgsConstructor
public class SteamAppParentEntry {

    @Id
    @Column(name = "app_id")
    private String appId;

    @Column(name = "parent_app_id")
    private String parentAppId;

    @Column(name = "parent_name")
    private String parentName;

    @Column(name = "resolved_at", nullable = false)
    private Instant resolvedAt;

    public SteamAppParentEntry(String appId, String parentAppId, String parentName) {
        this.appId = appId;
        this.parentAppId = parentAppId;
        this.parentName = parentName;
        this.resolvedAt = Instant.now();
    }
}
