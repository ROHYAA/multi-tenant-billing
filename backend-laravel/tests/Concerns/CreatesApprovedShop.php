<?php

namespace Tests\Concerns;

use App\Models\Shop;

trait CreatesApprovedShop
{
    protected function approvedOwnerToken(string $email = 'owner@example.com', string $name = 'Rohit Store'): string
    {
        $this->postJson('/api/v1/auth/signup', [
            'name' => $name,
            'email' => $email,
            'password' => 'Secret123',
        ])->assertCreated();

        Shop::query()->where('owner_email', $email)->update(['status' => 'ACTIVE']);

        $slug = explode('@', $email)[0];
        $slug = strtolower(preg_replace('/[^a-zA-Z0-9]/', '', $slug) ?: 'shop');
        if (strlen($slug) < 2) {
            $slug = str_pad($slug, 2, 'x');
        }
        $slug = substr($slug, 0, 20);

        $login = $this->postJson('/api/v1/auth/login', [
            'email' => $email,
            'password' => 'Secret123',
            'tenantSlug' => $slug,
        ]);
        $login->assertOk();

        return (string) $login->getCookie('access_token', false)?->getValue();
    }

    protected function authJson(string $method, string $uri, array $data, string $token)
    {
        return $this->withHeader('Authorization', 'Bearer '.$token)
            ->json($method, $uri, $data);
    }
}
