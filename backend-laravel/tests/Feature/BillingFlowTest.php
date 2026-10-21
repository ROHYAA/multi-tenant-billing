<?php

namespace Tests\Feature;

use App\Support\Money;
use Database\Seeders\DatabaseSeeder;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Tests\Concerns\CreatesApprovedShop;
use Tests\TestCase;

class BillingFlowTest extends TestCase
{
    use CreatesApprovedShop, RefreshDatabase;

    protected function setUp(): void
    {
        parent::setUp();
        $this->seed(DatabaseSeeder::class);
    }

    public function test_money_half_up_scale_2(): void
    {
        $this->assertSame('1.23', Money::round('1.225'));
        $this->assertSame('20.00', Money::mul('10', '2'));
        $this->assertSame('3.60', Money::round(bcmul('20.00', '0.18', 8)));
    }

    public function test_customer_product_bill_payment_and_credit_confirm(): void
    {
        $token = $this->approvedOwnerToken();

        $walkin = $this->withHeader('Authorization', 'Bearer '.$token)
            ->getJson('/api/v1/customers/walkin')
            ->assertOk()
            ->json('data.id');

        $this->withHeader('Authorization', 'Bearer '.$token)
            ->deleteJson('/api/v1/customers/'.$walkin)
            ->assertStatus(403)
            ->assertJsonPath('errorCode', 'RES_4003');

        $customer = $this->withHeader('Authorization', 'Bearer '.$token)
            ->postJson('/api/v1/customers', ['name' => 'Acme Traders', 'email' => 'acme@example.com'])
            ->assertCreated()
            ->json('data');

        $product = $this->withHeader('Authorization', 'Bearer '.$token)
            ->postJson('/api/v1/products', [
                'name' => 'Widget',
                'price' => 10,
                'taxPercentage' => 18,
            ])
            ->assertCreated()
            ->json('data');

        $bill = $this->withHeader('Authorization', 'Bearer '.$token)
            ->postJson('/api/v1/business-invoices', [
                'customerId' => $customer['id'],
                'items' => [
                    ['productId' => $product['id'], 'quantity' => 2],
                ],
            ])
            ->assertCreated()
            ->json('data');

        $this->assertSame('INV-0001', $bill['invoiceNumber']);
        $this->assertEquals(20.0, $bill['subtotal']);
        $this->assertEquals(3.6, $bill['taxAmount']);
        $this->assertEquals(23.6, $bill['totalAmount']);
        $this->assertSame('DRAFT', $bill['status']);

        $this->withHeader('Authorization', 'Bearer '.$token)
            ->postJson('/api/v1/business-invoices/'.$bill['id'].'/finalize')
            ->assertOk()
            ->assertJsonPath('data.status', 'OPEN');

        $this->withHeader('Authorization', 'Bearer '.$token)
            ->postJson('/api/v1/business-payments/'.$bill['id'], [
                'amount' => 10,
                'method' => 'CASH',
            ])
            ->assertCreated()
            ->assertJsonPath('data.status', 'CONFIRMED');

        $this->withHeader('Authorization', 'Bearer '.$token)
            ->getJson('/api/v1/business-payments/invoice/'.$bill['id'].'/outstanding')
            ->assertOk()
            ->assertJsonPath('data', 13.6);

        $credit = $this->withHeader('Authorization', 'Bearer '.$token)
            ->postJson('/api/v1/business-payments/'.$bill['id'], [
                'amount' => 13.6,
                'method' => 'CREDIT',
            ])
            ->assertCreated()
            ->json('data');

        $this->assertSame('PENDING', $credit['status']);
        $this->withHeader('Authorization', 'Bearer '.$token)
            ->getJson('/api/v1/business-invoices/'.$bill['id'])
            ->assertJsonPath('data.status', 'OPEN');

        $this->withHeader('Authorization', 'Bearer '.$token)
            ->patchJson('/api/v1/business-payments/'.$credit['id'].'/confirm')
            ->assertOk()
            ->assertJsonPath('data.status', 'CONFIRMED');

        $this->withHeader('Authorization', 'Bearer '.$token)
            ->getJson('/api/v1/business-invoices/'.$bill['id'])
            ->assertJsonPath('data.status', 'PAID');
    }

    public function test_fifo_customer_payment_and_isolation(): void
    {
        $tokenA = $this->approvedOwnerToken('a@example.com', 'Shop A');
        $tokenB = $this->approvedOwnerToken('b@example.com', 'Shop B');

        $custA = $this->withHeader('Authorization', 'Bearer '.$tokenA)
            ->postJson('/api/v1/customers', ['name' => 'Cust A'])
            ->json('data.id');

        $this->withHeader('Authorization', 'Bearer '.$tokenB)
            ->getJson('/api/v1/customers/'.$custA)
            ->assertStatus(404);

        $first = $this->withHeader('Authorization', 'Bearer '.$tokenA)
            ->postJson('/api/v1/business-invoices', [
                'customerId' => $custA,
                'items' => [['description' => 'A', 'quantity' => 1, 'unitPrice' => 100]],
            ])->json('data');
        $second = $this->withHeader('Authorization', 'Bearer '.$tokenA)
            ->postJson('/api/v1/business-invoices', [
                'customerId' => $custA,
                'items' => [['description' => 'B', 'quantity' => 1, 'unitPrice' => 50]],
            ])->json('data');

        $this->withHeader('Authorization', 'Bearer '.$tokenA)
            ->postJson('/api/v1/business-invoices/'.$first['id'].'/finalize')->assertOk();
        $this->withHeader('Authorization', 'Bearer '.$tokenA)
            ->postJson('/api/v1/business-invoices/'.$second['id'].'/finalize')->assertOk();

        $fifo = $this->withHeader('Authorization', 'Bearer '.$tokenA)
            ->postJson('/api/v1/business-payments/customer/'.$custA, [
                'amount' => 120,
                'method' => 'UPI',
            ])
            ->assertCreated()
            ->json('data');

        $this->assertSame(1, $fifo['billsCompleted']);
        $this->assertCount(2, $fifo['payments']);
        $this->assertEquals(100.0, $fifo['payments'][0]['amount']);
        $this->assertEquals(20.0, $fifo['payments'][1]['amount']);

        $this->withHeader('Authorization', 'Bearer '.$tokenA)
            ->getJson('/api/v1/business-invoices/'.$first['id'])
            ->assertJsonPath('data.status', 'PAID');
        $this->withHeader('Authorization', 'Bearer '.$tokenA)
            ->getJson('/api/v1/business-invoices/'.$second['id'])
            ->assertJsonPath('data.status', 'OPEN');

        $this->withHeader('Authorization', 'Bearer '.$tokenA)
            ->getJson('/api/v1/shop-settings')
            ->assertOk()
            ->assertJsonPath('success', true);

        $this->withHeader('Authorization', 'Bearer '.$tokenA)
            ->getJson('/api/v1/reports/outstanding')
            ->assertOk();
    }
}
