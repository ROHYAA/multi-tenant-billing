<?php

namespace App\Support;

/**
 * Minimal uncompressed PDF 1.4 writer (Type1 Helvetica family). No GD/composer.
 * Text is stored as literal strings so tests can assert invoice numbers.
 * The rupee sign is not in the standard fonts, so "₹" is drawn as a vector glyph.
 */
class SimplePdf
{
    public const NAVY = [11, 44, 82];

    public const GOLD = [232, 185, 94];

    public const MUTED = [90, 102, 118];

    public const ROW = [245, 248, 252];

    private const RUPEE = "\u{20B9}";

    /** Helvetica advance widths (1/1000 em) for ASCII 32..126. */
    private const W_REGULAR = [
        278, 278, 355, 556, 556, 889, 667, 191, 333, 333, 389, 584, 278, 333, 278, 278,
        556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 278, 278, 584, 584, 584, 556,
        1015, 667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667, 556, 833, 722, 778,
        667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 278, 278, 278, 469, 556,
        333, 556, 556, 500, 556, 556, 278, 556, 556, 222, 222, 500, 222, 833, 556, 556,
        556, 556, 333, 500, 278, 556, 500, 722, 500, 500, 500, 334, 260, 334, 584,
    ];

    /** Helvetica-Bold advance widths (1/1000 em) for ASCII 32..126. */
    private const W_BOLD = [
        278, 333, 474, 556, 556, 889, 722, 238, 333, 333, 389, 584, 278, 333, 278, 278,
        556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 333, 333, 584, 584, 584, 611,
        975, 722, 722, 722, 722, 667, 611, 778, 722, 278, 556, 722, 611, 833, 722, 778,
        667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 333, 278, 333, 584, 556,
        333, 556, 611, 556, 611, 556, 333, 611, 611, 278, 278, 556, 278, 889, 611, 611,
        611, 611, 389, 556, 333, 611, 556, 778, 556, 556, 500, 389, 280, 389, 584,
    ];

    private const FONTS = ['R' => '/F1', 'B' => '/F2', 'I' => '/F3', 'BI' => '/F4'];

    private float $pageW;

    private float $pageH;

    private float $margin;

    private float $y;

    private string $content = '';

    private float $bottom = 0.0;

    /** @var list<string> finished page content streams */
    private array $pages = [];

    /** @var array{0:int,1:int,2:int} */
    private array $ink = [0, 0, 0];

    /** @var list<array{name:string,w:int,h:int,data:string}> */
    private array $images = [];

    public function __construct(float $pageW, float $pageH, float $margin = 36)
    {
        $this->pageW = $pageW;
        $this->pageH = $pageH;
        $this->margin = $margin;
        $this->y = $pageH - $margin;
    }

    public function pageWidth(): float
    {
        return $this->pageW;
    }

    public function pageHeight(): float
    {
        return $this->pageH;
    }

    public function pageSize(): array
    {
        return [$this->pageW, $this->pageH];
    }

    public function margin(): float
    {
        return $this->margin;
    }

    public function innerWidth(): float
    {
        return $this->pageW - (2 * $this->margin);
    }

    public function y(): float
    {
        return $this->y;
    }

    public function setY(float $y): void
    {
        $this->y = $y;
    }

    /**
     * Finish the current page and start drawing on a fresh one.
     */
    public function newPage(): void
    {
        $this->pages[] = $this->content;
        $this->content = '';
        $this->y = $this->pageH - $this->margin;
    }

    /**
     * Raise the page's bottom edge (MediaBox) to y. Used to fit receipt rolls to their content.
     */
    public function trimBottom(float $y): void
    {
        $this->bottom = max(0.0, min($y, $this->pageH - 1));
    }

    public function pageCount(): int
    {
        return count($this->pages) + 1;
    }

    public function rgb(int $r, int $g, int $b): void
    {
        $this->ink = [$r, $g, $b];
    }

    public function gray(float $g): void
    {
        $v = (int) round(max(0, min(1, $g)) * 255);
        $this->ink = [$v, $v, $v];
    }

    public function black(): void
    {
        $this->ink = [0, 0, 0];
    }

    public function white(): void
    {
        $this->ink = [255, 255, 255];
    }

    public function fillBox(float $x, float $y, float $w, float $h, array $rgb): void
    {
        $this->content .= sprintf("%s %.2f %.2f %.2f %.2f re f\n", $this->fillOp($rgb), $x, $y, $w, $h);
    }

    /**
     * Rounded rectangle; (x, y) is the bottom-left corner.
     *
     * @param  array{0:int,1:int,2:int}|null  $fill
     * @param  array{0:int,1:int,2:int}|null  $stroke
     */
    public function roundRect(float $x, float $y, float $w, float $h, float $r, ?array $fill, ?array $stroke = null, float $lineWidth = 0.8): void
    {
        $r = max(0, min($r, $w / 2, $h / 2));
        $k = $r * 0.5522847498;
        $path = sprintf(
            '%.2f %.2f m %.2f %.2f l %.2f %.2f %.2f %.2f %.2f %.2f c %.2f %.2f l %.2f %.2f %.2f %.2f %.2f %.2f c '
            .'%.2f %.2f l %.2f %.2f %.2f %.2f %.2f %.2f c %.2f %.2f l %.2f %.2f %.2f %.2f %.2f %.2f c h',
            $x + $r, $y,
            $x + $w - $r, $y,
            $x + $w - $r + $k, $y, $x + $w, $y + $r - $k, $x + $w, $y + $r,
            $x + $w, $y + $h - $r,
            $x + $w, $y + $h - $r + $k, $x + $w - $r + $k, $y + $h, $x + $w - $r, $y + $h,
            $x + $r, $y + $h,
            $x + $r - $k, $y + $h, $x, $y + $h - $r + $k, $x, $y + $h - $r,
            $x, $y + $r,
            $x, $y + $r - $k, $x + $r - $k, $y, $x + $r, $y
        );
        $this->paintPath($path, $fill, $stroke, $lineWidth);
    }

    /**
     * @param  array{0:int,1:int,2:int}  $rgb
     */
    public function strokeRect(float $x, float $y, float $w, float $h, array $rgb, float $lineWidth = 0.8): void
    {
        $this->paintPath(sprintf('%.2f %.2f %.2f %.2f re', $x, $y, $w, $h), null, $rgb, $lineWidth);
    }

    /**
     * @param  array{0:int,1:int,2:int}  $rgb
     */
    public function strokeLine(float $x1, float $y1, float $x2, float $y2, array $rgb, float $lineWidth = 0.8): void
    {
        $this->paintPath(sprintf('%.2f %.2f m %.2f %.2f l', $x1, $y1, $x2, $y2), null, $rgb, $lineWidth);
    }

    /**
     * Open polyline through the given points.
     *
     * @param  list<array{0:float,1:float}>  $points
     * @param  array{0:int,1:int,2:int}  $rgb
     */
    public function strokePolyline(array $points, array $rgb, float $lineWidth = 0.8): void
    {
        if (count($points) < 2) {
            return;
        }
        $path = '';
        foreach ($points as $i => [$px, $py]) {
            $path .= sprintf('%.2f %.2f %s ', $px, $py, $i === 0 ? 'm' : 'l');
        }
        $this->paintPath(trim($path), null, $rgb, $lineWidth);
    }

    /**
     * @param  array{0:int,1:int,2:int}  $rgb
     */
    public function strokeCircle(float $cx, float $cy, float $r, array $rgb, float $lineWidth = 0.8): void
    {
        $this->paintPath($this->circlePath($cx, $cy, $r), null, $rgb, $lineWidth);
    }

    /**
     * Single line of text at an absolute baseline. Does not move the cursor.
     * Style: R, B, I or BI. Align is relative to the box [x, x + width].
     *
     * @param  array{0:int,1:int,2:int}  $rgb
     */
    public function text(string $text, float $x, float $baseline, float $size, string $style = 'R', array $rgb = [0, 0, 0], string $align = 'L', float $width = 0): float
    {
        $saved = $this->ink;
        $this->ink = $rgb;
        $drawn = $this->paintAt($text, $size, $style, $align, $x, $width, $baseline);
        $this->ink = $saved;

        return $drawn;
    }

    public function measure(string $text, float $size, bool $bold = false): float
    {
        return $this->textWidth($text, $size, $bold);
    }

    /**
     * Word-wrap into lines that fit maxWidth.
     *
     * @return list<string>
     */
    public function lines(string $text, float $size, float $maxWidth, bool $bold = false): array
    {
        return $this->wrapWords($text, $size, $maxWidth, $bold);
    }

    public function truncate(string $text, float $size, float $maxWidth, bool $bold = false): string
    {
        return $this->fit($text, $size, $maxWidth, $bold);
    }

    public function line(string $text, float $size = 10, bool $bold = false, string $align = 'L'): void
    {
        $this->wrap($text, $size, $bold, $align);
    }

    public function wrap(string $text, float $size = 10, bool $bold = false, string $align = 'L', ?float $maxWidth = null, ?float $x = null): void
    {
        $maxWidth ??= $this->innerWidth();
        $x ??= $this->margin;
        foreach ($this->wrapWords($text, $size, $maxWidth, $bold) as $row) {
            $this->paint($row, $size, $bold, $align, $x, $maxWidth);
        }
    }

    /**
     * @param  list<array{text:string,width:float,align?:string,bold?:bool}>  $cols
     * @param  array{0:int,1:int,2:int}|null  $fill
     * @param  array{0:int,1:int,2:int}|null  $ink
     */
    public function columns(array $cols, float $size, ?array $fill = null, ?array $ink = null): void
    {
        $rowH = $size * 1.85;
        $this->ensure($rowH + 2);
        $boxY = $this->y - ($size * 0.5);
        if ($fill) {
            $this->fillBox($this->margin, $boxY, $this->innerWidth(), $rowH, $fill);
        }
        if ($ink) {
            $this->rgb($ink[0], $ink[1], $ink[2]);
        }
        $x = $this->margin;
        foreach ($cols as $col) {
            $w = (float) $col['width'];
            $align = $col['align'] ?? 'L';
            $bold = (bool) ($col['bold'] ?? false);
            $cell = $this->fit((string) $col['text'], $size, $w - 6, $bold);
            $this->paint($cell, $size, $bold, $align, $x + 3, $w - 6, false);
            $x += $w;
        }
        if ($ink) {
            $this->black();
        }
        $this->y -= $rowH;
    }

    /**
     * @param  list<array{text:string,width:float,align?:string,bold?:bool,fill?:array{0:int,1:int,2:int},ink?:array{0:int,1:int,2:int}}>  $cols
     */
    public function coloredRow(array $cols, float $size, float $rowScale = 2.05): void
    {
        $rowH = $size * $rowScale;
        $this->ensure($rowH + 2);
        $top = $this->y;
        $bottom = $top - $rowH;
        $textY = $bottom + (($rowH - $size) / 2);
        $x = $this->margin;
        foreach ($cols as $col) {
            $w = (float) $col['width'];
            if (! empty($col['fill'])) {
                $this->fillBox($x, $bottom, $w, $rowH, $col['fill']);
            }
            $x += $w;
        }
        $this->y = $textY;
        $x = $this->margin;
        foreach ($cols as $col) {
            $w = (float) $col['width'];
            $ink = $col['ink'] ?? [20, 20, 20];
            $this->rgb($ink[0], $ink[1], $ink[2]);
            $bold = (bool) ($col['bold'] ?? false);
            $cell = $this->fit((string) $col['text'], $size, $w - 8, $bold);
            $this->paint($cell, $size, $bold, $col['align'] ?? 'L', $x + 4, $w - 8, false);
            $x += $w;
        }
        $this->black();
        $this->y = $bottom;
    }

    /**
     * Label left / value right in a fixed box. Cursor y is the top of the row.
     *
     * @param  array{0:int,1:int,2:int}|null  $fill
     * @param  array{0:int,1:int,2:int}|null  $labelRgb
     * @param  array{0:int,1:int,2:int}|null  $valueRgb
     */
    public function pairAt(
        float $x,
        float $w,
        string $label,
        string $value,
        float $size,
        bool $valueBold = true,
        ?array $fill = null,
        ?array $labelRgb = null,
        ?array $valueRgb = null,
        float $rowScale = 1.75
    ): void {
        $rowH = $size * $rowScale;
        $this->ensure($rowH + 2);
        $top = $this->y;
        $bottom = $top - $rowH;
        if ($fill) {
            $this->fillBox($x, $bottom, $w, $rowH, $fill);
        }
        $this->y = $bottom + (($rowH - $size) / 2);
        $pad = 8;
        $labelRgb ??= self::MUTED;
        $valueRgb ??= self::NAVY;
        $this->rgb($labelRgb[0], $labelRgb[1], $labelRgb[2]);
        $this->paint($this->fit($label, $size, $w * 0.55 - $pad, (bool) $fill), $size, (bool) $fill, 'L', $x + $pad, $w * 0.55 - $pad, false);
        $this->rgb($valueRgb[0], $valueRgb[1], $valueRgb[2]);
        $this->paint($this->fit($value, $size, $w * 0.45 - $pad, $valueBold), $size, $valueBold, 'R', $x + $w * 0.52, $w * 0.48 - $pad, false);
        $this->black();
        $this->y = $bottom;
    }

    public function fillCircle(float $cx, float $cy, float $r, array $rgb): void
    {
        $this->content .= sprintf("%s %s f\n", $this->fillOp($rgb), $this->circlePath($cx, $cy, $r));
    }

    public function pair(string $label, string $value, float $size = 10, bool $valueBold = false, bool $light = false): void
    {
        $this->ensure($size * 1.55);
        $w = $this->innerWidth();
        if ($light) {
            $this->white();
        } else {
            $this->rgb(self::MUTED[0], self::MUTED[1], self::MUTED[2]);
        }
        $this->paint($label, $size, false, 'L', $this->margin, $w * 0.48, false);
        if ($light) {
            $this->white();
        } else {
            $this->black();
        }
        $this->paint($value, $size, $valueBold, 'R', $this->margin + $w * 0.48, $w * 0.52);
        $this->black();
    }

    public function spacer(float $pt = 8): void
    {
        $this->y -= $pt;
    }

    /**
     * @return array{w:float,h:float}|null
     */
    public function placeImage(string $bytes, float $x, float $topY, float $maxW, float $maxH, bool $center = false): ?array
    {
        $jpeg = $this->toJpeg($bytes);
        if ($jpeg === null) {
            return null;
        }
        $scale = min($maxW / max(1, $jpeg['w']), $maxH / max(1, $jpeg['h']));
        $drawW = $jpeg['w'] * $scale;
        $drawH = $jpeg['h'] * $scale;
        if ($center) {
            $x += ($maxW - $drawW) / 2;
            $topY -= ($maxH - $drawH) / 2;
        }
        $name = $this->imageName($jpeg);
        $this->content .= sprintf(
            "q %.2f 0 0 %.2f %.2f %.2f cm /%s Do Q\n",
            $drawW,
            $drawH,
            $x,
            $topY - $drawH,
            $name
        );

        return ['w' => $drawW, 'h' => $drawH];
    }

    public function placeImageInCircle(string $bytes, float $cx, float $cy, float $r): bool
    {
        $jpeg = $this->toJpeg($bytes);
        if ($jpeg === null) {
            return false;
        }
        $d = $r * 2;
        $scale = max($d / max(1, $jpeg['w']), $d / max(1, $jpeg['h']));
        $drawW = $jpeg['w'] * $scale;
        $drawH = $jpeg['h'] * $scale;
        $name = $this->imageName($jpeg);
        $this->content .= 'q '.$this->circlePath($cx, $cy, $r).' W n ';
        $this->content .= sprintf(
            'q %.2f 0 0 %.2f %.2f %.2f cm /%s Do Q Q'."\n",
            $drawW,
            $drawH,
            $cx - ($drawW / 2),
            $cy - ($drawH / 2),
            $name
        );

        return true;
    }

    /**
     * Registers a JPEG XObject once per distinct image. Identical images (e.g. the same
     * signature drawn twice) share one stream — Android's print spooler garbles duplicates.
     *
     * @param  array{data:string,w:int,h:int}  $jpeg
     */
    private function imageName(array $jpeg): string
    {
        $hash = md5($jpeg['data']);
        foreach ($this->images as $image) {
            if (($image['hash'] ?? null) === $hash) {
                return $image['name'];
            }
        }
        $name = 'Im'.(count($this->images) + 1);
        $this->images[] = ['name' => $name, 'w' => $jpeg['w'], 'h' => $jpeg['h'], 'data' => $jpeg['data'], 'hash' => $hash];

        return $name;
    }

    private function circlePath(float $cx, float $cy, float $r): string
    {
        $k = $r * 0.5522847498;

        return sprintf(
            '%.2f %.2f m %.2f %.2f %.2f %.2f %.2f %.2f c %.2f %.2f %.2f %.2f %.2f %.2f c %.2f %.2f %.2f %.2f %.2f %.2f c %.2f %.2f %.2f %.2f %.2f %.2f c',
            $cx,
            $cy + $r,
            $cx + $k,
            $cy + $r,
            $cx + $r,
            $cy + $k,
            $cx + $r,
            $cy,
            $cx + $r,
            $cy - $k,
            $cx + $k,
            $cy - $r,
            $cx,
            $cy - $r,
            $cx - $k,
            $cy - $r,
            $cx - $r,
            $cy - $k,
            $cx - $r,
            $cy,
            $cx - $r,
            $cy + $k,
            $cx - $k,
            $cy + $r,
            $cx,
            $cy + $r
        );
    }

    public function image(string $bytes, float $maxW, float $maxH, string $align = 'C'): bool
    {
        $this->ensure($maxH + 8);
        $x = $this->margin;
        if ($align === 'C') {
            $x = $this->margin + (($this->innerWidth() - $maxW) / 2);
        } elseif ($align === 'R') {
            $x = $this->pageW - $this->margin - $maxW;
        }
        $placed = $this->placeImage($bytes, $x, $this->y, $maxW, $maxH);
        if ($placed === null) {
            return false;
        }
        $this->y -= $placed['h'] + 10;

        return true;
    }

    public function rule(?array $rgb = null): void
    {
        $this->ensure(12);
        $y = $this->y + 2;
        $rgb ??= self::GOLD;
        $this->strokeLine($this->margin, $y, $this->pageW - $this->margin, $y, $rgb, 1.4);
        $this->y -= 10;
    }

    public function bytes(): string
    {
        $streams = [...$this->pages, $this->content];
        $w = sprintf('%.2f', $this->pageW);
        $h = sprintf('%.2f', $this->pageH);
        $bottom = sprintf('%.2f', $this->bottom);
        $xobjects = '';
        $enc = '/Encoding /WinAnsiEncoding';
        $objects = [
            1 => '<< /Type /Catalog /Pages 2 0 R >>',
            3 => "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica {$enc} >>",
            4 => "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold {$enc} >>",
            5 => "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Oblique {$enc} >>",
            6 => "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-BoldOblique {$enc} >>",
        ];
        $nextId = 7;
        foreach ($this->images as $image) {
            $id = $nextId++;
            $xobjects .= '/'.$image['name'].' '.$id.' 0 R ';
            $imgLen = strlen($image['data']);
            $objects[$id] = "<< /Type /XObject /Subtype /Image /Width {$image['w']} /Height {$image['h']} /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length {$imgLen} >>\nstream\n{$image['data']}\nendstream";
        }
        $xobjDict = $xobjects !== '' ? "/XObject << {$xobjects}>>" : '';
        $kids = [];
        foreach ($streams as $stream) {
            $pageId = $nextId++;
            $contentId = $nextId++;
            $kids[] = $pageId.' 0 R';
            $len = strlen($stream);
            $objects[$contentId] = "<< /Length {$len} >>\nstream\n{$stream}endstream";
            $objects[$pageId] = "<< /Type /Page /Parent 2 0 R /MediaBox [0 {$bottom} {$w} {$h}] /Contents {$contentId} 0 R /Resources << /Font << /F1 3 0 R /F2 4 0 R /F3 5 0 R /F4 6 0 R >> {$xobjDict} >> >>";
        }
        $objects[2] = '<< /Type /Pages /Kids ['.implode(' ', $kids).'] /Count '.count($kids).' >>';
        ksort($objects);
        $out = "%PDF-1.4\n";
        $offsets = [0];
        $maxId = 0;
        foreach ($objects as $id => $body) {
            $offsets[$id] = strlen($out);
            $out .= $id." 0 obj\n".$body."\nendobj\n";
            $maxId = max($maxId, $id);
        }
        $xref = strlen($out);
        $size = $maxId + 1;
        $out .= "xref\n0 {$size}\n";
        $out .= "0000000000 65535 f \n";
        for ($i = 1; $i <= $maxId; $i++) {
            $out .= sprintf("%010d 00000 n \n", $offsets[$i] ?? 0);
        }
        $out .= "trailer << /Size {$size} /Root 1 0 R >>\nstartxref\n{$xref}\n%%EOF\n";

        return $out;
    }

    private function paint(string $text, float $size, bool $bold, string $align, float $x, float $maxWidth, bool $advance = true): void
    {
        if ($advance) {
            $this->ensure($size * 1.45);
        }
        $this->paintAt($text, $size, $bold ? 'B' : 'R', $align, $x, $maxWidth, $this->y);
        if ($advance) {
            $this->y -= $size * 1.45;
        }
    }

    /**
     * Draw text (with vector rupee glyphs) and return its width.
     */
    private function paintAt(string $text, float $size, string $style, string $align, float $x, float $maxWidth, float $baseline): float
    {
        $bold = str_contains($style, 'B');
        $font = self::FONTS[$style] ?? '/F1';
        $width = $this->textWidth($text, $size, $bold);
        $tx = $x;
        if ($align === 'R') {
            $tx = $x + $maxWidth - $width;
        } elseif ($align === 'C') {
            $tx = $x + (($maxWidth - $width) / 2);
        }
        $parts = explode(self::RUPEE, $text);
        foreach ($parts as $i => $part) {
            if ($part !== '') {
                $this->content .= sprintf(
                    "%s BT %s %.2f Tf %.2f %.2f Td (%s) Tj ET\n",
                    $this->fillOp($this->ink),
                    $font,
                    $size,
                    $tx,
                    $baseline,
                    $this->escape($part)
                );
                $tx += $this->textWidth($part, $size, $bold);
            }
            if ($i < count($parts) - 1) {
                $this->rupeeGlyph($tx, $baseline, $size, $bold);
                $tx += 0.556 * $size;
            }
        }

        return $width;
    }

    private function rupeeGlyph(float $x, float $y, float $s, bool $bold): void
    {
        $lw = $s * ($bold ? 0.085 : 0.065);
        $l = $x + 0.08 * $s;
        $r = $x + 0.50 * $s;
        $top = $y + 0.70 * $s;
        $mid = $y + 0.53 * $s;
        $bowlEnd = $y + 0.37 * $s;
        $stem = $x + 0.20 * $s;
        $path = sprintf(
            '%.2f %.2f m %.2f %.2f l %.2f %.2f m %.2f %.2f l '
            .'%.2f %.2f m %.2f %.2f %.2f %.2f %.2f %.2f c %.2f %.2f l %.2f %.2f l',
            $l, $top, $r, $top,
            $l, $mid, $r, $mid,
            $stem, $top,
            $x + 0.50 * $s, $top, $x + 0.50 * $s, $bowlEnd, $stem, $bowlEnd,
            $l, $bowlEnd,
            $x + 0.46 * $s, $y
        );
        $this->content .= sprintf(
            "q %s %.2f w 1 J 1 j %s S Q\n",
            $this->strokeOp($this->ink),
            $lw,
            $path
        );
    }

    /**
     * @param  array{0:int,1:int,2:int}|null  $fill
     * @param  array{0:int,1:int,2:int}|null  $stroke
     */
    private function paintPath(string $path, ?array $fill, ?array $stroke, float $lineWidth): void
    {
        if (! $fill && ! $stroke) {
            return;
        }
        $ops = 'q ';
        if ($fill) {
            $ops .= $this->fillOp($fill).' ';
        }
        if ($stroke) {
            $ops .= $this->strokeOp($stroke).sprintf(' %.2f w 1 J 1 j ', $lineWidth);
        }
        $paint = $fill && $stroke ? 'B' : ($fill ? 'f' : 'S');
        $this->content .= $ops.$path.' '.$paint." Q\n";
    }

    /**
     * @param  array{0:int,1:int,2:int}  $rgb
     */
    private function fillOp(array $rgb): string
    {
        return sprintf('%.3f %.3f %.3f rg', $rgb[0] / 255, $rgb[1] / 255, $rgb[2] / 255);
    }

    /**
     * @param  array{0:int,1:int,2:int}  $rgb
     */
    private function strokeOp(array $rgb): string
    {
        return sprintf('%.3f %.3f %.3f RG', $rgb[0] / 255, $rgb[1] / 255, $rgb[2] / 255);
    }

    private function ensure(float $needed): void
    {
        if ($this->y < $this->margin + $needed) {
            $this->y = $this->pageH - $this->margin;
        }
    }

    /**
     * @return list<string>
     */
    private function wrapWords(string $text, float $size, float $maxWidth, bool $bold = false): array
    {
        $text = trim(preg_replace('/\s+/u', ' ', $text) ?? $text);
        if ($text === '') {
            return [''];
        }
        $words = explode(' ', $text);
        $lines = [];
        $current = '';
        foreach ($words as $word) {
            $try = $current === '' ? $word : $current.' '.$word;
            if ($this->textWidth($try, $size, $bold) <= $maxWidth) {
                $current = $try;
                continue;
            }
            if ($current !== '') {
                $lines[] = $current;
            }
            $current = $this->fit($word, $size, $maxWidth, $bold);
        }
        if ($current !== '') {
            $lines[] = $current;
        }

        return $lines ?: [''];
    }

    private function fit(string $text, float $size, float $maxWidth, bool $bold = false): string
    {
        if ($this->textWidth($text, $size, $bold) <= $maxWidth) {
            return $text;
        }
        $out = $text;
        while (mb_strlen($out) > 1 && $this->textWidth($out.'...', $size, $bold) > $maxWidth) {
            $out = mb_substr($out, 0, -1);
        }

        return rtrim($out).'...';
    }

    private function textWidth(string $text, float $size, bool $bold = false): float
    {
        $table = $bold ? self::W_BOLD : self::W_REGULAR;
        $units = 0;
        foreach (mb_str_split($text) as $char) {
            if ($char === self::RUPEE) {
                $units += 556;
                continue;
            }
            $code = strlen($char) === 1 ? ord($char) : 0;
            $units += ($code >= 32 && $code <= 126) ? $table[$code - 32] : 556;
        }

        return $units * $size / 1000;
    }

    /**
     * Always resample so PDF pixel size matches the box we draw.
     *
     * @return array{data:string,w:int,h:int}|null
     */
    private function toJpeg(string $bytes): ?array
    {
        if (! function_exists('imagecreatefromstring') || ! function_exists('imagejpeg')) {
            $info = @getimagesizefromstring($bytes);
            if (! is_array($info) || ($info[2] ?? 0) !== IMAGETYPE_JPEG) {
                return null;
            }

            return ['data' => $bytes, 'w' => (int) $info[0], 'h' => (int) $info[1]];
        }
        $src = @imagecreatefromstring($bytes);
        if ($src === false) {
            return null;
        }
        $w = imagesx($src);
        $h = imagesy($src);
        $max = 360;
        $nw = $w;
        $nh = $h;
        if ($w > $max || $h > $max) {
            $scale = min($max / $w, $max / $h);
            $nw = max(1, (int) round($w * $scale));
            $nh = max(1, (int) round($h * $scale));
        }
        $dst = imagecreatetruecolor($nw, $nh);
        $white = imagecolorallocate($dst, 255, 255, 255);
        imagefilledrectangle($dst, 0, 0, $nw, $nh, $white);
        imagecopyresampled($dst, $src, 0, 0, 0, 0, $nw, $nh, $w, $h);
        imagedestroy($src);
        ob_start();
        imagejpeg($dst, null, 82);
        $jpeg = ob_get_clean();
        imagedestroy($dst);
        if (! is_string($jpeg) || $jpeg === '') {
            return null;
        }

        return ['data' => $jpeg, 'w' => $nw, 'h' => $nh];
    }

    private function escape(string $text): string
    {
        $latin = @iconv('UTF-8', 'Windows-1252//TRANSLIT//IGNORE', $text);
        if ($latin === false) {
            $latin = preg_replace('/[^\x20-\x7E]/', '?', $text) ?? $text;
        }

        return str_replace(['\\', '(', ')'], ['\\\\', '\\(', '\\)'], $latin);
    }
}
