CREATE TABLE user_groups (
    id          UUID PRIMARY KEY,
    key         VARCHAR(255) NOT NULL UNIQUE,
    name        VARCHAR(255) NOT NULL,
    description VARCHAR(255),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE user_group_members (
    group_id UUID NOT NULL REFERENCES user_groups(id) ON DELETE CASCADE,
    user_id  UUID NOT NULL REFERENCES user_account(id) ON DELETE CASCADE,
    PRIMARY KEY (group_id, user_id)
);
