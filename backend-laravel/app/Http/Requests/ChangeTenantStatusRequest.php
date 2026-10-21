<?php

namespace App\Http\Requests;

use Illuminate\Foundation\Http\FormRequest;
use Illuminate\Validation\Rule;

class ChangeTenantStatusRequest extends FormRequest
{
    public function authorize(): bool
    {
        return true;
    }

    public function rules(): array
    {
        return [
            'status' => ['required', Rule::in(['ACTIVE', 'INACTIVE', 'SUSPENDED', 'PENDING_APPROVAL', 'REGISTERED'])],
            'reason' => ['required', 'string'],
        ];
    }
}
