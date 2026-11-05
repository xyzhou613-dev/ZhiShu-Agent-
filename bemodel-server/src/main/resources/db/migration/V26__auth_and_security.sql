-- =============================================================
-- V26: 认证授权 —— 平台用户表（BCrypt 口令哈希，角色 ADMIN/EDITOR/VIEWER）
--      种子三账号：admin/admin123、modeler/model123、viewer/viewer123（仅演示环境）
-- =============================================================

CREATE TABLE IF NOT EXISTS bm_user (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    username      VARCHAR(64)  NOT NULL UNIQUE COMMENT '登录名',
    password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt 口令哈希',
    display_name  VARCHAR(64)  COMMENT '显示名',
    role          VARCHAR(16)  NOT NULL DEFAULT 'VIEWER' COMMENT 'ADMIN/EDITOR/VIEWER',
    created_at    DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='平台用户';

INSERT INTO bm_user (username, password_hash, display_name, role) VALUES
('admin',   '$2b$10$p8qSig4jDf3F2RbVnx34Q.sKckZ2X0hn5w8JF4Cl.tWmICPkhMBOq', '平台管理员', 'ADMIN'),
('modeler', '$2b$10$z.cFB9hTxxLFQTk9ZpVTceEuby2rZjqxDHeLfDoVc8LiEJWBLVngu', '建模工程师', 'EDITOR'),
('viewer',  '$2b$10$Oh/TaO3YMIdHjBhoruu89elxoM7wGJ3ffQJNcsKliH0Kslq3xTMgq', '只读访客', 'VIEWER');
