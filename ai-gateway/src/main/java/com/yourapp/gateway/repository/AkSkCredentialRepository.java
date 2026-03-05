package com.yourapp.gateway.repository;

import com.yourapp.gateway.model.AkSkCredential;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * MyBatis mapper for the ak_sk_credential table (REQ-26).
 *
 * Used by AkSkAuthService for database-backed credential lookup.
 */
@Mapper
public interface AkSkCredentialRepository {

    /** Find an active credential by Access Key */
    AkSkCredential findActiveByAk(@Param("ak") String ak);

    /** Find credential by primary key */
    AkSkCredential findById(@Param("id") Long id);

    /** Insert a new credential */
    int insert(AkSkCredential credential);

    /** Update credential status (ACTIVE/DISABLED) */
    int updateStatus(@Param("id") Long id,
            @Param("status") AkSkCredential.CredentialStatus status);
}
