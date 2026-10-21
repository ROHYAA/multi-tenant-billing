<?php

namespace Database\Seeders;

use App\Models\BillTemplate;
use Illuminate\Database\Seeder;

class BillTemplateSeeder extends Seeder
{
    public function run(): void
    {
        BillTemplate::query()->firstOrCreate(
            ['code' => 'CASH_MEMO_V1'],
            [
                'name' => 'Simple Cash Memo',
                'description' => 'Clean A4 tax-invoice-style layout',
                'is_active' => true,
                'deleted' => false,
                'version' => 0,
            ]
        );
        BillTemplate::query()->firstOrCreate(
            ['code' => 'MARATHI_CASH_MEMO_V1'],
            [
                'name' => 'Marathi Cash Memo',
                'description' => 'Mobile-shop style Marathi cash memo',
                'is_active' => true,
                'deleted' => false,
                'version' => 0,
            ]
        );
    }
}
