<?php

namespace App\Support;

use Illuminate\Http\Request;

class NativeClient
{
    public static function isNative(?Request $request = null): bool
    {
        $request ??= request();
        $header = strtolower(trim((string) $request->header('X-ShopLedger-Client', '')));

        return in_array($header, ['capacitor', 'native'], true);
    }

    /** @param  array<string, mixed>  $payload */
    public static function withTokens(array $payload, string $access, string $refresh): array
    {
        if (! self::isNative()) {
            return $payload;
        }

        $payload['data']['tokens'] = [
            'accessToken' => $access,
            'refreshToken' => $refresh,
            'tokenType' => 'Bearer',
        ];

        return $payload;
    }
}
