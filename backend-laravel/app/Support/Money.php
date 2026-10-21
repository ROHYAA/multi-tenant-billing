<?php

namespace App\Support;

class Money
{
    public static function round(mixed $value, int $scale = 2): string
    {
        $n = is_string($value) ? $value : number_format((float) $value, $scale + 4, '.', '');
        $neg = str_starts_with($n, '-');
        $abs = $neg ? substr($n, 1) : $n;
        $delta = bcpow('10', (string) (-($scale + 1)), $scale + 1);
        $abs = bcadd($abs, bcmul('5', $delta, $scale + 1), $scale + 1);
        $abs = bcadd($abs, '0', $scale);

        return $neg && bccomp($abs, '0', $scale) !== 0 ? '-'.$abs : $abs;
    }

    public static function add(mixed $a, mixed $b, int $scale = 2): string
    {
        return self::round(bcadd(self::round($a, $scale + 2), self::round($b, $scale + 2), $scale + 2), $scale);
    }

    public static function sub(mixed $a, mixed $b, int $scale = 2): string
    {
        return self::round(bcsub(self::round($a, $scale + 2), self::round($b, $scale + 2), $scale + 2), $scale);
    }

    public static function mul(mixed $a, mixed $b, int $scale = 2): string
    {
        return self::round(bcmul((string) $a, (string) $b, $scale + 4), $scale);
    }

    public static function num(mixed $value): float
    {
        return (float) self::round($value);
    }

    public static function cmp(mixed $a, mixed $b): int
    {
        return bccomp(self::round($a), self::round($b), 2);
    }
}
