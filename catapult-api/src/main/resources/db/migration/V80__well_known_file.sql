-- Files catapult-web serves under /.well-known/ (location WELL_KNOWN, path possibly multi-segment:
-- acme-challenge/<token>) or at the site's root (location ROOT, one dotted segment: riot.txt),
-- edited from the admin page's Well-known tab.
CREATE TABLE well_known_file (
    id BIGSERIAL PRIMARY KEY,
    location VARCHAR(16) NOT NULL,
    path VARCHAR(255) NOT NULL,
    content_type VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_well_known_file_location_path UNIQUE (location, path)
);
