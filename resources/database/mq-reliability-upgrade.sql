-- 已初始化过 link.sql 的数据库，请单独执行本文件。
-- 新安装可直接执行最新版 link.sql，无需重复执行本文件。

CREATE TABLE IF NOT EXISTS `t_mq_consume_record`
(
    `id`             bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `message_id`     varchar(64) NOT NULL COMMENT '消息唯一标识',
    `consume_status` tinyint(1) NOT NULL DEFAULT '0' COMMENT '消费状态 0：处理中 1：已完成',
    `create_time`    datetime NOT NULL COMMENT '创建时间',
    `update_time`    datetime NOT NULL COMMENT '修改时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_message_id` (`message_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MQ 消费幂等记录';
