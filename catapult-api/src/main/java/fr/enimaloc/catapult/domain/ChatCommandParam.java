package fr.enimaloc.catapult.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * A free-form {@code key=value} pair a streamer defines for their own account, readable from
 * any of their chat commands as {@code ctx.params.<key>} (see {@code ParamGetExpr}). Global per
 * streamer, not scoped to one command — distinct from {@link ChatCommandFallback} (per-command)
 * and from {@link UserFlag} (an admin/experiment-targeting mechanism, not streamer-editable).
 */
@Entity
@Table(name = "chat_command_param", uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "key"}))
@Getter
@Setter
public class ChatCommandParam {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserAccount user;

    @Column(name = "key", nullable = false, length = 64)
    private String key;

    @Column(name = "value", nullable = false)
    private String value;
}
