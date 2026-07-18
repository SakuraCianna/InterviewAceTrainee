package icu.sakuracianna.mianba.interview.material;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/** 当前 HTTP 请求内的临时检索文本；关闭后字符会被覆盖且不能再次读取。 */
public final class EphemeralMaterial implements AutoCloseable {
    private final String interviewType;
    private final char[] retrievalQuery;
    private final AtomicBoolean closed = new AtomicBoolean();

    public EphemeralMaterial(String interviewType, char[] retrievalQuery) {
        this.interviewType = interviewType;
        this.retrievalQuery = Arrays.copyOf(retrievalQuery, retrievalQuery.length);
        Arrays.fill(retrievalQuery, '\0');
    }

    public String interviewType() {
        requireOpen();
        return interviewType;
    }

    /** 创建一个只在调用栈内短暂存在的查询字符串，调用方不得保存。 */
    public String retrievalQuery() {
        requireOpen();
        return new String(retrievalQuery);
    }

    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            Arrays.fill(retrievalQuery, '\0');
        }
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Ephemeral material has already been cleared");
        }
    }
}
