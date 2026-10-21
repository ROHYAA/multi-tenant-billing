<?php

namespace App\Support;

use Illuminate\Database\Eloquent\Builder;
use Illuminate\Http\Request;

class SpringPage
{
    public static function paginate(Builder $query, Request $request, string $defaultSort = 'created_at,desc'): array
    {
        $page = max(0, (int) $request->query('page', 0));
        $size = max(1, min(100, (int) $request->query('size', 20)));
        $sort = (string) $request->query('sort', $defaultSort);
        [$col, $dir] = array_pad(explode(',', $sort, 2), 2, 'desc');
        $col = match ($col) {
            'createdAt', 'created_at' => 'created_at',
            'updatedAt', 'updated_at' => 'updated_at',
            'name' => 'name',
            'status' => 'status',
            'email' => 'email',
            default => 'created_at',
        };
        $dir = strtolower($dir) === 'asc' ? 'asc' : 'desc';

        $total = (clone $query)->count();
        $rows = $query->orderBy($col, $dir)->skip($page * $size)->take($size)->get();

        return [$rows, $page, $size, $total];
    }
}
