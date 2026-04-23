-- 在 SQL Server 中新建 test_login 庫，並建表
-- 執行順序：先執行此腳本，再啟動後端（DataInitializer 會自動插入用戶）

-- 1. 建庫
CREATE DATABASE test_login;
GO

-- 2. 切換到 test_login 庫
USE test_login;
GO

-- 3. 建表
CREATE TABLE sys_user (
    id       BIGINT IDENTITY(1,1) PRIMARY KEY,
    username NVARCHAR(50)  NOT NULL UNIQUE,
    password NVARCHAR(255) NOT NULL,
    enabled  BIT           NOT NULL DEFAULT 1
);
GO

-- 說明：
-- 用戶數據由後端啟動時 DataInitializer 自動寫入，無需手動插入
-- 初始用戶：
--   admin / 123456
--   user1 / 123456
--   user2 / 888888
