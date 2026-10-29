-- Phase 7: supplier failover, the Spring Batch job repository and settlement reconciliation.

-- Suppliers that already answered a definitive failure for this transaction (failover never
-- goes back to them). The current one is transactions.supplier_id.
ALTER TABLE transactions ADD COLUMN attempted_supplier_ids BIGINT[] NOT NULL DEFAULT '{}';

-- Reconciliation looks up successful transactions of one supplier by completion time.
CREATE INDEX idx_transactions_supplier_success ON transactions (supplier_id, updated_at) WHERE status = 'SUCCESS';

-- ---------------------------------------------------------------------------------------------
-- Spring Batch 6.0 job repository: copied unchanged from spring-batch-core's schema-postgresql.sql
-- (Spring Boot's own schema initialisation is disabled: every schema change goes through Flyway).
-- ---------------------------------------------------------------------------------------------

CREATE TABLE BATCH_JOB_INSTANCE (
	JOB_INSTANCE_ID BIGINT  NOT NULL PRIMARY KEY,
	VERSION BIGINT,
	JOB_NAME VARCHAR(100) NOT NULL,
	JOB_KEY VARCHAR(32) NOT NULL,
	constraint JOB_INST_UN unique (JOB_NAME, JOB_KEY)
) ;

CREATE TABLE BATCH_JOB_EXECUTION (
	JOB_EXECUTION_ID BIGINT  NOT NULL PRIMARY KEY,
	VERSION BIGINT,
	JOB_INSTANCE_ID BIGINT NOT NULL,
	CREATE_TIME TIMESTAMP NOT NULL,
	START_TIME TIMESTAMP DEFAULT NULL,
	END_TIME TIMESTAMP DEFAULT NULL,
	STATUS VARCHAR(10),
	EXIT_CODE VARCHAR(2500),
	EXIT_MESSAGE VARCHAR(2500),
	LAST_UPDATED TIMESTAMP,
	constraint JOB_INST_EXEC_FK foreign key (JOB_INSTANCE_ID)
	references BATCH_JOB_INSTANCE(JOB_INSTANCE_ID)
) ;

CREATE TABLE BATCH_JOB_EXECUTION_PARAMS (
	JOB_EXECUTION_ID BIGINT NOT NULL,
	PARAMETER_NAME VARCHAR(100) NOT NULL,
	PARAMETER_TYPE VARCHAR(100) NOT NULL,
	PARAMETER_VALUE VARCHAR(2500),
	IDENTIFYING CHAR(1) NOT NULL,
	constraint JOB_EXEC_PARAMS_FK foreign key (JOB_EXECUTION_ID)
	references BATCH_JOB_EXECUTION(JOB_EXECUTION_ID)
) ;

CREATE TABLE BATCH_STEP_EXECUTION (
	STEP_EXECUTION_ID BIGINT  NOT NULL PRIMARY KEY,
	VERSION BIGINT NOT NULL,
	STEP_NAME VARCHAR(100) NOT NULL,
	JOB_EXECUTION_ID BIGINT NOT NULL,
	CREATE_TIME TIMESTAMP NOT NULL,
	START_TIME TIMESTAMP DEFAULT NULL,
	END_TIME TIMESTAMP DEFAULT NULL,
	STATUS VARCHAR(10),
	COMMIT_COUNT BIGINT,
	READ_COUNT BIGINT,
	FILTER_COUNT BIGINT,
	WRITE_COUNT BIGINT,
	READ_SKIP_COUNT BIGINT,
	WRITE_SKIP_COUNT BIGINT,
	PROCESS_SKIP_COUNT BIGINT,
	ROLLBACK_COUNT BIGINT,
	EXIT_CODE VARCHAR(2500),
	EXIT_MESSAGE VARCHAR(2500),
	LAST_UPDATED TIMESTAMP,
	constraint JOB_EXEC_STEP_FK foreign key (JOB_EXECUTION_ID)
	references BATCH_JOB_EXECUTION(JOB_EXECUTION_ID)
) ;

CREATE TABLE BATCH_STEP_EXECUTION_CONTEXT (
	STEP_EXECUTION_ID BIGINT NOT NULL PRIMARY KEY,
	SHORT_CONTEXT VARCHAR(2500) NOT NULL,
	SERIALIZED_CONTEXT TEXT,
	constraint STEP_EXEC_CTX_FK foreign key (STEP_EXECUTION_ID)
	references BATCH_STEP_EXECUTION(STEP_EXECUTION_ID)
) ;

CREATE TABLE BATCH_JOB_EXECUTION_CONTEXT (
	JOB_EXECUTION_ID BIGINT NOT NULL PRIMARY KEY,
	SHORT_CONTEXT VARCHAR(2500) NOT NULL,
	SERIALIZED_CONTEXT TEXT,
	constraint JOB_EXEC_CTX_FK foreign key (JOB_EXECUTION_ID)
	references BATCH_JOB_EXECUTION(JOB_EXECUTION_ID)
) ;

CREATE SEQUENCE BATCH_STEP_EXECUTION_SEQ MAXVALUE 9223372036854775807 NO CYCLE;
CREATE SEQUENCE BATCH_JOB_EXECUTION_SEQ MAXVALUE 9223372036854775807 NO CYCLE;
CREATE SEQUENCE BATCH_JOB_INSTANCE_SEQ MAXVALUE 9223372036854775807 NO CYCLE;

-- ---------------------------------------------------------------------------------------------
-- Settlement reconciliation
-- ---------------------------------------------------------------------------------------------

-- One imported settlement file. The checksum makes re-importing the same content a no-op.
CREATE TABLE settlement_files (
    id             BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    supplier_id    BIGINT       NOT NULL REFERENCES suppliers (id),
    business_date  DATE         NOT NULL,
    file_name      VARCHAR(255) NOT NULL,
    checksum       CHAR(64)     NOT NULL UNIQUE,
    content        TEXT         NOT NULL,
    status         VARCHAR(20)  NOT NULL DEFAULT 'IMPORTING'
                   CHECK (status IN ('IMPORTING', 'COMPLETED')),
    line_count     INT,
    imported_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at   TIMESTAMPTZ
);

CREATE TABLE settlement_lines (
    settlement_file_id  BIGINT       NOT NULL REFERENCES settlement_files (id) ON DELETE CASCADE,
    line_no             INT          NOT NULL,
    reference           VARCHAR(64)  NOT NULL,
    -- The reference parsed as our transaction id; NULL when it is not one.
    transaction_id      UUID,
    supplier_ref        VARCHAR(64),
    sku                 VARCHAR(60),
    destination         VARCHAR(20),
    amount              BIGINT       NOT NULL CHECK (amount > 0),
    currency            CHAR(3)      NOT NULL,
    completed_at        TIMESTAMPTZ,
    PRIMARY KEY (settlement_file_id, line_no)
);

CREATE INDEX idx_settlement_lines_transaction ON settlement_lines (settlement_file_id, transaction_id);

CREATE TABLE recon_results (
    id                  BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    settlement_file_id  BIGINT       NOT NULL REFERENCES settlement_files (id) ON DELETE CASCADE,
    match_status        VARCHAR(20)  NOT NULL
                        CHECK (match_status IN ('MATCHED', 'MISSING_INTERNAL', 'MISSING_SUPPLIER', 'AMOUNT_DIFF', 'DUPLICATE')),
    transaction_id      UUID         REFERENCES transactions (id),
    line_no             INT,
    reference           VARCHAR(64),
    supplier_ref        VARCHAR(64),
    internal_status     VARCHAR(20),
    internal_amount     BIGINT,
    supplier_amount     BIGINT,
    -- supplier_amount - internal_amount; positive means the supplier billed more than we booked.
    diff_amount         BIGINT,
    currency            CHAR(3),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_recon_results_file ON recon_results (settlement_file_id, match_status);
