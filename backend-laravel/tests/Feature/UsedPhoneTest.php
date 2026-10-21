<?php

namespace Tests\Feature;

use App\Models\Product;
use App\Models\UsedPhonePurchase;
use Database\Seeders\DatabaseSeeder;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Http\UploadedFile;
use Tests\Concerns\CreatesApprovedShop;
use Tests\TestCase;

class UsedPhoneTest extends TestCase
{
    use CreatesApprovedShop, RefreshDatabase;

    protected function setUp(): void
    {
        parent::setUp();
        $this->seed(DatabaseSeeder::class);
    }

    public function test_buy_in_requires_id_photo_mobile_valid_imei_and_declaration(): void
    {
        $token = $this->approvedOwnerToken();
        $photo = $this->uploadPhoto($token);
        $base = $this->buyInPayload($photo, $this->imei('35693803564380'));

        $missingPhoto = $base;
        unset($missingPhoto['idProofAttachmentId']);
        $this->authJson('POST', '/api/v1/used-phones', $missingPhoto, $token)->assertStatus(400);

        $this->authJson('POST', '/api/v1/used-phones', ['sellerMobile' => '12345'] + $base, $token)
            ->assertStatus(400)
            ->assertJsonPath('message', 'Seller mobile must be a valid 10-digit Indian mobile number.');

        $badImei = substr($base['imei1'], 0, 14).(((int) substr($base['imei1'], -1) + 1) % 10);
        $this->authJson('POST', '/api/v1/used-phones', ['imei1' => $badImei] + $base, $token)->assertStatus(400);
        $this->authJson('POST', '/api/v1/used-phones', ['imei1' => '12345'] + $base, $token)->assertStatus(400);
        $this->authJson('POST', '/api/v1/used-phones', ['declarationAccepted' => false] + $base, $token)->assertStatus(400);
        $this->authJson('POST', '/api/v1/used-phones', ['sellerIdNumber' => '1234'] + $base, $token)->assertStatus(400);

        $this->assertSame(0, UsedPhonePurchase::withoutGlobalScopes()->count());
    }

    public function test_buy_in_creates_voucher_stock_and_blocks_duplicate_imei(): void
    {
        $token = $this->approvedOwnerToken();
        $photo = $this->uploadPhoto($token);
        $imei = $this->imei('35693803564380');

        $created = $this->authJson('POST', '/api/v1/used-phones', $this->buyInPayload($photo, $imei), $token)
            ->assertCreated()
            ->assertJsonPath('data.voucherNumber', 'PUR-0001')
            ->assertJsonPath('data.status', 'IN_STOCK')
            ->assertJsonPath('data.sellerIdNumber', 'XXXX XXXX 9012')
            ->assertJsonPath('data.sellerMobile', '9876543210')
            ->json('data');

        $product = $this->authJson('GET', '/api/v1/products/'.$created['productId'], [], $token)
            ->assertOk()
            ->json('data');
        $this->assertSame($imei, $product['imei']);
        $this->assertSame('Samsung Galaxy A52 8GB/128GB (Used)', $product['name']);
        $this->assertSame(9500, (int) $product['price']);
        $this->assertSame($created['id'], $product['usedPhonePurchaseId']);

        $this->authJson('POST', '/api/v1/used-phones', $this->buyInPayload($photo, $imei), $token)
            ->assertStatus(400);

        $pdf = $this->withHeader('Authorization', 'Bearer '.$token)
            ->get('/api/v1/used-phones/'.$created['id'].'/voucher')
            ->assertOk();
        $body = $pdf->getContent();
        $this->assertStringStartsWith('%PDF-1.4', $body);
        $this->assertStringContainsString('PUR-0001.pdf', (string) $pdf->headers->get('Content-Disposition'));
        foreach (['PURCHASE VOUCHER', 'PUR-0001', $imei, 'Seller declaration', 'Ramesh Patil', 'XXXX XXXX 9012', '/Im1'] as $needle) {
            $this->assertStringContainsString($needle, $body);
        }

        $this->authJson('DELETE', '/api/v1/attachments/'.$photo, [], $token)->assertStatus(400);
    }

    public function test_sale_marks_phone_sold_needs_named_buyer_and_void_returns_it_to_stock(): void
    {
        $token = $this->approvedOwnerToken();
        $photo = $this->uploadPhoto($token);
        $imei = $this->imei('35693803564381');
        $purchase = $this->authJson('POST', '/api/v1/used-phones', $this->buyInPayload($photo, $imei), $token)
            ->assertCreated()
            ->json('data');

        $walkin = $this->authJson('GET', '/api/v1/customers/walkin', [], $token)->json('data.id');
        $this->authJson('POST', '/api/v1/business-invoices', [
            'customerId' => $walkin,
            'items' => [['productId' => $purchase['productId'], 'quantity' => 1]],
        ], $token)->assertStatus(400);

        $buyer = $this->authJson('POST', '/api/v1/customers', ['name' => 'Suresh Jadhav', 'phone' => '9822012345'], $token)
            ->assertCreated()
            ->json('data');

        $this->authJson('POST', '/api/v1/business-invoices', [
            'customerId' => $buyer['id'],
            'items' => [['productId' => $purchase['productId'], 'quantity' => 2]],
        ], $token)->assertStatus(400);

        // Negotiated price: the POS sends a free-text line with the IMEI instead of the productId.
        $bill = $this->authJson('POST', '/api/v1/business-invoices', [
            'customerId' => $buyer['id'],
            'items' => [[
                'description' => 'Samsung Galaxy A52 (Used)',
                'imei' => $imei,
                'quantity' => 1,
                'unitPrice' => 9200,
                'taxPercentage' => 0,
            ]],
        ], $token)->assertCreated()->json('data');
        $this->authJson('POST', '/api/v1/business-invoices/'.$bill['id'].'/finalize', [], $token)->assertOk();

        $sold = $this->authJson('GET', '/api/v1/used-phones/'.$purchase['id'], [], $token)
            ->assertOk()
            ->assertJsonPath('data.status', 'SOLD')
            ->assertJsonPath('data.soldInvoiceNumber', $bill['invoiceNumber'])
            ->assertJsonPath('data.buyerName', 'Suresh Jadhav')
            ->json('data');
        $this->assertNotNull($sold['soldAt']);
        $this->assertFalse((bool) Product::query()->withoutGlobalScopes()->find($purchase['productId'])->is_active);

        $history = $this->authJson('GET', '/api/v1/used-phones/imei-check?imei='.$imei, [], $token)
            ->assertOk()
            ->json('data');
        $this->assertFalse($history['inStock']);
        $this->assertTrue($history['validImei']);
        $this->assertCount(1, $history['purchases']);
        $this->assertSame($bill['invoiceNumber'], $history['sales'][0]['invoiceNumber']);

        // Same phone cannot be billed again while sold.
        $this->authJson('POST', '/api/v1/business-invoices', [
            'customerId' => $buyer['id'],
            'items' => [['description' => 'Phone', 'imei' => $imei, 'quantity' => 1, 'unitPrice' => 100]],
        ], $token)->assertStatus(400);

        $voucher = $this->withHeader('Authorization', 'Bearer '.$token)
            ->get('/api/v1/used-phones/'.$purchase['id'].'/voucher')
            ->getContent();
        $this->assertStringContainsString('Sold on', $voucher);

        $this->authJson('POST', '/api/v1/business-invoices/'.$bill['id'].'/void', [], $token)->assertOk();
        $this->authJson('GET', '/api/v1/used-phones/'.$purchase['id'], [], $token)
            ->assertJsonPath('data.status', 'IN_STOCK')
            ->assertJsonPath('data.soldInvoiceNumber', null);
        $this->assertTrue((bool) Product::query()->withoutGlobalScopes()->find($purchase['productId'])->is_active);
    }

    public function test_phone_can_be_bought_back_after_sale_and_other_shop_cannot_see_records(): void
    {
        $token = $this->approvedOwnerToken();
        $photo = $this->uploadPhoto($token);
        $imei = $this->imei('35693803564382');
        $purchase = $this->authJson('POST', '/api/v1/used-phones', $this->buyInPayload($photo, $imei), $token)->json('data');
        $buyer = $this->authJson('POST', '/api/v1/customers', ['name' => 'Buyer One', 'phone' => '9822000001'], $token)->json('data');
        $bill = $this->authJson('POST', '/api/v1/business-invoices', [
            'customerId' => $buyer['id'],
            'items' => [['productId' => $purchase['productId'], 'quantity' => 1]],
        ], $token)->assertCreated()->json('data');
        $this->authJson('POST', '/api/v1/business-invoices/'.$bill['id'].'/finalize', [], $token)->assertOk();

        $this->authJson('POST', '/api/v1/used-phones', $this->buyInPayload($photo, $imei), $token)
            ->assertCreated()
            ->assertJsonPath('data.voucherNumber', 'PUR-0002');

        $other = $this->approvedOwnerToken('other@example.com', 'Other Shop');
        $this->authJson('GET', '/api/v1/used-phones/'.$purchase['id'], [], $other)->assertStatus(404);
        $this->withHeader('Authorization', 'Bearer '.$other)
            ->get('/api/v1/used-phones/'.$purchase['id'].'/voucher')
            ->assertStatus(404);
        $this->authJson('GET', '/api/v1/used-phones', [], $other)->assertOk()->assertJsonPath('data.totalElements', 0);
        $this->assertSame([], $this->authJson('GET', '/api/v1/used-phones/imei-check?imei='.$imei, [], $other)->json('data.purchases'));
    }

    private function uploadPhoto(string $token): int
    {
        return (int) $this->withHeader('Authorization', 'Bearer '.$token)
            ->post('/api/v1/used-phones/photos?purpose=ID_PROOF', [
                'file' => UploadedFile::fake()->image('aadhaar.jpg', 120, 80),
            ])
            ->assertCreated()
            ->json('data.id');
    }

    private function buyInPayload(int $photoId, string $imei): array
    {
        return [
            'sellerName' => 'Ramesh Patil',
            'sellerMobile' => '+91 98765 43210',
            'sellerAddress' => 'Phaltan, Satara',
            'sellerIdType' => 'AADHAAR',
            'sellerIdNumber' => '1234 5678 9012',
            'idProofAttachmentId' => $photoId,
            'brand' => 'Samsung',
            'model' => 'Galaxy A52',
            'storage' => '8GB/128GB',
            'color' => 'Black',
            'imei1' => $imei,
            'conditionGrade' => 'GOOD',
            'batteryHealth' => 88,
            'accessories' => ['charger', 'box'],
            'hasOriginalBill' => true,
            'ceirChecked' => true,
            'declarationAccepted' => true,
            'purchasePrice' => 7500,
            'sellingPrice' => 9500,
            'paymentMethod' => 'CASH',
        ];
    }

    /**
     * 14 digits + Luhn check digit.
     */
    private function imei(string $body): string
    {
        $sum = 0;
        foreach (str_split(strrev($body)) as $i => $ch) {
            $d = (int) $ch;
            if ($i % 2 === 0) {
                $d *= 2;
                if ($d > 9) {
                    $d -= 9;
                }
            }
            $sum += $d;
        }

        return $body.((10 - $sum % 10) % 10);
    }
}
