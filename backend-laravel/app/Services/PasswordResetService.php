<?php

namespace App\Services;

use App\Exceptions\ApiException;
use App\Models\PasswordResetToken;
use App\Models\Shop;
use App\Models\User;
use Illuminate\Support\Facades\Cache;
use Illuminate\Support\Facades\Hash;
use Illuminate\Support\Facades\Log;
use Illuminate\Support\Facades\Mail;
use Illuminate\Support\Str;

class PasswordResetService
{
    public function request(string $tenantSlug, string $email): void
    {
        $shop = Shop::query()->where('slug', strtolower(trim($tenantSlug)))->first();
        if (! $shop) {
            throw ApiException::tenantNotFound();
        }

        $user = User::withoutGlobalScopes()
            ->where('shop_id', $shop->id)
            ->where('email', $email)
            ->where('deleted', false)
            ->first();

        if (! $user) {
            return;
        }

        PasswordResetToken::query()->where('shop_id', $shop->id)->where('email', $email)->delete();

        $raw = (string) Str::uuid();
        PasswordResetToken::query()->create([
            'shop_id' => $shop->id,
            'email' => $email,
            'token' => Hash::make($raw),
            'created_at' => now(),
        ]);

        if (app()->runningUnitTests()) {
            Cache::put('test.password_reset_token', $raw, 120);
        }

        $link = rtrim((string) env('FRONTEND_URL', 'http://localhost:4200'), '/')
            .'/reset-password?token='.$raw.'&slug='.$shop->slug;

        try {
            Mail::raw(
                "Reset your ShopLedger password: {$link}",
                function ($message) use ($user) {
                    $message->to($user->email)->subject('Reset your password');
                }
            );
        } catch (\Throwable $e) {
            Log::warning('Password reset mail failed: '.$e->getMessage());
        }
    }

    public function reset(string $tenantSlug, string $token, string $newPassword): void
    {
        $shop = Shop::query()->where('slug', strtolower(trim($tenantSlug)))->first();
        if (! $shop) {
            throw ApiException::tenantNotFound();
        }

        $rows = PasswordResetToken::query()->where('shop_id', $shop->id)->get();
        $match = $rows->first(fn ($row) => Hash::check($token, $row->token));
        if (! $match) {
            throw ApiException::resetTokenInvalid();
        }

        $ttl = (int) env('PASSWORD_RESET_TTL_MINUTES', 15);
        if ($match->created_at && $match->created_at->lt(now()->subMinutes($ttl))) {
            $match->delete();
            throw ApiException::resetTokenInvalid();
        }

        $user = User::withoutGlobalScopes()
            ->where('shop_id', $shop->id)
            ->where('email', $match->email)
            ->where('deleted', false)
            ->first();
        if (! $user) {
            throw ApiException::resetTokenInvalid();
        }

        $match->delete();
        $user->password = $newPassword;
        $user->token_version = ((int) $user->token_version) + 1;
        $user->save();
    }
}
