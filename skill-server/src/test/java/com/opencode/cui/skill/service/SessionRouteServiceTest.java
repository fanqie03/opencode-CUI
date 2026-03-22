package com.opencode.cui.skill.service;

import com.opencode.cui.skill.model.SessionRoute;
import com.opencode.cui.skill.repository.SessionRouteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionRouteServiceTest {

    @Mock
    private SessionRouteRepository repository;

    private SessionRouteService service;

    private static final String INSTANCE_ID = "ss-az1-1";

    @BeforeEach
    void setUp() {
        service = new SessionRouteService(repository, INSTANCE_ID);
    }

    @Nested
    @DisplayName("路由记录 CRUD")
    class CrudTests {

        @Test
        @DisplayName("createRoute 插入记录并返回")
        void createRouteInsertsRecord() {
            service.createRoute("ak-1", 12345L, "skill-server", "user-123");

            ArgumentCaptor<SessionRoute> captor = ArgumentCaptor.forClass(SessionRoute.class);
            verify(repository).insert(captor.capture());

            SessionRoute route = captor.getValue();
            assertEquals("ak-1", route.getAk());
            assertEquals(12345L, route.getWelinkSessionId());
            assertEquals("skill-server", route.getSourceType());
            assertEquals(INSTANCE_ID, route.getSourceInstance());
            assertEquals("user-123", route.getUserId());
            assertEquals("ACTIVE", route.getStatus());
            assertNull(route.getToolSessionId());
        }

        @Test
        @DisplayName("updateToolSessionId 更新记录")
        void updateToolSessionIdUpdatesRecord() {
            service.updateToolSessionId(12345L, "skill-server", "oc-uuid-001");

            verify(repository).updateToolSessionId(12345L, "skill-server", "oc-uuid-001");
        }

        @Test
        @DisplayName("closeRoute 设置状态为 CLOSED")
        void closeRouteSetsStatusClosed() {
            service.closeRoute(12345L, "skill-server");

            verify(repository).updateStatus(12345L, "skill-server", "CLOSED");
        }
    }

    @Nested
    @DisplayName("Ownership 检查")
    class OwnershipTests {

        @Test
        @DisplayName("isMySession 返回 true 当 sourceInstance 匹配本实例")
        void isMySessionReturnsTrueWhenMatching() {
            SessionRoute route = new SessionRoute();
            route.setSourceInstance(INSTANCE_ID);
            when(repository.findByWelinkSessionId(12345L)).thenReturn(route);

            assertTrue(service.isMySession("12345"));
        }

        @Test
        @DisplayName("isMySession 返回 false 当 sourceInstance 不匹配")
        void isMySessionReturnsFalseWhenNotMatching() {
            SessionRoute route = new SessionRoute();
            route.setSourceInstance("ss-az1-2");
            when(repository.findByWelinkSessionId(12345L)).thenReturn(route);

            assertFalse(service.isMySession("12345"));
        }

        @Test
        @DisplayName("isMySession 返回 false 当记录不存在")
        void isMySessionReturnsFalseWhenNotFound() {
            when(repository.findByWelinkSessionId(12345L)).thenReturn(null);

            assertFalse(service.isMySession("12345"));
        }

        @Test
        @DisplayName("isMyToolSession 返回 true 当 sourceInstance 匹配")
        void isMyToolSessionReturnsTrueWhenMatching() {
            SessionRoute route = new SessionRoute();
            route.setSourceInstance(INSTANCE_ID);
            when(repository.findByToolSessionId("oc-uuid-001")).thenReturn(route);

            assertTrue(service.isMyToolSession("oc-uuid-001"));
        }

        @Test
        @DisplayName("isMyToolSession 返回 false 当不匹配或不存在")
        void isMyToolSessionReturnsFalseWhenNotMatching() {
            when(repository.findByToolSessionId("oc-uuid-001")).thenReturn(null);

            assertFalse(service.isMyToolSession("oc-uuid-001"));
        }
    }

    @Nested
    @DisplayName("查询")
    class QueryTests {

        @Test
        @DisplayName("findByToolSessionId 返回路由记录")
        void findByToolSessionIdReturnsRoute() {
            SessionRoute expected = new SessionRoute();
            expected.setToolSessionId("oc-uuid-001");
            expected.setSourceInstance(INSTANCE_ID);
            when(repository.findByToolSessionId("oc-uuid-001")).thenReturn(expected);

            SessionRoute result = service.findByToolSessionId("oc-uuid-001");

            assertNotNull(result);
            assertEquals("oc-uuid-001", result.getToolSessionId());
        }

        @Test
        @DisplayName("findByToolSessionId null 时返回 null")
        void findByToolSessionIdReturnsNullForNull() {
            assertNull(service.findByToolSessionId(null));
        }
    }
}
