<?php

namespace Tests\Feature;

use Database\Seeders\DatabaseSeeder;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Hash;
use Tests\Support\SqliteSnapshotFixture;
use Tests\TestCase;

class DataMigrationTest extends TestCase
{
    use RefreshDatabase;

    private string $hash;

    protected function setUp(): void
    {
        parent::setUp();
        $this->seed(DatabaseSeeder::class);
        $this->hash = Hash::make('Secret123');
        SqliteSnapshotFixture::connect();
        SqliteSnapshotFixture::installTwoCollidingShops($this->hash);
    }

    public function test_dry_run_does_not_write_target_or_source(): void
    {
        $this->artisan('shopledger:migrate-data', ['--dry-run' => true])
            ->assertSuccessful();

        $this->assertSame(0, DB::table('shops')->count());
        $this->assertSame(0, DB::table('customers')->count());
        $this->assertSame(2, DB::connection('pgsql_snapshot')->table('public_shops')->count());
        $this->assertSame(1, DB::connection('pgsql_snapshot')->table('t_schema_a__customers')->count());
    }

    public function test_copy_remaps_colliding_source_ids_and_preserves_money_and_hashes(): void
    {
        $this->artisan('shopledger:migrate-data')->assertSuccessful();

        $this->assertSame(2, DB::table('shops')->count());
        $this->assertTrue(DB::table('shops')->where('id', 10)->exists());
        $this->assertTrue(DB::table('shops')->where('id', 20)->exists());

        $customers = DB::table('customers')->orderBy('shop_id')->get();
        $this->assertCount(2, $customers);
        $this->assertNotSame($customers[0]->id, $customers[1]->id);
        $this->assertSame(1, (int) $customers[0]->source_id);
        $this->assertSame(1, (int) $customers[1]->source_id);

        $this->assertSame(2, DB::table('bills')->where('invoice_number', 'INV-0001')->count());
        $this->assertEquals('200.00', number_format((float) DB::table('bills')->sum('total_amount'), 2, '.', ''));
        $this->assertEquals('80.00', number_format((float) DB::table('payments')->sum('amount'), 2, '.', ''));
        $this->assertSame(1, (int) DB::table('number_series')->where('shop_id', 10)->value('current_number'));

        $copiedHash = DB::table('users')->where('shop_id', 10)->value('password');
        $this->assertSame($this->hash, $copiedHash);

        $this->assertSame(1, DB::connection('pgsql_snapshot')->table('t_schema_a__customers')->count());
        $this->artisan('shopledger:migrate-data', ['--allow-existing' => true])->assertSuccessful();
        $this->assertSame(2, DB::table('customers')->count());
    }

    public function test_source_refuse_non_select(): void
    {
        $this->expectException(\RuntimeException::class);
        (new \App\Migration\SnapshotReader)->executeRead('DELETE FROM public_shops');
    }
}
