-- The new features (catapult-web's NewFeatures: a tab, by key) each account has opened, so the
-- "new" dot next to them stays gone on every device once seen.
CREATE TABLE seen_feature (
    user_id UUID NOT NULL REFERENCES user_account(id) ON DELETE CASCADE,
    feature_key VARCHAR(100) NOT NULL,
    seen_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (user_id, feature_key)
);
