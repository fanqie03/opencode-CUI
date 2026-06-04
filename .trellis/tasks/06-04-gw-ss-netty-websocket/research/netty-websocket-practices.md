# Research: Netty WebSocket practices for GW-SS transport

- Date: 2026-06-04
- Scope: external research + current GW-SS failure context
- Trigger: GW logged `Delivered to-source relay` for `sourceType=skill-server`, but the corresponding SS trace had no `GatewayRelayService.handleGatewayMessage` / miniapp stream logs.

## Question

Before replacing the current WebSocket second-party package, define what "good" looks like for a Netty-based GW-SS internal transport:

- How should a Netty WebSocket server/client pipeline be built?
- What does a send operation mean: enqueue, local channel write, socket flush, or remote receipt?
- How should liveness, backpressure, close, drain, and rolling upgrade be handled?
- Which practices from mature Netty ecosystem projects should become mandatory constraints for this codebase?

## Primary sources checked

### Netty official APIs and examples

- `WebSocketServerProtocolHandler`: handles WebSocket handshaking and control frames; text/binary data frames are passed to application handlers.
  Source: https://netty.io/4.1/api/io/netty/handler/codec/http/websocketx/WebSocketServerProtocolHandler.html
- Netty WebSocket server example pipeline: `HttpServerCodec` -> `HttpObjectAggregator` -> optional compression -> `WebSocketServerProtocolHandler` -> application frame handler.
  Source: https://netty.io/4.1/xref/io/netty/example/http/websocketx/server/WebSocketServerInitializer.html
- Netty WebSocket client example: client builds an HTTP/WebSocket pipeline, waits for handshake completion, sends `TextWebSocketFrame`, `PingWebSocketFrame`, and `CloseWebSocketFrame` with `writeAndFlush`.
  Source: https://netty.io/4.1/xref/io/netty/example/http/websocketx/client/WebSocketClient.html
- `ChannelFuture`: all Netty I/O operations are asynchronous; a returned future reports success/failure/cancel. Netty recommends `addListener` over blocking `await` in I/O paths.
  Source: https://netty.io/4.2/api/io/netty/channel/ChannelFuture.html
- `Channel`: `isActive`, `closeFuture`, `isWritable`, `bytesBeforeUnwritable`, and `bytesBeforeWritable` are first-class channel state/backpressure signals.
  Source: https://netty.io/4.1/xref/io/netty/channel/Channel.html
- `WriteBufferWaterMark`: when queued outbound bytes exceed the high watermark, `Channel.isWritable()` becomes false; it becomes true again only after dropping below the low watermark.
  Source: https://netty.io/4.1/api/io/netty/channel/WriteBufferWaterMark.html
- `IdleStateHandler`: emits reader/writer/all idle events. Netty's own example sends ping on writer idle and closes on reader idle.
  Source: https://netty.io/4.1/api/io/netty/handler/timeout/IdleStateHandler.html

### Mature Netty-based frameworks / ecosystem projects

- Reactor Netty exposes WebSocket configuration knobs such as max frame payload length, compression, subprotocols, and whether Ping frames are proxied or automatically answered.
  Source: https://docs.spring.io/projectreactor/reactor-netty/docs/current/api/reactor/netty/http/websocket/WebsocketSpec.html
- Spring WebFlux over Reactor Netty models a WebSocket session as inbound and outbound streams; outbound `send(Publisher<WebSocketMessage>)` completes when the source completes and writing is done. It treats completion/error/cancel signals as the session lifecycle instead of repeatedly polling "is open".
  Source: https://docs.spring.io/spring-framework/reference/web/webflux-websocket.html
- Vert.x WebSocket, also built on Netty, exposes `setWriteQueueMaxSize`, `writeQueueFull`, `drainHandler`, `exceptionHandler`, `closeHandler`, and `pongHandler`; its docs explicitly position WebSocket as both read and write streams with flow control.
  Source: https://vertx.io/docs/apidocs/io/vertx/core/http/WebSocket.html
- Armeria is Netty-based and emphasizes that event-loop threads must not block. It separates socket I/O event loops from blocking executors, and its backpressure guide waits for data to be consumed/written before producing more.
  Sources: https://armeria.dev/docs/advanced/threading-model/ and https://armeria.dev/docs/advanced/streaming-backpressure/

### Protocol baseline

- RFC 6455 defines Close/Ping/Pong control frames, ordered fragments, OPEN/CLOSING/CLOSED state, and abnormal closure behavior.
  Source: https://www.rfc-editor.org/rfc/rfc6455

## Findings

### 1. "Delivered" must not mean "put into a local queue"

Netty's `writeAndFlush` returns a `ChannelFuture`; the operation is not complete at method return. A correct transport distinguishes at least four stages:

| Stage | Meaning | Acceptable log word |
|---|---|---|
| accepted | caller gave message to transport API | `accepted` |
| queued | transport queued message locally because channel is temporarily busy | `queued` |
| write_ok | Netty write/flush future succeeded | `write_ok` / `flushed` |
| received | remote side decoded the application frame | `received` / `acked` |

The current GW log `Delivered to-source relay` is therefore a bad semantic name if it is emitted before the actual socket write future succeeds and before SS receives the frame.

### 2. One channel must have one owner and one write path

Mature Netty usage centralizes per-channel state in the channel/pipeline and uses the channel event loop to serialize I/O. The current code shape has two risks that a Netty migration must explicitly avoid:

- two services owning independent async sender maps for the same conceptual GW -> SS delivery path;
- source routing state being updated separately from the physical channel lifecycle.

The replacement should have one `GwSsTransport` or equivalent owner for:

- channel registry;
- source identity binding;
- outbound sequence assignment;
- `writeAndFlush` and future listeners;
- channel inactive/exception/idle cleanup;
- metrics and logs.

### 3. Liveness must be channel-driven, not only registry-driven

Netty and RFC 6455 both expose actual connection lifecycle:

- `channelActive` / handshake complete: eligible to register the source.
- `channelInactive`, `exceptionCaught`, `CloseWebSocketFrame`, reader idle, and write failure: immediately unregister the source and close the channel.
- writer idle: send WebSocket Ping or application heartbeat.
- reader idle: close; do not keep a fake-live local entry.

Registry TTL can prevent cross-pod stale routing, but it is not a substitute for local channel cleanup.

### 4. Backpressure is a required behavior, not an optimization

Netty's `Channel.isWritable()` and `WriteBufferWaterMark`, Vert.x `writeQueueFull/drainHandler`, and Armeria `whenConsumed` all express the same rule: a fast producer must stop or degrade when the receiver/socket cannot keep up.

For GW-SS streaming, "message too many" should produce a visible state transition:

- writable -> not writable;
- enqueue bounded;
- overflow policy triggered;
- recovery on `channelWritabilityChanged`;
- drop/close/escalate metrics emitted.

Silent in-memory loss is not acceptable.

### 5. Heartbeat needs both WebSocket control frames and application identity

WebSocket Ping/Pong answers "is the peer TCP/WebSocket endpoint responsive?" It does not prove "this channel is bound to sourceInstanceId X and can process skill messages."

Therefore GW-SS should use:

- WebSocket Ping/Pong or Netty idle events for transport health;
- `source_hello` / `source_registered` / `source_leave` / batched `source_ack` application frames for identity and delivery observability.

### 6. Rolling upgrade requires drain semantics

Good Netty systems do not rely on "process died eventually" for cleanup:

- readiness should turn false before shutdown;
- server should stop accepting new source channels;
- existing channels should receive a close/goaway control frame;
- source state should be removed on graceful close;
- remaining channels should be force-closed after a bounded grace period.

This prevents both stale source routing and "old channel looked active locally" windows.

## Recommended GW-SS transport invariants

1. Register a source only after WebSocket handshake and `source_hello` validation succeed.
2. A source binding is `(sourceType, sourceInstanceId, sourceBootId, connectionId)`; `sourceInstanceId` alone is not enough for replacement/rolling-upgrade races.
3. Never log `delivered` before either remote application ack or an explicitly documented delivery stage.
4. Every `writeAndFlush` has a listener. Failure immediately unregisters the source, closes the channel, and records route failure.
5. Outbound writes are serialized by the Netty channel/event loop; no duplicate per-service sender maps.
6. Bounded backpressure is mandatory. When not writable, queue only up to a configured byte/message budget, then use an explicit overflow policy.
7. `channelInactive`, `exceptionCaught`, close frame, reader idle, and write future failure all converge to the same cleanup path.
8. Ping/Pong handles transport liveness; app-level hello/ack handles source identity and frame receipt.
9. Rolling upgrade uses drain/goaway instead of relying only on pod restart.
10. Tests must include `EmbeddedChannel`/integration coverage for write failure, idle close, non-writable channel, duplicate connection replacement, and graceful drain.

## Direct implications for the observed trace

The trace where GW logged `Delivered to-source relay: sourceType=skill-server` but SS logged no `handleGatewayMessage` is exactly the class of issue this spec is meant to eliminate:

- If the message was only enqueued locally, the log must say `queued`.
- If Netty write future fails, the source must be unregistered immediately and the failure must be traceable.
- If the remote SS decodes the frame, SS should emit `received` and optionally batch ack.
- If the channel is fake-live, idle/write/inactive cleanup must remove it without waiting for Redis TTL.
