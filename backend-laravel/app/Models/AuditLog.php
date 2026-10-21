<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;

class AuditLog extends Model
{
    protected $guarded = ['id'];

    protected function casts(): array
    {
        return [
            'changes_before' => 'array',
            'changes_after' => 'array',
            'changes_summary' => 'array',
            'context_metadata' => 'array',
            'deleted' => 'boolean',
        ];
    }
}
