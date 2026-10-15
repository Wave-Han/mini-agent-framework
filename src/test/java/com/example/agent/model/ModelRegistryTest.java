package com.example.agent.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ModelRegistryTest —— pins the provider-registry contract:
 * first registration wins the default slot, unknown providers are rejected.
 */
class ModelRegistryTest {

    /** Stub ChatModel: the registry only stores it, never calls it. */
    private static ChatModel stub() {
        return prompt -> null;
    }

    @Test
    @DisplayName("first registered provider becomes the default")
    void firstRegistrationIsDefault() {
        ModelRegistry registry = new ModelRegistry();
        registry.register("openai", stub());
        registry.register("anthropic", stub());

        assertNotNull(registry.getDefault());
        assertTrue(registry.availableProviders().containsAll(Set.of("openai", "anthropic")));
    }

    @Test
    @DisplayName("get() on an unregistered provider is rejected")
    void unknownProviderRejected() {
        ModelRegistry registry = new ModelRegistry();
        registry.register("openai", stub());

        assertThrows(IllegalArgumentException.class, () -> registry.get("claude"));
    }

    @Test
    @DisplayName("getDefault() with no registration is an illegal state")
    void defaultWithoutRegistrationRejected() {
        ModelRegistry registry = new ModelRegistry();

        assertThrows(IllegalStateException.class, registry::getDefault);
    }

    @Test
    @DisplayName("setDefault() only accepts registered providers")
    void setDefaultRequiresRegistration() {
        ModelRegistry registry = new ModelRegistry();
        registry.register("openai", stub());
        registry.register("anthropic", stub());

        registry.setDefault("anthropic");
        assertThrows(IllegalArgumentException.class, () -> registry.setDefault("ollama"));
    }
}
