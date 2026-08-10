import { HttpErrorResponse, HttpEventType, HttpInterceptorFn, HttpResponse } from '@angular/common/http';
import { catchError, map, throwError } from 'rxjs';
import { ApiError, ApiResponseEnvelope } from '../models/api-response.model';

/**
 * Unwraps the backend's ApiResponse<T> envelope so nothing downstream of
 * this interceptor ever sees `.data`/`.success`/`.errorCode` directly:
 * success responses are replaced with just the payload, failures are thrown
 * as a typed ApiError. Runs as the outermost interceptor (see app.config.ts)
 * so it only converts the FINAL outcome of the auth interceptor's retry
 * logic, not an intermediate 401 that's about to be retried.
 */
export const apiResponseInterceptor: HttpInterceptorFn = (req, next) => {
  return next(req).pipe(
    map((event) => {
      if (event.type === HttpEventType.Response && event instanceof HttpResponse) {
        const body = event.body as ApiResponseEnvelope<unknown> | null;
        // Non-JSON responses (e.g. blob PDF downloads) pass through untouched.
        if (body && typeof body === 'object' && 'success' in body) {
          return event.clone({ body: body.data ?? null });
        }
      }
      return event;
    }),
    catchError((error: HttpErrorResponse) => {
      const body = error.error as ApiResponseEnvelope<unknown> | null;
      if (body && typeof body === 'object' && 'message' in body) {
        return throwError(() => new ApiError(
          body.message ?? 'Something went wrong',
          error.status,
          body.errorCode,
          body.fieldErrors,
        ));
      }
      // Network failure / non-JSON error body (e.g. the backend is unreachable).
      return throwError(() => new ApiError(
        error.status === 0 ? 'Could not reach the server' : 'Something went wrong',
        error.status,
      ));
    }),
  );
};
