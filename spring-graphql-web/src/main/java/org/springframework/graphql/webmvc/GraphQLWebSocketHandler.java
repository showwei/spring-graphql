/*
 * Copyright 2002-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.graphql.webmvc;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import graphql.ErrorType;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.GraphqlErrorBuilder;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import org.springframework.graphql.WebInterceptor;
import org.springframework.graphql.WebInterceptorExecutionChain;
import org.springframework.graphql.WebOutput;
import org.springframework.graphql.WebSocketMessageInput;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.SubProtocolCapable;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ExceptionWebSocketHandlerDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * WebSocketHandler for GraphQL based on
 * <a href="https://github.com/enisdenjo/graphql-ws/blob/master/PROTOCOL.md">GraphQL Over WebSocket Protocol</a>
 * and for use on a Servlet container with {@code spring-websocket}.
 */
public class GraphQLWebSocketHandler extends TextWebSocketHandler implements SubProtocolCapable  {

	private static final Log logger = LogFactory.getLog(GraphQLWebSocketHandler.class);

	private static final List<String> SUB_PROTOCOL_LIST =
			Arrays.asList("graphql-transport-ws", "subscriptions-transport-ws");


	private final WebInterceptorExecutionChain executionChain;

	private final Duration initTimeoutDuration;

	private final HttpMessageConverter<?> converter;

	private final Map<String, SessionState> sessionInfoMap = new ConcurrentHashMap<>();


	/**
	 * Create a new instance.
	 * @param graphQL the GraphQL instance to use for query execution
	 * @param interceptors 0 or more interceptors to customize input and output
	 * @param converter  for JSON encoding and decoding
	 * @param initTimeoutDuration the time within which the {@code CONNECTION_INIT}
	 * type message must be received.
	 */
	public GraphQLWebSocketHandler(GraphQL graphQL, List<WebInterceptor> interceptors,
			HttpMessageConverter<?> converter, Duration initTimeoutDuration) {

		Assert.notNull(converter, "HttpMessageConverter for JSON is required");
		this.executionChain = new WebInterceptorExecutionChain(graphQL, interceptors);
		this.initTimeoutDuration = initTimeoutDuration;
		this.converter = converter;
	}


	@Override
	public List<String> getSubProtocols() {
		return SUB_PROTOCOL_LIST;
	}


	@Override
	public void afterConnectionEstablished(WebSocketSession session) {
		if ("subscriptions-transport-ws".equalsIgnoreCase(session.getAcceptedProtocol())) {
			if (logger.isDebugEnabled()) {
				logger.debug("apollographql/subscriptions-transport-ws is not supported, nor maintained. " +
						"Please, use https://github.com/enisdenjo/graphql-ws.");
			}
			GraphQLStatus.closeSession(session, GraphQLStatus.INVALID_MESSAGE_STATUS);
			return;
		}

		SessionState sessionState = new SessionState(session.getId());
		this.sessionInfoMap.put(session.getId(), sessionState);

		Mono.delay(this.initTimeoutDuration)
				.then(Mono.fromRunnable(() -> {
					if (!sessionState.isConnectionInitProcessed()) {
						GraphQLStatus.closeSession(session, GraphQLStatus.INIT_TIMEOUT_STATUS);
					}
				}))
				.subscribe();
	}

	@Override
	@SuppressWarnings("unchecked")
	protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
		Map<String, Object> map = decode(message, Map.class);
		String id = (String) map.get("id");
		MessageType messageType = MessageType.resolve((String) map.get("type"));
		if (messageType == null) {
			GraphQLStatus.closeSession(session, GraphQLStatus.INVALID_MESSAGE_STATUS);
			return;
		}
		SessionState sessionState = getSessionInfo(session);
		switch (messageType) {
			case SUBSCRIBE:
				if (!sessionState.isConnectionInitProcessed()) {
					GraphQLStatus.closeSession(session, GraphQLStatus.UNAUTHORIZED_STATUS);
					return;
				}
				if (id == null) {
					GraphQLStatus.closeSession(session, GraphQLStatus.INVALID_MESSAGE_STATUS);
					return;
				}
				URI uri = session.getUri();
				HttpHeaders headers = session.getHandshakeHeaders();
				WebSocketMessageInput input = new WebSocketMessageInput(uri, headers, id, getPayload(map));
				if (logger.isDebugEnabled()) {
					logger.debug("Executing: " + input);
				}
				this.executionChain.execute(input)
						.flatMapMany(output -> handleWebOutput(session, input.requestId(), output))
						.publishOn(sessionState.getScheduler()) // Serial blocking send via single thread
						.subscribe(new SendMessageSubscriber(id, session, sessionState));
				return;
			case COMPLETE:
				if (id != null) {
					Subscription subscription = sessionState.getSubscriptions().remove(id);
					if (subscription != null) {
						subscription.cancel();
					}
				}
				return;
			case CONNECTION_INIT:
				if (sessionState.setConnectionInitProcessed()) {
					GraphQLStatus.closeSession(session, GraphQLStatus.TOO_MANY_INIT_REQUESTS_STATUS);
					return;
				}
				TextMessage outputMessage = encode(session, null, MessageType.CONNECTION_ACK, null);
				session.sendMessage(outputMessage);
				return;
			default:
				GraphQLStatus.closeSession(session, GraphQLStatus.INVALID_MESSAGE_STATUS);
		}

	}

	@SuppressWarnings("unchecked")
	private <T> T decode(TextMessage message, Class<T> targetClass) throws IOException {
		return ((HttpMessageConverter<T>) this.converter).read(targetClass, new HttpInputMessageAdapter(message));
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> getPayload(Map<String, Object> message) {
		Map<String, Object> payload = (Map<String, Object>) message.get("payload");
		Assert.notNull(payload, "No \"payload\" in message: " + message);
		return payload;
	}

	private SessionState getSessionInfo(WebSocketSession session) {
		SessionState info = this.sessionInfoMap.get(session.getId());
		Assert.notNull(info, "No SessionInfo for " + session);
		return info;
	}

	@SuppressWarnings("unchecked")
	private Flux<TextMessage> handleWebOutput(WebSocketSession session, String id, WebOutput output) {
		if (logger.isDebugEnabled()) {
			logger.debug("Execution result ready" +
					(!CollectionUtils.isEmpty(output.getErrors()) ?
							" with errors: " + output.getErrors() : "") + ".");
		}
		Flux<ExecutionResult> outputFlux;
		if (output.getData() instanceof Publisher) {
			// Subscription
			outputFlux = Flux.from((Publisher<ExecutionResult>) output.getData())
					.doOnSubscribe(subscription -> {
						Subscription prev = getSessionInfo(session).getSubscriptions().putIfAbsent(id, subscription);
						if (prev != null) {
							throw new SubscriptionExistsException();
						}
					});
		}
		else {
			// Query
			outputFlux = (CollectionUtils.isEmpty(output.getErrors()) ?
					Flux.just(output) :
					Flux.error(new IllegalStateException("Execution failed: " + output.getErrors())));
		}

		return outputFlux
				.map(result -> {
					Map<String, Object> dataMap = result.toSpecification();
					return encode(session, id, MessageType.NEXT, dataMap);
				})
				.concatWith(Mono.fromCallable(() -> encode(session, id, MessageType.COMPLETE, null)))
				.onErrorResume(ex -> {
					if (ex instanceof SubscriptionExistsException) {
						CloseStatus status = new CloseStatus(4409, "Subscriber for " + id + " already exists");
						GraphQLStatus.closeSession(session, status);
						return Flux.empty();
					}
					ErrorType errorType = ErrorType.DataFetchingException;
					String message = ex.getMessage();
					Map<String, Object> errorMap = GraphqlErrorBuilder.newError()
							.errorType(errorType)
							.message(message)
							.build()
							.toSpecification();
					return Mono.just(encode(session, id, MessageType.ERROR, errorMap));
				});
	}

	@SuppressWarnings("unchecked")
	private <T> TextMessage encode(
			WebSocketSession session, @Nullable String id, MessageType messageType, @Nullable Object payload) {

		Map<String, Object> payloadMap = new HashMap<>(3);
		payloadMap.put("type", messageType.getType());
		if (id != null) {
			payloadMap.put("id", id);
		}
		if (payload != null) {
			payloadMap.put("payload", payload);
		}
		try {
			HttpOutputMessageAdapter outputMessage = new HttpOutputMessageAdapter();
			((HttpMessageConverter<T>) this.converter).write((T) payloadMap, null, outputMessage);
			return new TextMessage(outputMessage.toByteArray());
		}
		catch (IOException ex) {
			throw new IllegalStateException("Failed to write " + payloadMap + " as JSON", ex);
		}
	}

	@Override
	public void handleTransportError(WebSocketSession session, Throwable exception) {
		SessionState info = this.sessionInfoMap.remove(session.getId());
		if (info != null) {
			info.dispose();
		}
	}

	@Override
	public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) {
		SessionState info = this.sessionInfoMap.remove(session.getId());
		if (info != null) {
			info.dispose();
		}
	}

	@Override
	public boolean supportsPartialMessages() {
		return false;
	}


	private enum MessageType {

		CONNECTION_INIT("connection_init"),
		CONNECTION_ACK("connection_ack"),
		SUBSCRIBE("subscribe"),
		NEXT("next"),
		ERROR("error"),
		COMPLETE("complete");


		private static final Map<String, MessageType> messageTypes = new HashMap<>(6);

		static {
			for (MessageType messageType : MessageType.values()) {
				messageTypes.put(messageType.getType(), messageType);
			}
		}


		private final String type;

		MessageType(String type) {
			this.type = type;
		}

		public String getType() {
			return this.type;
		}

		@Nullable
		public static MessageType resolve(@Nullable String type) {
			return (type != null ? messageTypes.get(type) : null);
		}
	}


	private static class GraphQLStatus {

		private static final CloseStatus INVALID_MESSAGE_STATUS = new CloseStatus(4400, "Invalid message");

		private static final CloseStatus UNAUTHORIZED_STATUS = new CloseStatus(4401, "Unauthorized");

		private static final CloseStatus INIT_TIMEOUT_STATUS = new CloseStatus(4408, "Connection initialisation timeout");

		private static final CloseStatus TOO_MANY_INIT_REQUESTS_STATUS = new CloseStatus(4429, "Too many initialisation requests");


		public static void closeSession(WebSocketSession session, CloseStatus status) {
			try {
				session.close(status);
			}
			catch (IOException ex) {
				if (logger.isDebugEnabled()) {
					logger.debug("Error while closing session with status: " + status, ex);
				}
			}
		}
	}


	private static class HttpInputMessageAdapter extends ByteArrayInputStream implements HttpInputMessage {

		HttpInputMessageAdapter(TextMessage message) {
			super(message.asBytes());
		}

		@Override
		public InputStream getBody() {
			return this;
		}

		@Override
		public HttpHeaders getHeaders() {
			return HttpHeaders.EMPTY;
		}
	}


	private static class HttpOutputMessageAdapter extends ByteArrayOutputStream implements HttpOutputMessage {

		private static final HttpHeaders noOpHeaders = new HttpHeaders();

		@Override
		public OutputStream getBody() {
			return this;
		}

		@Override
		public HttpHeaders getHeaders() {
			return noOpHeaders;
		}
	}


	private static class SessionState {

		private boolean connectionInitProcessed;

		private final Map<String, Subscription> subscriptions = new ConcurrentHashMap<>();

		private final Scheduler scheduler;

		public SessionState(String sessionId) {
			this.scheduler = Schedulers.newSingle("GraphQL-WsSession-" + sessionId);
		}

		public boolean isConnectionInitProcessed() {
			return this.connectionInitProcessed;
		}

		public synchronized boolean setConnectionInitProcessed() {
			boolean previousValue = this.connectionInitProcessed;
			this.connectionInitProcessed = true;
			return previousValue;
		}

		public Map<String, Subscription> getSubscriptions() {
			return this.subscriptions;
		}

		public void dispose() {
			for (Map.Entry<String, Subscription> entry : this.subscriptions.entrySet()) {
				try {
					entry.getValue().cancel();
				}
				catch (Throwable ex) {
					// Ignore and keep on
				}
			}
			this.subscriptions.clear();
			this.scheduler.dispose();
		}

		public Scheduler getScheduler() {
			return this.scheduler;
		}
	}


	private class SendMessageSubscriber extends BaseSubscriber<TextMessage> {

		private final String subscriptionId;

		private final WebSocketSession session;

		private final SessionState sessionState;

		public SendMessageSubscriber(String subscriptionId, WebSocketSession session, SessionState sessionState) {
			this.subscriptionId = subscriptionId;
			this.session = session;
			this.sessionState = sessionState;
		}

		@Override
		protected void hookOnSubscribe(Subscription subscription) {
			subscription.request(1);
		}

		@Override
		protected void hookOnNext(TextMessage nextMessage) {
			try {
				this.session.sendMessage(nextMessage);
				request(1);
			}
			catch (IOException ex) {
				ExceptionWebSocketHandlerDecorator.tryCloseWithError(this.session, ex, logger);
			}
		}

		@Override
		public void hookOnError(Throwable ex) {
			ExceptionWebSocketHandlerDecorator.tryCloseWithError(this.session, ex, logger);
		}

		@Override
		public void hookOnComplete() {
			this.sessionState.getSubscriptions().remove(this.subscriptionId);
		}
	}


	private static class SubscriptionExistsException extends RuntimeException {
	}

}
