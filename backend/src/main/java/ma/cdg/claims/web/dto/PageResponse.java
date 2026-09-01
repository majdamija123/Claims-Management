package ma.cdg.claims.web.dto;

import java.util.List;
import org.springframework.data.domain.Page;

/** Uniform pagination envelope so the Angular client only handles one shape. */
public record PageResponse<T>(List<T> content,
                              int page,
                              int size,
                              long totalElements,
                              int totalPages) {

    public static <E, T> PageResponse<T> of(Page<E> page, java.util.function.Function<E, T> mapper) {
        return new PageResponse<>(page.getContent().stream().map(mapper).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }

    public static <T> PageResponse<T> of(List<T> content, int page, int size, long total) {
        int totalPages = size == 0 ? 0 : (int) Math.ceil(total / (double) size);
        return new PageResponse<>(content, page, size, total, totalPages);
    }
}
