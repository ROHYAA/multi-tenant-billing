<?php

namespace App\Services;

use App\Exceptions\ApiException;
use App\Models\Bill;
use App\Models\BillItem;
use App\Models\Payment;
use App\Models\ShopSetting;
use App\Services\Concerns\DrawsShopLedgerPdf;
use App\Support\AmountInWords;
use App\Support\Money;
use App\Support\SimplePdf;
use Illuminate\Support\Collection;

/**
 * Client-facing tax invoice: navy/white card layout with a gold grand total.
 * A4 gets the full two-column design; thermal rolls get the same content stacked.
 */
class BillPdfService
{
    use DrawsShopLedgerPdf;

    public function __construct(
        private BillService $bills,
        private AttachmentService $attachments,
    ) {}

    public function generate(int $invoiceId, ?string $copyType = null): array
    {
        $bill = $this->bills->findForPdf($invoiceId);
        $settings = ShopSetting::query()->first();
        if (! $settings) {
            throw ApiException::notFound('ShopSettings', 'current');
        }
        $items = BillItem::query()->where('bill_id', $bill->id)->orderBy('id')->get();
        $payments = Payment::query()
            ->where('bill_id', $bill->id)
            ->where('status', '!=', 'CANCELLED')
            ->orderBy('id')
            ->get();
        $view = $this->view($bill, $settings, $payments, $copyType);

        $paper = (string) ($settings->paper_size ?? 'A4');
        $pdf = $this->pageFor($paper);
        if ($paper === 'A4') {
            $this->renderA4($pdf, $view, $items);
        } else {
            $this->renderThermal($pdf, $view, $items);
        }

        return [
            'bytes' => $pdf->bytes(),
            'invoiceNumber' => (string) $bill->invoice_number,
        ];
    }

    /**
     * Everything the layouts print, already formatted.
     *
     * @param  Collection<int, Payment>  $payments
     */
    private function view(Bill $bill, ShopSetting $settings, Collection $payments, ?string $copyType): array
    {
        $shop = $this->shopView($settings);
        $customer = $bill->customer;

        $tax = Money::round($bill->tax_amount);
        $cgst = Money::round(bcdiv($tax, '2', 4));
        $sgst = Money::sub($tax, $cgst);

        $paidVia = $payments->where('status', 'CONFIRMED')->pluck('method')->filter()->unique()->values()->all();
        if ($payments->where('status', 'PENDING')->isNotEmpty()) {
            $paidVia[] = 'CREDIT (pending)';
        }

        $notes = [
            'Please make all payments payable to '.$shop['shopName'].'.',
            'Payment is due as per the terms on this invoice.',
            'If you have any questions, feel free to contact us.',
        ];
        foreach ([$bill->notes, $settings->terms_and_conditions, $settings->warranty_text] as $extra) {
            if (trim((string) $extra) !== '') {
                $notes[] = trim((string) $extra);
            }
        }
        if ($settings->show_qr_code && $settings->upi_id) {
            $notes[] = 'UPI: '.$settings->upi_id;
        }

        $customerLines = [];
        if ($customer?->phone) {
            $customerLines[] = (string) $customer->phone;
        }
        if ($settings->show_customer_address && $customer?->address) {
            $customerLines[] = (string) $customer->address;
        }
        if ($customer?->gstin) {
            $customerLines[] = 'GSTIN: '.$customer->gstin;
        }

        $discount = Money::cmp($bill->discount_amount ?? 0, '0') > 0 ? Money::round($bill->discount_amount) : null;

        return $shop + [
            'showSignature' => (bool) $settings->show_signature,
            'signatureId' => $settings->signature_attachment_id ? (int) $settings->signature_attachment_id : null,
            'copyType' => $copyType ? strtoupper($copyType) : null,
            'invoiceNumber' => (string) $bill->invoice_number,
            'date' => $this->fmtDate($bill->created_at, $shop['tz']),
            'due' => $bill->due_date ? $this->fmtDate($bill->due_date, $shop['tz']) : null,
            'status' => strtoupper((string) $bill->status),
            'customerName' => (string) ($customer?->name ?: 'Customer'),
            'customerLines' => $customerLines,
            'subtotal' => $this->rupees($bill->subtotal),
            'cgst' => $this->rupees($cgst),
            'sgst' => $this->rupees($sgst),
            'discount' => $discount !== null ? '- '.$this->rupees($discount) : null,
            'total' => $this->rupees($bill->total_amount),
            'words' => $settings->show_amount_in_words ? AmountInWords::rupees($bill->total_amount, (string) $bill->currency) : null,
            'paidVia' => $paidVia ? implode(', ', $paidVia) : 'Not paid yet',
            'notes' => $notes,
            'footer' => trim((string) ($settings->footer_message ?: 'Thank you for your business!')),
        ];
    }

    /**
     * @param  Collection<int, BillItem>  $items
     */
    private function renderA4(SimplePdf $pdf, array $v, Collection $items): void
    {
        $W = $pdf->pageWidth();
        $L = 42.0;
        $R = $W - 42.0;
        $iw = $R - $L;

        $this->pageCard($pdf);
        $y = $this->drawA4Header($pdf, $v, 'TAX INVOICE', $v['copyType'], $L, $iw);
        $boxLines = ['Invoice #: '.$v['invoiceNumber'], 'Date: '.$v['date']];
        if ($v['due']) {
            $boxLines[] = 'Due: '.$v['due'];
        }
        $y = $this->drawContactAndBox($pdf, $v, $y, $boxLines, $v['status'], $L, $iw);

        // Bill to.
        $pdf->text('Bill To', $L, $y, 12, 'B', self::INK);
        $y -= 19;
        $pdf->text($pdf->truncate($v['customerName'], 14.5, $iw, true), $L, $y, 14.5, 'B', self::INK);
        $y -= 16;
        foreach ($v['customerLines'] as $line) {
            $pdf->text($pdf->truncate($line, 10.5, $iw * 0.6), $L, $y, 10.5, 'R', self::BODY);
            $y -= 14;
        }
        $y -= 12;

        $y = $this->itemsTableA4($pdf, $items, $L, $iw, $y);
        $y -= 20;

        // Totals card (right) with notes in the space beside it (left).
        $tW = 235.0;
        $tX = $R - $tW;
        $rows = [['Subtotal', $v['subtotal']], ['CGST', $v['cgst']], ['SGST', $v['sgst']]];
        if ($v['discount']) {
            $rows[] = ['Discount', $v['discount']];
        }
        $rowH = 21.0;
        $panelH = 10 + count($rows) * $rowH + 6;
        $barH = 38.0;
        $noteW = $tX - $L - 26 - 14;
        $noteLines = [];
        foreach ($v['notes'] as $note) {
            array_push($noteLines, ...$pdf->lines($note, 10, $noteW));
        }
        $notesH = 26 + count($noteLines) * 14;
        $blockH = max($panelH + $barH, $notesH);
        $y = $this->room($pdf, $y, $blockH + 10);

        $ny = $y - 14;
        $this->icon($pdf, 'doc', $L, $ny - 3, 14);
        $pdf->text('Notes:', $L + 26, $ny, 12, 'B', self::INK);
        $ny -= 17;
        foreach ($noteLines as $line) {
            $pdf->text($line, $L + 26, $ny, 10, 'R', self::BODY);
            $ny -= 14;
        }

        $cardBottom = $y - $panelH - $barH;
        $pdf->roundRect($tX, $cardBottom, $tW, $panelH + $barH, 7, self::SOFT, self::BORDER, 0.8);
        $pdf->roundRect($tX, $cardBottom, $tW, $barH, 7, self::NAVY);
        $pdf->fillBox($tX, $cardBottom + $barH / 2, $tW, $barH / 2, self::NAVY);
        $ry = $y - 25;
        foreach ($rows as [$label, $value]) {
            $pdf->text($label, $tX + 16, $ry, 11.5, 'R', self::INK);
            $pdf->text($value, $tX + 16, $ry, 11.5, 'B', self::INK, 'R', $tW - 32);
            $ry -= $rowH;
        }
        $barBase = $cardBottom + $barH / 2 - 4.5;
        $pdf->text('GRAND TOTAL', $tX + 16, $barBase, 12.5, 'B', self::WHITE);
        $pdf->text($v['total'], $tX + 16, $barBase - 0.5, 16, 'B', self::GOLD, 'R', $tW - 32);
        $y -= $blockH + 18;

        // Amount in words | paid via.
        $mid = $L + $iw * 0.57;
        $payX = $v['words'] ? $mid + 20 : $L;
        $wordLines = $v['words'] ? $pdf->lines($v['words'], 10.5, $mid - $L - 44) : [];
        $payLines = $pdf->lines($v['paidVia'], 10.5, $R - $payX - 26);
        $sectionH = 17 + max(count($wordLines), count($payLines)) * 14;
        $y = $this->room($pdf, $y, $sectionH + 30);
        $pdf->strokeLine($L, $y, $R, $y, self::BORDER, 1);
        $y -= 26;
        $sectionTop = $y + 14;
        if ($wordLines) {
            $this->icon($pdf, 'receipt', $L, $y - 3, 14);
            $pdf->text('Amount in words:', $L + 26, $y, 12, 'B', self::INK);
            $ly = $y - 17;
            foreach ($wordLines as $line) {
                $pdf->text($line, $L + 26, $ly, 10.5, 'R', self::BODY);
                $ly -= 14;
            }
        }
        $this->icon($pdf, 'wallet', $payX, $y - 3, 14);
        $pdf->text('Paid via:', $payX + 26, $y, 12, 'B', self::INK);
        $py = $y - 17;
        foreach ($payLines as $line) {
            $pdf->text($line, $payX + 26, $py, 10.5, 'R', self::BODY);
            $py -= 14;
        }
        $sectionBottom = $y - $sectionH + 6;
        if ($wordLines) {
            $pdf->strokeLine($mid, $sectionTop, $mid, $sectionBottom, self::BORDER, 1);
        }
        $y = $sectionBottom - 22;

        // Thank you + signature. Sits low on the page when there is room.
        $sigW = 150.0;
        $sigH = 46.0;
        $y = $this->room($pdf, $y, $sigH + 34);
        $y = min($y, 46 + 14 + $sigH + 16);
        $lineY = $y - $sigH - 6;
        if ($v['showSignature']) {
            $this->placeAttachment($pdf, $v['signatureId'], $R - $sigW + 10, $y, $sigW - 20, $sigH);
            $pdf->strokeLine($R - $sigW, $lineY, $R, $lineY, [148, 163, 184], 0.8);
            $pdf->text('Authorized signatory', $R - $sigW, $lineY - 14, 10.5, 'R', self::BODY, 'C', $sigW);
        }
        $pdf->text($pdf->truncate($v['footer'], 13, $iw - $sigW - 20, true), $L, $lineY - 14, 13, 'BI', self::NAVY);
    }

    /**
     * Bordered item grid. Returns the y of the table's bottom edge.
     *
     * @param  Collection<int, BillItem>  $items
     */
    private function itemsTableA4(SimplePdf $pdf, Collection $items, float $L, float $iw, float $y): float
    {
        $ratios = [0.075, 0.385, 0.10, 0.145, 0.145, 0.15];
        $widths = array_map(fn ($r) => $iw * $r, $ratios);
        $xs = [$L];
        foreach ($widths as $w) {
            $xs[] = end($xs) + $w;
        }
        $tableTop = $y;
        $y = $this->tableHeader($pdf, $xs, $widths, $L, $iw, $y);
        $rowEdges = [$y];

        $n = 1;
        foreach ($items as $item) {
            $descLines = array_slice($pdf->lines((string) $item->description, 10.5, $widths[1] - 18), 0, 3);
            $imei = $item->imei ? 'IMEI: '.$item->imei : null;
            $blockH = count($descLines) * 13 + ($imei ? 12 : 0);
            $rowH = max(30.0, $blockH + 16);
            if ($y - $rowH < 50) {
                $this->tableGrid($pdf, $xs, $L, $iw, $tableTop, $y, $rowEdges);
                $pdf->newPage();
                $this->pageCard($pdf);
                $tableTop = $pdf->pageHeight() - 52;
                $y = $this->tableHeader($pdf, $xs, $widths, $L, $iw, $tableTop);
                $rowEdges = [$y];
            }
            $mid = $y - $rowH / 2 - 3.6;

            $pct = rtrim(rtrim(Money::round($item->tax_percentage), '0'), '.') ?: '0';
            $cells = [
                0 => [sprintf('%02d', $n), 'R'],
                2 => [Money::round($item->quantity), 'R'],
                3 => [$this->indian(Money::round($item->unit_price)), 'R'],
                4 => [$this->indian(Money::round($item->tax_amount)).' ('.$pct.'%)', 'R'],
                5 => [$this->indian(Money::round($item->total)), 'B'],
            ];
            foreach ($cells as $i => [$text, $style]) {
                $pdf->text($pdf->truncate($text, 10.5, $widths[$i] - 10, $style === 'B'), $xs[$i] + 5, $mid, 10.5, $style, self::INK, 'C', $widths[$i] - 10);
            }
            $ly = $y - ($rowH - $blockH) / 2 - 9.5;
            foreach ($descLines as $line) {
                $pdf->text($line, $xs[1] + 9, $ly, 10.5, 'R', self::INK);
                $ly -= 13;
            }
            if ($imei) {
                $pdf->text($imei, $xs[1] + 9, $ly + 1, 9, 'R', self::MUTED);
            }

            $y -= $rowH;
            $rowEdges[] = $y;
            $n++;
        }
        $this->tableGrid($pdf, $xs, $L, $iw, $tableTop, $y, $rowEdges);

        return $y;
    }

    /**
     * @param  list<float>  $xs
     * @param  list<float>  $widths
     */
    private function tableHeader(SimplePdf $pdf, array $xs, array $widths, float $L, float $iw, float $y): float
    {
        $headers = ['#', 'Description', 'Qty', 'Rate ('.self::RUPEE.')', 'GST', 'Amount ('.self::RUPEE.')'];
        $headH = 30.0;
        $pdf->fillBox($L, $y - $headH, $iw, $headH, self::HEAD);
        foreach ($headers as $i => $label) {
            $pdf->text($label, $xs[$i], $y - $headH / 2 - 3.6, 10.5, 'B', self::INK, 'C', $widths[$i]);
        }

        return $y - $headH;
    }

    /**
     * @param  list<float>  $xs
     * @param  list<float>  $rowEdges
     */
    private function tableGrid(SimplePdf $pdf, array $xs, float $L, float $iw, float $top, float $bottom, array $rowEdges): void
    {
        $pdf->strokeRect($L, $bottom, $iw, $top - $bottom, self::BORDER, 0.9);
        foreach (array_slice($rowEdges, 0, -1) as $edge) {
            $pdf->strokeLine($L, $edge, $L + $iw, $edge, self::BORDER, 0.9);
        }
        foreach (array_slice($xs, 1, -1) as $x) {
            $pdf->strokeLine($x, $top, $x, $bottom, self::BORDER, 0.9);
        }
    }

    /**
     * Narrow receipt: same content, single column.
     *
     * @param  Collection<int, BillItem>  $items
     */
    private function renderThermal(SimplePdf $pdf, array $v, Collection $items): void
    {
        $W = $pdf->pageWidth();
        $m = $pdf->margin();
        $L = $m;
        $iw = $W - 2 * $m;
        $s = $W < 200 ? 7.0 : 8.5;
        $y = $pdf->pageHeight() - $m;

        $r = $s * 2.4;
        $this->logoBadge($pdf, $v, $W / 2, $y - $r, $r, $s * 1.5);
        $y -= 2 * $r + $s * 1.9;
        $y = $this->centered($pdf, $v['shopName'], $y, $s * 1.6, 'B', self::NAVY, $L, $iw);
        if ($v['subtitle'] !== '') {
            $y = $this->centered($pdf, $v['subtitle'], $y, $s, 'R', self::BODY, $L, $iw);
        }
        $y -= $s * 0.4;
        $y = $this->centered($pdf, 'TAX INVOICE', $y, $s * 1.3, 'B', self::NAVY, $L, $iw);
        if ($v['copyType']) {
            $y = $this->centered($pdf, $v['copyType'], $y, $s * 0.9, 'R', self::MUTED, $L, $iw);
        }
        foreach ($this->contactRows($v) as [, $text]) {
            $y = $this->centered($pdf, $text, $y, $s * 0.95, 'R', self::BODY, $L, $iw);
        }
        $y = $this->divider($pdf, $L, $iw, $y, $s);

        $info = [['Invoice #', $v['invoiceNumber']], ['Date', $v['date']]];
        if ($v['due']) {
            $info[] = ['Due', $v['due']];
        }
        $info[] = ['Status', $v['status']];
        foreach ($info as [$label, $value]) {
            $pdf->text($label, $L, $y, $s, 'R', self::BODY);
            $pdf->text($value, $L, $y, $s, 'B', self::NAVY, 'R', $iw);
            $y -= $s * 1.5;
        }
        $y = $this->divider($pdf, $L, $iw, $y, $s);

        $pdf->text('Bill To', $L, $y, $s, 'B', self::INK);
        $y -= $s * 1.4;
        foreach ($pdf->lines($v['customerName'], $s * 1.15, $iw, true) as $line) {
            $pdf->text($line, $L, $y, $s * 1.15, 'B', self::INK);
            $y -= $s * 1.45;
        }
        foreach ($v['customerLines'] as $line) {
            $pdf->text($pdf->truncate($line, $s * 0.9, $iw), $L, $y, $s * 0.9, 'R', self::BODY);
            $y -= $s * 1.3;
        }
        $y -= $s * 0.6;

        // Items: Description | Qty | Amount, with rate/GST/IMEI on sub-lines.
        $wDesc = $iw * 0.52;
        $wQty = $iw * 0.14;
        $wAmt = $iw - $wDesc - $wQty;
        $headH = $s * 2.2;
        $pdf->fillBox($L, $y - $headH, $iw, $headH, self::HEAD);
        $hb = $y - $headH / 2 - $s * 0.35;
        $pdf->text('Description', $L + 3, $hb, $s, 'B', self::INK);
        $pdf->text('Qty', $L + $wDesc, $hb, $s, 'B', self::INK, 'C', $wQty);
        $pdf->text('Amount ('.self::RUPEE.')', $L + $wDesc + $wQty, $hb, $s, 'B', self::INK, 'R', $wAmt - 3);
        $y -= $headH + $s * 1.2;
        foreach ($items as $item) {
            $pct = rtrim(rtrim(Money::round($item->tax_percentage), '0'), '.') ?: '0';
            $first = true;
            foreach ($pdf->lines((string) $item->description, $s, $wDesc - 3) as $line) {
                $pdf->text($line, $L + 3, $y, $s, 'R', self::INK);
                if ($first) {
                    $pdf->text(Money::round($item->quantity), $L + $wDesc, $y, $s, 'R', self::INK, 'C', $wQty);
                    $pdf->text($this->indian(Money::round($item->total)), $L + $wDesc + $wQty, $y, $s, 'B', self::INK, 'R', $wAmt - 3);
                    $first = false;
                }
                $y -= $s * 1.3;
            }
            $sub = '@ '.$this->indian(Money::round($item->unit_price)).'  GST '.$pct.'%';
            $pdf->text($pdf->truncate($sub, $s * 0.85, $iw - 3), $L + 3, $y, $s * 0.85, 'R', self::MUTED);
            $y -= $s * 1.2;
            if ($item->imei) {
                $pdf->text('IMEI: '.$item->imei, $L + 3, $y, $s * 0.85, 'R', self::MUTED);
                $y -= $s * 1.2;
            }
            $pdf->strokeLine($L, $y + $s * 0.55, $L + $iw, $y + $s * 0.55, self::BORDER, 0.6);
            $y -= $s * 0.7;
        }
        $y -= $s * 0.3;

        $rows = [['Subtotal', $v['subtotal']], ['CGST', $v['cgst']], ['SGST', $v['sgst']]];
        if ($v['discount']) {
            $rows[] = ['Discount', $v['discount']];
        }
        foreach ($rows as [$label, $value]) {
            $pdf->text($label, $L + 3, $y, $s, 'R', self::INK);
            $pdf->text($value, $L, $y, $s, 'B', self::INK, 'R', $iw - 3);
            $y -= $s * 1.5;
        }
        $barH = $s * 2.8;
        $barBottom = $y - $barH + $s;
        $pdf->roundRect($L, $barBottom, $iw, $barH, $s * 0.5, self::NAVY);
        $bb = $barBottom + $barH / 2 - $s * 0.38;
        $pdf->text('GRAND TOTAL', $L + $s * 0.8, $bb, $s, 'B', self::WHITE);
        $pdf->text($v['total'], $L, $bb, $s * 1.2, 'B', self::GOLD, 'R', $iw - $s * 0.8);
        $y = $barBottom - $s * 1.8;

        if ($v['words']) {
            $pdf->text('Amount in words:', $L, $y, $s * 0.9, 'B', self::INK);
            $y -= $s * 1.3;
            foreach ($pdf->lines($v['words'], $s * 0.85, $iw) as $line) {
                $pdf->text($line, $L, $y, $s * 0.85, 'R', self::BODY);
                $y -= $s * 1.2;
            }
            $y -= $s * 0.4;
        }
        $pdf->text('Paid via: ', $L, $y, $s * 0.9, 'B', self::INK);
        $pdf->text($pdf->truncate($v['paidVia'], $s * 0.9, $iw - $s * 4.5), $L + $pdf->measure('Paid via: ', $s * 0.9, true), $y, $s * 0.9, 'R', self::BODY);
        $y -= $s * 1.5;
        foreach (array_slice($v['notes'], 3) as $note) {
            foreach ($pdf->lines($note, $s * 0.85, $iw) as $line) {
                $pdf->text($line, $L, $y, $s * 0.85, 'R', self::BODY);
                $y -= $s * 1.2;
            }
        }

        if ($v['showSignature']) {
            $sigW = $iw * 0.6;
            $sigH = $s * 4;
            $y -= $s * 0.6;
            $this->placeAttachment($pdf, $v['signatureId'], $L + ($iw - $sigW) / 2, $y, $sigW, $sigH);
            $y -= $sigH + $s * 0.4;
            $pdf->strokeLine($L + ($iw - $sigW) / 2, $y, $L + ($iw + $sigW) / 2, $y, [148, 163, 184], 0.6);
            $y -= $s * 1.2;
            $y = $this->centered($pdf, 'Authorized signatory', $y, $s * 0.85, 'R', self::BODY, $L, $iw);
        }
        $y -= $s * 0.6;
        $y = $this->centered($pdf, $v['footer'], $y, $s * 1.05, 'BI', self::NAVY, $L, $iw);
        // Receipt rolls: cut the page just below the content so no blank paper feeds out.
        $pdf->trimBottom(max(0.0, $y - $m));
    }

    private function divider(SimplePdf $pdf, float $x, float $w, float $y, float $s): float
    {
        $y += $s * 0.3;
        $pdf->strokeLine($x, $y, $x + $w, $y, self::BORDER, 0.7);

        return $y - $s * 1.6;
    }

    private function pageFor(string $paper): SimplePdf
    {
        return match ($paper) {
            'THERMAL_58MM' => new SimplePdf(164.41, 700, 10),
            'THERMAL_80MM' => new SimplePdf(226.77, 900, 12),
            default => new SimplePdf(595.28, 841.89, 36),
        };
    }
}
