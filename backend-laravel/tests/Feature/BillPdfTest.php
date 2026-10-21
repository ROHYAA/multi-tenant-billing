<?php

namespace Tests\Feature;

use App\Models\ShopSetting;
use Database\Seeders\DatabaseSeeder;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Tests\Concerns\CreatesApprovedShop;
use Tests\TestCase;

class BillPdfTest extends TestCase
{
    use CreatesApprovedShop, RefreshDatabase;

    protected function setUp(): void
    {
        parent::setUp();
        $this->seed(DatabaseSeeder::class);
    }

    public function test_download_is_pdf_attachment_with_invoice_number_and_copy_label(): void
    {
        $token = $this->approvedOwnerToken();
        $billId = $this->openBill($token);

        $download = $this->withHeader('Authorization', 'Bearer '.$token)
            ->get('/api/v1/business-invoices/'.$billId.'/download?copyType=DUPLICATE')
            ->assertOk();

        $this->assertStringStartsWith('application/pdf', (string) $download->headers->get('Content-Type'));
        $this->assertStringContainsString('attachment', (string) $download->headers->get('Content-Disposition'));
        $this->assertStringContainsString('INV-0001.pdf', (string) $download->headers->get('Content-Disposition'));
        $body = $download->getContent();
        $this->assertStringStartsWith('%PDF-1.4', $body);
        $this->assertStringContainsString('INV-0001', $body);
        $this->assertStringContainsString('DUPLICATE', $body);
        $this->assertStringContainsString('TAX INVOICE', $body);
        $this->assertStringContainsString('Description', $body);
        $this->assertStringContainsString('Amount', $body);
        $this->assertStringContainsString('595.28', $body);

        $preview = $this->withHeader('Authorization', 'Bearer '.$token)
            ->get('/api/v1/business-invoices/'.$billId.'/preview')
            ->assertOk();
        $this->assertStringContainsString('inline', (string) $preview->headers->get('Content-Disposition'));
    }

    public function test_thermal_page_and_cross_tenant_404(): void
    {
        $tokenA = $this->approvedOwnerToken('a@example.com', 'Shop A');
        $tokenB = $this->approvedOwnerToken('b@example.com', 'Shop B');
        $billId = $this->openBill($tokenA, 'SECRET-PDF-'.uniqid());

        ShopSetting::withoutGlobalScopes()
            ->where('shop_id', \App\Models\Shop::query()->where('owner_email', 'a@example.com')->value('id'))
            ->update(['paper_size' => 'THERMAL_58MM']);

        $pdf = $this->withHeader('Authorization', 'Bearer '.$tokenA)
            ->get('/api/v1/business-invoices/'.$billId.'/download')
            ->assertOk()
            ->getContent();
        $this->assertStringContainsString('164.41', $pdf);

        $this->withHeader('Authorization', 'Bearer '.$tokenB)
            ->get('/api/v1/business-invoices/'.$billId.'/download')
            ->assertStatus(404);
    }

    public function test_logo_and_signature_embed_in_pdf(): void
    {
        $token = $this->approvedOwnerToken();
        $logo = \Illuminate\Http\UploadedFile::fake()->image('logo.jpg', 80, 40);
        $sign = \Illuminate\Http\UploadedFile::fake()->image('sign.png', 120, 40);

        $logoId = $this->withHeader('Authorization', 'Bearer '.$token)
            ->post('/api/v1/attachments?purpose=LOGO', ['file' => $logo])
            ->assertCreated()
            ->json('data.id');
        $signId = $this->withHeader('Authorization', 'Bearer '.$token)
            ->post('/api/v1/attachments?purpose=SIGNATURE', ['file' => $sign])
            ->assertCreated()
            ->json('data.id');

        $this->withHeader('Authorization', 'Bearer '.$token)
            ->getJson('/api/v1/shop-settings')
            ->assertOk()
            ->assertJsonPath('data.logoUrl', null);

        $this->withHeader('Authorization', 'Bearer '.$token)
            ->putJson('/api/v1/shop-settings', [
                'logoAttachmentId' => $logoId,
                'signatureAttachmentId' => $signId,
                'showLogo' => true,
                'showSignature' => true,
            ])
            ->assertOk()
            ->assertJsonPath('data.logoUrl', '/api/v1/attachments/'.$logoId.'/file')
            ->assertJsonPath('data.signatureUrl', '/api/v1/attachments/'.$signId.'/file');

        $this->withHeader('Authorization', 'Bearer '.$token)
            ->get('/api/v1/attachments/'.$logoId.'/file')
            ->assertOk();

        $billId = $this->openBill($token);
        $pdf = $this->withHeader('Authorization', 'Bearer '.$token)
            ->get('/api/v1/business-invoices/'.$billId.'/download')
            ->assertOk()
            ->getContent();
        $this->assertStringContainsString('/Im1', $pdf);
        $this->assertStringContainsString('/Im2', $pdf);
        $this->assertStringContainsString('/DCTDecode', $pdf);
        $this->assertStringContainsString('Authorized signatory', $pdf);
    }

    private function openBill(string $token, string $description = 'Widget'): int
    {
        $customer = $this->withHeader('Authorization', 'Bearer '.$token)
            ->postJson('/api/v1/customers', ['name' => 'Acme'])
            ->json('data.id');
        $bill = $this->withHeader('Authorization', 'Bearer '.$token)
            ->postJson('/api/v1/business-invoices', [
                'customerId' => $customer,
                'items' => [['description' => $description, 'quantity' => 2, 'unitPrice' => 10, 'taxPercentage' => 18]],
            ])->json('data');
        $this->withHeader('Authorization', 'Bearer '.$token)
            ->postJson('/api/v1/business-invoices/'.$bill['id'].'/finalize')
            ->assertOk();

        return (int) $bill['id'];
    }
}
