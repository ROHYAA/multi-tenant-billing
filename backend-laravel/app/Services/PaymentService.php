<?php

namespace App\Services;

use App\Exceptions\ApiException;
use App\Models\Bill;
use App\Models\OutboxEvent;
use App\Models\Payment;
use App\Support\Money;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Str;

class PaymentService
{
    public function __construct(
        private BillService $bills,
        private CustomerService $customers,
    ) {}

    public function record(int $invoiceId, array $data): array
    {
        return DB::transaction(function () use ($invoiceId, $data) {
            $invoice = $this->bills->entityForUpdate($invoiceId);
            if ($invoice->status !== 'OPEN') {
                throw ApiException::invalid('Payments can only be recorded against OPEN invoices. Current status: '.$invoice->status);
            }
            if (Money::cmp($data['amount'], '0') <= 0) {
                throw ApiException::invalid('Payment amount must be greater than zero.');
            }
            $alreadyPaid = $this->confirmedSum($invoiceId);
            $outstanding = Money::sub($invoice->total_amount, $alreadyPaid);
            if (Money::cmp($data['amount'], $outstanding) > 0) {
                throw ApiException::invalid(sprintf(
                    'Payment amount ₹%.2f exceeds outstanding balance ₹%.2f.',
                    Money::num($data['amount']),
                    Money::num($outstanding)
                ));
            }
            $isCredit = ($data['method'] ?? '') === 'CREDIT';
            $saved = $this->insertPayment($invoice, $data, $isCredit ? 'PENDING' : 'CONFIRMED', null);
            if (! $isCredit && Money::cmp(Money::add($alreadyPaid, $data['amount']), $invoice->total_amount) >= 0) {
                $this->bills->markPaid($invoiceId);
                $this->firePaid($invoice, $saved);
            }

            return $this->toResponse($saved);
        });
    }

    public function confirm(int $paymentId): array
    {
        return DB::transaction(function () use ($paymentId) {
            $payment = Payment::query()->lockForUpdate()->find($paymentId);
            if (! $payment) {
                throw ApiException::notFound('Payment', $paymentId);
            }
            if ($payment->status !== 'PENDING') {
                throw ApiException::invalid('Only a pending payment can be confirmed. Current status: '.$payment->status);
            }
            $invoice = $this->bills->entityForUpdate((int) $payment->bill_id);
            if ($invoice->status === 'PAID') {
                throw ApiException::invalid("This bill was already fully paid by other payments — there's nothing left to confirm this against.");
            }
            $payment->status = 'CONFIRMED';
            $payment->save();
            $totalPaid = $this->confirmedSum((int) $invoice->id);
            if (Money::cmp($totalPaid, $invoice->total_amount) >= 0) {
                $this->bills->markPaid((int) $invoice->id);
                $this->firePaid($invoice, $payment);
            }

            return $this->toResponse($payment->fresh());
        });
    }

    public function recordForCustomer(int $customerId, array $data): array
    {
        return DB::transaction(function () use ($customerId, $data) {
            $this->customers->entity($customerId);
            if (Money::cmp($data['amount'], '0') <= 0) {
                throw ApiException::invalid('Payment amount must be greater than zero.');
            }
            $openBills = $this->bills->openBillsForCustomerForUpdate($customerId);
            if ($openBills->isEmpty()) {
                throw ApiException::invalid('This customer has no outstanding bills to pay.');
            }
            $outstandingByBill = [];
            $totalOutstanding = '0.00';
            foreach ($openBills as $bill) {
                $billOut = Money::sub($bill->total_amount, $this->confirmedSum((int) $bill->id));
                $outstandingByBill[] = $billOut;
                $totalOutstanding = Money::add($totalOutstanding, $billOut);
            }
            if (Money::cmp($data['amount'], $totalOutstanding) > 0) {
                throw ApiException::invalid(sprintf(
                    'Payment amount ₹%.2f exceeds this customer\'s total outstanding balance ₹%.2f.',
                    Money::num($data['amount']),
                    Money::num($totalOutstanding)
                ));
            }
            $isCredit = ($data['method'] ?? '') === 'CREDIT';
            $groupId = (string) Str::uuid();
            $remaining = Money::round($data['amount']);
            $savedPayments = [];
            $billsCompleted = 0;
            foreach ($openBills as $i => $bill) {
                if (Money::cmp($remaining, '0') <= 0) {
                    break;
                }
                $billOutstanding = $outstandingByBill[$i];
                if (Money::cmp($billOutstanding, '0') <= 0) {
                    continue;
                }
                $allocate = Money::cmp($remaining, $billOutstanding) < 0 ? $remaining : $billOutstanding;
                $payload = $data;
                $payload['amount'] = $allocate;
                $saved = $this->insertPayment($bill, $payload, $isCredit ? 'PENDING' : 'CONFIRMED', $groupId);
                $savedPayments[] = $saved;
                if (! $isCredit && Money::cmp($allocate, $billOutstanding) >= 0) {
                    $this->bills->markPaid((int) $bill->id);
                    $billsCompleted++;
                    $this->firePaid($bill, $saved);
                }
                $remaining = Money::sub($remaining, $allocate);
            }

            return [
                'paymentGroupId' => $groupId,
                'totalAmount' => Money::num($data['amount']),
                'billsCompleted' => $billsCompleted,
                'payments' => array_map(fn (Payment $p) => $this->toResponse($p), $savedPayments),
            ];
        });
    }

    public function customerOutstanding(int $customerId): array
    {
        $this->customers->entity($customerId);
        $openBills = $this->bills->openBillsForCustomer($customerId);
        $items = [];
        $total = '0.00';
        foreach ($openBills as $bill) {
            $out = Money::sub($bill->total_amount, $this->confirmedSum((int) $bill->id));
            $total = Money::add($total, $out);
            $items[] = [
                'invoiceId' => $bill->id,
                'invoiceNumber' => $bill->invoice_number,
                'totalAmount' => Money::num($bill->total_amount),
                'outstandingAmount' => Money::num($out),
                'createdAt' => optional($bill->created_at)?->toISOString(),
                'dueDate' => optional($bill->due_date)?->toISOString(),
            ];
        }

        return [
            'customerId' => $customerId,
            'totalOutstanding' => Money::num($total),
            'bills' => $items,
        ];
    }

    public function listByInvoice(int $invoiceId): array
    {
        $this->bills->getById($invoiceId);

        return Payment::query()->where('bill_id', $invoiceId)->orderBy('id')->get()
            ->map(fn (Payment $p) => $this->toResponse($p))->values()->all();
    }

    public function outstanding(int $invoiceId): float
    {
        $bill = Bill::query()->find($invoiceId);
        if (! $bill) {
            throw ApiException::notFound('Bill', $invoiceId);
        }
        $paid = $this->confirmedSum($invoiceId);
        $out = Money::sub($bill->total_amount, $paid);

        return Money::cmp($out, '0') < 0 ? 0.0 : Money::num($out);
    }

    public function confirmedSum(int $invoiceId): string
    {
        $sum = Payment::query()->where('bill_id', $invoiceId)->where('status', 'CONFIRMED')->sum('amount');

        return Money::round($sum ?: 0);
    }

    public function toResponse(Payment $payment): array
    {
        return [
            'id' => $payment->id,
            'invoiceId' => $payment->bill_id,
            'amount' => Money::num($payment->amount),
            'currency' => $payment->currency,
            'method' => $payment->method,
            'status' => $payment->status,
            'notes' => $payment->notes,
            'paidAt' => optional($payment->paid_at)?->toISOString(),
            'createdAt' => optional($payment->created_at)?->toISOString(),
        ];
    }

    private function insertPayment(Bill $invoice, array $data, string $status, ?string $groupId): Payment
    {
        return Payment::query()->create([
            'bill_id' => $invoice->id,
            'amount' => Money::round($data['amount']),
            'currency' => $invoice->currency,
            'method' => $data['method'],
            'status' => $status,
            'notes' => $data['notes'] ?? null,
            'paid_at' => $data['paidAt'] ?? now(),
            'payment_group_id' => $groupId,
            'deleted' => false,
            'version' => 0,
        ]);
    }

    private function firePaid(Bill $invoice, Payment $payment): void
    {
        OutboxEvent::query()->create([
            'event_type' => 'PAYMENT_RECORDED',
            'aggregate_type' => 'Bill',
            'aggregate_id' => (string) $invoice->id,
            'payload' => json_encode(['paymentId' => $payment->id]),
            'event_class' => 'PAYMENT_RECORDED',
            'status' => 'PENDING',
            'deleted' => false,
            'version' => 0,
        ]);
    }
}
