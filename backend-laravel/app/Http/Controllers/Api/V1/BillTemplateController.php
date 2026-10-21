<?php

namespace App\Http\Controllers\Api\V1;

use App\Http\Controllers\Controller;
use App\Models\BillTemplate;
use App\Support\ApiResponse;

class BillTemplateController extends Controller
{
    public function index()
    {
        $rows = BillTemplate::query()->where('is_active', true)->where('deleted', false)->orderBy('id')->get()
            ->map(fn ($t) => [
                'id' => $t->id,
                'code' => $t->code,
                'name' => $t->name,
                'description' => $t->description,
            ])->values()->all();

        return ApiResponse::success($rows, 'Bill templates fetched successfully');
    }
}
