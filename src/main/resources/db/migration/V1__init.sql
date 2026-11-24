CREATE TABLE questions (
    id                   VARCHAR(36) PRIMARY KEY,
    text                 TEXT NOT NULL,
    resolution_criteria  TEXT,
    window_start         TIMESTAMP NOT NULL,
    deadline             TIMESTAMP NOT NULL,
    grace_hours          INT NOT NULL DEFAULT 48,
    status               VARCHAR(32) NOT NULL,
    status_reason        TEXT,
    search_queries_json  TEXT NOT NULL,
    webhook_url          TEXT,
    last_scan_end        TIMESTAMP,
    created_at           TIMESTAMP NOT NULL,
    updated_at           TIMESTAMP NOT NULL
);

CREATE INDEX idx_questions_status ON questions(status);

CREATE TABLE evidence (
    id              VARCHAR(36) PRIMARY KEY,
    question_id     VARCHAR(36) NOT NULL REFERENCES questions(id) ON DELETE CASCADE,
    url             TEXT NOT NULL,
    normalized_url  TEXT NOT NULL,
    domain          VARCHAR(255) NOT NULL,
    title           TEXT NOT NULL,
    seen_at         TIMESTAMP NOT NULL,
    published_at    TIMESTAMP,
    language        VARCHAR(64),
    source_country  VARCHAR(128),
    stage           VARCHAR(32) NOT NULL,
    fetch_status    VARCHAR(32) NOT NULL,
    headline_score  DOUBLE PRECISION,
    content_sha256  VARCHAR(64),
    excerpt         TEXT,
    simhash         BIGINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMP NOT NULL,
    CONSTRAINT uq_evidence_question_url UNIQUE (question_id, normalized_url)
);

CREATE INDEX idx_evidence_question_stage ON evidence(question_id, stage);

CREATE TABLE assessments (
    id                VARCHAR(36) PRIMARY KEY,
    evidence_id       VARCHAR(36) NOT NULL UNIQUE REFERENCES evidence(id) ON DELETE CASCADE,
    question_id       VARCHAR(36) NOT NULL REFERENCES questions(id) ON DELETE CASCADE,
    model             VARCHAR(64) NOT NULL,
    p_relevant        DOUBLE PRECISION NOT NULL,
    p_stance_fits     DOUBLE PRECISION NOT NULL,
    p_confirms        DOUBLE PRECISION NOT NULL,
    p_planned         DOUBLE PRECISION NOT NULL,
    p_speculation     DOUBLE PRECISION NOT NULL,
    p_denies          DOUBLE PRECISION NOT NULL,
    stance_unstable   BOOLEAN NOT NULL,
    stance_confidence DOUBLE PRECISION NOT NULL,
    p_fresh           DOUBLE PRECISION NOT NULL,
    attribution       VARCHAR(64) NOT NULL,
    injection_flag    BOOLEAN NOT NULL,
    input_tokens      INT NOT NULL,
    request_sha256    VARCHAR(64) NOT NULL,
    response_json     TEXT NOT NULL,
    created_at        TIMESTAMP NOT NULL
);

CREATE INDEX idx_assessments_question ON assessments(question_id);

CREATE TABLE resolutions (
    question_id                   VARCHAR(36) PRIMARY KEY REFERENCES questions(id) ON DELETE CASCADE,
    outcome                       VARCHAR(32) NOT NULL,
    confidence                    DOUBLE PRECISION NOT NULL,
    confirm_sum                   DOUBLE PRECISION NOT NULL,
    deny_sum                      DOUBLE PRECISION NOT NULL,
    independent_clusters          INT NOT NULL,
    supporting_evidence_ids_json  TEXT NOT NULL,
    policy_version                INT NOT NULL,
    model                         VARCHAR(64) NOT NULL,
    details                       TEXT,
    resolved_at                   TIMESTAMP NOT NULL
);

CREATE TABLE scan_logs (
    id              VARCHAR(36) PRIMARY KEY,
    question_id     VARCHAR(36) NOT NULL REFERENCES questions(id) ON DELETE CASCADE,
    started_at      TIMESTAMP NOT NULL,
    finished_at     TIMESTAMP NOT NULL,
    gdelt_ok        BOOLEAN NOT NULL,
    articles_found  INT NOT NULL,
    new_evidence    INT NOT NULL,
    error           TEXT
);

CREATE INDEX idx_scan_logs_question ON scan_logs(question_id);
