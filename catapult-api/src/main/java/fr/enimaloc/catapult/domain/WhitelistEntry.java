package fr.enimaloc.catapult.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "whitelist_entry")
@Getter
@Setter
@NoArgsConstructor
public class WhitelistEntry {

    @Id
    @Column(name = "twitch_id", nullable = false)
    private String twitchId;

    public WhitelistEntry(String twitchId) {
        this.twitchId = twitchId;
    }
}
