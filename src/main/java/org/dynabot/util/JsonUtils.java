package org.dynabot.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;

/**
 * Shared JSON utilities with a singleton ObjectMapper instance.
 * ObjectMapper is thread-safe after configuration, so sharing a single instance
 * improves performance by avoiding repeated initialization overhead.
 */
@Slf4j
public final class JsonUtils {

    private static final ObjectMapper MAPPER;

    static {
        MAPPER = new ObjectMapper();
        MAPPER.registerModule(new JavaTimeModule());
        MAPPER.setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY);
    }

    private JsonUtils() {
        // Utility class, no instantiation
    }

    /**
     * Get the shared ObjectMapper instance.
     * Note: Do not modify the configuration of this instance.
     */
    public static ObjectMapper getMapper() {
        return MAPPER;
    }

    /**
     * Serialize an object to JSON string.
     */
    public static String toJson(Object obj) throws JsonProcessingException {
        return MAPPER.writeValueAsString(obj);
    }

    /**
     * Serialize an object to JSON string, returning null on error.
     */
    public static String toJsonSafe(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize object to JSON: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Deserialize a JSON string to an object.
     */
    public static <T> T fromJson(String json, Class<T> clazz) throws JsonProcessingException {
        return MAPPER.readValue(json, clazz);
    }

    /**
     * Deserialize a JSON string to an object using TypeReference.
     */
    public static <T> T fromJson(String json, TypeReference<T> typeRef) throws JsonProcessingException {
        return MAPPER.readValue(json, typeRef);
    }

    /**
     * Deserialize a JSON string to an object, returning null on error.
     */
    public static <T> T fromJsonSafe(String json, Class<T> clazz) {
        try {
            return MAPPER.readValue(json, clazz);
        } catch (Exception e) {
            log.warn("Failed to deserialize JSON: {}", e.getMessage());
            return null;
        }
    }
}
