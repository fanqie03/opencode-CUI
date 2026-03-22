package com.opencode.cui.gateway.repository;

import com.opencode.cui.gateway.model.AkSkCredential;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * AK/SK 凭证的 MyBatis Mapper。
 * 对应数据库 ak_sk_credential 表，供 AkSkAuthService 进行凭证查询和管理。
 */
@Mapper
public interface AkSkCredentialRepository {

    /** 按 AK 查询启用状态的凭证 */
    AkSkCredential findActiveByAk(@Param("ak") String ak);

    /** 按主键查询凭证 */
    AkSkCredential findById(@Param("id") Long id);

    /** 插入新凭证 */
    int insert(AkSkCredential credential);

    /** 更新凭证状态（ACTIVE/DISABLED） */
    int updateStatus(@Param("id") Long id,
            @Param("status") AkSkCredential.CredentialStatus status);
}
