<?php

namespace App\Http\Controllers\Api\V1;

use App\Http\Controllers\Controller;
use App\Services\CustomerService;
use App\Support\ApiResponse;
use Illuminate\Http\Request;

class CustomerController extends Controller
{
    public function __construct(private CustomerService $customers) {}

    public function store(Request $request)
    {
        $data = $request->validate([
            'name' => ['required', 'string', 'max:255'],
            'email' => ['nullable', 'email', 'max:255'],
            'phone' => ['nullable', 'string', 'max:50'],
            'address' => ['nullable', 'string'],
            'gstin' => ['nullable', 'regex:/^[0-9A-Z]{15}$/'],
        ]);

        return ApiResponse::success($this->customers->create($data), 'Customer created successfully', 201);
    }

    public function index(Request $request)
    {
        return ApiResponse::success($this->customers->list($request), 'Customers fetched successfully');
    }

    public function walkin()
    {
        return ApiResponse::success($this->customers->walkIn(), 'Walk-in customer fetched successfully');
    }

    public function show(int $id)
    {
        return ApiResponse::success($this->customers->getById($id), 'Customer fetched successfully');
    }

    public function update(Request $request, int $id)
    {
        $data = $request->validate([
            'name' => ['sometimes', 'nullable', 'string', 'max:255'],
            'email' => ['sometimes', 'nullable', 'email', 'max:255'],
            'phone' => ['sometimes', 'nullable', 'string', 'max:50'],
            'address' => ['sometimes', 'nullable', 'string'],
            'gstin' => ['sometimes', 'nullable', 'regex:/^[0-9A-Z]{15}$/'],
        ]);

        return ApiResponse::success($this->customers->update($id, $data), 'Customer updated successfully');
    }

    public function destroy(int $id)
    {
        $this->customers->delete($id);

        return ApiResponse::success(null, 'Customer deleted successfully');
    }
}
