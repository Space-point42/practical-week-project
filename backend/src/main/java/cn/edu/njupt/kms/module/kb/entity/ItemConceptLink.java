package cn.edu.njupt.kms.module.kb.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * 知识条目 ↔ 本体概念 的标注关联。
 *
 * <p>这是“知识库”与“本体”两个模块的接合点：
 * 有了它，才能从概念反查知识、计算本体覆盖率、并支撑基于本体的检索扩展。</p>
 */
@Data
@Entity
@Table(name = "kb_item_concept")
@IdClass(ItemConceptLink.Key.class)
public class ItemConceptLink {

    @Id
    @Column(name = "item_id")
    private Long itemId;

    @Id
    @Column(name = "concept_id")
    private Long conceptId;

    /** MANUAL=人工标注，AUTO=依据别名匹配自动抽取 */
    @Column(nullable = false, length = 16)
    private String source = "MANUAL";

    @Column(nullable = false, precision = 4, scale = 2)
    private BigDecimal confidence = BigDecimal.ONE;

    /** 复合主键。 */
    @Data
    @Embeddable
    public static class Key implements Serializable {
        private Long itemId;
        private Long conceptId;

        public Key() {
        }

        public Key(Long itemId, Long conceptId) {
            this.itemId = itemId;
            this.conceptId = conceptId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Key k)) {
                return false;
            }
            return Objects.equals(itemId, k.itemId) && Objects.equals(conceptId, k.conceptId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(itemId, conceptId);
        }
    }
}
