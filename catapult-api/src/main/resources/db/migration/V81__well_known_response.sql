-- A well-known file's whole response: its status (a 3xx with a Location header is a redirect) and
-- its headers, a JSON array of {"name","value"} in the order they are sent.
ALTER TABLE well_known_file ADD COLUMN status_code INT NOT NULL DEFAULT 200;
ALTER TABLE well_known_file ADD COLUMN headers TEXT NOT NULL DEFAULT '[]';
