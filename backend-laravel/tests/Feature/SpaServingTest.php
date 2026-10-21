<?php

namespace Tests\Feature;

use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Facades\File;
use Tests\TestCase;

class SpaServingTest extends TestCase
{
    use RefreshDatabase;

    public function test_root_is_welcome_when_spa_is_absent(): void
    {
        $spa = public_path('spa/index.html');
        $backup = null;
        if (is_file($spa)) {
            $backup = File::get($spa);
            File::delete($spa);
        }

        try {
            $this->get('/')->assertOk()->assertSee('Laravel', false);
        } finally {
            if ($backup !== null) {
                File::put($spa, $backup);
            }
        }
    }

    public function test_root_and_client_route_serve_spa_when_present(): void
    {
        $dir = public_path('spa');
        File::ensureDirectoryExists($dir);
        $spa = $dir.'/index.html';
        $backup = is_file($spa) ? File::get($spa) : null;
        File::put($spa, '<html><body>shopledger-spa</body></html>');

        try {
            $this->get('/')->assertOk()->assertSee('shopledger-spa', false);
            $this->get('/login')->assertOk()->assertSee('shopledger-spa', false);
            $this->get('/api/v1/health')->assertOk()->assertJsonPath('success', true);
        } finally {
            if ($backup !== null) {
                File::put($spa, $backup);
            } else {
                File::delete($spa);
            }
        }
    }
}
