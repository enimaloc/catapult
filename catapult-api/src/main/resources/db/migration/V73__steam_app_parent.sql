CREATE TABLE steam_app_parent (
    app_id        VARCHAR(20)  PRIMARY KEY,
    parent_app_id VARCHAR(20),
    parent_name   VARCHAR(500),
    resolved_at   TIMESTAMP    NOT NULL DEFAULT now()
);
