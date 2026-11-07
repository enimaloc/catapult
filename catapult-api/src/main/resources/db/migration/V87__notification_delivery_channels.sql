-- Where a member also wants Catapult's notifications, besides the site (opt-in, none by default).
CREATE TABLE notification_channel_preference (
    user_id UUID NOT NULL REFERENCES user_account(id) ON DELETE CASCADE,
    channel VARCHAR(30) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (user_id, channel)
);

-- A notification still to be sent to one member through one of their channels: deleted once delivered or
-- cancelled (read, channel removed, expired). Keyed by what it delivers, so no surrogate id is needed and the
-- set-based INSERT ... SELECT that creates them stays portable (PostgreSQL and H2).
CREATE TABLE notification_delivery (
    notification_id UUID NOT NULL REFERENCES notification(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES user_account(id) ON DELETE CASCADE,
    channel VARCHAR(30) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (notification_id, user_id, channel)
);

CREATE INDEX idx_notification_delivery_user_channel ON notification_delivery(user_id, channel, created_at);
