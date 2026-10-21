<?php

namespace App\Services;

use App\Exceptions\ApiException;
use App\Models\ShopSetting;
use App\Models\UsedPhonePurchase;
use App\Services\Concerns\DrawsShopLedgerPdf;
use App\Support\AmountInWords;
use App\Support\SimplePdf;

/**
 * A4 purchase voucher for a second-hand phone bought from a seller:
 * seller identity + ID proof, device identity (IMEI), price paid, photos,
 * the seller's ownership declaration and both signatures. Always A4 — it is a record, not a receipt.
 */
class UsedPhoneVoucherPdfService
{
    use DrawsShopLedgerPdf;

    private const ID_LABELS = [
        'AADHAAR' => 'Aadhaar',
        'PAN' => 'PAN',
        'DRIVING_LICENCE' => 'Driving licence',
        'VOTER_ID' => 'Voter ID',
        'PASSPORT' => 'Passport',
        'OTHER' => 'ID',
    ];

    public function __construct(
        private UsedPhoneService $usedPhones,
        private AttachmentService $attachments,
    ) {}

    public function generate(int $id): array
    {
        $row = $this->usedPhones->entity($id);
        $settings = ShopSetting::query()->first();
        if (! $settings) {
            throw ApiException::notFound('ShopSettings', 'current');
        }
        $pdf = new SimplePdf(595.28, 841.89, 36);
        $this->render($pdf, $this->shopView($settings), $row, $settings);

        return ['bytes' => $pdf->bytes(), 'voucherNumber' => (string) $row->voucher_number];
    }

    private function render(SimplePdf $pdf, array $v, UsedPhonePurchase $p, ShopSetting $settings): void
    {
        $L = 42.0;
        $R = $pdf->pageWidth() - 42.0;
        $iw = $R - $L;
        $tz = $v['tz'];

        $this->pageCard($pdf);
        $y = $this->drawA4Header($pdf, $v, 'PURCHASE VOUCHER', 'Used mobile buy-in', $L, $iw);
        // Voucher strip: number, date/time, status.
        $stripH = 40.0;
        $pdf->roundRect($L, $y - $stripH + 8, $iw, $stripH, 9, self::PANEL);
        $sb = $y - $stripH / 2 + 4;
        $pdf->text('Voucher #: '.$p->voucher_number, $L + 16, $sb, 13, 'B', self::NAVY);
        $when = 'Date: '.$this->fmtDate($p->purchased_at, $tz).', '.$this->fmtDate($p->purchased_at, $tz, 'h:i A');
        $pdf->text($when, $L + $iw * 0.36, $sb, 11.5, 'R', self::INK);
        $status = $p->status === 'SOLD' ? 'SOLD' : 'IN STOCK';
        $pillW = $pdf->measure('Status: '.$status, 10.5, true) + 24;
        $this->statusPill($pdf, $status, $L + $iw - 14 - $pillW, $sb, 10.5);
        $y -= $stripH + 12;

        // Seller | Phone cards.
        $gap = 16.0;
        $cardW = ($iw - $gap) / 2;
        $idLabel = self::ID_LABELS[$p->seller_id_type] ?? $p->seller_id_type;
        $seller = array_values(array_filter([
            ['Name', $p->seller_name, true],
            ['Mobile', $p->seller_mobile, false],
            $p->seller_address ? ['Address', $p->seller_address, false] : null,
            [$idLabel.' no.', $p->seller_id_number, false],
            ['Original bill', $p->has_original_bill ? 'Yes' : 'No', false],
            ['CEIR / KYM', $p->ceir_checked ? 'Checked - not blocked' : 'Not recorded', false],
        ]));
        $phone = array_values(array_filter([
            ['Device', trim($p->brand.' '.$p->model), true],
            $p->color ? ['Colour', $p->color, false] : null,
            $p->storage ? ['RAM / Storage', $p->storage, false] : null,
            ['IMEI 1', $p->imei1, true],
            $p->imei2 ? ['IMEI 2', $p->imei2, true] : null,
            $p->serial_number ? ['Serial no.', $p->serial_number, false] : null,
            $p->condition_grade ? ['Condition', ucwords(strtolower(str_replace('_', ' ', $p->condition_grade))), false] : null,
            $p->battery_health ? ['Battery health', $p->battery_health.'%', false] : null,
            $p->accessories ? ['Accessories', ucwords(strtolower(str_replace(',', ', ', $p->accessories))), false] : null,
        ]));
        $sellerRows = $this->layoutRows($pdf, $seller, $cardW);
        $phoneRows = $this->layoutRows($pdf, $phone, $cardW);
        $cardH = 40 + max($sellerRows['h'], $phoneRows['h']) + 8;
        $y = $this->room($pdf, $y, $cardH + 10);
        $this->infoCard($pdf, 'user', 'Seller Details', $sellerRows['rows'], $L, $y, $cardW, $cardH);
        $this->infoCard($pdf, 'phone', 'Phone Details', $phoneRows['rows'], $L + $cardW + $gap, $y, $cardW, $cardH);
        $y -= $cardH + 18;

        // Amount: words + paid via (left), navy price bar (right).
        $barW = 235.0;
        $barX = $R - $barW;
        $barH = 46.0;
        $words = AmountInWords::rupees($p->purchase_price, 'INR');
        $wordLines = $pdf->lines($words, 10.5, $barX - $L - 46);
        $blockH = max($barH, 17 + count($wordLines) * 14 + 22);
        $y = $this->room($pdf, $y, $blockH + 10);
        $this->icon($pdf, 'receipt', $L, $y - 15, 14);
        $pdf->text('Amount paid to seller (in words):', $L + 26, $y - 12, 12, 'B', self::INK);
        $wy = $y - 29;
        foreach ($wordLines as $line) {
            $pdf->text($line, $L + 26, $wy, 10.5, 'R', self::BODY);
            $wy -= 14;
        }
        $pdf->text('Paid via: ', $L + 26, $wy - 4, 10.5, 'B', self::INK);
        $pdf->text((string) $p->payment_method, $L + 26 + $pdf->measure('Paid via: ', 10.5, true), $wy - 4, 10.5, 'R', self::BODY);
        $pdf->roundRect($barX, $y - $barH, $barW, $barH, 7, self::NAVY);
        $base = $y - $barH / 2 - 4.5;
        $pdf->text('PURCHASE PRICE', $barX + 16, $base, 12, 'B', self::WHITE);
        $pdf->text($this->rupees($p->purchase_price), $barX + 16, $base - 0.5, 16, 'B', self::GOLD, 'R', $barW - 32);
        $y -= $blockH + 16;

        // Photos on record.
        $photos = array_values(array_filter([
            $p->id_proof_attachment_id ? ['ID proof', (int) $p->id_proof_attachment_id] : null,
            $p->seller_photo_attachment_id ? ['Seller photo', (int) $p->seller_photo_attachment_id] : null,
            $p->phone_photo_attachment_id ? ['Phone photo', (int) $p->phone_photo_attachment_id] : null,
        ]));
        if ($photos) {
            $boxH = 84.0;
            $y = $this->room($pdf, $y, $boxH + 44);
            $pdf->text('Photos on record', $L, $y - 2, 12, 'B', self::INK);
            $y -= 14;
            $boxW = ($iw - 2 * 14) / 3;
            foreach ($photos as $i => [$caption, $attachmentId]) {
                $x = $L + $i * ($boxW + 14);
                $pdf->roundRect($x, $y - $boxH, $boxW, $boxH, 6, self::SOFT, self::BORDER, 0.8);
                if (! $this->placeAttachment($pdf, $attachmentId, $x + 6, $y - 6, $boxW - 12, $boxH - 12)) {
                    $pdf->text('Image unavailable', $x, $y - $boxH / 2, 9.5, 'R', self::MUTED, 'C', $boxW);
                }
                $pdf->text($caption, $x, $y - $boxH - 13, 10, 'R', self::BODY, 'C', $boxW);
            }
            $y -= $boxH + 26;
        }

        // Seller declaration.
        $imeis = $p->imei1.($p->imei2 ? ' / '.$p->imei2 : '');
        $declaration = 'I, '.$p->seller_name.', declare that I am the lawful owner of the mobile phone described above '
            .'(IMEI '.$imeis.'). It is not stolen, lost, financed, or under any dispute or legal claim. '
            .'I am selling it to '.$v['shopName'].' of my own free will for '.$this->rupees($p->purchase_price)
            .' and I have received the full amount. If any claim arises about this phone, I will be fully responsible for it.';
        $declLines = $pdf->lines($declaration, 10, $iw - 52);
        $declH = 32 + count($declLines) * 13 + 8;
        $y = $this->room($pdf, $y, $declH + 10);
        $pdf->roundRect($L, $y - $declH, $iw, $declH, 8, self::PANEL);
        $this->icon($pdf, 'shield', $L + 14, $y - 26, 14);
        $pdf->text('Seller declaration', $L + 38, $y - 22, 12, 'B', self::NAVY);
        $dy = $y - 39;
        foreach ($declLines as $line) {
            $pdf->text($line, $L + 38, $dy, 10, 'R', self::INK);
            $dy -= 13;
        }
        $y -= $declH + 14;

        if ($p->status === 'SOLD' && $p->sold_bill_id) {
            $bill = $p->soldBill;
            $buyer = $p->buyer;
            $soldLine = 'Sold on '.$this->fmtDate($p->sold_at, $tz).' on invoice '.($bill?->invoice_number ?? '#'.$p->sold_bill_id)
                .($buyer ? ' to '.$buyer->name.($buyer->phone ? ' ('.$buyer->phone.')' : '') : '').'.';
            $y = $this->room($pdf, $y, 24);
            $pdf->text($pdf->truncate($soldLine, 10.5, $iw, true), $L, $y - 4, 10.5, 'B', self::NAVY);
            $y -= 22;
        }

        // Signatures: seller (left), shop (right).
        $sigW = 170.0;
        $sigH = 36.0;
        $y = $this->room($pdf, $y, $sigH + 44);
        $y = min($y, 46 + 28 + $sigH + 14);
        $lineY = $y - $sigH - 6;
        $this->placeAttachment($pdf, $p->seller_signature_attachment_id ? (int) $p->seller_signature_attachment_id : null, $L + 10, $y, $sigW - 20, $sigH);
        $pdf->strokeLine($L, $lineY, $L + $sigW, $lineY, [148, 163, 184], 0.8);
        $pdf->text("Seller's signature", $L, $lineY - 14, 10.5, 'R', self::BODY, 'C', $sigW);
        $pdf->text($pdf->truncate($p->seller_name, 10, $sigW, true), $L, $lineY - 27, 10, 'B', self::INK, 'C', $sigW);
        if ($settings->show_signature) {
            $this->placeAttachment($pdf, $settings->signature_attachment_id ? (int) $settings->signature_attachment_id : null, $R - $sigW + 10, $y, $sigW - 20, $sigH);
        }
        $pdf->strokeLine($R - $sigW, $lineY, $R, $lineY, [148, 163, 184], 0.8);
        $pdf->text('Authorized signatory', $R - $sigW, $lineY - 14, 10.5, 'R', self::BODY, 'C', $sigW);
        $pdf->text($pdf->truncate('For '.$v['shopName'], 10, $sigW, true), $R - $sigW, $lineY - 27, 10, 'B', self::INK, 'C', $sigW);
    }

    /**
     * Wrap label/value rows for a card of the given width.
     *
     * @param  list<array{0:string,1:string,2:bool}>  $rows
     * @return array{rows: list<array{label:string, lines:list<string>, bold:bool}>, h: float}
     */
    private function layoutRows(SimplePdf $pdf, array $rows, float $cardW): array
    {
        $valueW = $cardW - 16 - 96 - 12;
        $out = [];
        $h = 0.0;
        foreach ($rows as [$label, $value, $bold]) {
            $lines = $pdf->lines((string) $value, 10.5, $valueW, $bold);
            $out[] = ['label' => $label, 'lines' => $lines, 'bold' => $bold];
            $h += count($lines) * 13 + 3.5;
        }

        return ['rows' => $out, 'h' => $h];
    }

    /**
     * @param  list<array{label:string, lines:list<string>, bold:bool}>  $rows
     */
    private function infoCard(SimplePdf $pdf, string $icon, string $title, array $rows, float $x, float $top, float $w, float $h): void
    {
        $pdf->roundRect($x, $top - $h, $w, $h, 8, self::SOFT, self::BORDER, 0.8);
        $this->icon($pdf, $icon, $x + 14, $top - 26, 14);
        $pdf->text($title, $x + 36, $top - 23, 12.5, 'B', self::NAVY);
        $pdf->strokeLine($x + 14, $top - 33, $x + $w - 14, $top - 33, self::BORDER, 0.8);
        $y = $top - 50;
        foreach ($rows as $row) {
            $pdf->text($row['label'], $x + 16, $y, 10, 'R', self::MUTED);
            foreach ($row['lines'] as $line) {
                $pdf->text($line, $x + 16 + 96, $y, 10.5, $row['bold'] ? 'B' : 'R', self::INK);
                $y -= 13;
            }
            $y -= 3.5;
        }
    }
}
