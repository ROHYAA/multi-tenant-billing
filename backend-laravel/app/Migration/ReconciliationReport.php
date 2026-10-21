<?php

namespace App\Migration;

class ReconciliationReport
{
    /** @var array<string, mixed> */
    public array $payload = [
        'ok' => true,
        'dry_run' => true,
        'shops' => [],
        'skipped' => [],
        'discrepancies' => [],
        'file_copies' => [],
        'warnings' => [],
    ];

    public function addDiscrepancy(string $message): void
    {
        $this->payload['discrepancies'][] = $message;
        $this->payload['ok'] = false;
    }

    public function warn(string $message): void
    {
        $this->payload['warnings'][] = $message;
    }

    public function skip(string $message): void
    {
        $this->payload['skipped'][] = $message;
    }

    public function toJson(): string
    {
        return json_encode($this->payload, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES);
    }
}
