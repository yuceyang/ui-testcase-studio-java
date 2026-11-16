-- 发现元素只是会话内的草稿，只有“批量标记为功能点”后才允许入库。
-- 清理旧版本在发现阶段直接写入的未确认元素。
DELETE FROM elements
WHERE confirmed = false
  AND id NOT IN (SELECT element_id FROM node_elements);
