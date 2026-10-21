<?php

namespace App\Http\Requests;

use Illuminate\Foundation\Http\FormRequest;

class LoginRequest extends FormRequest
{
    public function authorize(): bool
    {
        return true;
    }

    public function rules(): array
    {
        return [
            'email' => ['required', 'email'],
            'password' => ['required', 'string'],
            'tenantSlug' => ['required', 'string', 'min:2', 'max:50', 'regex:/^[a-z0-9-]+$/'],
        ];
    }
}
