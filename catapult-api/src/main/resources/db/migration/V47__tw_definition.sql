CREATE TABLE tw_definition (
    id          VARCHAR(40)  PRIMARY KEY,
    label       VARCHAR(80)  NOT NULL,
    description TEXT,
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order  INT          NOT NULL DEFAULT 0
);

CREATE INDEX idx_tw_definition_sort ON tw_definition (sort_order);
