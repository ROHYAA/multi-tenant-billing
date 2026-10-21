<?php

namespace App\Migration;

class IdMap
{
    /** @var array<string, array<int, array<int, int>>> table => shopId => sourceId => newId */
    private array $map = [];

    public function put(string $table, int $shopId, int $sourceId, int $newId): void
    {
        $this->map[$table][$shopId][$sourceId] = $newId;
    }

    public function get(string $table, int $shopId, ?int $sourceId): ?int
    {
        if ($sourceId === null) {
            return null;
        }

        return $this->map[$table][$shopId][$sourceId] ?? null;
    }

    public function require(string $table, int $shopId, int $sourceId): int
    {
        $id = $this->get($table, $shopId, $sourceId);
        if ($id === null) {
            throw new \RuntimeException("Missing id map {$table} shop={$shopId} source={$sourceId}");
        }

        return $id;
    }
}
