package com.mtbs.shared.dto.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * Standardised paginated response wrapper.
 *
 * Wraps Spring's {@link Page} into a clean JSON shape that doesn't expose
 * Spring internals to API clients. All paginated endpoints should return
 * {@code ApiResponse<PageResponse<T>>} instead of {@code ApiResponse<Page<T>>}.
 *
 * JSON shape:
 * {
 *   "content": [...],
 *   "page": 0,
 *   "size": 10,
 *   "totalElements": 42,
 *   "totalPages": 5,
 *   "first": true,
 *   "last": false,
 *   "empty": false,
 *   "hasNext": false,
 *   "hasPrevious": false
 * }
 *
 * USAGE in controllers (with mapper):
 *   Page<User> page = userService.getAllUsers(pageable);
 *   return ResponseEntity.ok(ApiResponse.success(PageResponse.of(page, userMapper::toResponse)));
 *
 * USAGE in controllers (already mapped):
 *   Page<UserResponse> page = userService.getAllUsers(pageable);
 *   return ResponseEntity.ok(ApiResponse.success(PageResponse.of(page)));
 *
 * USAGE with manual construction (for native queries):
 *   return PageResponse.<UserResponse>builder()
 *       .content(list)
 *       .page(pageable.getPageNumber())
 *       .size(pageable.getPageSize())
 *       .totalElements(total)
 *       .totalPages((int) Math.ceil((double) total / pageable.getPageSize()))
 *       .build();
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PageResponse<T> {

    private List<T> content;

    private int page;
    private int size;
    private long totalElements;
    private int totalPages;

    private boolean first;
    private boolean last;
    private boolean empty;

    /** True when there is a page after the current one. Useful for next-page buttons. */
    private boolean hasNext;

    /** True when there is a page before the current one. Useful for prev-page buttons. */
    private boolean hasPrevious;

    /**
     * Convenience factory — wraps a Spring {@link Page} directly.
     * The content list is already mapped by the caller.
     *
     * @param springPage the Spring Page result from a repository query
     * @param <T>        the DTO type (NOT the entity type)
     */
    public static <T> PageResponse<T> of(Page<T> springPage) {
        return PageResponse.<T>builder()
                .content(springPage.getContent())
                .page(springPage.getNumber())
                .size(springPage.getSize())
                .totalElements(springPage.getTotalElements())
                .totalPages(springPage.getTotalPages())
                .first(springPage.isFirst())
                .last(springPage.isLast())
                .empty(springPage.isEmpty())
                .hasNext(springPage.hasNext())
                .hasPrevious(springPage.hasPrevious())
                .build();
    }

    /**
     * Convenience factory — maps entity page to DTO page inline.
     * Avoids calling .map() on the Spring Page before wrapping.
     *
     * Example:
     *   Page<User> users = userRepository.findAll(pageable);
     *   PageResponse<UserResponse> response =
     *       PageResponse.of(users, userMapper::toResponse);
     *
     * @param springPage the Spring Page of entities
     * @param mapper     function to convert each entity to a DTO
     * @param <E>        entity type
     * @param <T>        DTO type
     */
    public static <E, T> PageResponse<T> of(Page<E> springPage, Function<E, T> mapper) {
        return of(springPage.map(mapper));
    }
}