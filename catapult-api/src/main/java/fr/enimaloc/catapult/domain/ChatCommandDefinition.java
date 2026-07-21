package fr.enimaloc.catapult.domain;

import fr.enimaloc.catapult.chat.ChatCommandEvent;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Data-driven chat command definition owned by a user.
 * Mirrors the {@code chat_command_definition} table defined in V39.
 */
@Entity
@Table(
    name = "chat_command_definition",
    uniqueConstraints = @UniqueConstraint(name = "uk_chat_cmd_user_name", columnNames = {"user_id", "name"})
)
@Getter
@Setter
@NoArgsConstructor
public class ChatCommandDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserAccount user;

    @Column(nullable = false, length = 32)
    private String name;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String template;

    @Column(columnDefinition = "TEXT")
    private String ast;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ChatCommandEvent.SenderRole permission;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "preset_key", length = 64)
    private String presetKey;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "command", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private Set<ChatCommandFallback> fallbacks = new HashSet<>();
}
