<?php

namespace App\Services;

use App\Exceptions\ApiException;
use Illuminate\Support\Facades\Cache;

class LoginRateLimiter
{
    public function checkBlocked(string $ip): void
    {
        $max = (int) config('shopledger.login_max_attempts', 5);
        if ($this->failures($ip) >= $max) {
            throw ApiException::tooManyRequests();
        }
    }

    public function recordFailure(string $ip): void
    {
        $window = (int) config('shopledger.login_window_minutes', 15);
        $key = $this->key($ip);
        $count = $this->failures($ip) + 1;
        Cache::put($key, $count, now()->addMinutes($window));
        $max = (int) config('shopledger.login_max_attempts', 5);
        if ($count >= $max) {
            throw ApiException::tooManyRequests();
        }
    }

    public function clear(string $ip): void
    {
        Cache::forget($this->key($ip));
    }

    private function failures(string $ip): int
    {
        return (int) Cache::get($this->key($ip), 0);
    }

    private function key(string $ip): string
    {
        return 'login_fail:'.$ip;
    }
}
