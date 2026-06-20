CREATE TABLE dtdd_mapping_proposal (
    id                  UUID         PRIMARY KEY,
    igdb_id             VARCHAR(64)  NOT NULL,
    proposed_dtdd_id    BIGINT       NULL,
    proposer_user_id    UUID         NOT NULL REFERENCES user_account(id) ON DELETE CASCADE,
    reason              VARCHAR(500),
    status              VARCHAR(16)  NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL,
    resolved_at         TIMESTAMPTZ  NULL,
    resolver_id         UUID         NULL REFERENCES user_account(id) ON DELETE SET NULL
);

CREATE INDEX idx_dtdd_proposal_status_igdb ON dtdd_mapping_proposal (status, igdb_id);
CREATE INDEX idx_dtdd_proposal_proposer ON dtdd_mapping_proposal (proposer_user_id);
