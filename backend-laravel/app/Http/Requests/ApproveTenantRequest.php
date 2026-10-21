<?php

namespace App\Http\Requests;

use Illuminate\Foundation\Http\FormRequest;

class ApproveTenantRequest extends FormRequest
{
    public function authorize(): bool
    {
        return true;
    }

    public function rules(): array
    {
        return [
            'planName' => ['required', 'string'],
            'subscriptionExpiresAt' => ['required', 'date', 'after:now'],
        ];
    }
}
