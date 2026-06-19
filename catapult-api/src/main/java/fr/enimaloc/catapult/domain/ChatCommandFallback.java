package fr.enimaloc.catapult.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Placeholder-specific fallback text attached to a {@link ChatCommandDefinition}.
 * Mirrors the {@code chat_command_fallback} table defined in V39.
 */
@Entity
@Table(name = "chat_command_fallback")
@IdClass(ChatCommandFallback.Pk.class)
@Getter
@Setter
@NoArgsConstructor
public class ChatCommandFallback {

    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "command_id", nullable = false)
    private ChatCommandDefinition command;

    @Id
    @Column(nullable = false, length = 64)
    private String placeholder;

    @Column(name = "fallback_text", nullable = false, length = 200)
    private String fallbackText;

    /**
     * Composite primary key for {@link ChatCommandFallback}.
     * The {@code command} field name matches the entity field that owns the join column.
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Pk implements Serializable {

        private UUID command;
        private String placeholder;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Pk pk)) return false;
            return Objects.equals(command, pk.command) && Objects.equals(placeholder, pk.placeholder);
        }

        @Override
        public int hashCode() {
            return Objects.hash(command, placeholder);
        }
    }
}
