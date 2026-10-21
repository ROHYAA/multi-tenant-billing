<?php

namespace App\Http\Controllers\Api\V1;

use App\Exceptions\ApiException;
use App\Http\Controllers\Controller;
use App\Services\AttachmentService;
use App\Support\ApiResponse;
use Illuminate\Http\Request;

class AttachmentController extends Controller
{
    public function __construct(private AttachmentService $attachments) {}

    public function store(Request $request)
    {
        $file = $request->file('file');
        if (! $file) {
            throw ApiException::invalid('File is required');
        }
        $purpose = (string) $request->query('purpose', $request->input('purpose', 'OTHER'));

        return ApiResponse::success($this->attachments->upload($purpose, $file), 'Attachment uploaded successfully', 201);
    }

    public function show(int $id)
    {
        return ApiResponse::success($this->attachments->getById($id), 'Attachment fetched successfully');
    }

    public function file(int $id)
    {
        $bytes = $this->attachments->bytes($id);

        return response($bytes, 200, [
            'Content-Type' => $this->attachments->contentType($id),
            'Cache-Control' => 'private, max-age=3600',
        ]);
    }

    public function destroy(int $id)
    {
        $this->attachments->delete($id);

        return ApiResponse::success(null, 'Attachment deleted successfully');
    }
}
