package cn.edu.njupt.kms.module.kb.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.io.Serializable;
import java.util.Objects;

/** 知识条目 ↔ 标签 关联。 */
@Data
@Entity
@Table(name = "kb_item_tag")
@IdClass(ItemTagLink.Key.class)
public class ItemTagLink {

    @Id
    @Column(name = "item_id")
    private Long itemId;

    @Id
    @Column(name = "tag_id")
    private Long tagId;

    public ItemTagLink() {
    }

    public ItemTagLink(Long itemId, Long tagId) {
        this.itemId = itemId;
        this.tagId = tagId;
    }

    @Data
    @Embeddable
    public static class Key implements Serializable {
        private Long itemId;
        private Long tagId;

        public Key() {
        }

        public Key(Long itemId, Long tagId) {
            this.itemId = itemId;
            this.tagId = tagId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Key k)) {
                return false;
            }
            return Objects.equals(itemId, k.itemId) && Objects.equals(tagId, k.tagId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(itemId, tagId);
        }
    }
}
