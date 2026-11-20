-- Hex of the wire bytes of binary exchanges (BGS, Ankama), next to their decoded form.
ALTER TABLE debug_exchange ADD COLUMN request_raw TEXT;
ALTER TABLE debug_exchange ADD COLUMN response_raw TEXT;
