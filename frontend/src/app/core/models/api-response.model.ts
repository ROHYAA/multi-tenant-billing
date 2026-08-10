/**
 * Mirrors com.mtbs.shared.dto.common.ApiResponse<T> — every backend endpoint
 * responds with this envelope. ApiResponseInterceptor unwraps `.data` for
 * success responses; on failure it throws an ApiError built from the same
 * shape, so components never see the envelope directly.
 */
export interface ApiResponseEnvelope<T> {
  success: boolean;
  message?: string;
  data?: T;
  errorCode?: string;
  timestamp: string;
  fieldErrors?: Record<string, string>;
}

/** Mirrors com.mtbs.shared.dto.common.PageResponse<T>. */
export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
  empty: boolean;
  hasNext: boolean;
  hasPrevious: boolean;
}

/** Thrown by ApiClient on any non-success response — components catch this, not the raw HttpErrorResponse. */
export class ApiError extends Error {
  readonly errorCode?: string;
  readonly fieldErrors?: Record<string, string>;
  readonly httpStatus: number;

  constructor(message: string, httpStatus: number, errorCode?: string, fieldErrors?: Record<string, string>) {
    super(message);
    this.name = 'ApiError';
    this.httpStatus = httpStatus;
    this.errorCode = errorCode;
    this.fieldErrors = fieldErrors;
  }
}
