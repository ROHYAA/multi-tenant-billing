<?php

namespace App\Console\Commands;

use App\Migration\DataMigrator;
use App\Migration\SnapshotReader;
use Illuminate\Console\Command;
use Illuminate\Support\Facades\File;

class MigratePostgresDataCommand extends Command
{
    protected $signature = 'shopledger:migrate-data
        {--dry-run : Read source and report without writing the target}
        {--shop= : Limit to one shop id, slug, or schema_name}
        {--include-admins : Copy platform_admins except admin@platform.com}
        {--allow-existing : Permit idempotent upserts when target already has shops}
        {--copy-files : Copy attachment bytes from PG_ATTACHMENTS_PATH}
        {--report= : Write JSON report to this path}';

    protected $description = 'Copy a read-only PostgreSQL/ShopLedger snapshot into the Laravel MySQL (or test) database.';

    public function handle(): int
    {
        $dry = (bool) $this->option('dry-run');
        if (! $dry && ! env('PG_SOURCE_URL') && config('database.connections.pgsql_snapshot.driver') === 'pgsql') {
            $this->error('PG_SOURCE_URL is required for a live copy.');

            return self::FAILURE;
        }

        $this->info($dry ? 'Dry-run: no target writes.' : 'Writing to the Laravel database only. PostgreSQL is not modified.');

        $migrator = new DataMigrator(new SnapshotReader);
        $report = $migrator->run([
            'dry_run' => $dry,
            'shop' => $this->option('shop') ?: null,
            'include_admins' => (bool) $this->option('include-admins'),
            'allow_existing' => (bool) $this->option('allow-existing'),
            'copy_files' => (bool) $this->option('copy-files'),
            'attachments_path' => env('PG_ATTACHMENTS_PATH'),
        ]);

        $path = $this->option('report') ?: storage_path('logs/migration-report-'.now()->format('Ymd-His').'.json');
        File::ensureDirectoryExists(dirname($path));
        File::put($path, $report->toJson());
        $this->line($report->toJson());
        $this->info('Report: '.$path);

        if (! $report->payload['ok']) {
            $this->error('Discrepancies remain. Do not proceed to cutover.');

            return self::FAILURE;
        }

        return self::SUCCESS;
    }
}
