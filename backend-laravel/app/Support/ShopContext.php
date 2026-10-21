<?php

namespace App\Support;

use App\Models\PlatformAdmin;
use App\Models\Shop;
use App\Models\User;

class ShopContext
{
    private static ?int $shopId = null;

    private static ?User $user = null;

    private static ?PlatformAdmin $admin = null;

    private static array $permissions = [];

    public static function setUser(User $user, array $permissions): void
    {
        self::$user = $user;
        self::$shopId = (int) $user->shop_id;
        self::$permissions = $permissions;
        self::$admin = null;
    }

    public static function setAdmin(PlatformAdmin $admin): void
    {
        self::$admin = $admin;
        self::$user = null;
        self::$shopId = null;
        self::$permissions = ['SUPER_ADMIN'];
    }

    public static function clear(): void
    {
        self::$shopId = null;
        self::$user = null;
        self::$admin = null;
        self::$permissions = [];
    }

    public static function shopId(): ?int
    {
        return self::$shopId;
    }

    public static function requireShopId(): int
    {
        if (self::$shopId === null) {
            throw new \RuntimeException('No shop context');
        }

        return self::$shopId;
    }

    public static function user(): ?User
    {
        return self::$user;
    }

    public static function admin(): ?PlatformAdmin
    {
        return self::$admin;
    }

    public static function isSuperAdmin(): bool
    {
        return self::$admin !== null;
    }

    public static function permissions(): array
    {
        return self::$permissions;
    }

    public static function hasPermission(string $name): bool
    {
        return in_array($name, self::$permissions, true);
    }

    public static function shop(): ?Shop
    {
        if (self::$shopId === null) {
            return null;
        }

        return Shop::query()->find(self::$shopId);
    }
}
