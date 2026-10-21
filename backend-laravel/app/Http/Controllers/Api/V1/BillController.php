<?php

namespace App\Http\Controllers\Api\V1;

use App\Http\Controllers\Controller;
use App\Services\BillPdfService;
use App\Services\BillService;
use App\Support\ApiResponse;
use Illuminate\Http\Request;

class BillController extends Controller
{
    public function __construct(
        private BillService $bills,
        private BillPdfService $pdf,
    ) {}

    public function store(Request $request)
    {
        $data = $request->validate([
            'customerId' => ['required', 'integer'],
            'currency' => ['nullable', 'string', 'size:3'],
            'notes' => ['nullable', 'string'],
            'discountAmount' => ['nullable', 'numeric', 'min:0'],
            'items' => ['required', 'array', 'min:1'],
            'items.*.productId' => ['nullable', 'integer'],
            'items.*.description' => ['nullable', 'string', 'max:500'],
            'items.*.quantity' => ['required', 'numeric', 'min:0.01'],
            'items.*.unitPrice' => ['nullable', 'numeric', 'min:0'],
            'items.*.taxPercentage' => ['nullable', 'numeric', 'min:0'],
            'items.*.imei' => ['nullable', 'string', 'max:20'],
        ]);

        return ApiResponse::success($this->bills->create($data), 'Invoice created successfully', 201);
    }

    public function index(Request $request)
    {
        return ApiResponse::success($this->bills->list($request), 'Invoices fetched successfully');
    }

    public function show(int $id)
    {
        return ApiResponse::success($this->bills->getById($id), 'Invoice fetched successfully');
    }

    public function addItem(Request $request, int $id)
    {
        $data = $request->validate([
            'productId' => ['nullable', 'integer'],
            'description' => ['nullable', 'string', 'max:500'],
            'quantity' => ['required', 'numeric', 'min:0.01'],
            'unitPrice' => ['nullable', 'numeric', 'min:0'],
            'taxPercentage' => ['nullable', 'numeric', 'min:0'],
        ]);

        return ApiResponse::success($this->bills->addItem($id, $data), 'Line item added successfully');
    }

    public function removeItem(int $id, int $itemId)
    {
        return ApiResponse::success($this->bills->removeItem($id, $itemId), 'Line item removed successfully');
    }

    public function finalize(int $id)
    {
        return ApiResponse::success($this->bills->finalize($id), 'Invoice finalized successfully');
    }

    public function send(int $id)
    {
        return ApiResponse::success($this->bills->send($id), 'Invoice sent to customer successfully');
    }

    public function voidInvoice(int $id)
    {
        return ApiResponse::success($this->bills->voidInvoice($id), 'Invoice voided successfully');
    }

    public function download(Request $request, int $id)
    {
        return $this->pdfResponse($request, $id, 'attachment');
    }

    public function preview(Request $request, int $id)
    {
        return $this->pdfResponse($request, $id, 'inline');
    }

    private function pdfResponse(Request $request, int $id, string $disposition)
    {
        $copy = $request->query('copyType');
        if ($copy !== null && $copy !== '' && ! in_array($copy, ['ORIGINAL', 'DUPLICATE', 'TRIPLICATE'], true)) {
            return ApiResponse::error('Validation failed', 'VAL_6001', 400, [
                'copyType' => 'Must be ORIGINAL, DUPLICATE, or TRIPLICATE',
            ]);
        }
        $built = $this->pdf->generate($id, is_string($copy) && $copy !== '' ? $copy : null);
        $filename = $built['invoiceNumber'].'.pdf';

        return response($built['bytes'], 200, [
            'Content-Type' => 'application/pdf',
            'Content-Disposition' => $disposition.'; filename="'.$filename.'"',
            'Content-Length' => (string) strlen($built['bytes']),
        ]);
    }
}
