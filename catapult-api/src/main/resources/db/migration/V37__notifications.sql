CREATE TABLE notification (
    id UUID PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    body TEXT NOT NULL,
    severity VARCHAR(16) NOT NULL,
    cta_url VARCHAR(512),
    cta_label VARCHAR(64),
    expires_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    created_by UUID NOT NULL REFERENCES user_account(id),
    audience VARCHAR(16) NOT NULL
);

CREATE TABLE notification_recipient (
    notification_id UUID NOT NULL REFERENCES notification(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES user_account(id) ON DELETE CASCADE,
    read_at TIMESTAMP,
    PRIMARY KEY (notification_id, user_id)
);

CREATE INDEX idx_notification_recipient_user_read ON notification_recipient(user_id, read_at);
CREATE INDEX idx_notification_expires_at ON notification(expires_at);
