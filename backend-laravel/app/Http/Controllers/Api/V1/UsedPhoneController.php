<?php

namespace App\Http\Controllers\Api\V1;

use App\Exceptions\ApiException;
use App\Http\Controllers\Controller;
use App\Services\AttachmentService;
use App\Services\UsedPhoneService;
use App\Services\UsedPhoneVoucherPdfService;
use App\Support\ApiResponse;
use Illuminate\Http\Request;
use Illuminate\Validation\Rule;

class UsedPhoneController extends Controller
{
    public function __construct(
        private UsedPhoneService $usedPhones,
        private UsedPhoneVoucherPdfService $voucher,
        private AttachmentService $attachments,
    ) {}

    public function store(Request $request)
    {
        $data = $request->validate([
            'sellerName' => ['required', 'string', 'max:255'],
            'sellerMobile' => ['required', 'string', 'max:20'],
            'sellerAddress' => ['nullable', 'string', 'max:500'],
            'sellerIdType' => ['required', 'string', Rule::in(UsedPhoneService::ID_TYPES)],
            'sellerIdNumber' => ['required', 'string', 'max:40'],
            'idProofAttachmentId' => ['required', 'integer'],
            'sellerPhotoAttachmentId' => ['nullable', 'integer'],
            'phonePhotoAttachmentId' => ['nullable', 'integer'],
            'sellerSignatureAttachmentId' => ['nullable', 'integer'],
            'brand' => ['required', 'string', 'max:60'],
            'model' => ['required', 'string', 'max:120'],
            'color' => ['nullable', 'string', 'max:40'],
            'storage' => ['nullable', 'string', 'max:40'],
            'imei1' => ['required', 'string', 'max:20'],
            'imei2' => ['nullable', 'string', 'max:20'],
            'serialNumber' => ['nullable', 'string', 'max:60'],
            'conditionGrade' => ['nullable', 'string', Rule::in(['LIKE_NEW', 'GOOD', 'FAIR', 'POOR'])],
            'batteryHealth' => ['nullable', 'integer', 'min:1', 'max:100'],
            'accessories' => ['nullable', 'array'],
            'accessories.*' => ['string', 'max:30'],
            'hasOriginalBill' => ['nullable', 'boolean'],
            'ceirChecked' => ['nullable', 'boolean'],
            'declarationAccepted' => ['required', 'boolean'],
            'purchasePrice' => ['required', 'numeric', 'min:0'],
            'sellingPrice' => ['nullable', 'numeric', 'min:0'],
            'taxPercentage' => ['nullable', 'numeric', 'min:0', 'max:28'],
            'paymentMethod' => ['nullable', 'string', Rule::in(['CASH', 'UPI', 'BANK_TRANSFER', 'CARD'])],
            'notes' => ['nullable', 'string', 'max:1000'],
        ]);

        return ApiResponse::success($this->usedPhones->create($data), 'Used phone purchase recorded', 201);
    }

    public function index(Request $request)
    {
        return ApiResponse::success($this->usedPhones->list($request), 'Used phone purchases fetched');
    }

    public function show(int $id)
    {
        return ApiResponse::success($this->usedPhones->getById($id), 'Used phone purchase fetched');
    }

    public function imeiCheck(Request $request)
    {
        return ApiResponse::success($this->usedPhones->imeiHistory((string) $request->query('imei', '')), 'IMEI history fetched');
    }

    /**
     * Photo upload for the buy-in form. Separate from /attachments so staff with
     * PRODUCT_MANAGE (not only shop admins) can capture seller ID proof.
     */
    public function uploadPhoto(Request $request)
    {
        $file = $request->file('file');
        if (! $file) {
            throw ApiException::invalid('File is required');
        }
        $purpose = strtoupper((string) $request->query('purpose', $request->input('purpose', '')));
        if (! in_array($purpose, UsedPhoneService::PHOTO_PURPOSES, true)) {
            throw ApiException::invalid('Invalid photo purpose.');
        }

        return ApiResponse::success($this->attachments->upload($purpose, $file), 'Photo uploaded', 201);
    }

    public function voucher(int $id)
    {
        return $this->pdfResponse($id, 'attachment');
    }

    public function voucherPreview(int $id)
    {
        return $this->pdfResponse($id, 'inline');
    }

    private function pdfResponse(int $id, string $disposition)
    {
        $pdf = $this->voucher->generate($id);

        return response($pdf['bytes'], 200, [
            'Content-Type' => 'application/pdf',
            'Content-Disposition' => $disposition.'; filename="'.$pdf['voucherNumber'].'.pdf"',
            'Content-Length' => (string) strlen($pdf['bytes']),
        ]);
    }
}
