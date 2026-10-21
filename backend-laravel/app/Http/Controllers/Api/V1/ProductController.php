<?php

namespace App\Http\Controllers\Api\V1;

use App\Http\Controllers\Controller;
use App\Services\ProductService;
use App\Support\ApiResponse;
use Illuminate\Http\Request;

class ProductController extends Controller
{
    public function __construct(private ProductService $products) {}

    public function store(Request $request)
    {
        $data = $request->validate([
            'name' => ['required', 'string', 'max:255'],
            'description' => ['nullable', 'string'],
            'price' => ['required', 'numeric', 'min:0'],
            'taxPercentage' => ['nullable', 'numeric', 'min:0'],
            'hsnSacCode' => ['nullable', 'string', 'max:20'],
            'imei' => ['nullable', 'string', 'max:20'],
            'unit' => ['nullable', 'string', 'max:50'],
        ]);

        return ApiResponse::success($this->products->create($data), 'Product created successfully', 201);
    }

    public function index(Request $request)
    {
        return ApiResponse::success($this->products->list($request), 'Products fetched successfully');
    }

    public function active()
    {
        return ApiResponse::success($this->products->listActive(), 'Active products fetched successfully');
    }

    public function show(int $id)
    {
        return ApiResponse::success($this->products->getById($id), 'Product fetched successfully');
    }

    public function update(Request $request, int $id)
    {
        $data = $request->validate([
            'name' => ['sometimes', 'nullable', 'string', 'max:255'],
            'description' => ['sometimes', 'nullable', 'string'],
            'price' => ['sometimes', 'nullable', 'numeric', 'min:0'],
            'taxPercentage' => ['sometimes', 'nullable', 'numeric', 'min:0'],
            'hsnSacCode' => ['sometimes', 'nullable', 'string', 'max:20'],
            'imei' => ['sometimes', 'nullable', 'string', 'max:20'],
            'unit' => ['sometimes', 'nullable', 'string', 'max:50'],
        ]);

        return ApiResponse::success($this->products->update($id, $data), 'Product updated successfully');
    }

    public function destroy(int $id)
    {
        $this->products->deactivate($id);

        return ApiResponse::success(null, 'Product deactivated successfully');
    }

    public function reactivate(int $id)
    {
        $this->products->reactivate($id);

        return ApiResponse::success(null, 'Product reactivated successfully');
    }
}
