<?php

namespace App\Http\Controllers\Api\V1;

use App\Exceptions\ApiException;
use App\Http\Controllers\Controller;
use App\Models\Permission;
use App\Support\ApiResponse;
use App\Support\ShopContext;
use Illuminate\Http\Request;

class PermissionCatalogController extends Controller
{
    public function index()
    {
        $rows = Permission::query()->where('deleted', false)->orderBy('name')->get()
            ->map(fn (Permission $p) => [
                'id' => $p->id,
                'name' => $p->name,
                'description' => $p->description,
            ])->values()->all();

        return ApiResponse::success($rows, 'Permissions retrieved successfully');
    }

    public function show(int $id)
    {
        $p = Permission::query()->where('deleted', false)->find($id);
        if (! $p) {
            throw ApiException::notFound('Permission', $id);
        }

        return ApiResponse::success([
            'id' => $p->id,
            'name' => $p->name,
            'description' => $p->description,
        ], 'Permission retrieved successfully');
    }

    public function store(Request $request)
    {
        if (! ShopContext::isSuperAdmin()) {
            throw ApiException::accessDenied();
        }
        $data = $request->validate([
            'name' => ['required', 'string'],
            'description' => ['nullable', 'string'],
        ]);
        $p = Permission::query()->create([
            'name' => $data['name'],
            'description' => $data['description'] ?? null,
            'deleted' => false,
            'version' => 0,
        ]);

        return ApiResponse::success([
            'id' => $p->id,
            'name' => $p->name,
            'description' => $p->description,
        ], 'Permission created successfully', 201);
    }

    public function destroy(int $id)
    {
        if (! ShopContext::isSuperAdmin()) {
            throw ApiException::accessDenied();
        }
        $p = Permission::query()->find($id);
        if (! $p) {
            throw ApiException::notFound('Permission', $id);
        }
        $p->deleted = true;
        $p->deleted_at = now();
        $p->save();

        return ApiResponse::success(null, 'Permission deleted successfully');
    }
}
