<?php

namespace App\Services;

use App\Models\PlatformAdmin;
use App\Models\User;
use Firebase\JWT\JWT;
use Firebase\JWT\Key;
use UnexpectedValueException;

class JwtTokenService
{
    public function signingKey(): string
    {
        $secret = (string) config('jwt.secret');
        $decoded = base64_decode($secret, true);
        if (is_string($decoded) && strlen($decoded) >= 32) {
            return $decoded;
        }
        if (strlen($secret) < 32) {
            throw new UnexpectedValueException('JWT_SECRET must be at least 32 characters or base64-encoded 256-bit key');
        }

        return $secret;
    }

    public function accessTtlSeconds(): int
    {
        return (int) (config('jwt.expiration_ms') / 1000);
    }

    public function refreshTtlSeconds(): int
    {
        return (int) (config('jwt.refresh_expiration_ms') / 1000);
    }

    public function generateAccessToken(User $user): string
    {
        $now = time();

        return JWT::encode([
            'sub' => (string) $user->id,
            'tenantId' => (int) $user->shop_id,
            'roleId' => $user->role_id ? (int) $user->role_id : null,
            'tokenVersion' => (int) $user->token_version,
            'typ' => 'ACCESS',
            'iss' => config('jwt.issuer'),
            'aud' => config('jwt.audience'),
            'iat' => $now,
            'exp' => $now + $this->accessTtlSeconds(),
        ], $this->signingKey(), 'HS256');
    }

    public function generateAdminAccessToken(PlatformAdmin $admin): string
    {
        $now = time();

        return JWT::encode([
            'sub' => (string) $admin->id,
            'email' => $admin->email,
            'isSuperAdmin' => true,
            'typ' => 'ACCESS',
            'iss' => config('jwt.issuer'),
            'aud' => config('jwt.audience'),
            'iat' => $now,
            'exp' => $now + $this->accessTtlSeconds(),
        ], $this->signingKey(), 'HS256');
    }

    public function decode(string $token): object
    {
        JWT::$leeway = 10;

        return JWT::decode($token, new Key($this->signingKey(), 'HS256'));
    }

    public function tryDecode(string $token): ?object
    {
        try {
            return $this->decode($token);
        } catch (\Throwable) {
            return null;
        }
    }
}
