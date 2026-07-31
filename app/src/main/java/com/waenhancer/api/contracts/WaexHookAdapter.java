package com.waenhancer.api.contracts;

/**
 * Base contract representing a hook adapter for interfacing with target clients.
 */
public interface WaexHookAdapter {
    
    /**
     * Initializes the hooks for the specific client.
     */
    void initialize();
}
