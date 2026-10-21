<?php

namespace App\Services;

use Illuminate\Http\Request;
use Symfony\Component\HttpFoundation\Cookie;

class AuthCookieService
{
    public const ACCESS = 'access_token';

    public const REFRESH = 'refresh_token';

    public const ACCESS_PATH = '/';

    public const REFRESH_PATH = '/api/v1/auth/refresh';

    public const ADMIN_REFRESH_PATH = '/api/v1/admin/auth/refresh';

    public function __construct(private JwtTokenService $jwt) {}

    public function addTenantCookies($response, string $access, string $refresh)
    {
        return $this->attach($response, $access, $refresh, self::REFRESH_PATH);
    }

    public function addAdminCookies($response, string $access, string $refresh)
    {
        return $this->attach($response, $access, $refresh, self::ADMIN_REFRESH_PATH);
    }

    public function clear($response)
    {
        foreach ([self::ACCESS_PATH, self::REFRESH_PATH, self::ADMIN_REFRESH_PATH] as $path) {
            $name = $path === self::ACCESS_PATH ? self::ACCESS : self::REFRESH;
            $response->headers->setCookie($this->forget($name, $path));
        }

        return $response;
    }

    public function accessToken(Request $request): ?string
    {
        $header = $request->header('Authorization', '');
        if (str_starts_with($header, 'Bearer ')) {
            $bearer = substr($header, 7);

            return $bearer !== '' ? $bearer : null;
        }
        $cookie = $request->cookie(self::ACCESS);

        return is_string($cookie) && $cookie !== '' ? $cookie : null;
    }

    public function refreshToken(Request $request): ?string
    {
        $body = $request->input('refreshToken');
        if (is_string($body) && $body !== '') {
            return $body;
        }
        $cookie = $request->cookie(self::REFRESH);

        return is_string($cookie) && $cookie !== '' ? $cookie : null;
    }

    private function attach($response, string $access, string $refresh, string $refreshPath)
    {
        $secure = (bool) config('jwt.cookie_secure');
        $response->headers->setCookie(new Cookie(
            self::ACCESS,
            $access,
            now()->addSeconds($this->jwt->accessTtlSeconds()),
            self::ACCESS_PATH,
            null,
            $secure,
            true,
            false,
            Cookie::SAMESITE_LAX
        ));
        $response->headers->setCookie(new Cookie(
            self::REFRESH,
            $refresh,
            now()->addSeconds($this->jwt->refreshTtlSeconds()),
            $refreshPath,
            null,
            $secure,
            true,
            false,
            Cookie::SAMESITE_LAX
        ));

        return $response;
    }

    private function forget(string $name, string $path): Cookie
    {
        return new Cookie(
            $name,
            '',
            now()->subHour(),
            $path,
            null,
            (bool) config('jwt.cookie_secure'),
            true,
            false,
            Cookie::SAMESITE_LAX
        );
    }
}
