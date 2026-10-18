package cn.edu.njupt.kms.common;

import lombok.Data;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * 分页结果包装，屏蔽 Spring Data {@link Page} 的字段冗余。
 *
 * @param <T> 列表元素类型
 */
@Data
public class PageResult<T> {

    private List<T> list;
    /** 总记录数 */
    private long total;
    /** 当前页码，从 1 开始 */
    private int page;
    /** 每页条数 */
    private int size;
    /** 总页数 */
    private int pages;

    public static <T> PageResult<T> of(List<T> list, long total, int page, int size) {
        PageResult<T> r = new PageResult<>();
        r.list = list;
        r.total = total;
        r.page = page;
        r.size = size;
        r.pages = size <= 0 ? 0 : (int) Math.ceil((double) total / size);
        return r;
    }

    /** 把 Spring Data 的 {@link Page} 转换为本包装，并对元素做映射。 */
    public static <E, T> PageResult<T> of(Page<E> p, Function<E, T> mapper) {
        return of(p.getContent().stream().map(mapper).toList(),
                p.getTotalElements(), p.getNumber() + 1, p.getSize());
    }

    public static <T> PageResult<T> empty(int page, int size) {
        return of(List.of(), 0, page, size);
    }
}
