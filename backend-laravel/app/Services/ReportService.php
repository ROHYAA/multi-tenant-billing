<?php

namespace App\Services;

use App\Exceptions\ApiException;
use App\Models\Bill;
use App\Models\Payment;
use App\Support\Money;
use Illuminate\Support\Carbon;

class ReportService
{
    public function __construct(private PaymentService $payments) {}

    public function revenue(string $from, string $to): array
    {
        $fromAt = Carbon::parse($from);
        $toAt = Carbon::parse($to);
        if ($fromAt->gt($toAt)) {
            throw ApiException::invalid("'from' must be before 'to'");
        }
        $totalRevenue = Payment::query()
            ->where('status', 'CONFIRMED')
            ->whereBetween('paid_at', [$fromAt, $toAt])
            ->sum('amount');
        $paidInvoices = Bill::query()->where('status', 'PAID')->whereBetween('created_at', [$fromAt, $toAt])->get();
        $avg = '0.00';
        if ($paidInvoices->isNotEmpty()) {
            $sum = '0.00';
            foreach ($paidInvoices as $inv) {
                $sum = Money::add($sum, $inv->total_amount);
            }
            $avg = Money::round(bcdiv($sum, (string) $paidInvoices->count(), 8));
        }
        $byMethod = Payment::query()
            ->where('status', 'CONFIRMED')
            ->whereBetween('paid_at', [$fromAt, $toAt])
            ->selectRaw('method, SUM(amount) as total')
            ->groupBy('method')
            ->pluck('total', 'method')
            ->map(fn ($v) => Money::num($v))
            ->all();

        return [
            'from' => $fromAt->toISOString(),
            'to' => $toAt->toISOString(),
            'totalRevenue' => Money::num($totalRevenue ?: 0),
            'paidInvoiceCount' => $paidInvoices->count(),
            'averageInvoiceValue' => Money::num($avg),
            'revenueByMethod' => $byMethod,
        ];
    }

    public function outstanding(): array
    {
        $now = now();
        $allOpen = Bill::query()->where('status', 'OPEN')->orderBy('id')->get();
        $items = [];
        $overdueAmount = '0.00';
        $overdueCount = 0;
        $totalOutstanding = '0.00';
        foreach ($allOpen as $invoice) {
            $paid = $this->payments->confirmedSum((int) $invoice->id);
            $out = Money::sub($invoice->total_amount, $paid);
            if (Money::cmp($out, '0') < 0) {
                $out = '0.00';
            }
            $isOverdue = $invoice->due_date && $invoice->due_date->lt($now);
            if ($isOverdue) {
                $overdueAmount = Money::add($overdueAmount, $invoice->total_amount);
                $overdueCount++;
            }
            $totalOutstanding = Money::add($totalOutstanding, $invoice->total_amount);
            $items[] = [
                'invoiceId' => $invoice->id,
                'invoiceNumber' => $invoice->invoice_number,
                'customerId' => $invoice->customer_id,
                'totalAmount' => Money::num($invoice->total_amount),
                'outstandingAmount' => Money::num($out),
                'dueDate' => optional($invoice->due_date)?->toISOString(),
                'overdue' => $isOverdue,
            ];
        }
        $currentAmount = Money::sub($totalOutstanding, $overdueAmount);
        if (Money::cmp($currentAmount, '0') < 0) {
            $currentAmount = '0.00';
        }

        return [
            'totalOutstanding' => Money::num($totalOutstanding),
            'overdueAmount' => Money::num($overdueAmount),
            'overdueCount' => $overdueCount,
            'currentAmount' => Money::num($currentAmount),
            'currentCount' => $allOpen->count() - $overdueCount,
            'items' => $items,
        ];
    }

    public function monthly(int $year): array
    {
        if ($year < 2020 || $year > 2100) {
            throw ApiException::invalid('Year must be between 2020 and 2100');
        }
        $rows = [];
        $names = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
        for ($m = 1; $m <= 12; $m++) {
            $from = Carbon::create($year, $m, 1, 0, 0, 0, 'UTC')->startOfDay();
            $to = (clone $from)->endOfMonth()->endOfDay();
            $invoices = Bill::query()->whereBetween('created_at', [$from, $to])->get();
            $invoiceTotal = '0.00';
            foreach ($invoices as $inv) {
                $invoiceTotal = Money::add($invoiceTotal, $inv->total_amount);
            }
            $collected = Payment::query()->where('status', 'CONFIRMED')->whereBetween('paid_at', [$from, $to])->sum('amount');
            $collected = Money::round($collected ?: 0);
            $outstanding = Money::sub($invoiceTotal, $collected);
            if (Money::cmp($outstanding, '0') < 0) {
                $outstanding = '0.00';
            }
            $rows[] = [
                'year' => $year,
                'month' => $m,
                'monthName' => $names[$m - 1],
                'invoiceCount' => $invoices->count(),
                'invoiceTotal' => Money::num($invoiceTotal),
                'collected' => Money::num($collected),
                'outstanding' => Money::num($outstanding),
            ];
        }

        return $rows;
    }
}
