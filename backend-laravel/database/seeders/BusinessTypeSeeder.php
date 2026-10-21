<?php

namespace Database\Seeders;

use App\Models\BusinessType;
use Illuminate\Database\Seeder;

class BusinessTypeSeeder extends Seeder
{
    public function run(): void
    {
        $rows = [
            ['RETAIL', 'Retail shop', 10, false],
            ['GROCERY', 'Grocery shop', 20, false],
            ['MOBILE_ELECTRONICS', 'Mobile / electronics shop', 30, false],
            ['WHOLESALE', 'Wholesale', 40, true],
            ['RESTAURANT', 'Hotel / restaurant / café', 50, false],
            ['SALON', 'Salon / barber shop', 60, false],
            ['REPAIR', 'Repair / service business', 70, false],
            ['SERVICE', 'Service', 80, true],
            ['PHARMACY', 'Pharmacy', 90, true],
            ['OTHER', 'Other', 100, false],
        ];

        foreach ($rows as [$code, $label, $sort, $legacy]) {
            BusinessType::query()->updateOrCreate(
                ['code' => $code],
                [
                    'label' => $label,
                    'sort_order' => $sort,
                    'is_active' => true,
                    'legacy' => $legacy,
                ]
            );
        }
    }
}
