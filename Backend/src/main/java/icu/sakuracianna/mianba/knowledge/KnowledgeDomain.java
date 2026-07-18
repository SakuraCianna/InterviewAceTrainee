package icu.sakuracianna.mianba.knowledge;

/** 公共知识库的强制隔离领域。 */
public enum KnowledgeDomain {
    JOB("job"),
    POSTGRADUATE("postgraduate");

    private final String code;

    KnowledgeDomain(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
