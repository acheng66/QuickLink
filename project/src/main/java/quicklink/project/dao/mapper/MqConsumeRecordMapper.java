/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package quicklink.project.dao.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * MQ 消费记录持久层。数据库唯一键是最终幂等屏障。
 */
public interface MqConsumeRecordMapper {

    /**
     * 首次消费返回 1，重复消息返回 0。
     */
    @Insert("INSERT IGNORE INTO t_mq_consume_record " +
            "(message_id, consume_status, create_time, update_time) " +
            "VALUES (#{messageId}, 0, NOW(), NOW())")
    int insertIgnore(@Param("messageId") String messageId);

    @Update("UPDATE t_mq_consume_record SET consume_status = 1, update_time = NOW() " +
            "WHERE message_id = #{messageId}")
    int markCompleted(@Param("messageId") String messageId);
}
