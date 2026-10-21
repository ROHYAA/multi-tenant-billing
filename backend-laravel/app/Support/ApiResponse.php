<?php

namespace App\Support;

use Illuminate\Http\JsonResponse;
use Illuminate\Support\Carbon;

class ApiResponse
{
    public static function success(mixed $data = null, ?string $message = null, int $status = 200): JsonResponse
    {
        $payload = [
            'success' => true,
            'data' => $data,
            'timestamp' => Carbon::now('UTC')->toISOString(),
        ];
        if ($message !== null) {
            $payload['message'] = $message;
        }

        return response()->json($payload, $status);
    }

    public static function error(string $message, string $errorCode, int $status = 400, ?array $fieldErrors = null): JsonResponse
    {
        $payload = [
            'success' => false,
            'message' => $message,
            'errorCode' => $errorCode,
            'timestamp' => Carbon::now('UTC')->toISOString(),
        ];
        if ($fieldErrors !== null) {
            $payload['fieldErrors'] = $fieldErrors;
        }

        return response()->json($payload, $status);
    }

    public static function page(array $content, int $page, int $size, int $totalElements): array
    {
        $totalPages = $size > 0 ? (int) ceil($totalElements / $size) : 0;

        return [
            'content' => $content,
            'page' => $page,
            'size' => $size,
            'totalElements' => $totalElements,
            'totalPages' => $totalPages,
            'first' => $page === 0,
            'last' => $totalPages === 0 || $page >= $totalPages - 1,
            'empty' => $totalElements === 0,
            'hasNext' => $page < $totalPages - 1,
            'hasPrevious' => $page > 0,
        ];
    }
}
