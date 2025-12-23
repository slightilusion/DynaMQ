package org.dynabot.session;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.dynabot.config.AppConfig;

import java.util.Optional;

/**
 * Session Manager Interface.
 * Manages MQTT client sessions across the broker cluster.
 */
public interface SessionManager {

    /**
     * Create a session manager based on configuration
     */
    static SessionManager create(Vertx vertx, AppConfig config) {
        if (config.isRedisEnabled()) {
            return new RedisSessionManager(vertx, config);
        } else {
            return new LocalSessionManager();
        }
    }

    /**
     * Create or restore a session for a client
     * 
     * @param clientId     Client identifier
     * @param cleanSession Whether to start with a clean session
     * @return Future containing the session
     */
    Future<ClientSession> createSession(String clientId, boolean cleanSession);

    /**
     * Get an existing session
     * 
     * @param clientId Client identifier
     * @return Future containing optional session
     */
    Future<Optional<ClientSession>> getSession(String clientId);

    /**
     * Update session state
     * 
     * @param session Session to update
     * @return Future completing when update is done
     */
    Future<Void> updateSession(ClientSession session);

    /**
     * Remove a session
     * 
     * @param clientId  Client identifier
     * @param permanent If true, remove even persistent session data
     * @return Future completing when removal is done
     */
    Future<Void> removeSession(String clientId, boolean permanent);

    /**
     * Check if a client is connected (possibly on another node)
     * 
     * @param clientId Client identifier
     * @return Future containing connection status
     */
    Future<Boolean> isClientConnected(String clientId);

    /**
     * Get the node where a client is connected
     * 
     * @param clientId Client identifier
     * @return Future containing optional node ID
     */
    Future<Optional<String>> getClientNode(String clientId);

    /**
     * Disconnect a client (used when same clientId connects elsewhere)
     * 
     * @param clientId Client identifier
     * @return Future completing when disconnect is done
     */
    Future<Void> forceDisconnect(String clientId);

    /**
     * Get total session count
     * 
     * @return Future containing session count
     */
    Future<Long> getSessionCount();
}
