package com.hai.aiknowledgebase.service;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ParamOverrideScopeTest {

    @Test
    void nestedScopesRestorePreviousSnapshot() {
        String result = ParamOverrideScope.callWith(Map.of("a", "outer", "b", "b"), () -> {
            assertThat(ParamOverrideScope.get("a")).isEqualTo("outer");
            String nested = ParamOverrideScope.callWith(Map.of("a", "inner"), () -> {
                assertThat(ParamOverrideScope.get("a")).isEqualTo("inner");
                assertThat(ParamOverrideScope.get("b")).isNull();
                return "nested";
            });
            assertThat(ParamOverrideScope.get("a")).isEqualTo("outer");
            assertThat(ParamOverrideScope.get("b")).isEqualTo("b");
            return nested;
        });

        assertThat(result).isEqualTo("nested");
        assertThat(ParamOverrideScope.isActive()).isFalse();
        assertThat(ParamOverrideScope.get("a")).isNull();
    }

    @Test
    void exceptionStillCleansThreadLocal() {
        assertThatThrownBy(() -> ParamOverrideScope.callWith(Map.of("a", "1"), () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(ParamOverrideScope.isActive()).isFalse();
    }

    @Test
    void emptySnapshotHasNoSideEffects() {
        String value = ParamOverrideScope.callWith(Map.of(), () -> {
            assertThat(ParamOverrideScope.isActive()).isFalse();
            return "ok";
        });

        assertThat(value).isEqualTo("ok");
        assertThat(ParamOverrideScope.get("a")).isNull();
    }

    @Test
    void wrappedTasksPropagateAndRestoreSnapshot() throws Exception {
        AtomicReference<String> runnableValue = new AtomicReference<>();
        Callable<String> callable = ParamOverrideScope.callWith(Map.of("a", "snapshot"), () -> {
            Runnable runnable = ParamOverrideScope.wrap(() -> runnableValue.set(ParamOverrideScope.get("a")));
            runnable.run();
            return ParamOverrideScope.wrap(() -> ParamOverrideScope.get("a"));
        });

        assertThat(ParamOverrideScope.isActive()).isFalse();
        assertThat(callable.call()).isEqualTo("snapshot");
        assertThat(runnableValue.get()).isEqualTo("snapshot");
        assertThat(ParamOverrideScope.isActive()).isFalse();
    }

    @Test
    void invalidNumericOverrideFallsBackWithoutReadingLiveParams() {
        ParamService paramService = mock(ParamService.class);
        ParamValueProvider provider = new ParamValueProvider(paramService);
        when(paramService.resolveAll(null)).thenReturn(Map.of("live", "9"));

        ParamOverrideScope.runWith(Map.of("int", "bad", "double", "bad"), () -> {
            assertThat(provider.getInt("int", 7)).isEqualTo(7);
            assertThat(provider.getDouble("double", 0.5)).isEqualTo(0.5);
            assertThat(provider.getString("live", "fallback")).isEqualTo("9");
        });
        assertThat(provider.getString("live", "fallback")).isEqualTo("9");
    }
}
