CREATE TABLE feedback_submission (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id               UUID NOT NULL REFERENCES user_account(id) ON DELETE CASCADE,
    type                  VARCHAR(20)  NOT NULL,
    title                 VARCHAR(255) NOT NULL,
    description           TEXT,
    gitlab_issue_iid      INTEGER NOT NULL,
    gitlab_issue_url      VARCHAR(500) NOT NULL,
    last_known_updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    subscribed            BOOLEAN NOT NULL DEFAULT TRUE,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
