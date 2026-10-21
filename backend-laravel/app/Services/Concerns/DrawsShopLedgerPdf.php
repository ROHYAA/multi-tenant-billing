<?php

namespace App\Services\Concerns;

use App\Models\ShopSetting;
use App\Support\Money;
use App\Support\ShopContext;
use App\Support\SimplePdf;
use Carbon\Carbon;

/**
 * Shared look for ShopLedger documents (tax invoice, purchase voucher):
 * navy/white card, logo badge, icon rows, summary box with a status pill.
 * Expects the using class to have an AttachmentService in $this->attachments.
 */
trait DrawsShopLedgerPdf
{
    protected const NAVY = [24, 49, 83];

    protected const INK = [30, 41, 59];

    protected const BODY = [71, 85, 105];

    protected const MUTED = [100, 116, 139];

    protected const ICON = [51, 65, 85];

    protected const BORDER = [215, 222, 231];

    protected const PANEL = [236, 241, 247];

    protected const HEAD = [243, 246, 250];

    protected const SOFT = [248, 250, 252];

    protected const GOLD = [246, 196, 64];

    protected const WHITE = [255, 255, 255];

    protected const RUPEE = "\u{20B9}";

    /**
     * Shop identity fields every document header prints.
     */
    protected function shopView(ShopSetting $settings): array
    {
        return [
            'shopName' => trim((string) ($settings->business_name ?: ShopContext::shop()?->name ?: 'Shop')),
            'subtitle' => trim(implode(', ', array_filter([$settings->city, $settings->state]))),
            'address' => trim(implode(', ', array_filter([
                $settings->address,
                trim(implode(' ', array_filter([$settings->city, $settings->state, $settings->pincode]))),
            ]))),
            'phone' => (string) ($settings->mobile ?? ''),
            'email' => (string) ($settings->email ?? ''),
            'gstin' => ($settings->show_gst && $settings->gstin) ? (string) $settings->gstin : '',
            'logoId' => ($settings->show_logo && $settings->logo_attachment_id) ? (int) $settings->logo_attachment_id : null,
            'tz' => (string) ($settings->timezone ?: 'Asia/Kolkata'),
        ];
    }

    /**
     * A4 header: logo badge, shop name + city, document title + subtitle, divider.
     * Returns the y to continue drawing from.
     */
    protected function drawA4Header(SimplePdf $pdf, array $v, string $title, ?string $titleSub, float $L, float $iw): float
    {
        $r = 30.0;
        $cx = $L + $r;
        $cy = $pdf->pageHeight() - 46 - $r;
        $this->logoBadge($pdf, $v, $cx, $cy, $r, 20);
        $nx = $cx + $r + 16;
        $nameW = ($L + $iw * 0.58) - $nx;
        $hasSub = $v['subtitle'] !== '';
        $pdf->text($pdf->truncate($v['shopName'], 21, $nameW, true), $nx, $cy + ($hasSub ? 3 : -7), 21, 'B', self::NAVY);
        if ($hasSub) {
            $pdf->text($pdf->truncate($v['subtitle'], 11, $nameW), $nx, $cy - 14, 11, 'R', self::BODY);
        }
        $pdf->text($title, $L, $cy + ($titleSub ? 3 : -7), 20, 'B', self::NAVY, 'R', $iw);
        if ($titleSub) {
            $pdf->text($titleSub, $L, $cy - 14, 11, 'R', self::MUTED, 'R', $iw);
        }
        $y = $cy - $r - 16;
        $pdf->strokeLine($L, $y, $L + $iw, $y, self::BORDER, 1);

        return $y - 24;
    }

    /**
     * Shop contact rows (left) beside a shaded summary box (right).
     * $boxLines[0] is printed bold as the box heading. Returns the y below both, after a divider.
     *
     * @param  list<string>  $boxLines
     */
    protected function drawContactAndBox(SimplePdf $pdf, array $v, float $y, array $boxLines, ?string $status, float $L, float $iw): float
    {
        $R = $L + $iw;
        $boxW = 205.0;
        $boxX = $R - $boxW;
        $leftW = $boxX - $L - 24;
        $top = $y;
        $rowY = $y - 6;
        foreach ($this->contactRows($v) as [$icon, $text]) {
            $this->icon($pdf, $icon, $L, $rowY - 2.5, 12);
            foreach ($pdf->lines($text, 11, $leftW - 24) as $line) {
                $pdf->text($line, $L + 24, $rowY, 11, 'R', self::INK);
                $rowY -= 14;
            }
            $rowY -= 8;
        }
        $leftBottom = $rowY + 14;

        $boxH = 24 + (count($boxLines) - 1) * 18 + 2 + ($status ? 30 : 0) + 18;
        $boxTop = $top + 4;
        $boxBottom = $boxTop - $boxH;
        $pdf->roundRect($boxX, $boxBottom, $boxW, $boxH, 9, self::PANEL);
        $bx = $boxX + 15;
        $by = $boxTop - 24;
        foreach ($boxLines as $i => $line) {
            if ($i === 0) {
                $pdf->text($pdf->truncate($line, 13, $boxW - 30, true), $bx, $by, 13, 'B', self::NAVY);
                $by -= 20;
            } else {
                $pdf->text($pdf->truncate($line, 11.5, $boxW - 30), $bx, $by, 11.5, 'R', self::INK);
                $by -= 18;
            }
        }
        if ($status) {
            $by -= 12;
            $this->statusPill($pdf, $status, $bx, $by, 10.5);
        }

        $y = min($leftBottom, $boxBottom) - 18;
        $pdf->strokeLine($L, $y, $R, $y, self::BORDER, 1);

        return $y - 28;
    }

    /**
     * @return list<array{0:string,1:string}>
     */
    protected function contactRows(array $v): array
    {
        return array_values(array_filter([
            $v['address'] !== '' ? ['pin', $v['address']] : null,
            $v['phone'] !== '' ? ['phone', $v['phone']] : null,
            $v['email'] !== '' ? ['mail', $v['email']] : null,
            $v['gstin'] !== '' ? ['doc', 'GSTIN: '.$v['gstin']] : null,
        ]));
    }

    protected function pageCard(SimplePdf $pdf): void
    {
        $pdf->roundRect(18, 18, $pdf->pageWidth() - 36, $pdf->pageHeight() - 36, 14, self::WHITE, self::BORDER, 0.9);
    }

    /**
     * Start a new A4 page when the next block will not fit above the bottom edge.
     */
    protected function room(SimplePdf $pdf, float $y, float $needed): float
    {
        if ($y - $needed >= 44) {
            return $y;
        }
        $pdf->newPage();
        $this->pageCard($pdf);

        return $pdf->pageHeight() - 52;
    }

    protected function logoBadge(SimplePdf $pdf, array $v, float $cx, float $cy, float $r, float $initialsSize): void
    {
        $pdf->fillCircle($cx, $cy, $r + max(1.5, $r * 0.07), self::NAVY);
        if ($v['logoId'] && $this->placeAttachmentInCircle($pdf, $v['logoId'], $cx, $cy, $r)) {
            return;
        }
        $pdf->fillCircle($cx, $cy, $r, self::PANEL);
        $pdf->text($this->initials($v['shopName']), $cx - $r, $cy - $initialsSize * 0.36, $initialsSize, 'B', self::NAVY, 'C', 2 * $r);
    }

    protected function statusPill(SimplePdf $pdf, string $status, float $x, float $baseline, float $size): void
    {
        $label = 'Status: ';
        $labelW = $pdf->measure($label, $size, true);
        $w = $labelW + $pdf->measure($status, $size, true) + 24;
        $h = $size * 2.1;
        $bottom = $baseline - ($h - $size * 0.72) / 2;
        $pdf->roundRect($x, $bottom, $w, $h, $h / 2, self::NAVY);
        $pdf->text($label, $x + 12, $baseline, $size, 'B', self::WHITE);
        $pdf->text($status, $x + 12 + $labelW, $baseline, $size, 'B', self::GOLD);
    }

    /**
     * Small line icons drawn in a size x size box whose bottom-left is (x, y).
     */
    protected function icon(SimplePdf $pdf, string $kind, float $x, float $y, float $s): void
    {
        $c = self::ICON;
        $lw = max(0.8, $s * 0.08);
        switch ($kind) {
            case 'pin':
                $cx = $x + $s / 2;
                $pdf->strokeCircle($cx, $y + $s * 0.64, $s * 0.3, $c, $lw);
                $pdf->strokePolyline([[$cx - $s * 0.27, $y + $s * 0.5], [$cx, $y], [$cx + $s * 0.27, $y + $s * 0.5]], $c, $lw);
                $pdf->fillCircle($cx, $y + $s * 0.64, $s * 0.1, $c);
                break;
            case 'phone':
                $pdf->roundRect($x + $s * 0.24, $y, $s * 0.52, $s, $s * 0.1, null, $c, $lw);
                $pdf->strokeLine($x + $s * 0.42, $y + $s * 0.14, $x + $s * 0.58, $y + $s * 0.14, $c, $lw);
                break;
            case 'mail':
                $pdf->roundRect($x, $y + $s * 0.12, $s, $s * 0.72, $s * 0.08, null, $c, $lw);
                $pdf->strokePolyline([[$x + $s * 0.06, $y + $s * 0.78], [$x + $s / 2, $y + $s * 0.42], [$x + $s * 0.94, $y + $s * 0.78]], $c, $lw);
                break;
            case 'wallet':
                $pdf->roundRect($x, $y + $s * 0.08, $s, $s * 0.76, $s * 0.12, null, $c, $lw);
                $pdf->roundRect($x + $s * 0.56, $y + $s * 0.32, $s * 0.44, $s * 0.3, $s * 0.06, null, $c, $lw);
                $pdf->fillCircle($x + $s * 0.72, $y + $s * 0.47, $s * 0.05, $c);
                break;
            case 'user':
                $cx = $x + $s / 2;
                $pdf->strokeCircle($cx, $y + $s * 0.7, $s * 0.24, $c, $lw);
                $pdf->roundRect($x + $s * 0.12, $y, $s * 0.76, $s * 0.38, $s * 0.18, null, $c, $lw);
                break;
            case 'shield':
                $pdf->strokePolyline([
                    [$x + $s / 2, $y], [$x + $s * 0.12, $y + $s * 0.3], [$x + $s * 0.12, $y + $s * 0.82],
                    [$x + $s / 2, $y + $s], [$x + $s * 0.88, $y + $s * 0.82], [$x + $s * 0.88, $y + $s * 0.3], [$x + $s / 2, $y],
                ], $c, $lw);
                $pdf->strokePolyline([[$x + $s * 0.33, $y + $s * 0.52], [$x + $s * 0.46, $y + $s * 0.38], [$x + $s * 0.68, $y + $s * 0.64]], $c, $lw);
                break;
            case 'receipt':
                $pdf->strokePolyline([
                    [$x + $s * 0.15, $y + $s], [$x + $s * 0.15, $y], [$x + $s * 0.33, $y + $s * 0.1],
                    [$x + $s * 0.5, $y], [$x + $s * 0.67, $y + $s * 0.1], [$x + $s * 0.85, $y],
                    [$x + $s * 0.85, $y + $s], [$x + $s * 0.15, $y + $s],
                ], $c, $lw);
                foreach ([0.75, 0.55, 0.35] as $f) {
                    $pdf->strokeLine($x + $s * 0.3, $y + $s * $f, $x + $s * 0.7, $y + $s * $f, $c, $lw * 0.9);
                }
                break;
            default: // doc
                $pdf->roundRect($x + $s * 0.12, $y, $s * 0.76, $s, $s * 0.08, null, $c, $lw);
                foreach ([0.74, 0.52, 0.3] as $f) {
                    $pdf->strokeLine($x + $s * 0.28, $y + $s * $f, $x + $s * 0.72, $y + $s * $f, $c, $lw * 0.9);
                }
        }
    }

    protected function centered(SimplePdf $pdf, string $text, float $y, float $size, string $style, array $rgb, float $x, float $w): float
    {
        foreach ($pdf->lines($text, $size, $w, str_contains($style, 'B')) as $line) {
            $pdf->text($line, $x, $y, $size, $style, $rgb, 'C', $w);
            $y -= $size * 1.35;
        }

        return $y;
    }

    protected function initials(string $name): string
    {
        $words = preg_split('/\s+/', trim($name)) ?: [];
        $letters = '';
        foreach (array_slice($words, 0, 2) as $word) {
            $letters .= strtoupper(substr($word, 0, 1));
        }

        return $letters !== '' ? $letters : 'S';
    }

    protected function rupees(mixed $value): string
    {
        return self::RUPEE.' '.$this->indian(Money::round($value));
    }

    /**
     * Indian digit grouping: 1234567.50 -> 12,34,567.50
     */
    protected function indian(string $amount): string
    {
        $negative = str_starts_with($amount, '-');
        $amount = ltrim($amount, '-');
        [$int, $dec] = array_pad(explode('.', $amount, 2), 2, null);
        if (strlen($int) > 3) {
            $head = substr($int, 0, -3);
            $head = preg_replace('/\B(?=(\d{2})+(?!\d))/', ',', $head) ?? $head;
            $int = $head.','.substr($int, -3);
        }

        return ($negative ? '-' : '').$int.($dec !== null ? '.'.$dec : '');
    }

    protected function fmtDate(mixed $value, string $tz, string $format = 'd M Y'): string
    {
        try {
            return Carbon::parse($value)->setTimezone($tz)->format($format);
        } catch (\Throwable) {
            return Carbon::parse($value)->utc()->format($format);
        }
    }

    protected function placeAttachment(SimplePdf $pdf, ?int $attachmentId, float $x, float $topY, float $maxW, float $maxH): bool
    {
        if (! $attachmentId) {
            return false;
        }
        try {
            return $pdf->placeImage($this->attachments->bytes($attachmentId), $x, $topY, $maxW, $maxH, true) !== null;
        } catch (\Throwable) {
            return false;
        }
    }

    protected function placeAttachmentInCircle(SimplePdf $pdf, int $attachmentId, float $cx, float $cy, float $r): bool
    {
        try {
            return $pdf->placeImageInCircle($this->attachments->bytes($attachmentId), $cx, $cy, $r);
        } catch (\Throwable) {
            return false;
        }
    }
}
