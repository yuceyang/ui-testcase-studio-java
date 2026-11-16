-- 用例详情补齐与 Python 版本一致的字段：请求方式、URL、一级/二级标题、问题标注
ALTER TABLE test_cases ADD COLUMN method varchar(16);
ALTER TABLE test_cases ADD COLUMN url varchar(1000);
ALTER TABLE test_cases ADD COLUMN l1 varchar(255);
ALTER TABLE test_cases ADD COLUMN l2 varchar(255);
ALTER TABLE test_cases ADD COLUMN annotation varchar(2000);
