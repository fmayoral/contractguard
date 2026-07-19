CREATE TABLE IF NOT EXISTS runs (
    id              VARCHAR(64)  PRIMARY KEY,
    name            VARCHAR(200) NOT NULL,
    state           VARCHAR(32)  NOT NULL,
    repository_id   VARCHAR(200) NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    payload_version INT          NOT NULL,
    payload         TEXT         NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_runs_repository_state ON runs (repository_id, state);

CREATE TABLE IF NOT EXISTS run_events (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id      VARCHAR(64)  NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    step        VARCHAR(64)  NOT NULL,
    status      VARCHAR(32)  NOT NULL,
    message     VARCHAR(2000) NOT NULL,
    metadata    TEXT
);

CREATE INDEX IF NOT EXISTS idx_run_events_run ON run_events (run_id, id);
