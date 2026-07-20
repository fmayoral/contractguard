-- No foreign key to runs: audit entries must survive retention purges of
-- their parent run (FR-023 deletes finished runs/events/artifacts by age;
-- the audit trail is deliberately exempt — see ADR-0008).
CREATE TABLE IF NOT EXISTS audit_log (
    id             VARCHAR(64)   PRIMARY KEY,
    run_id         VARCHAR(64)   NOT NULL,
    repository_id  VARCHAR(200)  NOT NULL,
    principal      VARCHAR(200)  NOT NULL,
    event_type     VARCHAR(32)   NOT NULL,
    detail         VARCHAR(2000) NOT NULL,
    plan_hash      VARCHAR(128),
    occurred_at    TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_audit_log_run ON audit_log (run_id, occurred_at);
CREATE INDEX IF NOT EXISTS idx_audit_log_repository ON audit_log (repository_id, occurred_at);
