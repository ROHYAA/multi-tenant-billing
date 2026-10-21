<?php

use App\Support\SpaHtml;
use Illuminate\Support\Facades\Route;

Route::get('/', function () {
    return SpaHtml::response() ?? view('welcome');
});

Route::fallback(function () {
    if (request()->is('api/*') || request()->is('up') || request()->is('storage/*')) {
        abort(404);
    }

    return SpaHtml::response() ?? abort(404);
});
