<?php

namespace App\Models;

use App\Models\Concerns\Auditable;
use App\Models\Concerns\BelongsToShop;
use Illuminate\Database\Eloquent\Model;

class ShopSetting extends Model
{
    use Auditable, BelongsToShop;

    protected $guarded = ['id'];
}
