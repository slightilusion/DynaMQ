package org.dynabot.session;

import io.vertx.core.Future;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Local in-memory session manager.
 * Used when Redis is not available or for single-node deployments.
 */
@Slf4j
public class LocalSessionManager implements SessionManager {

    private final ConcurrentHashMap<String, ClientSession> sessions = new ConcurrentHashMap<>();
    private final AtomicLong sessionCount = new AtomicLong(0);

    @Override
    public Future<ClientSession> createSession(String clientId, boolean cleanSession) {
        // Check if session exists
        ClientSession existingSession = sessions.get(clientId);

        if (existingSession != null) {
            if (cleanSession) {
                // Clean session requested, remove old session
                sessions.remove(clientId);
                log.debug("Removed existing session for client: {}", clientId);
            } else {
                // Restore existing session
                existingSession.setConnectedAt(Instant.now());
                existingSession.touch();
                log.debug("Restored existing session for client: {}", clientId);
                return Future.succeededFuture(existingSession);
            }
        }

        // Create new session
        ClientSession session = ClientSession.builder()
                .clientId(clientId)
                .cleanSession(cleanSession)
                .connectedAt(Instant.now())
                .lastActivityAt(Instant.now())
                .subscriptions(new ConcurrentHashMap<>())
                .pendingQoS1(new ConcurrentHashMap<>())
                .pendingQoS2(new ConcurrentHashMap<>())
                .build();

        sessions.put(clientId, session);
        sessionCount.incrementAndGet();

        log.debug("Created new session for client: {}", clientId);
        return Future.succeededFuture(session);
    }

    @Override
    public Future<Optional<ClientSession>> getSession(String clientId) {
        return Future.succeededFuture(Optional.ofNullable(sessions.get(clientId)));
    }

    @Override
    public Future<Void> updateSession(ClientSession session) {
        sessions.put(session.getClientId(), session);
        return Future.succeededFuture();
    }

    @Override
    public Future<Void> removeSession(String clientId, boolean permanent) {
        ClientSession removed = sessions.remove(clientId);
        if (removed != null) {
            sessionCount.decrementAndGet();
            log.debug("Removed session for client: {} (permanent: {})", clientId, permanent);
        }
        return Future.succeededFuture();
    }

    @Override
    public Future<Boolean> isClientConnected(String clientId) {
        ClientSession session = sessions.get(clientId);
        return Future.succeededFuture(session != null && session.isConnected());
    }

    @Override
    public Future<Optional<String>> getClientNode(String clientId) {
        ClientSession session = sessions.get(clientId);
        if (session != null) {
            return Future.succeededFuture(Optional.ofNullable(session.getNodeId()));
        }
        return Future.succeededFuture(Optional.empty());
    }

    @Override
    public Future<Void> forceDisconnect(String clientId) {
        ClientSession session = sessions.get(clientId);
        if (session != null && session.getEndpoint() != null) {
            session.getEndpoint().close();
        }
        return Future.succeededFuture();
    }

    @Override
    public Future<Long> getSessionCount() {
        return Future.succeededFuture(sessionCount.get());
    }
}
