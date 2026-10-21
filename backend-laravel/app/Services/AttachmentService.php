<?php

namespace App\Services;

use App\Exceptions\ApiException;
use App\Models\Attachment;
use App\Models\UsedPhonePurchase;
use App\Support\ShopContext;
use Illuminate\Http\UploadedFile;
use Illuminate\Support\Facades\Storage;
use Illuminate\Support\Str;

class AttachmentService
{
    private const ALLOWED = ['image/png', 'image/jpeg', 'image/jpg'];

    private const MAX_BYTES = 2 * 1024 * 1024;

    public function upload(string $purpose, UploadedFile $file): array
    {
        $purpose = strtoupper(trim($purpose));
        if (! in_array($purpose, ['LOGO', 'SIGNATURE', 'STAMP', 'QR_CODE', 'OTHER', ...UsedPhoneService::PHOTO_PURPOSES], true)) {
            throw ApiException::invalid('Invalid attachment purpose.');
        }
        if ($file->getSize() > self::MAX_BYTES) {
            throw ApiException::invalid('File exceeds maximum size of 2MB');
        }
        $mime = strtolower((string) ($file->getMimeType() ?: $file->getClientMimeType()));
        if (! in_array($mime, self::ALLOWED, true)) {
            throw ApiException::invalid('Unsupported content type: '.$mime.'. Allowed: image/png, image/jpeg');
        }

        $shopId = ShopContext::shopId();
        if ($shopId === null) {
            throw ApiException::accessDenied();
        }
        $ext = str_contains($mime, 'png') ? 'png' : 'jpg';
        $key = 'attachments/'.$shopId.'/'.Str::uuid()->toString().'.'.$ext;
        Storage::disk('local')->put($key, $file->getContent());

        $row = Attachment::query()->create([
            'purpose' => $purpose,
            'file_name' => $file->getClientOriginalName() ?: ('file.'.$ext),
            'content_type' => $mime === 'image/jpg' ? 'image/jpeg' : $mime,
            'size_bytes' => $file->getSize(),
            'storage_key' => $key,
            'deleted' => false,
            'version' => 0,
        ]);

        return $this->toResponse($row);
    }

    public function getById(int $id): array
    {
        return $this->toResponse($this->find($id));
    }

    public function bytes(int $id): string
    {
        $row = $this->find($id);
        $data = $this->readBytes($row->storage_key);
        if ($data === null) {
            throw ApiException::notFound('Attachment', $id);
        }

        return $data;
    }

    public function contentType(int $id): string
    {
        return (string) $this->find($id)->content_type ?: 'application/octet-stream';
    }

    public function delete(int $id): void
    {
        $row = $this->find($id);
        $usedByPurchase = UsedPhonePurchase::query()
            ->where(fn ($q) => $q->where('id_proof_attachment_id', $id)
                ->orWhere('seller_photo_attachment_id', $id)
                ->orWhere('phone_photo_attachment_id', $id)
                ->orWhere('seller_signature_attachment_id', $id))
            ->exists();
        if ($usedByPurchase) {
            throw ApiException::invalid('This photo is part of a used-phone purchase record and cannot be deleted.');
        }
        Storage::disk('local')->delete($row->storage_key);
        $abs = storage_path('app/'.$row->storage_key);
        if (is_file($abs)) {
            @unlink($abs);
        }
        $row->delete();
    }

    public function url(?int $id): ?string
    {
        if (! $id) {
            return null;
        }
        try {
            $this->find($id);

            return '/api/v1/attachments/'.$id.'/file';
        } catch (\Throwable) {
            return null;
        }
    }

    private function find(int $id): Attachment
    {
        $row = Attachment::query()->where('deleted', false)->find($id);
        if (! $row) {
            throw ApiException::notFound('Attachment', $id);
        }

        return $row;
    }

    private function readBytes(string $key): ?string
    {
        if (Storage::disk('local')->exists($key)) {
            return Storage::disk('local')->get($key);
        }
        $legacy = storage_path('app/'.$key);
        if (is_file($legacy)) {
            $data = file_get_contents($legacy);

            return $data === false ? null : $data;
        }

        return null;
    }

    private function toResponse(Attachment $row): array
    {
        return [
            'id' => (int) $row->id,
            'purpose' => $row->purpose,
            'fileName' => $row->file_name,
            'contentType' => $row->content_type,
            'sizeBytes' => (int) $row->size_bytes,
            'url' => '/api/v1/attachments/'.$row->id.'/file',
            'createdAt' => optional($row->created_at)?->toISOString(),
        ];
    }
}
