<?php

namespace App\Http\Requests;

use Illuminate\Foundation\Http\FormRequest;

class ResetPasswordRequest extends FormRequest
{
    public function authorize(): bool
    {
        return true;
    }

    public function rules(): array
    {
        return [
            'tenantSlug' => ['required', 'string', 'min:2', 'max:50', 'regex:/^[a-z0-9-]+$/'],
            'token' => ['required', 'string'],
            'newPassword' => ['required', 'string', 'min:8'],
        ];
    }
}
