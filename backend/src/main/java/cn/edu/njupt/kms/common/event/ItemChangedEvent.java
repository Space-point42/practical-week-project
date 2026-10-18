package cn.edu.njupt.kms.common.event;

/**
 * 知识条目内容变更事件。
 *
 * <p>用于解耦「知识库/CMS」与「RAG 索引」两个模块：
 * 条目保存、发布、回滚、归档时发布该事件，
 * RAG 模块异步监听并重建该条目的检索分块与向量。</p>
 *
 * @param itemId    条目 id
 * @param operation 变更类型：SAVED / PUBLISHED / ROLLED_BACK / ARCHIVED / DELETED
 */
public record ItemChangedEvent(Long itemId, String operation) {

    public static final String SAVED = "SAVED";
    public static final String PUBLISHED = "PUBLISHED";
    public static final String ROLLED_BACK = "ROLLED_BACK";
    public static final String ARCHIVED = "ARCHIVED";
    public static final String DELETED = "DELETED";

    public static ItemChangedEvent saved(Long itemId) {
        return new ItemChangedEvent(itemId, SAVED);
    }

    public static ItemChangedEvent published(Long itemId) {
        return new ItemChangedEvent(itemId, PUBLISHED);
    }

    public static ItemChangedEvent rolledBack(Long itemId) {
        return new ItemChangedEvent(itemId, ROLLED_BACK);
    }

    public static ItemChangedEvent archived(Long itemId) {
        return new ItemChangedEvent(itemId, ARCHIVED);
    }

    public static ItemChangedEvent deleted(Long itemId) {
        return new ItemChangedEvent(itemId, DELETED);
    }
}
