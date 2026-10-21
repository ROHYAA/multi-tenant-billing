<?php

namespace App\Services;

use App\Exceptions\ApiException;
use App\Models\NumberSeries;
use App\Support\ShopContext;
use Illuminate\Support\Carbon;

class NumberSeriesService
{
    public function nextNumber(string $seriesType = 'INVOICE'): string
    {
        $series = NumberSeries::query()
            ->where('series_type', $seriesType)
            ->where('is_active', true)
            ->lockForUpdate()
            ->first();
        if (! $series) {
            throw ApiException::notFound('Active NumberSeries', $seriesType);
        }
        $next = ((int) $series->current_number) + 1;
        $series->current_number = $next;
        $series->save();

        return $series->prefix.'-'.$this->fySegment((string) $series->financial_year_format).sprintf('%04d', $next);
    }

    private function fySegment(string $format): string
    {
        if ($format === 'NONE' || $format === '') {
            return '';
        }
        $today = Carbon::now(ShopContext::shop()?->timezone ?? 'Asia/Kolkata');
        $fyStart = ((int) $today->month) >= 4 ? (int) $today->year : ((int) $today->year) - 1;
        if ($format === 'YYYY') {
            return $fyStart.'-';
        }
        if ($format === 'YYYY_YY') {
            return $fyStart.'-'.sprintf('%02d', ($fyStart + 1) % 100).'-';
        }

        return '';
    }
}
