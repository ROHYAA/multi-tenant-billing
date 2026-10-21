<?php

namespace App\Models\Concerns;

use Illuminate\Database\Eloquent\Builder;
use Illuminate\Database\Eloquent\SoftDeletes;

trait Auditable
{
    use SoftDeletes;

    public static function bootAuditable(): void
    {
        static::creating(function ($model) {
            if (! isset($model->deleted)) {
                $model->deleted = false;
            }
            if (! isset($model->version)) {
                $model->version = 0;
            }
        });

        static::updating(function ($model) {
            $model->version = ((int) $model->version) + 1;
        });

        static::deleting(function ($model) {
            $model->deleted = true;
        });
    }

    public function initializeAuditable(): void
    {
        $this->casts = array_merge($this->casts ?? [], [
            'deleted' => 'boolean',
            'deleted_at' => 'datetime',
            'created_at' => 'datetime',
            'updated_at' => 'datetime',
        ]);
    }

    public function scopeNotDeleted(Builder $query): Builder
    {
        return $query->where($this->getTable().'.deleted', false);
    }
}
