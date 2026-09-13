-- V53: 重构用户组：创建 Admin 和 User 两个系统用户组，替换旧的"默认用户组"

-- 1. 创建 Admin 用户组（全权限）
INSERT INTO permission_group(group_name, description, system_group, created_by)
VALUES ('Admin', '系统管理员用户组，拥有全部权限', 1, NULL)
ON DUPLICATE KEY UPDATE description = VALUES(description), system_group = 1;

-- 2. 创建 User 用户组（标准权限）
INSERT INTO permission_group(group_name, description, system_group, created_by)
VALUES ('User', '普通用户组，拥有云盘和知识库基本权限', 1, NULL)
ON DUPLICATE KEY UPDATE description = VALUES(description), system_group = 1;

-- 3. 为 Admin 组分配全部权限
INSERT INTO permission_group_grant(group_id, permission_key, allowed)
SELECT g.group_id, p.permission_key, 1
FROM permission_group g
CROSS JOIN (
    SELECT 'CLOUD_DRIVE' AS permission_key UNION ALL
    SELECT 'FILE_UPLOAD' UNION ALL
    SELECT 'FILE_DOWNLOAD' UNION ALL
    SELECT 'KNOWLEDGE_USE' UNION ALL
    SELECT 'KNOWLEDGE_FILE_ADD'
) p
WHERE g.group_name = 'Admin'
ON DUPLICATE KEY UPDATE allowed = VALUES(allowed);

-- 4. 为 User 组分配标准权限（与原"默认用户组"相同）
INSERT INTO permission_group_grant(group_id, permission_key, allowed)
SELECT g.group_id, p.permission_key, 1
FROM permission_group g
CROSS JOIN (
    SELECT 'CLOUD_DRIVE' AS permission_key UNION ALL
    SELECT 'FILE_UPLOAD' UNION ALL
    SELECT 'FILE_DOWNLOAD' UNION ALL
    SELECT 'KNOWLEDGE_USE' UNION ALL
    SELECT 'KNOWLEDGE_FILE_ADD'
) p
WHERE g.group_name = 'User'
ON DUPLICATE KEY UPDATE allowed = VALUES(allowed);

-- 5. 将现有 ADMIN 角色用户迁移到 Admin 组
INSERT INTO user_permission_group(user_id, group_id)
SELECT u.user_id, g.group_id
FROM users u
JOIN permission_group g ON g.group_name = 'Admin'
WHERE u.role = 'ADMIN'
ON DUPLICATE KEY UPDATE group_id = VALUES(group_id);

-- 6. 将现有 USER 角色用户迁移到 User 组（覆盖旧的"默认用户组"分配）
INSERT INTO user_permission_group(user_id, group_id)
SELECT u.user_id, g.group_id
FROM users u
JOIN permission_group g ON g.group_name = 'User'
WHERE u.role = 'USER'
ON DUPLICATE KEY UPDATE group_id = VALUES(group_id);

-- 7. 删除旧的"默认用户组"权限配置
DELETE FROM permission_group_grant
WHERE group_id IN (SELECT group_id FROM permission_group WHERE group_name = '默认用户组');

-- 8. 删除旧的"默认用户组"（如果有用户仍关联此组，先迁移到 User）
UPDATE user_permission_group upg
JOIN permission_group old_g ON old_g.group_id = upg.group_id AND old_g.group_name = '默认用户组'
JOIN permission_group new_g ON new_g.group_name = 'User'
SET upg.group_id = new_g.group_id, upg.update_time = NOW()
WHERE old_g.group_name = '默认用户组';

-- 9. 保留原有配额数值，将其引用迁移到 User 组。
-- 若目标组已配置独立配额，不覆盖任一配置：保留旧组供管理员后续处理。
UPDATE quota_policy old_q
JOIN permission_group old_g ON old_g.group_id = old_q.group_id AND old_g.group_name = '默认用户组'
JOIN permission_group new_g ON new_g.group_name = 'User'
LEFT JOIN quota_policy new_q ON new_q.group_id = new_g.group_id
SET old_q.group_id = new_g.group_id
WHERE new_q.group_id IS NULL;

DELETE FROM permission_group
WHERE group_name = '默认用户组'
  AND NOT EXISTS (SELECT 1 FROM quota_policy q WHERE q.group_id = permission_group.group_id);
