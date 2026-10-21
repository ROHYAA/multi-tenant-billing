<?php

namespace App\Services;

use App\Exceptions\ApiException;
use App\Models\Role;
use App\Models\User;
use App\Support\ApiResponse;
use App\Support\ShopContext;
use App\Support\SpringPage;
use Illuminate\Http\Request;

class TenantUserService
{
    public function list(Request $request): array
    {
        $query = User::query()->where('deleted', false);
        [$rows, $page, $size, $total] = SpringPage::paginate($query, $request, 'created_at,desc');
        $content = $rows->map(fn (User $u) => $this->toResponse($u))->values()->all();

        return ApiResponse::page($content, $page, $size, $total);
    }

    public function get(int $id): array
    {
        return $this->toResponse($this->find($id));
    }

    public function create(array $data): array
    {
        if (User::query()->where('email', $data['email'])->exists()) {
            throw ApiException::alreadyExists('User', $data['email']);
        }
        $roleName = strtoupper(trim($data['roleName']));
        if ($roleName !== 'EMPLOYEE') {
            throw ApiException::invalid('Shop admin can only create employee logins.');
        }
        $role = Role::query()->where('name', $roleName)->first();
        if (! $role) {
            throw ApiException::notFound('Role', $roleName);
        }
        $user = User::query()->create([
            'name' => $data['name'],
            'email' => $data['email'],
            'password' => $data['password'],
            'role_id' => $role->id,
            'status' => 'ACTIVE',
            'token_version' => 1,
            'is_first_login' => true,
            'deleted' => false,
            'version' => 0,
        ]);

        return $this->toResponse($user);
    }

    public function update(int $id, array $data): array
    {
        $user = $this->find($id);
        if (! empty($data['name'])) {
            $user->name = $data['name'];
        }
        if (! empty($data['email']) && strcasecmp($data['email'], $user->email) !== 0) {
            if (User::query()->where('email', $data['email'])->where('id', '!=', $id)->exists()) {
                throw ApiException::alreadyExists('User', $data['email']);
            }
            $user->email = $data['email'];
        }
        $user->save();

        return $this->toResponse($user);
    }

    public function delete(int $id): void
    {
        $me = ShopContext::user();
        if ($me && (int) $me->id === $id) {
            throw ApiException::invalid('Cannot delete your own account');
        }
        $user = $this->find($id);
        $user->deleted = true;
        $user->deleted_at = now();
        $user->save();
    }

    public function changeRole(int $id, string $roleName): array
    {
        $user = $this->find($id);
        $role = Role::query()->where('name', $roleName)->first();
        if (! $role) {
            throw ApiException::notFound('Role', $roleName);
        }
        $user->role_id = $role->id;
        $user->save();

        return $this->toResponse($user);
    }

    public function changeStatus(int $id, string $status): array
    {
        $user = $this->find($id);
        $user->status = $status;
        $user->save();

        return $this->toResponse($user);
    }

    public function me(): array
    {
        $user = ShopContext::user();
        if (! $user) {
            throw ApiException::accessDenied();
        }

        return $this->toResponse($user);
    }

    public function updateMe(array $data): array
    {
        $user = ShopContext::user();
        if (! $user) {
            throw ApiException::accessDenied();
        }
        if (! empty($data['name'])) {
            $user->name = $data['name'];
        }
        if (! empty($data['password'])) {
            $user->password = $data['password'];
            $user->token_version = ((int) $user->token_version) + 1;
        }
        $user->save();

        return $this->toResponse($user);
    }

    public function toResponse(User $user): array
    {
        $user->loadMissing('role');

        return [
            'id' => $user->id,
            'name' => $user->name,
            'email' => $user->email,
            'roleName' => $user->role?->name,
            'status' => $user->status,
            'createdAt' => optional($user->created_at)?->toISOString(),
            'updatedAt' => optional($user->updated_at)?->toISOString(),
        ];
    }

    private function find(int $id): User
    {
        $user = User::query()->where('deleted', false)->find($id);
        if (! $user) {
            throw ApiException::notFound('User', $id);
        }

        return $user;
    }
}
