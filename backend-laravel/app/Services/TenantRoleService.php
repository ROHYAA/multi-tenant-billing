<?php

namespace App\Services;

use App\Exceptions\ApiException;
use App\Models\Permission;
use App\Models\Role;
use App\Models\User;
use App\Support\ShopContext;

class TenantRoleService
{
    public function all(): array
    {
        return Role::query()->orderBy('name')->get()->map(fn (Role $r) => $this->toRole($r))->values()->all();
    }

    public function get(int $id): array
    {
        $role = $this->find($id);
        $perms = $role->permissions()->get()->map(fn (Permission $p) => $this->toPerm($p))->values()->all();

        return $this->toRole($role) + ['permissions' => $perms];
    }

    public function create(array $data): array
    {
        if (Role::query()->where('name', $data['name'])->exists()) {
            throw ApiException::alreadyExists('Role', $data['name']);
        }
        $role = Role::query()->create([
            'name' => $data['name'],
            'deleted' => false,
            'version' => 0,
        ]);

        return $this->toRole($role);
    }

    public function update(int $id, array $data): array
    {
        $role = $this->find($id);
        $this->assertCustom($role);
        if (! empty($data['name']) && $data['name'] !== $role->name
            && Role::query()->where('name', $data['name'])->where('id', '!=', $id)->exists()) {
            throw ApiException::alreadyExists('Role', $data['name']);
        }
        if (! empty($data['name'])) {
            $role->name = $data['name'];
            $role->save();
        }

        return $this->toRole($role);
    }

    public function delete(int $id): void
    {
        $role = $this->find($id);
        $this->assertCustom($role);
        if (User::query()->where('role_id', $id)->where('deleted', false)->exists()) {
            throw ApiException::invalid('Cannot delete a role that still has users');
        }
        $role->delete();
    }

    public function permissions(int $id): array
    {
        return $this->find($id)->permissions()->get()->map(fn (Permission $p) => $this->toPerm($p))->values()->all();
    }

    public function assign(int $id, int $permissionId): array
    {
        $role = $this->find($id);
        $perm = Permission::query()->find($permissionId);
        if (! $perm) {
            throw ApiException::notFound('Permission', $permissionId);
        }
        $role->permissions()->syncWithoutDetaching([
            $perm->id => [
                'shop_id' => ShopContext::requireShopId(),
                'deleted' => false,
                'version' => 0,
                'created_at' => now(),
                'updated_at' => now(),
            ],
        ]);

        return $this->get($id);
    }

    public function removePermission(int $id, int $permId): void
    {
        $role = $this->find($id);
        $role->permissions()->updateExistingPivot($permId, ['deleted' => true, 'updated_at' => now()]);
    }

    private function assertCustom(Role $role): void
    {
        if (in_array($role->name, ['OWNER', 'ADMIN', 'EMPLOYEE'], true)) {
            throw ApiException::invalid('System roles cannot be modified');
        }
    }

    private function find(int $id): Role
    {
        $role = Role::query()->find($id);
        if (! $role) {
            throw ApiException::notFound('Role', $id);
        }

        return $role;
    }

    private function toRole(Role $role): array
    {
        return [
            'id' => $role->id,
            'name' => $role->name,
            'createdAt' => optional($role->created_at)?->toISOString(),
            'updatedAt' => optional($role->updated_at)?->toISOString(),
        ];
    }

    private function toPerm(Permission $p): array
    {
        return [
            'id' => $p->id,
            'name' => $p->name,
            'description' => $p->description,
        ];
    }
}
