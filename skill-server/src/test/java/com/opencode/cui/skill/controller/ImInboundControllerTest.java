package com.opencode.cui.skill.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.AssistantResolveResult;
import com.opencode.cui.skill.model.ImMessageRequest;
import com.opencode.cui.skill.model.InvokeCommand;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.config.AssistantIdProperties;
import com.opencode.cui.skill.model.AgentSummary;
import com.opencode.cui.skill.service.AssistantAccountResolverService;
import com.opencode.cui.skill.service.ContextInjectionService;
import com.opencode.cui.skill.service.GatewayApiClient;
import com.opencode.cui.skill.service.GatewayRelayService;
import com.opencode.cui.skill.service.ImOutboundService;
import com.opencode.cui.skill.service.ImSessionManager;
import com.opencode.cui.skill.service.SessionRebuildService;
import com.opencode.cui.skill.service.SkillMessageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.contains;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
/** ImInboundController 单元测试：验证 IM 入站消息的接收和处理逻辑。 */
class ImInboundControllerTest {

        @Mock
        private AssistantAccountResolverService resolverService;
        @Mock
        private GatewayApiClient gatewayApiClient;
        @Mock
        private ImSessionManager sessionManager;
        @Mock
        private ImOutboundService imOutboundService;
        @Mock
        private ContextInjectionService contextInjectionService;
        @Mock
        private GatewayRelayService gatewayRelayService;
        @Mock
        private SkillMessageService messageService;
        @Mock
        private SessionRebuildService rebuildService;
        private AssistantIdProperties assistantIdProperties;
        private ImInboundController controller;

        @BeforeEach
        void setUp() {
                assistantIdProperties = new AssistantIdProperties();
                assistantIdProperties.setEnabled(true);
                assistantIdProperties.setTargetToolType("assistant");
                controller = new ImInboundController(
                                resolverService,
                                assistantIdProperties,
                                gatewayApiClient,
                                sessionManager,
                                imOutboundService,
                                contextInjectionService,
                                gatewayRelayService,
                                messageService,
                                rebuildService,
                                new ObjectMapper());
                // 默认 Agent 在线
                lenient().when(gatewayApiClient.getAgentByAk(any()))
                        .thenReturn(AgentSummary.builder().ak("ak-001").toolType("assistant").build());
        }

        @Test
        @DisplayName("direct message persists user input and sends gateway invoke with ownerWelinkId")
        void directMessagePersistsAndInvokes() {
                SkillSession session = new SkillSession();
                session.setId(101L);
                session.setAk("ak-001");
                session.setUserId("owner-welink-001");
                session.setBusinessSessionDomain("im");
                session.setBusinessSessionType("direct");
                session.setToolSessionId("tool-001");

                ImMessageRequest request = new ImMessageRequest(
                                "im",
                                "direct",
                                "dm-001",
                                "assist-001",
                                "hello",
                                "text",
                                null,
                                null);

                when(resolverService.resolve("assist-001"))
                                .thenReturn(new AssistantResolveResult("ak-001", "owner-welink-001"));
                when(sessionManager.findSession("im", "direct", "dm-001", "ak-001"))
                                .thenReturn(session);
                when(contextInjectionService.resolvePrompt("direct", "hello", null)).thenReturn("hello");

                var response = controller.receiveMessage(request);

                assertEquals(HttpStatus.OK, response.getStatusCode());
                verify(messageService).saveUserMessage(101L, "hello");
                ArgumentCaptor<InvokeCommand> captor = ArgumentCaptor.forClass(InvokeCommand.class);
                verify(gatewayRelayService).sendInvokeToGateway(captor.capture());
                assertEquals("ak-001", captor.getValue().ak());
                assertEquals("owner-welink-001", captor.getValue().userId());
                assertEquals("chat", captor.getValue().action());
                assertTrue(captor.getValue().payload().contains("tool-001"));
        }

        @Test
        @DisplayName("group message skips user persistence")
        void groupMessageSkipsUserPersistence() {
                SkillSession session = new SkillSession();
                session.setId(102L);
                session.setAk("ak-001");
                session.setUserId("owner-welink-001");
                session.setBusinessSessionDomain("im");
                session.setBusinessSessionType("group");
                session.setToolSessionId("tool-002");

                ImMessageRequest request = new ImMessageRequest(
                                "im",
                                "group",
                                "grp-001",
                                "assist-001",
                                "summarize this",
                                "text",
                                null,
                                List.of(new ImMessageRequest.ChatMessage("user-1", "Alice", "history", 1710000000L)));

                when(resolverService.resolve("assist-001"))
                                .thenReturn(new AssistantResolveResult("ak-001", "owner-welink-001"));
                when(sessionManager.findSession("im", "group", "grp-001", "ak-001"))
                                .thenReturn(session);
                when(contextInjectionService.resolvePrompt(eq("group"), eq("summarize this"), any()))
                                .thenReturn("group prompt");

                var response = controller.receiveMessage(request);

                assertEquals(HttpStatus.OK, response.getStatusCode());
                verify(messageService, never()).saveUserMessage(any(), any());
                verify(gatewayRelayService).sendInvokeToGateway(any(InvokeCommand.class));
        }

        @Test
        @DisplayName("no session triggers async creation with ownerWelinkId and returns OK immediately")
        void noSessionTriggersAsyncCreation() {
                ImMessageRequest request = new ImMessageRequest(
                                "im",
                                "direct",
                                "dm-new",
                                "assist-001",
                                "first message",
                                "text",
                                null,
                                null);

                when(resolverService.resolve("assist-001"))
                                .thenReturn(new AssistantResolveResult("ak-001", "owner-welink-001"));
                when(sessionManager.findSession("im", "direct", "dm-new", "ak-001"))
                                .thenReturn(null);
                when(contextInjectionService.resolvePrompt("direct", "first message", null))
                                .thenReturn("first message");

                var response = controller.receiveMessage(request);

                assertEquals(HttpStatus.OK, response.getStatusCode());
                verify(sessionManager).createSessionAsync(
                                "im", "direct", "dm-new", "ak-001",
                                "owner-welink-001", "assist-001", "first message");
                verify(gatewayRelayService, never()).sendInvokeToGateway(any());
        }

        @Test
        @DisplayName("session exists but toolSessionId not ready triggers requestToolSession")
        void sessionWithoutToolSessionTriggersRebuild() {
                SkillSession session = new SkillSession();
                session.setId(105L);
                session.setAk("ak-001");
                session.setUserId("owner-welink-001");
                session.setBusinessSessionDomain("im");
                session.setBusinessSessionType("direct");
                session.setToolSessionId(null);

                ImMessageRequest request = new ImMessageRequest(
                                "im",
                                "direct",
                                "dm-005",
                                "assist-001",
                                "waiting message",
                                "text",
                                null,
                                null);

                when(resolverService.resolve("assist-001"))
                                .thenReturn(new AssistantResolveResult("ak-001", "owner-welink-001"));
                when(sessionManager.findSession("im", "direct", "dm-005", "ak-001"))
                                .thenReturn(session);
                when(contextInjectionService.resolvePrompt("direct", "waiting message", null))
                                .thenReturn("waiting message");

                var response = controller.receiveMessage(request);

                assertEquals(HttpStatus.OK, response.getStatusCode());
                verify(sessionManager).requestToolSession(session, "waiting message");
                verify(gatewayRelayService, never()).sendInvokeToGateway(any());
        }

        @Test
        @DisplayName("agent offline in direct chat with existing session replies IM and saves system message")
        void agentOfflineDirectWithSessionRepliesAndPersists() {
                SkillSession session = new SkillSession();
                session.setId(101L);
                session.setAk("ak-001");

                ImMessageRequest request = new ImMessageRequest(
                                "im", "direct", "dm-001", "assist-001", "hello", "text", null, null);

                when(resolverService.resolve("assist-001"))
                                .thenReturn(new AssistantResolveResult("ak-001", "owner-001"));
                when(gatewayApiClient.getAgentByAk("ak-001")).thenReturn(null); // Agent 离线
                when(sessionManager.findSession("im", "direct", "dm-001", "ak-001")).thenReturn(session);

                var response = controller.receiveMessage(request);

                assertEquals(HttpStatus.OK, response.getStatusCode());
                verify(imOutboundService).sendTextToIm(eq("direct"), eq("dm-001"),
                                contains("任务下发失败"), eq("assist-001"));
                verify(messageService).saveSystemMessage(eq(101L), contains("任务下发失败"));
                verify(gatewayRelayService, never()).sendInvokeToGateway(any());
        }

        @Test
        @DisplayName("agent offline in group chat replies IM without persisting")
        void agentOfflineGroupRepliesWithoutPersisting() {
                ImMessageRequest request = new ImMessageRequest(
                                "im", "group", "grp-001", "assist-001", "hello", "text", null, null);

                when(resolverService.resolve("assist-001"))
                                .thenReturn(new AssistantResolveResult("ak-001", "owner-001"));
                when(gatewayApiClient.getAgentByAk("ak-001")).thenReturn(null); // Agent 离线

                var response = controller.receiveMessage(request);

                assertEquals(HttpStatus.OK, response.getStatusCode());
                verify(imOutboundService).sendTextToIm(eq("group"), eq("grp-001"),
                                contains("任务下发失败"), eq("assist-001"));
                verify(messageService, never()).saveSystemMessage(any(), any());
                verify(gatewayRelayService, never()).sendInvokeToGateway(any());
        }

        @Test
        @DisplayName("agent offline in direct chat without session replies IM only")
        void agentOfflineDirectNoSessionRepliesOnly() {
                ImMessageRequest request = new ImMessageRequest(
                                "im", "direct", "dm-new", "assist-001", "hello", "text", null, null);

                when(resolverService.resolve("assist-001"))
                                .thenReturn(new AssistantResolveResult("ak-001", "owner-001"));
                when(gatewayApiClient.getAgentByAk("ak-001")).thenReturn(null); // Agent 离线
                when(sessionManager.findSession("im", "direct", "dm-new", "ak-001")).thenReturn(null);

                var response = controller.receiveMessage(request);

                assertEquals(HttpStatus.OK, response.getStatusCode());
                verify(imOutboundService).sendTextToIm(eq("direct"), eq("dm-new"),
                                contains("任务下发失败"), eq("assist-001"));
                verify(messageService, never()).saveSystemMessage(any(), any());
        }
}
