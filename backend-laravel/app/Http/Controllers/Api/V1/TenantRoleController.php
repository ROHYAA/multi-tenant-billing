<?php

namespace App\Http\Controllers\Api\V1;

use App\Http\Controllers\Controller;
use App\Services\TenantRoleService;
use App\Support\ApiResponse;
use Illuminate\Http\Request;

class TenantRoleController extends Controller
{
    public function __construct(private TenantRoleService $roles) {}

    public function index()
    {
        return ApiResponse::success($this->roles->all(), 'Roles retrieved successfully');
    }

    public function show(int $id)
    {
        return ApiResponse::success($this->roles->get($id), 'Role retrieved successfully');
    }

    public function store(Request $request)
    {
        $data = $request->validate(['name' => ['required', 'string', 'max:100']]);

        return ApiResponse::success($this->roles->create($data), 'Role created successfully', 201);
    }

    public function update(Request $request, int $id)
    {
        $data = $request->validate(['name' => ['sometimes', 'string', 'max:100']]);

        return ApiResponse::success($this->roles->update($id, $data), 'Role updated successfully');
    }

    public function destroy(int $id)
    {
        $this->roles->delete($id);

        return ApiResponse::success(null, 'Role deleted successfully');
    }

    public function permissions(int $id)
    {
        return ApiResponse::success($this->roles->permissions($id), 'Role permissions retrieved successfully');
    }

    public function assignPermission(Request $request, int $id)
    {
        $data = $request->validate(['permissionId' => ['required', 'integer']]);

        return ApiResponse::success($this->roles->assign($id, (int) $data['permissionId']), 'Permission assigned to role successfully', 201);
    }

    public function removePermission(int $id, int $permId)
    {
        $this->roles->removePermission($id, $permId);

        return ApiResponse::success(null, 'Permission removed from role successfully');
    }
}
