package org.dynabot.mqtt.retry;

import io.netty.handler.codec.mqtt.MqttQoS;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import lombok.extern.slf4j.Slf4j;
import org.dynabot.config.AppConfig;
import org.dynabot.session.ClientSession;
import org.dynabot.session.SessionManager;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Scheduler for retrying unacknowledged QoS 1 and QoS 2 messages.
 * Periodically checks for messages that haven't been acknowledged and resends
 * them.
 */
@Slf4j
public class MessageRetryScheduler {

    private static final long DEFAULT_RETRY_INTERVAL_MS = 10000; // 10 seconds
    private static final int DEFAULT_MAX_RETRIES = 3;

    private final Vertx vertx;
    private final SessionManager sessionManager;
    private final long retryIntervalMs;
    private final int maxRetries;

    private Long timerId;
    private boolean running = false;

    public MessageRetryScheduler(Vertx vertx, SessionManager sessionManager, AppConfig config) {
        this.vertx = vertx;
        this.sessionManager = sessionManager;
        this.retryIntervalMs = DEFAULT_RETRY_INTERVAL_MS;
        this.maxRetries = DEFAULT_MAX_RETRIES;
    }

    /**
     * Start the retry scheduler
     */
    public void start() {
        if (running) {
            log.warn("Message retry scheduler already running");
            return;
        }

        running = true;
        timerId = vertx.setPeriodic(retryIntervalMs, id -> processRetries());
        log.info("Message retry scheduler started with interval: {}ms, maxRetries: {}",
                retryIntervalMs, maxRetries);
    }

    /**
     * Stop the retry scheduler
     */
    public void stop() {
        if (!running) {
            return;
        }

        running = false;
        if (timerId != null) {
            vertx.cancelTimer(timerId);
            timerId = null;
        }
        log.info("Message retry scheduler stopped");
    }

    /**
     * Process all pending messages and retry if needed
     */
    private void processRetries() {
        sessionManager.getSessionCount()
                .onSuccess(count -> {
                    log.trace("Processing retries for {} sessions", count);
                })
                .onFailure(err -> {
                    log.error("Failed to get session count for retry processing: {}", err.getMessage());
                });

        // Note: In production, we would iterate through connected sessions
        // For now, we process retries when messages are delivered to sessions
    }

    /**
     * Process retries for a specific client session
     * This should be called periodically for each session with pending messages
     */
    public void processSessionRetries(ClientSession session) {
        if (session == null || !session.isConnected()) {
            return;
        }

        Instant now = Instant.now();

        // Process QoS 1 pending messages
        for (Map.Entry<Integer, ClientSession.PendingMessage> entry : session.getPendingQoS1().entrySet()) {
            processPendingMessage(session, entry.getKey(), entry.getValue(), now, 1);
        }

        // Process QoS 2 pending messages
        for (Map.Entry<Integer, ClientSession.PendingMessage> entry : session.getPendingQoS2().entrySet()) {
            processPendingMessage(session, entry.getKey(), entry.getValue(), now, 2);
        }
    }

    private void processPendingMessage(ClientSession session, int messageId,
            ClientSession.PendingMessage pending, Instant now, int qos) {

        if (pending.getSentAt() == null) {
            return;
        }

        Duration elapsed = Duration.between(pending.getSentAt(), now);

        // Check if retry is needed
        if (elapsed.toMillis() < retryIntervalMs) {
            return;
        }

        // Check max retries
        if (pending.getRetryCount() >= maxRetries) {
            log.warn("Max retries ({}) exceeded for message {} to client {}, dropping message",
                    maxRetries, messageId, session.getClientId());

            if (qos == 1) {
                session.getPendingQoS1().remove(messageId);
            } else {
                session.getPendingQoS2().remove(messageId);
            }
            return;
        }

        // Retry the message
        retryMessage(session, messageId, pending, qos);
    }

    private void retryMessage(ClientSession session, int messageId,
            ClientSession.PendingMessage pending, int qos) {

        if (session.getEndpoint() == null || !session.getEndpoint().isConnected()) {
            log.debug("Cannot retry message {} - client {} not connected",
                    messageId, session.getClientId());
            return;
        }

        // Update retry info
        pending.setSentAt(Instant.now());
        pending.setRetryCount(pending.getRetryCount() + 1);

        // Resend with DUP flag set
        session.getEndpoint().publish(
                pending.getTopic(),
                Buffer.buffer(pending.getPayload()),
                MqttQoS.valueOf(qos),
                true, // DUP flag for retransmission
                false,
                messageId);

        log.debug("Retried message {} to client {} (attempt {}/{})",
                messageId, session.getClientId(), pending.getRetryCount(), maxRetries);
    }

    /**
     * Schedule a retry for a newly sent message
     */
    public void scheduleRetry(ClientSession session, int messageId,
            String topic, byte[] payload, int qos) {

        ClientSession.PendingMessage pending = ClientSession.PendingMessage.builder()
                .messageId(messageId)
                .topic(topic)
                .payload(payload)
                .qos(qos)
                .sentAt(Instant.now())
                .retryCount(0)
                .build();

        if (qos == 1) {
            session.getPendingQoS1().put(messageId, pending);
        } else if (qos == 2) {
            session.getPendingQoS2().put(messageId, pending);
        }

        log.trace("Scheduled retry for message {} to client {}", messageId, session.getClientId());
    }

    /**
     * Cancel a scheduled retry (called when ACK is received)
     */
    public void cancelRetry(ClientSession session, int messageId, int qos) {
        if (qos == 1) {
            session.getPendingQoS1().remove(messageId);
        } else if (qos == 2) {
            session.getPendingQoS2().remove(messageId);
        }
        log.trace("Cancelled retry for message {} from client {}", messageId, session.getClientId());
    }

    /**
     * Check if the scheduler is running
     */
    public boolean isRunning() {
        return running;
    }
}
