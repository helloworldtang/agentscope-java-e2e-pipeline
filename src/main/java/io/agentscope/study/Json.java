package io.agentscope.study;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * 共享的 {@link ObjectMapper} 单例（注册了 JavaTimeModule，能反序列化 {@code McpServerConfig} 里的
 * {@code java.time.Duration} 字段）。
 *
 * <p>避免每次调用都 {@code new ObjectMapper()}（既是性能浪费，也容易漏注册 JSR310 模块）。
 */
public final class Json {

    public static final ObjectMapper MAPPER =
            new ObjectMapper().registerModule(new JavaTimeModule());

    private Json() {}
}
