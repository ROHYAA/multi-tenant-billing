<?php

namespace App\Models\Concerns;

use App\Support\ShopContext;
use Illuminate\Database\Eloquent\Builder;

trait BelongsToShop
{
    public static function bootBelongsToShop(): void
    {
        static::addGlobalScope('shop', function (Builder $builder) {
            if (ShopContext::isSuperAdmin()) {
                return;
            }
            $shopId = ShopContext::shopId();
            if ($shopId === null) {
                $builder->whereRaw('1 = 0');

                return;
            }
            $builder->where($builder->getModel()->getTable().'.shop_id', $shopId);
        });

        static::creating(function ($model) {
            if (ShopContext::isSuperAdmin()) {
                return;
            }
            $shopId = ShopContext::shopId();
            if ($shopId !== null) {
                $model->shop_id = $shopId;
            }
        });
    }
}
