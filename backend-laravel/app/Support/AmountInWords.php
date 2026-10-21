<?php

namespace App\Support;

class AmountInWords
{
    public static function rupees(mixed $amount, string $currency = 'INR'): string
    {
        $rounded = Money::round($amount, 2);
        if (! str_contains($rounded, '.')) {
            $rounded .= '.00';
        }
        [$nPart, $pPart] = array_pad(explode('.', $rounded, 2), 2, '00');
        $n = (int) $nPart;
        $paise = (int) substr($pPart.'00', 0, 2);
        $head = $currency === 'INR' ? 'Rupees ' : $currency.' ';
        if ($n === 0) {
            $words = $head.'Zero';
        } else {
            $words = $head.self::indian($n);
        }
        if ($paise > 0) {
            $words .= ' and '.$paise.'/100';
        }

        return $words.' Only';
    }

    private static function indian(int $n): string
    {
        $n = abs($n);
        $ones = ['', 'One', 'Two', 'Three', 'Four', 'Five', 'Six', 'Seven', 'Eight', 'Nine', 'Ten',
            'Eleven', 'Twelve', 'Thirteen', 'Fourteen', 'Fifteen', 'Sixteen', 'Seventeen', 'Eighteen', 'Nineteen'];
        $tens = ['', '', 'Twenty', 'Thirty', 'Forty', 'Fifty', 'Sixty', 'Seventy', 'Eighty', 'Ninety'];
        $chunk = function (int $v) use ($ones, $tens, &$chunk): string {
            if ($v === 0) {
                return '';
            }
            if ($v < 20) {
                return $ones[$v];
            }
            if ($v < 100) {
                return trim($tens[intdiv($v, 10)].' '.$ones[$v % 10]);
            }
            return trim($ones[intdiv($v, 100)].' Hundred '.$chunk($v % 100));
        };
        $crore = intdiv($n, 10000000);
        $n %= 10000000;
        $lakh = intdiv($n, 100000);
        $n %= 100000;
        $thousand = intdiv($n, 1000);
        $rest = $n % 1000;
        $parts = [];
        if ($crore) {
            $parts[] = $chunk($crore).' Crore';
        }
        if ($lakh) {
            $parts[] = $chunk($lakh).' Lakh';
        }
        if ($thousand) {
            $parts[] = $chunk($thousand).' Thousand';
        }
        if ($rest) {
            $parts[] = $chunk($rest);
        }

        return trim(implode(' ', $parts));
    }
}
