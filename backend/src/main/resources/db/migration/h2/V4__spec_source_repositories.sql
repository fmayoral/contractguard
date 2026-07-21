CREATE TABLE IF NOT EXISTS spec_source_repositories (
    repository_id         VARCHAR(200)  PRIMARY KEY,
    clone_url              VARCHAR(500) NOT NULL,
    owner                  VARCHAR(200) NOT NULL,
    name                   VARCHAR(200) NOT NULL,
    default_branch         VARCHAR(200) NOT NULL,
    credential_ciphertext  VARCHAR(4000),
    credential_nonce       VARCHAR(200),
    registered_at          TIMESTAMP WITH TIME ZONE NOT NULL
);
