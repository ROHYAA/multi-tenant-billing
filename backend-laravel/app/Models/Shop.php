<?php

namespace App\Models;

use App\Models\Concerns\Auditable;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\HasMany;

class Shop extends Model
{
    use Auditable;

    protected $guarded = ['id'];

    protected $casts = [
        'subscription_expires_at' => 'datetime',
        'expiry_alert_sent_at' => 'datetime',
        'deleted' => 'boolean',
    ];

    public function users(): HasMany
    {
        return $this->hasMany(User::class);
    }

    public function isActive(): bool
    {
        return $this->status === 'ACTIVE';
    }

    public function isTrialPlan(): bool
    {
        return strtoupper((string) $this->plan_name) === 'TRIAL';
    }

    public function isLifetime(): bool
    {
        return strtoupper((string) $this->plan_name) === 'LIFETIME';
    }

    public function isFreePlan(): bool
    {
        return strtoupper((string) $this->plan_name) === 'FREE';
    }

    public function subscriptionLapsed(): bool
    {
        return false;
    }

    public function isWriteBlocked(): bool
    {
        return $this->status !== 'ACTIVE';
    }

    public function writeBlockMessage(): string
    {
        if ($this->status === 'SUSPENDED') {
            return 'Your shop is suspended. You can view data but cannot make changes.';
        }

        return 'Your shop is pending approval — you can view data but cannot make changes yet';
    }
}
