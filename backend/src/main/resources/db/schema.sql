-- =====================================================================
--  知识管理系统 (Knowledge Management System, KMS)
--  专业课程设计II —— 题目1
--  数据库：MySQL 8.0 / 字符集 utf8mb4 / 全文检索使用 ngram 解析器
--  说明：脚本可重复执行（DROP IF EXISTS），用于初始化开发与演示环境。
-- =====================================================================

CREATE DATABASE IF NOT EXISTS `kms`
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_0900_ai_ci;

USE `kms`;

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ---------------------------------------------------------------------
-- 1. 用户与权限（RBAC）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `sys_user_role`;
DROP TABLE IF EXISTS `sys_role`;
DROP TABLE IF EXISTS `sys_user`;

CREATE TABLE `sys_user` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `username`      VARCHAR(64)  NOT NULL COMMENT '登录名',
    `password`      VARCHAR(128) NOT NULL COMMENT 'BCrypt 口令散列',
    `real_name`     VARCHAR(64)           DEFAULT NULL COMMENT '姓名',
    `email`         VARCHAR(128)          DEFAULT NULL COMMENT '邮箱',
    `avatar`        VARCHAR(255)          DEFAULT NULL COMMENT '头像地址',
    `department`    VARCHAR(128)          DEFAULT NULL COMMENT '所属单位/班级',
    `status`        TINYINT      NOT NULL DEFAULT 1 COMMENT '1=启用 0=停用',
    `last_login_at` DATETIME              DEFAULT NULL COMMENT '最近登录时间',
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_username` (`username`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '系统用户';

CREATE TABLE `sys_role` (
    `id`          BIGINT      NOT NULL AUTO_INCREMENT,
    `code`        VARCHAR(32) NOT NULL COMMENT '角色编码：ADMIN/EDITOR/VIEWER',
    `name`        VARCHAR(64) NOT NULL COMMENT '角色名称',
    `description` VARCHAR(255)         DEFAULT NULL,
    `sort_order`  INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_role_code` (`code`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '角色';

CREATE TABLE `sys_user_role` (
    `user_id` BIGINT NOT NULL,
    `role_id` BIGINT NOT NULL,
    PRIMARY KEY (`user_id`, `role_id`),
    KEY `idx_ur_role` (`role_id`),
    CONSTRAINT `fk_ur_user` FOREIGN KEY (`user_id`) REFERENCES `sys_user` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_ur_role` FOREIGN KEY (`role_id`) REFERENCES `sys_role` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户-角色关联';

-- ---------------------------------------------------------------------
-- 2. 系统配置（版本策略、RAG 参数等运行期可改的配置）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `sys_setting`;

CREATE TABLE `sys_setting` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `cfg_key`     VARCHAR(64)  NOT NULL COMMENT '配置键',
    `cfg_value`   VARCHAR(512) NOT NULL COMMENT '配置值',
    `cfg_group`   VARCHAR(32)  NOT NULL DEFAULT 'common' COMMENT '配置分组',
    `description` VARCHAR(255)          DEFAULT NULL,
    `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_setting_key` (`cfg_key`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '系统配置项';

-- ---------------------------------------------------------------------
-- 3. 知识本体（Ontology）—— 用本体对知识的逻辑结构建模
--    要求：≥20 个知识点（概念）、≥5 种关系
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `ont_relation`;
DROP TABLE IF EXISTS `ont_relation_type`;
DROP TABLE IF EXISTS `ont_concept`;

CREATE TABLE `ont_concept` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `code`        VARCHAR(64)  NOT NULL COMMENT '概念唯一编码，如 SE.REQ.ELICITATION',
    `name`        VARCHAR(128) NOT NULL COMMENT '概念名称（中文）',
    `en_name`     VARCHAR(128)          DEFAULT NULL COMMENT '英文名',
    `aliases`     VARCHAR(512)          DEFAULT NULL COMMENT '别名，逗号分隔，用于实体链接',
    `description` VARCHAR(1024)         DEFAULT NULL COMMENT '概念释义',
    `parent_id`   BIGINT                DEFAULT NULL COMMENT '父概念（层次结构的父节点）',
    `level`       TINYINT      NOT NULL DEFAULT 1 COMMENT '层次深度，根为1',
    `path`        VARCHAR(512)          DEFAULT NULL COMMENT '物化路径，如 /1/5/12/',
    `domain`      VARCHAR(64)           DEFAULT NULL COMMENT '所属知识域',
    `difficulty`  TINYINT      NOT NULL DEFAULT 3 COMMENT '认知难度 1~5',
    `importance`  TINYINT      NOT NULL DEFAULT 3 COMMENT '重要程度 1~5',
    `color`       VARCHAR(16)           DEFAULT NULL COMMENT '图谱配色',
    `builtin`     TINYINT      NOT NULL DEFAULT 0 COMMENT '1=系统内置，不可删除',
    `sort_order`  INT          NOT NULL DEFAULT 0,
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_concept_code` (`code`),
    KEY `idx_concept_parent` (`parent_id`),
    KEY `idx_concept_domain` (`domain`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '本体概念（知识点）';

CREATE TABLE `ont_relation_type` (
    `id`           BIGINT      NOT NULL AUTO_INCREMENT,
    `code`         VARCHAR(32) NOT NULL COMMENT '关系编码',
    `name`         VARCHAR(64) NOT NULL COMMENT '关系名称',
    `inverse_code` VARCHAR(32)          DEFAULT NULL COMMENT '逆关系编码，对称关系与自身相同',
    `directed`     TINYINT     NOT NULL DEFAULT 1 COMMENT '1=有向 0=无向（对称）',
    `color`        VARCHAR(16)          DEFAULT NULL,
    `description`  VARCHAR(255)         DEFAULT NULL,
    `sort_order`   INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_relation_type_code` (`code`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '本体关系类型';

CREATE TABLE `ont_relation` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT,
    `source_id`        BIGINT       NOT NULL COMMENT '源概念',
    `target_id`        BIGINT       NOT NULL COMMENT '目标概念',
    `relation_type`    VARCHAR(32)  NOT NULL COMMENT '关系类型编码，引用 ont_relation_type.code',
    `weight`           DECIMAL(4, 2) NOT NULL DEFAULT 1.00 COMMENT '关系强度 0~1',
    `description`      VARCHAR(512)          DEFAULT NULL COMMENT '关系说明（三元组断言的自然语言表述）',
    `builtin`          TINYINT      NOT NULL DEFAULT 0,
    `created_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_relation_triple` (`source_id`, `target_id`, `relation_type`),
    KEY `idx_relation_source` (`source_id`),
    KEY `idx_relation_target` (`target_id`),
    KEY `idx_relation_type` (`relation_type`),
    CONSTRAINT `fk_rel_source` FOREIGN KEY (`source_id`) REFERENCES `ont_concept` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_rel_target` FOREIGN KEY (`target_id`) REFERENCES `ont_concept` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '本体关系（三元组）';

-- ---------------------------------------------------------------------
-- 4. 知识库：分类、标签、知识条目、附件
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `kb_item_concept`;
DROP TABLE IF EXISTS `kb_item_tag`;
DROP TABLE IF EXISTS `kb_attachment`;
DROP TABLE IF EXISTS `kb_item`;
DROP TABLE IF EXISTS `kb_tag`;
DROP TABLE IF EXISTS `kb_category`;

CREATE TABLE `kb_category` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `name`        VARCHAR(64)  NOT NULL COMMENT '分类名称',
    `code`        VARCHAR(64)  NOT NULL COMMENT '分类编码',
    `parent_id`   BIGINT                DEFAULT NULL,
    `level`       TINYINT      NOT NULL DEFAULT 1,
    `path`        VARCHAR(512)          DEFAULT NULL COMMENT '物化路径 /1/4/',
    `icon`        VARCHAR(64)           DEFAULT NULL,
    `description` VARCHAR(512)          DEFAULT NULL,
    `sort_order`  INT          NOT NULL DEFAULT 0,
    `item_count`  INT          NOT NULL DEFAULT 0 COMMENT '冗余计数，列表展示用',
    `builtin`     TINYINT      NOT NULL DEFAULT 0,
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_category_code` (`code`),
    KEY `idx_category_parent` (`parent_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '知识分类（层次结构）';

CREATE TABLE `kb_tag` (
    `id`         BIGINT      NOT NULL AUTO_INCREMENT,
    `name`       VARCHAR(64) NOT NULL,
    `color`      VARCHAR(16)          DEFAULT NULL,
    `use_count`  INT         NOT NULL DEFAULT 0,
    `created_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tag_name` (`name`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '标签';

CREATE TABLE `kb_item` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT,
    `title`            VARCHAR(255) NOT NULL COMMENT '标题',
    `summary`          VARCHAR(1024)         DEFAULT NULL COMMENT '摘要', --写给前端
    `content`          LONGTEXT              DEFAULT NULL COMMENT '正文（Markdown）', --写给后端
    `plain_text`       LONGTEXT              DEFAULT NULL COMMENT '去标记后的纯文本，供检索/RAG使用',
    `content_format`   VARCHAR(16)  NOT NULL DEFAULT 'MARKDOWN' COMMENT 'MARKDOWN/TEXT',
    `asset_type`       VARCHAR(32)  NOT NULL DEFAULT 'DOCUMENT'
        COMMENT '知识资产类型：DOCUMENT文档 REPORT技术报告 PAPER论文 VIDEO视频 IMAGE图片 CODE代码 MINDMAP思维导图 OTHER',
    `category_id`      BIGINT                DEFAULT NULL COMMENT '所属分类',
    `author_id`        BIGINT                DEFAULT NULL COMMENT '创建者',
    `status`           VARCHAR(16)  NOT NULL DEFAULT 'DRAFT'
        COMMENT '生命周期：DRAFT草稿 PENDING待审 PUBLISHED已发布 ARCHIVED已归档',
    `source_url`       VARCHAR(512)          DEFAULT NULL COMMENT '外部来源链接',
    `source_desc`      VARCHAR(255)          DEFAULT NULL COMMENT '来源说明（期刊/课程/机构）',
    `cover_image`      VARCHAR(512)          DEFAULT NULL,
    `keywords`         VARCHAR(512)          DEFAULT NULL COMMENT '抽取出的关键词，逗号分隔',
    `version_no`       INT          NOT NULL DEFAULT 1 COMMENT '当前版本号',
    `view_count`       INT          NOT NULL DEFAULT 0,
    `like_count`       INT          NOT NULL DEFAULT 0,
    `download_count`   INT          NOT NULL DEFAULT 0,
    `change_count`     INT          NOT NULL DEFAULT 0 COMMENT '自上一个快照版本以来的累计修改次数',
    `last_snapshot_at` DATETIME              DEFAULT NULL COMMENT '上一次生成版本快照的时间',
    `published_at`     DATETIME              DEFAULT NULL,
    `archived_at`      DATETIME              DEFAULT NULL,
    `archive_reason`   VARCHAR(255)          DEFAULT NULL,
    `deleted`          TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    `created_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_item_category` (`category_id`),
    KEY `idx_item_author` (`author_id`),
    KEY `idx_item_status` (`status`),
    KEY `idx_item_asset_type` (`asset_type`),
    KEY `idx_item_published` (`published_at`),
    -- 中文全文检索：ngram 二元切分，配合 innodb_ft_min_token_size/ngram_token_size=2
    FULLTEXT KEY `ft_item_ngram` (`title`, `summary`, `plain_text`) WITH PARSER ngram, --sql全文索引默认空格和标点切词，避免如“软件测试”被当成一个词搜索“测试”的时候搜不到
    CONSTRAINT `fk_item_category` FOREIGN KEY (`category_id`) REFERENCES `kb_category` (`id`) ON DELETE SET NULL --分类被删除时条目本身仍有价值，条目被删除时级联删除
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '知识条目（知识资产主表）';

CREATE TABLE `kb_item_tag` (
    `item_id` BIGINT NOT NULL,
    `tag_id`  BIGINT NOT NULL,
    PRIMARY KEY (`item_id`, `tag_id`),
    KEY `idx_it_tag` (`tag_id`),
    CONSTRAINT `fk_it_item` FOREIGN KEY (`item_id`) REFERENCES `kb_item` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_it_tag` FOREIGN KEY (`tag_id`) REFERENCES `kb_tag` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '条目-标签关联';

CREATE TABLE `kb_item_concept` (
    `item_id`    BIGINT      NOT NULL,
    `concept_id` BIGINT      NOT NULL,
    `source`     VARCHAR(16) NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL手工标注 AUTO自动抽取',
    `confidence` DECIMAL(4, 2) NOT NULL DEFAULT 1.00,
    PRIMARY KEY (`item_id`, `concept_id`),
    KEY `idx_ic_concept` (`concept_id`),
    CONSTRAINT `fk_ic_item` FOREIGN KEY (`item_id`) REFERENCES `kb_item` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_ic_concept` FOREIGN KEY (`concept_id`) REFERENCES `ont_concept` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '条目-本体概念标注';

CREATE TABLE `kb_attachment` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT,
    `item_id`        BIGINT                DEFAULT NULL COMMENT '所属条目，可为空（先传后挂）',
    `file_name`      VARCHAR(255) NOT NULL COMMENT '原始文件名',
    `stored_name`    VARCHAR(255) NOT NULL COMMENT '存储文件名（UUID）',
    `file_path`      VARCHAR(512) NOT NULL COMMENT '相对存储根目录的路径',
    `file_size`      BIGINT       NOT NULL DEFAULT 0,
    `mime_type`      VARCHAR(128)          DEFAULT NULL,
    `media_type`     VARCHAR(16)  NOT NULL DEFAULT 'FILE'
        COMMENT 'FILE DOCUMENT IMAGE AUDIO VIDEO',
    `extension`      VARCHAR(16)           DEFAULT NULL,
    `extracted_text` LONGTEXT              DEFAULT NULL COMMENT '抽取出的正文，供全文检索/RAG使用',
    `extract_status` VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SUCCESS/FAILED/UNSUPPORTED',
    `duration_sec`   INT                   DEFAULT NULL COMMENT '音视频时长（秒）',
    `uploader_id`    BIGINT                DEFAULT NULL,
    `created_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_att_item` (`item_id`),
    KEY `idx_att_media` (`media_type`),
    FULLTEXT KEY `ft_att_ngram` (`file_name`, `extracted_text`) WITH PARSER ngram,
    CONSTRAINT `fk_att_item` FOREIGN KEY (`item_id`) REFERENCES `kb_item` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '知识条目附件（文档/图片/音视频）';

-- ---------------------------------------------------------------------
-- 5. CMS 版本管理
--    策略：满足「距上次快照 ≥ N 天」或「累计修改 ≥ M 次」时生成新版本快照
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `cms_item_version`;
DROP TABLE IF EXISTS `cms_version_policy`;

CREATE TABLE `cms_item_version` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT,
    `item_id`          BIGINT       NOT NULL,
    `version_no`       INT          NOT NULL COMMENT '版本序号，从 1 递增',
    `title`            VARCHAR(255) NOT NULL,
    `summary`          VARCHAR(1024)         DEFAULT NULL,
    `content`          LONGTEXT              DEFAULT NULL COMMENT '该版本完整正文快照',
    `category_id`      BIGINT                DEFAULT NULL,
    `status`           VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    `asset_type`       VARCHAR(32)           DEFAULT NULL,
    `concepts_snapshot` VARCHAR(1024)        DEFAULT NULL COMMENT '概念标注快照，形如 id:name|id:name',
    `tags_snapshot`    VARCHAR(1024)          DEFAULT NULL COMMENT '标签快照',
    `change_log`       VARCHAR(1024)         DEFAULT NULL COMMENT '版本说明',
    `change_source`    VARCHAR(16)  NOT NULL DEFAULT 'AUTO'
        COMMENT 'AUTO自动快照 MANUAL手工创建 ROLLBACK回滚生成 PUBLISH发布',
    `char_added`       INT          NOT NULL DEFAULT 0 COMMENT '相对上一版本新增字符数',
    `char_removed`     INT          NOT NULL DEFAULT 0 COMMENT '相对上一版本删除字符数',
    `content_hash`     CHAR(32)              DEFAULT NULL COMMENT 'MD5，用于判断内容是否变化',
    `editor_id`        BIGINT                DEFAULT NULL,
    `editor_name`      VARCHAR(64)           DEFAULT NULL,
    `created_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_version_item_no` (`item_id`, `version_no`),
    KEY `idx_version_item` (`item_id`),
    CONSTRAINT `fk_version_item` FOREIGN KEY (`item_id`) REFERENCES `kb_item` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '知识条目历史版本快照';

CREATE TABLE `cms_version_policy` (
    `id`                BIGINT      NOT NULL AUTO_INCREMENT,
    `scope`             VARCHAR(16) NOT NULL DEFAULT 'GLOBAL' COMMENT 'GLOBAL全局 CATEGORY分类级 ITEM条目级',
    `scope_id`          BIGINT               DEFAULT NULL COMMENT 'scope 对应的分类/条目 id',
    `policy_name`       VARCHAR(64) NOT NULL DEFAULT '默认策略',
    `interval_days`     INT         NOT NULL DEFAULT 1 COMMENT '天数阈值：距上次快照≥该天数即生成新版本',
    `min_change_count`  INT         NOT NULL DEFAULT 5 COMMENT '修改次数阈值：累计修改≥该次数即生成新版本',
    `max_versions`      INT         NOT NULL DEFAULT 50 COMMENT '保留的最大版本数，超出淘汰最旧版本',
    `auto_snapshot`     TINYINT     NOT NULL DEFAULT 1 COMMENT '是否启用自动快照',
    `enabled`           TINYINT     NOT NULL DEFAULT 1,
    `priority`          INT         NOT NULL DEFAULT 0 COMMENT '数值大者优先',
    `updated_at`        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_policy_scope` (`scope`, `scope_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '版本管理策略（指定天数/修改次数）';

-- ---------------------------------------------------------------------
-- 6. 搜索引擎：检索日志 + RAG 分块/会话
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `search_log`;
DROP TABLE IF EXISTS `rag_message`;
DROP TABLE IF EXISTS `rag_session`;
DROP TABLE IF EXISTS `rag_chunk`;

CREATE TABLE `rag_chunk` (
    `id`          BIGINT      NOT NULL AUTO_INCREMENT,
    `item_id`     BIGINT      NOT NULL COMMENT '来源知识条目',
    `item_title`  VARCHAR(255)         DEFAULT NULL COMMENT '冗余，便于直接构造引用',
    `source_type` VARCHAR(16) NOT NULL DEFAULT 'CONTENT' COMMENT 'CONTENT正文 ATTACHMENT附件',
    `source_id`   BIGINT               DEFAULT NULL COMMENT '附件 id（source_type=ATTACHMENT 时）',
    `chunk_index` INT         NOT NULL DEFAULT 0 COMMENT '块序号',
    `content`     TEXT        NOT NULL COMMENT '块文本',
    `char_count`  INT         NOT NULL DEFAULT 0,
    `embedding`   MEDIUMBLOB           DEFAULT NULL COMMENT '向量（float32 小端序列）',
    `dim`         INT                  DEFAULT NULL COMMENT '向量维度',
    `embed_model` VARCHAR(64)          DEFAULT NULL COMMENT '产生该向量的模型标识',
    `created_at`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_chunk_item` (`item_id`),
    FULLTEXT KEY `ft_chunk_ngram` (`content`) WITH PARSER ngram,
    CONSTRAINT `fk_chunk_item` FOREIGN KEY (`item_id`) REFERENCES `kb_item` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'RAG 检索分块与向量';

CREATE TABLE `rag_session` (
    `id`         BIGINT      NOT NULL AUTO_INCREMENT,
    `user_id`    BIGINT               DEFAULT NULL,
    `title`      VARCHAR(128) NOT NULL DEFAULT '新会话',
    `created_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_session_user` (`user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'RAG 问答会话';

CREATE TABLE `rag_message` (
    `id`            BIGINT      NOT NULL AUTO_INCREMENT,
    `session_id`    BIGINT      NOT NULL,
    `role`          VARCHAR(16) NOT NULL COMMENT 'user / assistant',
    `content`       LONGTEXT    NOT NULL,
    `citations`     TEXT                 DEFAULT NULL COMMENT 'JSON 数组：引用的条目/块',
    `retrieval_meta` TEXT                DEFAULT NULL COMMENT 'JSON：检索过程元数据（命中块、分数、耗时）',
    `model`         VARCHAR(64)          DEFAULT NULL COMMENT '生成该回答的模型',
    `latency_ms`    INT                  DEFAULT NULL,
    `created_at`    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_msg_session` (`session_id`),
    CONSTRAINT `fk_msg_session` FOREIGN KEY (`session_id`) REFERENCES `rag_session` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'RAG 问答消息';

CREATE TABLE `search_log` (
    `id`            BIGINT      NOT NULL AUTO_INCREMENT,
    `user_id`       BIGINT               DEFAULT NULL,
    `keyword`       VARCHAR(255)         DEFAULT NULL,
    `search_type`   VARCHAR(16) NOT NULL DEFAULT 'SIMPLE' COMMENT 'SIMPLE/ADVANCED/RAG',
    `filters`       VARCHAR(1024)         DEFAULT NULL COMMENT 'JSON 形式的检索条件',
    `result_count`  INT         NOT NULL DEFAULT 0,
    `cost_ms`       INT         NOT NULL DEFAULT 0,
    `created_at`    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_search_created` (`created_at`),
    KEY `idx_search_keyword` (`keyword`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '检索行为日志';

-- ---------------------------------------------------------------------
-- 7. 审计日志
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `sys_audit_log`;

CREATE TABLE `sys_audit_log` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `user_id`     BIGINT                DEFAULT NULL,
    `username`    VARCHAR(64)           DEFAULT NULL,
    `action`      VARCHAR(32)  NOT NULL COMMENT 'CREATE/UPDATE/DELETE/PUBLISH/ARCHIVE/ROLLBACK/LOGIN/...',
    `target_type` VARCHAR(32)           DEFAULT NULL COMMENT 'ITEM/CONCEPT/CATEGORY/USER/...',
    `target_id`   BIGINT                DEFAULT NULL,
    `detail`      VARCHAR(1024)         DEFAULT NULL,
    `ip`          VARCHAR(64)           DEFAULT NULL,
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_audit_created` (`created_at`),
    KEY `idx_audit_user` (`user_id`),
    KEY `idx_audit_target` (`target_type`, `target_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '审计日志';

SET FOREIGN_KEY_CHECKS = 1;
