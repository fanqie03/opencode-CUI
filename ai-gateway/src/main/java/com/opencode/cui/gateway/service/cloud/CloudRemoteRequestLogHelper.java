package com.opencode.cui.gateway.service.cloud;

import com.opencode.cui.gateway.logging.SensitiveDataMasker;
import org.slf4j.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CloudRemoteRequestLogHelper {

    private CloudRemoteRequestLogHelper() {
    }

    public static void logRequest(Logger log,
                           String protocol,
                           String endpoint,
                           Map<String, List<String>> headers,
                           String body,
                           CloudConnectionContext context) {
        if (log == null || !log.isInfoEnabled()) {
            return;
        }
        log.info("[EXT_CALL] CloudRemote.invoke request: protocol={}, endpoint={}, scope={}, appId={}, authType={}, "
                        + "cloudProfile={}, traceId={}, headers={}, body={}",
                protocol,
                endpoint,
                context == null ? null : context.getScope(),
                context == null ? null : context.getAppId(),
                context == null ? null : context.getAuthType(),
                context == null ? null : context.getCloudProfile(),
                context == null ? null : context.getTraceId(),
                maskedHeaders(headers),
                body == null ? "" : body);
    }

    static Map<String, List<String>> maskedHeaders(Map<String, List<String>> headers) {
        Map<String, List<String>> masked = new LinkedHashMap<>();
        if (headers == null || headers.isEmpty()) {
            return masked;
        }
        headers.forEach((name, values) -> masked.put(name, maskValues(name, values)));
        return masked;
    }

    private static List<String> maskValues(String name, List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        if (!isSensitiveHeader(name)) {
            return values.stream()
                    .map(value -> value == null ? "" : value)
                    .toList();
        }
        return values.stream()
                .map(SensitiveDataMasker::maskToken)
                .toList();
    }

    private static boolean isSensitiveHeader(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("authorization")
                || lower.contains("token")
                || lower.contains("secret")
                || lower.contains("signature");
    }
}
