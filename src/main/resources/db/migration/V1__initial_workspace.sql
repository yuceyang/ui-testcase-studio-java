CREATE TABLE projects (
    id varchar(36) PRIMARY KEY,
    revision bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    deleted_at timestamptz,
    name varchar(255) NOT NULL,
    description varchar(2000),
    archived boolean NOT NULL DEFAULT false
);

CREATE TABLE systems (
    id varchar(36) PRIMARY KEY,
    revision bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    deleted_at timestamptz,
    project_id varchar(36) NOT NULL REFERENCES projects(id) ON DELETE RESTRICT,
    name varchar(255) NOT NULL,
    code varchar(255) NOT NULL,
    description varchar(1000)
);
CREATE INDEX idx_system_project ON systems(project_id) WHERE deleted_at IS NULL;

CREATE TABLE system_environments (
    id varchar(36) PRIMARY KEY,
    revision bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    deleted_at timestamptz,
    system_id varchar(36) NOT NULL REFERENCES systems(id) ON DELETE RESTRICT,
    name varchar(255) NOT NULL,
    base_url varchar(2000) NOT NULL,
    default_environment boolean NOT NULL DEFAULT false
);
CREATE INDEX idx_environment_system ON system_environments(system_id) WHERE deleted_at IS NULL;

CREATE TABLE tree_nodes (
    id varchar(36) PRIMARY KEY,
    revision bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    deleted_at timestamptz,
    project_id varchar(36) NOT NULL REFERENCES projects(id) ON DELETE RESTRICT,
    system_id varchar(36) NOT NULL REFERENCES systems(id) ON DELETE RESTRICT,
    parent_id varchar(36) REFERENCES tree_nodes(id) ON DELETE RESTRICT,
    node_type varchar(20) NOT NULL CHECK (node_type IN ('MODULE','TITLE1','TITLE2','TITLE3','POINT')),
    name varchar(255) NOT NULL,
    description varchar(2000),
    sort_order integer NOT NULL DEFAULT 0,
    node_path varchar(4000)
);
CREATE INDEX idx_tree_project ON tree_nodes(project_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_tree_parent ON tree_nodes(parent_id) WHERE deleted_at IS NULL;

CREATE TABLE elements (
    id varchar(36) PRIMARY KEY,
    revision bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    deleted_at timestamptz,
    project_id varchar(36) NOT NULL REFERENCES projects(id) ON DELETE RESTRICT,
    system_id varchar(36) NOT NULL REFERENCES systems(id) ON DELETE RESTRICT,
    session_id varchar(255) NOT NULL,
    discovery_run_id varchar(255) NOT NULL,
    fingerprint varchar(255) NOT NULL,
    name varchar(255) NOT NULL,
    kind varchar(255) NOT NULL,
    component varchar(255),
    selector varchar(8000),
    semantic_path varchar(4000),
    frame_path varchar(255) NOT NULL DEFAULT 'main',
    identity_strength varchar(255) NOT NULL,
    exclusion_reason varchar(255),
    confirmed boolean NOT NULL DEFAULT false,
    archived boolean NOT NULL DEFAULT false
);
CREATE INDEX idx_element_project ON elements(project_id) WHERE deleted_at IS NULL;

CREATE TABLE test_cases (
    id varchar(36) PRIMARY KEY,
    revision bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    deleted_at timestamptz,
    project_id varchar(36) NOT NULL REFERENCES projects(id) ON DELETE RESTRICT,
    system_id varchar(36) NOT NULL REFERENCES systems(id) ON DELETE RESTRICT,
    point_id varchar(36) REFERENCES tree_nodes(id) ON DELETE SET NULL,
    title varchar(255) NOT NULL,
    preconditions varchar(4000),
    steps varchar(10000),
    expected_result varchar(10000),
    actual_result varchar(10000),
    status varchar(20) NOT NULL DEFAULT 'DRAFT'
);
CREATE INDEX idx_case_project ON test_cases(project_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_case_point ON test_cases(point_id) WHERE deleted_at IS NULL;

CREATE TABLE screenshots (
    id varchar(36) PRIMARY KEY,
    revision bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    deleted_at timestamptz,
    project_id varchar(36) NOT NULL REFERENCES projects(id) ON DELETE RESTRICT,
    system_id varchar(36) NOT NULL REFERENCES systems(id) ON DELETE RESTRICT,
    environment_id varchar(36) REFERENCES system_environments(id) ON DELETE RESTRICT,
    session_id varchar(255) NOT NULL,
    page_revision varchar(255) NOT NULL,
    sha256 varchar(64) NOT NULL,
    relative_path varchar(255) NOT NULL,
    width integer NOT NULL,
    height integer NOT NULL,
    status varchar(20) NOT NULL CHECK (status IN ('REQUESTED','CAPTURING','VALIDATING','READY','BOUND','FAILED','STALE','DUPLICATE')),
    error_code varchar(255),
    quarantined boolean NOT NULL DEFAULT false,
    UNIQUE(project_id, sha256)
);
CREATE INDEX idx_screenshot_project ON screenshots(project_id) WHERE deleted_at IS NULL;

CREATE TABLE case_screenshots (
    id varchar(36) PRIMARY KEY,
    test_case_id varchar(36) NOT NULL REFERENCES test_cases(id) ON DELETE RESTRICT,
    screenshot_id varchar(36) NOT NULL REFERENCES screenshots(id) ON DELETE RESTRICT,
    sort_order integer NOT NULL DEFAULT 0,
    caption varchar(1000),
    UNIQUE(test_case_id, screenshot_id)
);

CREATE TABLE workspace_sessions (
    id varchar(36) PRIMARY KEY,
    project_id varchar(36) NOT NULL REFERENCES projects(id) ON DELETE RESTRICT,
    system_id varchar(36) NOT NULL REFERENCES systems(id) ON DELETE RESTRICT,
    environment_id varchar(36) REFERENCES system_environments(id) ON DELETE RESTRICT,
    owner_key varchar(255) NOT NULL,
    page_revision varchar(255) NOT NULL,
    last_accessed_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL
);

CREATE TABLE discovery_runs (
    id varchar(36) PRIMARY KEY,
    session_id varchar(36) NOT NULL REFERENCES workspace_sessions(id) ON DELETE RESTRICT,
    page_revision varchar(255) NOT NULL,
    status varchar(20) NOT NULL,
    created_at timestamptz NOT NULL,
    completed_at timestamptz
);

CREATE TABLE node_elements (
    node_id varchar(36) NOT NULL REFERENCES tree_nodes(id) ON DELETE RESTRICT,
    element_id varchar(36) NOT NULL REFERENCES elements(id) ON DELETE RESTRICT,
    PRIMARY KEY(node_id, element_id)
);

CREATE TABLE test_case_versions (
    id varchar(36) PRIMARY KEY,
    test_case_id varchar(36) NOT NULL REFERENCES test_cases(id) ON DELETE RESTRICT,
    revision bigint NOT NULL,
    content jsonb NOT NULL,
    changed_by varchar(255),
    changed_at timestamptz NOT NULL
);

CREATE TABLE deletion_records (
    id varchar(36) PRIMARY KEY,
    project_id varchar(36) NOT NULL REFERENCES projects(id) ON DELETE RESTRICT,
    target_type varchar(255) NOT NULL,
    target_id varchar(36) NOT NULL,
    snapshot jsonb NOT NULL,
    deleted_by varchar(255),
    deleted_at timestamptz NOT NULL,
    restored_at timestamptz
);

CREATE TABLE operation_logs (
    id varchar(36) PRIMARY KEY,
    occurred_at timestamptz NOT NULL,
    actor varchar(255),
    operation varchar(255) NOT NULL,
    project_id varchar(36) REFERENCES projects(id) ON DELETE RESTRICT,
    target_type varchar(255),
    target_id varchar(255),
    detail varchar(8000)
);
CREATE INDEX idx_operation_project ON operation_logs(project_id, occurred_at DESC);

-- 父节点和子节点必须同项目、同系统；跨系统只能通过显式复制迁移实现。
CREATE OR REPLACE FUNCTION validate_tree_node_parent() RETURNS trigger AS $$
DECLARE parent_project varchar(36);
DECLARE parent_system varchar(36);
BEGIN
    IF NEW.parent_id IS NOT NULL THEN
        SELECT project_id, system_id INTO parent_project, parent_system
            FROM tree_nodes WHERE id = NEW.parent_id AND deleted_at IS NULL;
        IF NOT FOUND OR parent_project <> NEW.project_id OR parent_system <> NEW.system_id THEN
            RAISE EXCEPTION 'tree node parent must belong to same active project and system';
        END IF;
        IF NEW.parent_id = NEW.id THEN
            RAISE EXCEPTION 'tree node cannot be its own parent';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_tree_parent_integrity
AFTER INSERT OR UPDATE OF parent_id, project_id, system_id, deleted_at ON tree_nodes
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION validate_tree_node_parent();
