<?php

namespace App\Migration;

use Illuminate\Support\Facades\DB;
use RuntimeException;

/**
 * Read-only snapshot of the Java/Postgres (or sqlite fixture) source.
 * Never issues INSERT/UPDATE/DELETE against the source connection.
 */
class SnapshotReader
{
    public function __construct(private readonly string $connection = 'pgsql_snapshot') {}

    public function driver(): string
    {
        return (string) config("database.connections.{$this->connection}.driver");
    }

    /** @return list<object> */
    public function publicRows(string $table): array
    {
        $this->assertSafeTable($table);
        if ($this->driver() === 'pgsql') {
            return $this->select('SELECT * FROM public.'.$this->ident($table).' ORDER BY id');
        }

        $name = 'public_'.$table;
        if (! $this->sqliteTableExists($name)) {
            return [];
        }

        return $this->select('SELECT * FROM '.$this->ident($name).' ORDER BY id');
    }

    /** @return list<object> */
    public function tenantRows(string $schema, string $table): array
    {
        $this->assertSafeSchema($schema);
        $this->assertSafeTable($table);
        if ($this->driver() === 'pgsql') {
            $this->setSearchPath($schema);

            return $this->select('SELECT * FROM '.$this->ident($table).' ORDER BY id');
        }

        $name = $this->sqliteTenantTable($schema, $table);
        if (! $this->sqliteTableExists($name)) {
            return [];
        }

        return $this->select('SELECT * FROM '.$this->ident($name).' ORDER BY id');
    }

    public function tenantCount(string $schema, string $table): int
    {
        return count($this->tenantRows($schema, $table));
    }

    /** @return list<object> */
    public function executeRead(string $sql, array $bindings = []): array
    {
        return $this->select($sql, $bindings);
    }

    public function sqliteTenantTable(string $schema, string $table): string
    {
        return 't_'.$schema.'__'.$table;
    }

    private function setSearchPath(string $schema): void
    {
        $this->raw('SET search_path TO '.$this->ident($schema).', public');
    }

    /** @return list<object> */
    private function select(string $sql, array $bindings = []): array
    {
        $this->assertReadOnly($sql);

        return DB::connection($this->connection)->select($sql, $bindings);
    }

    private function raw(string $sql): void
    {
        $this->assertReadOnly($sql);
        DB::connection($this->connection)->unprepared($sql);
    }

    private function assertReadOnly(string $sql): void
    {
        if (! preg_match('/^\s*(SELECT|SET\s+search_path)\b/i', $sql)) {
            throw new RuntimeException('Source snapshot is read-only; refused: '.$sql);
        }
    }

    private function assertSafeTable(string $name): void
    {
        if (! preg_match('/^[a-z][a-z0-9_]*$/i', $name)) {
            throw new RuntimeException('Invalid table name');
        }
    }

    private function assertSafeSchema(string $name): void
    {
        if (! preg_match('/^[a-z][a-z0-9_]*$/i', $name)) {
            throw new RuntimeException('Invalid schema name');
        }
    }

    private function ident(string $name): string
    {
        $this->assertSafeTable($name);
        $driver = $this->driver();
        if ($driver === 'pgsql') {
            return '"'.$name.'"';
        }

        return '"'.$name.'"';
    }

    private function sqliteTableExists(string $name): bool
    {
        $row = DB::connection($this->connection)->select(
            "SELECT name FROM sqlite_master WHERE type='table' AND name = ?",
            [$name]
        );

        return $row !== [];
    }
}
