<?php

namespace App\Http\Controllers\Api\V1;

use App\Http\Controllers\Controller;
use App\Services\ReportService;
use App\Support\ApiResponse;
use Illuminate\Http\Request;

class ReportController extends Controller
{
    public function __construct(private ReportService $reports) {}

    public function revenue(Request $request)
    {
        $data = $request->validate([
            'from' => ['required', 'date'],
            'to' => ['required', 'date'],
        ]);

        return ApiResponse::success($this->reports->revenue($data['from'], $data['to']), 'Revenue report generated successfully');
    }

    public function outstanding()
    {
        return ApiResponse::success($this->reports->outstanding(), 'Outstanding report generated successfully');
    }

    public function monthly(Request $request)
    {
        $year = (int) ($request->query('year') ?: now()->year);

        return ApiResponse::success($this->reports->monthly($year), 'Monthly report generated for year '.$year);
    }
}
