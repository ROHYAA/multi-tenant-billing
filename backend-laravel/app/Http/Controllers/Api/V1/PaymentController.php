<?php

namespace App\Http\Controllers\Api\V1;

use App\Http\Controllers\Controller;
use App\Services\PaymentService;
use App\Support\ApiResponse;
use Illuminate\Http\Request;
use Illuminate\Validation\Rule;

class PaymentController extends Controller
{
    public function __construct(private PaymentService $payments) {}

    public function record(Request $request, int $invoiceId)
    {
        $data = $this->paymentBody($request);

        return ApiResponse::success($this->payments->record($invoiceId, $data), 'Payment recorded successfully', 201);
    }

    public function listByInvoice(int $invoiceId)
    {
        return ApiResponse::success($this->payments->listByInvoice($invoiceId), 'Payments fetched successfully');
    }

    public function outstanding(int $invoiceId)
    {
        return ApiResponse::success($this->payments->outstanding($invoiceId), 'Outstanding balance fetched successfully');
    }

    public function recordForCustomer(Request $request, int $customerId)
    {
        $data = $this->paymentBody($request);

        return ApiResponse::success($this->payments->recordForCustomer($customerId, $data), 'Payment recorded successfully', 201);
    }

    public function customerOutstanding(int $customerId)
    {
        return ApiResponse::success($this->payments->customerOutstanding($customerId), 'Customer outstanding balance fetched successfully');
    }

    public function confirm(int $paymentId)
    {
        return ApiResponse::success($this->payments->confirm($paymentId), 'Payment confirmed successfully');
    }

    private function paymentBody(Request $request): array
    {
        return $request->validate([
            'amount' => ['required', 'numeric', 'min:0.01'],
            'method' => ['required', Rule::in(['CASH', 'CARD', 'UPI', 'NETBANKING', 'BANK_TRANSFER', 'CREDIT'])],
            'notes' => ['nullable', 'string'],
            'paidAt' => ['nullable', 'date'],
        ]);
    }
}
