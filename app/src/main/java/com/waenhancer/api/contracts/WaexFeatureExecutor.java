package com.waenhancer.api.contracts;

/**
 * Interface representing the execution pipeline for WAEX features.
 * 
 * Execution Flow:
 * Feature → Registry → Compatibility Layer → Capability Check → Adapter → Client
 */
public interface WaexFeatureExecutor {
    
    /**
     * Executes the specified feature.
     * 
     * @param featureId The unique identifier of the feature to execute.
     */
    void execute(String featureId);
}
