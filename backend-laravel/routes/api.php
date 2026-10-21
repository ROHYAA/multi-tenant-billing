<?php

use App\Http\Controllers\Api\V1\AdminAuditLogController;
use App\Http\Controllers\Api\V1\AttachmentController;
use App\Http\Controllers\Api\V1\AdminAuthController;
use App\Http\Controllers\Api\V1\AdminTenantController;
use App\Http\Controllers\Api\V1\AdminUserController;
use App\Http\Controllers\Api\V1\AuthController;
use App\Http\Controllers\Api\V1\BillController;
use App\Http\Controllers\Api\V1\BillTemplateController;
use App\Http\Controllers\Api\V1\BusinessTypeController;
use App\Http\Controllers\Api\V1\CustomerController;
use App\Http\Controllers\Api\V1\HealthController;
use App\Http\Controllers\Api\V1\PaymentController;
use App\Http\Controllers\Api\V1\PermissionCatalogController;
use App\Http\Controllers\Api\V1\ProductController;
use App\Http\Controllers\Api\V1\UsedPhoneController;
use App\Http\Controllers\Api\V1\ReportController;
use App\Http\Controllers\Api\V1\ShopSelfController;
use App\Http\Controllers\Api\V1\ShopSettingsController;
use App\Http\Controllers\Api\V1\TenantRoleController;
use App\Http\Controllers\Api\V1\TenantUserController;
use Illuminate\Support\Facades\Route;

Route::get('/health', HealthController::class);
Route::get('/business-types', BusinessTypeController::class);

Route::prefix('auth')->group(function () {
    Route::post('/signup', [AuthController::class, 'signup']);
    Route::post('/tenants', [AuthController::class, 'tenants']);
    Route::post('/login', [AuthController::class, 'login']);
    Route::post('/refresh', [AuthController::class, 'refresh']);
    Route::post('/forgot-password', [AuthController::class, 'forgotPassword']);
    Route::post('/reset-password', [AuthController::class, 'resetPassword']);
    Route::middleware('auth.jwt')->group(function () {
        Route::post('/logout', [AuthController::class, 'logout']);
        Route::get('/me', [AuthController::class, 'me']);
    });
});

Route::prefix('admin/auth')->group(function () {
    Route::post('/login', [AdminAuthController::class, 'login']);
    Route::post('/refresh', [AdminAuthController::class, 'refresh']);
    Route::middleware(['auth.jwt', 'super.admin'])->group(function () {
        Route::post('/logout', [AdminAuthController::class, 'logout']);
        Route::get('/me', [AdminAuthController::class, 'me']);
    });
});

Route::middleware('auth.jwt')->prefix('tenant')->group(function () {
    Route::get('/', [ShopSelfController::class, 'show']);
    Route::get('/status', [ShopSelfController::class, 'status']);
    Route::middleware('permission:TENANT_MANAGE')->group(function () {
        Route::put('/', [ShopSelfController::class, 'update']);
        Route::get('/schema', [ShopSelfController::class, 'schema']);
        Route::post('/deactivate', [ShopSelfController::class, 'deactivate']);
        Route::post('/reactivate', [ShopSelfController::class, 'reactivate']);
    });
});

Route::middleware(['auth.jwt', 'super.admin'])->group(function () {
    Route::prefix('admin/tenants')->group(function () {
        Route::get('/', [AdminTenantController::class, 'index']);
        Route::get('/{id}', [AdminTenantController::class, 'show']);
        Route::put('/{id}/status', [AdminTenantController::class, 'changeStatus']);
        Route::post('/{id}/approve', [AdminTenantController::class, 'approve']);
        Route::post('/{id}/lifetime', [AdminTenantController::class, 'grantLifetime']);
        Route::post('/{id}/reactivate', [AdminTenantController::class, 'reactivate']);
        Route::get('/{id}/users', [AdminTenantController::class, 'users']);
        Route::delete('/{id}', [AdminTenantController::class, 'destroy']);
    });

    Route::delete('/admin/users/tenant/{tenantId}/user/{userId}', [AdminUserController::class, 'destroy']);

    Route::prefix('admin/audit-logs')->group(function () {
        Route::get('/', [AdminAuditLogController::class, 'index']);
        Route::get('/user/{userId}', [AdminAuditLogController::class, 'byUser']);
        Route::get('/entity/{entityType}/{entityId}', [AdminAuditLogController::class, 'byEntity']);
        Route::get('/tenant/{tenantId}', [AdminAuditLogController::class, 'byTenant']);
        Route::get('/date-range', [AdminAuditLogController::class, 'byDateRange']);
        Route::get('/statistics', [AdminAuditLogController::class, 'statistics']);
    });
});

Route::middleware('auth.jwt')->group(function () {
    Route::get('/shop-settings', [ShopSettingsController::class, 'show']);
    Route::put('/shop-settings', [ShopSettingsController::class, 'update'])->middleware('permission:TENANT_MANAGE');
    Route::post('/attachments', [AttachmentController::class, 'store'])->middleware('permission:TENANT_MANAGE');
    Route::get('/attachments/{id}', [AttachmentController::class, 'show'])->middleware('permission:TENANT_MANAGE');
    Route::get('/attachments/{id}/file', [AttachmentController::class, 'file']);
    Route::delete('/attachments/{id}', [AttachmentController::class, 'destroy'])->middleware('permission:TENANT_MANAGE');
    Route::get('/bill-templates', [BillTemplateController::class, 'index']);
    Route::get('/permissions', [PermissionCatalogController::class, 'index']);
    Route::get('/permissions/{id}', [PermissionCatalogController::class, 'show']);
    Route::post('/permissions', [PermissionCatalogController::class, 'store'])->middleware('super.admin');
    Route::delete('/permissions/{id}', [PermissionCatalogController::class, 'destroy'])->middleware('super.admin');

    Route::get('/customers/walkin', [CustomerController::class, 'walkin'])->middleware('permission:BILLING_MANAGE');
    Route::get('/customers', [CustomerController::class, 'index'])->middleware('permission:CUSTOMER_MANAGE');
    Route::post('/customers', [CustomerController::class, 'store'])->middleware('permission:CUSTOMER_MANAGE');
    Route::get('/customers/{id}', [CustomerController::class, 'show'])->middleware('permission:CUSTOMER_MANAGE');
    Route::put('/customers/{id}', [CustomerController::class, 'update'])->middleware('permission:CUSTOMER_MANAGE');
    Route::delete('/customers/{id}', [CustomerController::class, 'destroy'])->middleware('permission:CUSTOMER_MANAGE');

    Route::get('/products/active', [ProductController::class, 'active'])->middleware('permission:BILLING_MANAGE');
    Route::get('/products', [ProductController::class, 'index'])->middleware('permission:PRODUCT_MANAGE');
    Route::post('/products', [ProductController::class, 'store'])->middleware('permission:PRODUCT_MANAGE');
    Route::get('/products/{id}', [ProductController::class, 'show'])->middleware('permission:PRODUCT_MANAGE');
    Route::put('/products/{id}', [ProductController::class, 'update'])->middleware('permission:PRODUCT_MANAGE');
    Route::delete('/products/{id}', [ProductController::class, 'destroy'])->middleware('permission:PRODUCT_MANAGE');
    Route::post('/products/{id}/reactivate', [ProductController::class, 'reactivate'])->middleware('permission:PRODUCT_MANAGE');

    Route::middleware('permission:PRODUCT_MANAGE')->group(function () {
        Route::get('/used-phones/imei-check', [UsedPhoneController::class, 'imeiCheck']);
        Route::post('/used-phones/photos', [UsedPhoneController::class, 'uploadPhoto']);
        Route::get('/used-phones', [UsedPhoneController::class, 'index']);
        Route::post('/used-phones', [UsedPhoneController::class, 'store']);
        Route::get('/used-phones/{id}', [UsedPhoneController::class, 'show'])->whereNumber('id');
        Route::get('/used-phones/{id}/voucher', [UsedPhoneController::class, 'voucher'])->whereNumber('id');
        Route::get('/used-phones/{id}/voucher/preview', [UsedPhoneController::class, 'voucherPreview'])->whereNumber('id');
    });

    Route::middleware('permission:BILLING_MANAGE')->group(function () {
        Route::post('/business-invoices', [BillController::class, 'store']);
        Route::get('/business-invoices', [BillController::class, 'index']);
        Route::get('/business-invoices/{id}', [BillController::class, 'show']);
        Route::post('/business-invoices/{id}/items', [BillController::class, 'addItem']);
        Route::delete('/business-invoices/{id}/items/{itemId}', [BillController::class, 'removeItem']);
        Route::post('/business-invoices/{id}/finalize', [BillController::class, 'finalize']);
        Route::post('/business-invoices/{id}/send', [BillController::class, 'send']);
        Route::post('/business-invoices/{id}/void', [BillController::class, 'voidInvoice']);
        Route::get('/business-invoices/{id}/download', [BillController::class, 'download']);
        Route::get('/business-invoices/{id}/preview', [BillController::class, 'preview']);

        Route::post('/business-payments/customer/{customerId}', [PaymentController::class, 'recordForCustomer']);
        Route::get('/business-payments/customer/{customerId}/outstanding', [PaymentController::class, 'customerOutstanding']);
        Route::get('/business-payments/invoice/{invoiceId}', [PaymentController::class, 'listByInvoice']);
        Route::get('/business-payments/invoice/{invoiceId}/outstanding', [PaymentController::class, 'outstanding']);
        Route::patch('/business-payments/{paymentId}/confirm', [PaymentController::class, 'confirm']);
        Route::post('/business-payments/{invoiceId}', [PaymentController::class, 'record']);

        Route::get('/reports/revenue', [ReportController::class, 'revenue']);
        Route::get('/reports/outstanding', [ReportController::class, 'outstanding']);
        Route::get('/reports/monthly', [ReportController::class, 'monthly']);
    });

    Route::get('/users/me', [TenantUserController::class, 'me']);
    Route::put('/users/me', [TenantUserController::class, 'updateMe']);
    Route::get('/users', [TenantUserController::class, 'index'])->middleware('permission:USER_VIEW');
    Route::get('/users/{id}', [TenantUserController::class, 'show'])->middleware('permission:USER_VIEW');
    Route::post('/users', [TenantUserController::class, 'store'])->middleware('permission:USER_MANAGE');
    Route::put('/users/{id}', [TenantUserController::class, 'update'])->middleware('permission:USER_MANAGE');
    Route::delete('/users/{id}', [TenantUserController::class, 'destroy'])->middleware('permission:USER_DELETE');
    Route::put('/users/{id}/role', [TenantUserController::class, 'changeRole'])->middleware('permission:USER_MANAGE');
    Route::put('/users/{id}/status', [TenantUserController::class, 'changeStatus'])->middleware('permission:USER_MANAGE');

    Route::get('/roles', [TenantRoleController::class, 'index'])->middleware('permission:ROLE_VIEW');
    Route::get('/roles/{id}', [TenantRoleController::class, 'show'])->middleware('permission:ROLE_VIEW');
    Route::post('/roles', [TenantRoleController::class, 'store'])->middleware('permission:ROLE_MANAGE');
    Route::put('/roles/{id}', [TenantRoleController::class, 'update'])->middleware('permission:ROLE_MANAGE');
    Route::delete('/roles/{id}', [TenantRoleController::class, 'destroy'])->middleware('permission:ROLE_MANAGE');
    Route::get('/roles/{id}/permissions', [TenantRoleController::class, 'permissions'])->middleware('permission:ROLE_VIEW');
    Route::post('/roles/{id}/permissions', [TenantRoleController::class, 'assignPermission'])->middleware('permission:ROLE_MANAGE');
    Route::delete('/roles/{id}/permissions/{permId}', [TenantRoleController::class, 'removePermission'])->middleware('permission:ROLE_MANAGE');
});
