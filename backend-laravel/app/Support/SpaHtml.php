<?php

namespace App\Support;

use Illuminate\Support\Facades\File;
use Symfony\Component\HttpFoundation\Response;

class SpaHtml
{
    public static function response(): ?Response
    {
        $spa = public_path('spa/index.html');
        if (! is_file($spa)) {
            return null;
        }

        return response(File::get($spa), 200, [
            'Content-Type' => 'text/html; charset=UTF-8',
            'Cache-Control' => 'no-cache',
        ]);
    }
}
