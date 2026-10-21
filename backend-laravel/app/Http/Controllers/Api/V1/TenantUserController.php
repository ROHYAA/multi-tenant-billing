<?php

namespace App\Http\Controllers\Api\V1;

use App\Http\Controllers\Controller;
use App\Services\TenantUserService;
use App\Support\ApiResponse;
use Illuminate\Http\Request;

class TenantUserController extends Controller
{
    public function __construct(private TenantUserService $users) {}

    public function index(Request $request)
    {
        return ApiResponse::success($this->users->list($request), 'All User retrieved successfully');
    }

    public function show(int $id)
    {
        return ApiResponse::success($this->users->get($id), 'User retrieved successfully');
    }

    public function store(Request $request)
    {
        $data = $request->validate([
            'name' => ['required', 'string', 'max:120'],
            'email' => ['required', 'email'],
            'password' => ['required', 'string', 'min:8'],
            'roleName' => ['required', 'string'],
        ]);

        return ApiResponse::success($this->users->create($data), 'User created successfully', 201);
    }

    public function update(Request $request, int $id)
    {
        $data = $request->validate([
            'name' => ['sometimes', 'string', 'max:120'],
            'email' => ['sometimes', 'email'],
        ]);

        return ApiResponse::success($this->users->update($id, $data), 'User updated successfully');
    }

    public function destroy(int $id)
    {
        $this->users->delete($id);

        return ApiResponse::success(null, 'User deleted successfully');
    }

    public function changeRole(Request $request, int $id)
    {
        $data = $request->validate(['roleName' => ['required', 'string']]);

        return ApiResponse::success($this->users->changeRole($id, $data['roleName']), 'User role updated successfully');
    }

    public function changeStatus(Request $request, int $id)
    {
        $data = $request->validate(['status' => ['required', 'string']]);

        return ApiResponse::success($this->users->changeStatus($id, $data['status']), 'User status updated successfully');
    }

    public function me()
    {
        return ApiResponse::success($this->users->me(), 'Own profile retrieved successfully');
    }

    public function updateMe(Request $request)
    {
        $data = $request->validate([
            'name' => ['sometimes', 'string', 'max:120'],
            'password' => ['sometimes', 'string', 'min:8'],
        ]);

        return ApiResponse::success($this->users->updateMe($data), 'Profile updated successfully');
    }
}
