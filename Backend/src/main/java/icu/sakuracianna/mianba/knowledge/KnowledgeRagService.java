package icu.sakuracianna.mianba.knowledge;

import java.util.List;

/** 只读公共知识检索；查询文本只用于当前内存中的向量计算。 */
public interface KnowledgeRagService {
    List<KnowledgeSnippet> retrieve(String query, KnowledgeDomain domain);
}
