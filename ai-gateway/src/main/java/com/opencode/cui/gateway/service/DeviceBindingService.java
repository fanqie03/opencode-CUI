package com.opencode.cui.gateway.service;

import com.opencode.cui.gateway.model.AgentConnection;
import com.opencode.cui.gateway.repository.AgentConnectionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * AK 设备绑定校验服务。
 * 强制业务规则：一个 AK 只能绑定一台设备（MAC 地址）和一个工具类型（toolType）。
 *
 * <p>
 * 直接查 agent_connection 表判断绑定关系：
 * <ul>
 * <li>首次连接（无记录）→ 放行（后续 register 会创建绑定）</li>
 * <li>有记录 → 比对 MAC 地址和 toolType，不一致则拒绝</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
public class DeviceBindingService {

    @Value("${gateway.device-binding.enabled:false}")
    private boolean enabled;

    private final AgentConnectionRepository agentConnectionRepository;

    public DeviceBindingService(AgentConnectionRepository agentConnectionRepository) {
        this.agentConnectionRepository = agentConnectionRepository;
    }

    /**
     * 校验 AK 设备绑定。
     * 一个 AK 只允许绑定一台设备（MAC）和一个 toolType。
     *
     * @param ak         Access Key ID
     * @param macAddress 设备 MAC 地址
     * @param toolType   工具类型（如 OPENCODE、channel）
     * @return 校验通过返回 true
     */
    public boolean validate(String ak, String macAddress, String toolType) {
        if (!enabled) {
            log.debug("Device binding validation disabled, allowing: ak={}", ak);
            return true;
        }

        try {
            // 按 AK 查询最近的连接记录（不限 toolType，覆盖所有历史绑定）
            AgentConnection existing = agentConnectionRepository.findLatestByAkId(ak);
            if (existing == null) {
                // 首次连接，无绑定记录，放行
                log.info("Device binding: first connection for ak={}, allowing", ak);
                return true;
            }

            // 校验 toolType 是否一致
            String boundToolType = existing.getToolType();
            if (boundToolType != null && !boundToolType.equalsIgnoreCase(toolType)) {
                log.warn("Device binding rejected: toolType mismatch. ak={}, bound={}, requested={}",
                        ak, boundToolType, toolType);
                return false;
            }

            // 校验 MAC 地址是否一致
            String boundMac = existing.getMacAddress();
            if (boundMac != null && macAddress != null && !boundMac.equalsIgnoreCase(macAddress)) {
                log.warn("Device binding rejected: MAC mismatch. ak={}, bound={}, requested={}",
                        ak, boundMac, macAddress);
                return false;
            }

            log.debug("Device binding valid: ak={}, mac={}, toolType={}", ak, macAddress, toolType);
            return true;

        } catch (Exception e) {
            log.warn("Device binding check failed, fail-open: ak={}, error={}", ak, e.getMessage());
            return true;
        }
    }
}
