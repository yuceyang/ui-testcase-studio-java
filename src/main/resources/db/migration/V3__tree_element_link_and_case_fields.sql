-- 功能点节点与交互元素的精确关联（取消标记时按 element_id 回收节点）
ALTER TABLE tree_nodes ADD COLUMN element_id varchar(36);
CREATE INDEX idx_tree_element ON tree_nodes(element_id) WHERE deleted_at IS NULL;

-- 用例字段对齐原 Python 工具（编号 / 模块 / 优先级 / 分类 / 设计需求 / 测试跟踪 / 备注 / 截图）
ALTER TABLE test_cases ADD COLUMN case_no varchar(32);
ALTER TABLE test_cases ADD COLUMN module_name varchar(255);
ALTER TABLE test_cases ADD COLUMN priority varchar(8);
ALTER TABLE test_cases ADD COLUMN category varchar(20);
ALTER TABLE test_cases ADD COLUMN design_requirement varchar(4000);
ALTER TABLE test_cases ADD COLUMN test_track varchar(1000);
ALTER TABLE test_cases ADD COLUMN remark varchar(2000);
ALTER TABLE test_cases ADD COLUMN params_text varchar(2000);
ALTER TABLE test_cases ADD COLUMN screenshot_id varchar(36);
CREATE INDEX idx_case_no ON test_cases(project_id, case_no) WHERE deleted_at IS NULL;
CREATE INDEX idx_case_screenshot ON test_cases(screenshot_id) WHERE deleted_at IS NULL;
