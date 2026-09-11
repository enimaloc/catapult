package fr.enimaloc.catapult.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "twitchat_widget_settings")
@Getter
@Setter
public class TwitchatWidgetSettings {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "user_id")
    private UserAccount user;

    @Column(nullable = false)
    private boolean enabled = false;

    @Column(name = "obs_host")
    private String obsHost;

    @Column(name = "obs_port")
    private Integer obsPort;

    @Column(name = "obs_password_encrypted")
    private String obsPasswordEncrypted;
}
