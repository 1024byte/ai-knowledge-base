package com.hai.aiknowledgebase.service;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * <h2>参数快照覆盖作用域</h2>
 *
 * <p>用于检索评测：一次评测运行创建时会把当时生效的参数整体快照，
 * 执行每个 Case 时在快照作用域内运行，保证「运行期间修改参数管理不影响本次评测」。</p>
 *
 * <p>实现为 {@link ThreadLocal}，因此<b>参数读取必须发生在作用域所在线程</b>。
 * 若检索链中的异步任务需要读取参数，必须用 {@link #wrap(Runnable)} / {@link #wrap(Callable)}
 * 包装提交，或在外层解析后以方法参数传入（现有检索链均为后者）。</p>
 *
 * <p>空调用（快照为 null 或空）直接执行，不读写 ThreadLocal，对召回测试与生产问答零影响。</p>
 *
 * @see ParamValueProvider 所有类型化读取入口会优先查询本作用域
 */
public final class ParamOverrideScope {

    private static final ThreadLocal<Map<String, String>> OVERRIDES = new ThreadLocal<>();

    private ParamOverrideScope() {
    }

    /**
     * 在参数快照作用域内执行动作。
     *
     * @param snapshot 参数快照（key=paramKey，value=生效值），null/空表示不覆盖
     * @param action   待执行动作
     */
    public static <T> T callWith(Map<String, String> snapshot, Supplier<T> action) {
        if (snapshot == null || snapshot.isEmpty()) {
            return action.get();
        }
        Map<String, String> previous = OVERRIDES.get();
        OVERRIDES.set(Collections.unmodifiableMap(new HashMap<>(snapshot)));
        try {
            return action.get();
        } finally {
            restore(previous);
        }
    }

    /** {@link #callWith(Map, Supplier)} 的无返回值版本。 */
    public static void runWith(Map<String, String> snapshot, Runnable action) {
        callWith(snapshot, () -> {
            action.run();
            return null;
        });
    }

    /**
     * 读取当前线程被覆盖的参数值。
     *
     * @return 命中快照返回值；未命中或无作用域返回 null
     */
    public static String get(String key) {
        Map<String, String> snapshot = OVERRIDES.get();
        return snapshot == null ? null : snapshot.get(key);
    }

    /** 当前线程是否存在快照作用域。 */
    public static boolean isActive() {
        return OVERRIDES.get() != null;
    }

    /** 传播当前快照到异步任务（仅当异步任务内确实需要读取参数时使用）。 */
    public static Runnable wrap(Runnable task) {
        Map<String, String> snapshot = OVERRIDES.get();
        if (snapshot == null) {
            return task;
        }
        return () -> runWith(snapshot, task);
    }

    /** 传播当前快照到异步任务（仅当异步任务内确实需要读取参数时使用）。 */
    public static <T> Callable<T> wrap(Callable<T> task) {
        Map<String, String> snapshot = OVERRIDES.get();
        if (snapshot == null) {
            return task;
        }
        return () -> {
            Map<String, String> previous = OVERRIDES.get();
            OVERRIDES.set(snapshot);
            try {
                return task.call();
            } finally {
                restore(previous);
            }
        };
    }

    private static void restore(Map<String, String> previous) {
        if (previous == null) {
            OVERRIDES.remove();
        } else {
            OVERRIDES.set(previous);
        }
    }
}
